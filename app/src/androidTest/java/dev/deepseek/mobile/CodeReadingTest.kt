package dev.deepseek.mobile

import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CodeReadingTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test
    fun tokensRespectLanguageAndPreserveSource() {
        fun colorAt(source: String, language: String, token: String): Color? {
            val text = CodeHighlight.text(source, language)
            assertEquals(source, text.text)
            val start = source.indexOf(token)
            assertTrue(start >= 0)
            return text.spanStyles.firstOrNull { start in it.start until it.end }?.item?.color
        }
        val json = "{\"name\": \"whale\", \"count\": 12, \"ready\": true}"
        assertEquals(CodeHighlight.property, colorAt(json, "json", "\"name\""))
        assertEquals(CodeHighlight.string, colorAt(json, "json", "\"whale\""))
        assertEquals(CodeHighlight.number, colorAt(json, "json", "12"))
        assertNull(colorAt("value = 8 // 2", "python", "//"))
        assertEquals(CodeHighlight.comment, colorAt("// comment", "javascript", "comment"))
        assertEquals(CodeHighlight.comment, colorAt("SELECT name -- comment", "sql", "comment"))
        assertEquals(CodeHighlight.keyword, colorAt("SELECT name FROM items", "sql", "SELECT"))
        assertEquals(CodeHighlight.function, colorAt("def greet(name):", "python", "greet"))
        assertEquals(CodeHighlight.string, colorAt("\"\"\"return\nclass\"\"\"", "python", "class"))
        assertEquals(
            CodeHighlight.property,
            colorAt("echo \"hi\"; echo ${'$'}HOME", "bash", "${'$'}HOME"),
        )
        assertTrue(CodeHighlight.text("class x = 12", "plaintext").spanStyles.isEmpty())
        val malicious = "<script>alert(1)</script> & \"quoted\""
        val html = CodeHighlight.html(malicious, "html")
        assertFalse(html.contains("<script>"))
        assertTrue(html.contains("&lt;"))
        assertTrue(html.contains("&amp;"))
        assertTrue(CodeHighlight.text("x".repeat(65537), "python").spanStyles.isEmpty())
    }

    @Test
    fun claudeCodeUsesQuietCaptionAndVisibleLanguageTokens() {
        ui.waitUntil(30000) { AssistantState.ready && DshaFonts.ready }
        val source =
            """```python
def greet(name: str):
    # 说明意图，不抢正文
    return "Hello, " + name
```

```kotlin
val count = 12
fun greet() = println("你好")
```

```json
{"name": "whale", "count": 12}
```

```sql
SELECT name FROM items
WHERE count > 12 -- filter
```"""
        ui.runOnUiThread {
            ui.activity.setContent {
                MaterialTheme {
                    FontEnvironment {
                        Surface(color = Color.White) {
                            Column(
                                Modifier.fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(20.dp),
                            ) {
                                MarkdownText(source)
                            }
                        }
                    }
                }
            }
        }
        ui.onAllNodesWithTag("code-caption").assertCountEquals(4)
        ui.onAllNodesWithTag("code-caption")[0].assertHeightIsEqualTo(32.dp)
        ui.onAllNodesWithContentDescription("复制代码")[0].performClick()
        ui.onNodeWithText("已复制").assertExists()
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
            "def greet(name: str):\n    # 说明意图，不抢正文\n    return \"Hello, \" + name",
            copied,
        )
        TestSnapshots.capture(ui, "ux-code-languages")
    }
}
