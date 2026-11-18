package dev.deepseek.mobile.workflow

import org.json.JSONArray
import org.json.JSONObject

internal data class PlanStep(
    val id: String,
    val title: String,
    val status: String,
    val dependsOn: List<String> = emptyList(),
    val note: String = "",
) {
    fun json() =
        JSONObject()
            .put("id", id)
            .put("title", title)
            .put("status", status)
            .put("depends_on", JSONArray(dependsOn))
            .put("note", note)
}

/** Plan completion is an Agent report; verification is recorded separately from actual tools. */
internal data class WorkPlan(
    val revision: Int = 0,
    val goal: String = "",
    val steps: List<PlanStep> = emptyList(),
) {
    fun json() =
        JSONObject()
            .put("revision", revision)
            .put("goal", goal)
            .put("steps", JSONArray(steps.map { it.json() }))

    fun update(input: JSONObject): WorkPlan {
        require(input.getInt("expected_revision") == revision) { "计划已变化，请 work_status 后合并更新" }
        val next = parse(input.put("revision", revision + 1))
        require(next.goal.isNotBlank() && next.goal.length <= 500) { "计划目标须为 1–500 字" }
        require(next.steps.size in 1..20) { "计划需要 1–20 步" }
        require(next.steps.map { it.id }.distinct().size == next.steps.size) { "步骤 ID 不能重复" }
        require(next.steps.count { it.status == "in_progress" } <= 1) { "同时只能有一个进行中的步骤" }
        val byId = next.steps.associateBy { it.id }
        for (step in next.steps) {
            require(step.id.matches(Regex("[A-Za-z0-9_-]{1,40}"))) { "步骤 ID 无效" }
            require(
                step.title.isNotBlank() && step.title.length <= 200 && step.note.length <= 500,
            ) {
                "步骤内容过长或为空"
            }
            require(step.status in setOf("pending", "in_progress", "completed", "blocked")) {
                "步骤状态无效"
            }
            require(step.status != "blocked" || step.note.isNotBlank()) { "受阻步骤需要说明原因" }
            require(
                step.dependsOn.distinct().size == step.dependsOn.size &&
                    step.dependsOn.all { it != step.id && it in byId },
            ) {
                "步骤依赖无效"
            }
            if (step.status in setOf("in_progress", "completed"))
                require(step.dependsOn.all { byId.getValue(it).status == "completed" }) {
                    "请先完成依赖步骤"
                }
        }
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        fun visit(id: String) {
            if (id in visited) return
            require(visiting.add(id)) { "步骤依赖存在循环" }
            byId.getValue(id).dependsOn.forEach(::visit)
            visiting.remove(id)
            visited.add(id)
        }
        byId.keys.forEach(::visit)
        return next
    }

    companion object {
        fun parse(input: JSONObject?): WorkPlan {
            if (input == null) return WorkPlan()
            val array = input.optJSONArray("steps") ?: JSONArray()
            require(array.length() <= 20) { "计划步骤过多" }
            return WorkPlan(
                input.optInt("revision"),
                input.optString("goal"),
                (0 until array.length()).map { index ->
                    val s = array.getJSONObject(index)
                    val deps = s.optJSONArray("depends_on") ?: JSONArray()
                    require(deps.length() <= 20)
                    PlanStep(
                        s.getString("id"),
                        s.getString("title"),
                        s.getString("status"),
                        (0 until deps.length()).map { deps.getString(it) },
                        s.optString("note"),
                    )
                },
            )
        }
    }
}

internal data class VerificationRecord(
    val label: String,
    val runId: String,
    val command: String,
    val outcome: String,
    val exitCode: Int?,
    val detail: String,
    val createdAt: Long,
    val id: String = java.util.UUID.randomUUID().toString(),
) {
    fun recovered() =
        if (outcome == "running") copy(outcome = "interrupted", detail = "进程已结束，验证没有完成") else this

    fun json() =
        JSONObject()
            .put("id", id)
            .put("label", label)
            .put("run_id", runId)
            .put("command", command)
            .put("outcome", outcome)
            .put("exit_code", exitCode ?: JSONObject.NULL)
            .put("detail", detail)
            .put("created_at", createdAt)

    companion object {
        fun measured(
            label: String,
            command: String,
            result: JSONObject,
            now: Long,
        ): VerificationRecord {
            val exit = (result.opt("exitCode") as? Number)?.toInt()
            val started = result.optBoolean("executionStarted", false)
            val status = result.optString("status")
            val passed = started && exit == 0 && status in setOf("completed", "success", "done")
            return VerificationRecord(
                label.take(120),
                result.optString("id"),
                result.optString("command", command).take(4000),
                if (passed) "passed"
                else if (status == "cancelled") "cancelled"
                else if (started && exit != null) "failed" else "unavailable",
                exit,
                result.optString("reason", result.optString("error")).take(600),
                now,
            )
        }

        fun parse(input: JSONObject) =
            VerificationRecord(
                input.optString("label"),
                input.optString("run_id"),
                input.optString("command"),
                input.optString("outcome", "unavailable"),
                (input.opt("exit_code") as? Number)?.toInt(),
                input.optString("detail"),
                input.optLong("created_at"),
                input.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
            )
    }
}
