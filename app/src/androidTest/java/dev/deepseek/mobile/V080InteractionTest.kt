package dev.deepseek.mobile

import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.webkit.WebView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.deepseek.mobile.harness.HarnessCoordinator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
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
