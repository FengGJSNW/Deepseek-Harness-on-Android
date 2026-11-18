package dev.deepseek.mobile.terminal

import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class RootNotGranted : Exception("su did not grant UID 0")

internal interface RootBinding {
    suspend fun bind(authorized: () -> ServiceConnection?)

    fun unbind(connection: ServiceConnection)
}

internal class LibsuRootBinding(private val context: Context) : RootBinding {
    override suspend fun bind(authorized: () -> ServiceConnection?) =
        withContext(Dispatchers.IO) {
            Shell.Builder.create().setTimeout(60).build().use { shell ->
                if (!shell.isRoot) throw RootNotGranted()
                val task =
                    withContext(Dispatchers.Main) {
                        authorized()?.let {
                            RootService.bindOrTask(
                                Intent(context, RootTerminalService::class.java),
                                context.mainExecutor,
                                it,
                            )
                        }
                    }
                if (task != null) shell.execTask(task)
            }
        }

    override fun unbind(connection: ServiceConnection) = RootService.unbind(connection)
}
