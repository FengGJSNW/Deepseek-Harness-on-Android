package dev.deepseek.mobile

import android.animation.ValueAnimator
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.*
import kotlinx.coroutines.launch

// Adapted to native Canvas from Bai-2008/dsh-better-panel (MIT).
// Source: assets/EFFORT-SLIDER-SOURCE.json; notices: EFFORT-SLIDER-LICENSE.txt.
@Composable
internal fun EffortRail(
    index: Int,
    onPreview: (Int) -> Unit,
    onCommit: (Int) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier,
    stops: List<EffortStop> = effortStops,
    enabled: Boolean = true,
) {
    val preview by rememberUpdatedState(onPreview)
    val commit by rememberUpdatedState(onCommit)
    val cancel by rememberUpdatedState(onCancel)
    val currentIndex by rememberUpdatedState(index)
    val scope = rememberCoroutineScope()
    val last = stops.lastIndex
    var dragging by remember { mutableStateOf(false) }
    val rawPosition = remember { mutableFloatStateOf(index.toFloat()) }
    val position = remember { Animatable(index.toFloat()) }
    LaunchedEffect(index) {
        if (!dragging) position.animateTo(index.toFloat(), spring(.78f, 600f))
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val inspecting = LocalInspectionMode.current
    var foreground by
        remember(lifecycle) {
            mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val elapsed =
        rememberStarProgress(
            foreground &&
                enabled &&
                !inspecting &&
                ValueAnimator.areAnimatorsEnabled() &&
                (index > 0 || dragging),
        )
    val insetPx = with(LocalDensity.current) { (UiTuning.ReasoningThumbRadius + 1.dp).toPx() }

    Canvas(
        modifier
            .testTag("reasoning-slider")
            .semantics {
                if (!enabled) disabled()
                contentDescription = "思考强度"
                stateDescription = stops[index].title + "，" + stops[index].detail
                progressBarRangeInfo =
                    ProgressBarRangeInfo(
                        if (dragging) rawPosition.floatValue else index.toFloat(),
                        0f..last.toFloat(),
                        last - 1,
                    )
                setProgress { value ->
                    if (enabled && value.isFinite()) {
                        commit(value.roundToInt().coerceIn(0, last))
                        true
                    } else false
                }
            }
            .onKeyEvent { event ->
                if (!enabled || event.type != KeyEventType.KeyDown) false
                else {
                    val next =
                        when (event.key) {
                            Key.DirectionLeft -> (currentIndex - 1).coerceAtLeast(0)
                            Key.DirectionRight -> (currentIndex + 1).coerceAtMost(last)
                            Key.MoveHome -> 0
                            Key.MoveEnd -> last
                            else -> null
                        }
                    if (next == null) false
                    else {
                        commit(next)
                        true
                    }
                }
            }
            .focusable()
            .pointerInput(last, enabled) {
                if (!enabled) return@pointerInput
                fun unitAt(x: Float) =
                    ((x - insetPx) / (size.width - 2 * insetPx).coerceAtLeast(1f)).coerceIn(
                        0f,
                        1f,
                    ) * last.toFloat()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    dragging = true
                    rawPosition.floatValue = unitAt(down.position.x)
                    var target = rawPosition.floatValue.roundToInt().coerceIn(0, last)
                    preview(target)
                    var released = false
                    try {
                        do {
                            val event = awaitPointerEvent()
                            val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                            rawPosition.floatValue = unitAt(pointer.position.x)
                            val next = rawPosition.floatValue.roundToInt().coerceIn(0, last)
                            if (next != target) {
                                target = next
                                preview(target)
                            }
                            released = !pointer.pressed && !pointer.isConsumed
                            pointer.consume()
                            if (!pointer.pressed) break
                        } while (true)
                    } finally {
                        val releasePosition = rawPosition.floatValue
                        // Keep drawing the exact finger position until the snap animation takes
                        // over.
                        scope.launch {
                            position.snapTo(releasePosition)
                            dragging = false
                            if (released) commit(target) else cancel()
                            position.animateTo(
                                (if (released) target else currentIndex).toFloat(),
                                spring(.78f, 600f),
                            )
                        }
                    }
                }
            },
    ) {
        val cy = size.height / 2
        val radius = UiTuning.ReasoningTrackHeight.toPx() / 2
        val knob = UiTuning.ReasoningThumbRadius.toPx()
        val targetFraction =
            ((if (dragging) rawPosition.floatValue else position.value) / last)
                .coerceIn(0f, 1f)
                .toDouble()
        val thumb =
            insetPx +
                (size.width - 2 * insetPx) *
                    ((if (dragging) rawPosition.floatValue else position.value) / last).coerceIn(
                        0f,
                        1f,
                    )
        val trackRect = Rect(0f, cy - radius, size.width, cy + radius)
        // Slider ends are circular arcs, independent of the continuous panel outline.
        val trackClip = Path().apply { addRoundRect(RoundRect(trackRect, CornerRadius(radius))) }
        // The progress boundary follows the thumb centre; use the track's own capsule outline.
        // A second capsule ending at the thumb centre leaves a crescent exposed behind Low.
        val fillClip =
            Path.combine(
                PathOperation.Intersect,
                trackClip,
                Path().apply { addRect(Rect(0f, cy - radius, thumb, cy + radius)) },
            )
        drawRoundRect(Color(0xFFE8ECF3), trackRect.topLeft, trackRect.size, CornerRadius(radius))
        if (targetFraction > .0001)
            clipPath(fillClip) {
                drawRect(
                    Brush.horizontalGradient(
                        listOf(EffortBlue, effortFillEnd(targetFraction)),
                        endX = thumb,
                    ),
                )
                drawRect(
                    Brush.horizontalGradient(
                        0f to Color(0xFF3F8CFF),
                        .55f to Color(0xFF7C6CF6),
                        1f to Color(0xFFA855F7),
                        endX = thumb,
                    ),
                    alpha = effortEnergy(targetFraction).toFloat(),
                )
                // Original cxp-drift: +4px -> -(travel+8px), 0/12/82/100% opacity keys.
                val travel =
                    ((10.0 + targetFraction * (size.width.toDp().value - 20.0) + 16.0).roundToInt())
                        .dp
                        .toPx()
                repeat((effortEnergy(targetFraction) * effortStars.size).roundToInt()) { i ->
                    val star = effortStars[i]
                    val durationMillis = (star.duration(targetFraction) * 1000).roundToInt()
                    val offsetMillis = (-star.delay(targetFraction) * 1000).roundToInt()
                    val progress = ((elapsed.value * 1000 + offsetMillis) / durationMillis) % 1f
                    val fade =
                        when {
                            progress < .12f -> progress / .12f
                            progress <= .82f -> 1f
                            else -> (1f - progress) / .18f
                        }.coerceIn(0f, 1f)
                    val opacity = fade * star.brightness.toFloat()
                    val diameter = star.diameter.toFloat().dp.toPx()
                    val point =
                        Offset(
                            thumb + 4.dp.toPx() - progress * (travel + 12.dp.toPx()) + diameter / 2,
                            cy - radius +
                                radius * 2 * (star.topPercent / 100).toFloat() +
                                diameter / 2,
                        )
                    val starRadius = diameter / 2
                    val halo = starRadius + 2.dp.toPx()
                    drawCircle(
                        Brush.radialGradient(
                            listOf(
                                Color(0xFFEAF0FF).copy(alpha = .7f * opacity),
                                Color.Transparent,
                            ),
                            point,
                            halo,
                        ),
                        halo,
                        point,
                    )
                    drawCircle(Color(0xFFEAF0FF).copy(alpha = opacity), starRadius, point)
                }
            }
        repeat(stops.size) { tick ->
            val color = if (tick > index) Color(0xFFADB2B8) else Color(0xFFEBEEF2)
            drawCircle(
                color.copy(alpha = if (tick == index) 1f else if (index == last) 0f else 1f),
                2.dp.toPx(),
                Offset(insetPx + (size.width - 2 * insetPx) * tick / last.toFloat(), cy),
            )
        }
        val center = Offset(thumb, cy)
        val shadowRadius = knob + 8.dp.toPx()
        val shadowCenter = center + Offset(0f, 2.dp.toPx())
        drawCircle(
            Brush.radialGradient(
                0f to Color.Black.copy(alpha = .18f),
                (knob / shadowRadius) to Color.Black.copy(alpha = .18f),
                1f to Color.Transparent,
                center = shadowCenter,
                radius = shadowRadius,
            ),
            shadowRadius,
            shadowCenter,
        )
        drawCircle(Color.White, knob, center)
        drawCircle(
            Color(0xFFE2E6EF),
            knob,
            center,
            style = androidx.compose.ui.graphics.drawscope.Stroke(.6.dp.toPx()),
        )
    }
}

@Composable
private fun rememberStarProgress(animate: Boolean): State<Float> {
    // All particles use one clock; offsets/durations still differ per star in draw.
    return if (animate)
        rememberInfiniteTransition(label = "effort-stars")
            .animateFloat(
                0f,
                86400f,
                infiniteRepeatable(tween(86_400_000, easing = LinearEasing)),
                label = "effort-clock",
            )
    else rememberUpdatedState(0f)
}
