package dev.deepseek.mobile

import java.io.BufferedReader
import java.util.TreeMap
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/** Assemble real SSE deltas, including tool arguments fragmented across network frames. */
internal class CompletionStream {
    val text = StringBuilder()
    val reasoning = StringBuilder()
    private val tools = TreeMap<Int, JSONObject>()
    private var usage: JSONObject? = null
    private var finish = ""

    fun accept(value: JSONObject) {
        check(value.isNull("error")) { "DeepSeek 流式响应中断，请重试" }
        value.optJSONObject("usage")?.let { usage = it }
        val choices = value.optJSONArray("choices") ?: return
        if (choices.length() == 0) return
        val choice = choices.getJSONObject(0)
        if (!choice.isNull("finish_reason")) finish = choice.optString("finish_reason")
        val delta = choice.optJSONObject("delta") ?: return
        if (!delta.isNull("content")) text.append(delta.optString("content"))
        if (!delta.isNull("reasoning_content"))
            reasoning.append(delta.optString("reasoning_content"))
        val calls = delta.optJSONArray("tool_calls") ?: return
        for (i in 0 until calls.length()) {
            val call = calls.getJSONObject(i)
            val current =
                tools.getOrPut(call.optInt("index")) {
                    JSONObject()
                        .put("type", "function")
                        .put("function", JSONObject().put("name", "").put("arguments", ""))
                }
            if (!call.isNull("id")) {
                val previous = current.optString("id")
                val id = call.getString("id")
                if (id != previous) current.put("id", previous + id)
            }
            call.optJSONObject("function")?.let { f ->
                val target = current.getJSONObject("function")
                for (key in listOf("name", "arguments")) if (!f.isNull(key))
                    target.put(key, target.optString(key) + f.getString(key))
            }
        }
    }

    fun result(): JSONObject {
        check(finish.isNotBlank()) { "DeepSeek 连接提前结束，已收到的内容保留在会话中，请重试" }
        val message =
            JSONObject()
                .put("role", "assistant")
                .put("content", text.toString())
                .put("reasoning_content", reasoning.toString())
        if (tools.isNotEmpty()) message.put("tool_calls", JSONArray(tools.values.toList()))
        return JSONObject()
            .put(
                "choices",
                JSONArray().put(JSONObject().put("message", message).put("finish_reason", finish)),
            )
            .put("usage", usage ?: JSONObject.NULL)
    }
}

private const val MAX_COMPLETION_PAYLOAD = 8 * 1024 * 1024

/** Decode the response we actually received; format fallback never replays a completion POST. */
internal suspend fun readCompletion(
    reader: BufferedReader,
    checkActive: () -> Unit,
    onDelta: suspend (CompletionStream, Boolean) -> Unit,
): JSONObject {
    val stream = CompletionStream()
    var detected = false
    var firstLine = true
    val data = StringBuilder()

    suspend fun dispatch(): Boolean {
        val payload = data.toString().trim()
        data.setLength(0)
        if (payload == "[DONE]") return false
        if (payload.isNotEmpty()) {
            stream.accept(completionObject(payload))
            onDelta(stream, false)
        }
        return true
    }

    while (true) {
        checkActive()
        var line = reader.readLine() ?: break
        if (firstLine) {
            line = line.removePrefix("\uFEFF")
            firstLine = false
        }
        if (!detected) {
            if (line.isBlank() || line.startsWith(':'))
                continue // Keep-alives don't select a format.
            if (line.trimStart().startsWith("{")) {
                check(line.length <= MAX_COMPLETION_PAYLOAD) { "DeepSeek 回复超出读取上限" }
                val json = StringBuilder(line)
                while (true) {
                    checkActive()
                    val next = reader.readLine() ?: break
                    check(next.length < MAX_COMPLETION_PAYLOAD - json.length) {
                        "DeepSeek 回复超出读取上限"
                    }
                    json.append('\n').append(next)
                }
                checkActive()
                return validateCompletion(completionObject(json.toString()))
            }
            detected = true
        }
        when {
            line.isEmpty() -> if (!dispatch()) break
            line.startsWith("data:") -> {
                val value = line.substring(5).removePrefix(" ")
                check(value.length < MAX_COMPLETION_PAYLOAD - data.length) { "DeepSeek 流式帧超出读取上限" }
                if (data.isEmpty()) {
                    if (value.trim() == "[DONE]") break
                    // Gateways sometimes omit blank lines. Only emit a standalone object, never a
                    // nested fragment that happens to end with a closing brace.
                    if (value.lastOrNull { !it.isWhitespace() } == '}') {
                        val standalone =
                            try {
                                completionObject(value)
                            } catch (_: JSONException) {
                                null
                            }
                        if (standalone != null) {
                            stream.accept(standalone)
                            onDelta(stream, false)
                            continue
                        }
                    }
                }
                if (data.isNotEmpty()) data.append('\n')
                data.append(value)
            }
        // SSE comments, id, event, retry and unrecognized fields do not carry completion data.
        }
    }
    checkActive()
    if (data.isNotEmpty()) dispatch()
    val result = validateCompletion(stream.result())
    onDelta(stream, true)
    return result
}

private fun completionObject(text: String): JSONObject {
    val tokens = JSONTokener(text)
    val result = tokens.nextValue()
    check(result is JSONObject && tokens.nextClean() == '\u0000') { "DeepSeek 返回的 JSON 格式无效" }
    return result
}

private fun validateCompletion(value: JSONObject): JSONObject {
    check(value.isNull("error")) { "DeepSeek 返回了错误，未完成本轮回复" }
    val choice = value.optJSONArray("choices")?.optJSONObject(0) ?: error("DeepSeek 未返回有效结果")
    val message = choice.optJSONObject("message") ?: error("DeepSeek 未返回完整消息")
    val reason = choice.optString("finish_reason")
    check(reason in setOf("stop", "length", "tool_calls")) {
        when (reason) {
            "aborted",
            "insufficient_system_resource" -> "DeepSeek 提前中断了本轮回复，请重试"
            "content_filter" -> "DeepSeek 未提供完整回复（内容过滤）"
            else -> "DeepSeek 未确认本轮回复已完成，请重试"
        }
    }
    check((message.optJSONArray("tool_calls")?.length() ?: 0) == 0 || reason == "tool_calls") {
        "DeepSeek 未确认工具调用完整，已停止执行"
    }
    return value
}
