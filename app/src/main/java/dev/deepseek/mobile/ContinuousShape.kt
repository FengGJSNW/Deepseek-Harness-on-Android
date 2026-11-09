package dev.deepseek.mobile

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.*

/**
 * iOS-style corners with Figma's documented iOS preset (60% smoothing). Geometry adapted from
 * phamfoo/figma-squircle, commit 45622dc (MIT). A circular arc joins two tangent cubic shoulders;
 * smoothing reduces when space is tight. Apple does not publish numerical control points for
 * CALayer.cornerCurve.
 */
internal class ContinuousShape(
    topStart: CornerSize,
    topEnd: CornerSize,
    bottomEnd: CornerSize,
    bottomStart: CornerSize,
) : CornerBasedShape(topStart, topEnd, bottomEnd, bottomStart) {
    constructor(
        topStart: Dp = 0.dp,
        topEnd: Dp = 0.dp,
        bottomEnd: Dp = 0.dp,
        bottomStart: Dp = 0.dp,
    ) : this(
        CornerSize(topStart),
        CornerSize(topEnd),
        CornerSize(bottomEnd),
        CornerSize(bottomStart),
    )

    constructor(radius: Dp) : this(radius, radius, radius, radius)

    override fun copy(
        topStart: CornerSize,
        topEnd: CornerSize,
        bottomEnd: CornerSize,
        bottomStart: CornerSize,
    ) = ContinuousShape(topStart, topEnd, bottomEnd, bottomStart)

    override fun createOutline(
        size: Size,
        topStart: Float,
        topEnd: Float,
        bottomEnd: Float,
        bottomStart: Float,
        layoutDirection: LayoutDirection,
    ): Outline {
        val rtl = layoutDirection == LayoutDirection.Rtl
        val radii =
            floatArrayOf(
                if (rtl) topEnd else topStart,
                if (rtl) topStart else topEnd,
                if (rtl) bottomStart else bottomEnd,
                if (rtl) bottomEnd else bottomStart,
            )
        val w = size.width
        val h = size.height
        val scale =
            minOf(
                1f,
                w / (radii[0] + radii[1]).coerceAtLeast(1f),
                w / (radii[2] + radii[3]).coerceAtLeast(1f),
                h / (radii[0] + radii[3]).coerceAtLeast(1f),
                h / (radii[1] + radii[2]).coerceAtLeast(1f),
            )
        radii.indices.forEach { radii[it] = radii[it].coerceAtLeast(0f) * scale }
        val corners = radii.map { r ->
            val smoothing =
                if (r == 0f) 0f else minOf(.6f, minOf(w, h) / (2 * r) - 1f).coerceAtLeast(0f)
            val p = (1 + smoothing) * r
            val arc = 90f * (1 - smoothing)
            val beta = Math.toRadians((45 * smoothing).toDouble())
            val distance = r * tan(Math.toRadians((90 - arc).toDouble() / 4)).toFloat()
            val c = distance * cos(beta).toFloat()
            val d = c * tan(beta).toFloat()
            val arcLength = sin(Math.toRadians(arc.toDouble() / 2)).toFloat() * r * sqrt(2f)
            val b = (p - arcLength - c - d) / 3
            floatArrayOf(r, p, 2 * b, b, c, d, arc)
        }
        val path = Path()
        // Local corner: (0,0) → (p,p), rotated through the four quadrants.
        fun corner(x: Float, y: Float, values: FloatArray, rotation: Int) {
            val (r, p, a, b, c) = values
            val d = values[5]
            val arc = values[6]
            fun point(a: Float, b: Float): Pair<Float, Float> =
                when (rotation) {
                    0 -> x + a to y + b
                    1 -> x - b to y + a
                    2 -> x - a to y - b
                    else -> x + b to y - a
                }
            fun cubic(a: Float, b: Float, c: Float, d: Float, e: Float, f: Float) {
                val p = point(a, b)
                val q = point(c, d)
                val end = point(e, f)
                path.cubicTo(p.first, p.second, q.first, q.second, end.first, end.second)
            }
            if (r == 0f) {
                path.lineTo(x, y)
                return
            }
            cubic(a, 0f, a + b, 0f, a + b + c, d)
            val center = point(p - r, r)
            path.arcTo(
                Rect(center.first - r, center.second - r, center.first + r, center.second + r),
                -90f + 45f * (1 - arc / 90f) + 90f * rotation,
                arc,
                false,
            )
            cubic(p, p - a - b, p, p - a, p, p)
        }
        path.moveTo(corners[0][1], 0f)
        path.lineTo(w - corners[1][1], 0f)
        corner(w - corners[1][1], 0f, corners[1], 0)
        path.lineTo(w, h - corners[2][1])
        corner(w, h - corners[2][1], corners[2], 1)
        path.lineTo(corners[3][1], h)
        corner(corners[3][1], h, corners[3], 2)
        path.lineTo(0f, corners[0][1])
        corner(0f, corners[0][1], corners[0], 3)
        path.close()
        return Outline.Generic(path)
    }
}
