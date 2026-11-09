package dev.deepseek.mobile.harness

import android.content.Context
import dev.deepseek.mobile.PhoneTask
import dev.deepseek.mobile.runtime.RuntimeManager

/**
 * App-owned policy/configuration. No instance is exposed to the Linux guest or JavaScript Agent.
 */
internal class HarnessCoordinator private constructor(context: Context) {
    val permissions = PermissionManager(context)
    val approvals = ApprovalLedger(context)
    val contexts = ContextManager(context, dev.deepseek.mobile.MemoryStore(context), permissions)
    val workspaces = WorkspaceManager(context)
    val runtimes = RuntimeManager(context, permissions, workspaces)
    val skills = SkillStore(context)
    val extensions = ExtensionManager(context, permissions, runtimes)
    val plugins = PluginManager(context, permissions)
    val packages = dev.deepseek.mobile.runtime.PackageManager(context, permissions, runtimes)

    fun context(task: PhoneTask) =
        PermissionContext(
            sessionId = task.sessionId,
            projectId = task.projectId,
            environment =
                runCatching { org.json.JSONObject(task.terminalConfig).getString("level") }
                    .getOrDefault("sandbox"),
        )

    fun worktreeId(task: PhoneTask, args: org.json.JSONObject): String {
        val id = args.optString("_agentId")
        if (id.isBlank()) return task.id
        val owner =
            dev.deepseek.mobile.TeamState.agents.firstOrNull { it.id == id } ?: error("子智能体不存在")
        check(owner.sessionId == task.sessionId && owner.taskId == task.id) { "子智能体不属于当前任务" }
        return owner.id
    }

    companion object {
        @Volatile private var instance: HarnessCoordinator? = null

        fun get(context: Context): HarnessCoordinator =
            instance
                ?: synchronized(this) {
                    instance
                        ?: HarnessCoordinator(context.applicationContext).also { instance = it }
                }
    }
}
