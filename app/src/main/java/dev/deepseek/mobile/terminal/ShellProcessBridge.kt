package dev.deepseek.mobile.terminal

import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** Root bootstrap can only forward fixed worker RPCs to its separate UID 2000 child. */
internal class ShellProcessBridge(context: Context) : ITerminalWorker.Stub() {
    private val app = context.packageManager.getApplicationInfo("dev.deepseek.mobile", 0)
    private val handles =
        NativeProcess.spawnShellWorker(
                app.sourceDir,
                File(app.nativeLibraryDir, "libterminal_guard.so").absolutePath,
            )
            .also { check(it.size == 4) { "无法创建 Shell 降权进程" } }
    private val input =
        ParcelFileDescriptor.AutoCloseOutputStream(
                ParcelFileDescriptor.adoptFd(handles[1]),
            )
            .bufferedWriter()
    private val output =
        ParcelFileDescriptor.AutoCloseInputStream(
                ParcelFileDescriptor.adoptFd(handles[2]),
            )
            .bufferedReader()
    private val error =
        ParcelFileDescriptor.AutoCloseInputStream(
            ParcelFileDescriptor.adoptFd(handles[3]),
        )
    private val replies = Executors.newSingleThreadExecutor()
    private val errors = Executors.newSingleThreadExecutor()
    private val detail = StringBuilder()
    @Volatile private var closed = false
    @Volatile private var reaped = false
    @Volatile private var failure = ""

    private val errorDrain = errors.submit {
        runCatching {
            error.bufferedReader().use { reader ->
                val buffer = CharArray(512)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    synchronized(detail) {
                        if (detail.length < 4096)
                            detail.append(buffer, 0, minOf(count, 4096 - detail.length))
                    }
                }
            }
        }
    }

    @Synchronized
    private fun rpc(method: String, argument: String = ""): String {
        check(!closed) { failure.ifBlank { "Shell 降权通道已关闭，请重新连接" } }
        try {
            val message = JSONObject().put("method", method).put("argument", argument).toString()
            require(message.length <= 524288)
            input.write(message)
            input.newLine()
            input.flush()
            val line = replies.submit<String?> { output.readLine() }.get(15, TimeUnit.SECONDS)
            if (line == null) {
                runCatching { errorDrain.get(1, TimeUnit.SECONDS) }
                val code =
                    replies
                        .submit<Int> { NativeProcess.waitFor(handles[0]) }
                        .get(1, TimeUnit.SECONDS)
                reaped = true
                error("Shell 子进程退出 $code：${synchronized(detail) { detail.toString() }}")
            }
            check(line.length <= 1048576)
            val reply = JSONObject(line)
            if (reply.has("error")) throw IllegalArgumentException(reply.getString("error"))
            return reply.getString("value")
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            failure =
                "Shell 降权失败，不会退回 Root：${synchronized(detail) { detail.toString() }} ${e.message.orEmpty()}"
            close()
            throw IllegalStateException(failure, e)
        }
    }

    override fun info() =
        JSONObject(rpc("info")).put("brokerUid", 0).put("downgradedToShell", true).toString()

    override fun inspect(spec: String) = rpc("inspect", spec)

    override fun start(spec: String) = rpc("start", spec)

    override fun poll(id: String) = rpc("poll", id)

    override fun readOnlyProbe() = rpc("probe")

    override fun appSystemAction(action: String, userId: Int) {
        rpc("app-system", JSONObject().put("action", action).put("user", userId).toString())
    }

    override fun cancel(id: String) {
        rpc("cancel", id)
    }

    private fun close() {
        if (closed) return
        closed = true
        if (!reaped) NativeProcess.terminate(handles[0])
        runCatching { input.close() }
        runCatching { output.close() }
        runCatching { error.close() }
        replies.shutdownNow()
        errors.shutdownNow()
        Thread {
            if (!reaped) {
                NativeProcess.waitFor(handles[0])
                reaped = true
            }
        }
            .apply {
                isDaemon = true
                start()
            }
    }

    override fun destroy() {
        runCatching { rpc("shutdown") }
        close()
        kotlin.system.exitProcess(0)
    }
}
