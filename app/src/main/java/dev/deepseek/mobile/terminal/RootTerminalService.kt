package dev.deepseek.mobile.terminal

import android.content.Intent
import android.os.IBinder
import com.topjohnwu.superuser.ipc.RootService

class RootTerminalService : RootService() {
    private val worker = ProcessWorker()

    override fun onBind(intent: Intent): IBinder = worker

    override fun onDestroy() {
        worker.close()
        super.onDestroy()
    }
}
