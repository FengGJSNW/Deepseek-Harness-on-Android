package dev.deepseek.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.deepseek.mobile.terminal.*
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Authorized API on emulator-5582 only; the model may inspect identity and run id -u. */
class LiveTerminalPrivilegeTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test
    fun realAgentUsesVerifiedShizukuTerminal() {
        ui.waitUntil(30000) { AssistantState.ready && AgentService.current != null }
        val service = AgentService.current!!
        val keys = KeyStore(ui.activity)
        val previous = keys.list().firstOrNull { it.selected }?.id
        val input = File(ui.activity.filesDir, "test-key")
        check(input.isFile)
        val id = keys.save(input.readText().trim(), "临时073权限验收")
        input.delete()
        val browser = ExperienceSettings.browserEnabled
        try {
            ui.runOnUiThread {
                resetTransientUiForTest()
                AssistantState.appMode = "work"
                ExperienceSettings.browserEnabled = false
                AssistantState.addSession().apply {
                    title = "Shizuku Agent 只读验收"
                    model = "deepseek-flash"
                    effort = "low"
                    access = "full"
                    phoneTools = false
                    fileTools = false
                    terminalEnabled = true
                    terminalLevel = "shell"
                }
                service.terminal.connections.connectShell()
            }
            ui.waitUntil(15000) { service.terminal.connections.shellState.ready }
            ui.onNodeWithTag("message-input")
                .performTextInput(
                    "这是只读终端身份验收。先调用 terminal_status(probe=true)，再调用且只调用一次 terminal_exec，command 为 id -u、cwd 为 /。根据工具结果说明实际UID。不要运行其他命令，不要写记忆，不要打开网页或派生智能体。",
                )
            ui.onNodeWithTag("composer-send").performClick()
            ui.waitUntil(120000) {
                AssistantState.tasks.lastOrNull()?.status in
                    setOf("done", "error", "failed", "cancelled", "interrupted") &&
                    !AssistantState.busy
            }
            val task = AssistantState.tasks.last()
            assertEquals("done", task.status)
            assertTrue(task.tokens > 0)
            val runs = service.terminal.runs.values.filter { it.taskId == task.id }
            assertEquals(1, runs.size)
            val run = runs.single()
            assertEquals("id -u", run.command.trim())
            assertEquals("/", run.cwd)
            assertEquals(2000, run.verifiedUid)
            assertEquals(0, run.exitCode)
            assertEquals("2000", run.output.trim())
            val replies =
                AssistantState.current.messages
                    .filter { it.type == "assistant" }
                    .joinToString("\n") { it.text }
            assertTrue(replies.contains("2000"))
            File(ui.activity.filesDir, "v073-live-shizuku.json")
                .writeText(
                    JSONObject()
                        .put("passed", true)
                        .put("task", task.id)
                        .put("tokens", task.tokens)
                        .put("model", task.model)
                        .put("effort", task.effort)
                        .put("runs", JSONArray(runs.map { it.json() }))
                        .put("reply", replies)
                        .toString(2),
                )
        } finally {
            keys.delete(id)
            previous?.let { keys.select(it) }
            input.delete()
            ui.runOnUiThread {
                ExperienceSettings.browserEnabled = browser
                AssistantState.configured = keys.configured()
            }
        }
    }
}
