package dev.deepseek.mobile.terminal

import android.os.ParcelFileDescriptor
import android.os.Process
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** Same transport on app, Shizuku and Root processes. Output stays bounded before Binder IPC. */
open class ProcessWorker : ITerminalWorker.Stub() {
    private class Job(val pid: Int, val limit: Int) {
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        var bytes = 0
        var truncated = false
        @Volatile var done = false
        var code: Int? = null
        var reason = ""

        @Synchronized
        fun append(error: Boolean, text: String) {
            val remaining = (limit - bytes).coerceAtLeast(0)
            val (accepted, count) = utf8Prefix(text, remaining)
            (if (error) stderr else stdout).append(accepted)
            bytes += count
            if (accepted.length < text.length) truncated = true
        }

        @Synchronized
        fun json() =
            JSONObject()
                .put("output", stdout.toString())
                .put("processId", pid)
                .put("stderr", stderr.toString())
                .put("done", done)
                .put("exitCode", code ?: JSONObject.NULL)
                .put("truncated", truncated)
                .put("reason", reason)
                .toString()
    }

    private val jobs = ConcurrentHashMap<String, Job>()
    private val executor = Executors.newCachedThreadPool()
    private val timer = Executors.newSingleThreadScheduledExecutor()

    override fun info(): String {
        val missing =
            listOf("mnt", "pid", "ipc", "net", "uts").filterNot {
                File("/proc/self/ns/$it").exists()
            }
        val credentials = JSONObject()
        runCatching {
            File("/proc/self/status").forEachLine { line ->
                val key = line.substringBefore(':')
                if (
                    key in
                        setOf(
                            "Uid",
                            "Gid",
                            "Groups",
                            "CapInh",
                            "CapPrm",
                            "CapEff",
                            "CapBnd",
                            "CapAmb",
                            "NoNewPrivs",
                        )
                )
                    credentials.put(key, line.substringAfter(':').trim())
            }
        }
        return JSONObject()
            .put("uid", Process.myUid())
            .put("credentials", credentials)
            .put(
                "selinux",
                runCatching { File("/proc/self/attr/current").readText().trim().trimEnd('\u0000') }
                    .getOrDefault(""),
            )
            .put(
                "programs",
                org.json.JSONArray(
                    listOf("sh", "su", "which", "ssh-keygen", "nano").filter { name ->
                        "/system/bin:/system/xbin"
                            .split(':')
                            .filter { it.startsWith('/') }
                            .any { path -> File(path, name).let { it.isFile && it.canExecute() } }
                    },
                ),
            )
            .put("pid", Process.myPid())
            .put("guard_prerequisites", missing.isEmpty())
            .put("guard_missing", org.json.JSONArray(missing))
            .put("guard", "mountns+pidns+readonly-mounts+capabilities+seccomp; probe required")
            .toString()
    }

    override fun inspect(spec: String): String {
        val args = JSONObject(spec)
        val result =
            TerminalPolicy(args.getString("rules")).inspect(
                args.getString("command"),
                args.getString("cwd"),
                args.getString("workspace"),
            ) {
                File(it).canonicalPath
            }
        return JSONObject()
            .put("decision", result.decision.name)
            .put("reasons", org.json.JSONArray(result.reasons))
            .toString()
    }

    @Synchronized
    override fun start(spec: String): String {
        val args = JSONObject(spec)
        require(jobs.values.none { !it.done }) { "终端进程仍在运行" }
        jobs.entries.removeIf { it.value.done }
        val session =
            args.getString("sessionId").also { require(it.matches(Regex("[a-f0-9-]{36}"))) }
        val level = TerminalLevel.of(args.getString("level"))
        require(level != TerminalLevel.ISOLATED)
        when (level) {
            TerminalLevel.SHELL ->
                check(Process.myUid() == 2000) { "此 Shizuku 服务不是 Shell UID 2000，不能用于 Shell 档位" }
            TerminalLevel.ROOT,
            TerminalLevel.ROOT_GUARDED -> check(Process.myUid() == 0) { "未获得 Root UID 0" }
            TerminalLevel.APP -> check(Process.myUid() >= 10000) { "应用用户终端不能使用特权服务" }
            else -> Unit
        }
        val workspace = args.getString("workspace")
        val cwd = args.getString("cwd")
        require(cwd.startsWith('/')) { "当前目录必须为绝对路径" }
        if (level in setOf(TerminalLevel.ROOT, TerminalLevel.ROOT_GUARDED, TerminalLevel.SHELL)) {
            val base =
                if (level == TerminalLevel.SHELL) "/data/local/tmp/dev.deepseek.mobile-shell"
                else "/data/local/tmp/dev.deepseek.mobile"
            val expected =
                if (level == TerminalLevel.SHELL) "$base/session_$session"
                else "$base/root/session_$session"
            require(workspace == expected)
            val components =
                if (level == TerminalLevel.SHELL) listOf(base, workspace)
                else listOf(base, "$base/root", workspace)
            // Read-only commands in an existing external cwd do not create/chmod a Root workspace.
            for (path in
                if (within(cwd, workspace) || level == TerminalLevel.ROOT_GUARDED) components
                else emptyList()) {
                try {
                    android.system.Os.mkdir(path, 448)
                } catch (e: android.system.ErrnoException) {
                    if (e.errno != android.system.OsConstants.EEXIST) throw e
                }
                val stat = android.system.Os.lstat(path)
                require(
                    android.system.OsConstants.S_ISDIR(stat.st_mode) &&
                        stat.st_uid == Process.myUid() &&
                        File(path).canonicalPath == path,
                ) {
                    "工作区目录类型、所有者或真实路径不匹配：$path"
                }
                android.system.Os.chmod(path, 448)
            }
        }
        require(File(cwd).isDirectory) { "当前目录不存在或不可访问" }
        val timeout = args.getInt("timeout_seconds").coerceIn(1, 300)
        val limit = args.getInt("output_bytes").coerceIn(1024, 262144)
        val command =
            args.getString("command").also {
                require(it.length <= 16_384 && !it.contains('\u0000'))
            }
        if (args.has("rules") && !args.isNull("rules")) {
            val policy = TerminalPolicy(args.getString("rules"))
            val inspection = policy.inspect(command, cwd, workspace) { File(it).canonicalPath }
            check(inspection.decision != RuleDecision.DENY) {
                "执行进程复检拒绝：${inspection.reasons.joinToString("；")}"
            }
            check(
                inspection.decision != RuleDecision.SENSITIVE ||
                    args.optBoolean("sensitive_approved"),
            ) {
                "实际路径触发了新的敏感规则，请重新提交审批"
            }
            var directory = cwd
            for (c in requireNotNull(inspection.plan).commands) {
                if (level !in setOf(TerminalLevel.ROOT, TerminalLevel.ROOT_GUARDED))
                    check(
                        TerminalPolicy.pathUses(c, directory).none {
                            it.path == "/system/bin/su" && 'x' in it.permissions
                        },
                    ) {
                        "审查模式的 su -c 仅用于已选择的 Root 档位，不能用来升级当前身份"
                    }
                for (use in TerminalPolicy.pathUses(c, directory)) {
                    val canonical = File(use.path).canonicalPath
                    check(
                        canonical == use.path ||
                            (use.permissions == "x" &&
                                use.path.startsWith("/system/bin/") &&
                                canonical.startsWith("/system/bin/")),
                    ) {
                        "审查模式不跟随间接路径，请使用实际路径：${use.path} → $canonical"
                    }
                    val protected = args.optJSONArray("protected_paths") ?: org.json.JSONArray()
                    if ('w' in use.permissions)
                        for (i in 0 until protected.length()) {
                            val path = protected.getString(i)
                            check(!within(canonical, path) && !within(path, canonical)) {
                                "Agent 不能修改自身审查配置、会话数据库或密钥存储"
                            }
                        }
                }
                if (c.argv[0] == "cd")
                    directory = normalizePath(c.argv.getOrElse(1) { workspace }, directory)
            }
        }
        // Add routing after rechecking the original command against the native policy.
        val port = args.optInt("proxy_port").takeIf { it in 1..65535 }
        return launchProcess(
            dev.deepseek.mobile.proxy.MihomoManager.withProxyEnvironment(command, port),
            cwd,
            level == TerminalLevel.ROOT_GUARDED,
            workspace,
            timeout,
            limit,
        )
    }

    /** Fixed diagnostic, no parameters and no workspace creation or permission changes. */
    @Synchronized
    override fun readOnlyProbe(): String {
        check(jobs.values.none { !it.done }) { "终端正在运行，未进行诊断" }
        jobs.entries.removeIf { it.value.done }
        return launchProcess("id; pwd; uname -r; ls /proc/self/ns", "/", false, "/", 5, 4096)
    }

    private fun launchProcess(
        command: String,
        cwd: String,
        guarded: Boolean,
        workspace: String,
        timeout: Int,
        limit: Int,
    ): String {
        val handles = NativeProcess.spawn(command, cwd, guarded, workspace)
        check(handles.size == 3) { "创建命令进程失败" }
        val job = Job(handles[0], limit)
        val id = UUID.randomUUID().toString()
        jobs[id] = job
        val readers =
            handles.drop(1).mapIndexed { index, fd ->
                executor.submit {
                    try {
                        ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.adoptFd(fd))
                            .bufferedReader()
                            .use { reader ->
                                val buffer = CharArray(2048)
                                while (true) {
                                    val n = reader.read(buffer)
                                    if (n < 0) break
                                    job.append(index == 1, String(buffer, 0, n))
                                }
                            }
                    } catch (e: Exception) {
                        job.append(true, "\n[stream] ${e.message}\n")
                    }
                }
            }
        val timeoutJob =
            timer.schedule(
                {
                    synchronized(job) {
                        if (!job.done) {
                            job.reason = "命令超过 ${timeout}s，已终止进程组"
                            NativeProcess.terminate(job.pid)
                        }
                    }
                },
                timeout.toLong(),
                TimeUnit.SECONDS,
            )
        executor.submit {
            val code = NativeProcess.waitFor(job.pid)
            // A child retaining a pipe cannot make polling hang forever.
            readers.forEach {
                try {
                    it.get(2, TimeUnit.SECONDS)
                } catch (_: Exception) {
                    NativeProcess.terminate(job.pid)
                    it.cancel(true)
                }
            }
            synchronized(job) {
                job.code = code
                job.done = true
            }
            timeoutJob.cancel(false)
        }
        return id
    }

    override fun poll(id: String) = jobs[id]?.json() ?: error("终端任务不存在")

    override fun cancel(id: String) {
        jobs[id]?.let {
            synchronized(it) {
                if (!it.done) {
                    it.reason = "命令已取消"
                    NativeProcess.terminate(it.pid)
                }
            }
        }
    }

    fun close() {
        jobs.keys.forEach { cancel(it) }
        executor.shutdownNow()
        timer.shutdownNow()
    }

    override fun destroy() {
        close()
        kotlin.system.exitProcess(0)
    }
}
