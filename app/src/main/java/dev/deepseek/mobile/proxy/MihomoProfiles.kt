package dev.deepseek.mobile.proxy

import android.content.Context
import android.util.AtomicFile
import dev.deepseek.mobile.harness.SafeYaml
import dev.deepseek.mobile.harness.boundedBytes
import java.io.File
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal data class ProxyProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val yaml: String,
    val source: String = "",
    val rules: List<String> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(),
    val mode: String = "",
) {
    fun json() =
        JSONObject()
            .put("id", id)
            .put("name", name)
            .put("yaml", yaml)
            .put("source", source)
            .put("rules", JSONArray(rules))
            .put("updatedAt", updatedAt)
            .put("mode", mode)

    companion object {
        fun parse(row: JSONObject): ProxyProfile {
            val rules = row.optJSONArray("rules") ?: JSONArray()
            return ProxyProfile(
                row.getString("id"),
                row.getString("name"),
                row.getString("yaml"),
                row.optString("source"),
                List(rules.length()) { rules.getString(it) },
                row.optLong("updatedAt"),
                row.optString("mode"),
            )
        }
    }
}

internal class MihomoProfiles(context: Context) {
    private val folder = File(context.filesDir, "mihomo/profiles").apply { mkdirs() }

    fun list(): List<ProxyProfile> =
        folder
            .listFiles()
            .orEmpty()
            .filter { it.extension == "json" }
            .mapNotNull {
                runCatching {
                    ProxyProfile.parse(JSONObject(AtomicFile(it).readFully().decodeToString()))
                }
                    .getOrNull()
            }
            .sortedBy { it.name }

    fun read(id: String) =
        ProxyProfile.parse(JSONObject(AtomicFile(file(id)).readFully().decodeToString()))

    fun save(profile: ProxyProfile) {
        require(profile.name.trim().length in 1..80) { "配置名称须为 1 至 80 字符" }
        config(profile)
        val atomic = AtomicFile(file(profile.id))
        val out = atomic.startWrite()
        try {
            out.write(profile.json().toString().toByteArray())
            atomic.finishWrite(out)
        } catch (error: Throwable) {
            atomic.failWrite(out)
            throw error
        }
    }

    fun delete(id: String) = AtomicFile(file(id)).delete()

    private fun file(id: String): File = File(folder, "${UUID.fromString(id)}.json")

    companion object {
        const val maxYamlBytes = 2_000_000

        fun download(source: String): String {
            var url = runCatching { URL(source) }.getOrElse { error("请输入有效的 HTTPS 订阅地址") }
            repeat(5) {
                require(url.protocol == "https" && url.host.isNotBlank() && url.userInfo == null) {
                    "订阅须使用 HTTPS 且地址不可包含用户认证字段"
                }
                val connection = url.openConnection(Proxy.NO_PROXY) as HttpURLConnection
                try {
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 12000
                    connection.readTimeout = 15000
                    connection.setRequestProperty("User-Agent", "clash.meta/1.19.33 DSHA")
                    val code = connection.responseCode
                    if (code in setOf(301, 302, 303, 307, 308)) {
                        url = URL(url, connection.getHeaderField("Location") ?: error("订阅重定向无效"))
                    } else {
                        check(code in 200..299) { "订阅下载失败（HTTP $code）" }
                        return connection.inputStream.use {
                            it.boundedBytes(maxYamlBytes)
                                .decodeToString(throwOnInvalidSequence = true)
                        }
                    }
                } catch (error: java.io.IOException) {
                    throw IllegalStateException("订阅连接失败，请检查网络与地址", error)
                } finally {
                    connection.disconnect()
                }
            }
            error("订阅重定向次数过多")
        }

        fun config(profile: ProxyProfile): MutableMap<String, Any?> {
            require(profile.yaml.toByteArray().size <= maxYamlBytes) { "YAML 配置过大" }
            val input = SafeYaml.mapping(profile.yaml)
            val supported =
                setOf(
                    "proxies",
                    "proxy-groups",
                    "proxy-providers",
                    "rule-providers",
                    "rules",
                    "sub-rules",
                    "dns",
                    "hosts",
                    "sniffer",
                    "ipv6",
                    "mode",
                    "log-level",
                    "geodata-mode",
                    "geox-url",
                    "geosite-matcher",
                    "tcp-concurrent",
                    "unified-delay",
                    "find-process-mode",
                )
            // Do not let profiles configure listeners, external dashboards, controllers or TUN.
            val config = input.filterKeys { it in supported }.toMutableMap()
            var budget = 150_000
            fun validate(value: Any?, depth: Int = 0) {
                require(depth <= 30 && --budget >= 0) { "YAML 嵌套或展开后的内容过大" }
                when (value) {
                    is Map<*, *> ->
                        value.forEach { (key, child) ->
                            require(key is String) { "YAML 字段名必须是文字" }
                            if (
                                key in
                                    setOf(
                                        "path",
                                        "private-key",
                                        "certificate",
                                        "private-key-path",
                                        "ca",
                                        "ca-path",
                                    ) && child is String
                            ) {
                                // Inline keys and PEM are accepted. File paths are confined to the
                                // core directory.
                                require(
                                    !child.startsWith('/') &&
                                        !child.startsWith('~') &&
                                        !child.contains('\\') &&
                                        !child.split('/').contains("..") &&
                                        !child.startsWith("file:", true),
                                ) {
                                    "配置中引用了核心目录之外的文件"
                                }
                            }
                            validate(child, depth + 1)
                        }
                    is List<*> -> value.forEach { validate(it, depth + 1) }
                    is String,
                    is Number,
                    is Boolean,
                    null -> Unit
                    else -> error("YAML 包含不支持的字段类型")
                }
            }
            validate(config)
            val rules = config["rules"] as? List<*> ?: emptyList<Any>()
            require(rules.all { it is String }) { "rules 须为规则列表" }
            if (profile.mode.isNotBlank()) config["mode"] = profile.mode
            config["rules"] = profile.rules + rules.ifEmpty { listOf("MATCH,DIRECT") }
            (config["dns"] as? Map<*, *>)?.let { dns ->
                config["dns"] = dns.filterKeys { it != "listen" }
            }
            for (key in listOf("proxy-providers", "rule-providers")) {
                val providers = config[key] as? Map<*, *> ?: continue
                config[key] =
                    providers.entries.associate { (name, raw) ->
                        val row = (raw as? Map<*, *>)?.toMutableMap() ?: error("Provider 配置无效")
                        require(row["type"] != "file") {
                            "请将本地 Provider 改为 inline 或 HTTPS Provider"
                        }
                        if (row["type"] == "http") {
                            val url = runCatching { URL(row["url"] as String) }.getOrNull()
                            require(url?.protocol == "https" && url.userInfo == null) {
                                "远程 Provider 须使用 HTTPS"
                            }
                            val identity =
                                dev.deepseek.mobile.MemoryStore.hash(name.toString()).take(24)
                            row["path"] = "providers/${profile.id}/$key/$identity"
                        }
                        name to row
                    }
            }
            return config
        }
    }
}
