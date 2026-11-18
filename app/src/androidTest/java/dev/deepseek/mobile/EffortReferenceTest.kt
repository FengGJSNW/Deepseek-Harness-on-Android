package dev.deepseek.mobile

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** RGB fixtures rendered by the captured GLSL in the user's Edge WebGL context. */
class EffortReferenceTest {
    @Test
    fun fieldAndParticlesMatchCapturedBrowserSource() {
        val fixture =
            JSONObject(
                InstrumentationRegistry.getInstrumentation()
                    .context
                    .assets
                    .open("effort-reference-fixtures.json")
                    .bufferedReader()
                    .use { it.readText() },
            )
        val rows = fixture.getJSONArray("rows")
        repeat(rows.length()) { i ->
            val row = rows.getJSONObject(i)
            val rgb =
                effortFieldColor(
                    row.getDouble("x"),
                    row.getDouble("y"),
                    fixture.getDouble("width"),
                    fixture.getDouble("height"),
                    row.getDouble("time"),
                )
            repeat(3) { channel ->
                // The source's sine hash amplifies GPU float rounding into grain.
                assertEquals(
                    "WebGL sample $i channel $channel",
                    row.getJSONArray("rgb").getDouble(channel),
                    rgb[channel] * 255,
                    8.0,
                )
            }
        }
        val particles = fixture.getJSONArray("particles")
        assertEquals(particles.length(), effortParticles.size)
        repeat(particles.length()) { i ->
            val expected = particles.getJSONObject(i)
            val actual = effortParticles[i]
            assertEquals(expected.getDouble("left"), actual.left.toDouble(), 1e-6)
            assertEquals(expected.getDouble("opacity"), actual.opacity.toDouble(), 1e-6)
            assertEquals(expected.getDouble("scale"), actual.scale.toDouble(), 1e-6)
        }
        assertFalse(effortMaxActive(0, 3))
        assertFalse(effortMaxActive(2, 3))
        assertTrue(effortMaxActive(3, 3))
        assertTrue(effortMaxActive(2, 2))
        assertFalse(effortMaxActive(0, 0))
    }
}
