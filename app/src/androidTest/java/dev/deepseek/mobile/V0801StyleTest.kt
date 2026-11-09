package dev.deepseek.mobile

import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.webkit.WebView
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.*
import org.junit.*
import org.junit.Assert.*

class V0801StyleTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private var original: FontPreset? = null

    private fun ready() {
        ui.waitUntil(30000) { AssistantState.ready && DshaFonts.ready }
        ui.runOnUiThread {
            original = FontSettings.preset
            FontSettings.preset = FontPreset.DEFAULT
            FontSettings.changed()
            resetTransientUiForTest()
            AssistantState.appMode = "work"
            AssistantState.addSession()
        }
    }

    @After
    fun restore() {
        ui.mainClock.autoAdvance = true
        ui.runOnUiThread {
            original?.let {
                FontSettings.preset = it
                FontSettings.changed()
            }
        }
    }

    private fun screen(content: @Composable ColumnScope.() -> Unit) {
        ui.runOnUiThread {
            ui.activity.setContent {
                MaterialTheme(colorScheme = lightColorScheme(primary = WhaleBlue)) {
                    FontEnvironment {
                        Surface(color = Color.White) {
                            Column(
                                Modifier.fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(20.dp)
                                    .testTag("audit-scroll"),
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                                content = content,
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun nativeClaudeReadingTableAndChatGptCodeAndFiles() {
        ready()
        val file =
            AttachmentStore(ui.activity)
                .save(
                    "项目说明".byteInputStream(),
                    AssistantState.current.id,
                    "项目说明.md",
                    "text/markdown",
                )
        val source =
            """## 项目中的文件

一段自然的正文，带有 **克制的强调**、*斜体*和 `inline_code`。

| 文件 | 说明 | 状态 |
| :--- | :--- | ---: |
| `App.kt` | Compose 界面与交互 | 已更新 |
| `memory.md` | 共享的项目记忆 | 待确认 |

> 好的界面，让内容容易阅读，让操作容易发现。

- [x] 调整阅读排版
- [ ] 确认项目改动

```kotlin
val message = "你好，鲸鲸"
println(message)
```
"""
        screen {
            MarkdownText(source)
            AttachmentCard(file.id)
            AttachmentCard(file.id, upload = true)
        }
        ui.onNodeWithTag("markdown-table").assertExists()
        val tablePixels = ui.onNodeWithTag("markdown-table").captureToImage().toPixelMap()
        val separatorPixels =
            (0 until tablePixels.height).count { y ->
                (0 until tablePixels.width).count { x ->
                    val pixel = tablePixels[x, y]
                    kotlin.math.abs(pixel.red - ReadingStyle.line.red) < .015f &&
                        kotlin.math.abs(pixel.green - ReadingStyle.line.green) < .015f &&
                        kotlin.math.abs(pixel.blue - ReadingStyle.line.blue) < .015f
                } > tablePixels.width * .95f
            }
        assertTrue("The cached Claude table keeps header and row separators", separatorPixels >= 2)
        TestSnapshots.capture(ui, "ux-reference-native")
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        ui.onNodeWithText("memory.md").performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult,
        ) {
            it(layouts)
        }
        assertEquals(
            "Short filenames must not be split across lines",
            1,
            layouts.single().lineCount,
        )
        ui.onNodeWithContentDescription("复制代码").performScrollTo().performClick()
        var copied = ""
        ui.runOnUiThread {
            copied =
                (ui.activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager)
                    .primaryClip!!
                    .getItemAt(0)
                    .text
                    .toString()
        }
        assertEquals(
            """val message = "你好，鲸鲸"
println(message)""",
            copied,
        )
        TestSnapshots.capture(ui, "v0801-reading-code")
        ui.onNodeWithContentDescription("下载 项目说明.md").performScrollTo().assertIsDisplayed()
        TestSnapshots.capture(ui, "v0801-file-cards")
    }

    @Test
    fun disclosureMovesThroughIntermediateHeightsAndCanReverse() {
        ready()
        val entries =
            List(3) { i ->
                ChatEntry(
                    "tool",
                    JSONObject()
                        .put("name", "workspace_read")
                        .put("args", JSONObject().put("path", "/workspace/file-$i.md"))
                        .put("status", if (i == 0) "waiting" else "completed")
                        .put("result", "已经读取项目文件。\n内容可供下一步处理。")
                        .toString(),
                )
            }
        screen {
            ToolGroup(entries)
            ThinkingDisclosure(ChatEntry("reasoning", "先查看项目结构，再整理界面层级。"), "")
        }
        ui.waitForIdle()
        ui.onNodeWithTag("tool-group").assertExists()
        ui.mainClock.autoAdvance = false
        fun height() = ui.onNodeWithTag("tool-group").fetchSemanticsNode().boundsInRoot.height
        val closed = height()
        ui.onNodeWithTag("tool-group-toggle").performClick()
        ui.mainClock.advanceTimeByFrame()
        ui.mainClock.advanceTimeBy(80)
        val middle = height()
        TestSnapshots.capture(ui, "v0801-fold-mid")
        ui.mainClock.advanceTimeBy(700)
        val open = height()
        ui.onNodeWithText("待确认").assertIsDisplayed()
        assertTrue(
            "Expansion must have a real intermediate layout height",
            middle > closed + 1 && middle < open - 1,
        )
        TestSnapshots.capture(ui, "v0801-fold-open")
        ui.onNodeWithTag("tool-row-0").performClick()
        ui.mainClock.advanceTimeBy(700)
        ui.onNodeWithTag("tool-detail-0").assertExists()
        TestSnapshots.capture(ui, "v0801-tool-detail")
        ui.onNodeWithTag("tool-group-toggle").performClick()
        ui.mainClock.advanceTimeBy(80)
        ui.onNodeWithTag("tool-group-toggle").performClick()
        ui.mainClock.advanceTimeBy(700)
        ui.onNodeWithTag("tool-row-0").assertIsDisplayed()
        ui.onNodeWithTag("tool-group-toggle").performClick()
        ui.mainClock.advanceTimeBy(700)
        ui.onNodeWithTag("tool-group-body").assertDoesNotExist()
        ui.onNodeWithTag("thinking-toggle").performClick()
        ui.mainClock.advanceTimeBy(700)
        ui.onNodeWithText("先查看项目结构，再整理界面层级。").assertIsDisplayed()
        java.io
            .File(ui.activity.filesDir, "v0801-disclosure-motion.json")
            .writeText(
                JSONObject()
                    .put("closedHeight", closed)
                    .put("heightAt80ms", middle)
                    .put("expandedHeight", open)
                    .put("interruptedReversePassed", true)
                    .toString(2),
            )
        TestSnapshots.capture(ui, "v0801-thinking")
    }

    private fun math(): WebView? {
        fun find(v: View): WebView? {
            if (v is WebView && v.url?.endsWith("/math.html") == true) return v
            if (v is ViewGroup)
                for (i in 0 until v.childCount) find(v.getChildAt(i))?.let {
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

    private fun js(script: String): String {
        val latch = CountDownLatch(1)
        var out = ""
        ui.runOnUiThread {
            math()!!.evaluateJavascript(script) {
                out = JSONTokener(it).nextValue().toString()
                latch.countDown()
            }
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        return out
    }

    @Test
    fun formulaMessageKeepsTheSameTableAndSafeReadingTreatment() {
        ready()
        val d = '$'
        val source =
            """## 数据与推导

行内公式 ${d}E=mc^2${d}，正文保持阅读排版。

| 项目 | 数值 |
| --- | ---: |
| **长度** | 128 |
| `width` | 256 |

> 引用保持轻量。

```python
def greet(name):
    return "Hello, " + name
```

<script>window.hacked=true</script>
"""
        screen { MarkdownText(source) }
        ui.waitUntil(30000) {
            math() != null &&
                js("document.querySelectorAll('.katex').length").toIntOrNull() == 1 &&
                js("document.fonts.status") == "loaded"
        }
        assertEquals("0px", js("getComputedStyle(document.querySelector('th')).borderLeftWidth"))
        assertEquals("0px", js("getComputedStyle(document.querySelector('td')).borderRightWidth"))
        assertEquals("0px", js("getComputedStyle(document.querySelector('td')).paddingLeft"))
        assertEquals("0", js("document.querySelectorAll('.table-card svg').length"))
        assertEquals("0px", js("getComputedStyle(document.querySelector('table')).borderTopWidth"))
        assertTrue(
            js("parseFloat(getComputedStyle(document.querySelector('td + td')).paddingLeft)")
                .toFloat() > 20f,
        )
        assertEquals("580", js("getComputedStyle(document.querySelector('th')).fontWeight"))
        // WebView quantizes CSS borders to physical pixels (1px becomes .888889px at 2.25x).
        for (selector in
            listOf("th" to "borderBottomWidth", "tbody tr:last-child td" to "borderTopWidth")) {
            val width =
                js(
                        "getComputedStyle(document.querySelector('${selector.first}')).${selector.second}",
                    )
                    .removeSuffix("px")
                    .toFloat()
            assertEquals("Internal separators must remain one CSS pixel", 1f, width, .15f)
        }
        assertEquals(
            "rgba(0, 0, 0, 0)",
            js("getComputedStyle(document.querySelector('th')).backgroundColor"),
        )
        assertEquals(
            "right",
            js("getComputedStyle(document.querySelector('td:last-child')).textAlign"),
        )
        assertTrue(js("getComputedStyle(document.body).fontFamily").contains("DSHASerif"))
        assertEquals("undefined", js("typeof window.hacked"))
        assertEquals("1", js("document.querySelectorAll('.table-scroll').length"))
        assertEquals("32px", js("getComputedStyle(document.querySelector('.code-header')).height"))
        assertEquals(
            "0px",
            js("getComputedStyle(document.querySelector('.code-header')).borderBottomWidth"),
        )
        assertTrue(js("document.querySelectorAll('pre code span[style]').length").toInt() >= 3)
        assertEquals(
            "rgb(121, 94, 38)",
            js(
                "getComputedStyle([...document.querySelectorAll('pre code span')].find(s => s.textContent === 'greet')).color",
            ),
        )
        js("document.querySelector('.code-header button').click(); 'clicked'")
        var copiedCode = ""
        ui.runOnUiThread {
            copiedCode =
                (ui.activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager)
                    .primaryClip!!
                    .getItemAt(0)
                    .text
                    .toString()
        }
        assertEquals("def greet(name):\n    return \"Hello, \" + name\n", copiedCode)

        TestSnapshots.capture(ui, "v0801-math-table")
    }

    @Test
    fun emojiStayUprightInsideItalicFormulaMessage() {
        ready()
        val italic = FontSettings.italic
        try {
            ui.runOnUiThread {
                FontSettings.italic = true
                FontSettings.changed()
            }
            val dollar = '$'
            screen { MarkdownText("*Italic 😀 👩🏽‍💻 🇨🇳 1️⃣* and ${dollar}x^2${dollar}.") }
            ui.waitUntil(30000) {
                math() != null && js("document.querySelectorAll('.upright-symbol').length") == "4"
            }
            assertEquals("italic", js("getComputedStyle(document.body).fontStyle"))
            assertEquals(
                "true",
                js(
                    "[...document.querySelectorAll('.upright-symbol')].every(e => getComputedStyle(e).fontStyle === 'normal')",
                ),
            )
            assertEquals(
                "😀👩🏽‍💻🇨🇳1️⃣",
                js(
                    "[...document.querySelectorAll('.upright-symbol')].map(e => e.textContent).join('')",
                ),
            )
            assertEquals("1", js("document.querySelectorAll('.katex').length"))
            TestSnapshots.capture(ui, "ux-italic-math")
        } finally {
            ui.runOnUiThread {
                FontSettings.italic = italic
                FontSettings.changed()
            }
        }
    }

    @Test
    fun htmlTablesAndCodeExposeScrollbarsDuringTwoAxisScrolling() {
        ready()
        val table =
            "| " +
                (1..8).joinToString(" | ") { "列 $it" } +
                " |\n| " +
                (1..8).joinToString(" | ") { "---" } +
                " |\n" +
                (1..30).joinToString("\n") { row ->
                    "| " + (1..8).joinToString(" | ") { "内容 $row-$it" } + " |"
                }
        val code =
            "```python\n" +
                (1..35).joinToString("\n") { "print('" + "wide ".repeat(22) + "')" } +
                "\n```"
        val dollar = '$'
        screen { MarkdownText("${dollar}x^2${dollar}\n\n" + table + "\n\n" + code) }
        ui.waitUntil(30000) {
            math() != null && js("document.querySelectorAll('pre code').length") == "1"
        }
        for (selector in listOf(".table-scroll", "pre > code")) {
            assertEquals(
                "true",
                js(
                    "(() => { const e = document.querySelector('$selector'); return e.scrollWidth > e.clientWidth && e.scrollHeight > e.clientHeight; })()",
                ),
            )
            js(
                "(() => { const e = document.querySelector('$selector'); e.scrollLeft = 100; e.scrollTop = 100; return 'scrolled'; })()",
            )
            ui.waitUntil(3000) {
                js("document.querySelector('$selector').classList.contains('scrollbars-active')") ==
                    "true"
            }
            assertEquals(
                "true",
                js(
                    "(() => { const e = document.querySelector('$selector'); return e.scrollLeft > 0 && e.scrollTop > 0; })()",
                ),
            )
            assertEquals(
                "4px",
                js(
                    "getComputedStyle(document.querySelector('$selector'), '::-webkit-scrollbar').width",
                ),
            )
            assertFalse(
                js(
                    "getComputedStyle(document.querySelector('$selector'), '::-webkit-scrollbar-thumb').backgroundColor",
                ) == "rgba(0, 0, 0, 0)",
            )
            ui.waitUntil(3000) {
                js("document.querySelector('$selector').classList.contains('scrollbars-active')") ==
                    "false"
            }
        }
    }
}
