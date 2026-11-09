package dev.deepseek.mobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Round slider track and circular thumb, independent of the app's continuous card corners. */
@Composable
internal fun CleanSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onValueChangeFinished: () -> Unit = {},
) {
    val update by rememberUpdatedState(onValueChange)
    val finish by rememberUpdatedState(onValueChangeFinished)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    fun quantize(v: Float): Float {
        val f =
            ((v - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
        val stepped = if (steps > 0) (f * (steps + 1)).roundToInt().toFloat() / (steps + 1) else f
        return valueRange.start + stepped * (valueRange.endInclusive - valueRange.start)
    }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(36.dp)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(value, valueRange, steps)
                setProgress {
                    update(quantize(it))
                    finish()
                    true
                }
            }
            .pointerInput(valueRange, steps, rtl) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    fun change(x: Float) {
                        val margin = 12.dp.toPx()
                        val f =
                            ((x - margin) / (size.width - margin * 2).coerceAtLeast(1f)).coerceIn(
                                0f,
                                1f,
                            )
                        update(
                            quantize(
                                valueRange.start +
                                    (if (rtl) 1 - f else f) *
                                        (valueRange.endInclusive - valueRange.start),
                            ),
                        )
                    }
                    change(down.position.x)
                    do {
                        val event = awaitPointerEvent()
                        val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                        pointer.consume()
                        change(pointer.position.x)
                    } while (pointer.pressed)
                    finish()
                }
            },
    ) {
        val margin = 12.dp.toPx()
        val y = center.y
        val fraction =
            ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(
                0f,
                1f,
            )
        val left = Offset(margin, y)
        val right = Offset(size.width - margin, y)
        val thumb =
            Offset(margin + (if (rtl) 1 - fraction else fraction) * (size.width - margin * 2), y)
        drawLine(Color(0xFFE5E7EB), left, right, 5.dp.toPx(), StrokeCap.Round)
        drawLine(WhaleBlue, if (rtl) right else left, thumb, 5.dp.toPx(), StrokeCap.Round)
        drawCircle(Color(0x10000000), 11.dp.toPx(), thumb + Offset(0f, 1.dp.toPx()))
        drawCircle(Color.White, 10.dp.toPx(), thumb)
        drawCircle(Color(0xFFE0E4EC), 10.dp.toPx(), thumb, style = Stroke(1.dp.toPx()))
    }
}
