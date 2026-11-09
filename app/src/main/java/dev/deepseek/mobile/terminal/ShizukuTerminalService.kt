package dev.deepseek.mobile.terminal

import android.content.Context
import android.os.Process

/** Constructed by Shizuku UserService, not registered as an exported Android service. */
class ShizukuTerminalService(context: Context?) : ITerminalWorker.Stub() {
    constructor() : this(null)

    private val worker: ITerminalWorker =
        when (Process.myUid()) {
            2000 -> ProcessWorker()
            0 -> ShellProcessBridge(checkNotNull(context) { "Root 启动模式需要 Shizuku 13 或更新版本" })
            else -> error("不支持的 Shizuku 服务身份")
        }

    override fun info() = worker.info()

    override fun inspect(spec: String) = worker.inspect(spec)

    override fun start(spec: String) = worker.start(spec)

    override fun poll(id: String) = worker.poll(id)

    override fun cancel(id: String) = worker.cancel(id)

    override fun readOnlyProbe() = worker.readOnlyProbe()

    override fun destroy() = worker.destroy()
}
