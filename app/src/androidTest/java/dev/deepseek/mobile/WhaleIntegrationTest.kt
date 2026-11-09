package dev.deepseek.mobile

import android.animation.ValueAnimator
import android.content.Context
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.io.File
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

/** The real conversation and IME, rather than a standalone always-enabled WhaleDock. */
class WhaleIntegrationTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private var fixture: PhoneTask? = null

    private fun ready() {
        ui.waitUntil(30000) { AssistantState.ready && DshaFonts.ready }
        ui.runOnUiThread {
            resetTransientUiForTest()
            AssistantState.appMode = "work"
            AssistantState.petEnabled = true
            AssistantState.activeTaskId = ""
            AssistantState.addSession()
            WhaleActivity.touching = false
            WhaleActivity.interact()
        }
        ui.onNodeWithTag("pet-body").assertExists()
        assertTrue(ValueAnimator.areAnimatorsEnabled())
    }

    @After
    fun cleanup() {
        ui.mainClock.autoAdvance = true
        ui.runOnUiThread {
            fixture?.let { t -> AssistantState.tasks.removeAll { it.id == t.id } }
            AssistantState.activeTaskId = ""
            WhaleActivity.touching = false
            (ui.activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(ui.activity.window.decorView.windowToken, 0)
        }
    }

    private fun ime() =
        ui.activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true

    private fun left() = ui.onNodeWithTag("pet-body").fetchSemanticsNode().boundsInRoot.left

    private fun verifyPatrol(name: String) {
        ui.waitForIdle()
        ui.mainClock.autoAdvance = false
        val home = left()
        ui.mainClock.advanceTimeBy(34000)
        val moved = left()
        val phase =
            ui.onNodeWithTag("pet-body")
                .fetchSemanticsNode()
                .config[SemanticsProperties.StateDescription]
        File(ui.activity.filesDir, "whale-integration-$name.json")
            .writeText(
                JSONObject()
                    .put("home", home)
                    .put("afterIdle", moved)
                    .put("phase", phase)
                    .put("keyboard", ime())
                    .put("busy", AssistantState.busy)
                    .put("touching", WhaleActivity.touching)
                    .put("revision", WhaleActivity.revision)
                    .put("patrolling", WhaleActivity.patrolling)
                    .toString(2),
            )
        assertTrue(
            "Idle whale must travel left in the actual conversation: $name; phase=$phase",
            moved < home - 30,
        )
    }

    @Test
    fun idleConversationPatrolsInRealScreen() {
        ready()
        verifyPatrol("conversation")
    }

    @Test
    fun agentActivityDoesNotBlockIdleCompanionForever() {
        ready()
        ui.runOnUiThread {
            val t =
                PhoneTask(
                    sessionId = AssistantState.current.id,
                    text = "UI-only running-state fixture",
                    images = emptyList(),
                    model = "deepseek-flash",
                    effort = "low",
                    access = "none",
                    phoneTools = false,
                    fileTools = false,
                    workspace = "",
                )
            t.status = "running"
            fixture = t
            AssistantState.tasks.add(t)
            AssistantState.activeTaskId = t.id
        }
        verifyPatrol("agent-busy")
    }

    @Test
    fun keyboardCanStayOpenAfterTheInitialReturnHome() {
        ready()
        ui.onNodeWithTag("message-input").performClick().performTextInput("输入法巡游测试")
        ui.waitUntil(15000) { ime() }
        verifyPatrol("keyboard")
    }
}
