package dev.deepseek.mobile

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test

/** Metadata from the public sign-in document only; never reads entered values or auth storage. */
class PublicLoginDiagnosticTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test
    fun publicDocumentMetadata() {
        ui.waitUntil(30000) { AssistantState.ready }
        ui.runOnUiThread {
            AssistantState.appMode = "work"
            OfficialChatNavigation.requestChat(ui.activity)
            OfficialChatNavigation.view!!.loadUrl("https://chat.deepseek.com/sign_in")
        }
        Thread.sleep(12000)
        val data = runBlocking {
            withContext(Dispatchers.Main) {
                OfficialChatNavigation.evaluate(
                    """
                    (()=>({url:location.href,title:document.title,language:document.documentElement.lang,agentLanguage:navigator.language,
                    inputs:[...document.querySelectorAll('input')].map(e=>({type:e.type,placeholder:e.placeholder,autocomplete:e.autocomplete,className:e.className})),
                    buttons:[...document.querySelectorAll('[role=button],button')].map(e=>e.innerText).slice(0,20),
                    publicText:location.pathname==='/sign_in'?document.body?.innerText.slice(0,500):''}))()
                    """
                        .trimIndent(),
                )
            }
        }
        runBlocking {
            withContext(Dispatchers.Main) {
                OfficialChatNavigation.evaluate(
                    "[...document.querySelectorAll('[role=button],button')].find(e=>e.innerText.trim()==='Login with password')?.click();true",
                )
            }
        }
        Thread.sleep(500)
        val password = runBlocking {
            withContext(Dispatchers.Main) {
                OfficialChatNavigation.evaluate(
                    """
                    (()=>({inputs:[...document.querySelectorAll('input')].map(e=>({type:e.type,placeholder:e.placeholder,autocomplete:e.autocomplete})),buttons:[...document.querySelectorAll('[role=button],button')].map(e=>e.innerText).slice(0,20)}))()
                    """
                        .trimIndent(),
                )
            }
        }
        File(ui.activity.filesDir, "v072-public-diagnostic.json")
            .writeText(
                JSONObject()
                    .put("dom", data)
                    .put("password", password)
                    .put("nativeFailure", OfficialChatNavigation.failure)
                    .put("mode", OfficialChatNavigation.auth.mode)
                    .put("loading", OfficialChatNavigation.loading)
                    .toString(2),
            )
    }
}
