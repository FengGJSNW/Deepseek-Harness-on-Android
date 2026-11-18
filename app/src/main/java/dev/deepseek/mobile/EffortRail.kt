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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// Native touch handling with the live ChatGPT model-picker field and activation rules.
// Source capture and adaptation notes: assets/EFFORT-SLIDER-SOURCE.json.
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
    val haptics = rememberUiHaptics()
    val preview by rememberUpdatedState(onPreview)
    val commit by rememberUpdatedState(onCommit)
    val cancel by rememberUpdatedState(onCancel)
    val currentIndex by rememberUpdatedState(index)
    val scope = rememberCoroutineScope()
    val last = stops.lastIndex
    var settling by remember { mutableStateOf<Job?>(null) }
    val motionAllowed = ValueAnimator.areAnimatorsEnabled()
    var dragging by remember { mutableStateOf(false) }
    val rawPosition = remember { mutableFloatStateOf(index.toFloat()) }
    val position = remember { Animatable(index.toFloat()) }
    LaunchedEffect(index) {
        if (!dragging) {
            if (motionAllowed) position.animateTo(index.toFloat(), spring(.9f, 420f))
            else position.snapTo(index.toFloat())
        }
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
    val selectedIndex =
        if (dragging) rawPosition.floatValue.roundToInt().coerceIn(0, last) else index
    val highlighted = effortMaxActive(selectedIndex, last)
    val animate = foreground && enabled && !inspecting && motionAllowed
    val elapsed = rememberStarProgress(animate && highlighted)
    val opacity by
        animateFloatAsState(
            if (highlighted) 1f else 0f,
            tween(if (animate) 300 else 0, easing = CubicBezierEasing(.23f, 1f, .32f, 1f)),
            label = "max-fill-opacity",
        )
    val reveal = remember { Animatable(0f) }
    val burst = remember { Animatable(1f) }
    var previouslyMax by remember { mutableStateOf(highlighted) }
    LaunchedEffect(highlighted, animate) {
        val entering = highlighted && !previouslyMax
        previouslyMax = highlighted
        if (entering && animate) {
            reveal.snapTo(1f)
            burst.snapTo(0f)
            launch {
                reveal.animateTo(0f, tween(2000, easing = CubicBezierEasing(.25f, .1f, .25f, 1f)))
            }
            burst.animateTo(1f, tween(634, easing = LinearEasing))
        } else {
            reveal.snapTo(0f)
            burst.snapTo(1f)
        }
    }
    val context = LocalContext.current
    val maxPainter =
        remember(context) {
            createEffortMaxPainter(
                context.assets.open("effort-max.agsl").bufferedReader().use { it.readText() },
            )
        }
    val insetPx = with(LocalDensity.current) { UiTuning.ReasoningThumbRadius.toPx() }

    Canvas(
        modifier
            .testTag("reasoning-slider")
            .semantics {
                if (!enabled) disabled()
                contentDescription = "思考强度"
                stateDescription = stops[selectedIndex].title + "，" + stops[selectedIndex].detail
                progressBarRangeInfo =
                    ProgressBarRangeInfo(
                        if (dragging) rawPosition.floatValue else index.toFloat(),
                        0f..last.toFloat(),
                        last - 1,
                    )
                setProgress { value ->
                    if (enabled && value.isFinite()) {
                        val next = value.roundToInt().coerceIn(0, last)
                        if (next != currentIndex) {
                            commit(next)
                            haptics.perform(UiHaptic.Step)
                        }
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
                        if (next != currentIndex) {
                            commit(next)
                            haptics.perform(UiHaptic.Step)
                        }
                        true
                    }
                }
            }
            .focusable(enabled = enabled)
            .pointerInput(last, enabled, insetPx) {
                if (!enabled || last <= 0) return@pointerInput
                val width = (size.width - 2 * insetPx).coerceAtLeast(1f)
                fun unitAt(x: Float) = ((x - insetPx) / width).coerceIn(0f, 1f) * last
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val originalIndex = currentIndex
                    settling?.cancel()
                    val thumbX = insetPx + width * position.value / last
                    val onThumb = abs(down.position.x - thumbX) <= 24.dp.toPx()
                    val offset = if (onThumb) down.position.x - thumbX else 0f
                    var target = originalIndex
                    var horizontal = false
                    var released = false
                    var abandoned = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (pointer.isConsumed || event.changes.count { it.pressed } > 1) break
                            val delta = pointer.position - down.position
                            if (!horizontal && pointer.pressed) {
                                if (
                                    abs(delta.y) > viewConfiguration.touchSlop &&
                                        abs(delta.y) > abs(delta.x)
                                ) {
                                    abandoned = true
                                    break
                                }
                                if (
                                    abs(delta.x) > viewConfiguration.touchSlop &&
                                        abs(delta.x) > abs(delta.y) * 1.3f
                                )
                                    horizontal = true
                            }
                            if (horizontal && onThumb) {
                                dragging = true
                                rawPosition.floatValue = unitAt(pointer.position.x - offset)
                                val next = rawPosition.floatValue.roundToInt().coerceIn(0, last)
                                if (next != target) {
                                    target = next
                                    preview(target)
                                    haptics.perform(UiHaptic.Step)
                                }
                                pointer.consume()
                            }
                            if (!pointer.pressed) {
                                released = horizontal && onThumb
                                break
                            }
                        }
                    } finally {
                        val releasePosition =
                            if (dragging) rawPosition.floatValue else position.value
                        // Set state and notify synchronously; the spring cannot commit a stale
                        // gesture.
                        if (released && !abandoned) commit(target) else cancel()
                        settling =
                            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                position.snapTo(releasePosition)
                                dragging = false
                                val destination =
                                    (if (released && !abandoned) target else originalIndex)
                                        .toFloat()
                                if (motionAllowed)
                                    position.animateTo(destination, spring(.9f, 420f))
                                else position.snapTo(destination)
                            }
                    }
                }
            },
    ) {
        val radius = UiTuning.ReasoningTrackHeight.toPx() / 2
        val knob = UiTuning.ReasoningThumbRadius.toPx()
        // 4dp parent bottom padding + 4dp here equals the 8dp horizontal inset.
        val cy = size.height - radius - 4.dp.toPx()
        val fraction =
            ((if (dragging) rawPosition.floatValue else position.value) / last.coerceAtLeast(1))
                .coerceIn(0f, 1f)
        val thumb = insetPx + (size.width - 2 * insetPx) * fraction
        val trackRect = Rect(0f, cy - radius, size.width, cy + radius)
        // Restore circular end caps; blend curvature only where each arc meets the straight rail.
        val trackClip = effortTrackPath(trackRect)
        // The progress boundary follows the thumb centre; use the track's own capsule outline.
        // A second capsule ending at the thumb centre leaves a crescent exposed behind Low.
        val fillClip =
            Path.combine(
                PathOperation.Intersect,
                trackClip,
                Path().apply { addRect(Rect(0f, cy - radius, thumb, cy + radius)) },
            )
        drawPath(trackClip, Color(0xFFE8ECF3))
        clipPath(fillClip) {
            drawRect(Color(0xFF456BD0))
            if (opacity > 0f) {
                // Source: the Range translation is countered by 90% in MaxEffects.
                val shift = ((fraction - 1) * size.width + insetPx - 2 * insetPx * fraction) * .1f
                val bounds =
                    Rect(
                        shift - 1.dp.toPx(),
                        cy - radius - 1.dp.toPx(),
                        shift + size.width + 1.dp.toPx(),
                        cy + radius + 1.dp.toPx(),
                    )
                maxPainter.draw(this, bounds, elapsed.value, reveal.value, opacity)
                if (animate) drawEffortParticles(bounds, elapsed.value, opacity)
            }
        }
        repeat(stops.size) { tick ->
            val color =
                if (tick > selectedIndex) Color(0xFFADB2B8) else Color.White.copy(alpha = .3f)
            drawCircle(
                color.copy(alpha = color.alpha * (1f - opacity)),
                2.dp.toPx(),
                Offset(
                    insetPx + (size.width - 2 * insetPx) * tick / last.coerceAtLeast(1).toFloat(),
                    cy,
                ),
            )
        }
        val center = Offset(thumb, cy)
        if (animate) drawEffortBurst(center, burst.value)
        val shadowRadius = knob + 2.dp.toPx()
        val shadowCenter = center
        drawCircle(
            Brush.radialGradient(
                0f to Color.Black.copy(alpha = .1f),
                (knob / shadowRadius) to Color.Black.copy(alpha = .1f),
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
