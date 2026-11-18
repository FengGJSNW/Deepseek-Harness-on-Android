package dev.deepseek.mobile

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class PressMenuTrackingTest {
    @Test
    fun holdingWithoutDraggingKeepsTheMenuOpenWithoutSelecting() {
        val tracking = PressMenuTracking()
        var clicks = 0
        tracking.register("one", { Rect(0f, 60f, 200f, 110f) }) { clicks++ }
        tracking.begin(Offset(20f, 20f))
        tracking.release(Offset(20f, 20f))
        assertEquals(0, clicks)
    }

    @Test
    fun dragReleaseSelectsTheRowUnderTheFingerOnce() {
        val tracking = PressMenuTracking()
        var first = 0
        var second = 0
        tracking.register("one", { Rect(0f, 60f, 200f, 110f) }) { first++ }
        tracking.register("two", { Rect(0f, 110f, 200f, 160f) }) { second++ }
        tracking.begin(Offset(20f, 20f))
        tracking.move(Offset(20f, 80f), 8f)
        assertEquals("one", tracking.hovered)
        tracking.move(Offset(20f, 130f), 8f)
        tracking.release(Offset(20f, 130f))
        tracking.release(Offset(20f, 130f))
        assertEquals(0, first)
        assertEquals(1, second)
        assertNull(tracking.hovered)
    }

    @Test
    fun cancellationAndReleaseOutsideDoNotActivateOldRows() {
        val tracking = PressMenuTracking()
        var clicks = 0
        tracking.register("one", { Rect(0f, 60f, 200f, 110f) }) { clicks++ }
        tracking.begin(Offset(20f, 20f))
        tracking.move(Offset(20f, 80f), 8f)
        tracking.cancel()
        tracking.release(Offset(20f, 80f))
        tracking.begin(Offset(20f, 20f))
        tracking.move(Offset(20f, 80f), 8f)
        tracking.release(Offset(250f, 80f))
        assertEquals(0, clicks)
    }
}
