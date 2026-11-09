package dev.deepseek.mobile.harness

import android.content.Context
import androidx.javascriptengine.IsolateStartupParameters
import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import dev.deepseek.mobile.PhoneTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

internal class PluginManager(
    private val context: Context,
    private val permissions: PermissionManager,
) {
    val store = PluginStore(context)
    private val engine = Mutex()

    fun catalog(): JSONArray =
        JSONArray(
            store
                .list()
                .filter { it.enabled }
                .map {
                    JSONObject()
                        .put("id", it.bundle.id)
                        .put("name", it.bundle.name)
                        .put("description", it.bundle.description)
                },
        )

    suspend fun manage(
        args: JSONObject,
        task: PhoneTask,
        assertActive: () -> Unit,
        ask: suspend (String, String) -> Unit,
    ): JSONObject {
        val action = args.getString("action")
        if (action == "list_bundles")
            return withContext(Dispatchers.IO) {
                JSONObject().put("bundles", JSONArray(store.list().map { it.info() }))
            }
        require(action in setOf("install_bundle", "set_bundle", "remove_bundle")) { "未知的插件管理操作" }
        check(task.access in setOf("ask", "full")) { "安装或管理插件需要「受限访问」或「完全访问」" }
        val bundle =
            if (action == "install_bundle") PluginBundle.parse(args.getJSONObject("bundle"))
            else null
        val id = bundle?.id ?: args.getString("plugin_id")
        val previous = withContext(Dispatchers.IO) { store.find(id) }
        val target = bundle ?: requireNotNull(previous) { "插件未安装" }.bundle
        val enabled =
            when (action) {
                "install_bundle" -> args.optBoolean("enabled", true)
                "set_bundle" -> args.getBoolean("enabled")
                else -> false
            }
        val label =
            when (action) {
                "install_bundle" -> if (previous == null) "安装" else "更新"
                "set_bundle" -> if (enabled) "启用" else "停用"
                else -> "卸载"
            }
        val detail = buildString {
            append(target.name)
                .append(" ")
                .append(target.version)
                .append(" · ")
                .append(id)
                .append('\n')
            append(target.description).append('\n')
            append(target.tools.joinToString("、") { it.name }).append('\n')
            append("所有工作会话 · 离线 JavaScript")
        }
        val policy = PermissionContext(sessionId = task.sessionId, projectId = task.projectId)
        try {
            var confirmed = false
            permissions.authorize(policy, Capability.PLUGIN_MANAGE, id) {
                ask("$label Agent 插件？", detail)
                confirmed = true
                true
            }
            if (task.access == "ask" && !confirmed) ask("$label Agent 插件？", detail)
            assertActive()
            if (bundle != null) validate(bundle)
            return withContext(Dispatchers.IO) {
                assertActive()
                val rule = permissions.evaluate(policy, Capability.PLUGIN_MANAGE, id)
                check(rule.effect == PermissionEffect.ALLOW) { rule.reason }
                when (action) {
                    "install_bundle" -> store.install(target, enabled, previous?.revision)
                    "set_bundle" -> store.setEnabled(requireNotNull(previous), enabled)
                    else -> store.remove(requireNotNull(previous))
                }
                JSONObject()
                    .put("plugin_id", id)
                    .put("enabled", enabled)
                    .put("applied", true)
                    .put("message", "已${label}插件：${target.name}")
            }
        } finally {
            permissions.finish(policy)
        }
    }

    suspend fun call(
        args: JSONObject,
        sessionId: String,
        projectId: String,
        ask: suspend (String, String) -> Unit,
        expectedRevision: String? = null,
    ): JSONObject {
        val id = args.getString("plugin_id")
        val plugin = withContext(Dispatchers.IO) { store.find(id) }
        check(
            plugin != null &&
                plugin.enabled &&
                (expectedRevision == null || plugin.revision == expectedRevision),
        ) {
            "插件未启用或版本已变化"
        }
        val toolName = args.optString("tool")
        if (toolName.isBlank()) return plugin.info()
        val tool = plugin.bundle.tools.firstOrNull { it.name == toolName } ?: error("插件工具不存在")
        val input = if (args.has("arguments")) args.getJSONObject("arguments") else JSONObject()
        require(input.toString().toByteArray().size <= 32 * 1024) { "插件参数不得超过 32 KB" }
        val policy =
            PermissionContext(
                sessionId = sessionId,
                projectId = projectId,
                actor = "plugin",
                extensionId = id,
            )
        val resource = "$id/$toolName"
        try {
            permissions.authorize(policy, Capability.PLUGIN_EXEC, resource) {
                ask("运行 ${plugin.bundle.name}？", "$resource · ${it.reason}")
                true
            }
            return inEngine {
                check(store.find(id)?.revision == plugin.revision) { "插件已停用或版本已变化" }
                val rule = permissions.evaluate(policy, Capability.PLUGIN_EXEC, resource)
                check(rule.effect == PermissionEffect.ALLOW) { rule.reason }
                val body = JSONObject.quote(tool.code)
                val json = JSONObject.quote(input.toString())
                val output =
                    evaluateJavaScriptAsync(
                            """
                    (() => {
                        const run = new Function("input", $body);
                        const value = run(JSON.parse($json));
                        if (value === undefined || (value && typeof value.then === "function"))
                            throw new Error("插件须同步返回可序列化的 JSON 值");
                        return JSON.stringify({result: value});
                    })()
                    """
                                .trimIndent(),
                        )
                        .await()
                check(store.find(id)?.revision == plugin.revision) { "插件已停用或版本已变化" }
                check(output.toByteArray().size <= 32 * 1024) { "插件输出超过 32 KB" }
                JSONObject(output)
                    .also { check(it.has("result")) { "插件须返回可序列化的 JSON 值" } }
                    .put("plugin_id", id)
                    .put("tool", toolName)
                    .put("untrusted", true)
                    .put("message", "插件工具执行完成")
            }
        } finally {
            permissions.finish(policy)
        }
    }

    private suspend fun validate(bundle: PluginBundle) = inEngine {
        val source =
            bundle.tools.joinToString("\n") {
                "new Function(\"input\", ${JSONObject.quote(it.code)});"
            }
        evaluateJavaScriptAsync("$source\n'ok'").await()
    }

    private suspend fun <T : Any> inEngine(block: suspend JavaScriptIsolate.() -> T): T =
        withContext(Dispatchers.IO) {
            engine.withLock {
                withTimeoutOrNull(8_000) {
                    check(JavaScriptSandbox.isSupported()) {
                        "当前 Android 系统 WebView 不支持插件运行，请更新 WebView"
                    }
                    JavaScriptSandbox.createConnectedInstanceAsync(context).await().use { sandbox ->
                        check(
                            sandbox.isFeatureSupported(
                                JavaScriptSandbox.JS_FEATURE_ISOLATE_TERMINATION,
                            ) &&
                                sandbox.isFeatureSupported(
                                    JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE,
                                ),
                        ) {
                            "当前 Android 系统 WebView 缺少插件隔离能力，请更新 WebView"
                        }
                        val parameters =
                            IsolateStartupParameters().apply {
                                setMaxHeapSizeBytes(16L * 1024 * 1024)
                                if (
                                    sandbox.isFeatureSupported(
                                        JavaScriptSandbox
                                            .JS_FEATURE_EVALUATE_WITHOUT_TRANSACTION_LIMIT,
                                    )
                                )
                                    setMaxEvaluationReturnSizeBytes(32 * 1024)
                            }
                        sandbox.createIsolate(parameters).use { it.block() }
                    }
                } ?: error("插件执行超时")
            }
        }
}
