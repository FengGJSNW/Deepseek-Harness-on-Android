package dev.deepseek.mobile

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipboardManager
import android.os.SystemClock
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebView
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ComposerMarkdownTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private fun setPreview(enabled: Boolean) {
        ui.onNodeWithTag("composer-preview-toggle").performClick()
        ui.onNodeWithText(if (enabled) "Markdown 预览" else "纯文本").performClick()
        if (enabled) ui.onNodeWithTag("composer-preview-toggle").assertIsOn()
        else ui.onNodeWithTag("composer-preview-toggle").assertIsOff()
    }

    private fun screen(content: @Composable () -> Unit) {
        ui.waitUntil(30000) { AssistantState.ready }
        ui.runOnUiThread { ui.activity.setContent { MaterialTheme(content = content) } }
        ui.waitForIdle()
    }

    @Test
    fun selectedBodyFontAndForegroundPreviewDefaultAreRespected() {
        ui.waitUntil(30000) { AssistantState.ready && DshaFonts.ready }
        val previous = FontSettings.preset
        lateinit var conversation: Conversation
        ui.runOnUiThread {
            resetTransientUiForTest()
            AssistantState.appMode = "work"
            AssistantState.petEnabled = false
            conversation = AssistantState.addSession("")
            conversation.draft = "Anthropic 风格输入 · 跟随选定字体\n\n**Markdown 预览**"
        }
        try {
            for (preset in listOf(FontPreset.ANTHROPIC, FontPreset.SYSTEM, FontPreset.MONO)) {
                ui.runOnUiThread {
                    FontSettings.preset = preset
                    FontSettings.changed()
                }
                val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                ui.onNodeWithTag("message-input").performSemanticsAction(
                    androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult,
                ) {
                    it(layouts)
                }
                assertEquals(
                    DshaFonts.native(FontRole.BODY),
                    androidx.compose.ui.text.font
                        .createFontFamilyResolver(ui.activity)
                        .resolve(layouts.single().layoutInput.style.fontFamily)
                        .value,
                )
            }
            ui.runOnUiThread {
                FontSettings.preset = FontPreset.ANTHROPIC
                FontSettings.changed()
                AssistantState.persist()
            }
            TestSnapshots.capture(ui, "composer-selected-font")
            setPreview(true)
            ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            ui.onNodeWithTag("composer-preview-toggle").assertIsOff()
            ui.onNodeWithTag("message-input").assertTextContains(conversation.draft)
            setPreview(true)
            ui.activityRule.scenario.recreate()
            ui.onNodeWithTag("composer-preview-toggle").assertIsOff()
            ui.onNodeWithTag("message-input").assertTextContains(conversation.draft)
            ui.onNodeWithContentDescription("会话列表").performClick()
            ui.onNodeWithText("Work").assertIsDisplayed()
            ui.onNodeWithText("Clash").assertDoesNotExist()
            ui.onNodeWithText("Sandbox").assertIsDisplayed()
            TestSnapshots.capture(ui, "sidebar-larger-type")
        } finally {
            ui.runOnUiThread {
                FontSettings.preset = previous
                FontSettings.changed()
            }
        }
    }

    @Test
    fun draftGrowsWithLinesAndWrappingThenShrinksWithinHalfScreen() {
        val conversation = Conversation()
        screen {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.align(Alignment.BottomCenter)) { Composer(conversation) {} }
            }
        }
        fun height() = ui.onNodeWithTag("chat-island").fetchSemanticsNode().size.height
        val empty = height()
        ui.onNodeWithTag("composer-resize").assertDoesNotExist()
        ui.onNodeWithTag("composer-preview-toggle").assertIsNotEnabled()
        ui.runOnUiThread { conversation.draft = "第一行\n第二行\n第三行\n第四行" }
        val multiline = height()
        assertTrue("Adding lines must grow the composer", multiline > empty)
        ui.runOnUiThread { conversation.draft = "自动换行也应增高。".repeat(25) }
        assertTrue("Soft wrapping must also grow the composer", height() > multiline)
        val source = (1..50).joinToString("\n\n") { "第 $it 段内容" } + "\n\n末尾条目"
        ui.runOnUiThread { conversation.draft = source }
        val config = ui.activity.resources.configuration
        val cap = config.screenHeightDp * ui.activity.resources.displayMetrics.density / 2
        val full = height()
        assertTrue(
            "The entire panel, including toolbar, must fit half the screen",
            full <= cap + 1 && full >= cap - 2,
        )
        ui.onNodeWithTag("composer-send").assertIsDisplayed()
        setPreview(true)
        assertTrue(height() <= cap + 1)
        ui.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        setPreview(false)
        ui.onNodeWithTag("message-input").assertTextContains(source)
        assertEquals(source, conversation.draft)
        ui.runOnUiThread { conversation.draft = "短句" }
        assertEquals("Deleting lines restores a compact composer", empty, height())
    }

    @Test
    fun markdownToolbarToggleKeepsDraftAndSitsBetweenTerminalAndModel() {
        ui.waitUntil(30000) { AssistantState.ready && DshaFonts.ready }
        val source = "## 周末的小计划\n\n- 整理 **读书笔记**\n- 写一个小工具\n- 给自己留一点时间"
        lateinit var conversation: Conversation
        ui.runOnUiThread {
            resetTransientUiForTest()
            AssistantState.appMode = "work"
            conversation = AssistantState.addSession("")
            conversation.draft = source
        }
        val terminal =
            ui.onNodeWithTag("toolbar-Terminal", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        val toggle = ui.onNodeWithTag("composer-preview-toggle").fetchSemanticsNode().boundsInRoot
        val model =
            ui.onNodeWithTag("model-control", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        assertTrue(terminal.right <= toggle.left && toggle.right <= model.left)
        ui.onNodeWithTag("composer-resize").assertDoesNotExist()
        ui.onNodeWithText("编辑").assertDoesNotExist()
        TestSnapshots.capture(ui, "composer-auto-edit")
        setPreview(true)
        assertRenderedDraft("周末的小计划")
        ui.onNodeWithTag("message-input").assertIsDisplayed().assertTextContains(source)
        TestSnapshots.capture(ui, "composer-auto-preview")
        setPreview(false)
        ui.onNodeWithTag("message-input").assertTextContains(source)
        assertEquals(source, conversation.draft)
        ui.onNodeWithTag("message-input")
            .performTextReplacement((1..35).joinToString("\n") { "第 $it 行" })
        ui.awaitStableComposerTarget("composer-send")
        val panel = ui.onNodeWithTag("chat-island").fetchSemanticsNode().boundsInRoot
        val title = ui.onNodeWithTag("conversation-title").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "An expanded composer must remain below the title while typing",
            panel.top > title.bottom,
        )
        val send =
            ui.onNodeWithTag("composer-send").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        ui.runOnIdle {
            val keyboard =
                ui.activity.window.decorView.rootWindowInsets
                    .getInsets(android.view.WindowInsets.Type.ime())
                    .bottom
            assertTrue(
                "The send control stays above the IME",
                send.bottom <= ui.activity.window.decorView.height - keyboard + 2,
            )
        }
        TestSnapshots.capture(ui, "composer-auto-keyboard")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun livePreviewKeepsKeyboardFocusCursorAndEditableDraft() {
        ui.waitUntil(30000) { AssistantState.ready && DshaFonts.ready }
        lateinit var conversation: Conversation
        ui.runOnUiThread {
            resetTransientUiForTest()
            AssistantState.appMode = "work"
            AssistantState.petEnabled = false
            conversation = AssistantState.addSession("")
            conversation.draft = "## Live preview\n\nTyping stays here"
        }
        val editor = ui.onNodeWithTag("message-input")
        editor.performClick()
        ui.awaitStableComposerTarget("composer-preview-toggle")
        fun keyboardVisible(): Boolean {
            var visible = false
            ui.runOnIdle {
                visible =
                    ui.activity.window.decorView.rootWindowInsets.isVisible(
                        android.view.WindowInsets.Type.ime(),
                    )
            }
            return visible
        }
        ui.waitUntil(5000) { keyboardVisible() }
        editor.performTextInputSelection(androidx.compose.ui.text.TextRange(7))
        val before = editor.fetchSemanticsNode().id
        val selection =
            editor
                .fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsProperties.TextSelectionRange]
        setPreview(true)
        editor.assertIsFocused().assertIsDisplayed()
        assertEquals(
            "Preview preserves the live editor node",
            before,
            editor.fetchSemanticsNode().id,
        )
        assertEquals(
            selection,
            editor
                .fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsProperties.TextSelectionRange],
        )
        assertTrue("Enabling preview must not dismiss the keyboard", keyboardVisible())
        val label =
            ui.onNodeWithTag("composer-preview-label")
                .assertTextEquals("Markdown 预览")
                .fetchSemanticsNode()
                .boundsInRoot
        val input = editor.fetchSemanticsNode().boundsInRoot
        assertTrue(label.bottom < input.top)
        assertEquals(input.center.x, label.center.x, 2f)
        editor.performTextInput(" updated")
        assertEquals("## Live updated preview\n\nTyping stays here", conversation.draft)
        assertRenderedDraft("Live updated preview")
        editor.assertIsFocused()
        assertTrue(keyboardVisible())
        TestSnapshots.capture(ui, "composer-live-preview-keyboard")
        setPreview(false)
        editor.assertIsFocused()
        assertTrue("Disabling preview also preserves the keyboard", keyboardVisible())
        editor.performTextInput(" again")
        assertEquals("## Live updated again preview\n\nTyping stays here", conversation.draft)
        ui.onNodeWithTag("composer-preview-label").assertDoesNotExist()
    }

    @Test
    fun previewModeDoesNotCarryOverToAnotherConversation() {
        val first = Conversation().apply { draft = "**第一份草稿**" }
        val second = Conversation().apply { draft = "第二份草稿" }
        var selected by mutableStateOf(first)
        screen {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.align(Alignment.BottomCenter)) { Composer(selected) {} }
            }
        }
        setPreview(true)
        ui.runOnUiThread { selected = second }
        ui.onNodeWithTag("composer-preview-toggle").assertIsOff()
        ui.onNodeWithTag("message-input").assertTextEquals(second.draft)
        assertEquals("**第一份草稿**", first.draft)
    }

    @Test
    fun copyFormatsOnlyTheSelectedRangeAndKeepsMarkdownStructure() {
        val parsed =
            MarkdownDocument.parse("Skip **bold words** and [link](https://example.com).").single()
                as MarkdownBlock.Paragraph
        val p = parsed.text
        val selected = p.subSequence(p.text.indexOf("words"), p.text.indexOf('.'))
        assertEquals("words and link", MarkdownSelection.copy(selected, false))
        assertEquals(
            "**words** and [link](<https://example.com>)",
            MarkdownSelection.copy(selected, true),
        )
        val pieces =
            MarkdownSelection.block(AnnotatedString("Title"), "prefix:# ") +
                MarkdownSelection.block(AnnotatedString("echo hi"), "code:sh")
        assertEquals("Title\necho hi", MarkdownSelection.copy(pieces, false))
        assertEquals("# Title\n\n```sh\necho hi\n```", MarkdownSelection.copy(pieces, true))
    }

    @Test
    fun userBubbleAndAgentReplyExposeCopyActions() {
        screen {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                ChatMessage(ChatEntry("user", "selectable"))
                ChatMessage(ChatEntry("assistant", "**bold**"), showHeader = false)
            }
        }
        ui.onNodeWithText("selectable").performTouchInput { longClick() }
        copy(ui.activity.getString(android.R.string.copy), "selectable")
        ui.onNodeWithText("bold").performTouchInput { longClick() }
        copy("复制文本", "bold")
        ui.onNodeWithText("bold").performTouchInput { longClick() }
        copy("复制 Markdown", "**bold**")
    }

    @Test
    fun replyContainingMathOffersMarkdownCopyForTheSelectedWords() {
        screen {
            ChatMessage(ChatEntry("assistant", "**formula** and \\(x + 1\\)"), showHeader = false)
        }
        val boundsScript =
            """
            (() => {
                const strong = document.querySelector('article strong');
                if (!strong || !window.selectedMarkdown) return null;
                const rect = strong.getBoundingClientRect();
                return [(rect.x + rect.width / 2) * devicePixelRatio, (rect.y + rect.height / 2) * devicePixelRatio];
            })()
            """
                .trimIndent()
        fun webviews(view: View): Sequence<WebView> = sequence {
            if (view is WebView) yield(view)
            if (view is ViewGroup)
                for (i in 0 until view.childCount) yieldAll(webviews(view.getChildAt(i)))
        }
        val result = ArrayBlockingQueue<String>(1)
        var bounds = "null"
        ui.waitUntil(15000) {
            ui.runOnUiThread {
                val view =
                    webviews(ui.activity.window.decorView).firstOrNull {
                        it.url?.contains("document.dsha.invalid") == true
                    }
                if (view != null)
                    view.evaluateJavascript(boundsScript) { result.offer(it ?: "null") }
                else result.offer("null")
            }
            bounds = result.poll(1, TimeUnit.SECONDS) ?: "null"
            bounds != "null"
        }
        val point = JSONArray(bounds)
        ui.onNodeWithTag("math-message").performTouchInput {
            down(Offset(point.getDouble(0).toFloat(), point.getDouble(1).toFloat()))
        }
        // Chromium's gesture detector uses real uptime, unlike Compose's synthetic event clock.
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 250L)
        ui.onNodeWithTag("math-message").performTouchInput { up() }
        copy("复制 Markdown", "**formula**")
    }

    private fun assertRenderedDraft(expected: String) {
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        ui.onNodeWithTag("message-input").performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult,
        ) {
            it(layouts)
        }
        assertTrue(layouts.single().layoutInput.text.text.contains(expected))
        assertFalse(layouts.single().layoutInput.text.text.contains("## "))
        ui.onAllNodes(hasSetTextAction()).assertCountEquals(1)
    }

    private fun copy(label: String, expected: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val service = automation.serviceInfo
        val originalFlags = service.flags
        automation.serviceInfo = service.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        fun find(label: String): AccessibilityNodeInfo? =
            automation.windows
                .asSequence()
                .mapNotNull { it.root }
                .flatMap { it.findAccessibilityNodeInfosByText(label).asSequence() }
                .firstOrNull {
                    it.text?.toString() == label || it.contentDescription?.toString() == label
                }
        try {
            ui.waitUntil(5000) {
                val node = find(label) ?: return@waitUntil false
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
                    node.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            }
            ui.waitUntil(5000) {
                var actual: String? = null
                ui.runOnUiThread {
                    actual =
                        ui.activity
                            .getSystemService(ClipboardManager::class.java)
                            .primaryClip
                            ?.getItemAt(0)
                            ?.text
                            ?.toString()
                }
                actual == expected
            }
        } finally {
            automation.serviceInfo = service.apply { flags = originalFlags }
        }
    }
}
