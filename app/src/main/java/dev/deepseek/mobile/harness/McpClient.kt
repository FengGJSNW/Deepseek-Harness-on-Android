package dev.deepseek.mobile.harness

import android.util.Base64
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

internal class McpFailure(val http: Int, val error: JSONObject?) :
    IllegalStateException(
        "MCP HTTP $http / ${error?.optInt("code") ?: "transport"}: ${error?.optString("message")?.take(200).orEmpty()}",
    )

/** Bounded HTTPS Streamable HTTP, modern metadata and legacy initialization eras. */
internal class McpClient(
    private val endpoint: String,
    private val token: () -> String = { "" },
    private val allowed: () -> Boolean = { true },
) {
    private val mutex = Mutex()
    private var version = MODERN
    private var detected = false
    private var session = ""
    private var tools = emptyMap<String, JSONObject>()

    suspend fun listTools(): JSONArray = mutex.withLock {
        val result = mutableListOf<JSONObject>()
        var cursor = ""
        val seen = mutableSetOf<String>()
        do {
            val params = JSONObject().also { if (cursor.isNotEmpty()) it.put("cursor", cursor) }
            val page = requestCompatible("tools/list", params)
            val values = page.optJSONArray("tools") ?: JSONArray()
            for (i in 0 until values.length()) {
                val tool = values.getJSONObject(i)
                if (
                    runCatching {
                        parameterHeaders(tool.getJSONObject("inputSchema"), JSONObject())
                    }
                        .isSuccess
                )
                    result += tool
            }
            require(result.size <= 256) { "MCP 工具数量超出限制" }
            cursor = page.optString("nextCursor")
            if (cursor.isNotEmpty()) check(seen.add(cursor)) { "MCP 分页游标重复" }
        } while (cursor.isNotEmpty())
        require(result.map { it.getString("name") }.distinct().size == result.size) {
            "MCP 返回重复工具名称"
        }
        tools = result.associateBy { it.getString("name") }
        JSONArray(result)
    }

    suspend fun call(name: String, arguments: JSONObject): JSONObject = mutex.withLock {
        val tool = tools[name] ?: error("请先 mcp_list 获取工具定义")
        require(arguments.toString().toByteArray().size <= 262144)
        requestCompatible(
            "tools/call",
            JSONObject().put("name", name).put("arguments", arguments),
            parameterHeaders(tool.getJSONObject("inputSchema"), arguments),
        )
    }

    private suspend fun requestCompatible(
        method: String,
        params: JSONObject,
        headers: Map<String, String> = emptyMap(),
    ): JSONObject {
        try {
            return post(method, params, headers).also { detected = true }
        } catch (failure: McpFailure) {
            val code = failure.error?.optInt("code")
            if (!detected && code == -32022) {
                val supported =
                    failure.error?.optJSONObject("data")?.optJSONArray("supported") ?: JSONArray()
                val values = (0 until supported.length()).map { supported.getString(it) }
                check(MODERN in values || LEGACY in values) { "MCP 没有共同支持的协议版本" }
                if (MODERN !in values) initialize()
                detected = true
                return post(method, params, headers)
            }
            // Authentication, modern protocol and policy failures never trigger a downgrade.
            if (
                !detected &&
                    failure.http in setOf(400, 404, 405) &&
                    code !in setOf(-32020, -32021, -32022)
            ) {
                initialize()
                detected = true
                return post(method, params, headers)
            }
            // A legacy session may expire. Reinitialize once; never retry an ambiguous tool call.
            if (version != MODERN && failure.http == 404 && method == "tools/list") {
                session = ""
                initialize()
                return post(method, params, headers)
            }
            throw failure
        }
    }

    private suspend fun initialize() {
        version = LEGACY
        val result =
            post(
                "initialize",
                JSONObject()
                    .put("protocolVersion", LEGACY)
                    .put("capabilities", JSONObject())
                    .put("clientInfo", clientInfo()),
            )
        val selected = result.getString("protocolVersion")
        check(selected in setOf(LEGACY, "2025-06-18", "2025-03-26")) { "不支持服务端协商的 MCP 版本" }
        version = selected
        post("notifications/initialized", JSONObject(), notification = true)
    }

    private suspend fun post(
        method: String,
        raw: JSONObject,
        extraHeaders: Map<String, String> = emptyMap(),
        notification: Boolean = false,
    ): JSONObject {
        val id = UUID.randomUUID().toString()
        val params = JSONObject(raw.toString())
        if (version == MODERN)
            params.put(
                "_meta",
                JSONObject()
                    .put("io.modelcontextprotocol/protocolVersion", MODERN)
                    .put("io.modelcontextprotocol/clientInfo", clientInfo())
                    .put("io.modelcontextprotocol/clientCapabilities", JSONObject()),
            )
        val message = JSONObject().put("jsonrpc", "2.0").put("method", method).put("params", params)
        if (!notification) message.put("id", id)
        val requestToken = token()
        fun redact(value: JSONObject): JSONObject =
            if (requestToken.isBlank()) value
            else JSONObject(value.toString().replace(requestToken, "[扩展密钥已隐藏]"))
        val requestVersion = version
        val requestSession = session
        val result =
            suspendCancellableCoroutine<Pair<JSONObject, String>> { continuation ->
                val connection = AtomicReference<HttpURLConnection?>()
                val future = executor.submit {
                    try {
                        check(allowed()) { "网络权限未获允许" }
                        val conn =
                            validateEndpoint(endpoint).toURL().openConnection() as HttpURLConnection
                        connection.set(conn)
                        if (!continuation.isActive) {
                            conn.disconnect()
                            return@submit
                        }
                        try {
                            conn.instanceFollowRedirects = false
                            conn.connectTimeout = 20000
                            conn.readTimeout = 90000
                            conn.requestMethod = "POST"
                            conn.doOutput = true
                            conn.setRequestProperty(
                                "Content-Type",
                                "application/json; charset=utf-8",
                            )
                            conn.setRequestProperty("Accept", "application/json, text/event-stream")
                            conn.setRequestProperty("MCP-Protocol-Version", requestVersion)
                            conn.setRequestProperty("Mcp-Method", method)
                            if (params.has("name"))
                                conn.setRequestProperty(
                                    "Mcp-Name",
                                    headerValue(params.getString("name")),
                                )
                            if (requestVersion != MODERN && requestSession.isNotEmpty())
                                conn.setRequestProperty("Mcp-Session-Id", requestSession)
                            extraHeaders.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                            requestToken
                                .takeIf { it.isNotBlank() }
                                ?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
                            conn.outputStream.use { it.write(message.toString().toByteArray()) }
                            val status = conn.responseCode
                            val reader =
                                (if (status in 200..299) conn.inputStream else conn.errorStream)
                                    ?.bufferedReader()
                            val value =
                                reader?.use {
                                    if (
                                        status in 200..299 &&
                                            conn.contentType
                                                .orEmpty()
                                                .startsWith("text/event-stream")
                                    )
                                        readSse(it, id, allowed)
                                    else {
                                        val text = readBounded(it)
                                        if (text.isBlank()) JSONObject()
                                        else if (status in 200..299) parseMessage(text)
                                        else
                                            runCatching { parseMessage(text) }
                                                .getOrDefault(JSONObject())
                                    }
                                } ?: JSONObject()
                            check(allowed()) { "网络规则已改变，结果未交给扩展" }
                            if (status !in 200..299 || value.has("error"))
                                throw McpFailure(
                                    status,
                                    value.optJSONObject("error")?.let { redact(it) },
                                )
                            if (!notification) {
                                check(value.optString("id") == id) { "MCP 响应 ID 不匹配" }
                                check(value.optString("jsonrpc") == "2.0")
                                check(value.optJSONObject("result")?.has("inputRequests") != true) {
                                    "此服务器要求未启用的 sampling/elicitation/roots 能力"
                                }
                            }
                            val newSession = conn.getHeaderField("Mcp-Session-Id").orEmpty()
                            require(
                                newSession.length <= 512 &&
                                    newSession.all { it.code in 0x21..0x7e },
                            )
                            if (continuation.isActive)
                                continuation.resume(
                                    redact(value.optJSONObject("result") ?: JSONObject()) to
                                        newSession,
                                )
                        } finally {
                            conn.disconnect()
                        }
                    } catch (error: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
                continuation.invokeOnCancellation {
                    connection.get()?.disconnect()
                    future.cancel(true)
                }
            }
        if (requestVersion != MODERN && result.second.isNotEmpty()) session = result.second
        return result.first
    }

    companion object {
        const val MODERN = "2026-07-28"
        const val LEGACY = "2025-11-25"
        private val executor = Executors.newFixedThreadPool(3)

        private fun clientInfo() = JSONObject().put("name", "DSHA").put("version", "0.8.0")

        fun validateEndpoint(value: String): URI =
            URI(value).also {
                require(
                    value.length <= 2048 &&
                        it.scheme == "https" &&
                        !it.host.isNullOrBlank() &&
                        it.userInfo == null &&
                        it.fragment == null,
                ) {
                    "MCP 需要不含内嵌密码的 HTTPS 地址"
                }
            }

        fun headerValue(value: String): String =
            if (
                value.isNotEmpty() &&
                    value.trim() == value &&
                    value.all { it.code in 32..126 } &&
                    !(value.startsWith("=?base64?") && value.endsWith("?="))
            )
                value
            else "=?base64?${Base64.encodeToString(value.toByteArray(), Base64.NO_WRAP)}?="

        fun parameterHeaders(schema: JSONObject, arguments: JSONObject): Map<String, String> {
            val result = linkedMapOf<String, String>()
            val names = mutableSetOf<String>()
            fun visit(node: Any?, instance: Any?, reachable: Boolean, depth: Int) {
                require(depth <= 40)
                if (node is JSONArray) {
                    for (i in 0 until node.length()) visit(node.get(i), null, false, depth + 1)
                    return
                }
                if (node !is JSONObject) return
                if (node.has("x-mcp-header")) {
                    val name = node.getString("x-mcp-header")
                    require(
                        reachable &&
                            name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,80}")) &&
                            names.add(name.lowercase()),
                    )
                    val type = node.optString("type")
                    require(type in setOf("string", "integer", "boolean"))
                    if (instance != null && instance != JSONObject.NULL) {
                        require(
                            when (type) {
                                "string" -> instance is String
                                "boolean" -> instance is Boolean
                                else ->
                                    instance is Number &&
                                        instance.toDouble() % 1.0 == 0.0 &&
                                        kotlin.math.abs(instance.toDouble()) <= 9007199254740991.0
                            },
                        )
                        result["Mcp-Param-$name"] = headerValue(instance.toString())
                    }
                }
                node.keys().forEach { key ->
                    if (key == "properties" && node.optJSONObject(key) != null) {
                        val properties = node.getJSONObject(key)
                        properties.keys().forEach { p ->
                            visit(
                                properties.get(p),
                                (instance as? JSONObject)?.opt(p),
                                reachable,
                                depth + 1,
                            )
                        }
                    } else if (key != "x-mcp-header") visit(node.opt(key), null, false, depth + 1)
                }
            }
            visit(schema, arguments, true, 0)
            return result
        }

        fun parseMessage(raw: String): JSONObject {
            require(raw.length <= 1048576)
            var depth = 0
            var quoted = false
            var escaped = false
            for (c in raw) {
                if (quoted) {
                    if (escaped) escaped = false
                    else if (c == '\\') escaped = true else if (c == '"') quoted = false
                } else
                    when (c) {
                        '"' -> quoted = true
                        '{',
                        '[' -> {
                            depth++
                            require(depth <= 64) { "MCP JSON 嵌套过深" }
                        }
                        '}',
                        ']' -> {
                            depth--
                            require(depth >= 0)
                        }
                    }
            }
            require(depth == 0 && !quoted)
            return JSONObject(raw)
        }

        private fun readBounded(reader: BufferedReader): String {
            val out = StringBuilder()
            val buffer = CharArray(4096)
            while (true) {
                val n = reader.read(buffer)
                if (n < 0) break
                out.append(buffer, 0, n)
                require(out.length <= 1048576) { "MCP 响应过大" }
            }
            return out.toString()
        }

        private fun boundedLine(reader: BufferedReader): String? {
            val out = StringBuilder()
            while (true) {
                val c = reader.read()
                if (c == -1) return if (out.isEmpty()) null else out.toString().removeSuffix("\r")
                if (c == 10) return out.toString().removeSuffix("\r")
                out.append(c.toChar())
                require(out.length <= 65536) { "MCP SSE 单行过长" }
            }
        }

        fun readSse(
            reader: BufferedReader,
            id: String,
            allowed: () -> Boolean = { true },
        ): JSONObject {
            val data = StringBuilder()
            var total = 0
            while (true) {
                check(allowed()) { "网络权限已关闭" }
                val line = boundedLine(reader) ?: break
                total += line.length
                require(total <= 1048576) { "MCP 响应过大" }
                if (line.isEmpty()) {
                    if (data.isNotEmpty()) {
                        val value = parseMessage(data.toString())
                        data.clear()
                        check(!(value.has("method") && value.has("id"))) { "MCP 服务端请求未获支持，不会代为执行" }
                        if (
                            value.optString("id") == id &&
                                (value.has("result") || value.has("error"))
                        )
                            return value
                    }
                } else if (line.startsWith("data:")) {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.substring(5).removePrefix(" "))
                }
            }
            if (data.isNotEmpty())
                parseMessage(data.toString()).let {
                    if (it.optString("id") == id && (it.has("result") || it.has("error"))) return it
                }
            error("MCP SSE 未收到匹配响应")
        }
    }
}
