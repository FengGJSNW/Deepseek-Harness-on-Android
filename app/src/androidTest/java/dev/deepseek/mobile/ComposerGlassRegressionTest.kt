package dev.deepseek.mobile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.inspector.WindowInspector
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Pending device regressions: uses a dedicated emulator, no user session or real request. */
class ComposerGlassRegressionTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private fun emulatorOnly() {
        assumeTrue(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        ui.waitUntil(30000) { AssistantState.ready }
    }

    private fun tap(point: Offset) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val time = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, point.x, point.y, 0)
        down.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            assertTrue(automation.injectInputEvent(down, true))
        } finally {
            down.recycle()
        }
        SystemClock.sleep(20)
        val up =
            MotionEvent.obtain(
                time,
                SystemClock.uptimeMillis(),
                MotionEvent.ACTION_UP,
                point.x,
                point.y,
                0,
            )
        up.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            assertTrue(automation.injectInputEvent(up, true))
        } finally {
            up.recycle()
        }
        ui.waitForIdle()
    }

    private fun mainWindowOffset(): Offset {
        val screen = IntArray(2)
        val window = IntArray(2)
        ui.runOnUiThread {
            ui.activity.window.decorView.getLocationOnScreen(screen)
            ui.activity.window.decorView.getLocationInWindow(window)
        }
        return Offset((screen[0] - window[0]).toFloat(), (screen[1] - window[1]).toFloat())
    }

    private fun tapTag(tag: String) =
        tap(
            ui.onNodeWithTag(tag, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInWindow
                .center + mainWindowOffset(),
        )

    private fun snapshot(name: String): Bitmap {
        TestSnapshots.capture(ui, name)
        return BitmapFactory.decodeFile(File(ui.activity.filesDir, "v060-$name.png").path)
    }

    @Test
    fun sandboxSubmenuCanCloseWithoutDismissingTerminalOrDraft() {
        emulatorOnly()
        val conversation = Conversation().apply { draft = "保留键盘中的输入" }
        ui.runOnUiThread {
            ui.activity.setContent {
                MaterialTheme {
                    FontEnvironment {
                        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                            Column(Modifier.align(Alignment.BottomCenter)) {
                                Composer(conversation) {}
                            }
                        }
                    }
                }
            }
        }
        tapTag("message-input")
        tapTag("toolbar-Terminal")
        val popupOffset = IntArray(2)
        val popupWindow = IntArray(2)
        ui.runOnUiThread {
            val popup =
                WindowInspector.getGlobalWindowViews().last {
                    it.javaClass.name.contains("PopupLayout") && it.isShown
                }
            popup.getLocationOnScreen(popupOffset)
            popup.getLocationInWindow(popupWindow)
        }
        val switch =
            ui.onNodeWithTag("sandbox-switch").fetchSemanticsNode().boundsInWindow.center +
                Offset(
                    (popupOffset[0] - popupWindow[0]).toFloat(),
                    (popupOffset[1] - popupWindow[1]).toFloat(),
                )
        tap(switch)
        ui.onNodeWithTag("sandbox-picker").assertIsDisplayed()
        tap(switch)
        ui.onNodeWithTag("sandbox-picker").assertDoesNotExist()
        ui.onNodeWithTag("toolbar-popover").assertIsDisplayed()
        ui.onNodeWithTag("message-input").assertIsFocused()
        assertEquals("保留键盘中的输入", conversation.draft)
        tapTag("toolbar-Terminal")
        ui.onNodeWithTag("toolbar-popover").assertDoesNotExist()
    }

    @Test
    fun eachComposerControlTogglesItsMenuWithoutLosingTheDraftOrKeyboardFocus() {
        emulatorOnly()
        val conversation = Conversation().apply { draft = "**保持输入**" }
        var sends = 0
        ui.runOnUiThread {
            ui.activity.setContent {
                MaterialTheme {
                    FontEnvironment {
                        Box(
                            Modifier.fillMaxSize()
                                .safeDrawingPadding()
                                .imePadding()
                                .testTag("menu-test-root"),
                        ) {
                            Column(Modifier.align(Alignment.BottomCenter)) {
                                Composer(conversation) { sends++ }
                            }
                        }
                    }
                }
            }
        }
        tapTag("message-input")
        val draft = conversation.draft
        for (tag in
            listOf(
                "toolbar-Paperclip",
                "toolbar-Access",
                "toolbar-Terminal",
                "composer-preview-toggle",
                "model-control",
            )) {
            tapTag(tag)
            ui.onNodeWithTag("toolbar-popover").assertIsDisplayed()
            ui.onNodeWithTag("message-input").assertIsFocused()
            tapTag(tag)
            ui.onNodeWithTag("toolbar-popover").assertDoesNotExist()
            ui.onNodeWithTag("message-input").assertIsFocused()
        }
        // Switching control, Back, and outside taps still dismiss the appropriate pane.
        tapTag("toolbar-Paperclip")
        tapTag("composer-preview-toggle")
        ui.onAllNodesWithTag("toolbar-popover").assertCountEquals(1)
        ui.onNodeWithText("纯文本").assertIsDisplayed()
        ui.runOnUiThread { ui.activity.onBackPressedDispatcher.onBackPressed() }
        ui.onNodeWithTag("toolbar-popover").assertDoesNotExist()
        tapTag("toolbar-Paperclip")
        val page = ui.onNodeWithTag("menu-test-root").fetchSemanticsNode().boundsInWindow
        tap(Offset(page.left + 2f, page.top + 20f) + mainWindowOffset())
        ui.onNodeWithTag("toolbar-popover").assertDoesNotExist()
        assertEquals(draft, conversation.draft)
        assertEquals(0, sends)
    }

    @Test
    fun menuSamplesTheActualWhaleLayerAndRefreshesWhenItDisappears() {
        emulatorOnly()
        var visible by mutableStateOf(true)
        var expanded by mutableStateOf(false)
        ui.runOnUiThread {
            ui.activity.setContent {
                MaterialTheme {
                    val owner = LocalView.current
                    val layer = rememberGraphicsLayer()
                    val source = remember(layer, owner) { ChatBackdrop(layer, owner) }
                    ConversationLayout(
                        Modifier.fillMaxSize().safeDrawingPadding().background(Color.White),
                        backdrop = source,
                        composer = {
                            Box(Modifier.fillMaxWidth().height(140.dp)) {
                                ToolbarMenuAnchor(
                                    expanded,
                                    { expanded = false },
                                    width = 300.dp,
                                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                                    anchor = {
                                        DshaIconButton(
                                            "打开测试菜单",
                                            { expanded = !expanded },
                                            modifier = Modifier.testTag("whale-menu-trigger"),
                                        ) {}
                                    },
                                ) {
                                    Spacer(Modifier.fillMaxWidth().height(180.dp))
                                }
                            }
                        },
                        companion = {
                            if (visible)
                                WhaleDock(
                                    allowBubble = false,
                                    wanderEnabled = false,
                                    idleKey = "glass-regression",
                                )
                        },
                        header = {},
                    ) {
                        Box(Modifier.fillMaxSize().chatBackdrop(source).background(Color.White))
                    }
                }
            }
        }
        tapTag("whale-menu-trigger")
        val local = ui.onNodeWithTag("toolbar-popover").fetchSemanticsNode().boundsInWindow
        val position = IntArray(2)
        ui.runOnUiThread {
            WindowInspector.getGlobalWindowViews()
                .last { it.javaClass.name.contains("PopupLayout") }
                .getLocationOnScreen(position)
        }
        val window = IntArray(2)
        ui.runOnUiThread { ui.activity.window.decorView.getLocationOnScreen(window) }
        val pane =
            local.translate(
                Offset((position[0] - window[0]).toFloat(), (position[1] - window[1]).toFloat()),
            )
        val withWhale = snapshot("085-menu-with-whale")
        ui.runOnUiThread { visible = false }
        val withoutWhale = snapshot("085-menu-without-whale")
        var changed = 0
        val inset = with(ui.density) { 16.dp.toPx() }.toInt()
        for (y in pane.top.toInt() + inset until pane.bottom.toInt() - inset) {
            for (x in pane.left.toInt() + inset until pane.right.toInt() - inset) {
                if (withWhale.getPixel(x, y) != withoutWhale.getPixel(x, y)) changed++
            }
        }
        withWhale.recycle()
        withoutWhale.recycle()
        assertTrue("Whale pixels must contribute inside the popup: $changed", changed > 100)
        tapTag("whale-menu-trigger")
        ui.onNodeWithTag("toolbar-popover").assertDoesNotExist()
    }
}
