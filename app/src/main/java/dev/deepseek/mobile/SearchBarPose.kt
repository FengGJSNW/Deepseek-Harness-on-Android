package dev.deepseek.mobile

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import kotlin.math.max
import kotlin.math.min

internal fun Rect.stretched(sx: Float, sy: Float = sx, shift: Offset = Offset.Zero): Rect {
    val c = center + shift
    val dx = width * sx / 2
    val dy = height * sy / 2
    return Rect(c.x - dx, c.y - dy, c.x + dx, c.y + dy)
}

internal data class SearchBarPose(
    val field: Rect,
    val cancel: Rect,
    val source: Rect,
    val target: Rect,
)

internal fun searchBarPose(
    anchor: Rect,
    left: Float,
    right: Float,
    rtl: Boolean,
    frame: SearchMotionFrame,
): SearchBarPose {
    val h = 48f
    val gap = 12f
    val start = anchor.deflate(4f)
    val y = start.center.y
    val target =
        if (rtl) Rect(min(right - 1, left + h + gap), y - h / 2, right, y + h / 2)
        else Rect(left, y - h / 2, max(left + 1, right - h - gap), y + h / 2)
    val grown = lerp(start, target, frame.growth)
    fun lift(rect: Rect) =
        rect.stretched(1f + frame.lift * min(.25f, 16f / max(1f, max(rect.width, rect.height))))
    val field = lift(grown)
    val g = frame.growth.coerceAtLeast(0f)
    val reach = h / 2 + (-gap - h / 2) * g
    val x = if (rtl) field.left + reach - h * g else field.right - reach
    val flank = Rect(x, y - h * g / 2, x + h * g, y + h * g / 2)
    val source =
        lift(start)
            .stretched(
                frame.dropSize,
                shift =
                    Offset(
                        (target.center.x - start.center.x) * frame.dropOffset,
                        0f,
                    ),
            )
    return SearchBarPose(field, lift(flank).stretched(frame.flankScale), source, target)
}
