package dev.deepseek.mobile.terminal

import android.content.Context
import android.util.AtomicFile
import androidx.compose.runtime.mutableStateMapOf
import dev.deepseek.mobile.AssistantState
import dev.deepseek.mobile.ChatEntry
import dev.deepseek.mobile.PhoneTask
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

class TerminalManager(
    private val context: Context,
    private val review: suspend (PhoneTask, JSONObject, ReviewerConfig) -> ReviewVerdict,
    private val approve: suspend (PhoneTask, TerminalRun, String) -> String,
) {
    private data class Output(
        val stdout: String,
        val stderr: String,
        val code: Int,
        val truncated: Boolean,
    )

    internal val harness = dev.deepseek.mobile.harness.HarnessCoordinator.get(context)
    val config = TerminalConfigStore(context)
    val connections = TerminalConnections(context)
    val runs = mutableStateMapOf<String, TerminalRun>()
    private val grants = mutableSetOf<String>()
    private val executing =
        java.util.concurrent.ConcurrentHashMap<String, Pair<ITerminalWorker, String>>()
    private val jobs = mutableMapOf<String, Job>()
    private val diskJob = SupervisorJob()
    private val diskScope = CoroutineScope(diskJob + Dispatchers.IO)
    private val diskLock = Mutex()
    private var pendingWrite: Job? = null

    init {
        try {
            val file = AtomicFile(File(context.filesDir, "terminal-history.json"))
            if (file.baseFile.exists()) {
                val history = JSONArray(String(file.readFully(), Charsets.UTF_8))
                for (i in 0 until history.length()) {
                    val j = history.getJSONObject(i)
                    val run =
                        TerminalRun(
                            j.getString("id"),
                            j.getString("taskId"),
                            j.getString("sessionId"),
                            j.getString("command"),
                            j.getString("cwd"),
                            j.getString("level"),
                        )
                    run.status =
                        j.getString("status").let {
                            if (it in setOf("reviewing", "waiting", "running")) "interrupted"
                            else it
                        }
                    run.output = j.optString("output")
                    run.stderr = j.optString("stderr")
                    run.reason = j.optString("reason")
                    run.exitCode = if (j.isNull("exitCode")) null else j.getInt("exitCode")
                    run.truncated = j.optBoolean("truncated")
                    run.stage = j.optString("stage", "legacy")
                    run.executionStarted =
                        if (j.has("executionStarted") && !j.isNull("executionStarted"))
                            j.optBoolean("executionStarted")
                        else null
                    run.verifiedUid =
                        if (j.has("verifiedUid") && !j.isNull("verifiedUid"))
                            j.getInt("verifiedUid")
                        else null
                    run.errorCode = j.optString("errorCode").takeUnless { it == "null" }.orEmpty()
                    runs[run.id] = run
                }
            }
        } catch (_: Exception) {
            AssistantState.error = "终端历史读取失败；会话记录不受影响"
        }
    }

    fun workspace(level: TerminalLevel, sessionId: String): String {
        require(sessionId.matches(Regex("[a-f0-9-]{36}")))
        return when (level) {
            TerminalLevel.SANDBOX -> "/workspace"
            TerminalLevel.ISOLATED -> "/dev.deepseek.mobile/workplace/session_$sessionId"
            TerminalLevel.APP ->
                File(context.filesDir, "terminal/workplace/session_$sessionId")
                    .apply { mkdirs() }
                    .canonicalPath
            TerminalLevel.SHELL -> "/data/local/tmp/dev.deepseek.mobile-shell/session_$sessionId"
            else -> "/data/local/tmp/dev.deepseek.mobile/root/session_$sessionId"
        }
    }

    /**
     * Fixed, non-mutating diagnostics. No history, workspace creation, policy edits or user files.
     */
    suspend fun connectionStatus(level: TerminalLevel, probe: Boolean = false): JSONObject {
        if (level == TerminalLevel.SANDBOX) {
            val task = AssistantState.running
            val conversation =
                AssistantState.sessions.firstOrNull { it.id == task?.sessionId }
                    ?: AssistantState.sessions.firstOrNull { it.id == AssistantState.selectedId }
            val scope =
                if (task != null) harness.runtimes.scope(task)
                else if (conversation?.projectId?.isNotBlank() == true)
                    dev.deepseek.mobile.harness.WorkspaceScope("project", conversation.projectId)
                else
                    dev.deepseek.mobile.harness.WorkspaceScope(
                        "session",
                        requireNotNull(conversation).id,
                    )
            val info = harness.runtimes.info(scope)
            return JSONObject()
                .put("selectedLevel", "sandbox")
                .put(
                    "connection",
                    JSONObject()
                        .put("phase", info.phase)
                        .put("ready", info.phase == "ready")
                        .put("detail", info.detail),
                )
                .put("runtime", info.json())
                .put("workspace", "/workspace")
                .put("guestUid", 0)
                .put("androidRoot", false)
                .put("interactive", true)
                .put(
                    "network",
                    if (harness.runtimes.networkAllowed(scope)) "allowed" else "blocked_by_policy",
                )
                .put(
                    "message",
                    "真实 Linux 虚拟机，首次 terminal_exec 时创建。Guest Root 不是 Android Root；不挂载 Android 私有目录。工具仅在初始化成功后可用。",
                )
        }
        val state = connections.state(level)
        val result =
            JSONObject()
                .put("selectedLevel", level.key)
                .put("connection", state.json())
                .put("requesterPackage", context.packageName)
                .put("requesterUid", android.os.Process.myUid())
                .put("interactive", false)
                .put("probePerformed", false)
                .put("message", "档位、实际 UID、命令审查和程序是否存在是独立状态。原生接入不需要 rish；Root 不会安装额外程序。")
        if (!state.ready || level == TerminalLevel.ISOLATED) return result
        val worker = connections.worker(level)
        result.put("identity", withContext(Dispatchers.IO) { JSONObject(worker.info()) })
        if (!probe || level == TerminalLevel.ROOT_GUARDED) return result
        val id = withContext(Dispatchers.IO) { worker.readOnlyProbe() }
        try {
            val completed =
                withTimeout(15000) {
                    while (true) {
                        val output = withContext(Dispatchers.IO) { JSONObject(worker.poll(id)) }
                        if (output.getBoolean("done")) return@withTimeout output
                        delay(50)
                    }
                    error("unreachable")
                }
            return result
                .put("probePerformed", true)
                .put("probeCommand", "id; pwd; uname -r; ls /proc/self/ns")
                .put("probe", completed)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { worker.cancel(id) }
        }
    }

    suspend fun execute(
        task: PhoneTask,
        args: JSONObject,
        overrideSnapshot: TerminalSnapshot? = null,
    ): JSONObject = executeCommand(task, args, overrideSnapshot, null, null)

    internal suspend fun executeManually(
        task: PhoneTask,
        command: String,
        cwd: String,
        onRun: (TerminalRun) -> Unit,
        confirm: suspend (String) -> Boolean,
    ): JSONObject =
        executeCommand(
            task,
            JSONObject().put("command", command).put("cwd", cwd),
            null,
            onRun,
            confirm,
        )

    private suspend fun executeCommand(
        task: PhoneTask,
        args: JSONObject,
        overrideSnapshot: TerminalSnapshot?,
        onManualRun: ((TerminalRun) -> Unit)?,
        confirmManual: (suspend (String) -> Boolean)?,
    ): JSONObject {
        check(task.access !in setOf("none", "read")) { "完全禁止模式仅支持聊天" }
        check(task.terminalEnabled) { "本任务未启用终端" }
        val snapshot = overrideSnapshot ?: TerminalSnapshot.from(JSONObject(task.terminalConfig))
        val level = TerminalLevel.of(snapshot.level)
        val home = workspace(level, task.sessionId)
        val cwd = normalizePath(args.optString("cwd").ifBlank { home }, home)
        val command = args.getString("command")
        require(command.isNotBlank() && command.length <= 16_384)
        val run =
            TerminalRun(
                taskId = task.id,
                sessionId = task.sessionId,
                command = command,
                cwd = cwd,
                level = level.key,
            )
        if (onManualRun == null) {
            runs[run.id] = run
            val owner =
                dev.deepseek.mobile.TeamState.agents.firstOrNull {
                    it.id == args.optString("_agentId")
                }
            (owner?.messages ?: AssistantState.sessions.find { it.id == task.sessionId }?.messages)
                ?.add(ChatEntry("terminal", run.id, taskId = task.id))
            AssistantState.persist()
            persist()
            jobs[run.id] = currentCoroutineContext().job
        } else onManualRun(run)
        try {
            if (level == TerminalLevel.SANDBOX)
                return executeSandbox(task, args, run, snapshot, confirmManual)

            if (
                harness.permissions.requiresBroker(
                    harness.context(task),
                    setOf(
                        dev.deepseek.mobile.harness.Capability.NETWORK,
                        dev.deepseek.mobile.harness.Capability.WORKSPACE_READ,
                        dev.deepseek.mobile.harness.Capability.WORKSPACE_WRITE,
                    ),
                )
            )
                return deny(run, "当前精细网络 / 文件规则无法约束原生 Shell；请使用 Sandbox 或逐路径文件工具")
            val capability =
                when (level) {
                    TerminalLevel.APP -> dev.deepseek.mobile.harness.Capability.APP_EXEC
                    TerminalLevel.SHELL -> dev.deepseek.mobile.harness.Capability.SHELL_EXEC
                    TerminalLevel.ROOT,
                    TerminalLevel.ROOT_GUARDED -> dev.deepseek.mobile.harness.Capability.ROOT_EXEC
                    else -> dev.deepseek.mobile.harness.Capability.SANDBOX_EXEC
                }
            val permission =
                harness.permissions.evaluate(
                    harness.context(task).copy(environment = level.key),
                    capability,
                    command,
                )
            if (permission.effect == dev.deepseek.mobile.harness.PermissionEffect.DENY)
                return deny(run, permission.reason)
            if (permission.effect == dev.deepseek.mobile.harness.PermissionEffect.ASK) {
                val approved =
                    if (confirmManual != null) confirmManual(permission.reason)
                    else task.access != "auto" && approve(task, run, permission.reason) != "deny"
                if (!approved) return deny(run, "此命令未获人工批准")
                if (
                    harness.permissions
                        .evaluate(harness.context(task), capability, command)
                        .effect == dev.deepseek.mobile.harness.PermissionEffect.DENY
                )
                    return deny(run, "等待期间权限规则改变")
            }

            run.stage = "connection"
            if (level != TerminalLevel.ISOLATED) {
                val worker = connections.awaitWorker(level)
                val identity = withContext(Dispatchers.IO) { JSONObject(worker.info()) }
                run.verifiedUid = identity.getInt("uid")
            }
            if (level == TerminalLevel.ROOT_GUARDED) {
                val worker = connections.worker(level)
                val capability = withContext(Dispatchers.IO) { JSONObject(worker.info()) }
                if (!capability.optBoolean("guard_prerequisites"))
                    return deny(
                        run,
                        "本内核缺少 ${capability.optJSONArray("guard_missing")} 命名空间，受限 Root 无法建立隔离。未启动命令，也不会退回完全 Root。",
                    )
            }
            run.stage = "command_review"
            var plan: CommandPlan? = null
            var timeout = args.optInt("timeout_seconds", 30).coerceIn(1, 300)
            var outputLimit = 65536
            var sensitiveApproved = false
            if (task.access != "full") {
                val policy = TerminalPolicy(snapshot.rules)
                var inspection = policy.inspect(command, cwd, home)
                plan = inspection.plan
                timeout = minOf(timeout, policy.timeoutSeconds)
                outputLimit = policy.outputBytes
                if (inspection.decision == RuleDecision.DENY)
                    return deny(run, inspection.reasons.joinToString("\n"))
                if (level != TerminalLevel.ISOLATED) {
                    val worker = connections.worker(level)
                    val native =
                        withContext(Dispatchers.IO) {
                            JSONObject(
                                worker.inspect(
                                    JSONObject()
                                        .put("rules", snapshot.rules)
                                        .put("command", command)
                                        .put("cwd", cwd)
                                        .put("workspace", home)
                                        .toString(),
                                ),
                            )
                        }
                    val decision = RuleDecision.valueOf(native.getString("decision"))
                    val reasons = AssistantState.strings(native.getJSONArray("reasons"))
                    inspection =
                        inspection.copy(
                            decision =
                                if (decision.ordinal > inspection.decision.ordinal) decision
                                else inspection.decision,
                            reasons = (inspection.reasons + reasons).distinct(),
                        )
                    if (inspection.decision == RuleDecision.DENY)
                        return deny(run, inspection.reasons.joinToString("\n"))
                }
                if (confirmManual != null) {
                    sensitiveApproved = inspection.decision == RuleDecision.SENSITIVE
                    if (sensitiveApproved && !confirmManual(inspection.reasons.joinToString("\n")))
                        return deny(run, "用户拒绝本次命令")
                } else {
                    val grantKey =
                        policy.grantKey(task.sessionId, snapshot, cwd, requireNotNull(plan))
                    // Hard rules are evaluated again even for a saved similar-command grant.
                    val reviewer = ReviewerConfig.parse(snapshot.reviewer)
                    run.stage = "agent_review"
                    val verdict =
                        review(
                            task,
                            JSONObject()
                                .put("kind", "terminal")
                                .put("level", level.title)
                                .put("cwd", cwd)
                                .put("workspace", home)
                                .put("command", plan.shell())
                                .put("static_decision", inspection.decision.name)
                                .put("static_reasons", JSONArray(inspection.reasons)),
                            reviewer,
                        )
                    if (verdict.decision == RuleDecision.DENY) return deny(run, verdict.reason)
                    val sensitive =
                        inspection.decision == RuleDecision.SENSITIVE ||
                            verdict.decision == RuleDecision.SENSITIVE
                    run.reason =
                        (inspection.reasons + verdict.reason)
                            .filter { it.isNotBlank() }
                            .joinToString("\n")
                    if (task.access == "auto") {
                        if (verdict.decision != RuleDecision.ALLOW)
                            return deny(run, "替我审批不会弹出人工审批；审查员未明确允许：${verdict.reason}")
                    } else if (sensitive && grantKey !in grants) {
                        run.stage = "approval"
                        run.status = "waiting"
                        val answer = approve(task, run, run.reason)
                        if (answer == "deny") return deny(run, "用户拒绝本次命令")
                        if (answer == "similar") grants.add(grantKey)
                    }
                    sensitiveApproved = sensitive
                }
            }
            currentCoroutineContext().ensureActive()
            run.stage = "starting"
            run.status = "running"
            val executed = if (task.access == "full") command else requireNotNull(plan).shell()
            if (level == TerminalLevel.ISOLATED) {
                val interpreter = IsolatedTerminal(context, task.sessionId)
                val isolatedPlan = plan ?: CommandParser.parse(command)
                require(within(cwd, "/dev.deepseek.mobile/workplace")) { "目录超出隔离终端" }
                // The first cd is interpreted by the same capability boundary as every later
                // command.
                val all =
                    CommandPlan(
                        listOf(SimpleCommand(listOf("cd", cwd), emptyList(), "&&")) +
                            isolatedPlan.commands,
                    )
                run.executionStarted = true
                run.stage = "running"
                val chunks =
                    withTimeout(timeout * 1000L) {
                        withContext(Dispatchers.IO) {
                            val out = StringBuilder()
                            val err = StringBuilder()
                            var bytes = 0
                            var truncated = false
                            val code =
                                interpreter.execute(all) { stdout, stderr ->
                                    val (a, n) = utf8Prefix(stdout, outputLimit - bytes)
                                    out.append(a)
                                    bytes += n
                                    val (b, m) = utf8Prefix(stderr, outputLimit - bytes)
                                    err.append(b)
                                    bytes += m
                                    if (a.length < stdout.length || b.length < stderr.length)
                                        truncated = true
                                }
                            Output(out.toString(), err.toString(), code, truncated)
                        }
                    }
                run.output = chunks.stdout
                run.stderr = chunks.stderr
                run.exitCode = chunks.code
                run.truncated = chunks.truncated
            } else {
                val worker = connections.worker(level)
                val id =
                    withContext(Dispatchers.IO) {
                        worker
                            .start(
                                JSONObject()
                                    .put("sessionId", task.sessionId)
                                    .put("level", level.key)
                                    .put("workspace", home)
                                    .put("cwd", cwd)
                                    .put("command", executed)
                                    .put(
                                        "proxy_port",
                                        dev.deepseek.mobile.proxy.MihomoManager.get(context)
                                            .terminalPort(),
                                    )
                                    .put(
                                        "rules",
                                        if (task.access == "full") JSONObject.NULL
                                        else snapshot.rules,
                                    )
                                    .put("sensitive_approved", sensitiveApproved)
                                    .put(
                                        "protected_paths",
                                        JSONArray(
                                            listOf(
                                                File(context.filesDir, "terminal-policies")
                                                    .canonicalPath,
                                                File(context.filesDir, "conversations-v3.json")
                                                    .canonicalPath,
                                                File(context.filesDir, "events").canonicalPath,
                                                File(context.filesDir, "mihomo").canonicalPath,
                                                File(context.filesDir, "harness/skills")
                                                    .canonicalPath,
                                                File(
                                                        context.applicationInfo.dataDir,
                                                        "shared_prefs/mihomo.xml",
                                                    )
                                                    .canonicalPath,
                                                File(context.filesDir, "memory.md").canonicalPath,
                                                File(context.filesDir, "projects").canonicalPath,
                                                File(
                                                        context.applicationInfo.dataDir,
                                                        "shared_prefs",
                                                    )
                                                    .canonicalPath,
                                            ),
                                        ),
                                    )
                                    .put("timeout_seconds", timeout)
                                    .put("output_bytes", outputLimit)
                                    .toString(),
                            )
                            .also {
                                executing[run.id] = worker to it
                                run.executionStarted = true
                                run.stage = "running"
                            }
                    }
                withTimeout((timeout + 12) * 1000L) {
                    while (true) {
                        val result = withContext(Dispatchers.IO) { JSONObject(worker.poll(id)) }
                        run.output = result.getString("output")
                        run.stderr = result.getString("stderr")
                        run.truncated = result.getBoolean("truncated")
                        if (result.getBoolean("done")) {
                            run.exitCode =
                                if (result.isNull("exitCode")) null else result.getInt("exitCode")
                            if (result.optString("reason").isNotBlank())
                                run.reason += "\n" + result.getString("reason")
                            break
                        }
                        delay(100)
                    }
                }
            }
            run.status = if (run.exitCode == 0) "completed" else "failed"
            if (run.exitCode != 0)
                run.errorCode =
                    when (run.exitCode) {
                        127 -> "PROGRAM_NOT_FOUND"
                        126 -> "PROGRAM_NOT_EXECUTABLE"
                        else -> "COMMAND_EXIT_NONZERO"
                    }
            if (
                level == TerminalLevel.ROOT_GUARDED &&
                    run.exitCode == 126 &&
                    run.stderr.contains("[guard]")
            )
                run.reason = "本设备未能建立受限 Root 隔离；命令未启动，也未降级为完全 Root。"
            run.stage = "finished"
            return result(run)
        } catch (e: CancellationException) {
            run.status = "cancelled"
            run.reason = if (e is TimeoutCancellationException) "终端超时，已终止" else "终端已停止"
            throw e
        } catch (e: Exception) {
            run.status = "failed"
            run.errorCode =
                if (e is TerminalConnectionException) e.connection.errorCode
                else if (run.executionStarted == true) "EXECUTION_FAILED" else "START_FAILED"
            run.reason = e.message ?: "终端执行失败"
            return result(run).put("error", run.reason)
        } finally {
            executing.remove(run.id)?.let { (worker, id) ->
                try {
                    withContext(NonCancellable + Dispatchers.IO) { worker.cancel(id) }
                } catch (_: Exception) {}
            }
            if (onManualRun == null) {
                jobs.remove(run.id)
                persist()
            }
        }
    }

    private suspend fun executeSandbox(
        task: PhoneTask,
        args: JSONObject,
        run: TerminalRun,
        snapshot: TerminalSnapshot,
        confirmManual: (suspend (String) -> Boolean)?,
    ): JSONObject {
        val permissionContext = harness.context(task)
        try {
            val permission =
                harness.permissions.evaluate(
                    permissionContext,
                    dev.deepseek.mobile.harness.Capability.SANDBOX_EXEC,
                    run.command,
                )
            if (permission.effect == dev.deepseek.mobile.harness.PermissionEffect.DENY)
                return deny(run, permission.reason)
            val policy = TerminalPolicy(snapshot.rules)
            if (task.access != "full" && policy.hasPathRules)
                return deny(run, "路径规则需要逐文件执行，不能通过任意 Linux 程序强制保证；请使用文件工具，或在设置中改用统一权限规则。命令未启动。")
            val inspection =
                if (task.access == "full") Inspection(RuleDecision.ALLOW, emptyList(), null)
                else policy.inspectSandbox(run.command, run.cwd, "/workspace")
            if (inspection.decision == RuleDecision.DENY)
                return deny(run, inspection.reasons.joinToString("\n"))
            var sensitive =
                permission.effect == dev.deepseek.mobile.harness.PermissionEffect.ASK ||
                    inspection.decision == RuleDecision.SENSITIVE
            val grantKey = sha256(task.sessionId + snapshot.digest + run.cwd + run.command)
            if (task.access != "full" && confirmManual == null) {
                run.stage = "agent_review"
                val verdict =
                    review(
                        task,
                        JSONObject()
                            .put("kind", "sandbox_terminal")
                            .put("environment", "Linux guest; no Android Root or host paths")
                            .put("cwd", run.cwd)
                            .put("command", run.command)
                            .put("static_reasons", JSONArray(inspection.reasons)),
                        ReviewerConfig.parse(snapshot.reviewer),
                    )
                if (verdict.decision == RuleDecision.DENY) return deny(run, verdict.reason)
                sensitive = sensitive || verdict.decision == RuleDecision.SENSITIVE
                run.reason =
                    (inspection.reasons + verdict.reason)
                        .filter { it.isNotBlank() }
                        .joinToString("\n")
                if (task.access == "auto" && verdict.decision != RuleDecision.ALLOW)
                    return deny(run, "替我审批未获明确允许：${verdict.reason}")
            }
            if (
                confirmManual == null &&
                    task.access == "auto" &&
                    permission.effect == dev.deepseek.mobile.harness.PermissionEffect.ASK
            )
                return deny(run, "此规则需要人工批准；替我审批模式不会弹出审批框")
            if (
                (sensitive &&
                    (confirmManual != null || (task.access != "auto" && grantKey !in grants))) ||
                    permission.effect == dev.deepseek.mobile.harness.PermissionEffect.ASK
            ) {
                run.status = "waiting"
                run.stage = "approval"
                val reason =
                    run.reason.ifBlank {
                        inspection.reasons.joinToString("\n").ifBlank { permission.reason }
                    }
                val answer =
                    if (confirmManual != null) {
                        if (confirmManual(reason)) "once" else "deny"
                    } else approve(task, run, reason)
                when (answer) {
                    "deny" -> return deny(run, "用户拒绝本次命令")
                    "similar" -> grants.add(grantKey)
                }
                if (
                    harness.permissions
                        .evaluate(
                            permissionContext,
                            dev.deepseek.mobile.harness.Capability.SANDBOX_EXEC,
                            run.command,
                        )
                        .effect == dev.deepseek.mobile.harness.PermissionEffect.DENY
                )
                    return deny(run, "等待期间权限规则已变更")
            }
            run.status = "running"
            run.stage = "runtime"
            currentCoroutineContext().ensureActive()
            // Initialization can fail before a process starts; do not claim Android UID 0.
            val output =
                harness.runtimes.execute(
                    task,
                    if (inspection.plan != null) inspection.plan.shell() else run.command,
                    run.cwd,
                    minOf(
                        args.optInt("timeout_seconds", 30).coerceIn(1, 300),
                        policy.timeoutSeconds,
                    ),
                    limit = policy.outputBytes,
                    onStarted = {
                        run.executionStarted = true
                        run.stage = "running"
                    },
                    onOutput = { channel, text ->
                        if (channel == "out") run.output += text else run.stderr += text
                    },
                    worktreeId = harness.worktreeId(task, args),
                )
            run.executionStarted = true
            run.output = output.getString("stdout")
            run.stderr = output.getString("stderr")
            run.exitCode = output.getInt("exitCode")
            run.truncated = output.optBoolean("truncated")
            run.status = if (run.exitCode == 0) "completed" else "failed"
            run.stage = "finished"
            run.errorCode =
                if (run.exitCode == 0) ""
                else if (run.exitCode == 127) "PROGRAM_NOT_FOUND" else "COMMAND_EXIT_NONZERO"
            return result(run)
                .put(
                    "connection",
                    JSONObject()
                        .put("phase", "ready")
                        .put("connected", true)
                        .put("transport", "wasm-linux")
                        .put("guestUid", 0)
                        .put("androidRoot", false),
                )
                .put("runtime", output.optJSONObject("runtime"))
                .put("workspace", "/workspace")
                .put("guestUid", 0)
                .put("androidRoot", false)
                .put("worktreeId", output.optString("worktreeId"))
        } finally {
            harness.permissions.finish(permissionContext)
        }
    }

    private fun deny(run: TerminalRun, reason: String): JSONObject {
        run.errorCode = "POLICY_DENIED"
        run.status = "denied"
        run.reason = reason
        return result(run).put("error", "命令未执行：$reason")
    }

    private fun result(run: TerminalRun) =
        run.json()
            .put("stdout", run.output)
            .put("connection", connections.state(TerminalLevel.of(run.level)).json())
            .put(
                "error",
                if (run.status in setOf("failed", "denied"))
                    run.reason.ifBlank { "命令执行失败，exit=${run.exitCode}" }
                else JSONObject.NULL,
            )
            .put(
                "message",
                "终端${run.status}，阶段 ${run.stage}，exit=${run.exitCode ?: "无退出码"}，启动状态=${run.executionStarted}",
            )

    fun stop(id: String) {
        jobs[id]?.cancel(CancellationException("用户停止终端"))
    }

    fun revokeGrants() {
        grants.clear()
    }

    fun persist() {
        val history = runs.values.sortedBy { it.createdAt }.takeLast(150).map { it.json() }
        pendingWrite?.cancel()
        pendingWrite = diskScope.launch {
            delay(50)
            diskLock.withLock {
                val file = AtomicFile(File(context.filesDir, "terminal-history.json"))
                val stream = file.startWrite()
                try {
                    stream.write(JSONArray(history).toString().toByteArray())
                    file.finishWrite(stream)
                } catch (e: Exception) {
                    file.failWrite(stream)
                    AssistantState.error = "终端历史保存失败"
                }
            }
        }
    }

    fun close() {
        jobs.values.toList().forEach { it.cancel() }
        connections.close()
        persist()
        diskJob.complete()
    }
}
