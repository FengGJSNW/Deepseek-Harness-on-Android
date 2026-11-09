package dev.deepseek.mobile

import androidx.compose.ui.graphics.Color
import kotlin.math.floor
import kotlin.math.roundToInt

// Direct port of lib/client.picker.src.js at 4f798ff, MIT. Keep the JS rounding,
// 32-bit hash, sRGB interpolation and per-particle parameters, rather than substitutes.
internal val EffortBlue = Color(63, 140, 255)
private val EffortViolet = Color(168, 85, 247)
private val EffortIndigo = Color(124, 108, 246)

internal fun effortEnergy(fraction: Double) =
    ((fraction.coerceIn(0.0, 1.0) - 1.0 / 3.0) / (1.0 - 1.0 / 3.0)).coerceIn(0.0, 1.0)

internal fun effortSpeed(fraction: Double) = .35 + effortEnergy(fraction) * (2.0 - .35)

private fun mixRgb(from: Color, to: Color, fraction: Double): Color {
    val t = fraction.coerceIn(0.0, 1.0)
    fun channel(a: Float, b: Float): Int {
        val fromByte = (a * 255).roundToInt()
        val toByte = (b * 255).roundToInt()
        return (fromByte + (toByte - fromByte) * t).roundToInt().coerceIn(0, 255)
    }
    return Color(
        channel(from.red, to.red),
        channel(from.green, to.green),
        channel(from.blue, to.blue),
    )
}

internal fun effortFillEnd(fraction: Double): Color =
    if (fraction <= .5) mixRgb(EffortBlue, EffortIndigo, fraction / .5)
    else mixRgb(EffortIndigo, EffortViolet, (fraction - .5) / .5)

internal fun effortValueColor(fraction: Double): Color =
    if (fraction <= .5) mixRgb(EffortBlue, EffortViolet, fraction / .5)
    else mixRgb(EffortViolet, EffortIndigo, (fraction - .5) / .5)

internal fun effortAccent(index: Int): Color =
    effortValueColor(index / effortStops.lastIndex.toDouble())

private fun starHash(index: Int, salt: Int): Double {
    var x = ((index + 1) * salt) xor (salt * 2654435761u.toInt())
    x = (x xor (x ushr 16)) * 2246822507u.toInt()
    x = (x xor (x ushr 13)) * 3266489909u.toInt()
    return (x xor (x ushr 16)).toUInt().toLong() / 4294967296.0
}

internal data class EffortStar(
    val index: Int,
    val topPercent: Double,
    val diameter: Double,
    val brightness: Double,
    val durationBase: Double,
    val phase: Double,
) {
    fun duration(fraction: Double) = durationBase * (2.0 / effortSpeed(fraction))

    fun delay(fraction: Double) = -phase * duration(fraction)
}

internal val effortStars =
    List(22) { index ->
        fun twoPlaces(value: Double) = (value * 100).roundToInt() / 100.0
        EffortStar(
            index,
            15.0 + (floor(starHash(index, 0x51ab) * 8).toInt() % 8) * 10.0,
            twoPlaces(1.2 + starHash(index, 0x99) * 1.6),
            twoPlaces(.5 + starHash(index, 0xaa) * .5),
            1.5 * (1 + (starHash(index, 0x77) - .5) * 2 * .08),
            ((index * .6180339887498949) % 1 + starHash(index, 53) * .03) % 1,
        )
    }
