package dev.deepseek.mobile

import android.webkit.WebView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class HtmlPreviewTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Before
    fun emulatorOnly() {
        assumeTrue(android.os.Build.MODEL.contains("sdk_gphone"))
        ui.waitUntil(30000) { AssistantState.ready }
        ui.runOnUiThread {
            resetTransientUiForTest()
            AssistantState.appMode = "work"
            AssistantState.addSession()
        }
    }

    private fun expectJs(expression: String, expected: String) {
        val result = AtomicReference("")
        ui.waitUntil(15000) {
            ui.runOnUiThread {
                ui.activity.window.decorView
                    .findViewWithTag<WebView>("html-preview-webview")
                    ?.evaluateJavascript(expression) { result.set(it) }
            }
            result.get() == expected
        }
    }

    @Test
    fun opensInteractiveHtmlFromMessagesAndFilesWithoutDuplicateTabs() {
        val store = AttachmentStore(ui.activity)
        val session = AssistantState.selectedId
        val file =
            store.save(
                """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="stylesheet" href="css/theme.css"></head><body><h1 id="title">网页预览</h1><button id="tap" onclick="this.textContent='已点击'">试一下</button><script>window.readyForTest=true</script></body></html>"""
                    .byteInputStream(),
                session,
                "交互示例.html",
                "text/html",
            )
        val css =
            store.save(
                "h1 { color: rgb(12, 34, 56); }".byteInputStream(),
                session,
                "theme.css",
                "text/css",
            )
        store.recordSource(file.id, "runtime/workspace/site/${file.name}", "html-test")
        store.recordSource(css.id, "runtime/workspace/site/css/theme.css", "html-test")
        assertEquals("runtime/workspace/site/css/theme.css", store.get(css.id).sourcePath)
        ui.runOnUiThread {
            AssistantState.current.messages.add(
                ChatEntry("assistant", "预览：", files = listOf(file.id)),
            )
        }
        ui.onNodeWithTag("file-card-${file.id}").performClick()
        ui.onNodeWithTag("workspace-html").assertIsDisplayed()
        expectJs("document.querySelector('#title')?.textContent", "\"网页预览\"")
        expectJs("getComputedStyle(document.querySelector('#title')).color", "\"rgb(12, 34, 56)\"")
        expectJs(
            "document.querySelector('#tap').click();document.querySelector('#tap').textContent",
            "\"已点击\"",
        )
        expectJs("typeof Android + ':' + typeof DSHA", "\"undefined:undefined\"")
        TestSnapshots.capture(ui, "html-workspace-preview")
        ui.onNodeWithContentDescription("查看 HTML 源码").performClick()
        ui.onNodeWithTag("workspace-document").assertIsDisplayed()
        ui.onNodeWithTag("workspace-tab-list")
            .performScrollToNode(hasTestTag("workspace-tab-html-${file.id}"))
        ui.onNodeWithTag("workspace-tab-html-${file.id}").performClick()
        ui.onNodeWithTag("workspace-html").assertIsDisplayed()
        ui.onNodeWithContentDescription("刷新 HTML 预览").performClick()
        expectJs("document.querySelector('#tap')?.textContent", "\"试一下\"")
        ui.runOnUiThread { WorkspaceState.open(WorkspaceTab.Files) }
        ui.waitUntil(10000) {
            ui.onAllNodes(hasText(file.name) and !hasAnyAncestor(hasTestTag("workspace-tabs")))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        ui.onNode(hasText(file.name) and !hasAnyAncestor(hasTestTag("workspace-tabs")))
            .performClick()
        ui.onNodeWithTag("workspace-html").assertIsDisplayed()
        ui.runOnUiThread {
            assertEquals(1, WorkspaceState.tabs.filterIsInstance<WorkspaceTab.Html>().size)
            WorkspaceState.close(WorkspaceState.selected)
            assertEquals(WorkspaceTab.Files, WorkspaceState.selected)
        }
        ui.onNodeWithTag("workspace-html").assertDoesNotExist()
    }
}
