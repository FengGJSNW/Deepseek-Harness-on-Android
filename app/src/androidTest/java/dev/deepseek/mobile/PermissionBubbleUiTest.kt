package dev.deepseek.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PermissionBubbleUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test
    fun variablesRetainTheirOwnRanges() {
        ui.waitUntil(30000) { AssistantState.ready }
        ui.runOnUiThread {
            resetTransientUiForTest()
            AssistantState.appMode = "work"
            AssistantState.addSession().apply {
                phoneTools = false
                terminalEnabled = false
            }
        }
        ui.runOnUiThread {
            WhaleCompanion.reactTemplate("happy", "剩余额度 {limit}\n今天用了 {cost}\n陪你聊了 {sessions} 个会话")
        }
        ui.onNodeWithText("剩余额度", substring = true).assertExists()
        val text = WhaleCompanion.styledText
        assertEquals(3, text.getStringAnnotations("placeholder", 0, text.length).size)
        assertEquals(3, text.spanStyles.size)
        assertEquals(WhaleBubbleValue, text.spanStyles[0].item.color)
        assertEquals(WhaleBubbleCost, text.spanStyles[1].item.color)
        assertTrue(text.spanStyles.all { it.item.fontWeight == FontWeight.ExtraBold })
        TestSnapshots.capture(ui, "v073-whale-bubble")
    }

    @Test
    fun disconnectedShellDoesNotTrapTheTerminalSelector() {
        ui.waitUntil(30000) { AssistantState.ready }
        ui.runOnUiThread {
            resetTransientUiForTest()
            AssistantState.appMode = "work"
            AssistantState.addSession().apply {
                phoneTools = false
                terminalEnabled = true
                terminalLevel = "shell"
            }
            AgentService.current!!.terminal.connections.disconnect(false)
        }
        ui.onNodeWithTag("toolbar-Terminal", useUnmergedTree = true).performClick()
        ui.onNodeWithText("应用用户").assertIsDisplayed().performClick()
        ui.runOnIdle { assertEquals("app", AssistantState.current.terminalLevel) }
        ui.onNodeWithTag("toolbar-Terminal", useUnmergedTree = true).performClick()
        ui.onNodeWithText("关闭终端").performClick()
        ui.runOnIdle { assertFalse(AssistantState.current.terminalEnabled) }
        TestSnapshots.capture(ui, "v073-terminal-selector-recovered")
    }
}
