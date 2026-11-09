package dev.deepseek.mobile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** UI-only emulator fixtures: no network, Root access, or device control tools. */
class RefinementTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private fun prepare() {
        ui.waitUntil(30_000) { AssistantState.ready }
        ui.runOnUiThread {
            AssistantState.appMode = "work"
            AssistantState.petEnabled = true
            AssistantState.activeTaskId = ""
            AssistantState.addSession().apply {
                title = "慢慢来，和鲸鲸一起"
                effort = "high"
            }
            WhaleCompanion.settle()
        }
    }

    private fun back() =
        InstrumentationRegistry.getInstrumentation()
            .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)

    private fun capture(name: String): Bitmap {
        ui.waitForIdle()
        Thread.sleep(120)
        val done = CountDownLatch(1)
        var result = -1
        lateinit var bitmap: Bitmap
        ui.runOnUiThread {
            val decor = ui.activity.window.decorView
            bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(
                ui.activity.window,
                bitmap,
                {
                    result = it
                    done.countDown()
                },
                Handler(Looper.getMainLooper()),
            )
        }
        check(done.await(5, TimeUnit.SECONDS) && result == PixelCopy.SUCCESS)
        // Own-window composition only; preserve FLAG_SECURE. Popup geometry is measured in screen
        // coordinates.
        ui.runOnUiThread {
            val decor = ui.activity.window.decorView
            val origin = IntArray(2).also { decor.getLocationOnScreen(it) }
            val canvas = Canvas(bitmap)
            android.view.inspector.WindowInspector.getGlobalWindowViews()
                .filter { it.isShown && it !== decor && it.width > 0 }
                .forEach { view ->
                    val xy = IntArray(2).also { view.getLocationOnScreen(it) }
                    val params = view.layoutParams as? android.view.WindowManager.LayoutParams
                    if (
                        params != null &&
                            params.flags and
                                android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0
                    ) {
                        canvas.drawColor(
                            android.graphics.Color.argb((255 * params.dimAmount).toInt(), 0, 0, 0),
                        )
                    }
                    canvas.save()
                    canvas.translate((xy[0] - origin[0]).toFloat(), (xy[1] - origin[1]).toFloat())
                    view.draw(canvas)
                    canvas.restore()
                }
            File(ui.activity.filesDir, "v051-$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        return bitmap
    }

    private fun assertAbove(anchorTag: String) {
        val anchor =
            ui.onNodeWithTag(anchorTag, useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
        val top =
            anchor.top -
                if (anchorTag == "model-control") 0f
                else 11 * ui.activity.resources.displayMetrics.density
        ui.onNodeWithTag("toolbar-popover").assertIsDisplayed()
        ui.runOnUiThread {
            val decor = ui.activity.window.decorView
            val origin = IntArray(2).also { decor.getLocationOnScreen(it) }
            val popup =
                android.view.inspector.WindowInspector.getGlobalWindowViews().last {
                    it.isShown && it !== decor && it.width > 0
                }
            val xy = IntArray(2).also { popup.getLocationOnScreen(it) }
            val bottom = xy[1] - origin[1] + popup.height
            assertTrue(
                "Popup must finish above its own button: $bottom / ${anchor.top}",
                bottom <= top + 2,
            )
            val gap = top - bottom
            assertTrue(
                "Popup must stay near the button: $gap",
                gap < 16 * ui.activity.resources.displayMetrics.density,
            )
            assertTrue(xy[0] >= 0)
            assertTrue(xy[0] + popup.width <= decor.width + 2)
        }
    }

    @Test
    fun toolbarCardsFollowTheirButtonsAndModelHasFiveStops() {
        prepare()
        ui.onNodeWithContentDescription("添加附件").performClick()
        assertAbove("toolbar-Paperclip")
        capture("attachments").recycle()
        back()
        ui.onNodeWithContentDescription("操作权限：受限访问").performClick()
        assertAbove("toolbar-Access")
        capture("access").recycle()
        back()
        ui.onNodeWithContentDescription("终端：已关闭").performClick()
        assertAbove("toolbar-Terminal")
        capture("terminal").recycle()
        back()
        ui.onNodeWithContentDescription("模型与思考设置").performClick()
        assertAbove("model-control")
        ui.onNodeWithText("Off").assertDoesNotExist()
        ui.onNodeWithTag("reasoning-slider").performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.SetProgress,
        ) {
            it(0f)
        }
        ui.runOnUiThread { assertEquals("low", AssistantState.current.effort) }
        ui.onNodeWithTag("effort-title").assertTextEquals("Low")
        capture("model-low").recycle()
        ui.onNodeWithTag("reasoning-slider").performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.SetProgress,
        ) {
            it(4f)
        }
        ui.runOnUiThread { assertEquals("ultra", AssistantState.current.effort) }
        capture("model-ultra").recycle()
        back()
    }

    @Test
    fun addKeyIsCenteredAndIslandCastsVisibleShadow() {
        prepare()
        val island = ui.onNodeWithTag("chat-island", true).fetchSemanticsNode().boundsInWindow
        val bitmap = capture("home-shadow")
        val x =
            (island.left - 3 * ui.activity.resources.displayMetrics.density)
                .toInt()
                .coerceAtLeast(0)
        val y = island.center.y.toInt()
        val pixel = bitmap.getPixel(x, y)
        assertTrue(
            "The outline shadow should visibly darken the surrounding white",
            android.graphics.Color.red(pixel) < 252,
        )
        bitmap.recycle()
        ui.onNodeWithContentDescription("会话列表").performClick()
        ui.onNodeWithContentDescription("设置").performClick()
        ui.onNodeWithText("DeepSeek API Key").performClick()
        ui.onNodeWithText("添加密钥").performClick()
        ui.onNodeWithText("密钥").assertIsDisplayed()
        ui.onNodeWithText("名称").assertIsDisplayed()
        ui.onNodeWithTag("toolbar-popover").assertDoesNotExist()
        capture("add-key-modal").recycle()
        ui.runOnUiThread {
            val decor = ui.activity.window.decorView
            val dialog =
                android.view.inspector.WindowInspector.getGlobalWindowViews().last {
                    it.isShown && it !== decor && it.width > 0
                }
            val xy = IntArray(2).also { dialog.getLocationOnScreen(it) }
            assertTrue(
                kotlin.math.abs(xy[1] + dialog.height / 2f - decor.height / 2f) <
                    100 * ui.activity.resources.displayMetrics.density,
            )
        }
        back()
        ui.onNodeWithText("DeepSeek API Key").assertIsDisplayed()
    }

    @Test
    fun anchoredCardFitsAboveKeyboardAndHeaderBlurHasNoHardSeam() {
        prepare()
        ui.runOnUiThread {
            repeat(16) { index ->
                AssistantState.current.messages.add(
                    ChatEntry(
                        if (index % 3 == 0) "user" else "assistant",
                        "第 ${index + 1} 个想法\n\n把喜欢的事情慢慢做好。让文字穿过玻璃，也让想法保持清晰。\n\n和鲸鲸一起，一步一步来。",
                    ),
                )
            }
        }
        ui.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToIndex(7)
        ui.onAllNodes(hasScrollToIndexAction()).onLast().performTouchInput {
            swipeUp(startY = height * .65f, endY = height * .48f)
        }
        capture("header-blur").recycle()
        ui.onNodeWithTag("message-input").performTextInput("慢慢把事情做好")
        ui.waitUntil(5_000) {
            ui.activity.window.decorView.rootWindowInsets?.isVisible(
                android.view.WindowInsets.Type.ime(),
            ) == true
        }
        ui.onNodeWithContentDescription("操作权限：受限访问").performClick()
        assertAbove("toolbar-Access")
        capture("keyboard-popover").recycle()
        back()
    }
}
