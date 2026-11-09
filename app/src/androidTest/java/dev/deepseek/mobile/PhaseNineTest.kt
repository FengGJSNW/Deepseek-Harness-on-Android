package dev.deepseek.mobile

import android.webkit.CookieManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PhaseNineTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private fun ready() {
        ui.waitUntil(30000) { AssistantState.ready && AgentService.current != null }
        ui.runOnUiThread {
            resetTransientUiForTest()
            AssistantState.appMode = "work"
            AssistantState.activeTaskId = ""
            AssistantState.addSession()
            WhaleCompanion.settle()
            OfficialChatNavigation.cancelLogin()
        }
    }

    private fun popupRoot(): android.view.View {
        var popup: android.view.View? = null
        ui.waitUntil(5000) {
            ui.runOnUiThread {
                popup =
                    android.view.inspector.WindowInspector.getGlobalWindowViews().lastOrNull {
                        it.javaClass.name.endsWith("PopupLayout") && it.isShown && it.isLaidOut
                    }
            }
            popup != null
        }
        return popup!!
    }

    private fun assertPopupInsideScreen() {
        val root = popupRoot()
        ui.runOnUiThread {
            val xy = IntArray(2)
            root.getLocationOnScreen(xy)
            assertTrue("Popup shadow gutter starts outside display: ${xy[0]}", xy[0] >= -1)
            assertTrue(
                "Popup shadow gutter ends outside display",
                xy[0] + root.width <= ui.activity.window.decorView.width + 1,
            )
        }
    }

    private fun popupTop(): Float {
        val root = popupRoot()
        var top = 0f
        ui.runOnUiThread {
            val xy = IntArray(2)
            root.getLocationOnScreen(xy)
            top = xy[1].toFloat()
        }
        return top
    }

    private fun login(html: String = OfficialAuthFixture.login) {
        ui.runOnUiThread {
            OfficialChatNavigation.requestChat(ui.activity)
            OfficialChatNavigation.view!!.stopLoading()
            OfficialChatNavigation.view!!.loadDataWithBaseURL(
                "https://chat.deepseek.com/sign_in",
                html,
                "text/html",
                "UTF-8",
                null,
            )
        }
        ui.waitUntil(15000) { OfficialChatNavigation.auth.mode == "sms" }
        ui.onNodeWithTag("official-login-native").assertIsDisplayed()
    }

    private fun js(code: String): Any? = runBlocking {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            OfficialChatNavigation.evaluate(code)
        }
    }

    private fun authenticate() {
        login()
        ui.onNodeWithTag("official-login-account").performTextInput("13700000000")
        ui.onNodeWithTag("official-login-secret").performTextInput("123456")
        ui.onNodeWithTag("official-login-agreement").performClick()
        ui.onNodeWithTag("official-login-submit").performScrollTo().performClick()
        ui.waitUntil(15000) {
            AssistantState.appMode == "chat" && OfficialChatNavigation.accessConfirmed
        }
        ui.onNodeWithTag("official-native-chat").assertIsDisplayed()
    }

    @Test
    fun missingKeyShowsToastAndPreservesDraftAndAttachmentsWithoutOpeningSettings() {
        ready()
        assertTrue(
            "Only run this test on the dedicated empty-key emulator",
            KeyStore(ui.activity).list().isEmpty(),
        )
        val file =
            AttachmentStore(ui.activity)
                .save(
                    "pending attachment".byteInputStream(),
                    AssistantState.selectedId,
                    "pending.txt",
                    "text/plain",
                )
        ui.runOnUiThread { AssistantState.current.files.add(file.id) }
        ui.onNodeWithTag("message-input").performTextInput("这条草稿不能丢")
        val automation =
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                .getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val event =
            automation.executeAndWaitForEvent(
                { ui.onNodeWithTag("composer-send").performClick() },
                {
                    it.eventType ==
                        android.view.accessibility.AccessibilityEvent
                            .TYPE_NOTIFICATION_STATE_CHANGED &&
                        it.text.any { text -> text.toString() == "无有效api key" }
                },
                5000,
            )
        event.recycle()
        ui.onNodeWithTag("settings-screen").assertDoesNotExist()
        ui.onNodeWithTag("center-island").assertDoesNotExist()
        assertEquals("这条草稿不能丢", AssistantState.current.draft)
        assertTrue(AssistantState.current.files.contains(file.id))
        assertTrue(AssistantState.current.messages.isEmpty())
        TestSnapshots.capture(ui, "v072-send-missing-key")
    }

    @Test
    fun chatSelectionOpensNativeLoginAboveWorkAndCancelPreservesDraft() {
        ready()
        ui.onNodeWithTag("message-input").performTextInput("保留 Work 草稿")
        ui.onNodeWithContentDescription("会话列表").performClick()
        ui.onNodeWithText("Work").performClick()
        ui.onNodeWithText("Chat").performClick()
        ui.waitUntil(10000) { OfficialChatNavigation.loginOpen }
        assertEquals("work", AssistantState.appMode)
        assertEquals("保留 Work 草稿", AssistantState.current.draft)
        ui.onNodeWithTag("settings-screen").assertDoesNotExist()
        login()
        ui.runOnUiThread { assertNull(OfficialChatNavigation.view!!.parent) }
        TestSnapshots.capture(ui, "v072-native-login")
        ui.onNodeWithContentDescription("关闭面板").performClick()
        assertEquals("work", AssistantState.appMode)
        assertFalse(OfficialChatNavigation.loginOpen)
        assertEquals("保留 Work 草稿", AssistantState.current.draft)
    }

    @Test
    fun smsLoginRequiresConsentAndSuccessBeforeSwitchingAndHasNoHeaderAction() {
        ready()
        login()
        ui.onNodeWithTag("official-login-account").performTextInput("13700000000")
        ui.onNodeWithTag("official-login-secret").performTextInput("000000")
        ui.onNodeWithTag("official-login-submit").assertIsNotEnabled()
        ui.onNodeWithTag("official-login-agreement").performClick()
        ui.onNodeWithTag("official-login-submit").performScrollTo().performClick()
        ui.waitUntil(10000) {
            OfficialChatNavigation.auth.error.contains("凭据不正确") && !OfficialChatNavigation.authBusy
        }
        assertEquals("work", AssistantState.appMode)
        ui.onNodeWithTag("official-login-secret").performTextReplacement("123456")
        ui.onNodeWithTag("official-login-submit").performScrollTo().performClick()
        // Auth mode and conversation data arrive from separate asynchronous DOM evaluations.
        ui.waitUntil(15000) {
            AssistantState.appMode == "chat" &&
                !OfficialChatNavigation.authBusy &&
                OfficialChatNavigation.snapshot.messages.any { it.text == "登录验收成功" }
        }
        try {
            ui.onNodeWithText("登录验收成功").assertIsDisplayed()
        } catch (error: Throwable) {
            val roots = ui.onAllNodes(isRoot(), useUnmergedTree = true)
            java.io
                .File(ui.activity.filesDir, "v072-login-layout-failure.txt")
                .writeText(
                    "loginOpen=${OfficialChatNavigation.loginOpen}, loading=${OfficialChatNavigation.loading}, authenticated=${OfficialChatNavigation.snapshot.authenticated}\n" +
                        (0 until roots.fetchSemanticsNodes().size).joinToString("\n") {
                            roots[it].printToString()
                        },
                )
            TestSnapshots.capture(ui, "v072-login-layout-failure")
            throw error
        }
        ui.onNodeWithContentDescription("官方账户与页面").assertDoesNotExist()
        ui.onNodeWithContentDescription("打开功能区").assertExists()
        assertNull(OfficialChatNavigation.view!!.parent)
        TestSnapshots.capture(ui, "v072-native-chat")
    }

    @Test
    fun passwordLoginAndSmsRequestUseOfficialDomBehindNativeComponents() {
        ready()
        login()
        ui.onNodeWithTag("official-login-account").performTextInput("13700000000")
        ui.onNodeWithTag("official-login-agreement").performClick()
        ui.onNodeWithText("发送验证码").performClick()
        ui.waitUntil(10000) {
            OfficialChatNavigation.auth.codeLabel.contains("59") && !OfficialChatNavigation.authBusy
        }
        assertEquals(1, js("window.fixtureCodeRequests"))
        ui.onNodeWithText("密码登录").performClick()
        ui.waitUntil(10000) {
            OfficialChatNavigation.auth.mode == "password" && !OfficialChatNavigation.authBusy
        }
        ui.onNodeWithTag("official-login-account").performTextReplacement("fixture@example.invalid")
        ui.onNodeWithTag("official-login-secret").performTextInput("fixture-password")
        TestSnapshots.capture(ui, "v072-native-password-login")
        ui.onNodeWithTag("official-login-submit").performScrollTo().performClick()
        ui.waitUntil(15000) { AssistantState.appMode == "chat" }
        assertEquals(1, js("window.fixtureLoginRequests"))
        assertNull(OfficialChatNavigation.view!!.parent)
    }

    @Test
    fun englishOfficialFormUsesNativeChineseControlsAndBothLoginModes() {
        ready()
        val english =
            OfficialAuthFixture.login
                .replace("请输入手机号/邮箱地址", "Phone number/Email address")
                .replace("请输入手机号", "Phone number")
                .replace("请输入验证码", "Code")
                .replace("密码登录", "Login with password")
                .replace("验证码登录", "Login with code")
                .replace("发送验证码", "Send code")
                .replace("'登录'", "'Log in'")
        login(english)
        ui.onNodeWithText("密码登录").performClick()
        ui.waitUntil(10000) {
            OfficialChatNavigation.auth.mode == "password" && !OfficialChatNavigation.authBusy
        }
        ui.onNodeWithText("验证码登录").performClick()
        ui.waitUntil(10000) {
            OfficialChatNavigation.auth.mode == "sms" && !OfficialChatNavigation.authBusy
        }
        ui.onNodeWithTag("official-login-account").performTextInput("13700000000")
        ui.onNodeWithTag("official-login-secret").performTextInput("123456")
        ui.onNodeWithTag("official-login-agreement").performClick()
        ui.onNodeWithTag("official-login-submit").performScrollTo().performClick()
        ui.waitUntil(15000) { AssistantState.appMode == "chat" }
        assertTrue(OfficialChatNavigation.accessConfirmed)
    }

    @Test
    fun logoutExistsInSettingsAndClearsOnlyOfficialOrigin() {
        ready()
        authenticate()
        ui.runOnUiThread {
            CookieManager.getInstance()
                .setCookie("https://example.org/", "dsha_other=keep; Path=/; Secure")
            CookieManager.getInstance()
                .setCookie("https://chat.deepseek.com/", "dsha_official=fixture; Path=/; Secure")
        }
        assertEquals(true, js("localStorage.setItem('dsha-fixture','not-a-secret');true"))
        ui.onNodeWithContentDescription("官方会话列表").performClick()
        ui.onNodeWithContentDescription("设置").performClick()
        ui.onNodeWithText("连接设置").performClick()
        ui.onNodeWithText("DeepSeek Chat 账户").performClick()
        TestSnapshots.capture(ui, "v072-settings-signout")
        ui.onNodeWithText("退出登录", useUnmergedTree = true).performClick()
        ui.waitUntil(15000) {
            !OfficialChatNavigation.authBusy && !OfficialChatNavigation.accessConfirmed
        }
        assertEquals("work", AssistantState.appMode)
        assertTrue(OfficialChatNavigation.snapshot.messages.isEmpty())
        ui.runOnUiThread {
            assertFalse(
                CookieManager.getInstance()
                    .getCookie("https://chat.deepseek.com/")
                    .orEmpty()
                    .contains("dsha_official="),
            )
            assertTrue(
                CookieManager.getInstance()
                    .getCookie("https://example.org/")
                    .orEmpty()
                    .contains("dsha_other=keep"),
            )
            CookieManager.getInstance()
                .setCookie("https://example.org/", "dsha_other=; Max-Age=0; Path=/; Secure")
        }
    }

    @Test
    fun compactModelIslandAndRecedingParentKeepIndependentSettings() {
        ready()
        ui.onNodeWithContentDescription("模型与思考设置").performClick()
        assertPopupInsideScreen()
        val height = ui.onNodeWithTag("toolbar-popover").fetchSemanticsNode().boundsInRoot.height
        assertTrue(
            "Model island should be about two thirds of the previous 140dp: $height",
            height >= with(ui.density) { 80.dp.toPx() } &&
                height <= with(ui.density) { 106.dp.toPx() },
        )
        val rootTop =
            popupTop() + ui.onNodeWithTag("reasoning-panel").fetchSemanticsNode().boundsInRoot.top
        TestSnapshots.capture(ui, "v072-model")
        ui.onNodeWithContentDescription("子智能体模型与强度").performClick()
        ui.onNodeWithTag("child-model-panel").assertIsDisplayed()
        val receded =
            popupTop() + ui.onNodeWithTag("reasoning-panel").fetchSemanticsNode().boundsInRoot.top
        TestSnapshots.capture(ui, "v072-child-model")
        java.io
            .File(ui.activity.filesDir, "v072-model-layout.json")
            .writeText(
                org.json
                    .JSONObject()
                    .put("heightDp", height / ui.density.density)
                    .put("beforeY", rootTop)
                    .put("afterY", receded)
                    .toString(2),
            )
        assertTrue(
            "Parent island must move above the child",
            receded < rootTop - with(ui.density) { 5.dp.toPx() },
        )
        ui.onNodeWithTag("reasoning-slider").performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.SetProgress,
        ) {
            it(1f)
        }
        assertEquals("high", AssistantState.current.childEffort)
        assertEquals("low", AssistantState.current.effort)
        TestSnapshots.capture(ui, "v072-child-model")
        ui.onNodeWithContentDescription("返回主智能体设置").performClick()
        ui.onNodeWithTag("effort-title").assertTextEquals("Low")
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        ui.onNodeWithContentDescription("操作权限：${accessTitle(AssistantState.current.access)}")
            .performClick()
        assertPopupInsideScreen()
        TestSnapshots.capture(ui, "v072-access-shadow")
    }

    @Test
    fun captchaTextareaOnLoginCannotAuthenticateAndLateSuccessAfterCancelCannotSwitch() {
        ready()
        login()
        js(
            "document.body.insertAdjacentHTML('beforeend','<textarea name=gcaptcha-response></textarea>');true",
        )
        Thread.sleep(800)
        assertEquals("work", AssistantState.appMode)
        assertFalse(OfficialChatNavigation.accessConfirmed)
        ui.onNodeWithContentDescription("关闭面板").performClick()
        ui.runOnUiThread {
            OfficialChatNavigation.view!!.loadDataWithBaseURL(
                "https://chat.deepseek.com/a/chat/s/fixture-12345678",
                OfficialAuthFixture.chat,
                "text/html",
                "UTF-8",
                null,
            )
        }
        Thread.sleep(800)
        assertEquals("work", AssistantState.appMode)
        assertFalse(OfficialChatNavigation.loginOpen)
    }
}
