package dev.deepseek.mobile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import dev.deepseek.mobile.terminal.TerminalConnections
import dev.deepseek.mobile.terminal.TerminalLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal val TerminalLevel.canManageOwnPermissions: Boolean
    get() = this in setOf(TerminalLevel.SHELL, TerminalLevel.ROOT, TerminalLevel.ROOT_GUARDED)

internal suspend fun TerminalConnections.ownAppAction(level: TerminalLevel, action: String) {
    check(level.canManageOwnPermissions)
    withContext(Dispatchers.IO) {
        // Android app UIDs are allocated in a per-user range of 100,000.
        worker(level).appSystemAction(action, android.os.Process.myUid() / 100_000)
    }
}

@Composable
internal fun TaskPowerSettings(conversation: Conversation) {
    val context = LocalContext.current
    val level = TerminalLevel.of(conversation.terminalLevel)
    val exempt =
        context
            .getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)
    fun open(intent: Intent) {
        runCatching { context.startActivity(intent) }
            .onFailure {
                AssistantState.error = "无法打开该系统页面，请在系统设置中搜索应用省电策略"
            }
    }
    ToggleSetting("任务保活", "执行期间保持 CPU 唤醒，完成或停止时释放", ExperienceSettings.keepTasksAwake) {
        ExperienceSettings.keepTasksAwake = it
        ExperienceSettings.save()
    }
    SettingsLink(
        AppIcons.Bolt,
        "电池优化",
        if (exempt) "已放宽电池优化" else "打开系统电池优化列表",
    ) {
        open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
    SettingsLink(AppIcons.Settings, "应用省电策略", "打开本应用的系统详情与后台用电设置") {
        open(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            ),
        )
    }
    if (android.os.Build.MANUFACTURER.equals("xiaomi", true))
        Text("小米或澎湃 OS 可在系统设置搜索「应用省电策略」与「后台自启动」，为本应用调整。", color = WhaleMuted, fontSize = 12.sp)
    if (level.canManageOwnPermissions)
        ToggleSetting(
            "用 Shell / Root 辅助保活",
            "在任务期间通过已连接的档位临时放宽本应用的 Doze 限制",
            ExperienceSettings.privilegedKeepAlive,
        ) {
            ExperienceSettings.privilegedKeepAlive = it
            ExperienceSettings.save()
        }
}

internal data class DevicePermissionRequest(
    val level: TerminalLevel,
    val action: String,
    val label: String,
    val manual: () -> Unit,
)

internal object DevicePermissionState {
    var request by mutableStateOf<DevicePermissionRequest?>(null)
}

internal fun Context.hasDevicePermission(action: String): Boolean =
    when (action) {
        "accessibility" -> PhoneAccessibility.current != null
        "overlay" -> Settings.canDrawOverlays(this)
        "notifications" ->
            android.os.Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        else -> false
    }

@Composable
internal fun DevicePermissionPrompt() {
    val request = DevicePermissionState.request ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember(request) { mutableStateOf(false) }
    var failure by remember(request) { mutableStateOf("") }
    fun close() {
        if (!running) DevicePermissionState.request = null
    }
    WhaleSheet("开启${request.label}？", "使用已连接的 ${request.level.title} 为本应用开启这项权限。", { close() }) {
        if (failure.isNotBlank())
            Text(failure, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(
                enabled = !running,
                onClick = {
                    close()
                    request.manual()
                },
            ) {
                Text("手动开启")
            }
            Button(
                enabled = !running,
                onClick = {
                    running = true
                    scope.launch {
                        try {
                            val service = AgentService.current ?: error("任务服务未就绪")
                            service.terminal.connections.ownAppAction(request.level, request.action)
                            if (request.action == "accessibility") {
                                repeat(10) {
                                    if (context.hasDevicePermission("accessibility")) return@repeat
                                    kotlinx.coroutines.delay(150)
                                }
                            }
                            check(context.hasDevicePermission(request.action)) { "系统尚未开启该权限，可手动开启" }
                            if (request.action == "overlay") service.setFloating(true)
                            service.perform("status")
                            DevicePermissionState.request = null
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            failure = error.message.orEmpty()
                        } finally {
                            running = false
                        }
                    }
                },
            ) {
                Text(if (running) "开启中…" else "允许自动开启")
            }
        }
    }
}
