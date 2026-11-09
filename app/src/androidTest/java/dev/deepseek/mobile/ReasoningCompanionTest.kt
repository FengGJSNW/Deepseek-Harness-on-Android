package dev.deepseek.mobile

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReasoningCompanionTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private fun screen(name: String) {
        ui.waitForIdle()
        ui.runOnUiThread {
            val view =
                android.view.inspector.WindowInspector.getGlobalWindowViews().lastOrNull {
                    it.isShown && it.width > 0
                } ?: ui.activity.window.decorView
            val bitmap =
                android.graphics.Bitmap.createBitmap(
                    view.width,
                    view.height,
                    android.graphics.Bitmap.Config.ARGB_8888,
                )
            view.draw(android.graphics.Canvas(bitmap))
            java.io.File(ui.activity.filesDir, "$name.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    @Test
    fun modelAndEffortShareOnePopover() {
        ui.waitUntil(30000) { AssistantState.ready }
        ui.runOnUiThread {
            val c = AssistantState.addSession()
            c.effort = "max"
            AssistantState.petEnabled = false
        }
        ui.onNodeWithContentDescription("模型与思考设置").performClick()
        ui.onNodeWithTag("reasoning-panel").assertIsDisplayed()
        ui.onNodeWithTag("effort-title").assertTextEquals("Max")
        screen("v034-reasoning-max")
        ui.mainClock.autoAdvance = false
        ui.onNodeWithTag("reasoning-slider").performSemanticsAction(SemanticsActions.SetProgress) {
            it(4f)
        }
        ui.mainClock.advanceTimeBy(550)
        ui.onNodeWithTag("effort-title").assertTextEquals("Ultra")
        screen("v034-reasoning-ultra")
        ui.mainClock.advanceTimeBy(1000)
        screen("v034-reasoning-ultra-b")
        ui.mainClock.autoAdvance = true
        ui.onNodeWithContentDescription("选择模型").performClick()
        ui.onNodeWithText("DeepSeek Pro").performClick()
        ui.runOnUiThread {
            assertEquals("deepseek-v4-pro", AssistantState.current.model)
            assertEquals("ultra", AssistantState.current.effort)
        }
        // A real swipe selects the low end, then a tap selects Ultra again.
        ui.onNodeWithTag("reasoning-slider").performTouchInput { swipeLeft() }
        ui.runOnUiThread { assertTrue(AssistantState.current.effort in listOf("low")) }
        ui.onNodeWithTag("reasoning-slider").performTouchInput { click(centerRight) }
        ui.runOnUiThread { assertEquals("ultra", AssistantState.current.effort) }
        ui.onNodeWithContentDescription("选择模型").performClick()
        screen("v034-models-inline")
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        ui.onNodeWithTag("reasoning-panel").assertDoesNotExist()
        ui.onNodeWithContentDescription("新对话").performClick()
        ui.runOnUiThread { assertEquals("low", AssistantState.current.effort) }
        ui.onNodeWithContentDescription("模型与思考设置").performClick()
        ui.onNodeWithTag("reasoning-slider").performSemanticsAction(SemanticsActions.SetProgress) {
            it(1f)
        }
        ui.runOnUiThread { assertEquals("medium", AssistantState.current.effort) }
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        ui.activityRule.scenario.recreate()
        ui.runOnUiThread {
            assertEquals("medium", AssistantState.current.effort)
            val data =
                org.json.JSONObject(
                    java.io.File(ui.activity.filesDir, "conversations-v3.json").readText(),
                )
            val sessions = data.getJSONArray("sessions")
            val match =
                (0 until sessions.length())
                    .map { sessions.getJSONObject(it) }
                    .first { it.getString("id") == AssistantState.selectedId }
            assertEquals("medium", match.getString("effort"))
        }
    }

    @Test
    fun companionUsesCachedBalanceAndCurrentConversation() {
        ui.waitUntil(30000) { AssistantState.ready }
        var configured = false
        ui.runOnUiThread {
            configured = AssistantState.configured
            val c = AssistantState.addSession()
            c.model = "deepseek-v4-pro"
            c.effort = "ultra"
            AssistantState.configured = true
            AssistantState.balance = "CNY 12.34"
            AssistantState.balanceUpdatedAt = android.os.SystemClock.elapsedRealtime()
            AssistantState.petEnabled = true
            WhaleCompanion.touches = 0
            WhaleCompanion.settle()
        }
        try {
            ui.runOnUiThread {
                (ui.activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                        as android.view.inputmethod.InputMethodManager)
                    .hideSoftInputFromWindow(ui.activity.window.decorView.windowToken, 0)
            }
            ui.waitUntil(5000) {
                val insets = ui.activity.window.decorView.rootWindowInsets
                insets == null ||
                    (!insets.isVisible(android.view.WindowInsets.Type.ime()) &&
                        insets.getInsets(android.view.WindowInsets.Type.ime()).bottom == 0)
            }
            ui.waitForIdle()
            val pet = ui.onNodeWithContentDescription("哦鲸鲸，点击互动，长按打开小窝，可拖动").assertIsDisplayed()
            ui.mainClock.autoAdvance = false
            pet.performClick()
            ui.mainClock.advanceTimeBy(550)
            ui.runOnUiThread { assertTrue(WhaleCompanion.canUseFlash) }
            screen("v034-pet-before-credit-assert")
            val petNode = pet.fetchSemanticsNode()
            ui.runOnUiThread {
                java.io
                    .File(ui.activity.filesDir, "v034-pet-window.txt")
                    .writeText(
                        "pet=" +
                            petNode.boundsInRoot +
                            " view=" +
                            ui.activity.window.decorView.width +
                            "x" +
                            ui.activity.window.decorView.height +
                            " ime=" +
                            ui.activity.window.decorView.rootWindowInsets
                                .getInsets(android.view.WindowInsets.Type.ime())
                                .bottom +
                            " config=" +
                            ui.activity.resources.configuration.screenWidthDp +
                            "x" +
                            ui.activity.resources.configuration.screenHeightDp,
                    )
            }
            val creditNode = ui.onNodeWithText("剩余额度").fetchSemanticsNode()
            val amountNode = ui.onNodeWithText("CNY 12.34").fetchSemanticsNode()
            ui.runOnUiThread {
                java.io
                    .File(ui.activity.filesDir, "v034-credit-layout.txt")
                    .writeText(
                        "credit=" +
                            creditNode.boundsInRoot +
                            " amount=" +
                            amountNode.boundsInRoot +
                            " view=" +
                            ui.activity.window.decorView.width +
                            "x" +
                            ui.activity.window.decorView.height +
                            " ime=" +
                            ui.activity.window.decorView.rootWindowInsets
                                .getInsets(android.view.WindowInsets.Type.ime())
                                .bottom,
                    )
            }
            screen("v034-pet-before-credit-assert")
            ui.onNodeWithText("穷就别用", substring = true).assertIsDisplayed()
            ui.onNodeWithText("剩余额度").assertIsDisplayed()
            ui.onNodeWithText("CNY 12.34").assertIsDisplayed()
            screen("v034-pet-pro-balance")
            val action = ui.onNodeWithTag("pet-flash").assertIsDisplayed()
            val actionNode = action.fetchSemanticsNode()
            ui.runOnUiThread {
                java.io
                    .File(ui.activity.filesDir, "v034-flash-bounds.txt")
                    .writeText(
                        "flash=" +
                            actionNode.boundsInRoot +
                            ";canUse=" +
                            WhaleCompanion.canUseFlash,
                    )
            }
            action.performClick()
            ui.mainClock.advanceTimeBy(50)
            ui.runOnUiThread {
                assertEquals("deepseek-flash", AssistantState.current.model)
                assertEquals("ultra", AssistantState.current.effort)
            }
            ui.onNodeWithText("换好 Flash", substring = true).assertIsDisplayed()
            ui.mainClock.autoAdvance = true
            ui.runOnUiThread { AssistantState.current.draft = "测试草稿" }
            pet.performClick()
            ui.onNodeWithText("草稿都备好啦", substring = true).assertIsDisplayed()
            ui.runOnUiThread { WhaleCompanion.settle() }
            pet.performTouchInput { longClick() }
            ui.onNodeWithContentDescription("点击音效开关").performScrollTo().performClick()
            ui.runOnUiThread {
                assertFalse(AssistantState.petSoundEnabled)
                assertEquals(0, WhaleSounds.press())
            }
            ui.onNodeWithContentDescription("点击音效开关").performClick()
            ui.runOnUiThread { assertTrue(AssistantState.petSoundEnabled) }
            ui.waitUntil(5000) { WhaleSounds.isReady }
            screen("v034-pet-sound-settings")
            ui.onNodeWithText("收起").performScrollTo().performClick()
        } finally {
            ui.mainClock.autoAdvance = true
            ui.runOnUiThread {
                AssistantState.configured = configured
                AssistantState.balance = "未查询"
                AssistantState.balanceUpdatedAt = 0L
                AssistantState.petEnabled = true
                AssistantState.petSoundEnabled = true
                AssistantState.persist()
                WhaleCompanion.settle()
            }
        }
    }
}
