package dev.deepseek.mobile.harness

import android.content.Context
import android.util.AtomicFile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal enum class PermissionEffect {
    ALLOW,
    ASK,
    DENY,
}

internal enum class Capability {
    WORKSPACE_READ,
    WORKSPACE_WRITE,
    NETWORK,
    SANDBOX_EXEC,
    APP_EXEC,
    SHELL_EXEC,
    ROOT_EXEC,
    ANDROID_API,
    EXTERNAL_STORAGE,
    GLOBAL_TOOLCHAIN,
    MEMORY_READ,
    MEMORY_WRITE,
    INSTRUCTIONS_READ,
    INSTRUCTIONS_WRITE,
    CROSS_CONTEXT,
    MCP_TOOL,
    HOOK_EXEC,
    SKILL_READ,
    PROXY_CONFIG,
    PLUGIN_MANAGE,
    PLUGIN_EXEC,
}

internal enum class PermissionScope {
    DEFAULT,
    GLOBAL,
    PROJECT,
    SESSION,
    OPERATION,
}

internal data class PermissionContext(
    val operationId: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val projectId: String = "",
    val actor: String = "agent",
    val environment: String = "sandbox",
    val extensionId: String = "",
    val declaredCapabilities: Set<Capability>? = null,
) {
    init {
        require(actor in setOf("agent", "user", "mcp", "hook", "package-manager", "plugin"))
    }
}

internal data class PermissionRule(
    val id: String = UUID.randomUUID().toString(),
    val scope: PermissionScope,
    val owner: String = "",
    val capability: Capability,
    val resource: String = "*",
    val effect: PermissionEffect,
    val reason: String = "用户规则",
    val actor: String = "*",
    val extensionId: String = "*",
) {
    fun json() =
        JSONObject()
            .put("id", id)
            .put("scope", scope.name)
            .put("owner", owner)
            .put("capability", capability.name)
            .put("resource", resource)
            .put("effect", effect.name)
            .put("reason", reason)
            .put("actor", actor)
            .put("extensionId", extensionId)

    companion object {
        fun parse(j: JSONObject) =
            PermissionRule(
                j.getString("id"),
                PermissionScope.valueOf(j.getString("scope")),
                j.optString("owner"),
                Capability.valueOf(j.getString("capability")),
                j.optString("resource", "*"),
                PermissionEffect.valueOf(j.getString("effect")),
                j.optString("reason"),
                j.optString("actor", "*"),
                j.optString("extensionId", "*"),
            )
    }
}

internal data class PermissionResult(
    val effect: PermissionEffect,
    val reason: String,
    val rules: List<String> = emptyList(),
) {
    fun json() =
        JSONObject()
            .put("decision", effect.name)
            .put("reason", reason)
            .put("rules", JSONArray(rules))
}

/** One policy evaluator shared by terminal, memory, runtime network, MCP and hooks. */
internal class PermissionManager(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "harness/permissions.json"))
    private val lock = Any()
    var revision by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    @Volatile private var rules = emptyList<PermissionRule>()
    @Volatile
    var loadFailure: String = ""
        private set

    private data class Grant(
        val capability: Capability,
        val resource: String,
        val actor: String,
        val extensionId: String,
    )

    private val once = java.util.concurrent.ConcurrentHashMap<String, Set<Grant>>()

    init {
        file.baseFile.parentFile!!.mkdirs()
        if (file.baseFile.exists()) {
            // Corruption is visible and fail closed. Never replace a broken DENY policy with
            // defaults.
            runCatching { parse(String(file.readFully())) }
                .onSuccess { rules = it }
                .onFailure { loadFailure = "权限规则损坏，工具已关闭：${it.message}" }
        }
    }

    fun export() = JSONArray(rules.map { it.json() }).toString(2)

    /**
     * Fine file constraints require typed file operations, not arbitrary program preflight. Narrow
     * VM network restrictions conservatively close the entire shared VM network.
     */
    fun requiresBroker(context: PermissionContext, capabilities: Set<Capability>): Boolean {
        if (loadFailure.isNotBlank()) return true
        fun applies(rule: PermissionRule): Boolean =
            (rule.actor == "*" || rule.actor == context.actor) &&
                (rule.extensionId == "*" || rule.extensionId == context.extensionId) &&
                when (rule.scope) {
                    PermissionScope.DEFAULT,
                    PermissionScope.GLOBAL -> true
                    PermissionScope.PROJECT ->
                        context.projectId.isNotBlank() && rule.owner == context.projectId
                    PermissionScope.SESSION -> rule.owner == context.sessionId
                    PermissionScope.OPERATION -> rule.owner == context.operationId
                }
        val applicable = rules.filter { it.capability in capabilities && applies(it) }
        return applicable.any { rule ->
            when (rule.effect) {
                PermissionEffect.ALLOW -> false
                PermissionEffect.DENY -> true
                PermissionEffect.ASK ->
                    if ('*' !in rule.resource)
                        evaluate(context, rule.capability, rule.resource).effect !=
                            PermissionEffect.ALLOW
                    else
                        applicable.none { allow ->
                            allow.capability == rule.capability &&
                                allow.effect == PermissionEffect.ALLOW &&
                                allow.scope.ordinal > rule.scope.ordinal &&
                                (allow.resource == "*" || allow.resource == rule.resource)
                        }
            }
        }
    }

    private fun parse(raw: String): List<PermissionRule> {
        require(raw.length <= 262144)
        val values = JSONArray(raw)
        require(values.length() <= 1000)
        return (0 until values.length())
            .map { PermissionRule.parse(values.getJSONObject(it)) }
            .also { list ->
                require(list.map { it.id }.distinct().size == list.size)
                list.forEach {
                    require(it.resource.length in 1..2048 && !it.resource.contains('\u0000'))
                    require(
                        it.scope in setOf(PermissionScope.GLOBAL, PermissionScope.DEFAULT) ||
                            it.owner.isNotBlank(),
                    )
                }
            }
    }

    /** Settings-only API. The Agent's runtime never receives this file or a config-write tool. */
    fun saveFromUser(raw: String) =
        synchronized(lock) {
            val updated = parse(raw)
            val out = file.startWrite()
            try {
                out.write(JSONArray(updated.map { it.json() }).toString().toByteArray())
                file.finishWrite(out)
                rules = updated
                loadFailure = ""
                once.clear()
                revision++
            } catch (error: Throwable) {
                file.failWrite(out)
                throw error
            }
        }

    fun evaluate(
        context: PermissionContext,
        capability: Capability,
        resource: String = "*",
    ): PermissionResult {
        require(resource.length <= 4096 && !resource.contains('\u0000'))
        if (loadFailure.isNotBlank()) return PermissionResult(PermissionEffect.DENY, loadFailure)
        if (context.declaredCapabilities != null && capability !in context.declaredCapabilities)
            return PermissionResult(PermissionEffect.DENY, "扩展未声明 $capability 能力")
        if (capability == Capability.INSTRUCTIONS_WRITE && context.actor != "user")
            return PermissionResult(PermissionEffect.DENY, "Agent、MCP 与 Hook 不得修改工作指令")
        val matches = rules.filter { r ->
            r.capability == capability &&
                (r.actor == "*" || r.actor == context.actor) &&
                (r.extensionId == "*" || r.extensionId == context.extensionId) &&
                glob(r.resource, resource) &&
                when (r.scope) {
                    PermissionScope.DEFAULT,
                    PermissionScope.GLOBAL -> true
                    PermissionScope.PROJECT ->
                        context.projectId.isNotBlank() && r.owner == context.projectId
                    PermissionScope.SESSION -> r.owner == context.sessionId
                    PermissionScope.OPERATION -> r.owner == context.operationId
                }
        }
        // Explicit security denials remain effective across narrower scopes and single-use grants.
        val denied = matches.filter { it.effect == PermissionEffect.DENY }
        if (denied.isNotEmpty())
            return PermissionResult(
                PermissionEffect.DENY,
                denied.joinToString("；") { it.reason },
                denied.map { it.id },
            )
        if (
            Grant(capability, resource, context.actor, context.extensionId) in
                once[context.operationId].orEmpty()
        )
            return PermissionResult(PermissionEffect.ALLOW, "用户仅批准本次操作")
        val rank = matches.maxOfOrNull { it.scope.ordinal }
        val applicable = matches.filter { it.scope.ordinal == rank }
        if (applicable.isNotEmpty()) {
            val strongest = applicable.maxBy { it.effect.ordinal }
            return PermissionResult(strongest.effect, strongest.reason, applicable.map { it.id })
        }
        val elevated =
            context.environment == "sandbox" &&
                capability in
                    setOf(
                        Capability.APP_EXEC,
                        Capability.SHELL_EXEC,
                        Capability.ROOT_EXEC,
                        Capability.GLOBAL_TOOLCHAIN,
                        Capability.CROSS_CONTEXT,
                    )
        val globalMemory =
            capability == Capability.MEMORY_WRITE && resource in setOf("global", "persona")
        return PermissionResult(
            if (elevated || globalMemory) PermissionEffect.ASK else PermissionEffect.ALLOW,
            if (elevated || globalMemory) "此操作需要单独授权" else "默认允许当前工作域内的操作",
        )
    }

    suspend fun authorize(
        context: PermissionContext,
        capability: Capability,
        resource: String = "*",
        approval: suspend (PermissionResult) -> Boolean,
    ): PermissionResult {
        val result = evaluate(context, capability, resource)
        if (result.effect == PermissionEffect.DENY) error("操作被禁止：${result.reason}")
        if (result.effect == PermissionEffect.ASK) {
            check(approval(result)) { "用户拒绝本次操作" }
            // Re-check DENY after the human wait; settings may have changed in the meantime.
            check(evaluate(context, capability, resource).effect != PermissionEffect.DENY)
            once.compute(context.operationId) { _, existing ->
                existing.orEmpty() + Grant(capability, resource, context.actor, context.extensionId)
            }
        }
        return result.copy(effect = PermissionEffect.ALLOW)
    }

    fun finish(context: PermissionContext) {
        once.remove(context.operationId)
    }

    companion object {
        private fun glob(pattern: String, value: String): Boolean {
            val expression = pattern.split('*').joinToString(".*") { Regex.escape(it) }
            return Regex("^$expression$").matches(value)
        }
    }
}
