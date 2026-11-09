package dev.deepseek.mobile

import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inspector.WindowInspector
import android.webkit.WebView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.deepseek.mobile.harness.HarnessCoordinator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class V080InteractionTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @After
    fun closeAnyRuntime() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking { HarnessCoordinator.get(target).runtimes.close() }
        ui.runOnUiThread { LocalTerminalState.sessionId = "" }
    }

    private fun document(suffix: String): WebView? {
        fun find(view: View): WebView? {
            if (view is WebView && view.url?.endsWith(suffix) == true) return view
            if (view is ViewGroup)
                for (i in 0 until view.childCount) find(view.getChildAt(i))?.let {
                    return it
                }
            return null
        }
        var found: WebView? = null
        ui.runOnUiThread {
            found = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull { find(it) }
        }
        return found
    }

    private fun console(): WebView? = document("/console.html")

    private fun js(web: WebView, script: String): String {
        val done = CountDownLatch(1)
        var result = ""
        ui.runOnUiThread {
            web.evaluateJavascript(script) {
                result = runCatching { JSONTokener(it).nextValue().toString() }.getOrDefault(it)
                done.countDown()
            }
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        return result
    }

    @Test
    fun localTerminalTypingImeCtrlCAndSaveUseRealPty() {
        ui.waitUntil(30000) { AssistantState.ready && DshaFonts.ready }
        ui.runOnUiThread {
            resetTransientUiForTest()
            AssistantState.appMode = "work"
            val chat = AssistantState.addSession()
            chat.title = "终端交互验收"
            LocalTerminalState.open(chat.id)
        }
        ui.waitUntil(60000) {
            ui.onAllNodesWithText("Ctrl+C").fetchSemanticsNodes().isNotEmpty() && console() != null
        }
        val web = console()!!
        var polls = 0
        try {
            ui.waitUntil(30000) {
                val screen = js(web, "window.consoleText?.() || ''")
                if (++polls % 50 == 0) {
                    val target = InstrumentationRegistry.getInstrumentation().targetContext
                    java.io
                        .File(target.filesDir, "v080-terminal-debug.txt")
                        .writeText(
                            screen +
                                "\n" +
                                HarnessCoordinator.get(target).runtimes.states.value.toString() +
                                "\n" +
                                js(web, "document.body.innerText"),
                        )
                }
                screen.contains("#")
            }
        } catch (error: Throwable) {
            val target = InstrumentationRegistry.getInstrumentation().targetContext
            java.io
                .File(target.filesDir, "v080-terminal-debug.txt")
                .writeText(
                    js(
                        web,
                        "JSON.stringify({state:document.readyState,screen:window.consoleText?.(),native:typeof ConsoleNative,html:document.body.innerText.slice(0,2000)})",
                    ) + "\n" + HarnessCoordinator.get(target).runtimes.states.value.toString(),
                )
            throw error
        }
        js(web, "window.consoleKey(${JSONObject.quote("printf 'TERM_OK_你好\\n'; stty size\r")})")
        ui.waitUntil(20000) { js(web, "window.consoleText()").contains("TERM_OK_你好") }
        js(
            web,
            "window.consoleKey(${JSONObject.quote("sh -c 'printf \"SLEEP_%s\\n\" RUNNING; exec sleep 30'\r")})",
        )
        // Wait for execution in the foreground child, not the terminal's input echo.
        ui.waitUntil(15000) { js(web, "window.consoleText()").contains("SLEEP_RUNNING") }
        ui.onNodeWithText("Ctrl+C").performClick()
        js(web, "window.consoleKey(${JSONObject.quote("printf 'CTRL_%s\\n' OK\r")})")
        ui.waitUntil(15000) { js(web, "window.consoleText()").contains("CTRL_OK") }
        ui.runOnUiThread { web.requestFocus() }
        js(
            web,
            "window.consoleKey(${JSONObject.quote("printf 'IME_OK_")}); document.querySelector('.xterm-helper-textarea').focus()",
        )
        var committed = false
        ui.runOnUiThread {
            val connection = web.onCreateInputConnection(EditorInfo())
            committed = connection?.commitText("鲸鲸", 1) == true
        }
        assertTrue("WebView must accept a real Android IME input connection", committed)
        try {
            ui.waitUntil(10000) { js(web, "window.consoleText()").contains("IME_OK_鲸鲸") }
        } catch (error: Throwable) {
            java.io
                .File(
                    InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
                    "v080-ime-debug.txt",
                )
                .writeText(
                    js(
                        web,
                        "JSON.stringify({screen:window.consoleText(),active:document.activeElement.tagName,value:document.activeElement.value,focus:document.hasFocus()})",
                    ),
                )
            throw error
        }
        js(web, "window.consoleKey(${JSONObject.quote("\\n'\r")})")
        js(
            web,
            "window.consoleKey(${JSONObject.quote("printf saved-from-pty > terminal-proof.txt; printf 'SAVED_%s\\n' OK\r")})",
        )
        ui.waitUntil(10000) { js(web, "window.consoleText()").contains("SAVED_OK") }
        js(web, "window.consoleKey(${JSONObject.quote("exit\r")})")
        ui.waitUntil(10000) {
            ui.onAllNodesWithTag("terminal-process-exit").fetchSemanticsNodes().isNotEmpty()
        }
        ui.onNodeWithText("Ctrl+C").assertIsNotEnabled()
        TestSnapshots.capture(ui, "v080-local-terminal")
        ui.onNodeWithContentDescription("保存并关闭终端").performClick()
        ui.waitUntil(120000) { LocalTerminalState.sessionId.isBlank() }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val workspace =
            HarnessCoordinator.get(context)
                .workspaces
                .workspace(
                    dev.deepseek.mobile.harness.WorkspaceScope(
                        "session",
                        AssistantState.selectedId,
                    ),
                )
        assertEquals("saved-from-pty", java.io.File(workspace, "terminal-proof.txt").readText())
    }

    @Test
    fun formulasRenderLocallyAndHtmlCannotBecomeExecutable() {
        ui.waitUntil(30000) { AssistantState.ready && DshaFonts.ready }
        val dollar = '$'
        val source =
            """### 读懂一个公式

行内公式 ${dollar}E = mc^2${dollar} 与 **中文说明**。

${dollar}${dollar}\int_0^1 x^2\,dx = \frac{1}{3}${dollar}${dollar}

\(\{x_i\}_{i=1}^{n}\) 保留 TeX 转义。

```python
price = "${dollar}10 and ${dollar}20"
```

<script>window.hacked = true</script>
"""
        val html = MathDocument.html(source)
        assertTrue(html.contains("\\frac{1}{3}"))
        assertTrue(html.contains("\\{x_i\\}"))
        assertFalse(html.contains("<script>"))
        assertTrue(html.contains("price ="))
        val fileUrl = "dsha://file/01234567-89ab-cdef-0123-456789abcdef"
        val linkHtml = MathDocument.html("[附件]($fileUrl) [无效](javascript:alert)")
        assertTrue(linkHtml.contains("href=\"$fileUrl\""))
        assertFalse(linkHtml.contains("href=\"javascript:"))
        ui.runOnUiThread {
            resetTransientUiForTest()
            AssistantState.appMode = "work"
            AssistantState.addSession().messages.add(ChatEntry("assistant", source))
        }
        ui.onNodeWithTag("math-message").assertExists()
        fun math(): WebView? = document("/math.html")
        ui.waitUntil(30000) {
            math()?.let {
                js(it, "document.querySelectorAll('.katex').length").toIntOrNull()?.let { count ->
                    count >= 3 && js(it, "document.fonts.status") == "loaded"
                }
            } == true
        }
        assertEquals("undefined", js(math()!!, "typeof window.hacked"))
        assertTrue(
            js(math()!!, "document.querySelector('pre').textContent")
                .contains("${dollar}10 and ${dollar}20"),
        )
        TestSnapshots.capture(ui, "v080-math")
    }
}
