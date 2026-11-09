package dev.deepseek.mobile

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import kotlin.math.exp

/** A Gaussian falloff around the outline, independent of Android's very faint ambient elevation. */
internal fun Modifier.floatingIslandShadow(shape: Shape, opacity: () -> Float = { 1f }) =
    drawWithCache {
        val path = (shape.createOutline(size, layoutDirection, this) as Outline.Generic).path
        val sigma = 6.dp.toPx()
        val step = 16.dp.toPx() / 12
        val weights =
            (1..12).map { index ->
                val inner = (index - 1) * step
                val outer = index * step
                val alpha =
                    .10f *
                        (exp(-inner * inner / (2 * sigma * sigma)) -
                            exp(-outer * outer / (2 * sigma * sigma)))
                index * step to alpha
            }
        val offset = 3.dp.toPx()
        onDrawBehind {
            translate(top = offset) {
                weights.asReversed().forEach { (spread, alpha) ->
                    drawPath(
                        path,
                        Color(0xFF26334D).copy(alpha = alpha * opacity().coerceIn(0f, 1f)),
                        style = Stroke(spread * 2),
                    )
                }
            }
        }
    }
