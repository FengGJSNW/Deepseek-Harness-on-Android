package dev.deepseek.mobile.harness

import android.content.Context
import dev.deepseek.mobile.PhoneTask
import dev.deepseek.mobile.runtime.RuntimeManager
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

internal data class HookResult(val arguments: JSONObject, val context: String = "")

internal class ExtensionManager(
    context: Context,
    private val permissions: PermissionManager,
    private val runtimes: RuntimeManager,
) {
    val store = ExtensionStore(context)
    val secrets = ExtensionSecrets(context)

    private fun context(task: PhoneTask, actor: String, config: JSONObject) =
        PermissionContext(
            sessionId = task.sessionId,
            projectId = task.projectId,
            actor = actor,
            extensionId = config.getString("id"),
            declaredCapabilities = ExtensionStore.capabilities(config),
        )

    private suspend fun approve(
        context: PermissionContext,
        capability: Capability,
        resource: String,
        ask: suspend (String, String) -> Boolean,
    ) {
        permissions.authorize(context, capability, resource) {
            ask(it.reason, "${context.actor} · ${context.extensionId}\n$capability · $resource")
        }
    }

    suspend fun mcp(
        task: PhoneTask,
        server: String,
        name: String?,
        arguments: JSONObject,
        ask: suspend (String, String) -> Boolean,
    ): JSONObject {
        val enabled = store.enabled("mcp", task.projectId)
        if (server.isBlank())
            return JSONObject()
                .put(
                    "servers",
                    JSONArray(
                        enabled.map {
                            JSONObject()
                                .put("id", it.getString("id"))
                                .put("name", it.getString("name"))
                                .put(
                                    "scope",
                                    if (it.optString("projectId").isBlank()) "global"
                                    else "project",
                                )
                        },
                    ),
                )
        val config =
            enabled.firstOrNull { it.getString("id") == server } ?: error("MCP 未启用或不属于当前项目")
        val policy = context(task, "mcp", config)
        val resource = if (name == null) "$server/tools/list" else "$server/$name"
        try {
            approve(policy, Capability.MCP_TOOL, resource, ask)
            approve(policy, Capability.NETWORK, config.getString("url"), ask)
            if (name != null && config.optBoolean("alwaysAsk", true)) {
                check(
                    ask(
                        "允许调用 MCP ${config.getString("name")}？",
                        "$name\n${arguments.toString().take(1200)}",
                    ),
                ) {
                    "用户拒绝 MCP 调用"
                }
            }
            // Per-operation instance: server sessions and credentials never cross working domains.
            val client =
                McpClient(config.getString("url"), { secrets.get(server) }) {
                    store.enabled("mcp", task.projectId).any {
                        it.toString() == config.toString()
                    } &&
                        permissions
                            .evaluate(policy, Capability.NETWORK, config.getString("url"))
                            .effect == PermissionEffect.ALLOW &&
                        permissions.evaluate(policy, Capability.MCP_TOOL, resource).effect ==
                            PermissionEffect.ALLOW
                }
            val tools = client.listTools()
            val result =
                if (name == null) JSONObject().put("tools", tools) else client.call(name, arguments)
            store.record(server, name ?: "tools/list", "completed")
            return result.put("serverId", server).put("untrustedExternalContent", true)
        } catch (error: Throwable) {
            store.record(
                server,
                name ?: "tools/list",
                if (error is CancellationException) "cancelled" else "failed",
            )
            throw error
        } finally {
            permissions.finish(policy)
        }
    }

    suspend fun hooks(
        task: PhoneTask,
        event: HookEvent,
        method: String = "",
        arguments: JSONObject = JSONObject(),
        result: JSONObject? = null,
        worktreeId: String = task.id,
        ask: suspend (String, String) -> Boolean,
    ): HookResult {
        var current = JSONObject(arguments.toString())
        val extra = StringBuilder()
        for (config in
            store.enabled("hooks", task.projectId).filter { it.optString("event") == event.name }) {
            // Chat-only is a real execution boundary, including automatic lifecycle hooks.
            if (
                task.access in setOf("none", "read") &&
                    config.optString("action") !in setOf("context", "log")
            )
                continue
            val pattern = config.optString("match", "*")
            val matchValue =
                if (event in setOf(HookEvent.BeforeCommand, HookEvent.AfterCommand))
                    current.optString("command")
                else method
            if (
                !Regex("^" + pattern.split('*').joinToString(".*") { Regex.escape(it) } + "$")
                    .matches(matchValue)
            )
                continue
            val policy = context(task, "hook", config)
            val id = config.getString("id")
            try {
                approve(policy, Capability.HOOK_EXEC, id, ask)
                check(
                    store.enabled("hooks", task.projectId).any {
                        it.optString("revision") == config.optString("revision") &&
                            it.getString("id") == id
                    },
                ) {
                    "Hook 配置已改变，本次执行已取消"
                }
                when (config.optString("action", "log")) {
                    "deny" ->
                        error(
                            "Hook ${config.getString("name")} 拒绝：${config.optString("text").take(300)}",
                        )
                    "context" -> {
                        extra.append(config.optString("text").take(4096)).append('\n')
                        require(extra.length <= 8192)
                    }
                    "modify" -> {
                        val patch = config.getJSONObject("arguments")
                        require(patch.toString().length <= 16384)
                        patch.keys().forEach { key ->
                            require(
                                !key.startsWith('_') &&
                                    key !in setOf("sessionId", "taskId", "projectId"),
                            ) {
                                "Hook 不可改变任务归属"
                            }
                            current.put(key, patch.get(key))
                        }
                    }
                    "command" -> {
                        approve(policy, Capability.SANDBOX_EXEC, config.getString("command"), ask)
                        // Per-path file policy is checked again before import/export inside
                        // RuntimeManager.
                        if (Capability.NETWORK in policy.declaredCapabilities.orEmpty())
                            approve(policy, Capability.NETWORK, "*", ask)
                        val input =
                            JSONObject()
                                .put("event", event.name)
                                .put("tool", method)
                                .put("sessionId", task.sessionId)
                                .put("projectId", task.projectId)
                        if (Capability.WORKSPACE_READ in policy.declaredCapabilities.orEmpty())
                            input.put("arguments", current).put("result", result)
                        val output =
                            runtimes.runHook(
                                task,
                                policy,
                                config.getString("command"),
                                input,
                                worktreeId,
                            )
                        check(output.optInt("exitCode", -1) == 0) {
                            "Hook ${config.getString("name")} 执行失败，exit=${output.optInt("exitCode", -1)}"
                        }
                        if (config.optBoolean("outputAsContext"))
                            extra.append(output.optString("stdout").take(4096)).append('\n')
                    }
                }
                store.record(id, event.name, "completed")
            } catch (error: Throwable) {
                store.record(
                    id,
                    event.name,
                    if (error is CancellationException) "cancelled" else "failed",
                )
                if (
                    error is CancellationException ||
                        config.optString("action") == "deny" ||
                        config.optBoolean("failClosed", true)
                )
                    throw error
            } finally {
                permissions.finish(policy)
            }
        }
        return HookResult(current, extra.toString().take(8192))
    }
}
