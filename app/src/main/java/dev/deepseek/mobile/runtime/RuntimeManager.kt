package dev.deepseek.mobile.runtime

import android.content.Context
import android.util.AtomicFile
import dev.deepseek.mobile.AssistantState
import dev.deepseek.mobile.PhoneTask
import dev.deepseek.mobile.harness.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

internal enum class RuntimeKind(val id: String, val title: String, val available: Boolean) {
    LIGHT("light", "DSHA Light", true),
    ALPINE("alpine", "Alpine Linux", true),
    DEBIAN("debian", "Debian", false),
    UBUNTU("ubuntu", "Ubuntu", false);

    companion object {
        fun of(key: String) = entries.firstOrNull { it.id == key } ?: LIGHT
    }
}

internal enum class RuntimeLifetime {
    PERSISTENT,
    TEMPORARY,
}

internal data class RuntimeInfo(
    val scope: WorkspaceScope,
    val kind: RuntimeKind = RuntimeKind.LIGHT,
    val phase: String = "not_initialized",
    val detail: String = "首次使用终端时创建",
    val savedAt: Long = 0,
    val generation: Int = 1,
    val lifetime: RuntimeLifetime = RuntimeLifetime.PERSISTENT,
) {
    fun json() =
        JSONObject()
            .put("scopeKind", scope.kind)
            .put("scopeId", scope.id)
            .put("kind", kind.id)
            .put("phase", phase)
            .put("detail", detail)
            .put("savedAt", savedAt)
            .put("generation", generation)
            .put("lifetime", lifetime.name)
}

internal data class RuntimeTerminal(
    val vm: LinuxVm,
    val id: String,
    val tree: Worktree,
    val scope: WorkspaceScope,
)

/** Project/session lifecycle and virtual file exchange. Opening a chat never boots a machine. */
internal class RuntimeManager(
    private val context: Context,
    private val permissions: PermissionManager,
    val workspaces: WorkspaceManager,
) {
    private data class Active(
        val vm: LinuxVm,
        val imported: MutableSet<String> = mutableSetOf(),
        var terminal: RuntimeTerminal? = null,
    )

    private val root = File(context.filesDir, "runtimes/instances").canonicalFile.apply { mkdirs() }
    private val template = File(context.filesDir, "toolchains/light-v1").apply { mkdirs() }
    private val metadata = File(context.filesDir, "harness/runtimes").apply { mkdirs() }
    private val lock = Mutex()
    private val active = mutableMapOf<String, Active>()
    private val _states = MutableStateFlow<Map<String, RuntimeInfo>>(emptyMap())
    val states: StateFlow<Map<String, RuntimeInfo>> = _states

    init {
        metadata
            .listFiles()
            .orEmpty()
            .filter { it.extension == "json" }
            .forEach { file ->
                runCatching {
                    val j = JSONObject(String(AtomicFile(file).readFully()))
                    val scope = WorkspaceScope(j.getString("scopeKind"), j.getString("scopeId"))
                    val old = j.getString("phase")
                    val interrupted = old in setOf("initializing", "resetting", "saving", "running")
                    _states.value +=
                        scope.key to
                            RuntimeInfo(
                                scope,
                                RuntimeKind.of(j.getString("kind")),
                                if (interrupted) "interrupted" else old,
                                if (interrupted) "上次操作中断，将从最后保存的状态恢复" else j.optString("detail"),
                                j.optLong("savedAt"),
                                j.optInt("generation", 1),
                                RuntimeLifetime.valueOf(j.optString("lifetime", "PERSISTENT")),
                            )
                }
                    .onFailure { error ->
                        // Keep damaged metadata on disk for recovery; do not replace it silently.
                        android.util.Log.e(
                            "DSHA-Runtime",
                            "Runtime metadata unreadable: ${file.name}",
                            error,
                        )
                    }
            }
    }

    fun info(scope: WorkspaceScope) = _states.value[scope.key] ?: RuntimeInfo(scope)

    fun scope(task: PhoneTask) =
        if (task.projectId.isNotBlank()) WorkspaceScope("project", task.projectId)
        else WorkspaceScope("session", task.sessionId)

    suspend fun prepareTask(task: PhoneTask): WorkspaceScope =
        withContext(Dispatchers.IO) {
            val scope = scope(task)
            workspaces.migrateLegacy(scope, task.sessionId)
            val marker = File(context.filesDir, "harness/imports/${task.id}.json")
            if (task.files.isNotEmpty() && !marker.isFile) {
                val store = dev.deepseek.mobile.AttachmentStore(context)
                val policy =
                    PermissionContext(sessionId = task.sessionId, projectId = task.projectId)
                for (id in task.files.distinct()) {
                    val item = store.get(id)
                    check(item.sessionId == task.sessionId)
                    val name =
                        item.name.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").take(120).ifBlank {
                            "attachment"
                        }
                    val path = "imports/$id/$name"
                    check(
                        permissions
                            .evaluate(policy, Capability.WORKSPACE_WRITE, "/workspace/$path")
                            .effect == PermissionEffect.ALLOW,
                    ) {
                        "规则禁止附件导入"
                    }
                    workspaces.importFile(scope, path, store.file(id).readBytes())
                }
                marker.parentFile!!.mkdirs()
                val atomic = AtomicFile(marker)
                val out = atomic.startWrite()
                try {
                    out.write(
                        JSONObject().put("files", JSONArray(task.files)).toString().toByteArray(),
                    )
                    atomic.finishWrite(out)
                } catch (error: Throwable) {
                    atomic.failWrite(out)
                    throw error
                }
            }
            scope
        }

    private fun directory(scope: WorkspaceScope) =
        File(root, "${scope.kind}/${scope.id}").apply { mkdirs() }

    private suspend fun update(info: RuntimeInfo) {
        android.util.Log.d("DSHA-Runtime", "${info.scope.key} ${info.phase}: ${info.detail}")
        _states.value = _states.value + (info.scope.key to info)
        withContext(Dispatchers.IO) {
            val atomic = AtomicFile(File(metadata, "${info.scope.kind}-${info.scope.id}.json"))
            val out = atomic.startWrite()
            try {
                out.write(info.json().toString().toByteArray())
                atomic.finishWrite(out)
            } catch (e: Throwable) {
                atomic.failWrite(out)
                throw e
            }
        }
    }

    /** One shared machine uses the strongest network restriction of its participating sessions. */
    fun networkAllowed(scope: WorkspaceScope): Boolean {
        val sessions =
            AssistantState.sessions.filter {
                if (scope.kind == "project") it.projectId == scope.id else it.id == scope.id
            }
        val contexts =
            if (sessions.isEmpty())
                listOf(
                    PermissionContext(
                        sessionId = if (scope.kind == "session") scope.id else "",
                        projectId = if (scope.kind == "project") scope.id else "",
                    ),
                )
            else sessions.map { PermissionContext(sessionId = it.id, projectId = it.projectId) }
        return contexts.all {
            !permissions.requiresBroker(it, setOf(Capability.NETWORK)) &&
                permissions.evaluate(it, Capability.NETWORK).effect == PermissionEffect.ALLOW
        }
    }

    private suspend fun machine(scope: WorkspaceScope): Active {
        active[scope.key]?.let {
            if (it.vm.failed.isEmpty()) return it
            // A dead renderer cannot checkpoint. Restore a fresh VM from the last committed state.
            it.vm.close()
            active.remove(scope.key)
            update(info(scope).copy(phase = "interrupted", detail = "进程已退出，将从完整检查点恢复"))
        }
        // Limit inactive resident VMs. Every evicted instance has a durable checkpoint first.
        if (active.isNotEmpty()) {
            val idle = active.entries.firstOrNull { it.value.terminal == null }
            check(idle != null) { "请先关闭一个本地终端，再打开其他工作域" }
            release(idle.key, idle.value)
        }
        var record = info(scope)
        require(record.kind.available) { "${record.kind.title} 尚未提供可用镜像；请选择已实现的运行环境" }
        update(record.copy(phase = "initializing", detail = "准备独立 Linux 环境"))
        if (record.lifetime == RuntimeLifetime.TEMPORARY) clearDirectory(scope)
        val dir = directory(scope)
        val vm = LinuxVm(context, dir) { networkAllowed(scope) }
        try {
            if (!File(dir, "machine.state").isFile && record.kind == RuntimeKind.LIGHT) {
                prepareTemplate(scope)
                withContext(Dispatchers.IO) {
                    File(template, "machine.state")
                        .copyTo(File(dir, "machine.state"), overwrite = false)
                }
            }
            val boot = vm.start()
            if (!boot.optBoolean("restored"))
                initializeAlpine(vm, scope, installTools = record.kind == RuntimeKind.LIGHT)
            val entry = Active(vm)
            active[scope.key] = entry
            workspaces.recover(scope)
            record =
                record.copy(
                    phase = "ready",
                    detail = "环境就绪 · /workspace",
                    savedAt = System.currentTimeMillis(),
                )
            update(record)
            if (!boot.optBoolean("restored")) vm.snapshot()
            return entry
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                vm.close()
                update(record.copy(phase = "broken", detail = error.message ?: "初始化失败，可重试"))
            }
            throw error
        }
    }

    private suspend fun prepareTemplate(scope: WorkspaceScope) {
        val ready = File(template, "ready.json")
        if (File(template, "machine.state").isFile && ready.isFile) return
        check(networkAllowed(scope)) { "基础工具尚未下载，当前网络规则不允许初始化" }
        update(info(scope).copy(phase = "initializing", detail = "首次准备工具链：Git、SSH、Python、Node…"))
        val vm = LinuxVm(context, template) { networkAllowed(scope) }
        try {
            val boot = vm.start()
            if (!boot.optBoolean("restored")) {
                initializeAlpine(vm, scope, installTools = true)
                // Keep installed packages across a failed executable probe; never mark them ready
                // yet.
                vm.snapshot()
            }
            val probes = JSONObject()
            try {
                for ((name, command) in
                    listOf(
                        "git" to "git --version",
                        "ssh" to "ssh -V",
                        "python" to "python3 -c 'print(42)'",
                        "node" to "node -e 'console.log(42)'",
                        "npm" to "npm --version",
                    )) {
                    update(info(scope).copy(phase = "initializing", detail = "验证工具链：$name"))
                    val result = vm.execute(command, root = "/mnt/alpine", timeout = 120)
                    probes.put(name, result)
                    checkResult(result, "$name 实际执行验证")
                }
            } finally {
                probes.put("guestDiagnostics", vm.diagnostics())
                withContext(Dispatchers.IO) {
                    File(template, "probes.json").writeText(probes.toString(2))
                }
            }
            check(
                vm.guestList("/workspace").length() == 0 &&
                    vm.guestList("/worktrees").length() == 0,
            ) {
                "工具链模板意外包含工作区文件，不允许用于其他会话"
            }
            vm.snapshot()
            withContext(Dispatchers.IO) {
                ready.writeText(
                    JSONObject()
                        .put("engine", "v86-0.5.473")
                        .put("verifiedAt", System.currentTimeMillis())
                        .toString(),
                )
            }
        } finally {
            vm.close()
        }
    }

    private suspend fun initializeAlpine(
        vm: LinuxVm,
        scope: WorkspaceScope,
        installTools: Boolean,
    ) {
        update(info(scope).copy(phase = "initializing", detail = "初始化 Linux 文件系统"))
        checkResult(
            vm.execute(
                "set -e; gzip -dc /mnt/alpine.tar.gz | tar -xf - -C /mnt/alpine; mkdir -p /mnt/alpine/proc /mnt/alpine/dev /mnt/alpine/sys /mnt/alpine/workspace; mount -t proc proc /mnt/alpine/proc; mount --rbind /dev /mnt/alpine/dev; mount -t sysfs sysfs /mnt/alpine/sys; printf 'nameserver 192.168.86.1\\n' > /mnt/alpine/etc/resolv.conf; udhcpc -i eth0 -q -n",
                timeout = 90,
            ),
            "Linux 初始化",
        )
        if (installTools) {
            update(info(scope).copy(phase = "initializing", detail = "安装独立工具链，首次准备需要一些时间"))
            checkResult(
                vm.execute(
                    "apk add --no-cache bash git openssh-client curl python3 py3-pip nodejs npm",
                    root = "/mnt/alpine",
                    timeout = 300,
                ),
                "工具链安装",
            )
        }
    }

    private fun checkResult(result: JSONObject, stage: String) {
        check(result.getInt("exitCode") == 0) {
            "$stage 失败（exit=${result.optInt("exitCode")}）：${(result.optString("stdout") + "\n" + result.optString("stderr")).take(1600)}"
        }
    }

    private suspend fun importTree(entry: Active, tree: Worktree): String {
        check(tree.state in setOf("active", "ready", "conflicted")) { "工作副本已合并或丢弃；请开始新任务" }
        val guest = "/worktrees/${tree.taskId}"
        if (entry.imported.add(tree.taskId)) {
            try {
                entry.vm.guestDirectory(guest)
                val files = withContext(Dispatchers.IO) { workspaces.current(tree) }
                for ((path, version) in files) {
                    val bytes = withContext(Dispatchers.IO) { workspaces.content(version) }
                    if (version.link)
                        entry.vm.guestSymlink("$guest/$path", String(bytes, Charsets.UTF_8))
                    else entry.vm.guestWrite("$guest/$path", bytes, version.executable)
                }
            } catch (error: Throwable) {
                entry.imported.remove(tree.taskId)
                throw error
            }
        }
        return "/mnt$guest"
    }

    private suspend fun captureTree(entry: Active, tree: Worktree): Map<String, FileVersion> {
        val files = linkedMapOf<String, FileVersion>()
        var total = 0L
        suspend fun walk(path: String) {
            val entries =
                entry.vm.guestList(
                    "/worktrees/${tree.taskId}" + if (path.isEmpty()) "" else "/$path",
                )
            for (i in 0 until entries.length()) {
                val item = entries.getJSONObject(i)
                val relative = (if (path.isBlank()) "" else "$path/") + item.getString("name")
                WorkspaceManager.validPath(relative)
                val mode = item.getInt("mode")
                when (mode and 61440) {
                    16384 -> walk(relative)
                    32768,
                    40960 -> {
                        val bytes =
                            if ((mode and 61440) == 40960) item.getString("link").toByteArray()
                            else entry.vm.guestRead("/worktrees/${tree.taskId}/$relative")
                        total += bytes.size
                        require(total <= WorkspaceManager.MAX_BYTES && files.size < 20000) {
                            "工作区导出超过容量限制；原版本仍保留"
                        }
                        files[relative] =
                            withContext(Dispatchers.IO) {
                                workspaces.stage(bytes, (mode and 61440) == 40960, mode and 73 != 0)
                            }
                    }
                    else -> error("工作区包含不支持的设备或Socket：$relative")
                }
            }
        }
        walk("")
        return files
    }

    private suspend fun exportTree(entry: Active, tree: Worktree) {
        val files = captureTree(entry, tree)
        withContext(Dispatchers.IO) { workspaces.receive(tree, files) }
    }

    /** Extensions get an empty verified template, never the parent machine or another worktree. */
    suspend fun runHook(
        task: PhoneTask,
        policy: PermissionContext,
        command: String,
        event: JSONObject,
        worktreeId: String = task.id,
    ): JSONObject = lock.withLock {
        check(task.access !in setOf("none", "read")) { "只聊天模式禁止执行命令 Hook" }
        check(
            permissions.evaluate(policy, Capability.SANDBOX_EXEC, command).effect ==
                PermissionEffect.ALLOW,
        ) {
            "Hook 命令没有执行权限"
        }
        check(File(template, "ready.json").isFile && File(template, "machine.state").isFile) {
            "请先初始化 Light 工具链，再启用命令 Hook"
        }
        check(active.values.none { it.terminal != null }) { "请先关闭本地终端再运行命令 Hook" }
        for ((key, entry) in active.toMap()) release(key, entry)
        val scope = prepareTask(task)
        val tree = withContext(Dispatchers.IO) { workspaces.begin(scope, worktreeId, task.id) }
        val base = withContext(Dispatchers.IO) { workspaces.current(tree) }
        val canRead = Capability.WORKSPACE_READ in policy.declaredCapabilities.orEmpty()
        if (canRead)
            base.keys.forEach { path ->
                check(
                    permissions
                        .evaluate(policy, Capability.WORKSPACE_READ, "/workspace/$path")
                        .effect == PermissionEffect.ALLOW,
                ) {
                    "Hook 未获准读取 $path"
                }
            }
        val extensionRoot =
            File(context.filesDir, "runtimes/extensions").canonicalFile.apply { mkdirs() }
        val directory = File(extensionRoot, java.util.UUID.randomUUID().toString())
        directory.mkdirs()
        check(directory.canonicalFile.parentFile == extensionRoot)
        val vm =
            LinuxVm(context, directory) {
                !permissions.requiresBroker(policy, setOf(Capability.NETWORK)) &&
                    permissions.evaluate(policy, Capability.NETWORK).effect ==
                        PermissionEffect.ALLOW
            }
        try {
            withContext(Dispatchers.IO) {
                File(template, "machine.state").copyTo(File(directory, "machine.state"))
            }
            check(vm.start().optBoolean("restored"))
            vm.guestDirectory("/worktrees/${tree.taskId}")
            if (canRead)
                for ((path, version) in base) {
                    val bytes = withContext(Dispatchers.IO) { workspaces.content(version) }
                    if (version.link)
                        vm.guestSymlink("/worktrees/${tree.taskId}/$path", String(bytes))
                    else vm.guestWrite("/worktrees/${tree.taskId}/$path", bytes, version.executable)
                }
            vm.guestWrite("/alpine/tmp/dsha-hook-event.json", event.toString().toByteArray())
            val result =
                vm.execute(
                    "DSHA_HOOK_INPUT=/tmp/dsha-hook-event.json; export DSHA_HOOK_INPUT; " + command,
                    root = "/mnt/alpine",
                    tree = "/mnt/worktrees/${tree.taskId}",
                    timeout = 30,
                    limit = 32768,
                )
            val exported = captureTree(Active(vm), tree)
            val original = if (canRead) base else emptyMap()
            val changed = (original.keys + exported.keys).filter { original[it] != exported[it] }
            if (
                Capability.WORKSPACE_WRITE in policy.declaredCapabilities.orEmpty() &&
                    result.optInt("exitCode", -1) == 0
            ) {
                changed.forEach { path ->
                    check(
                        permissions
                            .evaluate(policy, Capability.WORKSPACE_WRITE, "/workspace/$path")
                            .effect == PermissionEffect.ALLOW,
                    ) {
                        "Hook 未获准写入 $path"
                    }
                    check(canRead || path !in base) { "Hook 不可覆盖未经读取授权的文件：$path" }
                }
                withContext(Dispatchers.IO) {
                    check(workspaces.current(tree) == base) { "工作副本已改变，Hook 修改未应用" }
                    val next = base.toMutableMap()
                    changed.forEach { path ->
                        if (exported[path] == null) next.remove(path)
                        else next[path] = exported.getValue(path)
                    }
                    workspaces.receive(tree, next)
                }
            }
            result.put(
                "workspaceChangesApplied",
                if (Capability.WORKSPACE_WRITE in policy.declaredCapabilities.orEmpty())
                    changed.size
                else 0,
            )
        } finally {
            withContext(NonCancellable) {
                vm.close()
                withContext(Dispatchers.IO) {
                    check(directory.canonicalFile.parentFile == extensionRoot)
                    directory.deleteRecursively()
                }
            }
        }
    }

    suspend fun execute(
        task: PhoneTask,
        command: String,
        cwd: String = "/workspace",
        timeout: Int = 30,
        limit: Int = 65536,
        onStarted: () -> Unit = {},
        onOutput: (String, String) -> Unit = { _, _ -> },
        worktreeId: String = task.id,
    ): JSONObject = lock.withLock {
        val policy = PermissionContext(sessionId = task.sessionId, projectId = task.projectId)
        check(
            !permissions.requiresBroker(
                policy,
                setOf(Capability.WORKSPACE_READ, Capability.WORKSPACE_WRITE),
            ),
        ) {
            "当前文件规则需要逐路径审查，请使用 workspace_read/write；完整 Linux 程序不能绕过文件限制"
        }
        val scope = prepareTask(task)
        val entry = machine(scope)
        check(entry.terminal == null) { "此工作域的本地终端正在使用环境，请关闭后重试" }
        val tree = withContext(Dispatchers.IO) { workspaces.begin(scope, worktreeId, task.id) }
        val path = importTree(entry, tree)
        update(info(scope).copy(phase = "running", detail = "正在执行终端命令"))
        try {
            val result =
                entry.vm.execute(
                    command,
                    cwd,
                    timeout,
                    limit,
                    root = "/mnt/alpine",
                    tree = path,
                    onStarted = onStarted,
                    onOutput = onOutput,
                    useMihomo = true,
                )
            exportTree(entry, tree)
            checkpoint(scope.key, entry)
            result
                .put("runtime", info(scope).json())
                .put("worktreeId", tree.taskId)
                .put("workspace", "/workspace")
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                entry.vm.close()
                active.remove(scope.key)
                update(info(scope).copy(phase = "interrupted", detail = "命令已打断，保留最后完整检查点"))
            }
            throw error
        } catch (error: Throwable) {
            update(info(scope).copy(phase = "broken", detail = error.message.orEmpty()))
            throw error
        }
    }

    private suspend fun checkpoint(key: String, entry: Active) {
        val record = _states.value[key] ?: return
        update(record.copy(phase = "saving", detail = "保存环境与包修改"))
        entry.vm.snapshot()
        update(
            record.copy(
                phase = "ready",
                detail = "环境就绪 · /workspace",
                savedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun openTerminal(
        task: PhoneTask,
        onData: (String, ByteArray) -> Unit = { _, _ -> },
        onExit: (String, Int) -> Unit = { _, _ -> },
    ): RuntimeTerminal = lock.withLock {
        val policy =
            PermissionContext(
                sessionId = task.sessionId,
                projectId = task.projectId,
                actor = "user",
            )
        check(
            permissions.evaluate(policy, Capability.SANDBOX_EXEC, "local-terminal").effect !=
                PermissionEffect.DENY,
        ) {
            "权限规则禁止本地终端"
        }
        check(
            !permissions.requiresBroker(
                policy,
                setOf(Capability.WORKSPACE_READ, Capability.WORKSPACE_WRITE),
            ),
        ) {
            "当前用户文件规则需要逐路径审查，不能开放任意程序终端"
        }
        val scope = prepareTask(task)
        val entry = machine(scope)
        check(entry.terminal == null) { "此工作域已有本地终端" }
        val tree = withContext(Dispatchers.IO) { workspaces.begin(scope, task.id) }
        val guest = importTree(entry, tree)
        entry.vm.onPty = onData
        entry.vm.onPtyExit = onExit
        val id = entry.vm.openPty(root = "/mnt/alpine", tree = guest)
        RuntimeTerminal(entry.vm, id, tree, scope).also {
            entry.terminal = it
            update(info(scope).copy(phase = "running", detail = "本地交互终端"))
        }
    }

    suspend fun closeTerminal(terminal: RuntimeTerminal) = lock.withLock {
        val entry = active[terminal.scope.key] ?: return@withLock
        if (entry.terminal?.id != terminal.id) return@withLock
        if (entry.vm.failed.isNotBlank()) {
            entry.vm.close()
            active.remove(terminal.scope.key)
            update(info(terminal.scope).copy(phase = "interrupted", detail = "终端进程退出，最后保存的工作副本保留"))
            return@withLock
        }
        try {
            terminal.vm.closePty(terminal.id)
            exportTree(entry, terminal.tree)
            checkpoint(terminal.scope.key, entry)
        } finally {
            entry.terminal = null
            entry.vm.onPty = { _, _ -> }
            entry.vm.onPtyExit = { _, _ -> }
        }
    }

    private fun checkLegacyFilePolicy(task: PhoneTask, path: String, mode: String) {
        if (task.access == "full" || task.terminalConfig.isBlank()) return
        val snapshot = JSONObject(task.terminalConfig)
        if (!snapshot.has("rules")) return
        val policy = dev.deepseek.mobile.terminal.TerminalPolicy(snapshot.getString("rules"))
        if (!policy.hasPathRules) return
        val result =
            policy.inspectPaths(
                listOf(dev.deepseek.mobile.terminal.PathUse(path, mode)),
                "/workspace",
            )
        check(result.decision != dev.deepseek.mobile.terminal.RuleDecision.DENY) {
            "文件被命令配置禁止：${result.reasons.joinToString()}"
        }
        check(result.decision != dev.deepseek.mobile.terminal.RuleDecision.SENSITIVE) {
            "此文件需要逐路径审批；请将这条旧路径规则迁移到统一权限的 ASK 规则"
        }
    }

    suspend fun workspaceTool(
        task: PhoneTask,
        method: String,
        args: JSONObject,
        worktreeId: String = task.id,
    ): JSONObject = lock.withLock {
        val scope = prepareTask(task)
        val tree = withContext(Dispatchers.IO) { workspaces.begin(scope, worktreeId, task.id) }
        val live = active[scope.key]
        if (live != null && worktreeId in live.imported) exportTree(live, tree)
        val files = withContext(Dispatchers.IO) { workspaces.current(tree) }.toMutableMap()
        if (method == "workspace_list") {
            val prefix = args.optString("path").removePrefix("/workspace").trim('/')
            if (prefix.isNotEmpty()) WorkspaceManager.validPath(prefix)
            checkLegacyFilePolicy(task, "/workspace/$prefix", "r")
            val children = linkedMapOf<String, JSONObject>()
            files.forEach { (path, version) ->
                val relative =
                    if (prefix.isBlank()) path
                    else if (path.startsWith("$prefix/")) path.removePrefix("$prefix/")
                    else return@forEach
                val name = relative.substringBefore('/')
                children[name] =
                    JSONObject()
                        .put("name", name)
                        .put("directory", '/' in relative)
                        .put("sha256", if ('/' in relative) JSONObject.NULL else version.hash)
            }
            val offset = args.optInt("offset").coerceAtLeast(0)
            return@withLock JSONObject()
                .put("files", JSONArray(children.values.toList().drop(offset).take(250)))
                .put("total", children.size)
                .put("offset", offset)
        }
        val path = WorkspaceManager.validPath(args.getString("name").removePrefix("/workspace/"))
        val capability =
            if (method == "workspace_write") Capability.WORKSPACE_WRITE
            else Capability.WORKSPACE_READ
        val permission =
            permissions.evaluate(
                PermissionContext(sessionId = task.sessionId, projectId = task.projectId),
                capability,
                "/workspace/$path",
            )
        check(permission.effect != PermissionEffect.DENY) { permission.reason }
        checkLegacyFilePolicy(
            task,
            "/workspace/$path",
            if (method == "workspace_write") "w" else "r",
        )
        if (method == "workspace_read") {
            var name = path
            var version = files[name] ?: error("文件不存在")
            repeat(8) {
                if (version.link) {
                    val target =
                        String(
                            withContext(Dispatchers.IO) { workspaces.content(version) },
                            Charsets.UTF_8,
                        )
                    val base = name.substringBeforeLast('/', "")
                    val normalized =
                        java.nio.file.Paths.get("/workspace/" + base)
                            .resolve(target)
                            .normalize()
                            .toString()
                    check(normalized.startsWith("/workspace/")) { "符号链接超出工作区" }
                    name = WorkspaceManager.validPath(normalized.removePrefix("/workspace/"))
                    check(
                        permissions
                            .evaluate(
                                PermissionContext(
                                    sessionId = task.sessionId,
                                    projectId = task.projectId,
                                ),
                                Capability.WORKSPACE_READ,
                                normalized,
                            )
                            .effect == PermissionEffect.ALLOW,
                    )
                    version = files[name] ?: error("链接目标不存在")
                }
            }
            check(!version.link) { "符号链接循环" }
            checkLegacyFilePolicy(task, "/workspace/$name", "r")
            val bytes = withContext(Dispatchers.IO) { workspaces.content(version) }
            require(bytes.size <= 100000) { "文件超过100KB，请使用终端分页读取或发布文件预览" }
            val text =
                Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            return@withLock JSONObject()
                .put("name", path)
                .put("text", text)
                .put("sha256", version.hash)
        }
        require(method == "workspace_write")
        check(
            files.keys.none { existing ->
                path.startsWith(existing + "/") || existing.startsWith(path + "/")
            },
        ) {
            "文件路径与现有文件或符号链接冲突"
        }
        val bytes = args.getString("text").toByteArray(Charsets.UTF_8)
        require(bytes.size <= 100000)
        val expected = args.optString("expected_sha256")
        if (expected.isNotBlank()) check(files[path]?.hash == expected) { "文件已改变，请重新读取并合并" }
        check(files[path]?.link != true) { "请写入链接实际目标，不能隐式覆盖符号链接" }
        val version =
            withContext(Dispatchers.IO) {
                workspaces.stage(bytes, executable = files[path]?.executable == true)
            }
        if (live != null && worktreeId in live.imported)
            live.vm.guestWrite("/worktrees/$worktreeId/$path", bytes, version.executable)
        files[path] = version
        withContext(Dispatchers.IO) { workspaces.receive(tree, files) }
        if (live != null && worktreeId in live.imported && live.terminal == null)
            checkpoint(scope.key, live)
        JSONObject()
            .put("name", path)
            .put("sha256", version.hash)
            .put("message", "已写入任务工作副本，待合并到共享工作区")
    }

    suspend fun publication(
        task: PhoneTask,
        path: String,
        worktreeId: String = task.id,
    ): ByteArray = lock.withLock {
        val scope = prepareTask(task)
        val tree = withContext(Dispatchers.IO) { workspaces.begin(scope, worktreeId, task.id) }
        active[scope.key]?.takeIf { worktreeId in it.imported }?.let { exportTree(it, tree) }
        val relative = WorkspaceManager.validPath(path.removePrefix("/workspace/"))
        checkLegacyFilePolicy(task, "/workspace/$relative", "r")
        check(
            permissions
                .evaluate(
                    PermissionContext(sessionId = task.sessionId, projectId = task.projectId),
                    Capability.WORKSPACE_READ,
                    "/workspace/$relative",
                )
                .effect == PermissionEffect.ALLOW,
        ) {
            "文件发布未获得读取权限"
        }
        val permission =
            permissions.evaluate(
                PermissionContext(sessionId = task.sessionId, projectId = task.projectId),
                Capability.WORKSPACE_READ,
                "/workspace/$relative",
            )
        check(permission.effect != PermissionEffect.DENY) { permission.reason }
        val entry =
            withContext(Dispatchers.IO) { workspaces.current(tree)[relative] } ?: error("文件不存在")
        check(!entry.link) { "请发布链接指向的实际文件" }
        withContext(Dispatchers.IO) { workspaces.content(entry) }
            .also { require(it.size <= 32 * 1024 * 1024) { "预览文件最多32MB" } }
    }

    suspend fun resolveWorktree(
        tree: Worktree,
        merge: Boolean,
        policy: PermissionContext,
        ask: suspend (PermissionResult) -> Boolean = { false },
    ): JSONObject = lock.withLock {
        val entry = active[tree.scope.key]
        check(entry?.terminal == null) { "请先关闭本地终端再合并工作副本" }
        if (entry != null) {
            release(tree.scope.key, entry)
        }
        val current = withContext(Dispatchers.IO) { workspaces.load(tree.scope, tree.taskId) }
        check(current.state in setOf("active", "ready", "conflicted")) { "工作副本已合并或丢弃" }
        try {
            for (change in withContext(Dispatchers.IO) { workspaces.review(current) }) permissions
                .authorize(policy, Capability.WORKSPACE_WRITE, "/workspace/" + change.path, ask)
            withContext(Dispatchers.IO) {
                if (merge) workspaces.merge(current)
                else {
                    workspaces.discard(current)
                    JSONObject().put("discarded", true)
                }
            }
        } finally {
            permissions.finish(policy)
        }
    }

    /** Build in a disposable copy, verify it is data-free, then atomically replace the template. */
    suspend fun installGlobalPackages(
        policy: PermissionContext,
        packages: List<String>,
    ): JSONObject = lock.withLock {
        PackageManager.validate(packages)
        check(
            permissions
                .evaluate(policy, Capability.GLOBAL_TOOLCHAIN, packages.joinToString(","))
                .effect != PermissionEffect.DENY,
        )
        check(active.values.none { it.terminal != null }) { "请先关闭本地终端，再更新共享工具链" }
        // First setup uses the same verified distribution/toolchain initialization path.
        prepareTemplate(
            if (policy.projectId.isBlank()) WorkspaceScope("session", policy.sessionId)
            else WorkspaceScope("project", policy.projectId),
        )
        for ((key, entry) in active.toMap()) release(key, entry)
        val parent = File(context.filesDir, "toolchains/updates").canonicalFile.apply { mkdirs() }
        val directory = File(parent, java.util.UUID.randomUUID().toString()).apply { mkdirs() }
        val vm =
            LinuxVm(context, directory) {
                !permissions.requiresBroker(policy, setOf(Capability.NETWORK)) &&
                    permissions.evaluate(policy, Capability.NETWORK).effect ==
                        PermissionEffect.ALLOW
            }
        try {
            withContext(Dispatchers.IO) {
                File(template, "machine.state").copyTo(File(directory, "machine.state"))
            }
            check(vm.start().optBoolean("restored"))
            val result =
                vm.execute(
                    "set -e; apk add --no-cache " +
                        packages.joinToString(" ") +
                        "; apk info -e " +
                        packages.joinToString(" "),
                    root = "/mnt/alpine",
                    timeout = 300,
                )
            checkResult(result, "全局工具链安装")
            check(
                vm.guestList("/workspace").length() == 0 &&
                    vm.guestList("/worktrees").length() == 0,
            ) {
                "模板包含工作数据，更新未发布"
            }
            vm.snapshot()
            withContext(Dispatchers.IO) {
                val target = AtomicFile(File(template, "machine.state"))
                val out = target.startWrite()
                try {
                    File(directory, "machine.state").inputStream().use { it.copyTo(out) }
                    target.finishWrite(out)
                } catch (error: Throwable) {
                    target.failWrite(out)
                    throw error
                }
                check(
                    File(template, "machine.state").length() ==
                        File(directory, "machine.state").length(),
                )
                val ready = AtomicFile(File(template, "ready.json"))
                val stream = ready.startWrite()
                try {
                    stream.write(
                        JSONObject()
                            .put("engine", "v86-0.5.473")
                            .put("verifiedAt", System.currentTimeMillis())
                            .put("addedPackages", JSONArray(packages))
                            .toString()
                            .toByteArray(),
                    )
                    ready.finishWrite(stream)
                } catch (error: Throwable) {
                    ready.failWrite(stream)
                    throw error
                }
            }
            result.put("scope", "global-toolchain").put("appliesTo", "新建或重置后的 Light 环境；现有项目环境保持原样")
        } finally {
            withContext(NonCancellable) {
                vm.close()
                withContext(Dispatchers.IO) {
                    check(directory.canonicalFile.parentFile == parent)
                    directory.deleteRecursively()
                }
            }
        }
    }

    suspend fun selectLifetime(scope: WorkspaceScope, lifetime: RuntimeLifetime) = lock.withLock {
        val record = info(scope)
        check(scope.key !in active && record.phase in setOf("not_initialized", "deleted")) {
            "切换生命周期前请重置环境；工作区会保留"
        }
        update(record.copy(lifetime = lifetime))
    }

    private suspend fun clearDirectory(scope: WorkspaceScope) =
        withContext(Dispatchers.IO) {
            val dir = directory(scope)
            check(
                dir.canonicalPath.startsWith(root.path + File.separator) &&
                    !java.nio.file.Files.isSymbolicLink(dir.toPath()),
            )
            java.nio.file.Files.walk(dir.toPath()).use { entries ->
                entries.sorted(java.util.Comparator.reverseOrder()).forEach {
                    java.nio.file.Files.delete(it)
                }
            }
        }

    private suspend fun release(key: String, entry: Active) {
        val record = _states.value.getValue(key)
        if (record.lifetime == RuntimeLifetime.PERSISTENT) checkpoint(key, entry)
        entry.vm.close()
        active.remove(key)
        if (record.lifetime == RuntimeLifetime.TEMPORARY) {
            clearDirectory(record.scope)
            update(
                record.copy(phase = "not_initialized", detail = "临时环境已释放，工作副本与记忆保留", savedAt = 0),
            )
        }
    }

    suspend fun delete(scope: WorkspaceScope) {
        reset(scope)
        lock.withLock {
            check(scope.key !in active)
            update(info(scope).copy(phase = "deleted", detail = "运行环境已删除，工作区保留"))
        }
    }

    suspend fun selectKind(scope: WorkspaceScope, kind: RuntimeKind) = lock.withLock {
        require(kind.available)
        check(!File(directory(scope), "machine.state").exists()) { "更换运行环境前需要重置，工作区与记忆会保留" }
        update(info(scope).copy(kind = kind))
    }

    suspend fun reset(scope: WorkspaceScope) = lock.withLock {
        val record = info(scope)
        check(active[scope.key]?.terminal == null) { "请先关闭本地终端" }
        update(record.copy(phase = "resetting", detail = "保留工作区、历史与记忆，重建环境"))
        active.remove(scope.key)?.vm?.close()
        withContext(Dispatchers.IO) {
            clearDirectory(scope)
        }
        update(record.copy(phase = "not_initialized", detail = "环境已重置，首次执行时重新创建", savedAt = 0))
    }

    suspend fun storage(scope: WorkspaceScope): JSONObject =
        withContext(Dispatchers.IO) {
            fun size(dir: File): Long =
                if (dir.exists())
                    java.nio.file.Files.walk(dir.toPath()).use { paths ->
                        paths
                            .filter {
                                java.nio.file.Files.isRegularFile(
                                    it,
                                    java.nio.file.LinkOption.NOFOLLOW_LINKS,
                                )
                            }
                            .mapToLong { java.nio.file.Files.size(it) }
                            .sum()
                    }
                else 0L
            JSONObject()
                .put("runtimeBytes", size(directory(scope)))
                .put("sharedToolchainBytes", size(template))
                .put("distributionBytes", size(RuntimeArtifacts.directory(context)))
                .put("workspaceBytes", size(workspaces.workspace(scope)))
        }

    suspend fun close() = lock.withLock {
        for ((key, entry) in active.toMap()) {
            try {
                entry.terminal?.let {
                    entry.vm.closePty(it.id)
                    exportTree(entry, it.tree)
                }
                release(key, entry)
            } catch (error: Throwable) {
                entry.vm.close()
                active.remove(key)
                throw error
            }
        }
    }
}
