package dev.deepseek.mobile

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PhaseEightTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private fun ready() {
        ui.waitUntil(30000) { AssistantState.ready }
        ui.runOnUiThread {
            resetTransientUiForTest()
            AssistantState.appMode = "work"
            AssistantState.activeTaskId = ""
            AssistantState.addSession()
            WhaleCompanion.settle()
            WorkspaceState.reset()
            OfficialChatNavigation.cancelLogin()
        }
    }

    @Test
    fun compactParentAndChildSettingsKeepIndependentConfiguration() {
        ready()
        ui.onNodeWithContentDescription("模型与思考设置").performClick()
        ui.onNodeWithTag("reasoning-slider").performSemanticsAction(SemanticsActions.SetProgress) {
            it(3f)
        }
        ui.onNodeWithTag("effort-title").assertTextEquals("Ultra")
        ui.onNodeWithContentDescription("子智能体模型与强度").performClick()
        ui.onNodeWithTag("child-model-panel").assertIsDisplayed()
        ui.onNodeWithContentDescription("选择子智能体模型").performClick()
        ui.onNodeWithText("DeepSeek Pro").performClick()
        ui.onNodeWithTag("reasoning-slider").performSemanticsAction(SemanticsActions.SetProgress) {
            it(1f)
        }
        ui.onNodeWithTag("child-effort-title").assertTextEquals("High")
        TestSnapshots.capture(ui, "v071-child-model")
        ui.onNodeWithContentDescription("返回主智能体设置").performClick()
        ui.onNodeWithTag("effort-title").assertTextEquals("Ultra")
        ui.runOnUiThread {
            assertEquals("deepseek-flash", AssistantState.current.model)
            assertEquals("deepseek-v4-pro", AssistantState.current.childModel)
            assertEquals("high", AssistantState.current.childEffort)
            assertEquals("high", normalizeEffort("medium"))
        }
        TestSnapshots.capture(ui, "v071-model")
    }

    @Test
    fun effortThumbFollowsFingerBetweenStopsThenSnapsOnRelease() {
        ready()
        ui.onNodeWithContentDescription("模型与思考设置").performClick()
        val slider = ui.onNodeWithTag("reasoning-slider")
        slider.performTouchInput {
            down(Offset(width * .43f, centerY))
            moveTo(Offset(width * .43f, centerY))
        }
        val progress =
            slider
                .fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo]
                .current
        assertTrue(
            "Accessible progress must follow the finger between stops",
            progress > 1.1f && progress < 1.6f,
        )
        ui.runOnUiThread { assertEquals("low", AssistantState.current.effort) }
        TestSnapshots.capture(ui, "v071-effort-held-between-stops")
        slider.performTouchInput { up() }
        ui.waitForIdle()
        ui.runOnUiThread { assertEquals("high", AssistantState.current.effort) }
        TestSnapshots.capture(ui, "v071-effort-snap")
    }

    @Test
    fun releasedDragAndKeyboardBothReturnWhaleButTapDoesNot() {
        ready()
        var motion: WhaleMotion? = null
        var scope: CoroutineScope? = null
        val keyboard = mutableStateOf(false)
        val input = mutableStateOf(0)
        ui.runOnUiThread {
            ui.activity.setContent {
                val m = rememberWhaleMotion(220f, false, input.value, keyboard.value)
                val jobs = rememberCoroutineScope()
                SideEffect {
                    motion = m
                    scope = jobs
                }
            }
        }
        ui.waitForIdle()
        ui.runOnUiThread {
            scope!!.launch {
                motion!!.x.snapTo(-90f)
                motion!!.y.snapTo(-25f)
            }
        }
        ui.waitForIdle()
        ui.runOnUiThread {
            input.value++
            WhaleActivity.interact()
        }
        ui.waitForIdle()
        assertEquals(-90f, motion!!.shownX, .5f)
        ui.runOnUiThread {
            motion!!.startDrag()
            motion!!.dragX = -140f
            scope!!.launch { motion!!.releaseDrag() }
        }
        ui.waitForIdle()
        assertEquals(0f, motion!!.shownX, .5f)
        assertEquals(0f, motion!!.shownY, .5f)
        ui.runOnUiThread {
            scope!!.launch {
                motion!!.x.snapTo(-100f)
                motion!!.y.snapTo(-25f)
            }
        }
        ui.waitForIdle()
        ui.runOnUiThread { keyboard.value = true }
        ui.waitForIdle()
        assertEquals(0f, motion!!.shownX, .5f)
        assertEquals(0f, motion!!.shownY, .5f)
    }

    @Test
    fun homeLongPressIsGoneAndEmptyPromptIsOneLine() {
        ready()
        ui.onNodeWithText("你想在DSHA中构建什么？").assertIsDisplayed()
        ui.onNodeWithText("今天，想做点什么？").assertDoesNotExist()
        ui.onNodeWithTag("pet-body").performTouchInput { longClick() }
        ui.onNodeWithText("鲸鲸的小窝").assertDoesNotExist()
        ui.runOnUiThread { WhaleCompanion.react("wave", "主人，我在这里。") }
        TestSnapshots.capture(ui, "v071-whale-cloud")
    }

    @Test
    fun settingsBackRevealsSidebarAndModeMenuStaysClosed() {
        ready()
        ui.onNodeWithContentDescription("会话列表").performClick()
        ui.onNodeWithContentDescription("设置").performClick()
        ui.onNodeWithContentDescription("返回").performClick()
        ui.onNodeWithTag("session-drawer").assertIsDisplayed()
        ui.onNodeWithText("Work").assertIsDisplayed()
        TestSnapshots.capture(ui, "v071-settings-back")
    }

    @Test
    fun officialHtmlRendersAsNativeConversationAndSendingUsesOfficialDom() {
        ready()
        ui.runOnUiThread { AssistantState.appMode = "chat" }
        ui.waitUntil(15000) { OfficialChatNavigation.view != null }
        ui.runOnUiThread {
            OfficialChatNavigation.view!!.stopLoading()
            OfficialChatNavigation.view!!.loadDataWithBaseURL(
                "https://chat.deepseek.com/a/chat/s/fixture-12345678",
                """
                <meta name="viewport" content="width=device-width,initial-scale=1"><title>本地合成对话 - DeepSeek</title>
                <a href="/a/chat/s/fixture-12345678">合成历史</a>
                <a href="https://evil.example/a/chat/s/fixture-12345678">排除外站</a>
                <div class="ds-message d29f3d7d"><div class="eafda4ae">合成用户问题</div><div>复制</div></div>
                <div class="ds-message"><div class="ds-markdown ds-assistant-message-main-content"><h2>官方标题</h2><p>合成官方答复 <strong>官方粗体</strong></p><table><tr><th>名称</th><th>值</th></tr><tr><td>x</td><td>2</td></tr></table><pre><code>val x = 1</code></pre></div></div>
                <div><div><textarea></textarea><div role="button" class="ds-button--primary"><svg><path d="M8.3125 fixture"/></svg></div></div></div>
                <script>
                  document.querySelector('[role=button]').onclick=()=>{
                    const input=document.querySelector('textarea');
                    const user=document.createElement('div');user.className='ds-message d29f3d7d';user.textContent=input.value;document.body.append(user);
                    const e=document.createElement('div');e.className='ds-message';
                    const body=document.createElement('div');body.className='ds-assistant-message-main-content';
                    body.textContent='收到：'+input.value;e.append(body);document.body.append(e);input.value='';
                  };
                </script>
                """
                    .trimIndent(),
                "text/html",
                "UTF-8",
                null,
            )
        }
        ui.waitUntil(15000) {
            OfficialChatNavigation.snapshot.messages.size == 2 &&
                OfficialChatNavigation.accessConfirmed
        }
        ui.onNodeWithTag("official-native-chat").assertIsDisplayed()
        ui.onNodeWithText("合成用户问题").assertIsDisplayed()
        ui.runOnUiThread {
            assertNull(
                "Official page must not replace the native conversation",
                OfficialChatNavigation.view!!.parent,
            )
            assertEquals(1, OfficialChatNavigation.snapshot.sessions.size)
            assertTrue(OfficialChatNavigation.snapshot.messages.last().text.contains("```"))
            assertTrue(OfficialChatNavigation.snapshot.messages.last().text.contains("**官方粗体**"))
            assertTrue(
                MarkdownDocument.parse(OfficialChatNavigation.snapshot.messages.last().text).any {
                    it is MarkdownBlock.Table
                },
            )
            assertFalse(OfficialChatDom.sessionPath("//evil.example/a/chat/s/fixture-12345678"))
        }
        ui.onNodeWithTag("official-composer").performTextInput("原生输入 <hello>")
        ui.onNodeWithContentDescription("发送官方消息").performClick()
        ui.waitUntil(15000) {
            OfficialChatNavigation.snapshot.messages.any { it.text == "收到：原生输入 <hello>" }
        }
        TestSnapshots.capture(ui, "v071-official-native-fixture")
        ui.onNodeWithContentDescription("官方会话列表").performClick()
        ui.onNodeWithText("合成历史").assertIsDisplayed()
        TestSnapshots.capture(ui, "v071-official-sidebar-fixture")
    }

    @Test
    fun persistenceCompletesOffMainAndRestoresChildConfiguration() {
        ready()
        val id = AssistantState.selectedId
        ui.runOnUiThread {
            AssistantState.current.childModel = "deepseek-v4-pro"
            AssistantState.current.childEffort = "max"
            AssistantState.persist()
        }
        AssistantState.awaitPersistence()
        val root = JSONObject(File(ui.activity.filesDir, "conversations-v3.json").readText())
        val sessions = root.getJSONArray("sessions")
        val record =
            (0 until sessions.length())
                .map { sessions.getJSONObject(it) }
                .first { it.getString("id") == id }
        assertEquals("max", record.getString("childEffort"))
        assertEquals("deepseek-v4-pro", record.getString("childModel"))
    }
}
