package dev.deepseek.mobile

import android.graphics.BitmapFactory
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MarkdownMotionTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private fun ready() {
        ui.waitUntil(30000) { AssistantState.ready }
        ui.runOnUiThread {
            resetTransientUiForTest()
            AssistantState.appMode = "work"
            AssistantState.activeTaskId = ""
            AssistantState.addSession()
            OfficialChatNavigation.cancelLogin()
            WhaleCompanion.settle()
        }
    }

    @Test
    fun commonmarkKeepsCodeEscapesNestedEmphasisTablesAndTasks() {
        val blocks =
            MarkdownDocument.parse(
                "# 标题\n\n**粗体 *斜体*** 与 \\*原文\\*\n\n```kotlin\nval s = \"**保留**\"\n```\n\n| 名称 | 值 |\n| --- | ---: |\n| x | `a\\|b` |\n\n- [x] 完成\n- [ ] 等待\n\n> 引用\n\n~~删除~~",
            )
        assertTrue(blocks.first() is MarkdownBlock.Heading)
        val p = blocks.filterIsInstance<MarkdownBlock.Paragraph>().first().text
        assertEquals("粗体 斜体 与 *原文*", p.text)
        assertTrue(p.spanStyles.any { (it.item.fontWeight?.weight ?: 0) >= 600 })
        assertEquals(
            "val s = \"**保留**\"",
            blocks.filterIsInstance<MarkdownBlock.Code>().first().text,
        )
        assertEquals(
            "a|b",
            blocks.filterIsInstance<MarkdownBlock.Table>().first().rows[1][1].text.text,
        )
        val tasks = blocks.filterIsInstance<MarkdownBlock.Listing>().first().items
        assertEquals(listOf(true, false), tasks.map { it.checked })
        assertTrue(blocks.any { it is MarkdownBlock.Quote })
    }

    @Test
    fun streamingFenceAndUnsafeLinksNeverBecomeExecutableContent() {
        val code =
            MarkdownDocument.parse("```sh\nprintf '**text**'\n").single() as MarkdownBlock.Code
        assertEquals("printf '**text**'", code.text)
        val p =
            MarkdownDocument.parse(
                    "[恶意](javascript:alert) [文件](dsha://file/12345678-1234-1234-1234-123456789abc)",
                )
                .single() as MarkdownBlock.Paragraph
        assertFalse(MarkdownDocument.safeLink("javascript:alert"))
        assertFalse(MarkdownDocument.safeLink("file:///data/private"))
        assertEquals(1, p.text.getLinkAnnotations(0, p.text.length).size)
    }

    @Test
    fun conversationShowsNativeMarkdownAndCopiesCodeWithoutFence() {
        ready()
        val source =
            "# 标题\n\n这是 **粗体** 与 *斜体*，包含 `行内代码`。\n\n> 一段引用\n\n- [x] 已完成\n- 第二项\n\n| 名称 | 值 |\n| --- | --- |\n| x | 2 |\n\n```kotlin\nval x = 2\n```"
        ui.runOnUiThread { AssistantState.current.messages.add(ChatEntry("assistant", source)) }
        ui.onNodeWithTag("markdown-heading").assertTextEquals("标题")
        ui.onNodeWithTag("markdown-table").assertExists()
        ui.onNodeWithTag("markdown-code").performScrollTo().assertIsDisplayed()
        ui.onNodeWithContentDescription("复制代码").performClick()
        var copied = ""
        ui.runOnUiThread {
            copied =
                android.content.ClipData::class.java.let {
                    (ui.activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager)
                        .primaryClip!!
                        .getItemAt(0)
                        .text
                        .toString()
                }
        }
        assertEquals("val x = 2", copied)
        TestSnapshots.capture(ui, "v072-markdown")
    }

    @Test
    fun markdownFileLinkOpensTheWorkspacePreview() {
        ready()
        val attachment =
            AttachmentStore(ui.activity)
                .save(
                    "文件预览成功".byteInputStream(),
                    AssistantState.current.id,
                    "markdown-link.txt",
                    "text/plain",
                )
        ui.runOnUiThread {
            AssistantState.current.messages.add(
                ChatEntry("assistant", "[查看说明](${attachment.link})"),
            )
        }
        ui.onNodeWithText("查看说明").performTouchInput { click() }
        ui.onNodeWithTag("workspace-document").assertIsDisplayed()
        ui.onNodeWithContentDescription("返回会话").assertIsDisplayed().performClick()
    }

    @Test
    fun settingsKeepOneHeaderAndReturnThroughCorrectHierarchyAndDrawer() {
        ready()
        ui.onNodeWithContentDescription("会话列表").performClick()
        ui.onNodeWithContentDescription("设置").performClick()
        ui.onAllNodesWithContentDescription("返回").assertCountEquals(1)
        ui.onNodeWithText("账户与连接").performScrollTo().performClick()
        ui.onNodeWithText("DeepSeek API Key").performClick()
        ui.onAllNodesWithContentDescription("返回").assertCountEquals(1)
        ui.onNodeWithContentDescription("返回").performClick()
        ui.onNodeWithText("DeepSeek Chat 账户").assertIsDisplayed()
        ui.onNodeWithContentDescription("返回").performClick()
        ui.onNodeWithText("手机权限").performScrollTo().performClick()
        ui.onNodeWithText("执行权限与工具").performClick()
        ui.onNodeWithContentDescription("返回").performClick()
        ui.onNodeWithText("无障碍服务").assertIsDisplayed()
        ui.onNodeWithContentDescription("返回").performClick()
        ui.onNodeWithContentDescription("返回").performClick()
        ui.onNodeWithTag("session-drawer").assertIsDisplayed()
        TestSnapshots.capture(ui, "v072-settings-return")
    }

    @Test
    fun lowRailHasNoExposedBluePixelsAtItsLeftEnd() {
        ready()
        ui.runOnUiThread {
            ui.activity.setContent {
                MaterialTheme {
                    Box(Modifier.fillMaxSize().background(Color.White).padding(24.dp)) {
                        EffortRail(0, {}, {}, {}, Modifier.width(260.dp).height(36.dp))
                    }
                }
            }
        }
        val map = ui.onNodeWithTag("reasoning-slider").captureToImage().toPixelMap()
        var blue = 0
        for (y in 0 until map.height) for (x in 0 until map.width) {
            val c = map[x, y]
            if (c.blue > .65f && c.red < .6f && c.blue - c.red > .2f) blue++
        }
        assertEquals("Low must not leave a blue crescent outside the white thumb", 0, blue)
        TestSnapshots.capture(ui, "v072-low-no-blue-edge")
    }

    @Test
    fun addingTallerIslandsKeepsExistingPreviewMeasurements() {
        ready()
        val count = mutableStateOf(1)
        ui.runOnUiThread {
            ui.activity.setContent {
                MaterialTheme {
                    CenterIsland({ count.value = 0 }) {
                        Box(Modifier.fillMaxWidth().height(120.dp)) { Text("第一个预览") }
                    }
                    if (count.value >= 2)
                        CenterIsland({ count.value = 1 }) {
                            Box(Modifier.fillMaxWidth().height(230.dp)) { Text("第二个预览") }
                        }
                    if (count.value >= 3)
                        CenterIsland({ count.value = 2 }) {
                            Box(Modifier.fillMaxWidth().height(340.dp)) { Text("第三个预览") }
                        }
                }
            }
        }
        ui.onNodeWithText("第一个预览").assertIsDisplayed()
        val firstHeight = ui.runOnIdle { IslandStack.heights.getValue(IslandStack.layers.first()) }
        ui.runOnUiThread { count.value = 2 }
        ui.onNodeWithText("第二个预览").assertIsDisplayed()
        val secondHeight = ui.runOnIdle { IslandStack.heights.getValue(IslandStack.layers[1]) }
        ui.runOnUiThread { count.value = 3 }
        ui.onNodeWithText("第三个预览").assertIsDisplayed()
        ui.runOnIdle {
            assertEquals(firstHeight, IslandStack.heights.getValue(IslandStack.layers[0]))
            assertEquals(secondHeight, IslandStack.heights.getValue(IslandStack.layers[1]))
        }
        ui.runOnUiThread { count.value = 1 }
        ui.onNodeWithText("第一个预览").assertIsDisplayed()
    }

    @Test
    fun tallCenterIslandShadowSurvivesAboveComposerAndContentTapDoesNotDismiss() {
        ready()
        val open = mutableStateOf(false)
        ui.runOnUiThread {
            ui.activity.setContent {
                MaterialTheme {
                    Box(Modifier.fillMaxSize().background(Color.White)) {
                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
                            Composer(AssistantState.current, onSend = {})
                        }
                        if (open.value)
                            CenterIsland({ open.value = false }) {
                                Box(Modifier.fillMaxWidth().height(600.dp)) {
                                    Text("投影测试", Modifier.padding(24.dp))
                                }
                            }
                    }
                }
            }
        }
        TestSnapshots.capture(ui, "v072-shadow-background")
        ui.runOnUiThread { open.value = true }
        ui.onNodeWithText("投影测试").performClick()
        assertTrue(open.value)
        val bounds = ui.onNodeWithTag("center-island").fetchSemanticsNode().boundsInRoot
        TestSnapshots.capture(ui, "v072-center-shadow")
        val before =
            BitmapFactory.decodeFile(
                File(ui.activity.filesDir, "v060-v072-shadow-background.png").path,
            )
        val after =
            BitmapFactory.decodeFile(File(ui.activity.filesDir, "v060-v072-center-shadow.png").path)
        val x = after.width / 2
        val whiteBottom =
            (0 until after.height).last { row ->
                val c = after.getPixel(x, row)
                android.graphics.Color.red(c) >= 250 &&
                    android.graphics.Color.green(c) >= 250 &&
                    android.graphics.Color.blue(c) >= 250
            }
        val y =
            (whiteBottom + with(ui.density) { 5.dp.toPx() }).toInt().coerceAtMost(after.height - 1)
        File(ui.activity.filesDir, "v072-shadow-metrics.json")
            .writeText(
                org.json
                    .JSONObject()
                    .put("whiteCardBottom", whiteBottom)
                    .put("sampleX", x)
                    .put("sampleY", y)
                    .toString(2),
            )
        val base = android.graphics.Color.red(before.getPixel(x, y)) * .82f
        val actual = android.graphics.Color.red(after.getPixel(x, y)).toFloat()
        before.recycle()
        after.recycle()
        assertTrue(
            "Expected visible shadow below the card over the toolbar: baseline=$base actual=$actual",
            actual < base - 4,
        )
        ui.onNodeWithTag("island-backdrop").performTouchInput {
            click(androidx.compose.ui.geometry.Offset(6f, 6f))
        }
        assertFalse(open.value)
    }
}
