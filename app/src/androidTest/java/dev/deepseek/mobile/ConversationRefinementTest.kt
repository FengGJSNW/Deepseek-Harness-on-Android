package dev.deepseek.mobile

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ConversationRefinementTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private fun ready() {
        ui.waitUntil(30000) { AssistantState.ready && AgentService.current != null }
        ui.runOnUiThread {
            resetTransientUiForTest()
            AssistantState.appMode = "work"
            AssistantState.activeTaskId = ""
            AssistantState.addSession()
            OfficialChatNavigation.cancelLogin()
            WhaleCompanion.settle()
            ExperienceSettings.taskNavigation = true
        }
    }

    @Test
    fun queuedRepliesStayWithTheirPromptAndTaskIdsPersist() {
        ready()
        val values =
            listOf(
                ChatEntry("user", "任务一", taskId = "first"),
                ChatEntry("user", "任务二", taskId = "second"),
                ChatEntry("assistant", "第一份结果", taskId = "first"),
                ChatEntry("reasoning", "第二段思考", taskId = "second"),
                ChatEntry("assistant", "第二份结果", taskId = "second"),
            )
        val groups = conversationGroups(values)
        assertEquals(listOf("任务一", "第一份结果", "任务二", "第二段思考"), groups.map { it.first().text })
        val followups =
            conversationGroups(
                listOf(
                    ChatEntry("user", "初始请求", taskId = "followup"),
                    ChatEntry("user", "补充说明", taskId = "followup"),
                    ChatEntry("assistant", "补充后的结果", taskId = "followup"),
                ),
            )
        assertEquals(2, followups.size)
        assertEquals(listOf("补充说明", "补充后的结果"), followups[1].map { it.text })
        val keys = conversationGroupKeys(followups)
        assertEquals(keys.size, keys.distinct().size)
        val id = AssistantState.selectedId
        ui.runOnUiThread {
            AssistantState.current.messages.addAll(values)
            AssistantState.persist()
        }
        AssistantState.awaitPersistence()
        val root =
            JSONObject(File(ui.activity.filesDir, "conversations-v3.json").readText())
                .getJSONArray("sessions")
        val record =
            (0 until root.length())
                .map { root.getJSONObject(it) }
                .first { it.getString("id") == id }
        assertEquals("first", record.getJSONArray("messages").getJSONObject(0).getString("taskId"))
        assertEquals("second", record.getJSONArray("messages").getJSONObject(4).getString("taskId"))
        ui.runOnUiThread {
            AssistantState.current.messages.clear()
            AssistantState.current.messages.addAll(followups.flatten())
        }
        ui.onNodeWithText("补充后的结果").assertIsDisplayed()
        assertEquals(1, workTaskAnchors(AssistantState.current, followups).size)
    }

    @Test
    fun completionCollapsesWorkAndKeepsTheFinalReportVisible() {
        ready()
        val c = AssistantState.current
        val task =
            PhoneTask(
                sessionId = c.id,
                text = "整理任务",
                images = emptyList(),
                model = c.model,
                effort = c.effort,
                access = "none",
                phoneTools = false,
                fileTools = false,
                workspace = "",
            )
        ui.runOnUiThread {
            task.status = "running"
            AssistantState.tasks.add(task)
            c.messages.addAll(
                listOf(
                    ChatEntry("user", "整理任务", taskId = task.id),
                    ChatEntry("reasoning", "需要检查材料", taskId = task.id),
                    ChatEntry("assistant", "中间进度", taskId = task.id),
                    ChatEntry(
                        "tool",
                        "{\"name\":\"workspace_read\",\"status\":\"done\"}",
                        taskId = task.id,
                    ),
                    ChatEntry("stream_assistant", "最终回报", taskId = task.id),
                ),
            )
        }
        ui.onNodeWithText("中间进度").assertExists()
        ui.onNodeWithTag("work-details-toggle").assertDoesNotExist()
        ui.runOnUiThread {
            c.messages[c.messages.lastIndex] =
                c.messages
                    .last()
                    .copy(text = (1..40).joinToString("\n\n") { "流式内容 $it" } + "\n\n流式尾标记")
        }
        ui.onNodeWithText("流式尾标记").assertIsDisplayed()
        ui.runOnUiThread {
            c.messages[c.messages.lastIndex] =
                c.messages.last().copy(type = "assistant", text = "最终回报")
            task.status = "done"
        }
        ui.onNodeWithText("最终回报").assertIsDisplayed()
        ui.onNodeWithText("中间进度").assertDoesNotExist()
        ui.onNodeWithText("查看思考").assertDoesNotExist()
        TestSnapshots.capture(ui, "v072-work-collapsed")
        ui.onNodeWithTag("work-details-toggle").performClick()
        ui.onNodeWithText("中间进度").assertExists()
        ui.onNodeWithText("查看思考").performClick()
        ui.onNodeWithText("需要检查材料").assertIsDisplayed()
        ui.onNodeWithContentDescription("关闭面板").performClick()
        ui.onNodeWithTag("work-details-toggle").performClick()
        ui.onNodeWithText("中间进度").assertDoesNotExist()
    }

    @Test
    fun typographyChangesIndependentlyAndPreviewUpdatesWithoutLeavingSettings() {
        ready()
        val oldSize = ExperienceSettings.textScale
        val oldSpacing = ExperienceSettings.lineSpacing
        try {
            ui.runOnUiThread {
                ExperienceSettings.textScale = 1f
                ExperienceSettings.lineSpacing = 1.4f
            }
            ui.onNodeWithContentDescription("会话列表").performClick()
            ui.onNodeWithContentDescription("设置").performClick()
            ui.onNodeWithText("显示").performClick()
            ui.onNodeWithText("字体与排版").performClick()
            ui.onNodeWithTag("typography-preview").assertIsDisplayed()
            val before = ui.onNodeWithText("构建你的想法").fetchSemanticsNode().boundsInRoot.height
            ui.onNodeWithTag("typography-font-slider").performSemanticsAction(
                SemanticsActions.SetProgress,
            ) {
                it(1.25f)
            }
            assertEquals(1.4f, ExperienceSettings.lineSpacing, .001f)
            val larger = ui.onNodeWithText("构建你的想法").fetchSemanticsNode().boundsInRoot.height
            assertTrue(larger > before)
            ui.onNodeWithTag("typography-spacing-slider").performSemanticsAction(
                SemanticsActions.SetProgress,
            ) {
                it(1.8f)
            }
            assertEquals(1.25f, ExperienceSettings.textScale, .001f)
            assertTrue(
                ui.onNodeWithText("构建你的想法").fetchSemanticsNode().boundsInRoot.height > larger,
            )
            TestSnapshots.capture(ui, "v072-typography-preview")
            ui.onNodeWithText("恢复默认排版").performScrollTo().performClick()
            assertEquals(1f, ExperienceSettings.textScale, .001f)
            assertEquals(1.4f, ExperienceSettings.lineSpacing, .001f)
            assertEquals(
                1.4f,
                ui.activity.getSharedPreferences("experience-v1", 0).getFloat("lineSpacing", 0f),
                .001f,
            )
        } finally {
            ui.runOnUiThread {
                ExperienceSettings.textScale = oldSize
                ExperienceSettings.lineSpacing = oldSpacing
                ExperienceSettings.save()
            }
        }
    }

    @Test
    fun railPreviewsAndJumpsToTaskSectionsWithoutEditingQueue() {
        ready()
        val tasksBefore = AssistantState.tasks.map { it.id to it.status }
        ui.runOnUiThread {
            repeat(12) { index ->
                AssistantState.current.messages.add(ChatEntry("user", "导航任务 ${index+1}"))
                AssistantState.current.messages.add(
                    ChatEntry("assistant", "第 ${index+1} 项结果。\n\n" + "这是任务内容。".repeat(35)),
                )
            }
        }
        ui.onNodeWithTag("task-navigation-rail").performTouchInput {
            click(androidx.compose.ui.geometry.Offset(centerX, 1f))
        }
        ui.onNodeWithTag("task-navigation-preview").assertIsDisplayed()
        ui.onNodeWithText("任务 1 / 12").assertIsDisplayed()
        TestSnapshots.capture(ui, "v072-task-navigation-preview")
        ui.onNodeWithTag("task-navigation-jump").performClick()
        ui.onNodeWithText("导航任务 1").assertIsDisplayed()
        ui.onNodeWithContentDescription("会话列表").performClick()
        ui.onNodeWithContentDescription("设置").performClick()
        ui.onNodeWithContentDescription("返回").performClick()
        ui.onNodeWithTag("conversation-card").performTouchInput {
            click(androidx.compose.ui.geometry.Offset(12f, height / 2f))
        }
        ui.onNodeWithText("导航任务 1").assertIsDisplayed()
        ui.onNodeWithTag("task-navigation-rail").performTouchInput {
            swipe(topCenter, bottomCenter, durationMillis = 500)
        }
        ui.onNodeWithText("导航任务 12").assertIsDisplayed()
        assertEquals(tasksBefore, AssistantState.tasks.map { it.id to it.status })
    }

    @Test
    fun modeSwitchKeepsTheDrawerOpenInBothDirections() {
        ready()
        ui.onNodeWithContentDescription("会话列表").performClick()
        ui.onNodeWithText("Work").performClick()
        ui.onNodeWithText("Chat").performClick()
        ui.runOnUiThread {
            OfficialChatNavigation.view!!.stopLoading()
            OfficialChatNavigation.view!!.loadDataWithBaseURL(
                "https://chat.deepseek.com/a/chat/s/fixture-12345678",
                OfficialAuthFixture.chat,
                "text/html",
                "UTF-8",
                null,
            )
        }
        ui.waitUntil(15000) {
            AssistantState.appMode == "chat" && OfficialChatNavigation.accessConfirmed
        }
        ui.onNodeWithTag("session-drawer").assertIsDisplayed()
        ui.onNodeWithText("Chat").performClick()
        ui.onNodeWithText("Work").performClick()
        ui.waitUntil(5000) { AssistantState.appMode == "work" }
        ui.onNodeWithTag("session-drawer").assertIsDisplayed()
        ui.onNodeWithText("Work").performClick()
        ui.onNodeWithText("Chat").performClick()
        ui.waitUntil(15000) {
            AssistantState.appMode == "chat" && !OfficialChatNavigation.loginOpen
        }
        ui.onNodeWithTag("session-drawer").assertIsDisplayed()
        TestSnapshots.capture(ui, "v072-mode-drawer")
    }
}
