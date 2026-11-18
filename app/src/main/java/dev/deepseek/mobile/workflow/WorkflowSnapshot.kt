package dev.deepseek.mobile.workflow

import dev.deepseek.mobile.PhoneTask
import org.json.JSONArray
import org.json.JSONObject

internal fun PhoneTask.workflowSnapshot(): JSONObject =
    JSONObject()
        .put("mode", workMode.key)
        .put("task_id", id)
        .put("task_status", status)
        .put("plan", workPlan.json())
        .put("verifications", JSONArray(verifications.takeLast(12).map { it.json() }))
        .put("message", "计划状态由 Agent 报告；验证状态来自真实命令退出码。历史计划仅供延续上下文，不能替代最新用户要求。")
