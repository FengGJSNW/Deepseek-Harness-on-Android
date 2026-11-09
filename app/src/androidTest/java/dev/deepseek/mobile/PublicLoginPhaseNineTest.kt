package dev.deepseek.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Read-only public form verification. Never fills credentials, submits login or requests SMS. */
class PublicLoginPhaseNineTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test
    fun publicOfficialLoginMapsToNativeSmsAndPasswordFields() {
        ui.waitUntil(30000) { AssistantState.ready }
        ui.runOnUiThread {
            AssistantState.appMode = "work"
            OfficialChatNavigation.requestChat(ui.activity)
        }
        ui.waitUntil(45000) { OfficialChatNavigation.auth.mode in listOf("sms", "password") }
        assertEquals("work", AssistantState.appMode)
        if (OfficialChatNavigation.auth.mode != "sms") {
            ui.onNodeWithText("验证码登录").performClick()
            ui.waitUntil(10000) {
                OfficialChatNavigation.auth.mode == "sms" && !OfficialChatNavigation.authBusy
            }
        }
        ui.onNodeWithTag("official-login-native").assertIsDisplayed()
        ui.onNodeWithTag("official-login-account").assertExists()
        ui.onNodeWithTag("official-login-secret").assertExists()
        assertNull(OfficialChatNavigation.view!!.parent)
        TestSnapshots.capture(ui, "v072-public-sms-login")
        ui.onNodeWithText("密码登录").performClick()
        ui.waitUntil(10000) {
            OfficialChatNavigation.auth.mode == "password" && !OfficialChatNavigation.authBusy
        }
        ui.onNodeWithTag("official-login-native").assertIsDisplayed()
        assertNull(OfficialChatNavigation.view!!.parent)
        TestSnapshots.capture(ui, "v072-public-password-login")
        File(ui.activity.filesDir, "v072-public-login.json")
            .writeText(
                JSONObject()
                    .put("passed", true)
                    .put("url", OfficialChatNavigation.snapshot.url)
                    .put("nativeSms", true)
                    .put("nativePassword", true)
                    .put("credentialsEntered", false)
                    .put("smsRequested", false)
                    .toString(2),
            )
        ui.runOnUiThread { OfficialChatNavigation.cancelLogin() }
    }
}
