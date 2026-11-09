package dev.deepseek.mobile

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReplyActivityTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private fun render(content: @Composable () -> Unit) {
        ui.waitUntil(30000) { AssistantState.ready && DshaFonts.ready }
        ui.runOnUiThread {
            ui.activity.setContent {
                MaterialTheme {
                    FontEnvironment {
                        Column(
                            Modifier.fillMaxSize()
                                .background(Color.White)
                                .verticalScroll(rememberScrollState())
                                .padding(20.dp),
                        ) {
                            content()
                        }
                    }
                }
            }
        }
    }

    private fun Color.near(other: Color) =
        abs(red - other.red) < .015f &&
            abs(green - other.green) < .015f &&
            abs(blue - other.blue) < .015f

    @Test
    fun assistantMarkdownKeepsTheLeftEdgeAtBothEnds() {
        render {
            ChatMessage(ChatEntry("assistant", "> 顶部引用\n\n回复正文保持完整。\n\n> 底部引用"), showHeader = false)
        }
        val pixels = ui.onNodeWithTag("markdown-message").captureToImage().toPixelMap()
        val x = with(ui.density) { 5.dp.roundToPx() }
        val inset = with(ui.density) { 1.dp.roundToPx() }
        TestSnapshots.capture(ui, "v0812-reply-corners")
        assertTrue(
            "The first quote rule reaches the top left",
            pixels[x, inset].near(ReadingStyle.line),
        )
        assertTrue(
            "The last quote rule reaches the bottom left",
            pixels[x, pixels.height - inset - 1].near(ReadingStyle.line),
        )
    }

    @Test
    fun thinkingAndToolRowsShareTheSameFramedCardWhileRunningAndWhenComplete() {
        val complete = mutableStateOf(false)
        val entries =
            listOf(
                ChatEntry("stream_reasoning", "我先检查项目结构。"),
                ChatEntry("tool", """{"name":"workspace_list","status":"done"}"""),
                ChatEntry("tool", """{"name":"workspace_read","status":"done"}"""),
                ChatEntry("reasoning", "文件已读取，可以整理结果。"),
                ChatEntry("assistant", "最终回复正文。"),
            )
        render { AgentTimeline(entries, showHeader = false, completed = complete.value) }
        val card = ui.onNodeWithTag("agent-activity-card")
        card.assertIsDisplayed()
        val bounds = card.fetchSemanticsNode().boundsInRoot
        listOf("思考中", "2 项执行", "思考过程").forEach { label ->
            val row = ui.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
            assertTrue(
                "$label belongs inside the shared frame",
                row.top >= bounds.top && row.bottom <= bounds.bottom,
            )
        }
        val pixels = card.captureToImage().toPixelMap()
        val margin = with(ui.density) { 13.dp.roundToPx() }
        assertTrue("The outer corner is rounded", pixels[0, 0].near(Color.White))
        assertTrue(
            "Activity has the tool card background",
            pixels[pixels.width / 2, margin].near(Color(0xFFFAFAFA)),
        )
        val separators =
            (margin until pixels.height - margin).count { y ->
                (margin until pixels.width - margin).all { x ->
                    pixels[x, y].near(ReadingStyle.workLine)
                }
            }
        assertTrue("Both horizontal row separators are visible: $separators", separators >= 2)
        ui.onNodeWithText("思考中").performClick()
        ui.onNodeWithText("2 项执行").performClick()
        ui.onNodeWithText("我先检查项目结构。").assertIsDisplayed()
        ui.onNodeWithTag("tool-row-1").assertIsDisplayed()
        TestSnapshots.capture(ui, "v0812-running-activity")
        ui.runOnUiThread { complete.value = true }
        ui.onNodeWithTag("work-details-card").assertIsDisplayed()
        ui.onNodeWithTag("agent-activity-card").assertDoesNotExist()
        ui.onNodeWithText("最终回复正文。").assertIsDisplayed()
        ui.onNodeWithTag("work-details-toggle").performClick()
        val details = ui.onNodeWithTag("work-details-card").fetchSemanticsNode().boundsInRoot
        val finalReply = ui.onNodeWithText("最终回复正文。").fetchSemanticsNode().boundsInRoot
        assertTrue("The final reply stays below the work card", finalReply.top >= details.bottom)
        TestSnapshots.capture(ui, "v0812-work-details")
    }
}
