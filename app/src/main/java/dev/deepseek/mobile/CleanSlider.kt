package dev.deepseek.mobile

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import kotlin.math.abs

/** Deliberate thumb drags and explicit fine-step buttons; track taps never change a setting. */
@Composable
internal fun CleanSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onValueChangeFinished: () -> Unit = {},
    enabled: Boolean = true,
) {
    val haptics = rememberUiHaptics()
    val update by rememberUpdatedState(onValueChange)
    val finish by rememberUpdatedState(onValueChangeFinished)
    val current by rememberUpdatedState(value)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val valid = enabled && valueRange.endInclusive > valueRange.start
    var dragging by remember { mutableStateOf(false) }
    val pressed by
        animateFloatAsState(if (dragging) 1f else 0f, tween(120), label = "parameter-drag")
    fun commitValue(next: Float) {
        if (next != current) {
            update(next)
            finish()
            haptics.perform(UiHaptic.Step)
        }
    }
    fun increment(direction: Int) {
        if (!valid) return
        val step =
            (valueRange.endInclusive - valueRange.start) / (if (steps > 0) steps + 1 else 100)
        val next = sliderValue(current + step * direction, valueRange, steps)
        commitValue(next)
    }
    Row(
        modifier.fillMaxWidth().heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SliderStep(false, valid && value > valueRange.start) { increment(-1) }
        Canvas(
            Modifier.weight(1f)
                .height(48.dp)
                .testTag("parameter-slider")
                .semantics {
                    contentDescription = "参数滑杆"
                    stateDescription = "${"%.2f".format(value)}，拖动滑块或使用加减键"
                    if (!valid) disabled()
                    progressBarRangeInfo =
                        ProgressBarRangeInfo(value.coerceIn(valueRange), valueRange, steps)
                    setProgress { requested ->
                        if (!valid || !requested.isFinite()) false
                        else {
                            commitValue(sliderValue(requested, valueRange, steps))
                            true
                        }
                    }
                }
                .onKeyEvent {
                    if (!valid || it.type != KeyEventType.KeyDown) false
                    else
                        when (it.key) {
                            Key.DirectionLeft -> {
                                increment(if (rtl) 1 else -1)
                                true
                            }
                            Key.DirectionRight -> {
                                increment(if (rtl) -1 else 1)
                                true
                            }
                            Key.MoveHome -> {
                                commitValue(valueRange.start)
                                true
                            }
                            Key.MoveEnd -> {
                                commitValue(valueRange.endInclusive)
                                true
                            }
                            else -> false
                        }
                }
                .focusable(enabled = valid)
                .pointerInput(valueRange, steps, rtl, valid) {
                    if (!valid) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val original = current
                        var lastBucket = sliderHapticBucket(original, valueRange, steps)
                        val inset = 16.dp.toPx()
                        val travel = (size.width - inset * 2).coerceAtLeast(1f)
                        val fraction =
                            ((original - valueRange.start) /
                                    (valueRange.endInclusive - valueRange.start))
                                .coerceIn(0f, 1f)
                        val thumb = inset + travel * (if (rtl) 1 - fraction else fraction)
                        val intent =
                            SliderIntent(
                                down.position.x,
                                down.position.y,
                                viewConfiguration.touchSlop,
                                abs(down.position.x - thumb) <= 24.dp.toPx(),
                            )
                        val offset = down.position.x - thumb
                        var changed = false
                        var released = false
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val pointer =
                                    event.changes.firstOrNull { it.id == down.id } ?: break
                                if (pointer.isConsumed || event.changes.count { it.pressed } > 1)
                                    break
                                if (intent.move(pointer.position.x, pointer.position.y)) {
                                    dragging = true
                                    var f =
                                        ((pointer.position.x - offset - inset) / travel).coerceIn(
                                            0f,
                                            1f,
                                        )
                                    if (rtl) f = 1 - f
                                    val next =
                                        sliderValue(
                                            valueRange.start +
                                                f * (valueRange.endInclusive - valueRange.start),
                                            valueRange,
                                            steps,
                                        )
                                    if (next != current) {
                                        update(next)
                                        changed = true
                                        val bucket = sliderHapticBucket(next, valueRange, steps)
                                        if (bucket != lastBucket) {
                                            haptics.perform(UiHaptic.Step)
                                            lastBucket = bucket
                                        }
                                    }
                                    pointer.consume()
                                }
                                if (intent.cancelled) break
                                if (!pointer.pressed) {
                                    released = intent.dragging
                                    break
                                }
                            }
                        } finally {
                            dragging = false
                            if (changed) {
                                if (released) finish() else update(original)
                            }
                        }
                    }
                },
        ) {
            val inset = 16.dp.toPx()
            val range = (valueRange.endInclusive - valueRange.start).coerceAtLeast(.0001f)
            val fraction = ((value - valueRange.start) / range).coerceIn(0f, 1f)
            val x = inset + (size.width - inset * 2) * (if (rtl) 1 - fraction else fraction)
            val gap = 6.dp.toPx()
            val ink = if (valid) WhaleBlue else WhaleMuted.copy(alpha = .4f)
            fun rail(left: Float, right: Float, active: Boolean, outerLeft: Boolean) {
                if (right <= left) return
                val bounds = Rect(left, center.y - 6.dp.toPx(), right, center.y + 6.dp.toPx())
                val outer = 6.dp
                val inner = 2.dp
                val shape =
                    ContinuousShape(
                        topStart = if (outerLeft != rtl) outer else inner,
                        bottomStart = if (outerLeft != rtl) outer else inner,
                        topEnd = if (outerLeft != rtl) inner else outer,
                        bottomEnd = if (outerLeft != rtl) inner else outer,
                    )
                val outline =
                    shape.createOutline(bounds.size, layoutDirection, this) as Outline.Generic
                outline.path.translate(bounds.topLeft)
                drawPath(outline.path, if (active) ink else Color(0xFFE0E1E9))
            }
            rail(inset, x - gap, !rtl, true)
            rail(x + gap, size.width - inset, rtl, false)
            if (steps in 1..20) {
                for (i in 0..steps + 1) {
                    val f = i.toFloat() / (steps + 1)
                    val tx = inset + (size.width - 2 * inset) * if (rtl) 1 - f else f
                    if (abs(tx - x) > gap + 2.dp.toPx())
                        drawCircle(
                            if (f <= fraction) Color.White.copy(alpha = .8f)
                            else WhaleMuted.copy(alpha = .4f),
                            1.3.dp.toPx(),
                            Offset(tx, center.y),
                        )
                }
            }
            val half = (14f + pressed * 3f).dp.toPx()
            drawLine(
                ink,
                Offset(x, center.y - half),
                Offset(x, center.y + half),
                (4f - pressed).dp.toPx(),
                StrokeCap.Round,
            )
        }
        SliderStep(true, valid && value < valueRange.endInclusive) { increment(1) }
    }
}

@Composable
private fun SliderStep(plus: Boolean, enabled: Boolean, action: () -> Unit) {
    Box(
        Modifier.size(36.dp, 48.dp)
            .clip(ContinuousShape(10.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = action)
            .semantics { contentDescription = if (plus) "增大参数" else "减小参数" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(16.dp)) {
            val color = WhaleMuted.copy(alpha = if (enabled) 1f else .25f)
            drawLine(
                color,
                Offset(3.dp.toPx(), center.y),
                Offset(size.width - 3.dp.toPx(), center.y),
                1.5.dp.toPx(),
                StrokeCap.Round,
            )
            if (plus)
                drawLine(
                    color,
                    Offset(center.x, 3.dp.toPx()),
                    Offset(center.x, size.height - 3.dp.toPx()),
                    1.5.dp.toPx(),
                    StrokeCap.Round,
                )
        }
    }
}
