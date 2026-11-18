package dev.deepseek.mobile

import org.json.JSONObject

/** Provider measurements only. Context is the latest request, never cumulative input tokens. */
internal data class ConversationUsage(
    val model: String = "",
    val prompt: Long? = null,
    val output: Long? = null,
    val cached: Long? = null,
    val contextBudget: Long? = null,
    val total: Long = 0,
    val requests: Long = 0,
) {
    val cacheRatio: Float?
        get() =
            if (prompt != null && prompt > 0 && cached != null) cached.toFloat() / prompt else null

    val contextRatio: Float?
        get() =
            if (prompt != null && contextBudget != null && contextBudget > 0)
                prompt.toFloat() / contextBudget
            else null

    fun record(value: JSONObject, measuredModel: String): ConversationUsage {
        val input = value.count("prompt_tokens")
        val out = value.count("completion_tokens")
        val hit =
            (value.count("prompt_cache_hit_tokens")
                    ?: value.optJSONObject("prompt_tokens_details")?.count("cached_tokens"))
                ?.takeIf { input != null && it <= input }
        return ConversationUsage(
            measuredModel,
            input,
            out,
            hit,
            value.count("context_budget")?.takeIf { it > 0 },
            total + (value.count("total_tokens") ?: ((input ?: 0) + (out ?: 0))),
            requests + 1,
        )
    }

    fun json() =
        JSONObject()
            .put("model", model)
            .put("prompt", prompt)
            .put("output", output)
            .put("cached", cached)
            .put("contextBudget", contextBudget)
            .put("total", total)
            .put("requests", requests)

    companion object {
        fun from(value: JSONObject?): ConversationUsage =
            if (value == null) ConversationUsage()
            else
                ConversationUsage(
                    value.optString("model"),
                    value.count("prompt"),
                    value.count("output"),
                    value.count("cached"),
                    value.count("contextBudget"),
                    value.count("total") ?: 0,
                    value.count("requests") ?: 0,
                )
    }
}

private fun JSONObject.count(key: String): Long? =
    (opt(key) as? Number)?.toLong()?.takeIf { it >= 0 }
