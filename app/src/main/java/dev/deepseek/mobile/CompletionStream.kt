package dev.deepseek.mobile

import java.util.TreeMap
import org.json.JSONArray
import org.json.JSONObject

/** Assemble real SSE deltas, including tool arguments fragmented across network frames. */
internal class CompletionStream {
    val text = StringBuilder()
    val reasoning = StringBuilder()
    private val tools = TreeMap<Int, JSONObject>()
    private var usage: JSONObject? = null
    private var finish = ""

    fun accept(value: JSONObject) {
        check(!value.has("error")) { "DeepSeek 流式响应中断，请重试" }
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
            if (call.has("id")) current.put("id", current.optString("id") + call.getString("id"))
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
