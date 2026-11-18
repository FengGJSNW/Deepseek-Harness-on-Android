package dev.deepseek.mobile

import androidx.compose.ui.geometry.Offset
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class EffortTrackShapeTest {
    private fun curvature(velocity: Offset, acceleration: Offset): Float =
        abs(velocity.x * acceleration.y - velocity.y * acceleration.x) /
            velocity.getDistance().pow(3)

    @Test
    fun blendHasZeroCurvatureAtLineAndCircularCurvatureAtArc() {
        for (radius in listOf(12f, 24f, 36f, 48f)) {
            val b = effortCapBlend(radius)
            assertEquals(0f, curvature(b.first * 3f, (b.second - b.first * 2f) * 6f), 1e-6f)
            val endVelocity = (b.end - b.second) * 3f
            val endAcceleration = (b.end - b.second * 2f + b.first) * 6f
            assertEquals(1f / radius, curvature(endVelocity, endAcceleration), 1e-6f)
            val normal = b.end - Offset(0f, radius)
            assertEquals(radius, normal.getDistance(), 1e-5f)
            assertEquals(0f, endVelocity.x * normal.x + endVelocity.y * normal.y, .001f)
        }
    }

    @Test
    fun adjustmentStaysWithinTheSmallArcJoinAndDoesNotFlattenTheCap() {
        val radius = 12f
        val b = effortCapBlend(radius)
        assertTrue(b.end.x < 4f && b.end.y < .6f)
        for (i in 0..100) {
            val t = i / 100f
            val p =
                b.first * (3 * (1 - t).pow(2) * t) +
                    b.second * (3 * (1 - t) * t * t) +
                    b.end * t.pow(3)
            assertTrue(p.y >= 0f && p.y <= b.end.y)
            assertTrue(
                "The local blend must stay close to the original circle",
                abs((p - Offset(0f, radius)).getDistance() - radius) < .04f,
            )
        }
        assertEquals(144f, 180f - 2 * EffortCapBlendDegrees, 0f)
    }
}
