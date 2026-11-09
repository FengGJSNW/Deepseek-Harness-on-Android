package dev.deepseek.mobile

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.*
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONTokener

internal data class OfficialAttachment(
    val uri: android.net.Uri,
    val name: String,
    val mime: String,
    val size: Long,
)

/** Official DOM transport. Native login and Work tools never share a JavaScript bridge. */
internal object OfficialChatNavigation {
    var view: WebView? = null
        private set

    var snapshot by mutableStateOf(OfficialSnapshot())
        private set

    var auth by mutableStateOf(OfficialAuthSnapshot())
        private set

    var accessConfirmed by mutableStateOf(false)
        private set

    var loginOpen by mutableStateOf(false)
        private set

    var loading by mutableStateOf(false)
        private set

    var failure by mutableStateOf("")
        private set

    var authBusy by mutableStateOf(false)
        private set

    var sending by mutableStateOf(false)
        private set

    var modeBusy by mutableStateOf(false)
        private set

    val attachments = mutableStateListOf<OfficialAttachment>()
    private var sendJob: Job? = null
    private var requestedPath: String? = null
    private var pendingSwitch = false
    private var epoch = 0
    private var actionGeneration = 0
    private var job: Job? = null
    private var actionJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @SuppressLint("SetJavaScriptEnabled")
    fun ensure(context: Context) {
        if (view != null) return
        epoch++
        val browser = WebView(context)
        view = browser
        browser.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
            saveFormData = false
        }
        if (android.os.Build.VERSION.SDK_INT >= 26)
            browser.importantForAutofill =
                android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(browser, false)
        browser.webViewClient =
            object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean =
                    r.isForMainFrame &&
                        !(r.url.scheme == "https" && r.url.host == "chat.deepseek.com")

                override fun onPageStarted(
                    v: WebView,
                    url: String?,
                    icon: android.graphics.Bitmap?,
                ) {
                    loading = true
                    failure = ""
                }

                override fun onPageFinished(v: WebView, url: String?) {
                    if (v === view && url == v.url) {
                        loading = false
                        requestedPath = null
                        CookieManager.getInstance().flush()
                    }
                }

                override fun onReceivedError(
                    v: WebView,
                    r: WebResourceRequest,
                    e: WebResourceError,
                ) {
                    if (r.isForMainFrame) {
                        loading = false
                        failure = "官方聊天暂时无法连接，请重试。"
                    }
                }

                override fun onRenderProcessGone(
                    v: WebView,
                    detail: RenderProcessGoneDetail,
                ): Boolean {
                    cancelSend()
                    attachments.clear()
                    failure = "官方连接已退出，请重试。"
                    loading = false
                    job?.cancel()
                    (v.parent as? android.view.ViewGroup)?.removeView(v)
                    if (view === v) view = null
                    v.destroy()
                    return true
                }
            }
        measure()
        browser.loadUrl("https://chat.deepseek.com/")
        job = scope.launch {
            while (isActive && view === browser) {
                if (AssistantState.appMode == "chat" || loginOpen) runCatching { refresh() }
                delay(650)
            }
        }
    }

    fun requestChat(context: Context) {
        // Keep Work, its drawer and its draft in place until a fresh DOM confirms authentication.
        cancelSend()
        requestedPath = null
        epoch++
        pendingSwitch = true
        loginOpen = !accessConfirmed
        auth = OfficialAuthSnapshot()
        failure = ""
        if (!accessConfirmed) AssistantState.appMode = "work"
        ensure(context)
        scope.launch { runCatching { refresh(force = true) } }
    }

    fun cancelLogin() {
        cancelSend()
        epoch++
        actionGeneration++
        pendingSwitch = false
        loginOpen = false
        actionJob?.cancel()
        authBusy = false
        scope.launch { runCatching { evaluate(OfficialAuthDom.clearFieldsScript) } }
        AssistantState.persist()
    }

    private suspend fun refresh(force: Boolean = false) {
        val source = view ?: return
        val ticket = epoch
        val value =
            evaluate(
                (if (force) "window.__dshaOfficialDirty=true;\n" else "") +
                    OfficialChatDom.snapshotScript,
            )
        if (source !== view || ticket != epoch) return
        if (value is JSONObject) snapshot = OfficialChatDom.parse(value)
        val path = runCatching {
            android.net.Uri.parse(snapshot.url).path.orEmpty().ifBlank { "/" }
        }
            .getOrDefault("")
        // A usable composer does not depend on analytics/subresources finishing page load.
        if (
            snapshot.authenticated &&
                !snapshot.unsupported &&
                (requestedPath == null || path == requestedPath)
        ) {
            loading = false
            requestedPath = null
        }
        val login = evaluate(OfficialAuthDom.snapshotScript)
        if (source !== view || ticket != epoch) return
        if (login is JSONObject) {
            auth = OfficialAuthDom.parse(login)
            if (auth.mode == "authenticated") {
                accessConfirmed = true
                if (pendingSwitch) {
                    pendingSwitch = false
                    loginOpen = false
                    AssistantState.appMode = "chat"
                    AssistantState.persist()
                }
            } else if (auth.mode in listOf("sms", "password") && accessConfirmed) {
                cancelSend()
                attachments.clear()
                accessConfirmed = false
                if (AssistantState.appMode == "chat") {
                    AssistantState.appMode = "work"
                    pendingSwitch = true
                    loginOpen = true
                    AssistantState.persist()
                }
            }
        }
    }

    fun setLoginMode(mode: String) {
        if (authBusy || mode !in listOf("sms", "password")) return
        authAction {
            val result = evaluate(OfficialAuthDom.modeScript(mode)) as? JSONObject
            check(result?.optBoolean("ready") == true) { result?.optString("error") ?: "切换登录方式失败" }
            delay(100)
            refresh(force = true)
        }
    }

    fun submitLogin(mode: String, account: String, secret: String, codeOnly: Boolean = false) {
        if (authBusy || mode !in listOf("sms", "password")) return
        authAction {
            val prepared =
                evaluate(OfficialAuthDom.prepareScript(mode, account.take(200), secret.take(256)))
                    as? JSONObject
            check(prepared?.optBoolean("prepared") == true) {
                prepared?.optString("error") ?: "登录输入失败"
            }
            delay(80)
            val result = evaluate(OfficialAuthDom.clickScript(codeOnly)) as? JSONObject
            check(result?.optBoolean("submitted") == true) {
                result?.optString("error") ?: "登录提交失败"
            }
            delay(500)
            refresh(force = true)
        }
    }

    private fun authAction(block: suspend () -> Unit) {
        val token = ++actionGeneration
        authBusy = true
        failure = ""
        actionJob = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failure = "官方登录暂不可用，请检查输入或重试。"
            } finally {
                if (token == actionGeneration) authBusy = false
            }
        }
    }

    suspend fun showVerification(): Boolean =
        evaluate(OfficialAuthDom.showVerificationScript) == true

    fun hideVerification() {
        scope.launch { runCatching { evaluate(OfficialAuthDom.hideVerificationScript) } }
    }

    /** Explicit sign-out is scoped to official Chat; never clear every browser's cookies. */
    fun signOut() {
        cancelSend()
        attachments.clear()
        requestedPath = null
        if (authBusy) return
        epoch++
        actionJob?.cancel()
        pendingSwitch = false
        loginOpen = false
        authAction {
            if (view != null) check(evaluate(OfficialAuthDom.clearOriginScript) == true)
            val cookies = CookieManager.getInstance()
            val urls =
                listOf(
                    "https://chat.deepseek.com/",
                    "https://chat.deepseek.com/a/",
                    "https://chat.deepseek.com/api/v0/",
                )
            // Only cookie names are used locally to expire this origin's cookies. No values leave
            // here.
            val names =
                urls
                    .flatMap { url ->
                        cookies.getCookie(url).orEmpty().split(';').map {
                            it.substringBefore('=').trim()
                        }
                    }
                    .filter { it.matches(Regex("[^\\s;=]{1,256}")) }
                    .distinct()
            for (name in names) for (path in listOf("/", "/a", "/api", "/api/v0")) for (domain in
                listOf("", "; Domain=chat.deepseek.com", "; Domain=deepseek.com")) {
                suspendCancellableCoroutine<Unit> { c ->
                    cookies.setCookie(
                        "https://chat.deepseek.com/",
                        "$name=; Max-Age=0; Path=$path$domain; Secure; HttpOnly",
                    ) {
                        if (c.isActive) c.resumeWith(Result.success(Unit))
                    }
                }
            }
            cookies.flush()
            WebStorage.getInstance().deleteOrigin("https://chat.deepseek.com")
            snapshot = OfficialSnapshot()
            auth = OfficialAuthSnapshot()
            accessConfirmed = false
            AssistantState.appMode = "work"
            AssistantState.persist()
            view?.apply {
                clearHistory()
                loadUrl("https://chat.deepseek.com/sign_in")
            }
        }
    }

    fun measure() {
        val browser = view ?: return
        if (browser.parent != null) return
        browser.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(
                1080,
                android.view.View.MeasureSpec.EXACTLY,
            ),
            android.view.View.MeasureSpec.makeMeasureSpec(
                1600,
                android.view.View.MeasureSpec.EXACTLY,
            ),
        )
        browser.layout(0, 0, 1080, 1600)
    }

    suspend fun evaluate(script: String): Any? =
        withTimeout(10000) {
            suspendCancellableCoroutine { continuation ->
                val browser = view
                if (browser == null)
                    continuation.resumeWith(Result.failure(IllegalStateException("官方页面未打开")))
                else
                    browser.evaluateJavascript(script) {
                        if (continuation.isActive)
                            continuation.resumeWith(runCatching { JSONTokener(it).nextValue() })
                    }
            }
        }

    fun open(path: String) {
        if (OfficialChatDom.sessionPath(path)) {
            cancelSend()
            epoch++
            requestedPath = path
            loading = true
            attachments.clear()
            failure = ""
            view?.loadUrl("https://chat.deepseek.com$path")
        }
    }

    fun newChat() {
        cancelSend()
        epoch++
        requestedPath = "/"
        loading = true
        attachments.clear()
        failure = ""
        view?.loadUrl("https://chat.deepseek.com/")
    }

    fun retry(context: Context) {
        requestedPath = null
        failure = ""
        if (view == null) ensure(context) else view?.reload()
    }

    fun toggleMode(mode: String) {
        if (modeBusy || sending || snapshot.generating) return
        modeBusy = true
        failure = ""
        val ticket = epoch
        scope.launch {
            try {
                val result = evaluate(OfficialChatDom.modeScript(mode)) as? JSONObject
                check(result?.optBoolean("changed") == true) {
                    result?.optString("error") ?: "模式切换失败"
                }
                delay(80)
                if (ticket == epoch) refresh(force = true)
            } catch (error: Exception) {
                if (ticket == epoch) failure = error.message ?: "模式切换失败"
            } finally {
                if (ticket == epoch) modeBusy = false
            }
        }
    }

    fun reportAttachmentPickerFailure() {
        failure = "文件选择器暂不可用，请重试"
    }

    fun pickAttachments(activity: MainActivity) {
        val ticket = epoch
        activity.pickOfficialAttachments { uris ->
            if (ticket == epoch && accessConfirmed) selectAttachments(activity, uris)
        }
    }

    fun selectAttachments(context: Context, uris: List<android.net.Uri>) {
        val ticket = epoch
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    uris.take(10).map { uri ->
                        val resolver = context.contentResolver
                        require(uris.size <= 10) { "一次最多选择10个附件" }
                        var name =
                            uri.lastPathSegment
                                ?.substringAfterLast('/')
                                ?.take(180)
                                .orEmpty()
                                .ifBlank { "附件" }
                        var size = 0L
                        if (uri.scheme == "file") size = java.io.File(uri.path.orEmpty()).length()
                        resolver
                            .query(
                                uri,
                                arrayOf(
                                    android.provider.OpenableColumns.DISPLAY_NAME,
                                    android.provider.OpenableColumns.SIZE,
                                ),
                                null,
                                null,
                                null,
                            )
                            ?.use { cursor ->
                                if (cursor.moveToFirst()) {
                                    name = cursor.getString(0).orEmpty().take(180).ifBlank { "附件" }
                                    if (!cursor.isNull(1)) size = cursor.getLong(1)
                                }
                            }
                        require(size <= 20L * 1024 * 1024) { "单个官方附件限20MiB" }
                        OfficialAttachment(
                            uri,
                            name,
                            resolver.getType(uri) ?: "application/octet-stream",
                            size,
                        )
                    }
                }
            }
            if (ticket == epoch)
                result
                    .onSuccess { files ->
                        if (attachments.size + files.size <= 10)
                            attachments.addAll(
                                files.filter { file -> attachments.none { it.uri == file.uri } },
                            )
                        else failure = "一次最多选择10个附件"
                    }
                    .onFailure { failure = it.message ?: "附件读取失败" }
        }
    }

    private fun cancelSend() {
        sendJob?.cancel()
        sendJob = null
        sending = false
        modeBusy = false
    }

    fun send(text: String, finished: (Boolean) -> Unit) {
        if (sending || (text.isBlank() && attachments.isEmpty())) return
        if (!accessConfirmed) {
            failure = "请先登录官方账户"
            finished(false)
            return
        }
        val browser = view ?: return
        val ticket = epoch
        val files = attachments.toList()
        val actual = text.ifBlank { "请查看这些附件" }.take(32000)
        sending = true
        failure = ""
        fun ensureCurrent() {
            check(ticket == epoch && view === browser && accessConfirmed) { "会话已切换，发送已取消" }
        }
        sendJob = scope.launch {
            val result = runCatching {
                ensureCurrent()
                val prepared = evaluate(OfficialChatDom.sendScript(actual)) as? JSONObject
                check(prepared?.optBoolean("prepared") == true) {
                    prepared?.optString("error") ?: "未找到官方输入框"
                }
                if (files.isNotEmpty()) {
                    val begin = evaluate(OfficialChatDom.beginAttachmentsScript) as? JSONObject
                    check(begin?.optBoolean("ready") == true) {
                        begin?.optString("error") ?: "附件入口暂不可用"
                    }
                    var total = 0L
                    files.forEach { file ->
                        val input =
                            withContext(Dispatchers.IO) {
                                browser.context.contentResolver.openInputStream(file.uri)
                            } ?: error("附件已无法读取")
                        input.use { stream ->
                            val buffer = ByteArray(192 * 1024)
                            var count = 0L
                            while (true) {
                                val n = withContext(Dispatchers.IO) { stream.read(buffer) }
                                if (n < 0) break
                                count += n
                                total += n
                                require(count <= 20L * 1024 * 1024 && total <= 40L * 1024 * 1024) {
                                    "附件超过20MiB单文件或40MiB总量限制"
                                }
                                ensureCurrent()
                                val encoded =
                                    android.util.Base64.encodeToString(
                                        buffer,
                                        0,
                                        n,
                                        android.util.Base64.NO_WRAP,
                                    )
                                check(
                                    (evaluate(OfficialChatDom.attachmentChunkScript(encoded))
                                            as? JSONObject)
                                        ?.optBoolean("ready") == true,
                                ) {
                                    "附件传输失败"
                                }
                            }
                        }
                        ensureCurrent()
                        check(
                            (evaluate(OfficialChatDom.finishAttachmentScript(file.name, file.mime))
                                    as? JSONObject)
                                ?.optBoolean("ready") == true,
                        ) {
                            "附件准备失败"
                        }
                    }
                    ensureCurrent()
                    check(
                        (evaluate(OfficialChatDom.commitAttachmentsScript) as? JSONObject)
                            ?.optBoolean("ready") == true,
                    ) {
                        "附件添加失败"
                    }
                }
                // React and the official uploader can update asynchronously. Click exactly once.
                withTimeout(if (files.isEmpty()) 6000L else 90000L) {
                    while (true) {
                        ensureCurrent()
                        val ready = evaluate(OfficialChatDom.readyToSendScript) as? JSONObject
                        if (ready?.optBoolean("ready") == true) break
                        delay(100)
                    }
                }
                ensureCurrent()
                val sent = evaluate(OfficialChatDom.clickSendScript) as? JSONObject
                check(sent?.optBoolean("sent") == true) { sent?.optString("error") ?: "官方发送失败" }
                withTimeout(10000) {
                    while (true) {
                        ensureCurrent()
                        val ack =
                            evaluate(
                                OfficialChatDom.acknowledgementScript(
                                    actual,
                                    prepared!!.optInt("users"),
                                    prepared.optString("url"),
                                ),
                            )
                                as? JSONObject
                        if (ack?.optBoolean("accepted") == true) break
                        delay(100)
                    }
                }
                ensureCurrent()
                refresh(force = true)
            }
            if (ticket == epoch && view === browser) {
                sending = false
                if (result.isSuccess) attachments.removeAll { it in files }
                result.onFailure {
                    failure =
                        if (it is TimeoutCancellationException) "官方页面尚未确认发送，请检查最后一条消息；草稿和附件已保留。"
                        else it.message ?: "官方聊天发送失败"
                    // Discard the page's unsubmitted upload state before a deliberate retry.
                    if (files.isNotEmpty()) browser.reload()
                }
                finished(result.isSuccess)
            }
            if (view === browser)
                browser.evaluateJavascript("delete window.__dshaOfficialUpload", null)
        }
    }

    fun dispose() {
        cancelSend()
        attachments.clear()
        requestedPath = null
        epoch++
        actionGeneration++
        job?.cancel()
        job = null
        actionJob?.cancel()
        actionJob = null
        view?.let {
            (it.parent as? android.view.ViewGroup)?.removeView(it)
            it.stopLoading()
            it.destroy()
        }
        view = null
        snapshot = OfficialSnapshot()
        auth = OfficialAuthSnapshot()
        loading = false
        sending = false
        authBusy = false
        accessConfirmed = false
        loginOpen = false
        pendingSwitch = false
    }
}
