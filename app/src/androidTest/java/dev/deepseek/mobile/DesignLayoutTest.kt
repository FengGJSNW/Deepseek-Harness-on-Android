package dev.deepseek.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Protect usable controls and readable text while a companion occupies the composer edge. */
class DesignLayoutTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private fun screen(name: String) {
        ui.waitForIdle()
        ui.runOnUiThread {
            val view = ui.activity.window.decorView
            val bitmap =
                android.graphics.Bitmap.createBitmap(
                    view.width,
                    view.height,
                    android.graphics.Bitmap.Config.ARGB_8888,
                )
            view.draw(android.graphics.Canvas(bitmap))
            java.io.File(ui.activity.filesDir, "$name.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    @Test
    fun approvalAndCompactToolbarRemainUsable() {
        ui.waitUntil(30000) { AssistantState.ready }
        lateinit var task: PhoneTask
        ui.runOnUiThread {
            val c = AssistantState.addSession()
            c.title = "和鲸鲸一起，慢慢来"
            c.effort = "high"
            c.access = "ask"
            c.messages.add(ChatEntry("user", "帮我把今天的待办整理一下，先从手机里的测试任务开始。"))
            c.messages.add(
                ChatEntry("assistant", "好呀。我们先完成一个小任务，再继续下一件事。\n\n**第一步**：打开手机操控实验室，检查输入和点击是否正常。"),
            )
            AssistantState.petEnabled = true
            AssistantState.petStyle = "ohjing"
            AssistantState.theme = "whale"
            task =
                PhoneTask(
                    sessionId = c.id,
                    text = "整理今天的待办",
                    images = emptyList(),
                    model = c.model,
                    effort = c.effort,
                    access = c.access,
                    phoneTools = true,
                    fileTools = false,
                    workspace = "",
                )
            task.status = "waiting"
            task.approval = true
            task.question = "允许打开手机操控实验室，完成一次输入和点击测试吗？"
            task.steps.add("已经整理好任务顺序")
            AssistantState.tasks.add(task)
            AssistantState.activeTaskId = task.id
            WhaleCompanion.settle()
        }
        try {
            val approve =
                ui.onNodeWithText("允许这次操作")
                    .assertIsDisplayed()
                    .assertHasClickAction()
                    .fetchSemanticsNode()
                    .boundsInRoot
            val pet =
                ui.onNodeWithContentDescription("哦鲸鲸，点击互动，长按打开小窝，可拖动")
                    .assertIsDisplayed()
                    .fetchSemanticsNode()
                    .boundsInRoot
            assertFalse("The pet must not cover an approval button", approve.overlaps(pet))
            screen("v033-approval-layout")
            val glyphs =
                listOf(
                    "toolbar-Paperclip",
                    "toolbar-Access",
                    "toolbar-Terminal",
                )
            val nodes = glyphs.map {
                ui.onNodeWithTag(it, useUnmergedTree = true)
                    .assertWidthIsEqualTo(22.dp)
                    .assertHeightIsEqualTo(22.dp)
                    .fetchSemanticsNode()
            }
            val sendTarget =
                ui.onNodeWithTag("composer-send").assertWidthIsEqualTo(44.dp).fetchSemanticsNode()
            // Equal icon geometry and a 44dp send target; the visible send circle is smaller.
            val model =
                ui.onNodeWithTag("model-control", useUnmergedTree = true)
                    .assertHeightIsEqualTo(44.dp)
                    .fetchSemanticsNode()
            ui.runOnUiThread {
                val bounds = nodes.map { it.boundsInRoot }
                // Fractional display densities accumulate subpixel rounding across nested rows.
                val alignmentTolerance = ui.activity.resources.displayMetrics.density
                listOf(0, 1, 2).forEach {
                    assertEquals(bounds[0].center.y, bounds[it].center.y, alignmentTolerance)
                }
                assertEquals(bounds[1].center.y, model.boundsInRoot.center.y, alignmentTolerance)
                assertEquals(
                    bounds[0].center.y,
                    sendTarget.boundsInRoot.center.y,
                    alignmentTolerance,
                )
            }
            ui.runOnUiThread {
                AssistantState.activeTaskId = ""
                AssistantState.tasks.remove(task)
                AssistantState.theme = "official"
                WhaleCompanion.react("idle", "哦鲸鲸…")
            }
            screen("v033-conversation")
            ui.onNodeWithText("给鲸鲸发送消息").performTextInput("先帮我列出步骤")
            ui.waitUntil(5000) {
                ui.activity.window.decorView.rootWindowInsets?.isVisible(
                    android.view.WindowInsets.Type.ime(),
                ) == true
            }
            ui.waitUntil(5000) {
                val send =
                    ui.onNodeWithTag("toolbar-ArrowUpward", useUnmergedTree = true)
                        .fetchSemanticsNode()
                        .boundsInWindow
                val view = ui.activity.window.decorView
                val keyboard =
                    view.rootWindowInsets.getInsets(android.view.WindowInsets.Type.ime()).bottom
                val window =
                    ui.activity
                        .getSystemService(android.view.WindowManager::class.java)
                        .currentWindowMetrics
                        .bounds
                send.bottom <= window.height() - keyboard + 2
            }
            ui.onNodeWithContentDescription("发送").assertIsEnabled()
            screen("v033-composer-keyboard")
            ui.onNodeWithText("哦鲸鲸…").assertDoesNotExist()
        } finally {
            ui.runOnUiThread {
                AssistantState.activeTaskId = ""
                AssistantState.tasks.remove(task)
                AssistantState.persist()
            }
        }
    }
}
