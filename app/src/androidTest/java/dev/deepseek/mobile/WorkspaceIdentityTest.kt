package dev.deepseek.mobile

import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WorkspaceIdentityTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private fun prepare() {
        ui.waitUntil(30000) { AssistantState.ready }
        ui.runOnUiThread {
            resetTransientUiForTest()
            AssistantState.appMode = "work"
            AssistantState.activeTaskId = ""
            AssistantState.addSession()
        }
    }

    @Test
    fun workspaceOpensOnlyAgentTitlesThenShowsDetailsAndActualBrowser() {
        prepare()
        lateinit var browser: BrowserTab
        ui.runOnUiThread {
            val id = AssistantState.selectedId
            TeamState.agents.addAll(
                listOf(
                    AgentWork("workspace-test-a", id, "资料核对", "").apply {
                        status = "done"
                        messages.add(ChatEntry("assistant", "资料核对内容"))
                    },
                    AgentWork("workspace-test-b", id, "页面调研", "").apply { status = "done" },
                ),
            )
            AssistantState.current.apply {
                title = "当前工作会话"
                messages.add(ChatEntry("user", "测试会话标题栏"))
                draft = "原会话保留草稿"
            }
            browser = BrowserTab("workspace-test-web", "workspace-test-b", id, WebView(ui.activity))
            browser.title = "测试网页"
            browser.url = "https://example.test/workspace"
            browser.view.loadDataWithBaseURL(
                browser.url,
                "<html><body>当前网页</body></html>",
                "text/html",
                "UTF-8",
                null,
            )
            BrowserState.tabs.add(browser)
        }
        try {
            val title = ui.onNodeWithTag("conversation-title")
            title.assertTextEquals("当前工作会话")
            ui.onNodeWithContentDescription("打开功能区").performClick()
            ui.onNodeWithTag("conversation-workspace").assertIsDisplayed()
            title.assertTextEquals("当前工作会话")
            ui.onNodeWithTag("workspace-tabs").assertDoesNotExist()
            TestSnapshots.capture(ui, "v082-workspace-tools")
            ui.onNodeWithText("子 Agent 会话").performClick()
            assertTrue(
                title.fetchSemanticsNode().boundsInRoot.bottom <=
                    ui.onNodeWithTag("workspace-tabs").fetchSemanticsNode().boundsInRoot.top,
            )
            ui.onNodeWithText("资料核对").assertIsDisplayed()
            ui.onNodeWithText("页面调研").assertIsDisplayed()
            ui.onNodeWithText("资料核对内容").assertDoesNotExist()
            assertNotEquals(agentIcon(0), agentIcon(1))
            TestSnapshots.capture(ui, "v082-workspace-agents")
            ui.onNodeWithText("资料核对").performClick()
            ui.onNodeWithText("资料核对内容").assertIsDisplayed()
            title.assertTextEquals("当前工作会话")
            ui.onNodeWithContentDescription("工作区工具").performClick()
            ui.onNodeWithText("内置浏览器").performClick()
            ui.onNodeWithTag("workspace-browser").assertIsDisplayed()
            title.assertTextEquals("当前工作会话")
            ui.runOnUiThread { assertTrue(browser.view.parent is ViewGroup) }
            TestSnapshots.capture(ui, "v082-workspace-browser")
            ui.onNodeWithContentDescription("返回会话").performClick()
            ui.onNodeWithText("原会话保留草稿").assertIsDisplayed()
            ui.runOnUiThread { assertNull(browser.view.parent) }
            ui.onNodeWithContentDescription("打开功能区").performClick()
            ui.onNodeWithTag("workspace-browser").assertIsDisplayed()
        } finally {
            ui.runOnUiThread {
                WorkspaceState.reset()
                closeBrowserTab(browser.id)
            }
        }
    }

    @Test
    fun displayNameAndAnimatedAvatarPersist() {
        prepare()
        val previousName = AgentIdentity.name
        val previousMode = AgentIdentity.mode
        try {
            ui.onNodeWithContentDescription("会话列表").performClick()
            ui.onNodeWithContentDescription("设置").performClick()
            ui.onNodeWithText("Agent").performClick()
            ui.onNodeWithText("头像与名称").performClick()
            ui.onNode(hasSetTextAction() and hasText("Agent 显示名称")).performTextReplacement("资料鲸")
            ui.onNodeWithText("保存名称").performClick()
            ui.onNode(hasText("Claude 风格圆点") and hasClickAction()).performScrollTo().performClick()
            ui.onNodeWithContentDescription("资料鲸 · 思考中").performScrollTo().assertIsDisplayed()
            ui.onNodeWithText("思考中").performScrollTo().performClick()
            ui.onNodeWithContentDescription("资料鲸 · 执行中").assertExists()
            TestSnapshots.capture(ui, "v082-identity")
            runBlocking { withContext(Dispatchers.Main) { AgentIdentity.load(ui.activity) } }
            assertEquals("资料鲸", AgentIdentity.name)
            assertEquals(AvatarMode.DOTS, AgentIdentity.mode)
            ui.onNodeWithText("DeepSeek Harness 鲸尾").performScrollTo().performClick()
            ui.onNodeWithContentDescription("资料鲸 · 执行中").assertExists()
            assertEquals(AvatarMode.WHALE, AgentIdentity.mode)
        } finally {
            ui.runOnUiThread {
                AgentIdentity.rename(ui.activity, previousName)
                AgentIdentity.select(ui.activity, previousMode)
            }
        }
    }

    @Test
    fun terminalUsesCurrentAppUidWithoutOpeningAgentReview() {
        prepare()
        ui.runOnUiThread {
            AssistantState.current.access = "full"
            AssistantState.current.terminalEnabled = true
            AssistantState.current.terminalLevel = "app"
        }
        ui.onNodeWithContentDescription("打开功能区").performClick()
        ui.onNodeWithText("会话终端").performClick()
        ui.onNodeWithTag("workspace-terminal").assertIsDisplayed()
        ui.onNode(hasSetTextAction() and hasText("命令")).performTextInput("id -u")
        ui.onNodeWithText("运行").assertIsEnabled().performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.OnClick,
        ) {
            it()
        }
        try {
            ui.waitUntil(20000) {
                ui.onAllNodesWithText("退出码 0").fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: Throwable) {
            throw AssertionError(ui.onNodeWithTag("workspace-terminal").printToString(), e)
        }
        ui.onNodeWithText(android.os.Process.myUid().toString(), substring = true).assertExists()
        ui.onNodeWithTag("agent-review-card").assertDoesNotExist()
        ui.onNodeWithContentDescription("关闭标签 终端").performClick()
    }
}
