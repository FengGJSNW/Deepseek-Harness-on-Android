package dev.deepseek.mobile

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic official DOM: no network, account credentials or real uploads. */
class OfficialChatControlsTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private fun fixture(history: Int = 1) =
        """
        <meta name="viewport" content="width=device-width,initial-scale=1"><title>原生控件验收 - DeepSeek</title>
        ${(1..history).joinToString(""){n->"<div class='ds-message d29f3d7d'><div class='fbb737a4'>官方旧任务$n</div></div><div class='ds-message'><div class='ds-assistant-message-main-content'>${if(n==20)"<p>"+(1..40).joinToString("</p><p>"){"较长的历史内容 $it"}+"</p><p>官方旧回复20末尾</p>" else "官方旧回复$n"}</div></div>"}}
        <div class="ds-message"><article>未知格式仍可阅读</article></div>
        <section><div><textarea></textarea></div>
        <div class="ds-toggle-button" aria-pressed="false">深度思考</div><div class="ds-toggle-button" aria-pressed="false">智能搜索</div>
        <input type="file" multiple><div role="button" class="ds-button--primary ds-button--disabled"><svg><path d="M8.3125 fixture"/></svg></div></section>
        <script>
        window.fixtureClicks=0;window.fixtureAcknowledge=true;window.fixtureFiles=[];window.fixtureWait=450;let uploading=false;
        const input=document.querySelector('textarea'),button=document.querySelector('[role=button]');
        const ready=()=>button.classList.toggle('ds-button--disabled',!input.value.trim()||uploading);
        input.oninput=()=>setTimeout(ready,250);
        document.querySelectorAll('.ds-toggle-button').forEach(e=>e.onclick=()=>{const selected=e.getAttribute('aria-pressed')!=='true';e.setAttribute('aria-pressed',String(selected));e.classList.toggle('ds-toggle-button--selected',selected)});
        document.querySelector('input[type=file]').onchange=async(e)=>{uploading=true;ready();window.fixtureFiles=await Promise.all([...e.target.files].map(async f=>({name:f.name,text:await f.text()})));setTimeout(()=>{uploading=false;ready()},350)};
        button.onclick=()=>{
            if(button.classList.contains('ds-button--disabled'))return;window.fixtureClicks++;
            if(!window.fixtureAcknowledge)return;
            const sent=input.value;button.classList.add('ds-button--disabled');
            const acknowledge=()=>{const u=document.createElement('div');u.className='ds-message d29f3d7d';const body=document.createElement('div');body.className='fbb737a4';body.textContent=sent;u.append(body);document.body.insertBefore(u,document.querySelector('section'));
                const a=document.createElement('div');a.className='ds-message';const r=document.createElement('div');r.className='ds-assistant-message-main-content';r.textContent='收到：'+sent;a.append(r);document.body.insertBefore(a,document.querySelector('section'));input.value='';ready();};if(window.fixtureHoldAck)window.fixtureResolveSend=acknowledge;else setTimeout(acknowledge,window.fixtureWait);
        };
        </script>
    """
            .trimIndent()

    private fun ready(history: Int = 1) {
        ui.waitUntil(30000) { AssistantState.ready }
        ui.runOnUiThread {
            resetTransientUiForTest()
            AssistantState.appMode = "work"
            AssistantState.activeTaskId = ""
            AssistantState.addSession()
            WhaleCompanion.settle()
            OfficialChatNavigation.requestChat(ui.activity)
            OfficialChatNavigation.view!!.stopLoading()
            OfficialChatNavigation.view!!.loadDataWithBaseURL(
                "https://chat.deepseek.com/a/chat/s/fixture-12345678",
                fixture(history),
                "text/html",
                "UTF-8",
                null,
            )
        }
        ui.waitUntil(15000) {
            OfficialChatNavigation.accessConfirmed &&
                AssistantState.appMode == "chat" &&
                OfficialChatNavigation.snapshot.thinkingAvailable
        }
        ui.onNodeWithTag("official-native-chat").assertIsDisplayed()
    }

    private fun js(code: String): Any? = runBlocking {
        withContext(Dispatchers.Main) { OfficialChatNavigation.evaluate(code) }
    }

    @Test
    fun officialReasoningIsSeparateAndRemainsReadableAfterDomCollapse() {
        ready()
        js(
            """document.querySelector('.ds-assistant-message-main-content').insertAdjacentHTML('afterbegin',
            '<div class="ds-think"><div class="ds-think-content">独立思考内容</div></div>')""",
        )
        ui.waitUntil(10000) {
            OfficialChatNavigation.snapshot.messages.any {
                it.reasoning == "独立思考内容" && it.text == "官方旧回复1"
            }
        }
        ui.onNodeWithText("思考过程", substring = true).performClick()
        ui.onNodeWithText("独立思考内容").assertIsDisplayed()
        js("document.querySelector('.ds-think').remove()")
        ui.waitForIdle()
        ui.onNodeWithText("独立思考内容").assertIsDisplayed()
    }

    @Test
    fun newUserMarkupAndUnknownHistoryDoNotDisableNativeSendingAndModesMatchDom() {
        ready()
        assertFalse(OfficialChatNavigation.snapshot.unsupported)
        assertTrue(OfficialChatNavigation.snapshot.partialHistory)
        assertTrue(
            OfficialChatNavigation.snapshot.messages.any {
                it.role == "user" && it.text == "官方旧任务1"
            },
        )
        ui.onNodeWithTag("official-thinking").performClick()
        ui.waitUntil(10000) {
            OfficialChatNavigation.snapshot.deepThinking && !OfficialChatNavigation.modeBusy
        }
        ui.onNodeWithTag("official-search").performClick()
        ui.waitUntil(10000) {
            OfficialChatNavigation.snapshot.search && !OfficialChatNavigation.modeBusy
        }
        ui.onNodeWithTag("official-composer").performTextInput("原生延迟发送")
        ui.awaitStableComposerTarget("official-send")
        ui.onNodeWithTag("official-send").performClick()
        ui.waitUntil(15000) {
            !OfficialChatNavigation.sending &&
                OfficialChatNavigation.snapshot.messages.any { it.text == "收到：原生延迟发送" }
        }
        ui.onNodeWithTag("official-composer").assertTextEquals("")
        assertEquals(1, (js("window.fixtureClicks") as Number).toInt())
        assertTrue(OfficialChatNavigation.attachments.isEmpty())
        assertNull(OfficialChatNavigation.view!!.parent)
        ui.onNodeWithContentDescription("打开功能区").assertExists()
        ui.onNodeWithContentDescription("官方账户与页面").assertDoesNotExist()
        TestSnapshots.capture(ui, "v072-official-controls")
    }

    @Test
    fun selectedAttachmentsReachOnlyTheOfficialFileInputAndClearAfterAcknowledgement() {
        ready()
        val workCount = AssistantState.current.messages.size
        val file =
            File(ui.activity.cacheDir, "official-attachment.txt").apply {
                writeText("attachment fixture only")
            }
        try {
            ui.runOnUiThread {
                OfficialChatNavigation.selectAttachments(
                    ui.activity,
                    listOf(android.net.Uri.fromFile(file)),
                )
            }
            ui.waitUntil(10000) { OfficialChatNavigation.attachments.size == 1 }
            ui.onNodeWithText("official-attachment.txt").assertIsDisplayed()
            ui.onNodeWithTag("official-send").assertIsEnabled().performClick()
            ui.waitUntil(15000) {
                !OfficialChatNavigation.sending &&
                    OfficialChatNavigation.snapshot.messages.any { it.text == "收到：请查看这些附件" }
            }
            val files = js("window.fixtureFiles") as JSONArray
            assertEquals("official-attachment.txt", files.getJSONObject(0).getString("name"))
            assertEquals("attachment fixture only", files.getJSONObject(0).getString("text"))
            assertTrue(OfficialChatNavigation.attachments.isEmpty())
            assertEquals(workCount, AssistantState.current.messages.size)
        } finally {
            file.delete()
        }
    }

    @Test
    fun sendingFromOldHistoryImmediatelyReturnsToConversationEnd() {
        ready(20)
        js("window.fixtureHoldAck=true")
        ui.onNodeWithTag("task-navigation-rail").performSemanticsAction(
            SemanticsActions.SetProgress,
        ) {
            it(0f)
        }
        ui.onNodeWithContentDescription("关闭任务预览").performClick()
        ui.onNodeWithText("官方旧任务1").assertIsDisplayed()
        ui.onNodeWithTag("official-composer").performTextInput("从旧历史发送")
        ui.awaitStableComposerTarget("official-send")
        ui.onNodeWithTag("official-send").performClick()
        try {
            ui.waitUntil(1000) {
                runCatching { ui.onNodeWithText("官方旧回复20末尾").assertIsDisplayed() }.isSuccess
            }
        } catch (error: Throwable) {
            val roots = ui.onAllNodes(isRoot(), useUnmergedTree = true)
            File(ui.activity.filesDir, "v072-official-tail-failure.txt")
                .writeText(
                    (0 until roots.fetchSemanticsNodes().size).joinToString("\n") {
                        roots[it].printToString()
                    } +
                        "\n" +
                        OfficialChatNavigation.snapshot.toString() +
                        "\nfixtureClicks=" +
                        js("window.fixtureClicks"),
                )
            TestSnapshots.capture(ui, "v072-official-tail-failure")
            throw error
        }
        js("window.fixtureResolveSend()")
        ui.waitUntil(15000) {
            !OfficialChatNavigation.sending &&
                OfficialChatNavigation.snapshot.messages.any { it.text == "收到：从旧历史发送" }
        }
        ui.onNodeWithText("收到：从旧历史发送").assertIsDisplayed()
    }

    @Test
    fun unconfirmedClickKeepsDraftAndDoesNotRetryAutomatically() {
        ready()
        js("window.fixtureAcknowledge=false")
        ui.onNodeWithTag("official-composer").performTextInput("保留未确认的草稿")
        ui.awaitStableComposerTarget("official-send")
        ui.onNodeWithTag("official-send").performClick()
        ui.waitUntil(20000) {
            !OfficialChatNavigation.sending && OfficialChatNavigation.failure.contains("尚未确认")
        }
        ui.onNodeWithTag("official-composer").assertTextEquals("保留未确认的草稿")
        assertEquals(1, (js("window.fixtureClicks") as Number).toInt())
        assertNull(OfficialChatNavigation.view!!.parent)
    }
}
