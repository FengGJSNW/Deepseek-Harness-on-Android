package dev.deepseek.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.deepseek.mobile.terminal.PathUse
import dev.deepseek.mobile.terminal.RuleDecision
import dev.deepseek.mobile.terminal.TerminalPolicy
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ProjectMemoryTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test
    fun sharedMemoryIsScopedAtomicAndRejectsStaleWriters() {
        val store = MemoryStore(ui.activity)
        val a = UUID.randomUUID().toString()
        val b = UUID.randomUUID().toString()
        val first = store.read("project", a)
        val changed = store.write("project", a, "# 项目甲\n使用 Compose", first.sha256)
        assertEquals("# 项目甲\n使用 Compose", store.read("project", a).text)
        assertEquals("", store.read("project", b).text)
        assertTrue(runCatching { store.write("project", a, "旧会话误覆盖", first.sha256) }.isFailure)
        assertEquals(changed.text, store.read("project", a).text)
        assertTrue(runCatching { store.read("project", "../outside") }.isFailure)
        val global = store.read("global")
        try {
            store.write("global", "", "偏好：中文交流", global.sha256)
            assertEquals("偏好：中文交流", MemoryStore(ui.activity).read("global").text)
            assertEquals(changed.text, store.read("project", a).text)
        } finally {
            store.write("global", "", global.text, store.read("global").sha256)
        }
        val rules = JSONObject(TerminalPolicy.defaults())
        rules
            .getJSONArray("paths")
            .put(
                JSONObject()
                    .put("path", "/dev.deepseek.mobile/memory.md")
                    .put("permissions", "w")
                    .put("effect", "deny")
                    .put("recursive", false)
                    .put("reason", "不允许改全局记忆"),
            )
        val policy = TerminalPolicy(rules.toString())
        assertEquals(
            RuleDecision.DENY,
            policy
                .inspectPaths(
                    listOf(PathUse(global.logicalPath, "w")),
                    "/dev.deepseek.mobile/workplace/session_$a",
                )
                .decision,
        )
        assertEquals(
            RuleDecision.ALLOW,
            policy
                .inspectPaths(
                    listOf(PathUse(changed.logicalPath, "w")),
                    "/dev.deepseek.mobile/workplace/session_$a",
                )
                .decision,
        )
    }

    @Test
    fun projectsSurviveRestartAndSupportIndependentConversations() {
        ui.waitUntil(30000) { AssistantState.ready }
        val projectName = "鲸鲸的小小工作室 " + System.currentTimeMillis().toString().takeLast(4)
        var projectId = ""
        var conversationId = ""
        ui.runOnUiThread {
            resetTransientUiForTest()
            AssistantState.appMode = "work"
            AssistantState.activeTaskId = ""
            val project = AssistantState.addProject(projectName)
            projectId = project.id
            val a = AssistantState.addSession(project.id)
            a.title = "整理今天的想法"
            conversationId = a.id
            val independent = AssistantState.addSession()
            assertEquals("", independent.projectId)
            AssistantState.select(a.id)
            AssistantState.persist()
            AssistantState.initialize(ui.activity)
            AssistantState.ready = true
            assertEquals(project.id, AssistantState.current.projectId)
            assertEquals(projectName, AssistantState.projects.first { it.id == project.id }.title)
        }
        ui.onNodeWithContentDescription("会话列表").performClick()
        ui.onNodeWithText(projectName).assertIsDisplayed()
        val glyph =
            ui.onAllNodesWithTag("session-more-glyph", useUnmergedTree = true)
                .onFirst()
                .fetchSemanticsNode()
                .size
        assertTrue(glyph.width >= 24 * ui.activity.resources.displayMetrics.density - 1)
        TestSnapshots.capture(ui, "projects")
        ui.onNodeWithContentDescription("项目操作：$projectName").performClick()
        ui.onNodeWithText("项目共享记忆").performClick()
        try {
            ui.waitUntil(10000) {
                ui.onAllNodes(hasTestTag("memory-editor-input") and isEnabled())
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
        } catch (error: Throwable) {
            val roots = ui.onAllNodes(isRoot(), useUnmergedTree = true)
            java.io
                .File(ui.activity.filesDir, "v072-project-editor-failure.txt")
                .writeText(
                    (0 until roots.fetchSemanticsNodes().size).joinToString("\n") {
                        roots[it].printToString()
                    },
                )
            TestSnapshots.capture(ui, "v072-project-editor-failure")
            throw error
        }
        ui.onNodeWithTag("memory-editor-input").performTextInput("# 共同目标\n认真做好每一处细节。")
        ui.onNodeWithText("保存").performClick()
        ui.waitUntil(10000) {
            MemoryStore(ui.activity).read("project", projectId).text.contains("共同目标")
        }
        TestSnapshots.capture(ui, "project-memory")
        ui.onNodeWithContentDescription("关闭面板").performClick()
        ui.runOnUiThread {
            AssistantState.moveSession(conversationId, "")
            assertEquals("", AssistantState.sessions.first { it.id == conversationId }.projectId)
            AssistantState.moveSession(conversationId, projectId)
            AssistantState.removeProject(projectId)
            assertEquals("", AssistantState.sessions.first { it.id == conversationId }.projectId)
            assertTrue(MemoryStore(ui.activity).read("project", projectId).text.contains("共同目标"))
        }
    }
}
