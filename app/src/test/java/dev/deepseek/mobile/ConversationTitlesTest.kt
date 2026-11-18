package dev.deepseek.mobile

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConversationTitlesTest {
    @Test
    fun initialTitleTrimsLinesWithoutSplittingSupplementaryCharacters() {
        val title = ConversationTitles.initial("  🐋".repeat(30), false)
        assertTrue(title.endsWith("…"))
        assertEquals(25, title.codePointCount(0, title.length))
        assertFalse(title.contains('\n'))
        assertEquals("图片会话", ConversationTitles.initial(" \n ", true))
    }

    @Test
    fun lateReplyCannotOverwriteAnyManualRenameEvenWhenRenamedBack() {
        val conversation = Conversation(title = "最初的标题")
        val revision = conversation.titleRevision
        conversation.title = "手动标题"
        conversation.title = "最初的标题"
        ConversationTitles.apply(conversation, revision, "后台生成标题")
        assertEquals("最初的标题", conversation.title)
        ConversationTitles.apply(conversation, conversation.titleRevision, "新的标题")
        assertEquals("新的标题", conversation.title)
    }

    @Test
    fun emptyMalformedAndMultilineResponsesKeepExistingTitle() {
        fun response(content: String) =
            JSONObject()
                .put(
                    "choices",
                    JSONArray()
                        .put(
                            JSONObject().put("message", JSONObject().put("content", content)),
                        ),
                )
        assertNull(ConversationTitles.result(response("   ")))
        assertNull(ConversationTitles.result(response("not json")))
        assertNull(ConversationTitles.result(response("{\"title\":\"first\\nsecond\"}")))
        assertEquals("调整搜索布局", ConversationTitles.result(response("{\"title\":\"调整搜索布局\"}")))
    }

    @Test
    fun titleRequestContainsNoExecutableToolsOrInventedImagePrompt() {
        val request = ConversationTitles.request("", 3, 0)
        assertFalse(request.has("tools"))
        assertFalse(request.getBoolean("stream"))
        val input =
            JSONObject(request.getJSONArray("messages").getJSONObject(1).getString("content"))
        assertEquals("", input.getString("message"))
        assertEquals(3, input.getInt("images"))
    }
}
