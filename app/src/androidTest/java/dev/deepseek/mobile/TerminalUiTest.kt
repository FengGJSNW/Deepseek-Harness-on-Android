package dev.deepseek.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.deepseek.mobile.terminal.TerminalRun
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Offline UI fixtures; no API calls, permissions, system gestures or real command execution. */
class TerminalUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private fun screenshot(name: String) = TestSnapshots.capture(ui, name)

    @Test
    fun terminalCardAndConfigEditor() {
        ui.waitUntil(30000) { AssistantState.ready }
        ui.runOnUiThread {
            resetTransientUiForTest()
            AssistantState.appMode = "work"
            val c = AssistantState.addSession()
            c.title = "鲸鲸的工作台"
            c.terminalEnabled = true
            c.messages.add(ChatEntry("user", "把今日计划保存到当前会话的工作区。"))
            val run =
                TerminalRun(
                    taskId = "ui-only",
                    sessionId = c.id,
                    command =
                        "mkdir -p notes; echo '今天也要好好生活。' > notes/today.txt; cat notes/today.txt",
                    cwd = "/dev.deepseek.mobile/workplace/session_${c.id}",
                    level = "isolated",
                )
            run.status = "completed"
            run.exitCode = 0
            run.output = "今天也要好好生活。\n"
            run.reason = "命令检查通过 · 独立审查允许"
            AgentService.current!!.terminal.runs[run.id] = run
            c.messages.add(ChatEntry("terminal", run.id))
            c.messages.add(ChatEntry("assistant", "已保存到 `notes/today.txt`，你可以随时接着补充。"))
        }
        ui.onNodeWithTag("work-details-toggle").performClick()
        ui.onNodeWithTag("tool-group-toggle").performClick()
        ui.onNodeWithText("mkdir -p notes; echo '今天也要好好生活。' > notes/today.txt; cat notes/today.txt")
            .performClick()
        ui.onNodeWithText("exit 0", substring = true, useUnmergedTree = true).assertExists()
        screenshot("terminal-chat")
        ui.onNodeWithContentDescription("会话列表").performClick()
        ui.onNodeWithContentDescription("设置").performClick()
        ui.onNodeWithText("手机权限").performScrollTo().performClick()
        ui.onNodeWithText("执行权限与工具").performClick()
        ui.onNodeWithText("终端运行环境").performScrollTo().performClick()
        ui.onNodeWithText("运行环境").assertExists()
        screenshot("terminal-environments")
        ui.onNodeWithText("命令 JSON").performClick()
        ui.onNodeWithText("校验并保存").performScrollTo().assertExists()
        screenshot("terminal-policy-editor")
    }

    @Test
    fun terminalApprovalHasAllThreeDecisions() {
        ui.waitUntil(30000) { AssistantState.ready }
        ui.runOnUiThread {
            resetTransientUiForTest()
            AssistantState.appMode = "work"
            val c = AssistantState.addSession()
            val task =
                PhoneTask(
                    sessionId = c.id,
                    text = "fixture",
                    images = emptyList(),
                    model = "deepseek-flash",
                    effort = "off",
                    access = "ask",
                    phoneTools = false,
                    fileTools = false,
                    workspace = "",
                )
            task.status = "waiting"
            task.approval = true
            task.terminalApproval = true
            task.question = "隔离工作区\n$ rm notes/draft.txt\n删除文件需要批准；仅影响当前会话。"
            AssistantState.tasks.add(task)
            AssistantState.activeTaskId = task.id
        }
        try {
            ui.onNodeWithText("批准终端命令").assertIsDisplayed()
            ui.onAllNodesWithText("拒绝").onLast().assertIsDisplayed()
            ui.onAllNodesWithText("允许本次命令").onLast().assertIsDisplayed()
            ui.onAllNodesWithText("允许类似命令").onLast().assertIsDisplayed()
            screenshot("terminal-approval")
        } finally {
            ui.runOnUiThread {
                AssistantState.running?.let {
                    it.terminalApproval = false
                    it.approval = false
                    it.status = "cancelled"
                }
                AssistantState.activeTaskId = ""
            }
        }
    }
}
