package dev.deepseek.mobile

import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CompletionResponseTest {
    private val partialTool =
        """{"tool_calls":[{"index":0,"id":"t","function":{"name":"write","arguments":"{}"}}]}"""

    private fun decode(body: String, onUpdate: (String, Boolean) -> Unit = { _, _ -> }) =
        runBlocking {
            readCompletion(body.reader().buffered(), {}) { stream, done ->
                onUpdate(stream.text.toString(), done)
            }
        }

    @Test
    fun completeJsonWithKeepAlivesIsReturnedIntactWithoutStreamEvents() {
        val result =
            decode(
                "\uFEFF\r\n: keep-alive\r\n\r\n" +
                    """{
                    "choices":[{"message":{"role":"assistant","content":"答复","reasoning_content":"思考"},"finish_reason":"stop"}],
                    "usage":{"total_tokens":9}
                    }""",
            ) { _, _ ->
                fail("Buffered response must be delivered once")
            }
        assertEquals(
            "思考",
            result
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("reasoning_content"),
        )
        assertEquals(9, result.getJSONObject("usage").getInt("total_tokens"))
    }

    @Test
    fun eventStreamHandlesCommentsMultilineDataUsageOnlyAndAdjacentFrames() {
        val progress = mutableListOf<Pair<String, Boolean>>()
        val response =
            decode(
                "\uFEFF: keep-alive\r\n\r\n" +
                    "event: message\r\ndata: {\"metadata\":{},\"choices\":[{\"delta\":{}},\r\n" +
                    "data: {\"ignored\":{}}]}\r\n\r\n" +
                    "data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}\r\n" +
                    "data: ]}\r\n\r\n" +
                    "data:{\"choices\":[{\"delta\":{\"content\":\"!\"},\"finish_reason\":\"stop\"}]}\n" +
                    "data: {\"choices\":[],\"usage\":{\"total_tokens\":11}}\n" +
                    "data: [DONE]\n\n",
            ) { text, done ->
                progress += text to done
            }
        assertTrue(progress.contains("hello" to false))
        assertEquals("hello!" to true, progress.last())
        assertEquals(11, response.getJSONObject("usage").getInt("total_tokens"))
    }

    @Test
    fun interruptedOrFilteredRepliesNeverReleasePartialToolCalls() {
        for (reason in
            listOf("aborted", "insufficient_system_resource", "content_filter", "length")) {
            val stream = """data: {"choices":[{"delta":$partialTool,"finish_reason":"$reason"}]}"""
            val buffered = """{"choices":[{"message":$partialTool,"finish_reason":"$reason"}]}"""
            for (body in listOf("$stream\n\ndata: [DONE]\n\n", buffered)) {
                assertThrows(IllegalStateException::class.java) { decode(body) }
            }
        }
        assertThrows(IllegalStateException::class.java) {
            decode("""data: {"choices":[{"delta":$partialTool}]}""" + "\n\ndata: [DONE]\n\n")
        }
    }

    @Test
    fun validFragmentedToolIsPreservedButConcatenatedJsonResponsesAreRejected() {
        val first =
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"t","function":{"name":"list","arguments":"{"}}]}}]}"""
        val last =
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":null,"function":{"arguments":"}"}}]},"finish_reason":"tool_calls"}]}"""
        val repeatId = """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"t"}]}}]}"""
        val choice =
            decode("data: $first\n\ndata: $repeatId\n\ndata: $last\n\n")
                .getJSONArray("choices")
                .getJSONObject(0)
        val tool = choice.getJSONObject("message").getJSONArray("tool_calls").getJSONObject(0)
        assertEquals("t", tool.getString("id"))
        assertEquals("{}", tool.getJSONObject("function").getString("arguments"))
        val json = """{"choices":[{"message":{"content":"one"},"finish_reason":"stop"}]}"""
        assertThrows(IllegalStateException::class.java) { decode("$json\n$json") }
    }

    @Test
    fun cancelBetweenFramesDoesNotReturnACompletion() {
        var cancelled = false
        assertThrows(CancellationException::class.java) {
            runBlocking {
                readCompletion(
                    ("data: {\"choices\":[{\"delta\":{\"content\":\"preview\"}}]}\n\n" +
                            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n")
                        .reader()
                        .buffered(),
                    { if (cancelled) throw CancellationException() },
                ) { _, done ->
                    assertFalse(done)
                    cancelled = true
                }
            }
        }
        assertTrue(cancelled)
    }
}
