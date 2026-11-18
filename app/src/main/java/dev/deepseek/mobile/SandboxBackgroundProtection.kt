package dev.deepseek.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.compose.runtime.snapshotFlow
import dev.deepseek.mobile.harness.HarnessCoordinator
import dev.deepseek.mobile.runtime.SandboxManager
import dev.deepseek.mobile.terminal.TerminalConnections
import dev.deepseek.mobile.terminal.TerminalLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Keeps the already running VM resident as long as Android keeps the service alive. */
internal class SandboxBackgroundProtection(
    private val context: Context,
    private val scope: CoroutineScope,
    private val connections: TerminalConnections,
) {
    private val manager = SandboxManager.get(context)
    private val runtimes = HarnessCoordinator.get(context).runtimes
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val preferences =
        context.getSharedPreferences("sandbox-background", Context.MODE_PRIVATE)
    private var keeper: Job? = null
    private var retainedId: String? = null
    private val observer: Job

    init {
        notifications.createNotificationChannel(
            NotificationChannel(
                "sandbox-runtime",
                "Sandbox 运行状态",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        observer = scope.launch {
            val recover = preferences.getString("protected", null)?.let(manager::get)
            if (
                ExperienceSettings.keepSandboxesAwake &&
                    recover != null &&
                    !recover.deleting &&
                    runtimes.info(recover.scope).phase in setOf("suspended", "interrupted")
            )
                manager.start(recover.id)
            combine(
                    runtimes.states,
                    manager.records,
                    snapshotFlow { ExperienceSettings.keepSandboxesAwake },
                ) { states, records, enabled ->
                    if (!enabled) null
                    else
                        records.firstOrNull {
                            !it.deleting &&
                                states[it.scope.key]?.phase in setOf("ready", "running", "saving")
                        }
                }
                .collect { record ->
                    val id = record?.id
                    preferences.edit().putString("protected", id).apply()
                    if (retainedId != id) {
                        keeper?.cancel()
                        retainedId = id
                        keeper = if (id == null) null else scope.launch { holdCpu() }
                    }
                    if (record == null) notifications.cancel(NotificationId)
                    else show(record.id, record.name)
                }
        }
    }

    private suspend fun holdCpu() {
        val wake =
            context
                .getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${context.packageName}:sandbox")
                .apply { setReferenceCounted(false) }
        try {
            while (true) {
                wake.acquire(120_000)
                val level = TerminalLevel.of(AssistantState.current.terminalLevel)
                if (level.canManageOwnPermissions && connections.state(level).ready) {
                    try {
                        connections.ownAppAction(level, "keep_awake")
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // The foreground service remains active if this OS restricts Doze
                        // exemptions.
                    }
                }
                delay(90_000)
            }
        } finally {
            if (wake.isHeld) wake.release()
        }
    }

    private fun show(id: String, name: String) {
        fun action(label: String, operation: String, request: Int): Notification.Action {
            val intent =
                Intent(context, AgentService::class.java)
                    .setAction(operation)
                    .putExtra("sandboxId", id)
            return Notification.Action.Builder(
                    null,
                    label,
                    PendingIntent.getService(
                        context,
                        request,
                        intent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
                .build()
        }
        val open =
            PendingIntent.getActivity(
                context,
                NotificationId,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification =
            Notification.Builder(context, "sandbox-runtime")
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("$name 仍在运行")
                .setContentText("DSHA Sandbox 后台保护")
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(action("重启", Restart, NotificationId + 1))
                .addAction(action("停止", Stop, NotificationId + 2))
                .build()
        try {
            notifications.notify(NotificationId, notification)
        } catch (_: SecurityException) {
            // User may disable notifications at any time.
        }
    }

    fun command(action: String, id: String) {
        if (manager.get(id) == null) return
        if (action == Restart) manager.restart(id) else if (action == Stop) manager.stop(id)
    }

    fun close() {
        observer.cancel()
        keeper?.cancel()
        notifications.cancel(NotificationId)
    }

    companion object {
        const val Restart = "SANDBOX_RESTART"
        const val Stop = "SANDBOX_STOP"
        private const val NotificationId = 6125
    }
}
