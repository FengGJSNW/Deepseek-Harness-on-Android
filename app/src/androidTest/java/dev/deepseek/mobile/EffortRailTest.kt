package dev.deepseek.mobile

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class EffortRailTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private fun awaitPersistedEffort(effort: String) {
        val session = AssistantState.selectedId
        ui.waitUntil(5000) {
            runCatching {
                    val data =
                        org.json.JSONObject(
                            java.io.File(ui.activity.filesDir, "conversations-v3.json").readText(),
                        )
                    val sessions = data.getJSONArray("sessions")
                    data.optString("selected") == session &&
                        (0 until sessions.length()).any {
                            val item = sessions.getJSONObject(it)
                            item.optString("id") == session && item.optString("effort") == effort
                        }
                }
                .getOrDefault(false)
        }
    }

    @Test
    fun horizontalThumbDragOwnsGestureButVerticalSwipeAndTrackTapsNeverCommit() {
        ui.waitUntil(30000) { AssistantState.ready }
        var selected by mutableIntStateOf(1)
        val commits = mutableListOf<Int>()
        var scroll: androidx.compose.foundation.ScrollState? = null
        ui.runOnUiThread {
            ui.activity.setContent {
                MaterialTheme {
                    val scrolling = rememberScrollState()
                    scroll = scrolling
                    Column(Modifier.fillMaxSize().verticalScroll(scrolling)) {
                        Spacer(Modifier.height(120.dp))
                        EffortRail(
                            selected,
                            {},
                            {
                                selected = it
                                commits.add(it)
                            },
                            {},
                            Modifier.fillMaxWidth().height(48.dp),
                        )
                        Spacer(Modifier.height(1200.dp))
                    }
                }
            }
        }
        val rail = ui.onNodeWithTag("reasoning-slider")
        rail.performTouchInput {
            down(center)
            moveTo(center + Offset(2f, -100f))
            up()
        }
        ui.runOnIdle {
            assertTrue("Vertical swipe belongs to the scroll container", scroll!!.value > 0)
            assertTrue("Scrolling must not change effort", commits.isEmpty())
        }
        ui.runOnUiThread {
            ui.activity.setContent {
                MaterialTheme {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 120.dp)) {
                        EffortRail(
                            selected,
                            {},
                            {
                                selected = it
                                commits.add(it)
                            },
                            {},
                            Modifier.fillMaxWidth().height(48.dp),
                        )
                    }
                }
            }
        }
        ui.waitForIdle()
        ui.mainClock.autoAdvance = false
        rail.performTouchInput {
            down(Offset(width / 3f, centerY))
            moveTo(centerRight + Offset(0f, 60f))
            up()
        }
        ui.mainClock.advanceTimeBy(16)
        rail.performTouchInput { click(centerLeft) }
        ui.mainClock.advanceTimeBy(16)
        rail.performTouchInput { click(centerRight) }
        ui.mainClock.advanceTimeBy(500)
        ui.runOnIdle {
            assertEquals(listOf(3), commits)
            assertEquals(3, selected)
        }
        ui.mainClock.autoAdvance = true
    }

    @Test
    fun highestEffortRendersAndChildHeaderHasOnlyBackAction() {
        ui.waitUntil(30000) { AssistantState.ready }
        ui.runOnUiThread {
            resetTransientUiForTest()
            AssistantState.appMode = "work"
            AssistantState.petEnabled = false
            AssistantState.addSession().apply {
                effort = "low"
                childEffort = "max"
            }
        }
        ui.onNodeWithContentDescription("模型与思考设置").performClick()
        ui.onNodeWithTag("reasoning-slider").performTouchInput { swipe(centerLeft, centerRight) }
        ui.mainClock.autoAdvance = false
        ui.mainClock.advanceTimeBy(2500)
        TestSnapshots.capture(ui, "effort-source-ultra")
        ui.onNodeWithContentDescription("子智能体模型与强度").performClick()
        ui.mainClock.advanceTimeBy(700)
        ui.onNodeWithContentDescription("返回主智能体设置").assertIsDisplayed()
        ui.onNodeWithContentDescription("子智能体设置").assertDoesNotExist()
        ui.onNodeWithTag("child-effort-title").assertTextEquals("Max")
        TestSnapshots.capture(ui, "effort-source-child")
        ui.mainClock.autoAdvance = true
    }

    @Test
    fun draggingPreviewsWithoutWritingAndReleaseCommitsOnce() {
        ui.waitUntil(30000) { AssistantState.ready }
        ui.runOnUiThread {
            resetTransientUiForTest()
            AssistantState.appMode = "work"
            AssistantState.addSession().effort = "low"
            AssistantState.petEnabled = false
            AssistantState.persist()
        }
        awaitPersistedEffort("low")
        val saved = java.io.File(ui.activity.filesDir, "conversations-v3.json").readText()
        ui.onNodeWithContentDescription("模型与思考设置").performClick()
        ui.onNodeWithTag("reasoning-slider").assertIsDisplayed()
        ui.mainClock.autoAdvance = false
        val rail = ui.onNodeWithTag("reasoning-slider")
        val inset =
            UiTuning.ReasoningThumbRadius.value * ui.activity.resources.displayMetrics.density
        rail.performTouchInput {
            down(Offset(inset, centerY))
            moveTo(Offset(inset + (width - 2 * inset) * 2 / 3f, centerY))
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
        awaitPersistedEffort("max")
        val committed = java.io.File(ui.activity.filesDir, "conversations-v3.json").readText()
        rail.performTouchInput {
            down(Offset(inset + (width - 2 * inset) * 2 / 3f, centerY))
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
        rail.performTouchInput {
            swipe(
                Offset(inset + (width - 2 * inset) * 2 / 3f, centerY),
                Offset(width - inset, centerY),
            )
        }
        ui.mainClock.advanceTimeBy(400)
        ui.onNodeWithTag("effort-title").assertTextEquals("Ultra")
        ui.runOnUiThread { assertEquals("ultra", AssistantState.current.effort) }
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        ui.mainClock.autoAdvance = true
    }
}
