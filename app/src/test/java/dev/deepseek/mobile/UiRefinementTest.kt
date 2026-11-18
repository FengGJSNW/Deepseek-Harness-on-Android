package dev.deepseek.mobile

import androidx.compose.ui.text.AnnotatedString
import dev.deepseek.mobile.terminal.ReviewerConfig
import kotlin.random.Random
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UiRefinementTest {
    @Test
    fun sliderRejectsTapsScrollsAndTrackGrabs() {
        assertFalse(SliderIntent(50f, 20f, 8f, true).move(52f, 23f))
        assertFalse(SliderIntent(50f, 20f, 8f, false).move(150f, 20f))
        val scroll = SliderIntent(50f, 20f, 8f, true)
        assertFalse(scroll.move(52f, 45f))
        assertTrue(scroll.cancelled)
        assertFalse(scroll.move(150f, 45f))
        val drag = SliderIntent(50f, 20f, 8f, true)
        assertFalse(drag.move(60f, 29f))
        assertTrue(drag.move(75f, 25f))
        assertTrue(drag.move(80f, 70f))
    }

    @Test
    fun sliderQuantizesClampsAndPreservesFinePrecision() {
        assertEquals(0f, sliderValue(-20f, 0f..1f, 3), 0f)
        assertEquals(1f, sliderValue(20f, 0f..1f, 3), 0f)
        assertEquals(.5f, sliderValue(.6f, 0f..1f, 3), 0f)
        assertEquals(.1234f, sliderValue(.1234f, 0f..1f, 0), .00001f)
        assertEquals(2f, sliderValue(Float.NaN, 2f..8f, 0), 0f)
    }

    @Test
    fun markdownRendersOneEditableDocumentWithNestedStyles() {
        val source =
            "## 标题\n\n**粗体与 *斜体***，~~旧字~~ 和 `code`\n- 事项\n- [x] 完成\n[链接](https://example.com)"
        val result = EditableMarkdown.filter(AnnotatedString(source))
        assertEquals("标题\n\n粗体与 斜体，旧字 和 code\n• 事项\n☑ 完成\n链接", result.text.text)
        assertTrue(result.text.spanStyles.size >= 5)
    }

    @Test
    fun editsInRenderedTextMapBackInsideOriginalMarkup() {
        val source = "**中文😀**"
        val result = EditableMarkdown.filter(AnnotatedString(source))
        assertEquals("中文😀", result.text.text)
        val end = result.offsetMapping.transformedToOriginal(result.text.length)
        assertEquals("**中文😀继续**", source.substring(0, end) + "继续" + source.substring(end))
        val beginning = result.offsetMapping.transformedToOriginal(0)
        assertEquals(2, beginning)
    }

    @Test
    fun cursorMappingIsBoundedMonotonicAndRoundTripsVisibleOffsets() {
        val random = Random(84)
        val samples =
            mutableListOf(
                "",
                "**",
                "**a****b**",
                "# 中\r\n\r\n😀",
                "```kt\nval a = 1\n```",
                "> **字**\n> 次行",
                "- **a**\n  - b",
                "[**hello**](url)",
            )
        repeat(250) {
            samples +=
                List(100) {
                        listOf("*", "_", "#", "[", "]", "(", ")", "中", "😀", "\n", " ", "`", "a")
                            .random(random)
                    }
                    .joinToString("")
        }
        for (source in samples) {
            val result = EditableMarkdown.filter(AnnotatedString(source))
            val forward = (0..source.length).map(result.offsetMapping::originalToTransformed)
            val reverse = (0..result.text.length).map(result.offsetMapping::transformedToOriginal)
            assertTrue(forward.zipWithNext().all { it.first <= it.second })
            assertTrue(reverse.zipWithNext().all { it.first <= it.second })
            assertTrue(forward.all { it in 0..result.text.length })
            assertTrue(reverse.all { it in 0..source.length })
            reverse.forEachIndexed { index, original ->
                assertEquals(source, index, forward[original])
            }
        }
    }

    @Test
    fun usageUsesLatestContextAndPersistsCumulativeTotals() {
        val first =
            ConversationUsage()
                .record(
                    JSONObject(
                        "{\"prompt_tokens\":100,\"completion_tokens\":20,\"prompt_cache_hit_tokens\":80,\"context_budget\":1000}",
                    ),
                    "a",
                )
        assertEquals(.8f, first.cacheRatio!!, .001f)
        val next = first.record(JSONObject("{\"prompt_tokens\":200,\"completion_tokens\":10}"), "b")
        assertEquals(200L, next.prompt)
        assertEquals(330L, next.total)
        assertNull(next.cached)
        assertNull(next.contextRatio)
        assertEquals(next, ConversationUsage.from(next.json()))
        assertNull(ConversationUsage().cacheRatio)
    }

    @Test
    fun invalidCacheNumbersNeverProduceMisleadingRates() {
        for (data in
            listOf(
                "{\"prompt_tokens\":0,\"prompt_cache_hit_tokens\":0}",
                "{\"prompt_tokens\":10,\"prompt_cache_hit_tokens\":20}",
                "{\"prompt_tokens\":10,\"prompt_cache_hit_tokens\":-1}",
            )) {
            assertNull(ConversationUsage().record(JSONObject(data), "a").cacheRatio)
        }
    }

    @Test
    fun reviewerDefaultRemainsParseableAndHasExplicitWorkBoundaries() {
        val config = ReviewerConfig.parse(ReviewerConfig.defaultText)
        assertEquals("inherit", config.model)
        assertTrue(config.system.contains("DSHA"))
        assertTrue(config.system.contains("已有") || config.system.contains("已经明确授权"))
        assertTrue(config.system.contains("原生拒绝"))
        assertFalse(config.system.contains("'''"))
    }
}
