package dev.deepseek.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class V080UiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test
    fun projectSidebarAppearanceAndRuntimeNavigation() {
        ui.waitUntil(30000) { AssistantState.ready && DshaFonts.ready }
        ui.runOnUiThread {
            resetTransientUiForTest()
            AssistantState.appMode = "work"
            val project = AssistantState.addProject("DSHA Android")
            AssistantState.addSession(project.id).title = "终端与运行环境"
            AssistantState.addSession(project.id).title = "界面与阅读体验"
            val notes = AssistantState.addProject("我的笔记")
            AssistantState.addSession(notes.id).title = "收集灵感"
            val chat = AssistantState.addSession()
            chat.title = "新的一天"
            chat.messages.add(
                ChatEntry(
                    "assistant",
                    "### 把想法变成现实\n\n给我一个目标，我们就从这里开始。你可以随时查看 **工作副本**，决定哪些修改合并到项目。\n\n> 工作指令只读，记忆保存事实。\n\n```kotlin\nval idea = \"Make something useful\"\nprintln(idea)\n```",
                ),
            )
        }
        TestSnapshots.capture(ui, "v080-conversation")
        ui.onNodeWithContentDescription("会话列表").performClick()
        TestSnapshots.capture(ui, "v080-sidebar")
        ui.onNodeWithContentDescription("设置").performClick()
        ui.onNodeWithText("外观").assertIsDisplayed()
        TestSnapshots.capture(ui, "v080-settings")
        ui.onNodeWithText("外观").performClick()
        ui.onNodeWithText("字体与排版").performClick()
        ui.onNodeWithText("Anthropic 风格").performScrollTo().performClick()
        TestSnapshots.capture(ui, "v080-fonts")
        ui.onNodeWithContentDescription("返回").performClick()
        ui.onNodeWithContentDescription("返回").performClick()
        ui.onNodeWithText("运行环境").performClick()
        ui.onNodeWithText("打开本地终端").assertIsDisplayed()
        TestSnapshots.capture(ui, "v080-runtime-settings")
        ui.runOnUiThread {
            FontSettings.preset = FontPreset.DEFAULT
            FontSettings.changed()
        }
    }
}
