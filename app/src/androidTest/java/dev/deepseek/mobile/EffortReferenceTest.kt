package dev.deepseek.mobile

import androidx.compose.ui.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.roundToInt
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Fixtures execute the upstream JS functions verbatim, from the pinned MIT source. */
class EffortReferenceTest {
    @Test
    fun nativeVisualParametersMatchOriginalJavaScript() {
        val raw =
            InstrumentationRegistry.getInstrumentation()
                .context
                .assets
                .open("effort-reference-fixtures.json")
                .bufferedReader()
                .use { it.readText() }
        val rows = JSONObject(raw).getJSONArray("rows")
        fun rgb(color: Color) =
            listOf(color.red, color.green, color.blue).map { (it * 255).roundToInt() }
        fun cssRgb(css: String) =
            Regex("rgb\\((\\d+),(\\d+),(\\d+)\\)").findAll(css).last().groupValues.drop(1).map {
                it.toInt()
            }
        repeat(rows.length()) { row ->
            val expected = rows.getJSONObject(row)
            val p = expected.getDouble("fraction")
            assertEquals(cssRgb(expected.getString("fill")), rgb(effortFillEnd(p)))
            assertEquals(cssRgb(expected.getString("value")), rgb(effortValueColor(p)))
            assertEquals(expected.getDouble("energy"), effortEnergy(p), 1e-9)
            assertEquals(expected.getDouble("speed"), effortSpeed(p), 1e-9)
            val stars = expected.getJSONArray("stars")
            repeat(stars.length()) { i ->
                val original = stars.getJSONObject(i)
                val native = effortStars[i]
                assertEquals(original.getDouble("top"), native.topPercent, 1e-9)
                assertEquals(original.getDouble("size"), native.diameter, 1e-9)
                assertEquals(original.getDouble("brightness"), native.brightness, 1e-9)
                assertEquals(original.getDouble("duration"), native.duration(p), 1e-9)
                assertEquals(original.getDouble("delay"), native.delay(p), 1e-9)
            }
        }
    }
}
