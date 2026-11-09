package dev.deepseek.mobile.harness

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal enum class HookEvent {
    BeforeAgentStart,
    AfterAgentFinish,
    BeforeToolCall,
    AfterToolCall,
    BeforeFileWrite,
    AfterFileWrite,
    BeforeCommand,
    AfterCommand,
}

/** User-owned configuration, outside every runtime and worktree. No Agent write endpoint. */
internal class ExtensionStore(context: Context) {
    private val root = File(context.filesDir, "harness/extensions").apply { mkdirs() }
    private val lock = Any()
    var failure: String = ""
        private set

    fun list(kind: String, projectId: String? = null): List<JSONObject> =
        synchronized(lock) {
            require(kind in setOf("mcp", "hooks"))
            val file = File(root, "$kind.json")
            if (!file.isFile) return@synchronized emptyList()
            try {
                val values = JSONArray(String(AtomicFile(file).readFully()))
                (0 until values.length())
                    .map { values.getJSONObject(it) }
                    .filter {
                        projectId == null ||
                            it.optString("projectId").let { p -> p.isBlank() || p == projectId }
                    }
            } catch (error: Exception) {
                failure = "扩展配置损坏，扩展已关闭：${error.message}"
                emptyList()
            }
        }

    fun saveFromUser(kind: String, value: JSONObject, approved: Boolean = false) =
        synchronized(lock) {
            val item = JSONObject(value.toString())
            val id = item.optString("id").ifBlank { UUID.randomUUID().toString() }
            UUID.fromString(id)
            val project = item.optString("projectId")
            if (project.isNotBlank()) UUID.fromString(project)
            require(item.getString("name").length in 1..80)
            val caps = capabilities(item)
            require(Capability.INSTRUCTIONS_WRITE !in caps && Capability.GLOBAL_TOOLCHAIN !in caps)
            if (kind == "mcp") {
                McpClient.validateEndpoint(item.getString("url"))
                require(Capability.NETWORK in caps && Capability.MCP_TOOL in caps)
                require(caps.all { it in setOf(Capability.NETWORK, Capability.MCP_TOOL) }) {
                    "HTTPS MCP 不提供本机文件、Shell、Root 或 Android 回调；这些能力保持禁止"
                }
            } else {
                HookEvent.valueOf(item.getString("event"))
                require(
                    item.optString("action", "log") in
                        setOf("log", "context", "deny", "modify", "command"),
                )
                require(Capability.HOOK_EXEC in caps)
                if (item.optString("action") == "command") {
                    require(Capability.SANDBOX_EXEC in caps)
                    require(item.getString("command").length in 1..16384)
                }
                require(
                    caps.none {
                        it in
                            setOf(
                                Capability.APP_EXEC,
                                Capability.SHELL_EXEC,
                                Capability.ROOT_EXEC,
                                Capability.ANDROID_API,
                                Capability.EXTERNAL_STORAGE,
                            )
                    },
                ) {
                    "Hook 仅能使用独立 Sandbox，不能请求手机提权能力"
                }
                if (item.optString("action") == "modify")
                    require(
                        item.getString("event") in
                            setOf("BeforeToolCall", "BeforeCommand", "BeforeFileWrite"),
                    )
            }
            item
                .put("id", id)
                .put("revision", UUID.randomUUID().toString())
                .put("enabled", approved && item.optBoolean("enabled"))
            item.put(
                "approvedCapabilities",
                if (approved) JSONArray(caps.map { it.name }) else JSONArray(),
            )
            val all = list(kind).filterNot { it.getString("id") == id } + item
            require(all.size <= 64)
            write(File(root, "$kind.json"), JSONArray(all).toString())
            failure = ""
        }

    fun deleteFromUser(kind: String, id: String) =
        synchronized(lock) {
            UUID.fromString(id)
            write(
                File(root, "$kind.json"),
                JSONArray(list(kind).filterNot { it.getString("id") == id }).toString(),
            )
        }

    fun enabled(kind: String, projectId: String): List<JSONObject> =
        list(kind, projectId).filter {
            it.optBoolean("enabled") &&
                capabilities(it).all { cap -> cap in capabilities(it, "approvedCapabilities") }
        }

    fun record(id: String, event: String, status: String, detail: String = "") =
        synchronized(lock) {
            // Do not persist arguments, authentication headers, command output or retrieved
            // secrets.
            val entries = audit().let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            write(
                File(root, "audit.json"),
                JSONArray(
                        (entries +
                                JSONObject()
                                    .put("extensionId", id)
                                    .put("event", event)
                                    .put("status", status)
                                    .put("detail", detail.take(160))
                                    .put("at", System.currentTimeMillis()))
                            .takeLast(100),
                    )
                    .toString(),
            )
        }

    fun audit(): JSONArray =
        synchronized(lock) {
            runCatching { JSONArray(String(AtomicFile(File(root, "audit.json")).readFully())) }
                .getOrDefault(JSONArray())
        }

    companion object {
        fun capabilities(item: JSONObject, key: String = "capabilities"): Set<Capability> {
            val values = item.optJSONArray(key) ?: JSONArray()
            return (0 until values.length())
                .map { Capability.valueOf(values.getString(it)) }
                .toSet()
        }

        private fun write(file: File, text: String) {
            require(text.toByteArray().size <= 524288)
            val atomic = AtomicFile(file)
            val out = atomic.startWrite()
            try {
                out.write(text.toByteArray())
                atomic.finishWrite(out)
            } catch (error: Throwable) {
                atomic.failWrite(out)
                throw error
            }
        }
    }
}
