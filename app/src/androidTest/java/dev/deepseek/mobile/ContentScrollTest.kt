package dev.deepseek.mobile

import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ContentScrollTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test
    fun overflowingTablesAndCodeHaveBothScrollAxesAndVisibleThumbs() {
        ui.waitUntil(30000) { AssistantState.ready && DshaFonts.ready }
        val table =
            "| " +
                (1..8).joinToString(" | ") { "列 $it" } +
                " |\n| " +
                (1..8).joinToString(" | ") { "---" } +
                " |\n" +
                (1..30).joinToString("\n") { row ->
                    "| " + (1..8).joinToString(" | ") { "内容 $row-$it" } + " |"
                }
        val code =
            "```kotlin\n" +
                (1..35).joinToString("\n") { "val line$it = \"${"wide ".repeat(22)}\"" } +
                "\n```"
        ui.runOnUiThread {
            ui.activity.setContent {
                MaterialTheme {
                    FontEnvironment {
                        Surface(color = Color.White) {
                            Column(
                                Modifier.fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(20.dp),
                            ) {
                                MarkdownText(table + "\n\n" + code)
                            }
                        }
                    }
                }
            }
        }
        fun verify(tag: String) {
            ui.waitUntil(10000) { ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithTag(tag).performScrollTo()
            val horizontal =
                ui.onNode(
                    SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange) and
                        hasAnyAncestor(hasTestTag(tag)),
                )
            val vertical =
                ui.onNode(
                    SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and
                        hasAnyAncestor(hasTestTag(tag)),
                )
            assertTrue(
                horizontal
                    .fetchSemanticsNode()
                    .config[SemanticsProperties.HorizontalScrollAxisRange]
                    .maxValue() > 0,
            )
            assertTrue(
                vertical
                    .fetchSemanticsNode()
                    .config[SemanticsProperties.VerticalScrollAxisRange]
                    .maxValue() > 0,
            )
            ui.mainClock.autoAdvance = false
            ui.onNodeWithTag(tag).performTouchInput { swipeLeft() }
            ui.mainClock.advanceTimeBy(100)
            assertTrue(
                horizontal
                    .fetchSemanticsNode()
                    .config[SemanticsProperties.HorizontalScrollAxisRange]
                    .value() > 0,
            )
            ui.onNodeWithTag(tag).performTouchInput { swipeUp() }
            ui.mainClock.advanceTimeBy(100)
            assertTrue(
                vertical
                    .fetchSemanticsNode()
                    .config[SemanticsProperties.VerticalScrollAxisRange]
                    .value() > 0,
            )
            val captured = ui.onNodeWithTag(tag).captureToImage()
            val bitmap = captured.asAndroidBitmap()
            java.io.File(ui.activity.filesDir, "scroll-diagnostic-$tag.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            val pixels = captured.toPixelMap()
            val density = ui.activity.resources.displayMetrics.density
            val inset = (4.25f * density).roundToInt()
            fun neutral(c: Color) =
                c.red in 0.45f..0.80f &&
                    abs(c.red - c.green) < .035f &&
                    abs(c.green - c.blue) < .035f
            fun longest(values: List<Boolean>): Int {
                var run = 0
                var longest = 0
                values.forEach {
                    run = if (it) run + 1 else 0
                    longest = maxOf(longest, run)
                }
                return longest
            }
            assertTrue(
                "Horizontal thumb must be visible",
                longest(
                    (0 until pixels.width).map { neutral(pixels[it, pixels.height - inset]) },
                ) >= 16 * density,
            )
            assertTrue(
                "Vertical thumb must be visible",
                // Translucent thumb can overlap syntax-colored glyphs, so count its
                // neutral pixels across the rail rather than requiring one unbroken run.
                (0 until pixels.height).count {
                    neutral(pixels[pixels.width - inset, it])
                } >= 16 * density,
            )
            TestSnapshots.capture(ui, "ux-scroll-$tag")
            ui.mainClock.autoAdvance = true
        }
        verify("markdown-table")
        verify("code-scroll-area")
    }
}
