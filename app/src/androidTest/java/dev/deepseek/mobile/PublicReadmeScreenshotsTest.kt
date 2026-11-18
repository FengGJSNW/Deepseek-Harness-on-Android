package dev.deepseek.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

/** Creates an explicitly fictional conversation for public README screenshots. */
class PublicReadmeScreenshotsTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun capturePublicScreens() {
        ui.waitUntil(60000) { AssistantState.ready && DshaFonts.ready }
        ui.runOnUiThread {
            resetTransientUiForTest()
            AssistantState.appMode = "work"
            AssistantState.petEnabled = false
            FontSettings.preset = FontPreset.ANTHROPIC
            FontSettings.changed()
            val chat = AssistantState.addSession("")
            chat.title = "一杯咖啡的灵感"
            chat.messages.addAll(listOf(
                ChatEntry("user", "帮我为周末咖啡时光写一份简短计划。"),
                ChatEntry("assistant", """## 给自己一点时间

从一杯喜欢的咖啡开始，让周末慢下来。

1. **挑选咖啡**：试试带有坚果香气的浅烘豆。
2. **准备角落**：打开窗，让自然光照进来。
3. **记录灵感**：读几页书，写下一个新想法。

> 最好的计划，是给生活留一点空白。

愿你度过一个温柔的周末。"""),
            ))
        }
        ui.onNodeWithText("给自己一点时间", substring = true).assertExists()
        TestSnapshots.capture(ui, "public-conversation")
        ui.onNodeWithContentDescription("会话列表").performClick()
        ui.onNodeWithText("Sandbox").assertIsDisplayed()
        TestSnapshots.capture(ui, "public-drawer")
        ui.onNodeWithText("Sandbox").performClick()
        ui.waitForIdle()
        TestSnapshots.capture(ui, "public-sandbox")
    }
}
