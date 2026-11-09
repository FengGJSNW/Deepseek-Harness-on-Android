package dev.deepseek.mobile

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Native login editing regression; entirely synthetic DOM, no login or SMS transmission. */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class OfficialChatInputTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test
    fun latinNumbersCompositionAndMiddleEditingKeepCaret() {
        ui.waitUntil(30000) { AssistantState.ready }
        ui.runOnUiThread {
            AssistantState.appMode = "work"
            OfficialChatNavigation.requestChat(ui.activity)
            OfficialChatNavigation.view!!.stopLoading()
            OfficialChatNavigation.view!!.loadDataWithBaseURL(
                "https://chat.deepseek.com/sign_in",
                OfficialAuthFixture.login,
                "text/html",
                "UTF-8",
                null,
            )
        }
        ui.waitUntil(15000) { OfficialChatNavigation.auth.mode == "sms" }
        val field = ui.onNodeWithTag("official-login-account")
        for (letter in "137abc") field.performTextInput(letter.toString())
        field.assertTextContains("137abc")
        field.performTextInputSelection(TextRange(3))
        field.performTextInput("X")
        field.assertTextContains("137Xabc")
        lateinit var connection: InputConnection
        ui.runOnUiThread {
            val root =
                android.view.inspector.WindowInspector.getGlobalWindowViews().last {
                    it.hasWindowFocus()
                }
            connection = root.findFocus()!!.onCreateInputConnection(EditorInfo())!!
        }
        val done = CountDownLatch(1)
        (connection.handler ?: android.os.Handler(android.os.Looper.getMainLooper())).post {
            try {
                connection.beginBatchEdit()
                connection.setComposingText("jing", 1)
                connection.setComposingText("jingjing", 1)
                connection.commitText("鲸鲸", 1)
                connection.finishComposingText()
                connection.endBatchEdit()
            } finally {
                done.countDown()
            }
        }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        ui.waitForIdle()
        field.assertTextContains("137X鲸鲸abc")
        assertEquals("work", AssistantState.appMode)
        assertNull(OfficialChatNavigation.view!!.parent)
    }
}
