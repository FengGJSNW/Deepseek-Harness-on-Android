package dev.deepseek.mobile.workflow

import org.json.JSONObject

enum class WorkMode(val key: String, val title: String, val description: String) {
    EXECUTE("execute", "执行", "探索、修改并验证结果"),
    PLAN("plan", "规划", "只读探索，形成可继续的计划"),
    REVIEW("review", "审查", "只读检查，报告问题和验证缺口");

    companion object {
        fun from(key: String): WorkMode =
            if (key.isBlank()) EXECUTE else entries.firstOrNull { it.key == key } ?: PLAN
    }
}

/** Shared asset is consumed by the model tool catalog and the native execution boundary. */
internal class WorkflowPolicy(text: String) {
    private val source = JSONObject(text).also { require(it.getInt("schemaVersion") == 1) }

    private fun names(key: String): Set<String> =
        source.getJSONArray(key).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }

    private val readOnly = names("readOnlyTools")
    val workspaceTools = names("workspaceTools")
    val workflowTools = names("workflowTools")
    val tools = workspaceTools + workflowTools

    fun permits(mode: WorkMode, method: String) = mode == WorkMode.EXECUTE || method in readOnly

    fun requireAllowed(mode: WorkMode, method: String) {
        check(permits(mode, method)) { "${mode.title}模式不允许 $method；请由用户切换执行模式后提交新任务" }
    }
}
