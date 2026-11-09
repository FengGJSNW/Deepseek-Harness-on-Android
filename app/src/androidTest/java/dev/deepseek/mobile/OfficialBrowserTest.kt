package dev.deepseek.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class OfficialBrowserTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test
    fun browserProviderSwitchesAndShowsMainAndChildRecords() {
        ui.waitUntil(30000) { AssistantState.ready }
        lateinit var session: Conversation
        lateinit var child: AgentWork
        ui.runOnUiThread {
            resetTransientUiForTest()
            AssistantState.appMode = "work"
            AssistantState.activeTaskId = ""
            session = AssistantState.addSession()
            child = AgentWork("official-test-child", session.id, "检索员", "")
            TeamState.agents.add(child)
            val search = OfficialWebTool.SEARCH
            val searchArgs = JSONObject().put("query", "官方测试搜索")
            val url = "https://example.org/browser-fixture"
            val results = JSONArray().put(JSONObject().put("url", url).put("title", "结果标题"))
            val searchResult =
                search.result(JSONObject().put("results", results), search.request(searchArgs)) {
                    true
                }
            appendResult(session.messages, search, "official-test-search", searchArgs, searchResult)
            val fetch = OfficialWebTool.FETCH
            val fetchArgs = JSONObject().put("url", url)
            val fetched =
                fetch.result(
                    JSONObject().put("title", "官方抓取示例").put("content", "# 正文示例"),
                    fetch.request(fetchArgs),
                ) {
                    true
                }
            appendResult(child.messages, fetch, "official-test-fetch", fetchArgs, fetched)
        }
        try {
            ui.onNodeWithContentDescription("打开功能区").performClick()
            ui.onNodeWithTag("workspace-tabs").assertDoesNotExist()
            ui.onNodeWithText("内置浏览器").performClick()
            ui.onNodeWithTag("browser-provider-local").assertIsSelected()
            ui.onNodeWithTag("browser-provider-official").performClick().assertIsSelected()
            ui.onNodeWithTag("workspace-official-browser").assertIsDisplayed()
            ui.runOnUiThread { assertEquals(BrowserProvider.OFFICIAL, session.browserProvider) }
            ui.onNodeWithText("官方测试搜索").performClick()
            ui.onNodeWithText("结果标题").assertIsDisplayed()
            ui.onNodeWithContentDescription("返回官方浏览记录").performClick()
            ui.onNodeWithText("官方抓取示例").performClick()
            ui.onNodeWithText("# 正文示例").assertIsDisplayed()
            ui.onNodeWithTag("browser-provider-local").performClick().assertIsSelected()
            ui.onNodeWithTag("workspace-official-browser").assertDoesNotExist()
            ui.runOnUiThread { assertEquals(BrowserProvider.LOCAL, session.browserProvider) }
        } finally {
            ui.runOnUiThread {
                WorkspaceState.reset()
                TeamState.agents.remove(child)
            }
        }
    }

    @Test
    fun officialRequestsRejectInvalidInputAndNormalizeUntrustedResults() {
        val search = OfficialWebTool.SEARCH
        val fetch = OfficialWebTool.FETCH
        assertEquals("https://api.deepseek.com/v1/tools/search", search.endpoint)
        assertEquals("https://api.deepseek.com/v1/tools/fetch", fetch.endpoint)
        val request = search.request(JSONObject().put("query", "🐋".repeat(128)).put("count", 3))
        assertTrue(
            runCatching { search.request(JSONObject().put("query", "🐋".repeat(129))) }.isFailure,
        )
        assertTrue(
            runCatching { search.request(JSONObject().put("query", "test").put("count", 51)) }
                .isFailure,
        )
        for (url in
            listOf(
                "file:///tmp/secret",
                "https://user:password@example.org",
                "https://example.org\\@test.invalid",
            )) {
            assertTrue(url, runCatching { fetch.request(JSONObject().put("url", url)) }.isFailure)
        }
        val allowed = "https://example.org/allowed"
        val response =
            JSONArray().apply {
                put(JSONObject().put("url", "https://example.org/blocked"))
                put(JSONObject().put("url", "javascript:alert(1)"))
                put(JSONObject().put("url", allowed))
            }
        val checked =
            search.result(JSONObject().put("results", response), request) { it == allowed }
        assertTrue(checked.getBoolean("untrusted"))
        assertEquals(1, checked.getJSONArray("results").length())
        assertEquals(allowed, checked.getJSONArray("results").getJSONObject(0).getString("url"))
        val content =
            fetch.result(
                JSONObject().put("content", "x".repeat(48_001)),
                fetch.request(JSONObject().put("url", allowed)),
            ) {
                true
            }
        assertTrue(content.getBoolean("truncated"))
        assertEquals(48_000, content.getString("content").length)
    }

    private fun appendResult(
        messages: MutableList<ChatEntry>,
        tool: OfficialWebTool,
        id: String,
        args: JSONObject,
        result: JSONObject,
    ) {
        val record =
            JSONObject()
                .put("id", id)
                .put("name", tool.method)
                .put("status", "running")
                .put("args", args)
        messages.add(ChatEntry("tool", record.toString()))
        finishTool(messages, JSONObject().put("id", id).put("ok", true).put("result", result))
    }
}
