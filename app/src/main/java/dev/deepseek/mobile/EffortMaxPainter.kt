package dev.deepseek.mobile

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import kotlin.math.*

internal interface EffortMaxPainter {
    fun draw(scope: DrawScope, bounds: Rect, seconds: Float, reveal: Float, alpha: Float)
}

internal fun createEffortMaxPainter(source: String): EffortMaxPainter =
    if (Build.VERSION.SDK_INT >= 33) ShaderEffortPainter(source) else BitmapEffortPainter()

@RequiresApi(33)
private class ShaderEffortPainter(source: String) : EffortMaxPainter {
    private val shader = RuntimeShader(source)
    private val softwareFallback by lazy { BitmapEffortPainter() }
    private val paint = Paint().apply { this.shader = this@ShaderEffortPainter.shader }

    override fun draw(scope: DrawScope, bounds: Rect, seconds: Float, reveal: Float, alpha: Float) {
        if (!scope.drawContext.canvas.nativeCanvas.isHardwareAccelerated) {
            softwareFallback.draw(scope, bounds, seconds, reveal, alpha)
            return
        }
        shader.setFloatUniform("uTime", seconds)
        shader.setFloatUniform(
            "uResolution",
            bounds.width / scope.density,
            bounds.height / scope.density,
        )
        shader.setFloatUniform("uOrigin", bounds.left, bounds.top)
        shader.setFloatUniform("uSize", bounds.width, bounds.height)
        shader.setFloatUniform("uReveal", reveal)
        paint.alpha = (alpha * 255).roundToInt().coerceIn(0, 255)
        scope.drawContext.canvas.nativeCanvas.drawRect(
            bounds.left,
            bounds.top,
            bounds.right,
            bounds.bottom,
            paint,
        )
    }
}

/** Android 11/12: same field and blend equations, sampled into a small filtered bitmap. */
private class BitmapEffortPainter : EffortMaxPainter {
    private val bitmap = Bitmap.createBitmap(192, 18, Bitmap.Config.ARGB_8888)
    private val pixels = IntArray(192 * 18)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var previous = emptyList<Float>()

    override fun draw(scope: DrawScope, bounds: Rect, seconds: Float, reveal: Float, alpha: Float) {
        val time = floor(seconds * 30) / 30
        val parameters = listOf(time, bounds.width, bounds.height, reveal)
        if (previous != parameters) {
            previous = parameters
            val width = (bounds.width / scope.density).toDouble()
            val height = (bounds.height / scope.density).toDouble()
            for (y in 0 until 18) for (x in 0 until 192) {
                val u = (x + .5) / 192
                val color = effortMaxColor(u, 1 - (y + .5) / 18, width, height, time.toDouble())
                val mask = ((u * width - reveal * width + 120) / 120).coerceIn(0.0, 1.0)
                pixels[y * 192 + x] = color.copy(alpha = mask.toFloat()).toArgb()
            }
            bitmap.setPixels(pixels, 0, 192, 0, 0, 192, 18)
        }
        paint.alpha = (alpha * 255).roundToInt().coerceIn(0, 255)
        scope.drawContext.canvas.nativeCanvas.drawBitmap(
            bitmap,
            null,
            android.graphics.RectF(bounds.left, bounds.top, bounds.right, bounds.bottom),
            paint,
        )
    }
}

private val particleEase = CubicBezierEasing(.45f, 0f, .55f, 1f)

internal fun DrawScope.drawEffortParticles(bounds: Rect, seconds: Float, alpha: Float) {
    effortParticles.forEachIndexed { index, particle ->
        // The web implementation picks random targets after the seeded initial position.
        // A seeded sequence here keeps captures reproducible, with the same ranges and easing.
        fun target(step: Int): Triple<Float, Float, Float> {
            val salt = step * 3 + 1
            return Triple(
                floor((effortParticleRandom(index, salt) - .5) * 8 + .5).toFloat(),
                floor(12 + (effortParticleRandom(index, salt + 1) - .5) * 14 + .5).toFloat(),
                (1.6 * (.8 + 1.2 * effortParticleRandom(index, salt + 2))).toFloat(),
            )
        }
        var remaining = seconds
        var step = 0
        var from = target(0)
        var to = target(1)
        while (remaining > to.third) {
            remaining -= to.third
            step++
            from = to
            to = target(step + 1)
        }
        val p = particleEase.transform((remaining / to.third).coerceIn(0f, 1f))
        val point =
            Offset(
                bounds.left +
                    bounds.width * particle.left +
                    (from.first + (to.first - from.first) * p).dp.toPx(),
                bounds.center.y - 12.dp.toPx() +
                    (from.second + (to.second - from.second) * p).dp.toPx(),
            )
        val radius = 1.5.dp.toPx() * particle.scale
        val glow = radius + 2.5.dp.toPx()
        drawCircle(
            Brush.radialGradient(
                listOf(
                    Color.White.copy(alpha = .34f * particle.opacity * alpha),
                    Color.Transparent,
                ),
                point,
                glow,
            ),
            glow,
            point,
        )
        drawCircle(Color.White.copy(alpha = .72f * particle.opacity * alpha), radius, point)
    }
}

private val burstPoints =
    listOf(
        Triple(-3, -34, 0),
        Triple(15, -29, 8),
        Triple(30, -19, 0),
        Triple(34, -2, 12),
        Triple(26, 20, 0),
        Triple(12, 31, 10),
        Triple(-6, 34, 0),
        Triple(-22, 26, 14),
        Triple(-32, 9, 0),
        Triple(-32, -10, 8),
        Triple(-21, -26, 0),
        Triple(7, -24, 6),
        Triple(24, -9, 0),
        Triple(20, 10, 10),
        Triple(-9, 21, 0),
        Triple(-25, -5, 12),
    )
private val burstEase = CubicBezierEasing(.25f, 1f, .5f, 1f)

internal fun DrawScope.drawEffortBurst(center: Offset, progress: Float) {
    if (progress >= 1f) return
    burstPoints.forEachIndexed { index, (x, y, delay) ->
        val raw = (progress - delay / 620f).coerceIn(0f, 1f)
        val first = raw < .22f
        val p = burstEase.transform(if (first) raw / .22f else (raw - .22f) / .78f)
        val opacity = if (first) p else 1f - p
        val scale = if (first) .25f + (1.28f - .25f) * p else 1.28f + (.55f - 1.28f) * p
        val travel = if (first) 0f else p
        val blueAmount =
            if (index % 4 == 2) .18f else if (index % 4 == 0 && index != 0) .32f else 0f
        drawCircle(
            lerp(Color(0xFF924FF7), Color(0xFF0285FF), blueAmount).copy(alpha = opacity),
            2.5.dp.toPx() * scale,
            center + Offset(x.dp.toPx() * travel, y.dp.toPx() * travel),
        )
    }
}
