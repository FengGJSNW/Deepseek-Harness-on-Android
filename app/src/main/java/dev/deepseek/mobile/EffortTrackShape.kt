package dev.deepseek.mobile

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import kotlin.math.*

internal const val EffortCapBlendDegrees = 18f

internal data class EffortCapBlend(val first: Offset, val second: Offset, val end: Offset)

/** Only the first/last 18 degrees of each semicircle blend into the horizontal rail. */
internal fun effortCapBlend(radius: Float): EffortCapBlend {
    val angle = Math.toRadians(EffortCapBlendDegrees.toDouble())
    val tangent = radius * tan(angle / 2)
    val secondX = tangent
    // Horizontal P0/P1/P2 make the line join curvature zero. This P1 also makes the
    // endpoint curvature exactly 1/r, matching the untouched circular arc.
    val firstX = secondX - 1.5 * tangent * tangent / (radius * sin(angle))
    return EffortCapBlend(
        Offset(firstX.toFloat(), 0f),
        Offset(secondX.toFloat(), 0f),
        Offset((radius * sin(angle)).toFloat(), (radius * (1 - cos(angle))).toFloat()),
    )
}

internal fun effortTrackPath(rect: Rect): Path {
    val radius = rect.height / 2
    if (radius <= 0f || rect.width <= 0f) return Path()
    if (rect.width < rect.height)
        return Path().apply {
            addRoundRect(RoundRect(rect, CornerRadius(rect.width / 2)))
        }
    val blend = effortCapBlend(radius)
    val left = rect.left + radius
    val right = rect.right - radius
    return Path().apply {
        moveTo(left, rect.top)
        lineTo(right, rect.top)
        cubicTo(
            right + blend.first.x,
            rect.top,
            right + blend.second.x,
            rect.top,
            right + blend.end.x,
            rect.top + blend.end.y,
        )
        arcTo(
            Rect(rect.right - 2 * radius, rect.top, rect.right, rect.bottom),
            -90f + EffortCapBlendDegrees,
            180f - 2 * EffortCapBlendDegrees,
            false,
        )
        cubicTo(
            right + blend.second.x,
            rect.bottom,
            right + blend.first.x,
            rect.bottom,
            right,
            rect.bottom,
        )
        lineTo(left, rect.bottom)
        cubicTo(
            left - blend.first.x,
            rect.bottom,
            left - blend.second.x,
            rect.bottom,
            left - blend.end.x,
            rect.bottom - blend.end.y,
        )
        arcTo(
            Rect(rect.left, rect.top, rect.left + 2 * radius, rect.bottom),
            90f + EffortCapBlendDegrees,
            180f - 2 * EffortCapBlendDegrees,
            false,
        )
        cubicTo(
            left - blend.second.x,
            rect.top,
            left - blend.first.x,
            rect.top,
            left,
            rect.top,
        )
        close()
    }
}
