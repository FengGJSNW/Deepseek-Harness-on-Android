package dev.deepseek.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Emulator with an already authorized, ADB-started Shizuku service. No Root request. */
class TerminalConnectionUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test
    fun selectingShellConnectsAndShowsActualIdentity() {
        ui.waitUntil(30000) { AssistantState.ready }
        ui.runOnUiThread {
            resetTransientUiForTest()
            AssistantState.appMode = "work"
            AssistantState.error = ""
            AssistantState.addSession().apply {
                title = "终端连接验收"
                terminalEnabled = true
                terminalLevel = "isolated"
            }
        }
        ui.onNodeWithContentDescription("会话列表").performClick()
        ui.onNodeWithContentDescription("设置").performClick()
        ui.onNodeWithText("手机权限").performScrollTo().performClick()
        ui.onNodeWithText("执行权限与工具").performClick()
        ui.onNodeWithText("终端运行环境").performScrollTo().performClick()
        ui.onNodeWithText("Shizuku Shell").performScrollTo().performClick()
        ui.waitUntil(15000) { AgentService.current!!.terminal.connections.shellState.ready }
        assertEquals(2000, AgentService.current!!.terminal.connections.shellState.uid)
        assertEquals(
            "No Toast should accompany a successful Shizuku authorization",
            "",
            AssistantState.error,
        )
        ui.onNodeWithText("身份已验证 · UID 2000").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("只读身份诊断").performScrollTo().performClick()
        ui.waitUntil(15000) {
            ui.onAllNodesWithText("uid=2000(shell)", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        ui.onNodeWithText("uid=2000(shell)", substring = true).performScrollTo().assertIsDisplayed()
        TestSnapshots.capture(ui, "v073-shell-connected")
    }
}
