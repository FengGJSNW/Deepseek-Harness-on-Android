package dev.deepseek.mobile.runtime

import android.annotation.SuppressLint
import android.content.Context
import android.util.AtomicFile
import android.util.Base64
import android.webkit.*
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * A complete x86 Linux machine, executed in a WASM Worker, with no host syscall passthrough. Each
 * instance is bound to one trusted runtime directory before any guest code runs. All asset paths
 * are constants; the guest can only exchange bytes, TCP and its own state.
 */
internal class LinuxVm(
    private val context: Context,
    private val directory: File,
    private val networkAllowed: () -> Boolean = { true },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pageReady = CompletableDeferred<Unit>()
    private val bootReady = CompletableDeferred<JSONObject>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val startedCallbacks = ConcurrentHashMap<String, () -> Unit>()
    private val outputCallbacks = ConcurrentHashMap<String, (String, String) -> Unit>()
    private val ptyExits = ConcurrentHashMap<String, CompletableDeferred<Int>>()
    private val commandLock = Mutex()
    private var view: WebView? = null
    private var stateStream: FileOutputStream? = null
    private var stateOffset = 0L
    private val state = AtomicFile(File(directory, "machine.state"))
    private val network =
        RuntimeNetwork(scope, networkAllowed, ::send) {
            dev.deepseek.mobile.proxy.MihomoManager.get(context).terminalPort()
        }
    var onPty: (String, ByteArray) -> Unit = { _, _ -> }
    var onPtyExit: (String, Int) -> Unit = { _, _ -> }
    var bootLog = ""
        private set

    @Volatile
    var failed = ""
        private set

    suspend fun start(): JSONObject {
        RuntimeArtifacts.ensure(context)
        withContext(Dispatchers.Main.immediate) { createView() }
        withTimeout(30000) { pageReady.await() }
        send(
            JSONObject()
                .put("type", "boot")
                .put("restore", withContext(Dispatchers.IO) { state.baseFile.isFile }),
        )
        val result = withTimeout(90000) { bootReady.await() }
        if (result.optBoolean("restored")) {
            // NIC state is restored, but the JS network backend is fresh. Reacquire the virtual
            // lease/ARP state instead of trusting a saved DHCP lease from another backend.
            // This only configures the emulated machine; it never changes Android networking.
            val refresh =
                execute(
                    "set -e; ifconfig eth0 down; ifconfig eth0 up; udhcpc -i eth0 -q -n",
                    timeout = 30,
                )
            check(refresh.optInt("exitCode", -1) == 0) { "恢复虚拟网络失败：${refresh.optString("stderr")}" }
            result.put("virtualNetworkReinitialized", true)
        }
        return result
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun createView() {
        check(view == null)
        directory.mkdirs()
        view =
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.setSupportMultipleWindows(false)
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                isSaveEnabled = false
                addJavascriptInterface(Events(), "AndroidVM")
                webViewClient =
                    object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ) = true

                        override fun onRenderProcessGone(
                            view: WebView,
                            detail: RenderProcessGoneDetail,
                        ): Boolean {
                            fail("Linux renderer stopped; saved state remains available")
                            scope.launch { close() }
                            return true
                        }

                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest,
                        ): WebResourceResponse {
                            val uri = request.url
                            val path = uri.path.orEmpty()
                            fun refuse() =
                                WebResourceResponse(
                                    "text/plain",
                                    "UTF-8",
                                    403,
                                    "Forbidden",
                                    emptyMap(),
                                    ByteArrayInputStream(ByteArray(0)),
                                )
                            if (
                                uri.scheme != "https" ||
                                    uri.host != "sandbox.dsha.invalid" ||
                                    request.method != "GET"
                            )
                                return refuse()
                            return try {
                                val input =
                                    when (path) {
                                        "/",
                                        "/assets/linux.html" ->
                                            context.assets.open("linux/linux.html")
                                        "/assets/linux-worker.js",
                                        "/assets/linux-page.js" ->
                                            context.assets.open(
                                                "linux/${path.substringAfterLast('/')}",
                                            )
                                        "/runtime/v86.wasm" -> context.assets.open("linux/v86.wasm")
                                        "/runtime/dsha-guest" ->
                                            context.assets.open("linux/dsha-guest")
                                        "/runtime/kernel.bin",
                                        "/runtime/seabios.bin",
                                        "/runtime/vgabios.bin",
                                        "/runtime/alpine.tar.gz" ->
                                            File(
                                                    RuntimeArtifacts.directory(context),
                                                    path.substringAfterLast('/'),
                                                )
                                                .inputStream()
                                        "/runtime/state.bin" -> state.openRead()
                                        else -> return refuse()
                                    }
                                val mime =
                                    when {
                                        path.endsWith(".js") -> "application/javascript"
                                        path.endsWith(".wasm") -> "application/wasm"
                                        path.startsWith("/runtime/") -> "application/octet-stream"
                                        else -> "text/html"
                                    }
                                WebResourceResponse(
                                    mime,
                                    if (mime.contains("text") || mime.contains("javascript"))
                                        "UTF-8"
                                    else null,
                                    200,
                                    "OK",
                                    mapOf(
                                        "Content-Security-Policy" to
                                            "default-src 'none'; script-src 'self' 'wasm-unsafe-eval' blob:; worker-src blob:; connect-src 'self'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'",
                                        "X-Content-Type-Options" to "nosniff",
                                    ),
                                    input,
                                )
                            } catch (_: Exception) {
                                refuse()
                            }
                        }
                    }
                loadUrl("https://sandbox.dsha.invalid/assets/linux.html")
            }
    }

    private fun send(message: JSONObject) {
        scope.launch(Dispatchers.Main.immediate) {
            view?.evaluateJavascript("window.vmSend(${message});", null)
        }
    }

    private inner class Events {
        @JavascriptInterface
        fun event(raw: String) {
            if (raw.length > 1_500_000) {
                fail("Runtime message too large")
                return
            }
            val message = runCatching { JSONObject(raw) }.getOrNull() ?: return
            scope.launch {
                try {
                    when (message.optString("type")) {
                        "pageReady" -> pageReady.complete(Unit)
                        "ready" -> {
                            bootLog = message.optString("boot")
                            bootReady.complete(message)
                        }
                        "processStarted" -> startedCallbacks[message.getString("id")]?.invoke()
                        "processOutput" ->
                            outputCallbacks[message.getString("id")]?.invoke(
                                message.getString("channel"),
                                message.getString("text"),
                            )
                        "result" -> pending.remove(message.optString("id"))?.complete(message)
                        "fault" -> {
                            val error = IllegalStateException(message.optString("error"))
                            pending.remove(message.optString("id"))?.completeExceptionally(error)
                                ?: fail(error.message.orEmpty())
                        }
                        "networkOpen" -> network.open()
                        "network" -> network.receive(message.getString("data"))
                        "dns" ->
                            reply(message) {
                                JSONObject().put("data", network.dns(message.getString("data")))
                            }
                        "pty" ->
                            onPty(
                                message.getString("id"),
                                Base64.decode(message.getString("data"), Base64.DEFAULT),
                            )
                        "ptyExit" -> {
                            val id = message.getString("id")
                            val code = message.getInt("exitCode")
                            ptyExits.remove(id)?.complete(code)
                            onPtyExit(id, code)
                        }
                        "stateChunk" ->
                            reply(message) {
                                withContext(Dispatchers.IO) { appendState(message) }
                                JSONObject()
                            }
                        "stateCommit" ->
                            reply(message) {
                                withContext(Dispatchers.IO) {
                                    check(stateOffset == message.getLong("size") && stateOffset > 0)
                                    state.finishWrite(checkNotNull(stateStream))
                                    stateStream = null
                                    stateOffset = 0
                                }
                                JSONObject()
                            }
                    }
                } catch (error: Exception) {
                    fail(error.message ?: error.javaClass.simpleName)
                }
            }
        }
    }

    private suspend fun reply(request: JSONObject, block: suspend () -> JSONObject) {
        val result =
            try {
                block()
            } catch (error: Exception) {
                JSONObject().put("error", error.message)
            }
        send(result.put("type", "rpc").put("id", request.getInt("id")))
    }

    private fun appendState(message: JSONObject) {
        val offset = message.getLong("offset")
        val bytes = Base64.decode(message.getString("data"), Base64.DEFAULT)
        require(bytes.size <= 196608 && offset >= 0 && offset + bytes.size <= 512L * 1024 * 1024)
        if (offset == 0L) {
            stateStream?.let { state.failWrite(it) }
            stateStream = state.startWrite()
            stateOffset = 0
        }
        require(offset == stateOffset) { "Runtime state chunk order mismatch" }
        checkNotNull(stateStream).write(bytes)
        stateOffset += bytes.size
    }

    private suspend fun request(
        message: JSONObject,
        timeout: Long = 60000,
        id: String = UUID.randomUUID().toString(),
    ): JSONObject {
        check(failed.isEmpty()) { failed }
        val response = CompletableDeferred<JSONObject>()
        pending[id] = response
        return try {
            send(message.put("id", id))
            withTimeout(timeout) { response.await() }
        } finally {
            pending.remove(id)
        }
    }

    suspend fun execute(
        command: String,
        cwd: String = "/workspace",
        timeout: Int = 30,
        limit: Int = 65536,
        root: String = "-",
        tree: String = "-",
        onStarted: () -> Unit = {},
        onOutput: (String, String) -> Unit = { _, _ -> },
        useMihomo: Boolean = false,
    ): JSONObject = commandLock.withLock {
        require(command.isNotBlank() && command.length <= 16384)
        val proxy = if (useMihomo) dev.deepseek.mobile.proxy.MihomoManager.get(context) else null
        val shell =
            dev.deepseek.mobile.proxy.MihomoManager.withProxyEnvironment(
                command,
                proxy?.terminalPort(),
                guest = true,
            )
        val id = UUID.randomUUID().toString()
        startedCallbacks[id] = onStarted
        outputCallbacks[id] = onOutput
        val result =
            try {
                request(
                    JSONObject()
                        .put("type", "exec")
                        .put("command", shell)
                        .put("cwd", cwd)
                        .put("root", root)
                        .put("tree", tree)
                        .put("timeout", timeout.coerceIn(1, 300) * 1000)
                        .put("limit", limit),
                    timeout.coerceIn(1, 300) * 1000L + 10000,
                    id = id,
                )
            } catch (error: CancellationException) {
                close()
                throw error
            } finally {
                startedCallbacks.remove(id)
                outputCallbacks.remove(id)
            }
        result.put(
            "stdout",
            String(Base64.decode(result.optString("stdout"), Base64.DEFAULT), Charsets.UTF_8),
        )
        result.put(
            "stderr",
            String(Base64.decode(result.optString("stderr"), Base64.DEFAULT), Charsets.UTF_8),
        )
        result.put("environment", "wasm-linux").put("androidRoot", false).put("guestUid", 0)
    }

    suspend fun put(name: String, data: ByteArray) =
        request(
            JSONObject()
                .put("type", "put")
                .put("name", name)
                .put("data", Base64.encodeToString(data, Base64.NO_WRAP)),
        )

    suspend fun get(name: String): ByteArray =
        Base64.decode(
            request(JSONObject().put("type", "get").put("name", name)).getString("data"),
            Base64.DEFAULT,
        )

    suspend fun diagnostics() = request(JSONObject().put("type", "diagnostics"))

    suspend fun snapshot(): JSONObject = commandLock.withLock {
        request(JSONObject().put("type", "snapshot"), 120000)
    }

    /** These paths belong only to the guest's in-memory 9P filesystem, never Android paths. */
    suspend fun guestDirectory(path: String) =
        request(JSONObject().put("type", "fs").put("action", "mkdir").put("path", path))

    suspend fun guestList(path: String) =
        request(JSONObject().put("type", "fs").put("action", "list").put("path", path))
            .getJSONArray("entries")

    suspend fun guestRead(path: String, maximum: Int = 256 * 1024 * 1024): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var offset = 0
        do {
            val result =
                request(
                    JSONObject()
                        .put("type", "fs")
                        .put("action", "read")
                        .put("path", path)
                        .put("offset", offset),
                )
            require(result.getLong("size") <= maximum) { "Guest file exceeds workspace quota" }
            val data = Base64.decode(result.getString("data"), Base64.DEFAULT)
            out.write(data)
            offset += data.size
            check(data.isNotEmpty() || offset == result.getInt("size")) {
                "Guest export stopped before EOF"
            }
        } while (offset < result.getInt("size"))
        return out.toByteArray()
    }

    suspend fun guestWrite(path: String, bytes: ByteArray, executable: Boolean = false) {
        var offset = 0
        do {
            val count = minOf(196608, bytes.size - offset)
            request(
                JSONObject()
                    .put("type", "fs")
                    .put("action", "write")
                    .put("path", path)
                    .put("offset", offset)
                    .put("executable", executable)
                    .put(
                        "data",
                        Base64.encodeToString(
                            bytes.copyOfRange(offset, offset + count),
                            Base64.NO_WRAP,
                        ),
                    ),
            )
            offset += count
        } while (offset < bytes.size)
    }

    suspend fun guestSymlink(path: String, target: String) =
        request(
            JSONObject()
                .put("type", "fs")
                .put("action", "symlink")
                .put("path", path)
                .put("link", target),
        )

    suspend fun openPty(
        cwd: String = "/workspace",
        root: String = "-",
        tree: String = "-",
        rows: Int = 32,
        cols: Int = 90,
    ): String {
        val id = UUID.randomUUID().toString()
        ptyExits[id] = CompletableDeferred()
        try {
            request(
                JSONObject()
                    .put("type", "ptyOpen")
                    .put("cwd", cwd)
                    .put("root", root)
                    .put("tree", tree)
                    .put("rows", rows.coerceIn(4, 200))
                    .put("cols", cols.coerceIn(10, 300)),
                id = id,
            )
        } catch (error: Throwable) {
            ptyExits.remove(id)
            throw error
        }
        return id
    }

    fun input(id: String, bytes: ByteArray) =
        send(
            JSONObject()
                .put("type", "ptyInput")
                .put("id", id)
                .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP)),
        )

    fun resize(id: String, rows: Int, cols: Int) =
        send(
            JSONObject()
                .put("type", "ptyResize")
                .put("id", id)
                .put("rows", rows.coerceIn(4, 200))
                .put("cols", cols.coerceIn(10, 300)),
        )

    suspend fun closePty(id: String) {
        val ended = ptyExits[id] ?: return
        send(JSONObject().put("type", "ptyClose").put("id", id))
        withTimeout(10000) { ended.await() }
    }

    fun interrupt() = send(JSONObject().put("type", "interrupt"))

    private fun fail(reason: String) {
        failed = reason
        val error = IllegalStateException(reason)
        pageReady.completeExceptionally(error)
        bootReady.completeExceptionally(error)
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
        ptyExits.keys.toList().forEach { id ->
            ptyExits.remove(id)?.completeExceptionally(error)
            onPtyExit(id, 137)
        }
    }

    suspend fun close() =
        withContext(Dispatchers.Main.immediate) {
            network.close()
            pending.values.forEach { it.cancel() }
            pending.clear()
            ptyExits.values.forEach { it.cancel() }
            ptyExits.clear()
            view?.removeJavascriptInterface("AndroidVM")
            view?.destroy()
            view = null
            withContext(Dispatchers.IO) {
                stateStream?.let { state.failWrite(it) }
                stateStream = null
            }
            scope.cancel()
        }
}
