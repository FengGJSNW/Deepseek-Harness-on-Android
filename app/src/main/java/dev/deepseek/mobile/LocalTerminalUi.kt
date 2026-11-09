package dev.deepseek.mobile

import android.annotation.SuppressLint
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.deepseek.mobile.harness.*
import dev.deepseek.mobile.runtime.RuntimeTerminal
import kotlinx.coroutines.*
import org.json.JSONObject

internal object LocalTerminalState {
    var sessionId by mutableStateOf("")

    fun open(id: String) {
        WorkspaceState.open(WorkspaceTab.Terminal)
        sessionId = id
    }
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
internal fun LocalTerminalPane(conversation: Conversation) {
    val id = conversation.id
    val context = LocalContext.current
    val harness = remember(context) { HarnessCoordinator.get(context) }
    val task =
        remember(id) {
            PhoneTask(
                sessionId = id,
                projectId = conversation.projectId,
                text = "本地用户终端",
                images = emptyList(),
                model = conversation.model,
                effort = "low",
                access = "full",
                phoneTools = false,
                fileTools = true,
                workspace = "",
                terminalEnabled = true,
                terminalConfig = "{\"level\":\"sandbox\"}",
            )
        }
    val scope = rememberCoroutineScope()
    val info by harness.runtimes.states.collectAsState()
    val domain = harness.runtimes.scope(task)
    var terminal by remember { mutableStateOf<RuntimeTerminal?>(null) }
    var view by remember { mutableStateOf<WebView?>(null) }
    var ready by remember { mutableStateOf(false) }
    var rows by remember { mutableIntStateOf(24) }
    var cols by remember { mutableIntStateOf(80) }
    var error by remember { mutableStateOf("") }
    var closing by remember { mutableStateOf(false) }
    var processExited by remember { mutableStateOf<Int?>(null) }
    val pending = remember { java.io.ByteArrayOutputStream() }
    val fontScale = LocalDensity.current.fontScale
    fun write(bytes: ByteArray) {
        if (!ready) {
            if (pending.size() < 131072) pending.write(bytes)
            return
        }
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        view?.evaluateJavascript("window.consoleOutput(${JSONObject.quote(encoded)})", null)
    }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(id, attempt) {
        android.util.Log.d("DSHA-Console", "Opening local terminal")
        try {
            val permission =
                harness.permissions.evaluate(
                    PermissionContext(sessionId = id, projectId = task.projectId, actor = "user"),
                    Capability.SANDBOX_EXEC,
                    "interactive",
                )
            check(permission.effect == PermissionEffect.ALLOW) { permission.reason }
            terminal =
                harness.runtimes.openTerminal(
                    task,
                    onData = { _, bytes ->
                        scope.launch(Dispatchers.Main.immediate) { write(bytes) }
                    },
                    onExit = { _, code ->
                        scope.launch(Dispatchers.Main.immediate) { processExited = code }
                    },
                )
            terminal?.let { it.vm.resize(it.id, rows, cols) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("DSHA-Console", "Local terminal initialization failed", e)
            error = e.message.orEmpty()
        }
    }
    fun sendInput(text: String) {
        val target = terminal ?: return
        if (processExited != null) return
        val bytes = text.toByteArray(Charsets.UTF_8)
        for (offset in bytes.indices step 8192) target.vm.input(
            target.id,
            bytes.copyOfRange(offset, minOf(offset + 8192, bytes.size)),
        )
    }
    suspend fun save(active: RuntimeTerminal) {
        harness.runtimes.closeTerminal(active)
        val merged =
            withContext(Dispatchers.IO) {
                harness.runtimes.resolveWorktree(
                    harness.workspaces.load(active.tree.scope, active.tree.taskId),
                    true,
                    PermissionContext(sessionId = id, projectId = task.projectId, actor = "user"),
                ) {
                    true
                }
            }
        if (!merged.getBoolean("merged")) AssistantState.error = "终端工作区发生冲突，改动已保留，请在运行环境中查看"
    }
    fun close() {
        if (closing) return
        closing = true
        scope.launch {
            try {
                withContext(NonCancellable) {
                    terminal?.let { save(it) }
                    terminal = null
                }
                if (WorkspaceState.sessionId == id) WorkspaceState.close(WorkspaceTab.Terminal)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message.orEmpty()
                closing = false
            }
        }
    }
    Column(
        Modifier.fillMaxSize().background(Color(0xFFFAFAF9)).testTag("local-terminal"),
    ) {
        Row(
            Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SmallIcon(AppIcons.ArrowBack, "保存并关闭终端") { close() }
            Column(Modifier.weight(1f)) {
                Text("本地终端", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(
                    conversation.title + " · /workspace",
                    fontSize = 10.sp,
                    color = WhaleMuted,
                    maxLines = 1,
                )
            }
            TextButton(
                onClick = { sendInput("\u0003") },
                enabled = terminal != null && processExited == null,
            ) {
                Text("Ctrl+C", fontSize = 11.sp)
            }
        }
        HorizontalDivider(color = WhaleLine)
        processExited?.let { code ->
            Text(
                "进程已退出 · $code",
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("terminal-process-exit"),
                fontSize = 12.sp,
                color = WhaleMuted,
            )
        }
        if (error.isNotBlank()) {
            Text(error, Modifier.padding(16.dp), color = Color(0xFFB55950), fontSize = 12.sp)
            if (terminal == null)
                TextButton(
                    onClick = {
                        error = ""
                        attempt++
                    },
                ) {
                    Text("重试")
                }
        }
        if ((terminal == null && error.isBlank()) || closing) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 1.5.dp)
                Text(
                    if (closing) "保存工作区与环境…" else info[domain.key]?.detail ?: "准备终端…",
                    Modifier.padding(start = 10.dp),
                    fontSize = 12.sp,
                    color = WhaleMuted,
                )
            }
        }
        AndroidView(
            factory = { c ->
                WebView(c).apply {
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.domStorageEnabled = false
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.setSupportMultipleWindows(false)
                    isSaveEnabled = false
                    webViewClient =
                        LocalDocumentClient(
                            c,
                            "console",
                            setOf("console.html", "console.js", "console.css", "xterm.css"),
                            setOf("jetbrains_mono.ttf", "noto_sans_sc.ttf"),
                        )
                    addJavascriptInterface(
                        object {
                            @JavascriptInterface
                            fun ready() {
                                scope.launch(Dispatchers.Main.immediate) {
                                    ready = true
                                    write(pending.toByteArray())
                                    pending.reset()
                                }
                            }

                            @JavascriptInterface
                            fun input(text: String) {
                                if (text.length <= 65536)
                                    scope.launch(Dispatchers.Main.immediate) { sendInput(text) }
                            }

                            @JavascriptInterface
                            fun resize(height: Int, width: Int) {
                                scope.launch(Dispatchers.Main.immediate) {
                                    if (
                                        height in 4..200 &&
                                            width in 10..300 &&
                                            (rows != height || cols != width)
                                    ) {
                                        rows = height
                                        cols = width
                                        terminal?.let { it.vm.resize(it.id, rows, cols) }
                                    }
                                }
                            }
                        },
                        "ConsoleNative",
                    )
                    view = this
                    loadUrl("https://document.dsha.invalid/assets/console.html")
                }
            },
            update = { web ->
                if (ready)
                    web.evaluateJavascript(
                        "window.consoleConfigure(${JSONObject().put("size", 12.5f * fontScale).put("system", FontSettings.preset == FontPreset.SYSTEM)})",
                        null,
                    )
            },
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("terminal-emulator"),
            onRelease = {
                it.removeJavascriptInterface("ConsoleNative")
                it.destroy()
                view = null
            },
        )
        HorizontalDivider(color = WhaleLine)
        Row(
            Modifier.fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf("Esc" to "\u001b", "Tab" to "\t", "Ctrl+D" to "\u0004").forEach { (label, value)
                ->
                TextButton(
                    onClick = { sendInput(value) },
                    enabled = terminal != null && processExited == null,
                ) {
                    Text(label, fontSize = 11.sp)
                }
            }
            listOf(0f to "\u001b[A", 180f to "\u001b[B", 270f to "\u001b[D", 90f to "\u001b[C")
                .forEach { (rotation, value) ->
                    IconButton(
                        onClick = { sendInput(value) },
                        enabled = terminal != null && processExited == null,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            AppIcons.ArrowUpward,
                            "方向键",
                            Modifier.size(16.dp).graphicsLayer { rotationZ = rotation },
                        )
                    }
                }
        }
    }
    DisposableEffect(id) {
        onDispose {
            if (!closing)
                terminal?.let { active ->
                    CoroutineScope(Dispatchers.Main.immediate).launch {
                        try {
                            save(active)
                        } catch (e: Exception) {
                            AssistantState.error = e.message.orEmpty()
                        }
                    }
                }
        }
    }
}
