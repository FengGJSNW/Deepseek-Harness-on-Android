package dev.deepseek.mobile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Compare rendered contours against the pinned upstream SVG, not duplicated Kotlin math. */
class ContinuousCornerTest {
    @Test
    fun iosPresetMatchesUpstreamAtDifferentAspectRatios() {
        val json =
            InstrumentationRegistry.getInstrumentation()
                .context
                .assets
                .open("continuous-corner-fixtures.json")
                .bufferedReader()
                .use { JSONObject(it.readText()) }
        val cases = json.getJSONArray("cases")
        repeat(cases.length()) { index ->
            val item = cases.getJSONObject(index)
            val width = item.getInt("width")
            val height = item.getInt("height")
            val actual =
                (ContinuousShape(item.getInt("radius").dp)
                        .createOutline(
                            Size(width.toFloat(), height.toFloat()),
                            LayoutDirection.Ltr,
                            Density(1f),
                        ) as Outline.Generic)
                    .path
            val expected = PathParser().parsePathString(item.getString("path")).toPath()
            fun render(path: androidx.compose.ui.graphics.Path): Bitmap {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                Canvas(bitmap)
                    .drawPath(
                        path.asAndroidPath(),
                        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK },
                    )
                return bitmap
            }
            val a = render(actual)
            val b = render(expected)
            var differences = 0
            repeat(height) { y ->
                repeat(width) { x ->
                    if (
                        (android.graphics.Color.alpha(a.getPixel(x, y)) > 128) !=
                            (android.graphics.Color.alpha(b.getPixel(x, y)) > 128)
                    )
                        differences++
                }
            }
            assertTrue(
                "Contour differs from iOS preset fixture $index: $differences",
                differences <= width * height * .0015 + 4,
            )
            a.recycle()
            b.recycle()
        }
    }
}
