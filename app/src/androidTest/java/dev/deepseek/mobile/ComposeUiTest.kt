package dev.deepseek.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ComposeUiTest {
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
    fun nativeSessionsToolbarAndSettings() {
        ui.waitUntil(30000) { AssistantState.ready }
        val title = "设计测试甲-${System.currentTimeMillis()}"
        ui.runOnUiThread {
            AssistantState.addSession().title = title
            AssistantState.petEnabled = true
            AssistantState.petStyle = "ohjing"
            AssistantState.theme = "official"
            WhaleCompanion.touches = 0
            WhaleCompanion.resting = false
            WhaleCompanion.settle()
        }
        ui.onNodeWithText("今天，想做点什么？").assertIsDisplayed()
        screen("v033-home")
        ui.mainClock.autoAdvance = false
        val pet = ui.onNodeWithContentDescription("哦鲸鲸，点击互动，长按打开小窝，可拖动")
        pet.performClick()
        ui.mainClock.advanceTimeBy(100)
        ui.onNodeWithText("哦鲸鲸… 再摸一下？").assertIsDisplayed()
        screen("v033-pet-bounce")
        ui.mainClock.advanceTimeBy(600)
        screen("v033-pet-bubble")
        pet.performClick()
        ui.mainClock.advanceTimeBy(100)
        ui.onNodeWithText("收到摸摸！今天也会认真干活。").assertIsDisplayed()
        ui.mainClock.advanceTimeBy(7200)
        ui.onNodeWithText("收到摸摸！今天也会认真干活。").assertDoesNotExist()
        ui.mainClock.autoAdvance = true
        pet.performTouchInput { longClick() }
        ui.onNodeWithText("鲸鲸的小窝").assertIsDisplayed()
        ui.onNodeWithText("动作预览").performScrollTo().performClick()
        ui.runOnUiThread { assertEquals("animated", AssistantState.petStyle) }
        ui.onNodeWithText("喂一碗饭").performScrollTo().performClick()
        ui.runOnUiThread { assertEquals(R.drawable.meme_rice, WhaleCompanion.sticker) }
        screen("v033-pet-home")
        Thread.sleep(420)
        screen("v033-pet-frame-b")
        ui.onNodeWithText("深度睡眠").performScrollTo()
        screen("v033-sticker-collection")
        ui.onNodeWithText("打个招呼").performScrollTo().performClick()
        ui.runOnUiThread { assertEquals(R.drawable.meme_ciallo, WhaleCompanion.sticker) }
        ui.onNodeWithText("收起").performScrollTo().performClick()
        screen("v033-animated-a")
        Thread.sleep(420)
        screen("v033-animated-b")
        ui.runOnUiThread { WhaleCompanion.react("think", "让我想想，这件事怎么做。") }
        screen("v033-thinking")
        ui.runOnUiThread { WhaleCompanion.settle() }

        ui.onNodeWithText("去连接").performTouchInput { click() }
        ui.onNodeWithText("DeepSeek API Key").assertIsDisplayed()
        ui.onNodeWithContentDescription("返回").performClick()
        ui.onNodeWithText("给鲸鲸发送消息").performTextInput("保存到甲的草稿")
        ui.runOnUiThread {
            java.io
                .File(ui.activity.filesDir, "v033-session-diagnostic.txt")
                .writeText(
                    "before new: selected=" +
                        AssistantState.selectedId +
                        "\n" +
                        AssistantState.sessions.joinToString("\n") {
                            it.id + " | " + it.title + " | " + it.draft
                        },
                )
        }
        screen("v033-draft-written")
        ui.onNodeWithContentDescription("新对话").performClick()
        ui.onNodeWithText("给鲸鲸发送消息").assertIsDisplayed()
        ui.onNodeWithContentDescription("会话列表").performClick()
        screen("v033-drawer")
        ui.runOnUiThread {
            java.io
                .File(ui.activity.filesDir, "v033-session-diagnostic.txt")
                .appendText(
                    "\nafter new: selected=" +
                        AssistantState.selectedId +
                        "\n" +
                        AssistantState.sessions.joinToString("\n") {
                            it.id + " | " + it.title + " | " + it.draft
                        },
                )
        }
        ui.onNodeWithText(title).performClick()
        ui.waitForIdle()
        screen("v033-session-return")
        ui.runOnUiThread { assertEquals("保存到甲的草稿", AssistantState.current.draft) }
        ui.onNodeWithText("保存到甲的草稿").assertIsDisplayed()
        ui.onNodeWithContentDescription("工具与工作区").performClick()
        ui.onNodeWithText("手机无障碍工具").assertIsDisplayed()
        screen("v033-tools")
        ui.onNodeWithText("完成").performClick()
        ui.onNodeWithText("Flash").performClick()
        ui.onNodeWithTag("reasoning-slider").performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.SetProgress,
        ) {
            it(3f)
        }
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        ui.onNodeWithText("Flash · 高").assertIsDisplayed()
        ui.onNodeWithTag("model-control", useUnmergedTree = true).assertHeightIsEqualTo(44.dp)
        val glyphs =
            listOf(
                "toolbar-Add",
                "toolbar-Shield",
                "toolbar-Hub",
                "toolbar-MicNone",
                "toolbar-ArrowUpward",
            )
        val glyphNodes = glyphs.map { tag ->
            ui.onNodeWithTag(tag, useUnmergedTree = true)
                .assertWidthIsEqualTo(22.dp)
                .assertHeightIsEqualTo(22.dp)
                .fetchSemanticsNode()
        }
        ui.runOnUiThread {
            val bounds = glyphNodes.map { it.boundsInRoot }
            bounds.forEach { assertEquals(bounds.first().center.y, it.center.y, 1f) }
        }
        ui.onNodeWithText("确认").performClick()
        ui.onNodeWithText("只读 · 观察与读取，不操作手机").performClick()
        ui.onNodeWithText("完成").performClick()
        ui.onNodeWithText("只读").assertIsDisplayed()
        ui.onNodeWithContentDescription("添加附件").performClick()
        ui.onNodeWithText("图片").assertIsDisplayed()
        ui.onNodeWithText("完成").performClick()
        ui.activityRule.scenario.recreate()
        ui.waitForIdle()
        ui.onNodeWithText("保存到甲的草稿").assertIsDisplayed()
        ui.onNodeWithContentDescription("会话列表").performClick()
        ui.onNodeWithText("设置与个性化").performClick()
        ui.onNodeWithText("DeepSeek API Key").assertIsDisplayed()
        screen("v033-settings")
        ui.onNodeWithText("粘贴 API Key").performClick().performTextInput("local-ui-test-value")
        ui.onAllNodes(hasSetTextAction())
            .onFirst()
            .assert(
                SemanticsMatcher.keyIsDefined(
                    androidx.compose.ui.semantics.SemanticsProperties.Password,
                ),
            )
        ui.onNodeWithText("保存密钥").performScrollTo().assertIsDisplayed()
        screen("v033-keyboard")
        ui.onNodeWithContentDescription("返回").performClick()
        ui.runOnUiThread {
            assertNull(AgentService.current!!.webView.parent)
            AssistantState.current.messages.add(ChatEntry("assistant", "我在呢。聊个想法，或交给我一件事。"))
            AssistantState.current.draft = ""
            WhaleCompanion.react("idle", "哦鲸鲸…")
            (ui.activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(ui.activity.window.decorView.windowToken, 0)
        }
        Thread.sleep(350)
        screen("v033-chat-avatar")
    }
}
