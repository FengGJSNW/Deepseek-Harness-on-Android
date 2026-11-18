package dev.deepseek.mobile

import android.app.Application
import android.graphics.*
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlassColorTest {
    private fun frame(finish: GlassFinish, density: Float, phase: Float, ink: Int): Bitmap {
        val width = (136 * density).toInt()
        val height = (84 * density).toInt()
        val lens = GlassLens()
        lens.resize(
            96f * density,
            44f * density,
            22f * density,
            density,
            20f * density,
            androidx.compose.ui.graphics.Color.White.copy(alpha = .54f),
            finish,
        )
        val node = RenderNode("glass-text-color")
        node.setPosition(0, 0, width, height)
        node.setRenderEffect(lens.androidEffect)
        val canvas = node.beginRecording(width, height)
        canvas.drawColor(Color.WHITE)
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ink
                textSize = 16f * density
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            }
        repeat(6) { row ->
            canvas.drawText(
                "Work Markdown 预览 Sandbox",
                (-12f + phase) * density,
                (12f + row * 16f) * density,
                paint,
            )
        }
        node.endRecording()
        return renderHardware(node, width, height)
    }

    @Test
    fun monochromeTextStaysNeutralAcrossFinishesDensitiesAndMovement() {
        val metrics = JSONArray()
        var worst = 0
        var leastDetail = 255
        var leastMovement = Int.MAX_VALUE
        for (density in listOf(1f, 2.625f, 3.75f)) for (finish in GlassFinish.entries) {
            val first = frame(finish, density, 0f, Color.BLACK)
            val moved = frame(finish, density, 4f, Color.BLACK)
            var maximum = 0
            var darkest = 255
            var changed = 0
            // Interior excludes the deliberately colored hairline of actual glass components.
            for (y in (30 * density).toInt() until (54 * density).toInt()) {
                for (x in (32 * density).toInt() until (104 * density).toInt()) {
                    val a = first.getPixel(x, y)
                    val b = moved.getPixel(x, y)
                    if (a != b) changed++
                    for (pixel in listOf(a, b)) {
                        val red = Color.red(pixel)
                        val green = Color.green(pixel)
                        val blue = Color.blue(pixel)
                        maximum = maxOf(maximum, maxOf(red, green, blue) - minOf(red, green, blue))
                        darkest = minOf(darkest, red, green, blue)
                    }
                }
            }
            metrics.put(
                JSONObject()
                    .put("finish", finish.name)
                    .put("density", density)
                    .put("maxChannelDifference", maximum)
                    .put("darkest", darkest)
                    .put("changedPixels", changed),
            )
            worst = maxOf(worst, maximum)
            leastDetail = minOf(leastDetail, 255 - darkest)
            leastMovement = minOf(leastMovement, changed)
            saveGlassFrame(first, "085-glass-text-${finish.name}-$density-rest")
            saveGlassFrame(moved, "085-glass-text-${finish.name}-$density-moved")
            first.recycle()
            moved.recycle()
        }
        File("build/reports/ui/085-glass-text-color.json").writeText(metrics.toString(2))
        assertTrue(
            "Black/white text acquired colored fringes; maximum channel difference=$worst",
            worst <= 2,
        )
        assertTrue("The glass must still show its backdrop", leastDetail > 10)
        assertTrue("Moving text must still update the refracted backdrop", leastMovement > 100)
    }

    @Test
    fun coloredContentIsNotDesaturatedToHideTheFringes() {
        val image = frame(GlassFinish.Regular, 1f, 0f, Color.rgb(30, 65, 175))
        var bluePixels = 0
        for (y in 30 until 54) for (x in 32 until 104) {
            val pixel = image.getPixel(x, y)
            if (Color.blue(pixel) - Color.red(pixel) > 20) bluePixels++
        }
        image.recycle()
        assertTrue("Color behind glass must remain visible: $bluePixels", bluePixels > 100)
    }
}
