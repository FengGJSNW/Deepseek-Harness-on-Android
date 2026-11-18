package dev.deepseek.mobile

import androidx.compose.ui.graphics.Color
import kotlin.math.*

// Numeric field and particles captured from ChatGPT's live model picker (see source manifest).
internal fun effortFieldColor(
    x: Double,
    y: Double,
    width: Double,
    height: Double,
    time: Double,
): DoubleArray {
    val uTime = time
    val speed = 1.25
    val px = x * 1.55
    val py = .40 + y * .18
    val centers =
        arrayOf(
            doubleArrayOf(
                0.18 + sin(uTime * speed * 0.42) * 0.18,
                0.36 + cos(uTime * speed * 0.50) * 0.42,
            ),
            doubleArrayOf(
                0.34 + cos(uTime * speed * 0.62) * 0.24,
                0.62 + sin(uTime * speed * 0.47) * 0.38,
            ),
            doubleArrayOf(
                0.52 + sin(uTime * speed * 0.38) * 0.28,
                0.30 + cos(uTime * speed * 0.58) * 0.36,
            ),
            doubleArrayOf(
                0.70 + cos(uTime * speed * 0.54) * 0.24,
                0.68 + sin(uTime * speed * 0.41) * 0.36,
            ),
            doubleArrayOf(
                0.88 + sin(uTime * speed * 0.74) * 0.16,
                0.36 + cos(uTime * speed * 0.64) * 0.40,
            ),
            doubleArrayOf(
                0.12 + cos(uTime * speed * 0.48) * 0.20,
                0.72 + sin(uTime * speed * 0.70) * 0.30,
            ),
            doubleArrayOf(
                0.30 + sin(uTime * speed * 0.58) * 0.22,
                0.44 + cos(uTime * speed * 0.52) * 0.42,
            ),
            doubleArrayOf(
                0.46 + cos(uTime * speed * 0.68) * 0.26,
                0.72 + sin(uTime * speed * 0.56) * 0.32,
            ),
            doubleArrayOf(
                0.60 + sin(uTime * speed * 0.44) * 0.28,
                0.26 + cos(uTime * speed * 0.60) * 0.38,
            ),
            doubleArrayOf(
                0.76 + cos(uTime * speed * 0.50) * 0.22,
                0.54 + sin(uTime * speed * 0.66) * 0.40,
            ),
            doubleArrayOf(
                0.92 + sin(uTime * speed * 0.70) * 0.15,
                0.66 + cos(uTime * speed * 0.46) * 0.30,
            ),
            doubleArrayOf(
                0.06 + cos(uTime * speed * 0.40) * 0.14,
                0.32 + sin(uTime * speed * 0.60) * 0.40,
            ),
        )
    val colors =
        arrayOf(
            doubleArrayOf(0.592, 0.388, 0.945),
            doubleArrayOf(0.831, 0.710, 0.953),
            doubleArrayOf(0.286, 0.000, 0.404),
            doubleArrayOf(0.145, 0.055, 0.478),
            doubleArrayOf(0.592, 0.000, 0.996),
            doubleArrayOf(0.780, 0.459, 0.914),
            doubleArrayOf(0.725, 0.576, 1.000),
            doubleArrayOf(0.400, 0.212, 0.820),
            doubleArrayOf(0.882, 0.690, 1.000),
            doubleArrayOf(0.498, 0.345, 0.957),
            doubleArrayOf(0.659, 0.275, 0.910),
            doubleArrayOf(0.212, 0.063, 0.400),
        )
    val modulation =
        doubleArrayOf(
            .7 + .3 * sin(uTime * 0.91),
            .7 + .3 * cos(uTime * 1.07),
            .7 + .3 * sin(uTime * 0.76),
            .7 + .3 * cos(uTime * 1.18),
            .7 + .3 * sin(uTime * 1.03),
            .7 + .3 * cos(uTime * 0.83),
            .7 + .3 * sin(uTime * 1.24),
            .7 + .3 * cos(uTime * 0.96),
            .7 + .3 * sin(uTime * 1.11),
            .7 + .3 * cos(uTime * 0.72),
            .7 + .3 * sin(uTime * 1.29),
            .7 + .3 * cos(uTime * 0.88),
        )
    val result = DoubleArray(3)
    var total = 0.0
    for (i in centers.indices) {
        val dx = px - centers[i][0] * 1.55
        val dy = py - centers[i][1]
        val weight = exp(-(dx * dx + dy * dy) * 12) * modulation[i]
        total += weight
        for (c in 0..2) result[c] += colors[i][c] * weight
    }
    val noise =
        sin((x * width * .5 + time) * 12.9898 + (y * height * .5 + time) * 78.233) * 43758.5453
    val grain = (noise - floor(noise)) * 2 - 1
    val shifts = doubleArrayOf(sin(time * .5), cos(time * .7), sin(time * .6))
    for (c in 0..2) {
        val field =
            (colors[3][c] * .04 + result[c] / max(total, .0001) * .96).coerceIn(0.0, 1.0).pow(.9)
        result[c] = max(0.0, field + grain * .012 + shifts[c] * .02).pow(.92).coerceIn(0.0, 1.0)
    }
    return result
}

internal fun effortParticleRandom(index: Int, salt: Int): Double {
    val n = 43758.5453 * sin((index + 1) * 12.9898 + 78.233 * salt)
    return n - floor(n)
}

internal data class EffortParticle(val left: Float, val opacity: Float, val scale: Float)

internal val effortParticles =
    List(14) { index ->
        EffortParticle(
            floor(4 + 92 * effortParticleRandom(index, 14) + .5).toFloat() / 100,
            (.4 + .6 * effortParticleRandom(index, 11)).toFloat(),
            (.5 + .45 * effortParticleRandom(index, 12)).toFloat(),
        )
    }

internal fun effortMaxActive(index: Int, last: Int) = last > 0 && index == last

// CSS mix-blend-mode: luminosity over the three-stop chart-blue / chart-purple gradient.
internal fun effortMaxColor(
    x: Double,
    y: Double,
    width: Double,
    height: Double,
    time: Double,
): Color {
    val field = effortFieldColor(x, y, width, height, time)
    val blue = doubleArrayOf(2.0 / 255, 133.0 / 255, 1.0)
    val purple = doubleArrayOf(146.0 / 255, 79.0 / 255, 247.0 / 255)
    val base =
        DoubleArray(3) { c ->
            if (x <= .55) {
                val start = blue[c] * .6 + purple[c] * .4
                start + (purple[c] - start) * (x / .55).coerceIn(0.0, 1.0)
            } else
                purple[c] +
                    (blue[c] * .28 + purple[c] * .72 - purple[c]) *
                        ((x - .55) / .45).coerceIn(0.0, 1.0)
        }
    fun lum(c: DoubleArray) = c[0] * .3 + c[1] * .59 + c[2] * .11
    val delta = lum(field) - lum(base)
    val result = DoubleArray(3) { base[it] + delta }
    val l = lum(result)
    val n = result.min()
    val high = result.max()
    if (n < 0) for (i in 0..2) result[i] = l + (result[i] - l) * l / (l - n)
    if (high > 1) for (i in 0..2) result[i] = l + (result[i] - l) * (1 - l) / (high - l)
    return Color(
        result[0].toFloat().coerceIn(0f, 1f),
        result[1].toFloat().coerceIn(0f, 1f),
        result[2].toFloat().coerceIn(0f, 1f),
    )
}
