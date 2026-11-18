package dev.deepseek.mobile.proxy

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

internal data class FlClashAgentPermissions(
    val start: Boolean = false,
    val nodes: Boolean = false,
    val rules: Boolean = false,
) {
    val enabled
        get() = start || nodes || rules

    fun permits(action: String) =
        when (action) {
            "status" -> enabled
            "start",
            "stop" -> start
            "select" -> nodes
            "rule" -> rules
            else -> false
        }

    fun json(): JSONObject =
        JSONObject().put("start", start).put("nodes", nodes).put("rules", rules)
}

internal class FlClashAgentPolicy(context: Context) {
    private val preferences = context.getSharedPreferences("flclash-agent", Context.MODE_PRIVATE)
    private val mutable: MutableStateFlow<FlClashAgentPermissions>

    init {
        if (!preferences.getBoolean("migrated", false)) {
            val legacy = context.getSharedPreferences("mihomo", Context.MODE_PRIVATE)
            check(
                preferences
                    .edit()
                    .putBoolean("start", legacy.getBoolean("agentStart", false))
                    .putBoolean("nodes", legacy.getBoolean("agentNodes", false))
                    .putBoolean("rules", legacy.getBoolean("agentRules", false))
                    .putBoolean("migrated", true)
                    .commit(),
            ) {
                "无法保存 Agent 代理权限"
            }
        }
        mutable =
            MutableStateFlow(
                FlClashAgentPermissions(
                    preferences.getBoolean("start", false),
                    preferences.getBoolean("nodes", false),
                    preferences.getBoolean("rules", false),
                ),
            )
    }

    val state
        get() = mutable.asStateFlow()

    @Synchronized
    fun set(key: String, allowed: Boolean): FlClashAgentPermissions {
        val next =
            when (key) {
                "start" -> mutable.value.copy(start = allowed)
                "nodes" -> mutable.value.copy(nodes = allowed)
                "rules" -> mutable.value.copy(rules = allowed)
                else -> error("未知 Agent 权限")
            }
        check(preferences.edit().putBoolean(key, allowed).commit()) { "无法保存 Agent 代理权限" }
        mutable.value = next
        return next
    }

    fun require(action: String) {
        check(mutable.value.permits(action)) { "用户未允许 Agent 执行此 FlClash 操作：$action" }
    }
}
