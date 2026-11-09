package dev.deepseek.mobile

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class EffortRailTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test
    fun draggingPreviewsWithoutWritingAndReleaseCommitsOnce() {
        ui.waitUntil(30000) { AssistantState.ready }
        ui.runOnUiThread {
            AssistantState.addSession().effort = "low"
            AssistantState.petEnabled = false
            AssistantState.persist()
        }
        val saved = java.io.File(ui.activity.filesDir, "conversations-v3.json").readText()
        ui.onNodeWithContentDescription("模型与思考设置").performClick()
        ui.onNodeWithTag("reasoning-slider").assertIsDisplayed()
        ui.mainClock.autoAdvance = false
        val rail = ui.onNodeWithTag("reasoning-slider")
        rail.performTouchInput {
            down(centerLeft)
            moveTo(Offset(width * .74f, centerY))
        }
        ui.mainClock.advanceTimeBy(180)
        ui.onNodeWithTag("effort-title").assertTextEquals("Max")
        ui.runOnUiThread {
            assertEquals("low", AssistantState.current.effort)
            assertEquals(
                "No session serialization while dragging",
                saved,
                java.io.File(ui.activity.filesDir, "conversations-v3.json").readText(),
            )
        }
        rail.performTouchInput { up() }
        ui.mainClock.advanceTimeBy(400)
        ui.runOnUiThread { assertEquals("max", AssistantState.current.effort) }
        val committed = java.io.File(ui.activity.filesDir, "conversations-v3.json").readText()
        rail.performTouchInput {
            down(centerRight)
            moveTo(center)
            cancel()
        }
        ui.mainClock.advanceTimeBy(400)
        ui.onNodeWithTag("effort-title").assertTextEquals("Max")
        ui.runOnUiThread {
            assertEquals("max", AssistantState.current.effort)
            assertEquals(
                "Cancel preserves the committed session",
                committed,
                java.io.File(ui.activity.filesDir, "conversations-v3.json").readText(),
            )
        }
        rail.performTouchInput { click(centerRight) }
        ui.mainClock.advanceTimeBy(400)
        ui.onNodeWithTag("effort-title").assertTextEquals("Ultra")
        ui.runOnUiThread { assertEquals("ultra", AssistantState.current.effort) }
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        ui.mainClock.autoAdvance = true
    }
}
