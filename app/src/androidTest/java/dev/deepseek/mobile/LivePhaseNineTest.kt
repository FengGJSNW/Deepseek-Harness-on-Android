package dev.deepseek.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Uses only the explicitly authorized temporary API key on the dedicated emulator. */
class LivePhaseNineTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test
    fun composerSendsWithStaleUiStatusWithoutOpeningSettings() {
        ui.waitUntil(30000) { AssistantState.ready && AgentService.current != null }
        val store = KeyStore(ui.activity)
        val previous = store.list().firstOrNull { it.selected }?.id
        val input = File(ui.activity.filesDir, "test-key")
        require(input.isFile)
        val key = store.save(input.readText().trim(), "临时072发送验收")
        input.delete()
        try {
            ui.runOnUiThread {
                AssistantState.appMode = "work"
                AssistantState.activeTaskId = ""
                AssistantState.addSession().apply {
                    model = "deepseek-flash"
                    effort = "low"
                    access = "none"
                    phoneTools = false
                    fileTools = false
                    terminalEnabled = false
                }
                repeat(18) { i ->
                    AssistantState.current.messages.add(ChatEntry("user", "旧任务$i"))
                    AssistantState.current.messages.add(ChatEntry("assistant", "旧消息内容。".repeat(30)))
                }
                AssistantState.configured = false
            }
            ui.onNodeWithTag("task-navigation-rail").performSemanticsAction(
                androidx.compose.ui.semantics.SemanticsActions.SetProgress,
            ) {
                it(0f)
            }
            ui.onNodeWithContentDescription("关闭任务预览").performClick()
            ui.onNodeWithText("旧任务0").assertIsDisplayed()
            ui.onNodeWithTag("message-input").performTextInput("仅回复：0.7.2 发送验收成功")
            ui.onNodeWithTag("composer-send").performClick()
            ui.onNodeWithText("仅回复：0.7.2 发送验收成功").assertIsDisplayed()
            ui.onNodeWithTag("settings-screen").assertDoesNotExist()
            ui.onNodeWithTag("center-island").assertDoesNotExist()
            ui.waitUntil(120000) {
                AssistantState.tasks.lastOrNull()?.status in
                    listOf("done", "error", "failed", "cancelled", "interrupted") &&
                    !AssistantState.busy
            }
            val task = AssistantState.tasks.last()
            assertEquals("done", task.status)
            assertTrue(task.tokens > 0)
            assertTrue(
                AssistantState.current.messages
                    .last { it.type == "assistant" }
                    .text
                    .contains("发送验收成功"),
            )
            assertEquals("", AssistantState.current.draft)
            ui.onNodeWithTag("settings-screen").assertDoesNotExist()
            File(ui.activity.filesDir, "v072-live-send.json")
                .writeText(
                    JSONObject()
                        .put("passed", true)
                        .put("model", task.model)
                        .put("effort", task.effort)
                        .put("tokens", task.tokens)
                        .put(
                            "reply",
                            AssistantState.current.messages.last { it.type == "assistant" }.text,
                        )
                        .put("staleUiStatus", false)
                        .put("openedSettings", false)
                        .toString(2),
                )
            TestSnapshots.capture(ui, "v072-live-send")
        } finally {
            store.delete(key)
            previous?.let { store.select(it) }
            input.delete()
            ui.runOnUiThread { AssistantState.configured = store.configured() }
        }
    }
}
