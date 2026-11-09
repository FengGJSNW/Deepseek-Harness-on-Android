package dev.deepseek.mobile

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WhaleMotionTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private fun setup(enabled: State<Boolean> = mutableStateOf(true)) {
        ui.waitUntil(30000) { AssistantState.ready }
        ui.mainClock.autoAdvance = false
        ui.runOnUiThread {
            WhaleActivity.touching = false
            WhaleActivity.patrolling = false
            WhaleCompanion.settle()
            ui.activity.setContent {
                MaterialTheme {
                    Column(Modifier.fillMaxSize().background(Color.White)) {
                        Text(
                            "聊天区",
                            Modifier.clickable { WhaleActivity.ambientInteraction() }
                                .padding(24.dp),
                        )
                        Box(Modifier.width(320.dp).height(470.dp)) {
                            WhaleDock(wanderEnabled = enabled.value) {}
                        }
                    }
                }
            }
        }
        ui.mainClock.advanceTimeBy(160)
        ui.waitForIdle()
    }

    private fun body() = ui.onNodeWithTag("pet-body")

    private fun x() = body().fetchSemanticsNode().boundsInRoot.left

    private fun phase() = body().fetchSemanticsNode().config[SemanticsProperties.StateDescription]

    private fun screen(name: String) {
        ui.runOnUiThread {
            val view = ui.activity.window.decorView
            val image =
                android.graphics.Bitmap.createBitmap(
                    view.width,
                    view.height,
                    android.graphics.Bitmap.Config.ARGB_8888,
                )
            view.draw(android.graphics.Canvas(image))
            java.io.File(ui.activity.filesDir, "$name.png").outputStream().use {
                image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            image.recycle()
        }
    }

    /** Measure the rendered blue silhouette, not the unchanged layout rectangle. */
    private fun visibleBodyHeight(): Int {
        val bounds = body().fetchSemanticsNode().boundsInRoot
        var result = 0
        ui.runOnUiThread {
            val view = ui.activity.window.decorView
            val image =
                android.graphics.Bitmap.createBitmap(
                    view.width,
                    view.height,
                    android.graphics.Bitmap.Config.ARGB_8888,
                )
            view.draw(android.graphics.Canvas(image))
            var top = image.height
            var bottom = 0
            for (y in
                bounds.top.toInt().coerceAtLeast(0) until
                    bounds.bottom.toInt().coerceAtMost(image.height)) {
                for (x in
                    bounds.left.toInt().coerceAtLeast(0) until
                        bounds.right.toInt().coerceAtMost(image.width)) {
                    val color = image.getPixel(x, y)
                    val blue = android.graphics.Color.blue(color)
                    if (
                        blue > android.graphics.Color.red(color) + 12 &&
                            blue > android.graphics.Color.green(color) + 5
                    ) {
                        top = minOf(top, y)
                        bottom = maxOf(bottom, y)
                    }
                }
            }
            result = (bottom - top + 1).coerceAtLeast(0)
            image.recycle()
        }
        return result
    }

    @Test
    fun idleWalkSquashesInPlaceReversesAndDraggingReturnsHome() {
        setup()
        val home = x()
        ui.mainClock.advanceTimeBy(29_000)
        assertEquals(home, x(), 1f)
        ui.mainClock.advanceTimeBy(4_000)
        assertTrue("Starts moving after 30 seconds", x() < home - 60f)
        assertEquals("向左巡游", phase())
        val feet = body().fetchSemanticsNode().boundsInRoot.bottom
        var shortest = Int.MAX_VALUE
        var tallest = 0
        val heights = mutableListOf<Int>()
        repeat(40) {
            ui.mainClock.advanceTimeBy(32)
            val height = visibleBodyHeight()
            heights += height
            if (height < shortest) {
                shortest = height
                screen("whale-jelly-compressed")
            }
            if (height > tallest) {
                tallest = height
                screen("whale-jelly-rebound")
            }
            assertEquals(
                "The feet stay on the composer edge",
                feet,
                body().fetchSemanticsNode().boundsInRoot.bottom,
                1f,
            )
        }
        assertTrue("The rendered character squashes and springs back", tallest - shortest > 8)
        val compressionCutoff = tallest - (tallest - shortest) * .6f
        assertTrue(
            "The silhouette holds near maximum compression before release: $heights",
            heights.windowed(3).any { sample ->
                sample.max() <= compressionCutoff && sample.max() - sample.min() <= 2
            },
        )
        ui.mainClock.advanceTimeBy(4_000)
        ui.runOnUiThread { WhaleCompanion.react("wave", "在左边也能好好说话。") }
        ui.mainClock.advanceTimeBy(200)
        val pet = body().fetchSemanticsNode().boundsInRoot
        val bubble =
            ui.onNodeWithTag("pet-bubble").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Bubble moves to the right near the left edge", bubble.center.x > pet.center.x)
        val track = ui.onNodeWithTag("pet-track").fetchSemanticsNode().boundsInRoot
        assertTrue(bubble.left >= track.left - 1f && bubble.right <= track.right + 1f)
        screen("whale-walk-left-bubble")
        ui.mainClock.advanceTimeBy(4_000)
        assertEquals("向右巡游", phase())
        screen("whale-walk-right-mirrored")
        body().performTouchInput {
            down(center)
            moveBy(Offset(-120f, -40f))
            moveBy(Offset(-30f, -10f))
        }
        ui.mainClock.advanceTimeBy(100)
        assertEquals("拖动", phase())
        val held = x()
        ui.mainClock.advanceTimeBy(1000)
        assertEquals("Holding interrupts the patrol", held, x(), 1f)
        screen("whale-drag-left-facing")
        body().performTouchInput { moveBy(Offset(50f, 0f)) }
        ui.mainClock.advanceTimeBy(64)
        screen("whale-drag-right-facing")
        body().performTouchInput { up() }
        ui.mainClock.advanceTimeBy(1200)
        assertEquals("Release returns to the right-hand origin", home, x(), 1f)
        ui.mainClock.advanceTimeBy(28_000)
        assertEquals("Idle delay restarts after release", home, x(), 1f)
        ui.mainClock.advanceTimeBy(4_000)
        assertTrue(x() < home - 40f)
        ui.runOnUiThread { ui.activity.setContent {} }
        ui.mainClock.autoAdvance = true
    }

    @Test
    fun ambientChatTouchKeepsPatrolAndHiddenDockReturnsHome() {
        val enabled = mutableStateOf(true)
        setup(enabled)
        val home = x()
        ui.mainClock.advanceTimeBy(34_000)
        assertTrue(x() < home - 40f)
        ui.onNodeWithText("聊天区").performTouchInput { click() }
        val beforeTouch = x()
        ui.mainClock.advanceTimeBy(400)
        assertTrue("Ambient conversation touches do not cancel the patrol", x() < beforeTouch)
        ui.runOnUiThread { enabled.value = false }
        ui.mainClock.advanceTimeBy(60_000)
        assertEquals("No patrol while the dock is hidden or blocked by a dialog", home, x(), 1f)
        ui.runOnUiThread { enabled.value = true }
        ui.mainClock.advanceTimeBy(29_000)
        assertEquals(home, x(), 1f)
        ui.mainClock.advanceTimeBy(4_000)
        assertTrue(x() < home - 40f)
        ui.runOnUiThread { ui.activity.setContent {} }
        ui.mainClock.autoAdvance = true
    }
}
