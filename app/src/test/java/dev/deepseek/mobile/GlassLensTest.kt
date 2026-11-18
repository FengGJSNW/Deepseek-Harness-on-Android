package dev.deepseek.mobile

import android.app.Application
import android.graphics.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlassLensTest {
    private fun render(effect: RenderEffect?, density: Float = 1f, phase: Float = 0f): Bitmap {
        val width = (136 * density).toInt()
        val height = (84 * density).toInt()
        val node = RenderNode("glass-test")
        node.setPosition(0, 0, width, height)
        node.setRenderEffect(effect)
        val canvas = node.beginRecording(width, height)
        canvas.drawColor(Color.WHITE)
        val paint = Paint().apply { color = Color.rgb(30, 65, 175) }
        for (x in -1..18) {
            val left = (x * 8f + phase) * density
            canvas.drawRect(left, 0f, left + 4 * density, height.toFloat(), paint)
        }
        node.endRecording()
        return renderHardware(node, width, height)
    }

    @Test
    fun effectHasConfiguredUniformsOnFirstFrameAndTracksMovingContent() {
        val lens = GlassLens()
        lens.resize(96f, 44f, 22f, 1f, 20f)
        val first = render(lens.androidEffect)
        val moved = render(lens.androidEffect, phase = 4f)
        val plain = render(null)
        var changed = 0
        var refracted = 0
        for (y in 22..61) for (x in 24..111) {
            if (first.getPixel(x, y) != moved.getPixel(x, y)) changed++
            if (Color.red(first.getPixel(x, y)) < 240 && Color.red(plain.getPixel(x, y)) > 250)
                refracted++
        }
        assertTrue("moving background must update the lens: $changed", changed > 1500)
        assertTrue("dark stripes must bend into formerly white pixels: $refracted", refracted > 30)
        saveGlassFrame(first, "085-glass-optics-rest")
        saveGlassFrame(moved, "085-glass-optics-moved")
    }

    @Test
    fun effectIsRebuiltOnlyWhenOpticalConfigurationChanges() {
        val lens = GlassLens()
        lens.resize(96f, 44f, 22f)
        val first = lens.androidEffect
        lens.resize(96f, 44f, 22f)
        assertSame(first, lens.androidEffect)
        lens.resize(360f, 165f, 82.5f, 3.75f, 75f)
        assertNotSame(first, lens.androidEffect)
        val highDensity = render(lens.androidEffect, 3.75f)
        lens.resize(96f, 44f, 22f, 1f, 20f)
        val normal = render(lens.androidEffect)
        var total = 0L
        var count = 0
        for (y in 25..58 step 3) for (x in 30..105 step 3) {
            val a = normal.getPixel(x, y)
            val b = highDensity.getPixel(((x + .5f) * 3.75f).toInt(), ((y + .5f) * 3.75f).toInt())
            total += kotlin.math.abs(Color.red(a) - Color.red(b))
            count++
        }
        assertTrue(
            "optics must be density independent, mean difference=${total.toFloat() / count}",
            total.toFloat() / count < 35f,
        )
        saveGlassFrame(highDensity, "085-glass-optics-600dpi")
    }

    @Test
    fun isolatedStrokeHasOneContinuousFrostProfileWithoutOffsetGhosts() {
        val gutter = 52f
        val width = 240
        val height = 128
        val totalWidth = width + gutter.toInt() * 2
        val totalHeight = height + gutter.toInt() * 2
        for (finish in listOf(GlassFinish.Menu, GlassFinish.Pill)) {
            val lens = GlassLens()
            lens.resize(
                width.toFloat(),
                height.toFloat(),
                24f,
                gutter = gutter,
                tint = androidx.compose.ui.graphics.Color.Transparent,
                finish = finish,
            )
            val node = RenderNode("isolated-glass-stroke")
            node.setPosition(0, 0, totalWidth, totalHeight)
            node.setRenderEffect(lens.androidEffect)
            val canvas = node.beginRecording(totalWidth, totalHeight)
            canvas.drawColor(Color.WHITE)
            val center = totalWidth / 2
            val ink = Paint().apply { color = Color.BLACK }
            canvas.drawRect(center - 1f, 0f, center + 1f, totalHeight.toFloat(), ink)
            node.endRecording()
            val image = renderHardware(node, totalWidth, totalHeight)
            val row = totalHeight / 2
            try {
                assertTrue(
                    "the stroke must remain visible",
                    Color.red(image.getPixel(center, row)) < 252,
                )
                for (direction in listOf(-1, 1)) {
                    var lightestSoFar = Color.red(image.getPixel(center, row))
                    for (distance in 1..42) {
                        val light = Color.red(image.getPixel(center + direction * distance, row))
                        assertTrue(
                            "$finish has a repeated stroke at offset ${direction * distance}",
                            light >= lightestSoFar - 2,
                        )
                        lightestSoFar = maxOf(lightestSoFar, light)
                    }
                }
            } finally {
                image.recycle()
            }
        }
    }

    @Test
    fun fullTransparencyIsClearUnlessIndependentBlurIsRequested() {
        val lens = GlassLens()
        lens.resize(
            96f,
            44f,
            22f,
            gutter = 20f,
            tint = androidx.compose.ui.graphics.Color.Transparent,
            finish = GlassFinish.Menu,
        )
        val clearEffect = lens.androidEffect
        val clear = render(clearEffect)
        lens.resize(
            96f,
            44f,
            22f,
            gutter = 20f,
            tint = androidx.compose.ui.graphics.Color.Transparent,
            finish = GlassFinish.Menu,
            blur = 12f,
        )
        assertNotSame(clearEffect, lens.androidEffect)
        val blurred = render(lens.androidEffect)
        fun contrast(image: Bitmap): Int {
            val values = (36..98).map { Color.red(image.getPixel(it, 42)) }
            return values.max() - values.min()
        }
        try {
            assertTrue(
                "clear glass must retain sharp stripe contrast: ${contrast(clear)}",
                contrast(clear) > 230,
            )
            assertTrue(
                "the explicit blur option must diffuse the stripes: ${contrast(blurred)}",
                contrast(blurred) < 45,
            )
        } finally {
            clear.recycle()
            blurred.recycle()
        }
    }

    @Test
    fun renderEffectSnapshotsUniformsBeforeLaterShaderChanges() {
        val shader =
            RuntimeShader(
                "uniform shader backdrop; uniform float level; half4 main(float2 p) { return half4(level, level, level, 1) * .999 + backdrop.eval(p) * .001; }",
            )
        shader.setFloatUniform("level", 0f)
        val old = RenderEffect.createRuntimeShaderEffect(shader, "backdrop")
        shader.setFloatUniform("level", 1f)
        val updated = RenderEffect.createRuntimeShaderEffect(shader, "backdrop")
        assertTrue(Color.red(render(old).getPixel(50, 40)) < 10)
        assertTrue(Color.red(render(updated).getPixel(50, 40)) > 245)
    }
}

internal fun renderHardware(node: RenderNode, width: Int, height: Int): Bitmap {
    val method =
        HardwareRenderer::class
            .java
            .getDeclaredMethod(
                "createHardwareBitmap",
                RenderNode::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
    val hardware = method.invoke(null, node, width, height) as Bitmap
    return hardware.copy(Bitmap.Config.ARGB_8888, false).also { hardware.recycle() }
}

internal fun saveGlassFrame(bitmap: Bitmap, name: String) {
    val file = File("build/reports/ui/$name.png")
    file.parentFile?.mkdirs()
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
}
