package dev.deepseek.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.deepseek.mobile.harness.Capability
import dev.deepseek.mobile.harness.PermissionEffect
import dev.deepseek.mobile.terminal.*
import dev.deepseek.mobile.workflow.*
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/** Owns the packaged JavaScript kernel while the user switches between apps. */
class AgentService : Service() {
    companion object {
        @Volatile var current: AgentService? = null
        const val ORIGIN = "https://appassets.androidplatform.net"
        private val reviewFollowUp =
            Regex(
                "(?:继续(?:吧|刚才的任务)?|接着做|(?:重启会话)?再试(?:试|一次|一遍)?|重试(?:一下)?|retry|try again|continue)[。.!！\\s]*",
                RegexOption.IGNORE_CASE,
            )
    }

    lateinit var webView: WebView
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val requests = ConcurrentHashMap<String, Job>()
    private val connections = ConcurrentHashMap<String, HttpURLConnection>()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var active = false
    @Volatile private var generation = 0L
    private var question: CompletableDeferred<String>? = null
    private lateinit var keys: KeyStore
    @Volatile private var credentialRevision = 0L
    private var balanceAttemptAt = -60000L
    private lateinit var floating: FloatingControl
    private var sandboxProtection: SandboxBackgroundProtection? = null
    private var approval: CompletableDeferred<Boolean>? = null
    private val approvalLock = Mutex()
    private val startHookLock = Mutex()
    private val startHookContexts = mutableMapOf<String, String>()
    private val phoneLock = Mutex()
    private lateinit var browser: AgentBrowser
    private lateinit var changes: ChangeTracker
    lateinit var terminal: TerminalManager
        private set

    lateinit var memories: MemoryStore
        private set

    private var terminalApproval: CompletableDeferred<String>? = null
    private var wake: android.os.PowerManager.WakeLock? = null
    private var keepAliveJob: Job? = null
    private var permissionPromptTask = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        current = this
        keys = KeyStore(this)
        AssistantState.initialize(this)
        terminal = TerminalManager(this, ::reviewOperation, ::approveTerminal)
        terminal.connections.restoreSelected(
            dev.deepseek.mobile.terminal.TerminalLevel.of(AssistantState.current.terminalLevel),
            AssistantState.current.terminalEnabled,
        )
        memories = MemoryStore(this)
        browser = AgentBrowser(this)
        changes = ChangeTracker(this)
        WhaleSounds.initialize(this)
        floating = FloatingControl(this)
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel("agent", "手机助手运行状态", NotificationManager.IMPORTANCE_LOW),
            )
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(
                1,
                notification("准备就绪"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        else startForeground(1, notification("准备就绪"))
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel("events", "任务消息与确认", NotificationManager.IMPORTANCE_DEFAULT),
            )
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel("task-review", "任务审查与提问", NotificationManager.IMPORTANCE_HIGH),
            )
        sandboxProtection = SandboxBackgroundProtection(this, scope, terminal.connections)
        loadEngine()
    }

    private fun loadEngine() {
        AssistantState.ready = false
        webView =
            WebView(this).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.setSupportMultipleWindows(false)
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                isSaveEnabled = false
                importantForAutofill =
                    android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                addJavascriptInterface(Bridge(), "Android")
                webViewClient =
                    object : WebViewClient() {
                        override fun onRenderProcessGone(
                            view: WebView,
                            detail: android.webkit.RenderProcessGoneDetail,
                        ): Boolean {
                            restartEngine()
                            return true
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ) = true

                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest,
                        ): WebResourceResponse {
                            val uri = request.url
                            val file = uri.path?.removePrefix("/assets/") ?: ""
                            if (
                                uri.scheme != "https" ||
                                    uri.host != "appassets.androidplatform.net" ||
                                    !uri.path.orEmpty().startsWith("/assets/") ||
                                    file.contains("..") ||
                                    file.contains('/')
                            ) {
                                return WebResourceResponse(
                                    "text/plain",
                                    "UTF-8",
                                    403,
                                    "Forbidden",
                                    emptyMap(),
                                    ByteArrayInputStream(ByteArray(0)),
                                )
                            }
                            return try {
                                val mime =
                                    when {
                                        file.endsWith(".js") -> "application/javascript"
                                        file.endsWith(".css") -> "text/css"
                                        file.endsWith(".png") -> "image/png"
                                        else -> "text/html"
                                    }
                                WebResourceResponse(
                                    mime,
                                    "UTF-8",
                                    200,
                                    "OK",
                                    mapOf(
                                        "Content-Security-Policy" to
                                            "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'",
                                    ),
                                    assets.open(file),
                                )
                            } catch (error: java.io.IOException) {
                                WebResourceResponse(
                                    "text/plain",
                                    "UTF-8",
                                    404,
                                    "Not Found",
                                    emptyMap(),
                                    ByteArrayInputStream(ByteArray(0)),
                                )
                            }
                        }
                    }
                webChromeClient =
                    object : WebChromeClient() {
                        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                            // Log positions, never model prompts, API bodies, or credentials.
                            if (BuildConfig.DEBUG)
                                android.util.Log.d(
                                    "DeepSeekMobile",
                                    "JS ${message.messageLevel()} line ${message.lineNumber()}",
                                )
                            return true
                        }
                    }
                loadUrl("$ORIGIN/assets/index.html")
            }
    }

    /**
     * Rebuild a lost renderer, preserving history but never replaying interrupted phone actions.
     */
    fun restartEngine() {
        active = false
        generation++
        AssistantState.ready = false
        connections.values.forEach { it.disconnect() }
        connections.clear()
        requests.values.forEach { it.cancel() }
        approval?.cancel()
        terminalApproval?.cancel()
        question?.cancel()
        AssistantState.tasks
            .filter { it.status in listOf("queued", "running", "waiting", "stopping") }
            .forEach {
                it.status = "interrupted"
                it.progress = "引擎已重启，请重新发送"
            }
        AssistantState.running?.let { task ->
            AssistantState.sessions
                .find { it.id == task.sessionId }
                ?.messages
                ?.add(
                    ChatEntry("system", "消息引擎已重启，刚才的操作已中断，请重新发送。"),
                )
        }
        AssistantState.activeTaskId = ""
        AssistantState.queuePaused = false
        AssistantState.persist()
        releaseWake()
        webView.removeJavascriptInterface("Android")
        webView.destroy()
        loadEngine()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra("sessionId")?.let { AssistantState.select(it) }
        if (
            intent?.action in
                setOf(SandboxBackgroundProtection.Restart, SandboxBackgroundProtection.Stop)
        ) {
            sandboxProtection?.command(
                intent?.action.orEmpty(),
                intent?.getStringExtra("sandboxId").orEmpty(),
            )
        }
        refreshFloating()
        if (intent?.action == "STOP") {
            stopTask()
            emit("stopped", JSONObject())
        }
        if (intent?.action == "QUIT") {
            stopTask()
            stopSelf()
        }
        return if (intent?.action == "QUIT") START_NOT_STICKY else START_STICKY
    }

    private fun notification(text: String): Notification {
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val stop =
            PendingIntent.getService(
                this,
                1,
                Intent(this, AgentService::class.java).setAction("STOP"),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val quit =
            PendingIntent.getService(
                this,
                2,
                Intent(this, AgentService::class.java).setAction("QUIT"),
                PendingIntent.FLAG_IMMUTABLE,
            )
        return Notification.Builder(this, "agent")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("DSHA")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(active)
            .addAction(Notification.Action.Builder(null, "停止任务", stop).build())
            .addAction(Notification.Action.Builder(null, "退出服务", quit).build())
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(1, notification(text))
        WhaleWidget.updateAll(this)
    }

    fun stopTask() {
        WhaleCompanion.react("cancelled", "好，先停在这里。等你叫我。")
        AssistantState.queuePaused = true
        AssistantState.tasks
            .filter { it.status == "queued" }
            .forEach {
                it.status = "cancelled"
                it.progress = "已随停止操作取消"
            }
        AssistantState.running?.let {
            it.status = "stopping"
            it.progress = "正在停止"
        }
        AssistantState.persist()
        active = false
        generation++
        approval?.cancel()
        approval = null
        terminalApproval?.cancel()
        terminalApproval = null
        connections.values.forEach { it.disconnect() }
        connections.clear()
        question?.cancel()
        question = null
        // Cancel pending gestures and all task-owned RPCs before accepting another turn.
        requests.filterKeys { it.startsWith("task-") }.values.forEach { it.cancel() }
        releaseWake()
        main.post {
            updateNotification("任务已停止")
            webView.evaluateJavascript("window.stopFromNative?.()", null)
        }
    }

    private fun releaseWake() {
        keepAliveJob?.cancel()
        keepAliveJob = null
        wake?.let { if (it.isHeld) it.release() }
        wake = null
    }

    private fun keepTaskRunning(task: PhoneTask) {
        releaseWake()
        val manager = getSystemService(android.os.PowerManager::class.java)
        val level =
            TerminalLevel.of(
                runCatching { JSONObject(task.terminalConfig).optString("level") }.getOrDefault(""),
            )
        keepAliveJob = scope.launch {
            while (AssistantState.running === task) {
                if (ExperienceSettings.keepTasksAwake) {
                    val lock =
                        wake
                            ?: manager
                                .newWakeLock(
                                    android.os.PowerManager.PARTIAL_WAKE_LOCK,
                                    "$packageName:agent-task",
                                )
                                .also {
                                    it.setReferenceCounted(false)
                                    wake = it
                                }
                    lock.acquire(120_000L)
                } else {
                    wake?.let { if (it.isHeld) it.release() }
                    wake = null
                }
                if (
                    ExperienceSettings.privilegedKeepAlive &&
                        level.canManageOwnPermissions &&
                        terminal.connections.state(level).ready
                ) {
                    try {
                        terminal.connections.ownAppAction(level, "keep_awake")
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // This Android build may restrict the temporary allowlist to system UID.
                    }
                }
                delay(90_000)
            }
        }
    }

    private fun emit(type: String, value: Any) {
        val encoded = if (value is String) JSONObject.quote(value) else value.toString()
        main.post {
            webView.evaluateJavascript(
                "window.nativeEvent?.(${JSONObject.quote(type)},$encoded)",
                null,
            )
        }
    }

    private fun resolve(id: String, value: Any?, error: String? = null) {
        val envelope =
            JSONObject()
                .put("id", id)
                .put("ok", error == null)
                .put("value", value ?: JSONObject.NULL)
                .put("error", error ?: JSONObject.NULL)
        main.post { webView.evaluateJavascript("window.nativeResolve?.($envelope)", null) }
    }

    private fun assertActive(epoch: Long) {
        check(active && generation == epoch) { "任务已停止" }
    }

    private fun phone() = PhoneAccessibility.current ?: error("请先在系统设置中开启“DSHA 手机助手”无障碍服务")

    private val flclash by lazy { dev.deepseek.mobile.proxy.FlClashRuntime.get(this) }

    private val sandboxes by lazy { dev.deepseek.mobile.runtime.SandboxManager.get(this) }
    private val memoryOperations =
        setOf("memory_read", "memory_write", "memory_search", "context_search", "context_read")
    private val sandboxOperations =
        setOf(
            "terminal_status",
            "terminal_exec",
            "workspace_list",
            "workspace_read",
            "workspace_write",
            "publish_file",
        )

    private val workflowPolicy by lazy {
        WorkflowPolicy(assets.open("workflow-policy.json").bufferedReader().use { it.readText() })
    }
    private val workspaceFileLock = kotlinx.coroutines.sync.Mutex()

    // Service construction precedes Context attachment. Do not force the asset-backed policy
    // from a field initializer; the bridge first uses this set after onCreate/loadEngine.
    private val taskMethods by lazy {
        setOf(
            "complete",
            "image",
            "observe",
            "tap",
            "long_press",
            "swipe",
            "input_text",
            "global_action",
            "list_apps",
            "launch_app",
            "wait",
            "ask_user",
            "workspace_list",
            "workspace_read",
            "workspace_write",
            "terminal_exec",
            "terminal_elevate",
            "sandbox_list",
            "sandbox_connect",
            "toolchain_install",
            "mcp_list",
            "mcp_call",
            "worktree_list",
            "worktree_review",
            "worktree_merge",
            "worktree_discard",
            "terminal_status",
            "memory_search",
            "skill_read",
            "plugin_manager",
            "plugin_call",
            "proxy_control",
            "context_search",
            "context_read",
            "instructions_read",
            "memory_read",
            "memory_write",
            "attachment_read",
            "publish_file",
            "browser_open",
            "browser_snapshot",
            "browser_source",
            "browser_act",
            "browser_search",
            "browser_fetch",
        ) + workflowPolicy.tools
    }

    inner class Bridge {
        @JavascriptInterface
        fun ui(type: String, json: String) {
            main.post { onUiEvent(type, json) }
        }

        @JavascriptInterface
        fun request(id: String, method: String, json: String) {
            if (id.length > 100 || json.length > 12_000_000) {
                resolve(id, null, "请求过大")
                return
            }
            val epoch = generation
            val taskRequest = method in taskMethods || method == "image_recognition"
            val mapId = if (taskRequest) "task-$id" else id
            val job =
                scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                    try {
                        val args = JSONObject(json)
                        if (taskRequest) assertActive(epoch)
                        val value =
                            if (
                                method in
                                    setOf(
                                        "observe",
                                        "tap",
                                        "long_press",
                                        "swipe",
                                        "input_text",
                                        "global_action",
                                        "launch_app",
                                    )
                            )
                                phoneLock.withLock { dispatch(method, args, id, epoch) }
                            else dispatch(method, args, id, epoch)
                        if (taskRequest) assertActive(epoch)
                        resolve(id, value)
                    } catch (error: CancellationException) {
                        resolve(id, null, "操作已取消，任务已停止")
                    } catch (error: Exception) {
                        resolve(id, null, safeMessage(error))
                    } finally {
                        requests.remove(mapId)
                    }
                }
            requests[mapId] = job
            job.start()
        }

        @JavascriptInterface
        fun cancel(id: String) {
            connections.remove(id)?.disconnect()
            requests["task-$id"]?.cancel()
        }

        @JavascriptInterface
        fun emergencyStop() {
            main.post { stopTask() }
        }
    }

    private fun safeMessage(error: Exception): String {
        val value =
            when (error) {
                is java.net.SocketTimeoutException -> "DeepSeek 响应超时，请检查网络后重新发送"
                is java.net.UnknownHostException -> "无法连接 DeepSeek，请检查网络或 DNS 设置"
                is java.net.ConnectException -> "无法连接 DeepSeek，请检查网络后重试"
                else -> error.message ?: "操作失败，请重试"
            }
        val key =
            try {
                keys.read()
            } catch (_: Exception) {
                ""
            }
        return (if (key.isNotEmpty()) value.replace(key, "[密钥已隐藏]") else value)
            .replace(Regex("sk-[A-Za-z0-9_-]{8,}"), "[密钥已隐藏]")
            .take(600)
    }

    /** Native UI operations keep API credentials out of the JavaScript runtime. */
    fun perform(method: String, args: JSONObject = JSONObject(), done: (Any?) -> Unit = {}) {
        scope.launch {
            try {
                val result = dispatch(method, args, "ui-${java.util.UUID.randomUUID()}", generation)
                done(result)
                AssistantState.receive(
                    "status",
                    dispatch("status", JSONObject(), "ui-status", generation).toString(),
                )
            } catch (error: Exception) {
                toast(safeMessage(error))
                done(null)
            }
        }
    }

    fun toast(text: String) {
        android.widget.Toast.makeText(this, text.take(150), android.widget.Toast.LENGTH_SHORT)
            .show()
        AssistantState.error = text
    }

    fun newSession(projectId: String = AssistantState.current.projectId) {
        AssistantState.addSession(projectId)
    }

    private fun readBounded(input: java.io.InputStream, limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (output.size() < limit) {
            val n = input.read(buffer, 0, minOf(buffer.size, limit - output.size()))
            if (n < 0) break
            if (n > 0) output.write(buffer, 0, n)
        }
        return output.toByteArray()
    }

    enum class SendResult {
        QUEUED,
        MISSING_KEY,
        NOT_READY,
        EMPTY,
        UNSUPPORTED,
    }

    fun send(text: String): SendResult {
        val c = AssistantState.current
        if (!AssistantState.ready) {
            toast("引擎正在准备，请稍后重试")
            return SendResult.NOT_READY
        }
        // The key store is authoritative; a delayed UI status event must not reroute Send.
        AssistantState.configured = keys.configured()
        if (!AssistantState.configured) return SendResult.MISSING_KEY
        if (text.isBlank() && c.images.isEmpty() && c.files.isEmpty()) return SendResult.EMPTY
        // A new user turn is an explicit request to run. Old cancelled work stays cancelled.
        AssistantState.queuePaused = false
        c.updatedAt = System.currentTimeMillis()
        if (c.model == "deepseek-v4-pro" && c.images.isNotEmpty()) {
            toast("图片附件请切换至 Flash")
            return SendResult.UNSUPPORTED
        }
        val allowedSandboxes =
            sandboxes.records.value.filter { it.allows(c.id, c.projectId) }.map { it.id }.toSet()
        if (c.sandboxEnabled && allowedSandboxes.isEmpty()) {
            toast("请先在右侧工作区允许此会话访问至少一个 Sandbox")
            return SendResult.UNSUPPORTED
        }
        val t =
            PhoneTask(
                sessionId = c.id,
                text = text,
                images = c.images.toList(),
                model = c.model,
                effort = c.effort,
                childModel = c.childModel,
                childEffort = c.childEffort,
                access = c.access,
                phoneTools = c.phoneTools,
                fileTools = c.fileTools,
                workspace = c.workspace,
                terminalEnabled = c.terminalEnabled || c.sandboxEnabled,
                terminalConfig =
                    terminal.config
                        .load(if (c.sandboxEnabled) "sandbox" else c.terminalLevel)
                        .json()
                        .toString(),
                sandboxId =
                    if (c.sandboxEnabled)
                        c.sandboxId.takeIf { it in allowedSandboxes } ?: allowedSandboxes.first()
                    else "",
                sandboxIds = allowedSandboxes,
                sandboxOnly = c.sandboxEnabled,
                projectId = c.projectId,
                files = c.files.toList(),
                browserProvider = ExperienceSettings.browserMode.key,
                creatorMode = c.creatorMode,
                workMode = c.workMode,
            )
        t.workPlan = c.workPlan
        val firstMessage = c.messages.isEmpty()
        if (firstMessage) c.title = ConversationTitles.initial(text, t.images.isNotEmpty())
        c.messages.add(ChatEntry("user", text, t.images, t.files, t.id))
        c.files.clear()
        c.images.clear()
        c.draft = ""
        AssistantState.tasks.add(t)
        AssistantState.persist()
        pump()
        if (firstMessage) generateConversationTitle(c, t)
        return SendResult.QUEUED
    }

    private fun generateConversationTitle(conversation: Conversation, task: PhoneTask) {
        if (conversation.titleGenerationAttempted) return
        conversation.titleGenerationAttempted = true
        val revision = conversation.titleRevision
        val requestId = "title-${task.id}"
        AssistantState.persist()
        scope.launch {
            try {
                val reply =
                    complete(
                        ConversationTitles.request(task.text, task.images.size, task.files.size),
                        requestId,
                        null,
                        timeoutMillis = 25_000,
                    )
                ensureActive()
                // A response belongs to this session even if the user is now in another one.
                if (AssistantState.sessions.none { it === conversation }) return@launch
                reply.optJSONObject("usage")?.let { usage ->
                    val tokens =
                        usage
                            .optInt(
                                "total_tokens",
                                usage.optInt("prompt_tokens") + usage.optInt("completion_tokens"),
                            )
                            .coerceAtLeast(0)
                    conversation.usage =
                        conversation.usage.copy(
                            total = conversation.usage.total + tokens,
                            requests = conversation.usage.requests + 1,
                        )
                    AssistantState.totalTokens += tokens
                }
                val title = ConversationTitles.result(reply)
                ConversationTitles.apply(conversation, revision, title)
                AssistantState.persist()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // Keep the original message prefix; a title request never interrupts chat.
            } finally {
                connections.remove(requestId)?.disconnect()
            }
        }
    }

    fun resumeQueue() {
        AssistantState.queuePaused = false
        AssistantState.persist()
        pump()
    }

    fun cancelTask(id: String) {
        val t = AssistantState.tasks.find { it.id == id } ?: return
        if (t.id == AssistantState.activeTaskId) {
            stopTask()
        } else if (t.status == "queued") {
            t.status = "cancelled"
            t.progress = "已取消"
            AssistantState.persist()
            floating.update()
        }
    }

    fun deleteSession(id: String) {
        if (
            AssistantState.tasks.any {
                it.sessionId == id &&
                    it.status in listOf("queued", "running", "waiting", "stopping")
            }
        ) {
            toast("请先取消此会话的未结束任务")
            return
        }
        AssistantState.sessions.removeAll { it.id == id }
        AssistantState.tasks.removeAll { it.sessionId == id }
        File(filesDir, "events/$id.json").delete()
        if (AssistantState.sessions.isEmpty()) AssistantState.addSession()
        else if (AssistantState.selectedId == id)
            AssistantState.selectedId = AssistantState.sessions.first().id
        AssistantState.persist()
    }

    private fun pump() {
        if (!AssistantState.ready || AssistantState.busy || AssistantState.queuePaused) return
        val task = AssistantState.tasks.firstOrNull { it.status == "queued" } ?: return
        WhaleCompanion.resting = false
        WhaleCompanion.react("think", "让我想想，这件事怎么做。")
        AssistantState.activeTaskId = task.id
        task.status = "running"
        task.progress = "正在思考"
        keepTaskRunning(task)
        AssistantState.persist()
        floating.update()
        scope.launch {
            try {
                if (!task.sandboxOnly) changes.begin(task)
            } catch (error: Exception) {
                AssistantState.error = "文件改动基线读取失败：${safeMessage(error)}"
            }
            if (AssistantState.activeTaskId == task.id && task.status == "running")
                webView.evaluateJavascript(
                    "window.runTask(${task.json().put("browserTools", ExperienceSettings.browserEnabled).put("memoryEnabled", ExperienceSettings.memoryEnabled).put("maxAgents", ExperienceSettings.maxAgents).put("proxyTools", flclash.policy.state.value.enabled)})",
                    null,
                )
        }
    }

    fun onUiEvent(type: String, json: String) {
        when (type) {
            "task_event" -> {
                val e = JSONObject(json)
                val t = AssistantState.running ?: return
                if (e.optString("taskId") != t.id) return
                val c = AssistantState.sessions.find { it.id == t.sessionId } ?: return
                when (e.getString("type")) {
                    "assistant" -> settleStream(c.messages, "assistant", e.getString("value"), t.id)
                    "agent_event" -> TeamState.event(c.id, e.getJSONObject("value"))
                    "error" -> {
                        t.progress = "执行失败"
                        c.messages.add(ChatEntry("system", e.getString("value"), taskId = t.id))
                    }
                    "tool" -> {
                        val v = e.getJSONObject("value")
                        val name = v.getString("name")
                        val text = toolName(name)
                        t.progress = text
                        t.avatarPhase = AvatarPhase.WORKING
                        t.steps.add(text)
                        if (name != "terminal_exec")
                            c.messages.add(
                                ChatEntry(
                                    "tool",
                                    JSONObject(v.toString()).put("status", "running").toString(),
                                    taskId = t.id,
                                ),
                            )
                    }
                    "tool_result" -> {
                        val v = e.getJSONObject("value")
                        finishTool(c.messages, v)
                        if (t.steps.isNotEmpty())
                            t.steps[t.steps.lastIndex] += " · " + v.optString("summary")
                    }
                    "request" -> {
                        t.progress = "正在思考"
                        t.avatarPhase = AvatarPhase.THINKING
                    }
                    "usage" -> {
                        val v = e.getJSONObject("value")
                        val n =
                            v.optInt(
                                "total_tokens",
                                v.optInt("prompt_tokens") + v.optInt("completion_tokens"),
                            )
                        c.usage = c.usage.record(v, t.model)
                        t.tokens += n
                        AssistantState.totalTokens += n
                    }
                    "image_index_usage" -> {
                        val usage = e.getJSONObject("value")
                        val n =
                            usage
                                .optInt(
                                    "total_tokens",
                                    usage.optInt("prompt_tokens") +
                                        usage.optInt("completion_tokens"),
                                )
                                .coerceAtLeast(0)
                        // Background recognition contributes cost, not the active chat's context
                        // size.
                        c.usage =
                            c.usage.copy(total = c.usage.total + n, requests = c.usage.requests + 1)
                        t.tokens += n
                        AssistantState.totalTokens += n
                    }
                    "turn_end" -> {
                        val r = e.getJSONObject("value").optJSONObject("reason")
                        if (r?.optString("kind") == "error") {
                            val message = r.optJSONObject("error")?.optString("message").orEmpty()
                            c.messages.add(
                                ChatEntry(
                                    "system",
                                    if (message.isBlank()) "本轮未能完成，请检查网络与 API 连接后重新发送。"
                                    else safeMessage(IllegalStateException(message)),
                                    taskId = t.id,
                                ),
                            )
                        }
                    }
                }
                if (e.getString("type") != "request") AssistantState.persist()
                floating.update()
            }
            "task_done" -> {
                val e = JSONObject(json)
                val t = AssistantState.running ?: return
                if (e.optString("taskId") != t.id) return
                scope.launch {
                    try {
                        if (!t.sandboxOnly)
                            terminal.harness.extensions.hooks(
                                t,
                                dev.deepseek.mobile.harness.HookEvent.AfterAgentFinish,
                            ) { reason, detail ->
                                extensionApproval(t, generation, reason, detail)
                            }
                    } catch (error: Exception) {
                        AssistantState.error = "后置 Hook 未完成：${safeMessage(error)}"
                    }
                    try {
                        if (!t.sandboxOnly) changes.finish(t)
                        withContext(Dispatchers.IO) {
                            if (ExperienceSettings.memoryEnabled && !t.sandboxOnly)
                                terminal.harness.contexts.complete(
                                    t,
                                    if (e.optBoolean("failed")) "error" else "done",
                                )
                        }
                    } catch (error: Exception) {
                        AssistantState.error = "文件改动记录未能保存：${safeMessage(error)}"
                    }
                    finishTask(t, e)
                }
            }

            else -> {
                AssistantState.receive(type, json)
                if (type == "ready") {
                    refreshFloating()
                    refreshPetBalance()
                    pump()
                }
            }
        }
    }

    private fun finishTask(t: PhoneTask, e: JSONObject) {
        sandboxes.finish(t.id)
        startHookContexts.remove(t.id)
        if (AssistantState.activeTaskId != t.id) return
        AssistantState.sessions
            .firstOrNull { it.id == t.sessionId }
            ?.messages
            ?.let { messages ->
                messages.indices.forEach { i ->
                    if (messages[i].type.startsWith("stream_"))
                        messages[i] =
                            messages[i].copy(type = messages[i].type.removePrefix("stream_"))
                }
            }
        streamIndices.clear()
        TeamState.persist(this)
        scope.launch { runCatching { fetchBalance() } }
        t.status =
            if (t.status == "stopping") "cancelled"
            else if (e.optBoolean("failed")) "failed" else "done"
        t.progress =
            when (t.status) {
                "cancelled" -> "任务已中断"
                "failed" -> "执行失败"
                else -> "任务已完成"
            }
        t.question = ""
        t.approval = false
        active = false
        AssistantState.activeTaskId = ""
        releaseWake()
        AssistantState.persist()
        WhaleCompanion.react(
            if (t.status == "done") "done" else if (t.status == "failed") "error" else "cancelled",
            when (t.status) {
                "done" -> "这一轮结束啦，来看看结果。"
                "failed" -> "遇到点问题，我们一起看看？"
                else -> "停好啦，等你下一句话。"
            },
            if (t.status == "done") R.drawable.meme_cute else 0,
        )
        notifyEvent(t, t.progress)
        if (MainActivity.inForeground || !hasDevicePermission("notifications"))
            AssistantState.error = t.progress
        floating.update()
        pump()
    }

    fun stopChild(id: String) {
        if (!id.matches(Regex("[a-f0-9-]{36}"))) return
        webView.evaluateJavascript("window.stopChild?.(${JSONObject.quote(id)})", null)
    }

    private fun toolName(name: String) =
        mapOf(
            "observe" to "查看屏幕",
            "tap" to "点击",
            "long_press" to "长按",
            "swipe" to "滑动",
            "input_text" to "输入文字",
            "global_action" to "系统导航",
            "launch_app" to "打开应用",
            "list_apps" to "应用列表",
            "wait" to "等待界面",
            "ask_user" to "等待回复",
            "workspace_list" to "列出工作区",
            "workspace_read" to "读取文件",
            "workspace_write" to "写入文件",
            "workspace_search" to "搜索工作区",
            "workspace_context" to "检查项目规则",
            "workspace_edit" to "精确编辑文件",
            "work_plan" to "更新工作计划",
            "work_status" to "读取工作进度",
            "verification_run" to "运行验证",
            "terminal_exec" to "执行终端命令",
            "terminal_status" to "检查终端连接",
            "memory_read" to "读取共享记忆",
            "skill_read" to "读取已启用技能",
            "plugin_manager" to "管理 Agent 插件",
            "plugin_call" to "运行 Agent 插件",
            "proxy_control" to "管理内置 FlClash",
            "browser_search" to "官方搜索网页",
            "browser_fetch" to "官方抓取网页",
            "memory_write" to "更新共享记忆",
            "attachment_read" to "读取附件",
            "publish_file" to "创建文件预览",
        )[name] ?: name

    fun openTask() {
        val t = AssistantState.running
        t?.let { AssistantState.select(it.sessionId) }
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("sessionId", t?.sessionId),
        )
    }

    private fun notifyEvent(task: PhoneTask, text: String) {
        floating.update()
        val message =
            if (task.status == "waiting") {
                if (task.approval) "有一项操作等待确认" else "Agent 有一个问题"
            } else text
        val intent =
            Intent(this, MainActivity::class.java)
                .putExtra("sessionId", task.sessionId)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val open =
            PendingIntent.getActivity(
                this,
                task.id.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val channel =
            if (task.status == "waiting" && !MainActivity.inForeground && !floating.hasReviewWindow)
                "task-review"
            else "events"
        val builder =
            Notification.Builder(this, channel)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("DSHA")
                .setContentText(message)
                .setVisibility(Notification.VISIBILITY_SECRET)
                .setContentIntent(open)
                .setAutoCancel(true)
        val stop =
            PendingIntent.getService(
                this,
                1,
                Intent(this, AgentService::class.java).setAction("STOP"),
                PendingIntent.FLAG_IMMUTABLE,
            )
        if (task.status == "waiting")
            builder.addAction(Notification.Action.Builder(null, "中断", stop).build())
        try {
            getSystemService(NotificationManager::class.java)
                .notify(task.id.hashCode(), builder.build())
        } catch (_: SecurityException) {}
        updateNotification(message)
    }

    fun denyCurrentApproval() {
        if (AssistantState.running?.approval == true) approval?.complete(false)
    }

    fun answer(text: String, allow: Boolean = false) {
        val t = AssistantState.running ?: return
        if (t.terminalApproval) {
            terminalApproval?.complete(if (allow) "once" else "deny")
            return
        }
        if (t.approval) {
            if (!allow) {
                stopTask()
                return
            }
            approval?.complete(true)
        } else {
            question?.complete(text)
            AssistantState.sessions
                .find { it.id == t.sessionId }
                ?.messages
                ?.add(ChatEntry("user", text, taskId = t.id))
            AssistantState.persist()
        }
    }

    fun setFloating(value: Boolean) {
        getSharedPreferences("ui", 0).edit().putBoolean("floating", value).apply()
        if (value && !android.provider.Settings.canDrawOverlays(this)) {
            MainActivity.current?.requestOverlay()
            return
        }
        refreshFloating()
    }

    fun refreshFloating() {
        AssistantState.overlay = android.provider.Settings.canDrawOverlays(this)
        if (getSharedPreferences("ui", 0).getBoolean("floating", false) && AssistantState.overlay) {
            floating.show()
        } else floating.hide()
        floating.update()
    }

    internal suspend fun <T> withoutPhonePanels(block: suspend () -> T): T =
        floating.withoutPanels(block)

    private suspend fun reviewOperation(
        task: PhoneTask,
        operation: JSONObject,
        config: ReviewerConfig,
    ): ReviewVerdict {
        val epoch = generation
        val nonce = "review-${java.util.UUID.randomUUID()}"
        val input = JSONObject().put("user_task", task.text).put("operation", operation)
        if (reviewFollowUp.matches(task.text.trim())) {
            val history = AssistantState.sessions.firstOrNull { it.id == task.sessionId }?.messages
            val index = history?.indexOfFirst { it.type == "user" && it.taskId == task.id } ?: -1
            val previous =
                if (index > 0)
                    history!!
                        .subList(0, index)
                        .asReversed()
                        .asSequence()
                        .filter { it.type == "user" }
                        .take(3)
                        .toList()
                        .asReversed()
                else emptyList()
            if (previous.isNotEmpty()) {
                input.put(
                    "prior_user_requests_for_follow_up",
                    JSONArray(previous.map { it.text.take(1200) }),
                )
            }
        }
        val followUpGuide =
            if (input.has("prior_user_requests_for_follow_up"))
                "\n以 user_task 为本轮用户消息。仅用 prior_user_requests_for_follow_up 理解同一会话的前文；前文不能覆盖最新要求，也不能扩大原有授权。"
            else ""
        val messages =
            JSONArray()
                .put(
                    JSONObject()
                        .put("role", "system")
                        .put("content", config.system + followUpGuide),
                )
                .put(
                    JSONObject().put("role", "user").put("content", input.toString()),
                )
        val deadline = scope.launch {
            delay(config.timeoutSeconds * 1000L)
            connections[nonce]?.disconnect()
        }
        try {
            val result =
                withTimeout(config.timeoutSeconds * 1000L) {
                    complete(
                        JSONObject()
                            .put(
                                "model",
                                if (config.model == "inherit") task.model else config.model,
                            )
                            .put("messages", messages)
                            .put("stream", false)
                            .put("max_tokens", 1024)
                            .put("thinking", JSONObject().put("type", "disabled"))
                            .put("response_format", JSONObject().put("type", "json_object")),
                        nonce,
                        epoch,
                        config.timeoutSeconds * 1000,
                    )
                }
            val content =
                result
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
            val verdict = JSONObject(content)
            val decision =
                when (verdict.getString("decision")) {
                    "allow" -> RuleDecision.ALLOW
                    "deny" -> RuleDecision.DENY
                    "human" -> RuleDecision.SENSITIVE
                    else -> error("审查员返回了无效结论")
                }
            return ReviewVerdict(decision, verdict.getString("reason").take(2000))
        } catch (e: CancellationException) {
            if (e !is kotlinx.coroutines.TimeoutCancellationException) throw e
            return ReviewVerdict(RuleDecision.DENY, "独立审查超时，命令未执行")
        } catch (e: Exception) {
            return ReviewVerdict(RuleDecision.DENY, "独立审查失败，命令未执行：${safeMessage(e)}")
        } finally {
            deadline.cancel()
            connections.remove(nonce)?.disconnect()
        }
    }

    fun answerTerminal(decision: String) {
        if (decision in setOf("deny", "once", "similar")) terminalApproval?.complete(decision)
    }

    private suspend fun approveTerminal(task: PhoneTask, run: TerminalRun, reason: String): String =
        if (task.access != "ask") "deny"
        else
            approvalLock.withLock {
                val pending = CompletableDeferred<String>()
                terminalApproval = pending
                task.approval = true
                task.terminalApproval = true
                task.status = "waiting"
                task.question =
                    "${TerminalLevel.of(run.level).title}\n${run.cwd}\n$ ${run.command}\n\n$reason\n\n允许类似命令：仅记住本次应用进程内、此会话及此策略下相同目录和完整参数的命令。"
                task.progress = "等待终端命令审批"
                notifyEvent(task, task.question)
                floating.update()
                try {
                    withTimeout(600000) { pending.await() }
                } finally {
                    if (terminalApproval === pending) terminalApproval = null
                    task.terminalApproval = false
                    task.approval = false
                    task.question = ""
                    if (task.status == "waiting") task.status = "running"
                    floating.update()
                }
            }

    private fun authorizeMemory(task: PhoneTask, args: JSONObject) {
        val scope = args.getString("scope")
        require(scope == "global" || scope == "project" && task.projectId.isNotBlank())
        val resource = if (scope == "project") "project:${task.projectId}" else scope
        val permissions = terminal.harness.permissions
        val caller = terminal.harness.context(task)
        for (capability in
            listOf(
                dev.deepseek.mobile.harness.Capability.MEMORY_READ,
                dev.deepseek.mobile.harness.Capability.MEMORY_WRITE,
            )) {
            val permission = permissions.evaluate(caller, capability, resource)
            check(permission.effect == dev.deepseek.mobile.harness.PermissionEffect.ALLOW) {
                "当前权限规则未授权自动更新此记忆：${permission.reason}"
            }
        }
        val snapshot =
            if (task.terminalConfig.isNotEmpty())
                TerminalSnapshot.from(JSONObject(task.terminalConfig))
            else terminal.config.load("isolated")
        val target = memories.read(scope, task.projectId)
        check(target.sha256 == args.getString("expected_sha256")) { "记忆版本已改变，请重新读取并合并" }
        val decision =
            TerminalPolicy(snapshot.rules)
                .inspectPaths(
                    listOf(PathUse(target.logicalPath, "w")),
                    "/dev.deepseek.mobile/workplace/session_${task.sessionId}",
                )
        check(decision.decision == RuleDecision.ALLOW) {
            "权限规则未授权自动写入：${decision.reasons.joinToString()}"
        }
    }

    private suspend fun authorize(
        method: String,
        args: JSONObject,
        epoch: Long,
        sandboxId: String = "",
    ) {
        val t =
            AssistantState.running
                ?: return // Legacy instrumentation owns its explicitly-started task.
        if (method == "ask_user") {
            check(t.access !in setOf("none", "read", "auto")) { "此模式不暂停等待人工；请在回复中说明缺少的信息" }
            return
        }
        if (method in setOf("complete", "image", "wait")) return
        check(t.access !in setOf("none", "read")) { "完全禁止模式仅支持聊天，所有执行工具已关闭" }
        if (method == "plugin_call") return
        if (method == "plugin_manager") {
            check(t.creatorMode && args.optString("_agentId").isBlank()) {
                "只有 Creator 会话的主 Agent 可以管理插件"
            }
            return
        }
        if (method.startsWith("memory_")) {
            check(ExperienceSettings.memoryEnabled) { "用户已关闭记忆" }
            if (method in setOf("memory_read", "memory_write"))
                require(args.getString("scope") in setOf("global", "project", "persona"))
        }
        val officialWeb = OfficialWebTool.fromMethod(method)
        val browser = method.startsWith("browser_")
        if (browser) {
            check(
                ExperienceSettings.browserMode.allows(officialWeb) &&
                    BrowserProvider.parse(t.browserProvider).allows(officialWeb),
            ) {
                "当前浏览器档位没有启用此工具"
            }
        }
        val permissionContext =
            terminal.harness.context(t).let {
                if (sandboxId.isBlank()) it else it.copy(environment = "sandbox")
            }
        val officialRequest = officialWeb?.request(args)
        val capability =
            when {
                method in setOf("memory_read", "memory_search") ->
                    dev.deepseek.mobile.harness.Capability.MEMORY_READ
                method == "skill_read" -> dev.deepseek.mobile.harness.Capability.SKILL_READ
                method == "proxy_control" -> dev.deepseek.mobile.harness.Capability.PROXY_CONFIG
                method == "memory_write" -> dev.deepseek.mobile.harness.Capability.MEMORY_WRITE
                method.startsWith("mcp_") -> dev.deepseek.mobile.harness.Capability.MCP_TOOL
                method == "instructions_read" ->
                    dev.deepseek.mobile.harness.Capability.INSTRUCTIONS_READ
                method.startsWith("workspace_") || method.startsWith("worktree_") ->
                    if (method in setOf("workspace_write", "worktree_merge", "worktree_discard"))
                        dev.deepseek.mobile.harness.Capability.WORKSPACE_WRITE
                    else dev.deepseek.mobile.harness.Capability.WORKSPACE_READ
                method.startsWith("browser_") -> dev.deepseek.mobile.harness.Capability.NETWORK
                method in
                    setOf(
                        "terminal_exec",
                        "terminal_elevate",
                        "terminal_status",
                        "context_search",
                        "context_read",
                        "attachment_read",
                        "publish_file",
                        "mcp_list",
                        "mcp_call",
                        "toolchain_install",
                    ) -> null
                else -> dev.deepseek.mobile.harness.Capability.ANDROID_API
            }
        if (capability != null) {
            val target =
                (when {
                        capability == dev.deepseek.mobile.harness.Capability.ANDROID_API -> method
                        method.startsWith("mcp_") ->
                            args.optString("server_id", "*") +
                                "/" +
                                args.optString("name", "tools/list")
                        method == "skill_read" -> args.optString("name")
                        method == "proxy_control" -> args.optString("action")
                        officialWeb == OfficialWebTool.SEARCH -> officialWeb.endpoint
                        officialWeb == OfficialWebTool.FETCH ->
                            requireNotNull(officialRequest).getString("url")
                        browser -> args.optString("url", "*")
                        method in setOf("memory_read", "memory_write") ->
                            dev.deepseek.mobile.harness.ContextManager.memoryResource(
                                args.optString("scope"),
                                t.sessionId,
                                t.projectId,
                            )
                        else -> args.optString("scope")
                    })
                    .ifBlank {
                        if (method.startsWith("workspace_") && args.has("name"))
                            "/workspace/" + args.getString("name").removePrefix("/workspace/")
                        else args.optString("name", "*")
                    }
            val resources =
                if (officialWeb == OfficialWebTool.FETCH)
                    listOf(officialWeb.endpoint, target).distinct()
                else if (method == "workspace_list")
                    listOf(
                        "/workspace/" +
                            dev.deepseek.mobile.harness.WorkspaceManager.directoryPath(
                                args.optString("path"),
                            ),
                    )
                else listOf(target)
            for (resource in resources) {
                val result =
                    terminal.harness.permissions.evaluate(
                        permissionContext,
                        capability,
                        resource,
                    )
                check(result.effect != dev.deepseek.mobile.harness.PermissionEffect.DENY) {
                    result.reason
                }
                if (
                    result.effect == dev.deepseek.mobile.harness.PermissionEffect.ASK &&
                        method != "memory_write"
                ) {
                    check(t.access != "auto") { "权限规则要求人工批准，替我审批模式未执行" }
                    requestCapability(t, result.reason, "$method · $resource", epoch)
                    check(
                        terminal.harness.permissions
                            .evaluate(permissionContext, capability, resource)
                            .effect != dev.deepseek.mobile.harness.PermissionEffect.DENY,
                    ) {
                        "等待期间权限规则改变，操作未执行"
                    }
                }
            }
        }
        if (
            method in
                setOf(
                    "memory_read",
                    "memory_search",
                    "instructions_read",
                    "skill_read",
                    "proxy_control",
                    "context_search",
                    "context_read",
                    "attachment_read",
                )
        )
            return
        if (method in setOf("workspace_list", "workspace_read")) {
            check(t.fileTools) { "本任务未启用工作区工具" }
            return // Read-only exploration already passed the capability and path policy above.
        }
        if (method == "publish_file") {
            check(if (args.optString("source") == "workspace") t.fileTools else t.terminalEnabled) {
                "未启用对应文件工具"
            }
            return
        }
        if (method == "memory_write") {
            authorizeMemory(t, args)
            return
        }
        if (
            method == "toolchain_install" ||
                method.startsWith("mcp_") ||
                method.startsWith("worktree_")
        )
            return
        if (method in setOf("terminal_exec", "terminal_status", "terminal_elevate")) {
            check(
                t.terminalEnabled || sandboxId.isNotBlank() && sandboxId in t.sandboxIds.orEmpty(),
            ) {
                "本任务未启用此终端"
            }
            return
        }
        if (!browser) {
            if (method.startsWith("workspace_")) check(t.fileTools) { "本任务未启用工作区工具" }
            else check(t.phoneTools) { "本任务未启用手机工具" }
        }
        val verdict =
            if (t.access in setOf("ask", "auto")) {
                val snapshot =
                    if (t.terminalConfig.isNotEmpty())
                        TerminalSnapshot.from(JSONObject(t.terminalConfig))
                    else terminal.config.load("isolated")
                reviewOperation(
                    t,
                    JSONObject()
                        .put("kind", "android_tool")
                        .put("tool", method)
                        .put("arguments", args),
                    ReviewerConfig.parse(snapshot.reviewer),
                )
            } else ReviewVerdict(RuleDecision.ALLOW, "完全访问")
        check(verdict.decision != RuleDecision.DENY) { "操作未获审查员批准：${verdict.reason}" }
        if (t.access == "auto")
            check(verdict.decision == RuleDecision.ALLOW) {
                "替我审批不会请求人工；审查员未明确允许：${verdict.reason}"
            }
        val writes =
            setOf(
                "tap",
                "long_press",
                "swipe",
                "input_text",
                "global_action",
                "launch_app",
                "workspace_write",
                "browser_act",
            )
        if (method !in writes && verdict.decision != RuleDecision.SENSITIVE) return
        if (t.access == "ask")
            approvalLock.withLock {
                assertActive(epoch)
                val pending = CompletableDeferred<Boolean>()
                approval = pending
                t.approval = true
                t.status = "waiting"
                val details =
                    args
                        .keys()
                        .asSequence()
                        .filter { it != "snapshot" && !it.startsWith("_") }
                        .joinToString("\n") { "${it}  ${args.optString(it).take(350)}" }
                t.question =
                    listOf(toolName(method), details.take(600))
                        .filter { it.isNotBlank() }
                        .joinToString("\n")
                t.progress = "等待操作授权"
                WhaleCompanion.react("waiting", "这一步要你点头，我先等着。", R.drawable.meme_ohjing)
                notifyEvent(t, t.question)
                floating.update()
                try {
                    check(withTimeout(600000) { pending.await() }) { "用户拒绝操作" }
                    assertActive(epoch)
                } finally {
                    if (approval === pending) approval = null
                    t.question = ""
                    t.approval = false
                    if (t.status == "waiting") t.status = "running"
                    floating.update()
                }
            }
    }

    private suspend fun requestCapability(
        task: PhoneTask,
        reason: String,
        detail: String,
        epoch: Long,
        devicePermission: Boolean = false,
    ) = approvalLock.withLock {
        check(task.access == "ask" || devicePermission && task.access !in setOf("none", "read")) {
            "此操作需要独立授权，当前执行模式不会请求确认：$reason"
        }
        assertActive(epoch)
        val pending = CompletableDeferred<Boolean>()
        approval = pending
        task.approval = true
        task.status = "waiting"
        task.question = "$reason\n${detail.take(500)}"
        task.progress = "等待单次操作授权"
        notifyEvent(task, task.question)
        floating.update()
        val ledger = terminal.harness.approvals.begin(task, reason, detail)
        var decision = "interrupted"
        try {
            val allowed = withTimeout(600000) { pending.await() }
            decision = if (allowed) "approved" else "rejected"
            check(allowed) { "用户拒绝本次操作" }
            assertActive(epoch)
        } finally {
            terminal.harness.approvals.settle(ledger, decision)
            if (approval === pending) approval = null
            task.approval = false
            task.question = ""
            if (task.status == "waiting") task.status = "running"
            floating.update()
        }
    }

    private suspend fun extensionApproval(
        task: PhoneTask,
        epoch: Long,
        reason: String,
        detail: String,
    ): Boolean {
        check(task.access !in setOf("none", "read", "auto")) { "扩展要求人工确认，当前模式未执行" }
        requestCapability(task, reason, detail, epoch)
        return true
    }

    private suspend fun dispatch(
        method: String,
        original: JSONObject,
        id: String,
        epoch: Long,
    ): Any {
        val task = AssistantState.running
        if (task != null) {
            if (method in taskMethods) workflowPolicy.requireAllowed(task.workMode, method)
            if (method in workflowPolicy.tools) {
                check(task.access !in setOf("none", "read")) { "完全禁止模式的执行工具已关闭" }
                terminal.harness.worktreeId(task, original)
                if (method in workflowPolicy.workspaceTools) {
                    check(task.fileTools) { "文件工具未开启" }
                    return WorkspaceTools { operation, arguments ->
                            dispatch(operation, arguments, "$id-$operation", epoch) as JSONObject
                        }
                        .run(method, original)
                }
                return workflowOperation(task, method, original, id, epoch)
            }
            check(ExperienceSettings.memoryEnabled || method !in memoryOperations) { "记忆已关闭" }
            val requested = original.optString("sandbox_id")
            if (task.sandboxOnly && method in taskMethods) {
                check(
                    method in sandboxOperations ||
                        method in
                            setOf(
                                "complete",
                                "ask_user",
                                "wait",
                                "image",
                                "sandbox_list",
                                "sandbox_connect",
                            ),
                ) {
                    "当前任务受 Sandbox 约束，不能调用 $method"
                }
                if (method == "image")
                    check(original.optString("id") in task.images) { "只能读取本轮上传的图片" }
                check(requested.isBlank() || task.acceptsSandbox(requested)) {
                    "此 Sandbox 不在本任务获准访问的范围内"
                }
            }
            if (method in setOf("sandbox_list", "sandbox_connect")) {
                check(
                    task.access !in setOf("none", "read") &&
                        (task.terminalEnabled || task.hasSandboxAccess),
                )
                if (method == "sandbox_connect")
                    return sandboxes.connect(original.getString("sandbox_id"), task)
                val session =
                    AssistantState.sessions.firstOrNull { it.id == task.sessionId }
                        ?: error("会话已删除")
                return JSONObject()
                    .put(
                        "sandboxes",
                        JSONArray(
                            sandboxes.records.value
                                .filter {
                                    task.acceptsSandbox(it.id) &&
                                        it.allows(session.id, session.projectId)
                                }
                                .map { sandboxes.describe(it, task) },
                        ),
                    )
            }
            if (method in sandboxOperations) {
                val target = requested.ifBlank { task.sandboxId }
                if (target.isNotBlank()) {
                    check(
                        (task.terminalEnabled || target in task.sandboxIds.orEmpty()) &&
                            task.access !in setOf("none", "read"),
                    )
                    check(sandboxes.isConnected(target, task)) {
                        "请先调用 sandbox_connect 连接此 Sandbox"
                    }
                    return sandboxes.withAccess(target, task) {
                        dispatchRaw(method, original, id, epoch, target)
                    }
                }
                check(!task.sandboxOnly) { "Only Sandbox 不能回退到手机终端或本机工作区" }
            }
        }
        val events = dev.deepseek.mobile.harness.HookEvent.entries
        if (
            task == null ||
                method !in taskMethods ||
                method in setOf("ask_user", "complete", "wait", "image")
        )
            return dispatchRaw(method, original, id, epoch)
        check(task.access !in setOf("none", "read")) { "完全禁止模式的执行工具已关闭" }
        val treeId = terminal.harness.worktreeId(task, original)
        val hookContexts = mutableListOf<String>()
        suspend fun hook(
            event: dev.deepseek.mobile.harness.HookEvent,
            arguments: JSONObject,
            result: JSONObject? = null,
        ) =
            terminal.harness.extensions
                .hooks(task, event, method, arguments, result, treeId) { reason, detail ->
                    extensionApproval(task, epoch, reason, detail)
                }
                .also { if (it.context.isNotBlank()) hookContexts += it.context }
        var args = hook(dev.deepseek.mobile.harness.HookEvent.BeforeToolCall, original).arguments
        val special =
            when (method) {
                "terminal_exec",
                "terminal_elevate" -> dev.deepseek.mobile.harness.HookEvent.BeforeCommand
                "workspace_write" -> dev.deepseek.mobile.harness.HookEvent.BeforeFileWrite
                else -> null
            }
        if (special != null) args = hook(special, args).arguments
        if (method == "workspace_write") WorkspaceText.checkPreparedWrite(original, args)
        // The modified operation passes the complete normal permission and command review again.
        val result = dispatchRaw(method, args, id, epoch)
        val json = result as? JSONObject
        try {
            if (special != null) hook(events[events.indexOf(special) + 1], args, json)
            hook(dev.deepseek.mobile.harness.HookEvent.AfterToolCall, args, json)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            // An after-hook cannot undo an already completed tool. Returning a tool failure here
            // could make an Agent repeat a side effect, so attach an explicit warning instead.
            json?.put("afterHookWarning", safeMessage(error))
        }
        if (hookContexts.isNotEmpty())
            return (json ?: JSONObject().put("value", result)).put(
                "hookContext",
                hookContexts.distinct().joinToString("\n").take(8192),
            )
        return result
    }

    private suspend fun workflowOperation(
        task: PhoneTask,
        method: String,
        args: JSONObject,
        id: String,
        epoch: Long,
    ): JSONObject {
        when (method) {
            "work_status" -> return task.workflowSnapshot()
            "work_plan" -> {
                check(args.optString("_agentId").isBlank()) { "子 Agent 不能替换主任务计划" }
                task.workPlan = task.workPlan.update(JSONObject(args.toString()))
                AssistantState.sessions.firstOrNull { it.id == task.sessionId }?.workPlan =
                    task.workPlan
                AssistantState.persist()
                return task.workflowSnapshot()
            }
            "verification_run" -> {
                val label = args.getString("label").trim().also { require(it.length in 1..120) }
                val command = args.getString("command")
                val pending =
                    VerificationRecord(
                        label,
                        "",
                        command.take(4000),
                        "running",
                        null,
                        "",
                        System.currentTimeMillis(),
                    )
                task.verifications.add(pending)
                while (task.verifications.size > 40) task.verifications.removeAt(0)
                AssistantState.persist()
                fun settle(record: VerificationRecord) {
                    val index = task.verifications.indexOfFirst { it.id == pending.id }
                    if (index >= 0)
                        task.verifications[index] =
                            record.copy(id = pending.id, createdAt = pending.createdAt)
                    AssistantState.persist()
                }
                val forwarded = JSONObject(args.toString()).apply { remove("label") }
                val result =
                    try {
                        (dispatch("terminal_exec", forwarded, "$id-verify", epoch) as JSONObject)
                            .also { assertActive(epoch) }
                    } catch (error: CancellationException) {
                        withContext(
                            kotlinx.coroutines.NonCancellable + Dispatchers.Main.immediate,
                        ) {
                            settle(pending.copy(outcome = "cancelled", detail = "任务已停止，验证未完成"))
                        }
                        throw error
                    } catch (error: Exception) {
                        JSONObject()
                            .put("executionStarted", false)
                            .put("reason", safeMessage(error))
                    }
                val record =
                    VerificationRecord.measured(label, command, result, pending.createdAt)
                        .copy(id = pending.id)
                settle(record)
                return JSONObject()
                    .put("verification", record.json())
                    .put("result", result)
                    .put(
                        "message",
                        if (record.outcome == "passed") "命令正常退出；仍需审查验证范围" else "验证未通过；请检查实际结果",
                    )
            }

            else -> error("未知工作流程操作")
        }
    }

    private suspend fun dispatchRaw(
        method: String,
        args: JSONObject,
        id: String,
        epoch: Long,
        sandboxId: String = "",
    ): Any {
        if (method in taskMethods) authorize(method, args, epoch, sandboxId)
        if (
            method in
                setOf("observe", "tap", "long_press", "swipe", "input_text", "global_action") &&
                !hasDevicePermission("accessibility")
        ) {
            val task = AssistantState.running
            if (task != null && permissionPromptTask != task.id) {
                val level =
                    TerminalLevel.of(
                        runCatching { JSONObject(task.terminalConfig).optString("level") }
                            .getOrDefault(""),
                    )
                if (level.canManageOwnPermissions && terminal.connections.state(level).ready) {
                    permissionPromptTask = task.id
                    requestCapability(
                        task,
                        "开启本应用的无障碍服务？",
                        "使用已连接的 ${level.title}。仅开启 DSHA 手机助手。",
                        epoch,
                        devicePermission = true,
                    )
                    terminal.connections.ownAppAction(level, "accessibility")
                    withTimeout(3000) {
                        while (!hasDevicePermission("accessibility")) delay(100)
                    }
                }
            }
        }
        return when (method) {
            "attachment_context" ->
                withContext(Dispatchers.IO) {
                    val task = AssistantState.running ?: error("没有运行任务")
                    check(args.getString("sessionId") == task.sessionId) { "会话已切换" }
                    val store = AttachmentStore(this@AgentService)
                    val array = JSONArray()
                    if (task.sandboxOnly) {
                        for (id in task.files.distinct()) {
                            val info = store.get(id)
                            check(info.sessionId == task.sessionId)
                            array.put(
                                JSONObject()
                                    .put("name", info.name)
                                    .put("id", id)
                                    .put(
                                        "guestPath",
                                        "/workspace/" +
                                            dev.deepseek.mobile.runtime.sandboxAttachmentPath(
                                                id,
                                                info.name,
                                            ),
                                    ),
                            )
                        }
                        return@withContext JSONObject().put("files", array)
                    }
                    var remaining = 100000
                    val recent =
                        AssistantState.sessions
                            .firstOrNull { it.id == task.sessionId }
                            ?.messages
                            .orEmpty()
                            .flatMap { it.files }
                            .distinct()
                            .takeLast(6)
                    (task.files + recent.reversed()).distinct().forEach { id ->
                        val info = store.get(id)
                        check(info.sessionId == task.sessionId)
                        val text = runCatching {
                            store.text(id, remaining.coerceAtLeast(1))
                        }
                            .getOrElse { "[${it.message}]" }
                        array.put(
                            info.json().put("preview", info.link).put("text", text.take(remaining)),
                        )
                        remaining = (remaining - text.length).coerceAtLeast(0)
                    }
                    JSONObject().put("files", array)
                }
            "attachment_read" ->
                withContext(Dispatchers.IO) {
                    val task = AssistantState.running ?: error("没有运行任务")
                    val id = args.getString("id")
                    val store = AttachmentStore(this@AgentService)
                    val info = store.get(id)
                    val conversation = AssistantState.sessions.first { it.id == task.sessionId }
                    check(
                        info.sessionId == task.sessionId &&
                            (id in task.files || conversation.messages.any { id in it.files }),
                    ) {
                        "附件不属于此会话已发送的消息"
                    }
                    info.json().put("text", store.text(id))
                }
            "publish_file" -> publishFile(args, sandboxId)
            "plugin_manager" -> {
                val task = AssistantState.running ?: error("任务已停止")
                terminal.harness.plugins.manage(args, task, { assertActive(epoch) }) { title, detail
                    ->
                    requestCapability(task, title, detail, epoch)
                }
            }
            "plugin_call" -> {
                val task = AssistantState.running ?: error("任务已停止")
                terminal.harness.plugins.call(
                    args,
                    task.sessionId,
                    task.projectId,
                    ask = { title, detail -> requestCapability(task, title, detail, epoch) },
                )
            }
            "skill_read" ->
                withContext(Dispatchers.IO) {
                    val task = AssistantState.running ?: error("任务已停止")
                    terminal.harness.skills.read(
                        task.projectId,
                        args.getString("name"),
                        args.optString("path", "SKILL.md"),
                        args.optInt("offset"),
                    )
                }
            "proxy_control" -> flclash.agent(args.getString("action"), args)
            "memory_context" -> {
                val task = AssistantState.running ?: error("没有运行中的会话")
                require(args.getString("sessionId") == task.sessionId)
                if (task.sandboxOnly) return JSONObject()
                withContext(Dispatchers.IO) {
                        terminal.harness.contexts
                            .startup(
                                task,
                                ExperienceSettings.memoryEnabled,
                                args.optString("checkpoint"),
                            )
                            .also { data ->
                                if (task.access !in setOf("none", "read")) {
                                    val policy = terminal.harness.context(task)
                                    runCatching { terminal.harness.plugins.catalog() }
                                        .onSuccess {
                                            data.put("plugins", it)
                                        }
                                    data.put(
                                        "skills",
                                        terminal.harness.skills.catalog(task.projectId) { name ->
                                            terminal.harness.permissions
                                                .evaluate(
                                                    policy,
                                                    dev.deepseek.mobile.harness.Capability
                                                        .SKILL_READ,
                                                    name,
                                                )
                                                .effect ==
                                                dev.deepseek.mobile.harness.PermissionEffect.ALLOW
                                        },
                                    )
                                }
                            }
                    }
                    .also { startup ->
                        val extra = startHookLock.withLock {
                            startHookContexts[task.id]
                                ?: terminal.harness.extensions
                                    .hooks(
                                        task,
                                        dev.deepseek.mobile.harness.HookEvent.BeforeAgentStart,
                                    ) { reason, detail ->
                                        extensionApproval(task, epoch, reason, detail)
                                    }
                                    .context
                                    .also { startHookContexts[task.id] = it }
                        }
                        startup.put("hookContext", extra)
                        startup.put(
                            "browserShared",
                            JSONArray(
                                BrowserState.tabs
                                    .filter {
                                        it.sessionId == task.sessionId &&
                                            !it.userOwned &&
                                            it.userAccess
                                    }
                                    .map { it.id },
                            ),
                        )
                    }
            }
            "memory_search" ->
                withContext(Dispatchers.IO) {
                    JSONObject()
                        .put(
                            "matches",
                            terminal.harness.contexts.searchMemory(
                                AssistantState.running ?: error("任务已停止"),
                                args.getString("query"),
                                args.optInt("limit", 8),
                            ),
                        )
                }
            "instructions_read" ->
                withContext(Dispatchers.IO) {
                    val task = AssistantState.running ?: error("任务已停止")
                    val name = args.optString("scope", "global")
                    require(name != "project" || task.projectId.isNotBlank())
                    terminal.harness.contexts.instruction(name, task.projectId)
                }
            "context_search",
            "context_read" -> {
                val task = AssistantState.running ?: error("任务已停止")
                val includeOther =
                    if (method == "context_search") args.optString("scope", "project") == "global"
                    else {
                        val conversation =
                            AssistantState.sessions.firstOrNull {
                                it.id == args.getString("session_id")
                            } ?: error("会话不存在")
                        !terminal.harness.contexts.sameProject(task, conversation)
                    }
                if (includeOther) {
                    val permission =
                        terminal.harness.permissions.evaluate(
                            terminal.harness.context(task),
                            dev.deepseek.mobile.harness.Capability.CROSS_CONTEXT,
                            if (method == "context_read") args.getString("session_id")
                            else "all_sessions",
                        )
                    check(permission.effect != dev.deepseek.mobile.harness.PermissionEffect.DENY) {
                        permission.reason
                    }
                    if (permission.effect == dev.deepseek.mobile.harness.PermissionEffect.ASK) {
                        check(task.access != "auto") { "跨项目或独立会话需要用户授权，替我审批模式未读取" }
                        requestCapability(
                            task,
                            "读取其他工作域的会话上下文？",
                            if (method == "context_search") "检索其他会话的摘要"
                            else args.getString("session_id"),
                            epoch,
                        )
                    }
                }
                withContext(Dispatchers.IO) {
                    if (method == "context_search")
                        JSONObject()
                            .put(
                                "matches",
                                terminal.harness.contexts.searchSessions(
                                    task,
                                    args.optString("query"),
                                    includeOther,
                                ),
                            )
                    else {
                        val permission =
                            terminal.harness.permissions.evaluate(
                                terminal.harness.context(task),
                                dev.deepseek.mobile.harness.Capability.MEMORY_READ,
                                "session:" + args.getString("session_id"),
                            )
                        check(
                            permission.effect == dev.deepseek.mobile.harness.PermissionEffect.ALLOW,
                        ) {
                            permission.reason + "；请在设置中明确允许该会话读取"
                        }
                        terminal.harness.contexts.readSession(
                            args.getString("session_id"),
                            args.optInt("offset"),
                            args.optInt("limit", 6000),
                        )
                    }
                }
            }
            "memory_read" ->
                withContext(Dispatchers.IO) {
                    val task = AssistantState.running ?: error("任务已停止")
                    memories
                        .read(
                            args.getString("scope"),
                            task.projectId,
                        )
                        .json()
                }
            "memory_write" ->
                withContext(Dispatchers.IO) {
                    val task = AssistantState.running ?: error("任务已停止")
                    memories
                        .write(
                            args.getString("scope"),
                            task.projectId,
                            args.getString("text"),
                            args.getString("expected_sha256"),
                        )
                        .json()
                        .put("message", "共享记忆已原子更新")
                }
            "browser_open",
            "browser_snapshot",
            "browser_source",
            "browser_act" -> browser.execute(method, args)
            "browser_search",
            "browser_fetch" ->
                officialWebRequest(
                    requireNotNull(OfficialWebTool.fromMethod(method)),
                    args,
                    id,
                    epoch,
                )
            "terminal_status" -> {
                val task = AssistantState.running ?: error("任务已停止")
                if (sandboxId.isNotBlank()) {
                    val description =
                        sandboxes.describe(sandboxes.requireAccess(sandboxId, task), task)
                    return description
                        .put("selectedLevel", "sandbox")
                        .put("access", task.access)
                        .put(
                            "connection",
                            JSONObject().put("phase", description.optString("phase")),
                        )
                }
                val level =
                    TerminalLevel.of(TerminalSnapshot.from(JSONObject(task.terminalConfig)).level)
                terminal
                    .connectionStatus(level, args.optBoolean("probe"))
                    .put("access", task.access)
                    .put("accessTitle", accessTitle(task.access))
                    .put(
                        "singleOperationElevationAvailable",
                        level == TerminalLevel.SANDBOX && task.access == "ask",
                    )
            }
            "toolchain_install" -> {
                val task = AssistantState.running ?: error("任务已停止")
                val array = args.getJSONArray("packages")
                val names = (0 until array.length()).map { array.getString(it) }
                val request =
                    withContext(Dispatchers.IO) { terminal.harness.packages.request(task, names) }
                if (task.access != "ask")
                    JSONObject()
                        .put("pendingUserApproval", true)
                        .put("requestId", request.getString("id"))
                        .put("message", "全局安装必须由用户在运行环境设置批准；项目级 pip/npm/apk 不受此流程影响")
                else {
                    var approved = false
                    try {
                        requestCapability(
                            task,
                            "向共享 Light 工具链安装这些包？",
                            names.joinToString("、") + "\n仅影响未来新建或重置的环境。",
                            epoch,
                        )
                        approved = true
                        terminal.harness.packages.installAfterUserApproval(request.getString("id"))
                    } catch (error: Exception) {
                        if (!approved && error !is kotlinx.coroutines.CancellationException)
                            withContext(Dispatchers.IO) {
                                terminal.harness.packages.deny(request.getString("id"))
                            }
                        throw error
                    }
                }
            }
            "mcp_list",
            "mcp_call" -> {
                val task = AssistantState.running ?: error("任务已停止")
                terminal.harness.extensions.mcp(
                    task,
                    args.optString("server_id"),
                    if (method == "mcp_call") args.getString("name") else null,
                    args.optJSONObject("arguments") ?: JSONObject(),
                ) { reason, detail ->
                    extensionApproval(task, epoch, reason, detail)
                }
            }
            "worktree_list",
            "worktree_review",
            "worktree_merge",
            "worktree_discard" -> {
                val task = AssistantState.running ?: error("任务已停止")
                val scope = terminal.harness.runtimes.scope(task)
                val trees =
                    withContext(Dispatchers.IO) {
                        terminal.harness.workspaces.tasks(scope, task.id)
                    }
                if (method == "worktree_list")
                    JSONObject()
                        .put(
                            "worktrees",
                            JSONArray(
                                trees.map { tree ->
                                    JSONObject()
                                        .put("id", tree.taskId)
                                        .put("state", tree.state)
                                        .put(
                                            "changes",
                                            terminal.harness.workspaces.review(tree).size,
                                        )
                                },
                            ),
                        )
                else {
                    val tree =
                        trees.firstOrNull { it.taskId == args.getString("worktree_id") }
                            ?: error("工作副本不属于本任务")
                    when (method) {
                        "worktree_review" ->
                            JSONObject()
                                .put(
                                    "changes",
                                    JSONArray(
                                        terminal.harness.workspaces.review(tree).map { it.json() },
                                    ),
                                )
                        "worktree_merge" ->
                            terminal.harness.runtimes.resolveWorktree(
                                tree,
                                true,
                                terminal.harness.context(task),
                            ) { verdict ->
                                extensionApproval(
                                    task,
                                    epoch,
                                    verdict.reason,
                                    "合并工作副本 ${tree.taskId}",
                                )
                            }
                        else ->
                            terminal.harness.runtimes.resolveWorktree(
                                tree,
                                false,
                                terminal.harness.context(task),
                            ) { verdict ->
                                extensionApproval(
                                    task,
                                    epoch,
                                    verdict.reason,
                                    "丢弃工作副本 ${tree.taskId}",
                                )
                            }
                    }
                }
            }
            "terminal_elevate" -> {
                val task = AssistantState.running ?: error("任务已停止")
                val selected =
                    TerminalLevel.of(TerminalSnapshot.from(JSONObject(task.terminalConfig)).level)
                check(selected == TerminalLevel.SANDBOX) {
                    "当前会话已选择${selected.title}。请用 terminal_exec 在当前档位执行；如需切换档位，请由用户在终端菜单选择。"
                }
                check(task.access == "ask") { "当前审批模式不提供单次提权；请由用户在终端菜单选择所需档位" }
                val level = args.getString("level")
                require(level in setOf("app", "shell", "root_guarded", "root"))
                val capability =
                    when (level) {
                        "app" -> dev.deepseek.mobile.harness.Capability.APP_EXEC
                        "shell" -> dev.deepseek.mobile.harness.Capability.SHELL_EXEC
                        else -> dev.deepseek.mobile.harness.Capability.ROOT_EXEC
                    }
                val policy = terminal.harness.context(task).copy(environment = "sandbox")
                val command = args.getString("command")
                val before = terminal.config.load(level)
                try {
                    check(
                        terminal.harness.permissions.evaluate(policy, capability, command).effect !=
                            dev.deepseek.mobile.harness.PermissionEffect.DENY,
                    ) {
                        "当前规则禁止此提权"
                    }
                    requestCapability(
                        task,
                        "仅这条命令使用 ${TerminalLevel.of(level).title}？",
                        command + "\n工作目录：" + args.optString("cwd", "默认独立目录"),
                        epoch,
                    )
                    check(before.digest == terminal.config.load(level).digest) {
                        "等待期间终端规则改变，请重新申请"
                    }
                    check(
                        terminal.harness.permissions.evaluate(policy, capability, command).effect !=
                            dev.deepseek.mobile.harness.PermissionEffect.DENY,
                    )
                    terminal
                        .execute(task, args, before)
                        .put("singleOperationElevation", true)
                        .put("sessionEnvironmentUnchanged", true)
                } finally {
                    terminal.harness.permissions.finish(policy)
                }
            }
            "terminal_exec" ->
                terminal.execute(
                    AssistantState.running ?: error("任务已停止"),
                    args,
                    sandboxId = sandboxId,
                )
            "session_load" ->
                withContext(Dispatchers.IO) {
                    val sid = args.getString("sessionId")
                    require(sid.matches(Regex("[a-f0-9-]{36}")))
                    AssistantState.running?.let { check(it.sessionId == sid) { "只能读取当前会话日志" } }
                    val f = File(filesDir, "events/$sid.json")
                    if (f.isFile) JSONArray(f.readText()) else JSONArray()
                }
            "session_recover" ->
                withContext(Dispatchers.IO) {
                    val sid = args.getString("sessionId")
                    require(sid == AssistantState.running?.sessionId)
                    val f = File(filesDir, "events/$sid.json")
                    if (f.isFile)
                        f.copyTo(
                            File(f.parentFile, "$sid-recovery-${System.currentTimeMillis()}.json"),
                        )
                    val c = AssistantState.sessions.first { it.id == sid }
                    val history =
                        JSONArray(
                            c.messages
                                .dropLastWhile { it.type == "user" }
                                .filter { it.type in setOf("user", "assistant") }
                                .map { JSONObject().put("role", it.type).put("content", it.text) },
                        )
                    withContext(Dispatchers.Main) {
                        c.messages.add(ChatEntry("system", "旧版历史记录已修复，可读对话已恢复；原事件文件保留为本地备份。"))
                    }
                    history
                }
            "session_save" ->
                withContext(Dispatchers.IO) {
                    val sid = args.getString("sessionId")
                    require(sid == AssistantState.running?.sessionId)
                    val folder = File(filesDir, "events").apply { mkdirs() }
                    val f = android.util.AtomicFile(File(folder, "$sid.json"))
                    val output = f.startWrite()
                    try {
                        output.write(args.getJSONArray("events").toString().toByteArray())
                        f.finishWrite(output)
                    } catch (e: Exception) {
                        f.failWrite(output)
                        throw e
                    }
                    JSONObject()
                }
            "image_recognition" -> {
                val owner = AssistantState.running ?: error("任务已停止")
                withContext(Dispatchers.IO) {
                    val cacheJob = kotlinx.coroutines.currentCoroutineContext()
                    ImageRecognitionStore(this@AgentService).exchange(owner, args) {
                        cacheJob.ensureActive()
                        assertActive(epoch)
                        check(AssistantState.running?.id == owner.id) { "任务已切换" }
                    }
                }
            }
            "attachment_refs" ->
                JSONArray(
                    AssistantState.strings(args.optJSONArray("ids")).map { imageId ->
                        require(imageId.matches(Regex("[a-f0-9-]{36}")))
                        JSONObject()
                            .put("attachmentId", imageId)
                            .put("mediaType", "image/jpeg")
                            .put("name", "附件.jpg")
                    },
                )
            "workspace_list",
            "workspace_read",
            "workspace_write" -> {
                val task = AssistantState.running ?: error("任务已停止")
                val result =
                    if (
                        sandboxId.isNotBlank() ||
                            runCatching { JSONObject(task.terminalConfig).optString("level") }
                                .getOrNull() == "sandbox"
                    )
                        terminal.harness.runtimes.workspaceTool(
                            task,
                            method,
                            args,
                            terminal.harness.worktreeId(task, args),
                            sandboxId,
                        )
                    else workspace(method, args) as JSONObject
                if (method == "workspace_read") WorkspaceText.window(result, args) else result
            }
            "balance" -> fetchBalance()
            "status" ->
                JSONObject()
                    .put("configured", keys.configured())
                    .put("accessibility", PhoneAccessibility.current != null)
                    .put("active", active)
                    .put("overlay", android.provider.Settings.canDrawOverlays(this))
                    .put(
                        "notifications",
                        getSystemService(NotificationManager::class.java).areNotificationsEnabled(),
                    )
                    .put("model", "deepseek-flash")
                    .put("harnessVersion", "0.2.1-rc.3")
            "list_keys" -> {
                AssistantState.credentials.clear()
                AssistantState.credentials.addAll(withContext(Dispatchers.IO) { keys.list() })
                JSONObject().put("count", AssistantState.credentials.size)
            }
            "select_key" -> {
                check(!active) { "请先停止当前回复再切换密钥" }
                withContext(Dispatchers.IO) { keys.select(args.getString("id")) }
                credentialRevision++
                AssistantState.balance = "未查询"
                AssistantState.balanceUpdatedAt = 0L
                balanceAttemptAt = -60000L
                dispatch("list_keys", JSONObject(), id, epoch)
            }
            "delete_key" -> {
                check(!active) { "请先停止当前回复再删除密钥" }
                withContext(Dispatchers.IO) { keys.delete(args.getString("id")) }
                credentialRevision++
                AssistantState.balance = "未查询"
                AssistantState.balanceUpdatedAt = 0L
                balanceAttemptAt = -60000L
                dispatch("list_keys", JSONObject(), id, epoch)
            }
            "save_key" -> {
                check(!active) { "请先停止任务再修改密钥" }
                withContext(Dispatchers.IO) {
                    keys.save(args.getString("key"), args.optString("name", "DeepSeek"))
                }
                credentialRevision++
                AssistantState.balance = "未查询"
                AssistantState.balanceUpdatedAt = 0L
                balanceAttemptAt = -60000L
                dispatch("list_keys", JSONObject(), id, epoch)
                JSONObject().put("saved", true)
            }
            "clear_key" -> {
                check(!active) { "请先停止任务" }
                keys.clear()
                credentialRevision++
                AssistantState.balance = "未查询"
                AssistantState.balanceUpdatedAt = 0L
                balanceAttemptAt = -60000L
                JSONObject().put("cleared", true)
            }
            "accessibility_settings" -> {
                MainActivity.current?.requestAccessibility() ?: error("请先返回助手应用")
                JSONObject()
            }
            "notification_settings" -> {
                MainActivity.current?.requestNotifications()
                JSONObject()
            }
            "begin" -> {
                AssistantState.running?.let {
                    check(it.status == "running" && !AssistantState.queuePaused) { "任务已取消或队列已暂停" }
                }
                check(!active) { "已有任务运行中" }
                check(keys.configured()) { "请先保存 DeepSeek API Key" }
                active = true
                generation++
                updateNotification("正在执行你发起的任务 · 可随时停止")
                JSONObject().put("started", true)
            }
            "end" -> {
                active = false
                releaseWake()
                updateNotification("本轮任务已结束")
                JSONObject()
            }
            "stop" -> {
                stopTask()
                JSONObject().put("stopped", true)
            }
            "complete" -> complete(args, id, epoch)
            "test_connection" -> {
                check(!active) { "请先停止当前任务" }
                complete(
                    JSONObject()
                        .put("model", "deepseek-flash")
                        .put("thinking", JSONObject().put("type", "disabled"))
                        .put("max_tokens", 8)
                        .put(
                            "messages",
                            JSONArray()
                                .put(JSONObject().put("role", "user").put("content", "Reply OK")),
                        ),
                    id,
                    null,
                    credentialId = args.optString("id"),
                )
                JSONObject().put("message", "连接成功 · deepseek-flash")
            }
            "image" ->
                withContext(Dispatchers.IO) {
                    val imageId = args.getString("id")
                    require(imageId.matches(Regex("[a-f0-9-]{36}"))) { "无效的截图引用" }
                    val file = File(filesDir, "screens/$imageId.jpg")
                    check(file.isFile) { "截图缓存已失效，请重新 observe" }
                    Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
                }
            "list_apps" -> {
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val apps =
                    packageManager
                        .queryIntentActivities(intent, 0)
                        .distinctBy { it.activityInfo.packageName }
                        .filter { it.activityInfo.packageName != packageName }
                JSONObject()
                    .put(
                        "apps",
                        JSONArray(
                            apps.map {
                                JSONObject()
                                    .put("name", it.loadLabel(packageManager).toString())
                                    .put("package", it.activityInfo.packageName)
                            },
                        ),
                    )
            }
            "launch_app" -> {
                phone()
                val target = args.getString("package")
                check(target != packageName) { "请由用户手动打开助手" }
                check(!getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) {
                    "请先手动解锁手机"
                }
                val launch = packageManager.getLaunchIntentForPackage(target) ?: error("应用未安装或不可启动")
                assertActive(epoch)
                startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                delay(750)
                JSONObject().put("message", "应用启动已提交，请重新观察验证")
            }
            "wait" -> {
                val ms = args.getLong("milliseconds")
                require(ms in 100..3000) { "等待时间应为 100–3000ms" }
                delay(ms)
                JSONObject().put("message", "等待完成")
            }
            "ask_user" ->
                approvalLock.withLock {
                    val text = args.getString("question").trim().take(1500)
                    require(text.isNotBlank()) { "请提供具体问题" }
                    val pending = CompletableDeferred<String>()
                    question = pending
                    AssistantState.running?.let {
                        it.question = text
                        it.approval = false
                        it.status = "waiting"
                        it.progress = "等待你的回复"
                        notifyEvent(it, text)
                        floating.update()
                    }
                    try {
                        JSONObject().put("answer", withTimeout(600_000) { pending.await() })
                    } finally {
                        if (question === pending) question = null
                        AssistantState.running?.let {
                            it.question = ""
                            if (it.status == "waiting") it.status = "running"
                        }
                        floating.update()
                    }
                }
            "answer_question" -> {
                answer(args.getString("answer").take(3000))
                JSONObject()
            }
            "append_log" ->
                withContext(Dispatchers.IO) {
                    val file = File(filesDir, "session.jsonl")
                    if (file.length() > 5_000_000) file.writeText("")
                    file.appendText(args.toString() + "\n")
                    JSONObject()
                }
            "history" ->
                withContext(Dispatchers.IO) {
                    val file = File(filesDir, "session.jsonl")
                    val entries =
                        if (file.isFile)
                            file.readLines().takeLast(300).mapNotNull { line ->
                                try {
                                    JSONObject(line)
                                } catch (_: Exception) {
                                    null
                                }
                            }
                        else emptyList()
                    JSONArray(entries)
                }
            "new_session" -> {
                check(!active) { "请先停止任务" }
                withContext(Dispatchers.IO) {
                    File(filesDir, "session.jsonl").writeText("")
                    File(cacheDir, "screens")
                        .listFiles()
                        ?.filter { it.isFile }
                        ?.forEach { it.delete() }
                }
                JSONObject()
            }
            "observe",
            "tap",
            "long_press",
            "swipe",
            "input_text",
            "global_action" ->
                withTimeout(15_000) {
                    assertActive(epoch)
                    check(AssistantState.running?.status != "waiting") { "任务已暂停，等待用户处理当前请求" }
                    if (method == "observe") phone().execute(method, args)
                    else
                        withoutPhonePanels {
                            assertActive(epoch)
                            phone().execute(method, args)
                        }
                }
            else -> error("不支持的调用")
        }
    }

    fun importAttachment(uri: android.net.Uri, sessionId: String, image: Boolean) {
        scope.launch {
            try {
                val conversation =
                    AssistantState.sessions.find { it.id == sessionId } ?: error("会话已不存在")
                check(if (image) conversation.images.size < 4 else conversation.files.size < 6) {
                    "每条消息最多 4 张图片、6 个文档"
                }
                val info =
                    withContext(Dispatchers.IO) {
                        val store = AttachmentStore(this@AgentService)
                        val info = store.import(uri, sessionId)
                        if (image) {
                            require(info.mime.startsWith("image/")) { "请选择图片文件" }
                            val bitmap =
                                android.graphics.ImageDecoder.decodeBitmap(
                                    android.graphics.ImageDecoder.createSource(store.file(info.id)),
                                ) { decoder, size, _ ->
                                    val scale =
                                        1280f / maxOf(size.size.width, size.size.height, 1280)
                                    decoder.setTargetSize(
                                        (size.size.width * scale).toInt().coerceAtLeast(1),
                                        (size.size.height * scale).toInt().coerceAtLeast(1),
                                    )
                                    decoder.allocator =
                                        android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                                }
                            File(filesDir, "screens").mkdirs()
                            File(filesDir, "screens/${info.id}.jpg").outputStream().use {
                                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it)
                            }
                            bitmap.recycle()
                        }
                        info
                    }
                if (AssistantState.sessions.any { it === conversation }) {
                    if (image) conversation.images.add(info.id) else conversation.files.add(info.id)
                    AssistantState.persist()
                    toast("附件已加入，可点击预览；发送后才会上传")
                }
            } catch (error: Exception) {
                toast(safeMessage(error))
            }
        }
    }

    private suspend fun publishFile(args: JSONObject, sandboxId: String): JSONObject {
        val task = AssistantState.running ?: error("没有运行任务")
        val epoch = generation
        val store = AttachmentStore(this)
        val path = args.getString("path")
        val source = args.getString("source")
        val snapshot =
            if (sandboxId.isNotBlank() && task.sandboxId.isBlank()) terminal.config.load("sandbox")
            else if (task.terminalConfig.isNotEmpty())
                TerminalSnapshot.from(JSONObject(task.terminalConfig))
            else terminal.config.load("isolated")
        val info =
            if (snapshot.level == "sandbox") {
                val bytes =
                    terminal.harness.runtimes.publication(
                        task,
                        path,
                        terminal.harness.worktreeId(task, args),
                        sandboxId,
                    )
                approvePublication(
                    task,
                    snapshot,
                    "/workspace/" + path.removePrefix("/workspace/"),
                    epoch,
                )
                withContext(Dispatchers.IO) {
                    store.save(
                        java.io.ByteArrayInputStream(bytes),
                        task.sessionId,
                        path.substringAfterLast('/'),
                        "application/octet-stream",
                    )
                }
            } else
                withContext(Dispatchers.IO) {
                    when (source) {
                        "terminal" -> {
                            check(task.terminalEnabled) { "未启用终端" }
                            val level = TerminalLevel.of(snapshot.level)
                            val alias = terminal.workspace(level, task.sessionId)
                            val root =
                                if (level == TerminalLevel.ISOLATED)
                                    File(filesDir, "terminal/workplace/session_${task.sessionId}")
                                        .canonicalFile
                                else File(alias).canonicalFile
                            val relative =
                                if (path.startsWith("$alias/")) path.removePrefix("$alias/")
                                else path
                            require(
                                !relative.startsWith('/') &&
                                    relative.split('/').none { it == ".." },
                            ) {
                                "只允许当前会话终端工作区内的文件"
                            }
                            val file = File(root, relative)
                            require(
                                file.canonicalFile == file.absoluteFile &&
                                    file.path.startsWith(root.path + File.separator) &&
                                    file.isFile,
                            ) {
                                "文件不在当前会话工作区，或路径包含符号链接"
                            }
                            require(file.canRead()) { "应用无法读取此文件，请先保存到已授权工作区" }
                            approvePublication(task, snapshot, "$alias/$relative", epoch)
                            file.inputStream().use {
                                store.save(
                                    it,
                                    task.sessionId,
                                    file.name,
                                    "application/octet-stream",
                                )
                            }
                        }
                        "workspace" -> {
                            check(task.fileTools) { "未启用工作区工具" }
                            require(
                                path.isNotBlank() &&
                                    path.length < 200 &&
                                    !path.contains('/') &&
                                    !path.contains('\\') &&
                                    path !in setOf(".", ".."),
                            ) {
                                "请提供所选目录中的单个文件名"
                            }
                            val root =
                                androidx.documentfile.provider.DocumentFile.fromTreeUri(
                                    this@AgentService,
                                    android.net.Uri.parse(task.workspace),
                                ) ?: error("请先选择工作区")
                            val file = root.findFile(path)?.takeIf { it.isFile } ?: error("文件不存在")
                            approvePublication(task, snapshot, "/workspace/$path", epoch)
                            contentResolver.openInputStream(file.uri)!!.use {
                                store.save(
                                    it,
                                    task.sessionId,
                                    path,
                                    file.type ?: "application/octet-stream",
                                )
                            }
                        }
                        else -> error("文件来源无效")
                    }
                }
        withContext(Dispatchers.IO) {
            AttachmentStore(this@AgentService)
                .recordSource(
                    info.id,
                    args.getString("source") +
                        "/" +
                        args
                            .getString("path")
                            .substringAfter("session_${task.sessionId}/", args.getString("path"))
                            .trimStart('/'),
                    task.id,
                )
        }
        assertActive(epoch)
        AssistantState.sessions
            .first { it.id == task.sessionId }
            .messages
            .add(ChatEntry("file", "", files = listOf(info.id), taskId = task.id))
        AssistantState.persist()
        return info
            .json()
            .put("preview", info.link)
            .put("markdown", "[${info.name.replace('[', '（').replace(']', '）')}](${info.link})")
            .put("message", "文件已加入会话，可点击预览")
    }

    private suspend fun approvePublication(
        task: PhoneTask,
        snapshot: TerminalSnapshot,
        path: String,
        epoch: Long,
    ) {
        if (task.access == "full") return
        val inspection =
            TerminalPolicy(snapshot.rules)
                .inspectPaths(
                    listOf(PathUse(path, "r")),
                    "/dev.deepseek.mobile/workplace/session_${task.sessionId}",
                )
        check(inspection.decision != RuleDecision.DENY) { "文件读取被路径规则禁止" }
        val verdict =
            reviewOperation(
                task,
                JSONObject()
                    .put("kind", "publish_file")
                    .put("path", path)
                    .put("static_decision", inspection.decision.name)
                    .put("static_reasons", JSONArray(inspection.reasons)),
                ReviewerConfig.parse(snapshot.reviewer),
            )
        check(verdict.decision != RuleDecision.DENY) { "文件预览未通过审查：${verdict.reason}" }
        if (task.access == "auto") {
            check(verdict.decision == RuleDecision.ALLOW) { "替我审批未允许导出此文件" }
        } else if (
            inspection.decision == RuleDecision.SENSITIVE ||
                verdict.decision == RuleDecision.SENSITIVE
        ) {
            // Share the existing, explicit operation confirmation surface.
            withContext(Dispatchers.Main) {
                approvalLock.withLock {
                    val pending = CompletableDeferred<Boolean>()
                    approval = pending
                    task.approval = true
                    task.status = "waiting"
                    task.question = "允许将文件加入会话预览？\n$path"
                    notifyEvent(task, "文件预览等待确认")
                    try {
                        check(withTimeout(600000) { pending.await() }) { "用户拒绝文件预览" }
                        assertActive(epoch)
                    } finally {
                        if (approval === pending) approval = null
                        task.approval = false
                        task.question = ""
                        if (task.status == "waiting") task.status = "running"
                    }
                }
            }
        }
    }

    private suspend fun workspace(method: String, args: JSONObject): Any =
        workspaceFileLock.withLock {
            withContext(Dispatchers.IO) {
                val task = AssistantState.running ?: error("没有运行中的任务")
                val root =
                    androidx.documentfile.provider.DocumentFile.fromTreeUri(
                        this@AgentService,
                        android.net.Uri.parse(task.workspace),
                    ) ?: error("请先选择工作区")
                fun resolve(path: String): androidx.documentfile.provider.DocumentFile? {
                    var file: androidx.documentfile.provider.DocumentFile? = root
                    if (path.isNotEmpty())
                        for (part in path.split('/')) {
                            check(file?.isDirectory == true) { "上级目录不存在" }
                            file = file?.findFile(part)
                        }
                    return file
                }
                if (method == "workspace_list") {
                    val path = WorkspaceText.path(args.optString("path"), rootAllowed = true)
                    val directory = resolve(path)
                    check(directory?.isDirectory == true) { "目录不存在" }
                    val files = directory!!.listFiles().sortedBy { it.name }
                    val offset = args.optInt("offset").coerceAtLeast(0)
                    return@withContext JSONObject()
                        .put(
                            "files",
                            JSONArray(
                                files.drop(offset).take(250).map {
                                    JSONObject()
                                        .put("name", it.name)
                                        .put("directory", it.isDirectory)
                                        .put("bytes", it.length())
                                },
                            ),
                        )
                        .put("total", files.size)
                        .put("offset", offset)
                }
                val name = WorkspaceText.path(args.getString("name"))
                var file = resolve(name)
                fun read(target: androidx.documentfile.provider.DocumentFile): String {
                    check(target.isFile) { "请选择普通文件" }
                    val bytes =
                        contentResolver.openInputStream(target.uri)!!.use {
                            readBounded(it, 100001)
                        }
                    check(bytes.size <= 100000) { "文件超过 100KB" }
                    return Charsets.UTF_8.newDecoder()
                        .decode(java.nio.ByteBuffer.wrap(bytes))
                        .toString()
                }
                if (method == "workspace_read") {
                    check(file != null) { "文件不存在" }
                    val text = read(file!!)
                    JSONObject()
                        .put("name", name)
                        .put("text", text)
                        .put("sha256", WorkspaceText.hash(text))
                } else {
                    require(method == "workspace_write")
                    val text = args.getString("text")
                    require(text.toByteArray().size <= 100000) { "内容超过 100KB" }
                    if (args.has("expected_sha256"))
                        WorkspaceText.checkVersion(
                            file?.let(::read),
                            args.getString("expected_sha256"),
                        )
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (file == null) {
                        val parent = resolve(name.substringBeforeLast('/', ""))
                        check(parent?.isDirectory == true) { "请先创建上级目录" }
                        file = parent!!.createFile("text/plain", name.substringAfterLast('/'))
                    }
                    check(file != null && file!!.isFile) { "无法创建文件" }
                    contentResolver.openOutputStream(file!!.uri, "wt")!!.use {
                        it.write(text.toByteArray())
                    }
                    JSONObject()
                        .put("name", name)
                        .put("sha256", WorkspaceText.hash(text))
                        .put("message", "已写入 $name")
                }
            }
        }

    /**
     * A pet tap may refresh the native account cache, at most once a minute, never a model call.
     */
    fun refreshPetBalance() {
        if (!AssistantState.configured || AssistantState.balanceRefreshing) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (
            (AssistantState.balanceUpdatedAt > 0L &&
                now - AssistantState.balanceUpdatedAt < 60000L) || now - balanceAttemptAt < 60000L
        )
            return
        balanceAttemptAt = now
        AssistantState.balanceRefreshing = true
        scope.launch {
            try {
                fetchBalance()
            } catch (_: CancellationException) {
                throw CancellationException("balance refresh cancelled")
            } catch (_: Exception) {
                /* Keep the last successful value; tapping a pet must not open an error snackbar. */
            } finally {
                AssistantState.balanceRefreshing = false
            }
        }
    }

    private suspend fun fetchBalance(): Any =
        withContext(Dispatchers.IO) {
            val revision = credentialRevision
            val key = keys.read()
            check(key.isNotBlank()) { "请先保存密钥" }
            val c =
                URL("https://api.deepseek.com/user/balance").openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 15000
                c.readTimeout = 20000
                c.instanceFollowRedirects = false
                c.setRequestProperty("Authorization", "Bearer $key")
                check(c.responseCode == 200) { "余额查询失败（${c.responseCode}）" }
                val result = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                val a = result.optJSONArray("balance_infos") ?: JSONArray()
                val summary =
                    (0 until a.length()).joinToString(" / ") { i ->
                        val v = a.getJSONObject(i)
                        "${v.optString("currency")} ${v.optString("total_balance")}"
                    }
                withContext(Dispatchers.Main) {
                    if (credentialRevision == revision) {
                        AssistantState.balance = summary.ifBlank { "暂无额度信息" }
                        for (i in 0 until a.length()) a.getJSONObject(i).let { balance ->
                            ExperienceSettings.recordBalance(
                                dev.deepseek.mobile.terminal.sha256(key),
                                balance.optString("currency"),
                                balance.optString("total_balance"),
                            )
                        }
                        AssistantState.balanceUpdatedAt = android.os.SystemClock.elapsedRealtime()
                        WhaleWidget.updateAll(this@AgentService)
                    }
                }
                result
            } finally {
                c.disconnect()
            }
        }

    private suspend fun officialWebRequest(
        tool: OfficialWebTool,
        args: JSONObject,
        id: String,
        epoch: Long,
    ): JSONObject {
        val task = AssistantState.running ?: error("任务已停止")
        check(ExperienceSettings.browserMode.official) { "官方浏览工具已关闭" }
        val request = tool.request(args)
        val permissions = terminal.harness.permissions
        val context = terminal.harness.context(task)
        val resources =
            listOfNotNull(tool.endpoint, request.optString("url").takeIf { it.isNotBlank() })
        return withContext(Dispatchers.IO) {
            assertActive(epoch)
            for (resource in resources) {
                val rule = permissions.evaluate(context, Capability.NETWORK, resource)
                check(
                    rule.effect == PermissionEffect.ALLOW ||
                        (rule.effect == PermissionEffect.ASK && task.access == "ask"),
                ) {
                    rule.reason
                }
            }
            val key = keys.read()
            check(key.isNotEmpty()) { "请先保存 API Key" }
            val connection = URL(tool.endpoint).openConnection() as HttpURLConnection
            connections[id] = connection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 20_000
                connection.readTimeout = 60_000
                connection.instanceFollowRedirects = false
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Authorization", "Bearer $key")
                assertActive(epoch)
                connection.outputStream.use {
                    it.write(request.toString().toByteArray(Charsets.UTF_8))
                }
                val code = connection.responseCode
                check(code == 200) {
                    when (code) {
                        400 -> "DeepSeek 拒绝网页请求参数（400）"
                        401 -> "API Key 无效，请检查后重试"
                        402 -> "DeepSeek 余额不足"
                        403 -> "DeepSeek 网页抓取请求被安全策略拒绝（403）"
                        404 -> "DeepSeek 未能取得可读的网页正文（404）"
                        429 -> "DeepSeek 网页工具请求过于频繁，请稍后再试"
                        408,
                        504 -> "DeepSeek 网页请求超时（$code）"
                        else -> "DeepSeek 网页请求失败（HTTP $code）"
                    }
                }
                val limit = 2 * 1024 * 1024
                val body = connection.inputStream.use { readBounded(it, limit + 1) }
                check(body.size <= limit) { "DeepSeek 返回的网页内容超过应用读取上限" }
                assertActive(epoch)
                tool.result(JSONObject(String(body, Charsets.UTF_8)), request) { url ->
                    permissions.evaluate(context, Capability.NETWORK, url).effect ==
                        PermissionEffect.ALLOW
                }
            } finally {
                connections.remove(id, connection)
                connection.disconnect()
            }
        }
    }

    private suspend fun complete(
        body: JSONObject,
        id: String,
        epoch: Long?,
        timeoutMillis: Int = 180_000,
        credentialId: String = "",
    ): JSONObject =
        withContext(Dispatchers.IO) {
            val key = keys.read(credentialId)
            check(key.isNotEmpty()) { "请先保存 API Key" }
            check(body.optString("model") in setOf("deepseek-flash", "deepseek-v4-pro")) {
                "不支持此模型"
            }
            if (epoch != null) assertActive(epoch)
            val connection =
                URL("https://api.deepseek.com/chat/completions").openConnection()
                    as HttpURLConnection
            connections[id] = connection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = minOf(20_000, timeoutMillis)
                connection.readTimeout = timeoutMillis
                connection.instanceFollowRedirects = false
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Authorization", "Bearer $key")
                connection.setRequestProperty(
                    "User-Agent",
                    "deepseek-harness/0.2.1-rc.3 (+https://github.com/deepseek-ai/deepseek-harness) DSHA/${BuildConfig.VERSION_NAME}",
                )
                if (epoch != null) assertActive(epoch)
                connection.outputStream.use {
                    it.write(
                        JSONObject(body.toString())
                            .apply { remove("_agentId") }
                            .toString()
                            .toByteArray(Charsets.UTF_8),
                    )
                }
                val code = connection.responseCode
                if (code !in 200..299) {
                    val message =
                        when (code) {
                            401 -> "API Key 无效，请检查后重试"
                            402 -> "DeepSeek 余额不足"
                            429 -> "请求过于频繁，请稍后再试"
                            in 500..599 -> "DeepSeek 服务暂时不可用（$code）"
                            else -> "DeepSeek 请求失败（HTTP $code），请检查配置或稍后重试"
                        }
                    error(message)
                }
                if (body.optBoolean("stream")) {
                    val stream = CompletionStream()
                    var lastPublished = 0L
                    suspend fun publish(done: Boolean) {
                        if (epoch != null) assertActive(epoch)
                        val task = AssistantState.running ?: return
                        withContext(Dispatchers.Main) {
                            val owner = body.optString("_agentId")
                            val messages =
                                if (owner.isBlank())
                                    AssistantState.sessions
                                        .firstOrNull { it.id == task.sessionId }
                                        ?.messages
                                else TeamState.agents.firstOrNull { it.id == owner }?.messages
                            if (messages != null) {
                                if (owner.isBlank()) {
                                    task.avatarPhase =
                                        if (stream.text.isNotBlank()) AvatarPhase.WRITING
                                        else AvatarPhase.THINKING
                                }
                                updateStream(
                                    messages,
                                    id,
                                    "reasoning",
                                    stream.reasoning.toString(),
                                    done,
                                )
                                updateStream(
                                    messages,
                                    id,
                                    "assistant",
                                    stream.text.toString(),
                                    false,
                                )
                            }
                        }
                    }
                    connection.inputStream.bufferedReader().use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (epoch != null) assertActive(epoch)
                            if (!line.startsWith("data:")) continue
                            val payload = line.substringAfter(':').trim()
                            if (payload == "[DONE]") break
                            stream.accept(JSONObject(payload))
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (now - lastPublished > 60) {
                                publish(false)
                                lastPublished = now
                            }
                        }
                    }
                    publish(true)
                    stream.result()
                } else {
                    val text = connection.inputStream.bufferedReader().use { it.readText() }
                    if (epoch != null) assertActive(epoch)
                    JSONObject(text)
                }
            } finally {
                connections.remove(id)
                connection.disconnect()
            }
        }

    private var backupJob: Job? = null

    fun exportBackup(downloads: Boolean = false): Job {
        backupJob
            ?.takeIf { it.isActive }
            ?.let {
                toast("正在打包备份")
                return it
            }
        backupJob = scope.launch {
            runCatching {
                AssistantState.persist()
                TeamState.persist(this@AgentService)
                val preferences =
                    JSONObject()
                        .put("phrases", JSONArray(ExperienceSettings.phrases.map { it.json() }))
                        .put("textScale", ExperienceSettings.textScale)
                        .put("lineSpacing", ExperienceSettings.lineSpacing)
                        .put("taskNavigation", ExperienceSettings.taskNavigation)
                        .put("reasoning", ExperienceSettings.reasoningMode)
                        .put("browser", ExperienceSettings.browserEnabled)
                        .put("memoryEnabled", ExperienceSettings.memoryEnabled)
                        .put("maxAgents", ExperienceSettings.maxAgents)
                        .toString()
                        .toByteArray()
                val sessionId = AssistantState.selectedId
                withContext(Dispatchers.IO) {
                    AssistantState.awaitPersistence()
                    if (downloads) writeDownloadBackup(preferences)
                    else {
                        val output = java.io.ByteArrayOutputStream()
                        writeBackup(output, preferences, 28L * 1024 * 1024)
                        AttachmentStore(this@AgentService)
                            .save(
                                output.toByteArray().inputStream(),
                                sessionId,
                                "DSHA-backup-${java.time.LocalDate.now()}.zip",
                                "application/zip",
                            )
                            .id
                    }
                }
            }
                .onSuccess {
                    if (downloads) toast("已保存到 Download/$it")
                    else MainActivity.current?.exportAttachment(it)
                }
                .onFailure { if (it !is CancellationException) toast("备份失败：${it.message}") }
        }
        return requireNotNull(backupJob)
    }

    private fun writeDownloadBackup(preferences: ByteArray): String {
        val name =
            "DSHA-backup-" +
                java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss")) +
                ".zip"
        val destination =
            contentResolver.insert(
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                    put(
                        android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_DOWNLOADS,
                    )
                    put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
                },
            ) ?: error("无法创建下载文件")
        try {
            val output = contentResolver.openOutputStream(destination) ?: error("无法写入下载文件")
            output.use { writeBackup(it, preferences, Long.MAX_VALUE) }
            check(
                contentResolver.update(
                    destination,
                    android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                    },
                    null,
                    null,
                ) == 1,
            ) {
                "无法完成下载文件"
            }
            return name
        } catch (error: Throwable) {
            contentResolver.delete(destination, null, null)
            throw error
        }
    }

    private fun writeBackup(output: java.io.OutputStream, preferences: ByteArray, limit: Long) {
        java.util.zip.ZipOutputStream(output).use { zip ->
            val dataRoot = filesDir.canonicalFile
            val snapshot = BackupSnapshot.capture(this, dataRoot)
            val roots =
                listOf(
                    "conversations-v3.json",
                    "agents-v1.json",
                    "changes-v1.json",
                    "memory.md",
                    "persona_memory.md",
                    "projects",
                    "sessions",
                    "events",
                    "attachments",
                    "screens",
                    "terminal/workplace",
                    "harness/instructions",
                )
            var total = 0L
            for (relative in roots) {
                val root = File(dataRoot, relative)
                if (!root.exists()) continue
                root
                    .walkTopDown()
                    .onEnter { it.canonicalFile == it.absoluteFile }
                    .filter {
                        it.isFile &&
                            it.canonicalFile == it.absoluteFile &&
                            snapshot.includes(it, dataRoot)
                    }
                    .forEach { file ->
                        val frozen = snapshot.texts[file.absolutePath]
                        total += frozen?.size?.toLong() ?: file.length()
                        require(total <= limit) { "备份超过 28 MB，请使用「保存到下载」" }
                        zip.putNextEntry(
                            java.util.zip.ZipEntry(
                                file.relativeTo(dataRoot).invariantSeparatorsPath,
                            ),
                        )
                        if (frozen != null) zip.write(frozen)
                        else file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
            }
            zip.putNextEntry(java.util.zip.ZipEntry("experience.json"))
            zip.write(preferences)
            zip.closeEntry()
        }
    }

    private val streamIndices = mutableMapOf<String, Int>()

    private fun updateStream(
        messages: MutableList<ChatEntry>,
        request: String,
        kind: String,
        text: String,
        done: Boolean,
    ) {
        if (text.isBlank()) return
        val key = "$request/$kind"
        val index =
            streamIndices[key]
                ?: messages.size.also {
                    streamIndices[key] = it
                    messages.add(
                        ChatEntry("stream_$kind", "", taskId = AssistantState.activeTaskId),
                    )
                }
        if (index in messages.indices)
            messages[index] =
                messages[index].copy(type = if (done) kind else "stream_$kind", text = text)
    }

    override fun onDestroy() {
        sandboxProtection?.close()
        sandboxProtection = null
        dev.deepseek.mobile.proxy.MihomoManager.stopWithAgentService()
        active = false
        generation++
        floating.hide()
        WhaleSounds.dispose()
        approval?.cancel()
        terminalApproval?.cancel()
        terminal.close()
        browser.close()
        AssistantState.running?.let {
            it.status = "interrupted"
            it.progress = "服务已退出"
        }
        AssistantState.persist()
        connections.values.forEach { it.disconnect() }
        question?.cancel()
        releaseWake()
        scope.cancel()
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.removeJavascriptInterface("Android")
        webView.destroy()
        if (current === this) current = null
        AssistantState.ready = false
        AssistantState.activeTaskId = ""
        super.onDestroy()
    }
}
