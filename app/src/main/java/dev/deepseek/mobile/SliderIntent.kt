package dev.deepseek.mobile

import kotlin.math.abs
import kotlin.math.roundToInt

/** Touch intent is independent of recomposition, animation and persistence. */
internal class SliderIntent(
    private val x: Float,
    private val y: Float,
    private val slop: Float,
    val grabbed: Boolean,
) {
    var dragging = false
        private set

    var cancelled = false
        private set

    fun move(px: Float, py: Float): Boolean {
        if (!grabbed || cancelled) return false
        val dx = abs(px - x)
        val dy = abs(py - y)
        if (!dragging && dy > slop && dy >= dx) cancelled = true
        if (!cancelled && dx > slop && dx > dy * 1.3f) dragging = true
        return dragging && !cancelled
    }
}

internal fun sliderValue(value: Float, range: ClosedFloatingPointRange<Float>, steps: Int): Float {
    if (!value.isFinite() || range.endInclusive <= range.start) return range.start
    val fraction = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
    val quantized =
        if (steps > 0) (fraction * (steps + 1)).roundToInt().toFloat() / (steps + 1) else fraction
    return range.start + quantized * (range.endInclusive - range.start)
}
