package dev.deepseek.mobile

import android.app.Application
import android.graphics.RenderNode
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h850dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class GlassPressTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private lateinit var motion: GlassPressMotion
    private var clicks = 0
    private var previousLiquid = false

    @Before
    fun enableExperimentalGlassForOpticsTests() {
        previousLiquid = ExperienceSettings.liquidGlassEnabled
        ExperienceSettings.liquidGlassEnabled = true
    }

    @After
    fun restoreGlassPreference() {
        ExperienceSettings.liquidGlassEnabled = previousLiquid
    }

    @Test
    fun productionGlassSamplesLiveDisplayListWhileBackgroundMoves() {
        lateinit var recordedSource: ChatBackdrop
        lateinit var list: androidx.compose.foundation.lazy.LazyListState
        ui.setContent {
            val source = rememberGraphicsLayer().let { remember(it) { ChatBackdrop(it) } }
            val messages = rememberGraphicsLayer().let { remember(it) { ChatBackdrop(it) } }
            recordedSource = source
            list = rememberLazyListState()
            Box(
                Modifier.fillMaxSize().onPlaced {
                    source.viewport = it
                    messages.viewport = it
                },
            ) {
                Box(Modifier.fillMaxSize().chatBackdrop(source, listOf(messages))) {
                    Box(Modifier.fillMaxSize().chatBackdrop(messages)) {
                        LazyColumn(state = list) {
                            items(80) {
                                Canvas(Modifier.fillMaxWidth().height(64.dp)) {
                                    drawRect(Color.White)
                                    for (y in 0..7) drawRect(
                                        Color(0xFF1E41AF),
                                        Offset(0f, y * 8f),
                                        Size(size.width, 4f),
                                    )
                                }
                            }
                        }
                    }
                }
                CompositionLocalProvider(LocalGlassBackdrop provides source) {
                    Box(Modifier.padding(80.dp)) {
                        DshaGlassGroup {
                            DshaIconButton("sample-left", {}, size = 52.dp) {}
                            DshaIconButton("sample-right", {}, size = 52.dp) {}
                        }
                    }
                }
                FrostedGlass(
                    source,
                    Modifier.offset(80.dp, 160.dp).size(96.dp, 44.dp).testTag("frosted-sample"),
                    Color.Transparent,
                    radius = 1f,
                )
            }
        }
        fun capture(name: String? = null): android.graphics.Bitmap {
            lateinit var bitmap: android.graphics.Bitmap
            ui.runOnIdle {
                val view = ui.activity.window.decorView
                val node = RenderNode("compose-glass")
                node.setPosition(0, 0, view.width, view.height)
                view.draw(node.beginRecording(view.width, view.height))
                node.endRecording()
                androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
                bitmap = renderHardware(node, view.width, view.height)
                if (name != null) saveGlassFrame(bitmap, name)
            }
            return bitmap
        }
        val bounds =
            ui.onNodeWithContentDescription("sample-left").fetchSemanticsNode().boundsInRoot
        val rest = capture("085-glass-live-rest")
        ui.runOnIdle { runBlocking { list.scrollToItem(0, 4) } }
        val moved = capture("085-glass-live-moved")
        var changed = 0
        var blue = 0
        for (x in (bounds.left.toInt() + 14)..(bounds.right.toInt() - 8)) {
            for (y in (bounds.top.toInt() + 14)..(bounds.bottom.toInt() - 14)) {
                if (rest.getPixel(x, y) != moved.getPixel(x, y)) changed++
                val pixel = rest.getPixel(x, y)
                // White readability tint raises all channels; retained blue chroma proves sampling.
                if (android.graphics.Color.blue(pixel) - android.graphics.Color.red(pixel) > 20)
                    blue++
            }
        }
        assertTrue("actual pane must see the blue background: $blue", blue > 100)
        assertTrue(
            "background-only invalidation must update the actual pane: $changed",
            changed > 300,
        )
        val frosted = ui.onNodeWithTag("frosted-sample").fetchSemanticsNode().boundsInRoot
        var frostedChanged = 0
        for (x in (frosted.left.toInt() + 8)..(frosted.right.toInt() - 8)) {
            for (y in (frosted.top.toInt() + 8)..(frosted.bottom.toInt() - 8)) {
                if (rest.getPixel(x, y) != moved.getPixel(x, y)) frostedChanged++
            }
        }
        assertTrue(
            "existing frosted consumers must also update: $frostedChanged",
            frostedChanged > 300,
        )
        // Scrolling invalidated both nested backgrounds. Flush their display lists before
        // using the revision as a baseline for a press that changes only the foreground.
        ui.mainClock.autoAdvance = false
        ui.mainClock.advanceTimeBy(500)
        capture().recycle()
        ui.mainClock.advanceTimeBy(320)
        capture().recycle()
        ui.waitForIdle()
        val revision = recordedSource.revision
        capture("085-glass-live-idle").recycle()
        ui.runOnIdle {
            assertEquals(
                "idle pane must not rerecord the background",
                revision,
                recordedSource.revision,
            )
        }
        val target = ui.onNodeWithContentDescription("sample-right")
        val original = target.fetchSemanticsNode().boundsInRoot
        target.performTouchInput { down(center) }
        ui.mainClock.advanceTimeBy(500)
        capture("085-glass-live-held")
        target.performTouchInput { moveBy(Offset(16f, 7f)) }
        ui.mainClock.advanceTimeByFrame()
        ui.waitForIdle()
        ui.mainClock.advanceTimeBy(300)
        capture("085-glass-live-pulled")
        assertEquals(original, target.fetchSemanticsNode().boundsInRoot)
        ui.runOnIdle {
            assertEquals(
                "press must not rerecord the background",
                revision,
                recordedSource.revision,
            )
        }
        target.performTouchInput { cancel() }
        ui.mainClock.advanceTimeBy(1800)
        capture("085-glass-live-released")
    }

    private fun button(enabled: Boolean = true) {
        ui.setContent {
            Box(Modifier.padding(80.dp)) {
                DshaIconButton(
                    "glass",
                    { clicks++ },
                    size = 52.dp,
                    glass = true,
                    enabled = enabled,
                ) {
                    motion = checkNotNull(LocalGlassPress.current)
                }
            }
        }
        ui.mainClock.autoAdvance = false
    }

    @Test
    fun holdGrowsAndPullHasResistanceWithoutMovingClickBounds() {
        button()
        val target = ui.onNodeWithContentDescription("glass")
        val original = target.fetchSemanticsNode().boundsInRoot
        target.performTouchInput { down(center) }
        ui.mainClock.advanceTimeBy(500)
        ui.runOnIdle { assertTrue(motion.transform().scaleX > 1.2f) }
        target.performTouchInput { moveBy(Offset(18f, 0f)) }
        ui.mainClock.advanceTimeByFrame()
        ui.waitForIdle()
        ui.mainClock.advanceTimeBy(250)
        ui.runOnIdle {
            assertTrue(
                "held=${motion.held} drag=${motion.drag} pull=${motion.transform().offset}",
                motion.transform().offset.x > 3f,
            )
            assertTrue(motion.transform().offset.x < 10f)
        }
        assertEquals(original, target.fetchSemanticsNode().boundsInRoot)
        target.performTouchInput { up() }
        // Deliver the release to the UI thread before advancing the paused animation clock.
        ui.waitForIdle()
        ui.mainClock.advanceTimeBy(1800)
        ui.runOnIdle {
            assertEquals(1, clicks)
            assertEquals(1f, motion.transform().scaleX, .001f)
            assertEquals(0f, motion.transform().offset.getDistance(), .01f)
        }
    }

    @Test
    fun cancellationReturnsHomeWithoutClicking() {
        button()
        val target = ui.onNodeWithContentDescription("glass")
        target.performTouchInput { down(center) }
        ui.mainClock.advanceTimeBy(300)
        target.performTouchInput {
            moveBy(Offset(50f, 0f))
            cancel()
        }
        ui.mainClock.advanceTimeBy(1800)
        ui.runOnIdle {
            assertFalse(motion.held)
            assertEquals(0, clicks)
            assertEquals(1f, motion.transform().scaleX, .001f)
        }
    }

    @Test
    fun disabledGlassDoesNotLiftOrClick() {
        button(false)
        ui.onNodeWithContentDescription("glass").performTouchInput { down(center) }
        ui.mainClock.advanceTimeBy(500)
        ui.runOnIdle { assertEquals(1f, motion.transform().scaleX, .001f) }
        ui.onNodeWithContentDescription("glass").performTouchInput { up() }
        ui.runOnIdle { assertEquals(0, clicks) }
    }

    @Test
    fun capsuleSharesOneMotionAndKeepsSeparateActions() {
        lateinit var left: GlassPressMotion
        lateinit var right: GlassPressMotion
        var rightClicks = 0
        ui.setContent {
            Box(Modifier.padding(80.dp)) {
                DshaGlassGroup {
                    DshaIconButton("left", { clicks++ }, size = 52.dp) {
                        left = checkNotNull(LocalGlassPress.current)
                    }
                    DshaIconButton("right", { rightClicks++ }, size = 52.dp) {
                        right = checkNotNull(LocalGlassPress.current)
                    }
                }
            }
        }
        ui.mainClock.autoAdvance = false
        ui.onNodeWithContentDescription("right").performTouchInput { down(center) }
        ui.mainClock.advanceTimeBy(500)
        ui.runOnIdle {
            assertSame(left, right)
            assertTrue(right.transform().scaleX > 1.1f)
        }
        ui.onNodeWithContentDescription("right").performTouchInput { up() }
        ui.mainClock.advanceTimeBy(1800)
        ui.onNodeWithContentDescription("left").performTouchInput { click() }
        ui.mainClock.advanceTimeBy(1800)
        ui.runOnIdle {
            assertEquals(1, clicks)
            assertEquals(1, rightClicks)
        }
    }
}
