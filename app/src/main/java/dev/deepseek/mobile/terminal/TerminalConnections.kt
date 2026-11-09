package dev.deepseek.mobile.terminal

import android.content.*
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.compose.runtime.*
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import dev.deepseek.mobile.BuildConfig
import kotlinx.coroutines.*
import org.json.JSONObject
import rikka.shizuku.Shizuku

/** A selected tier is configuration. Only a verified remote UID makes its transport ready. */
class TerminalConnections(private val context: Context) {
    val app = ProcessWorker()
    var shellState by mutableStateOf(TerminalConnectionState(detail = "连接 Shizuku 后验证 UID 2000"))
        private set

    var rootState by mutableStateOf(TerminalConnectionState(detail = "连接 Root 管理器后验证 UID 0"))
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private val preferences =
        context.getSharedPreferences("terminal-connections-v1", Context.MODE_PRIVATE)
    private var shell: ITerminalWorker? = null
    private var root: ITerminalWorker? = null
    private var shellConnection: ServiceConnection? = null
    private var rootConnection: ServiceConnection? = null
    private var shellEpoch = 0L
    private var rootEpoch = 0L
    private var shellRequested = false
    private var closed = false
    private var rootJob: Job? = null
    private val args =
        Shizuku.UserServiceArgs(ComponentName(context, ShizukuTerminalService::class.java))
            .tag("dsha-terminal-shell")
            .daemon(false)
            .processNameSuffix("terminal_shell")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)

    private val received = Shizuku.OnBinderReceivedListener {
        main.post { if (!closed) refreshShell() }
    }
    private val died = Shizuku.OnBinderDeadListener {
        main.post {
            if (!closed) {
                shellEpoch++
                shell = null
                shellConnection = null
                shellState =
                    TerminalConnectionState(
                        "disconnected",
                        "Shizuku 服务连接已断开，请启动服务后重新连接",
                        errorCode = "SHIZUKU_BINDER_DEAD",
                    )
            }
        }
    }
    private val permission = Shizuku.OnRequestPermissionResultListener { requestCode, grant ->
        if (requestCode == 410)
            main.post {
                if (!closed && shellRequested) {
                    if (grant == PackageManager.PERMISSION_GRANTED) bindShell()
                    else
                        shellState =
                            TerminalConnectionState(
                                "denied",
                                "Shizuku 未授予 DSHA 权限，请在 Shizuku 的应用管理中检查",
                                errorCode = "SHIZUKU_PERMISSION_DENIED",
                            )
                }
            }
    }

    init {
        Shizuku.addRequestPermissionResultListener(permission)
        Shizuku.addBinderDeadListener(died)
        Shizuku.addBinderReceivedListenerSticky(received)
        main.post {
            if (!closed) {
                shellRequested = preferences.getBoolean("shell_verified", false)
                refreshShell()
                // Restore only an earlier verified UI authorization; denial stops future retries.
                if (preferences.getBoolean("root_verified", false)) connectRoot()
            }
        }
    }

    fun refresh() {
        if (!closed) refreshShell()
    }

    private fun refreshShell() {
        try {
            refreshShellState()
        } catch (error: Exception) {
            shellState =
                TerminalConnectionState(
                    "disconnected",
                    "Shizuku 状态读取失败：${error.message}",
                    errorCode = "SHIZUKU_STATE_UNAVAILABLE",
                )
        }
    }

    private fun refreshShellState() {
        if (!Shizuku.pingBinder()) {
            if (shell == null && !shellState.pending)
                shellState =
                    TerminalConnectionState(
                        "unavailable",
                        "尚未收到 Shizuku 服务 Binder；请打开 Shizuku 检查是否已启动",
                        errorCode = "SHIZUKU_BINDER_UNAVAILABLE",
                    )
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            if (!shellState.pending)
                shellState =
                    TerminalConnectionState(
                        "permission_required",
                        "Shizuku 服务可用，DSHA 尚未获得使用权限",
                        errorCode = "SHIZUKU_PERMISSION_REQUIRED",
                    )
            return
        }
        if (
            shellRequested &&
                shell == null &&
                !shellState.pending &&
                shellState.phase !in setOf("failed", "denied", "wrong_identity")
        )
            bindShell()
        else if (!shellRequested && shell == null && !shellState.pending)
            shellState =
                TerminalConnectionState(
                    "available",
                    "Shizuku 已授权，点击连接以启动 DSHA 终端服务",
                    serverUid = Shizuku.getUid(),
                )
    }

    fun connectShell() {
        if (closed || shellState.ready || shellState.pending) return
        shellRequested = true
        if (!Shizuku.pingBinder()) {
            shellState =
                TerminalConnectionState(
                    "unavailable",
                    "尚未收到 Shizuku 服务 Binder；请打开 Shizuku，通过无线调试或 ADB 启动",
                    errorCode = "SHIZUKU_BINDER_UNAVAILABLE",
                )
            return
        }
        try {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                if (Shizuku.shouldShowRequestPermissionRationale()) {
                    shellState =
                        TerminalConnectionState(
                            "denied",
                            "Shizuku 授权已被拒绝，请到 Shizuku 的应用管理中允许 DSHA",
                            errorCode = "SHIZUKU_PERMISSION_DENIED",
                        )
                } else {
                    shellState =
                        TerminalConnectionState(
                            "permission",
                            "等待 Shizuku 授权；允许后会自动连接",
                            errorCode = "SHIZUKU_PERMISSION_REQUIRED",
                        )
                    Shizuku.requestPermission(410)
                }
            } else bindShell()
        } catch (error: Exception) {
            shellState =
                TerminalConnectionState(
                    "failed",
                    "Shizuku 请求失败：${error.message}",
                    errorCode = "SHIZUKU_REQUEST_FAILED",
                )
        }
    }

    private fun bindShell() {
        try {
            bindShellReady()
        } catch (error: Exception) {
            shellState =
                TerminalConnectionState(
                    "failed",
                    "Shizuku 绑定请求失败：${error.message}",
                    errorCode = "SHIZUKU_BIND_FAILED",
                )
        }
    }

    private fun bindShellReady() {
        if (closed || shellState.phase in setOf("binding", "verifying", "ready")) return
        val serverUid = Shizuku.getUid()
        check(serverUid != 0 || Shizuku.getVersion() >= 13) {
            "Root 启动模式请升级到 Shizuku 13 或更新版本，以建立独立 Shell 进程"
        }
        if (serverUid !in setOf(0, 2000)) {
            shellState =
                TerminalConnectionState(
                    "wrong_identity",
                    "Shizuku 当前服务 UID $serverUid；无法建立 UID 2000 的 Shell 终端。",
                    serverUid = serverUid,
                    errorCode = "SHIZUKU_NOT_SHELL",
                )
            return
        }
        val stale = shellConnection
        val epoch = ++shellEpoch
        shellConnection = null
        shell = null
        if (stale != null) runCatching { Shizuku.unbindUserService(args, stale, true) }
        shellState =
            TerminalConnectionState(
                "binding",
                if (serverUid == 0) "Shizuku 以 Root 启动，正在建立降权 Shell 进程…"
                else "Shizuku 已授权，正在启动终端服务…",
                serverUid = serverUid,
                errorCode = "SHIZUKU_BINDING",
            )
        shellConnection = connection(false, epoch)
        try {
            Shizuku.bindUserService(args, shellConnection!!)
        } catch (error: Exception) {
            shellState =
                TerminalConnectionState(
                    "failed",
                    "Shizuku 终端绑定失败：${error.message}",
                    serverUid = serverUid,
                    errorCode = "SHIZUKU_BIND_FAILED",
                )
        }
        timeout(false, epoch)
    }

    fun connectRoot() {
        if (closed || rootState.ready || rootState.pending) return
        val epoch = ++rootEpoch
        rootState =
            TerminalConnectionState(
                "requesting",
                "正在请求 su，请在 SukiSU Ultra / Magisk 等管理器中允许 DSHA…",
                errorCode = "ROOT_AUTHORIZING",
            )
        rootJob = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // A fresh shell avoids a cached, non-root main shell silently skipping bind.
                    Shell.Builder.create().setTimeout(30).build().use { privileged ->
                        if (!privileged.isRoot) {
                            withContext(Dispatchers.Main) {
                                if (current(true, epoch)) {
                                    preferences.edit().putBoolean("root_verified", false).apply()
                                    rootState =
                                        TerminalConnectionState(
                                            "denied",
                                            "su 没有授予 UID 0。请在 Root 管理器中检查 DSHA 授权、Root 配置或 su 可见性；此结果不能单独证明设备未 Root。",
                                            errorCode = "ROOT_NOT_GRANTED",
                                        )
                                }
                            }
                            return@withContext
                        }
                        val task =
                            withContext(Dispatchers.Main) {
                                if (!current(true, epoch)) return@withContext null
                                rootState =
                                    TerminalConnectionState(
                                        "binding",
                                        "su 已可用，正在绑定 Root 终端服务…",
                                        errorCode = "ROOT_BINDING",
                                    )
                                rootConnection = connection(true, epoch)
                                RootService.bindOrTask(
                                    Intent(context, RootTerminalService::class.java),
                                    context.mainExecutor,
                                    rootConnection!!,
                                )
                            }
                        if (task != null) privileged.execTask(task)
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (current(true, epoch))
                    rootState =
                        TerminalConnectionState(
                            "failed",
                            "Root 通道启动失败：${error.message}",
                            errorCode = "ROOT_BIND_FAILED",
                        )
            }
        }
        timeout(true, epoch)
    }

    private fun current(isRoot: Boolean, epoch: Long) =
        !closed && epoch == if (isRoot) rootEpoch else shellEpoch

    private fun connection(isRoot: Boolean, epoch: Long) =
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (!current(isRoot, epoch)) return
                val worker = ITerminalWorker.Stub.asInterface(binder)
                val previous = if (isRoot) rootState else shellState
                if (isRoot)
                    rootState = previous.copy(phase = "verifying", detail = "正在验证 Root 服务身份…")
                else shellState = previous.copy(phase = "verifying", detail = "正在验证 Shizuku 服务身份…")
                scope.launch {
                    try {
                        val identity = withContext(Dispatchers.IO) { JSONObject(worker.info()) }
                        if (!current(isRoot, epoch)) return@launch
                        val expected = if (isRoot) 0 else 2000
                        check(identity.getInt("uid") == expected) {
                            "实际服务 UID ${identity.optInt("uid")}，要求 $expected"
                        }
                        if (!isRoot && previous.serverUid == 0) {
                            check(
                                identity.optBoolean("downgradedToShell") &&
                                    identity.optString("selinux") == "u:r:shell:s0",
                            ) {
                                "Root Shizuku 未通过独立 Shell 降权验证"
                            }
                        }
                        binder.linkToDeath({ main.post { disconnected(isRoot, epoch) } }, 0)
                        val state =
                            TerminalConnectionState(
                                "ready",
                                "已连接 · UID $expected · PID ${identity.getInt("pid")}",
                                expected,
                                if (isRoot) null else previous.serverUid,
                                identity.getInt("pid"),
                                identity.optString("selinux"),
                                guardAvailable = identity.optBoolean("guard_prerequisites"),
                            )
                        if (isRoot) root = worker else shell = worker
                        if (isRoot) rootState = state else shellState = state
                        preferences
                            .edit()
                            .putBoolean(if (isRoot) "root_verified" else "shell_verified", true)
                            .apply()
                    } catch (error: Exception) {
                        if (current(isRoot, epoch)) {
                            val state =
                                TerminalConnectionState(
                                    "failed",
                                    if (!isRoot && previous.serverUid == 0)
                                        "Shizuku 以 Root 运行，但未能建立 Shell 身份。可用 ADB/无线调试启动 Shizuku，或切换其他终端。原始原因：${error.message}"
                                    else "终端身份验证失败：${error.message}",
                                    serverUid = if (isRoot) null else previous.serverUid,
                                    errorCode =
                                        if (!isRoot && previous.serverUid == 0)
                                            "SHELL_DOWNGRADE_FAILED"
                                        else "IDENTITY_VERIFICATION_FAILED",
                                )
                            if (isRoot) rootState = state else shellState = state
                        }
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName) = disconnected(isRoot, epoch)

            override fun onBindingDied(name: ComponentName) = disconnected(isRoot, epoch)

            override fun onNullBinding(name: ComponentName) = disconnected(isRoot, epoch)
        }

    private fun disconnected(isRoot: Boolean, epoch: Long) {
        if (!current(isRoot, epoch)) return
        val state =
            TerminalConnectionState(
                "disconnected",
                "终端服务已断开，请重新连接",
                errorCode = "SERVICE_DISCONNECTED",
            )
        if (isRoot) {
            root = null
            rootState = state
        } else {
            shell = null
            shellState = state
        }
    }

    private fun timeout(isRoot: Boolean, epoch: Long) {
        scope.launch {
            delay(60000)
            if (current(isRoot, epoch) && (if (isRoot) rootState else shellState).pending) {
                disconnect(isRoot, forget = false)
                val state =
                    TerminalConnectionState(
                        "failed",
                        "连接等待超时；尚未启动任何终端命令，请检查授权或重新连接",
                        errorCode = "CONNECTION_TIMEOUT",
                    )
                if (isRoot) rootState = state else shellState = state
            }
        }
    }

    fun disconnect(isRoot: Boolean, forget: Boolean = true) {
        if (isRoot) {
            rootEpoch++
            rootJob?.cancel()
            rootJob = null
            root = null
            rootConnection?.let { runCatching { RootService.unbind(it) } }
            rootConnection = null
            rootState = TerminalConnectionState(detail = "Root 已断开")
        } else {
            shellEpoch++
            shellRequested = false
            shell = null
            shellConnection?.let { runCatching { Shizuku.unbindUserService(args, it, true) } }
            shellConnection = null
            shellState = TerminalConnectionState(detail = "Shizuku 已断开")
        }
        if (forget)
            preferences
                .edit()
                .putBoolean(if (isRoot) "root_verified" else "shell_verified", false)
                .apply()
    }

    fun state(level: TerminalLevel): TerminalConnectionState =
        when (level) {
            TerminalLevel.SANDBOX ->
                TerminalConnectionState(
                    "available",
                    "独立 Linux · 首次执行时创建；Guest Root 不是 Android Root",
                )
            TerminalLevel.SHELL -> shellState
            TerminalLevel.ROOT,
            TerminalLevel.ROOT_GUARDED -> rootState
            TerminalLevel.APP ->
                TerminalConnectionState(
                    "ready",
                    "应用 UID ${android.os.Process.myUid()}",
                    android.os.Process.myUid(),
                    pid = android.os.Process.myPid(),
                )
            else -> TerminalConnectionState("ready", "内置隔离文件终端，不是系统 Shell")
        }

    suspend fun awaitWorker(level: TerminalLevel): ITerminalWorker {
        val currentState = state(level)
        if (
            !closed &&
                !currentState.ready &&
                !currentState.pending &&
                currentState.phase in setOf("idle", "available", "disconnected")
        ) {
            withContext(Dispatchers.Main) {
                if (
                    level == TerminalLevel.SHELL &&
                        preferences.getBoolean("shell_verified", false) &&
                        Shizuku.pingBinder() &&
                        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                ) {
                    shellRequested = true
                    bindShell()
                } else if (
                    level in setOf(TerminalLevel.ROOT, TerminalLevel.ROOT_GUARDED) &&
                        preferences.getBoolean("root_verified", false)
                )
                    connectRoot()
            }
        }
        val until = android.os.SystemClock.elapsedRealtime() + 10000
        while (state(level).pending && android.os.SystemClock.elapsedRealtime() < until) delay(80)
        return worker(level)
    }

    fun worker(level: TerminalLevel): ITerminalWorker =
        when (level) {
            TerminalLevel.APP -> app
            TerminalLevel.SHELL ->
                shell?.takeIf { shellState.ready && it.asBinder().isBinderAlive }
                    ?: throw TerminalConnectionException(shellState)
            TerminalLevel.ROOT,
            TerminalLevel.ROOT_GUARDED ->
                root?.takeIf { rootState.ready && it.asBinder().isBinderAlive }
                    ?: throw TerminalConnectionException(rootState)
            else -> error("内置终端不使用系统进程")
        }

    fun status(level: TerminalLevel) = state(level).json().toString()

    fun close() {
        if (closed) return
        closed = true
        Shizuku.removeBinderReceivedListener(received)
        Shizuku.removeBinderDeadListener(died)
        Shizuku.removeRequestPermissionResultListener(permission)
        disconnect(true, forget = false)
        disconnect(false, forget = false)
        scope.cancel()
        app.close()
    }
}
