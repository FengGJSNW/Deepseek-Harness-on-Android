package dev.deepseek.mobile.harness

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

internal data class PluginTool(val name: String, val description: String, val code: String) {
    fun info() = JSONObject().put("name", name).put("description", description)
}

internal data class PluginBundle(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val tools: List<PluginTool>,
) {
    fun json(includeCode: Boolean = false) =
        JSONObject()
            .put("id", id)
            .put("name", name)
            .put("version", version)
            .put("description", description)
            .put(
                "tools",
                JSONArray(
                    tools.map { tool ->
                        tool.info().apply { if (includeCode) put("code", tool.code) }
                    },
                ),
            )

    companion object {
        private val identifier = Regex("[a-z][a-z0-9_-]{1,39}")
        private val versionPattern = Regex("[0-9]+[.][0-9]+[.][0-9]+(?:[-+][a-zA-Z0-9.-]+)?")

        fun parse(value: JSONObject): PluginBundle {
            require(value.toString().toByteArray().size <= 96 * 1024) { "插件包不得超过 96 KB" }
            val id = field(value, "id", 40)
            val version = field(value, "version", 32)
            require(identifier.matches(id)) { "插件 ID 须为 2～40 位小写字母、数字、横线或下划线，以字母开头" }
            require(versionPattern.matches(version)) { "版本请使用 1.0.0 格式" }
            val entries = value.getJSONArray("tools")
            require(entries.length() in 1..6) { "每个插件包须有 1～6 个工具" }
            val tools =
                (0 until entries.length()).map { index ->
                    val entry = entries.getJSONObject(index)
                    val name = field(entry, "name", 40)
                    require(identifier.matches(name)) { "工具名称格式无效：$name" }
                    PluginTool(
                        name,
                        field(entry, "description", 240),
                        field(entry, "code", 24 * 1024),
                    )
                }
            require(tools.map { it.name }.distinct().size == tools.size) { "工具名称重复" }
            return PluginBundle(
                id,
                field(value, "name", 64),
                version,
                field(value, "description", 240),
                tools,
            )
        }

        private fun field(json: JSONObject, key: String, max: Int): String {
            val value = (json.opt(key) as? String).orEmpty().trim()
            require(value.isNotEmpty() && value.length <= max && '\u0000' !in value) {
                "插件字段 $key 无效"
            }
            return value
        }
    }
}

internal data class InstalledPlugin(
    val bundle: PluginBundle,
    val enabled: Boolean,
    val revision: String = UUID.randomUUID().toString(),
) {
    fun info() = bundle.json().put("enabled", enabled)
}

/** The inventory is committed atomically. Expected revisions bind approval to a specific change. */
internal class PluginStore(context: Context) {
    private val root = File(context.filesDir, "harness/plugins")
    private val file = AtomicFile(File(root, "installed.json"))
    private val changes = MutableStateFlow(0L)
    val revision = changes.asStateFlow()
    private var loaded: List<InstalledPlugin>? = null

    @Synchronized fun list(): List<InstalledPlugin> = loaded ?: read().also { loaded = it }

    fun find(id: String) = list().firstOrNull { it.bundle.id == id }

    @Synchronized
    fun install(bundle: PluginBundle, enabled: Boolean, expected: String?): InstalledPlugin {
        val installed = InstalledPlugin(bundle, enabled)
        update(bundle.id, expected, installed)
        return installed
    }

    @Synchronized
    fun setEnabled(plugin: InstalledPlugin, enabled: Boolean) {
        update(
            plugin.bundle.id,
            plugin.revision,
            plugin.copy(enabled = enabled, revision = UUID.randomUUID().toString()),
        )
    }

    @Synchronized
    fun remove(plugin: InstalledPlugin) = update(plugin.bundle.id, plugin.revision, null)

    private fun update(id: String, expected: String?, replacement: InstalledPlugin?) {
        check(find(id)?.revision == expected) { "插件已被修改，请重新查看后再操作" }
        val next = list().filterNot { it.bundle.id == id } + listOfNotNull(replacement)
        require(next.size <= 24) { "最多可安装 24 个插件" }
        val bytes =
            JSONArray(
                    next.map {
                        JSONObject()
                            .put("bundle", it.bundle.json(includeCode = true))
                            .put("enabled", it.enabled)
                            .put("revision", it.revision)
                    },
                )
                .toString()
                .toByteArray(Charsets.UTF_8)
        root.mkdirs()
        val out = file.startWrite()
        try {
            out.write(bytes)
            file.finishWrite(out)
        } catch (error: Throwable) {
            file.failWrite(out)
            throw error
        }
        loaded = next
        changes.value++
    }

    private fun read(): List<InstalledPlugin> {
        val bytes =
            try {
                file.readFully()
            } catch (_: java.io.FileNotFoundException) {
                return emptyList()
            }
        return try {
            val entries = JSONArray(String(bytes, Charsets.UTF_8))
            require(entries.length() <= 24)
            (0 until entries.length())
                .map { index ->
                    val entry = entries.getJSONObject(index)
                    InstalledPlugin(
                        PluginBundle.parse(entry.getJSONObject("bundle")),
                        entry.getBoolean("enabled"),
                        entry.getString("revision"),
                    )
                }
                .also { plugins ->
                    require(plugins.map { it.bundle.id }.distinct().size == plugins.size)
                }
        } catch (error: Exception) {
            throw IllegalStateException("插件清单无法读取", error)
        }
    }
}
