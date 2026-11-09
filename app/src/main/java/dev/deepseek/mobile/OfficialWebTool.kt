package dev.deepseek.mobile

import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

internal enum class BrowserProvider(
    val key: String,
    val label: String,
    val chrome: Boolean,
    val official: Boolean,
) {
    BLOCKED("blocked", "阻止联网", false, false),
    LOCAL("local", "仅 Chrome", true, false),
    OFFICIAL("official", "仅官方接口", false, true),
    BOTH("both", "同时提供", true, true);

    fun allows(tool: OfficialWebTool?) = if (tool == null) chrome else official

    companion object {
        fun parse(key: String) = entries.firstOrNull { it.key == key } ?: LOCAL
    }
}

/** The standalone Search and Fetch endpoints work with the existing model and tool loop. */
internal enum class OfficialWebTool(val method: String, endpoint: String) {
    SEARCH("browser_search", "search"),
    FETCH("browser_fetch", "fetch");

    val endpoint = "https://api.deepseek.com/v1/tools/$endpoint"

    fun request(args: JSONObject): JSONObject =
        when (this) {
            SEARCH -> {
                val query = (args.opt("query") as? String).orEmpty().trim()
                require(query.codePointCount(0, query.length) in 1..128) { "搜索词须为 1～128 个字符" }
                val count =
                    if (args.has("count")) {
                        args.opt("count") as? Int ?: error("搜索结果数量须为整数")
                    } else 10
                require(count in 1..50) { "搜索结果数量须为 1～50" }
                JSONObject().put("query", query).put("count", count)
            }
            FETCH -> {
                val url = (args.opt("url") as? String).orEmpty().trim()
                require(validUrl(url)) { "请输入不带用户名或密码的有效 HTTP / HTTPS 地址（最多 4096 字符）" }
                JSONObject().put("url", url)
            }
        }

    fun result(
        body: JSONObject,
        request: JSONObject,
        allowed: (String) -> Boolean,
    ): JSONObject =
        when (this) {
            SEARCH -> {
                val results = body.getJSONArray("results")
                val visible = JSONArray()
                for (index in 0 until minOf(results.length(), request.getInt("count"))) {
                    val result = results.optJSONObject(index) ?: continue
                    val url = result.optString("url")
                    if (!validUrl(url) || !allowed(url)) continue
                    visible.put(
                        JSONObject()
                            .put("title", result.optString("title").take(300))
                            .put("url", url)
                            .put("snippet", result.optString("snippet").take(500))
                            .put("summary", result.optString("summary").take(1200))
                            .put("site_name", result.optString("site_name", "").take(100))
                            .put("date", result.optString("date", "").take(50)),
                    )
                }
                JSONObject()
                    .put("query", request.getString("query"))
                    .put("results", visible)
                    .put("message", "官方搜索返回 ${visible.length()} 条结果")
            }
            FETCH -> {
                val content = body.getString("content")
                JSONObject()
                    .put("url", request.getString("url"))
                    .put("title", body.optString("title").take(300))
                    .put("content", content.take(48_000))
                    .put("truncated", content.length > 48_000)
                    .put("message", "已抓取网页")
            }
        }.put("untrusted", true)

    companion object {
        fun fromMethod(method: String) = entries.firstOrNull { it.method == method }

        private fun validUrl(url: String): Boolean {
            if (url.length !in 1..4096) return false
            val uri = runCatching { URI(url) }.getOrNull() ?: return false
            return uri.scheme?.lowercase() in setOf("http", "https") &&
                !uri.host.isNullOrBlank() &&
                uri.rawUserInfo == null &&
                uri.port in -1..65535
        }
    }
}
