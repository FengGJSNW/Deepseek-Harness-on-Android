package dev.deepseek.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isFinite
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.delay

/** The original window retains the finger; popup rows contribute bounds in screen coordinates. */
internal class PressMenuTracking {
    private data class Target(val bounds: () -> Rect?, val choose: () -> Unit)

    private val targets = linkedMapOf<Any, Target>()
    var hovered by mutableStateOf<Any?>(null)
        private set

    private var origin = Offset.Zero
    private var point = Offset.Zero
    private var dragging = false
    private var held = false

    fun begin(position: Offset) {
        origin = position
        point = position
        dragging = false
        held = true
        hovered = null
    }

    fun move(position: Offset, slop: Float) {
        point = position
        dragging = dragging || (position - origin).getDistance() > slop
        if (held && dragging)
            hovered = targets.entries.lastOrNull { it.value.bounds()?.contains(point) == true }?.key
    }

    fun register(id: Any, bounds: () -> Rect?, choose: () -> Unit) {
        targets[id] = Target(bounds, choose)
        if (held && dragging) move(point, 0f)
    }

    fun remove(id: Any) {
        targets.remove(id)
    }

    fun release(position: Offset) {
        val target =
            if (dragging) targets[hovered]?.takeIf { it.bounds()?.contains(position) == true }
            else null
        cancel()
        target?.choose?.invoke()
    }

    fun cancel() {
        held = false
        dragging = false
        hovered = null
    }
}

/** A non-scrolling PopupAnchor opens on down. A stationary release leaves it open. */
internal fun Modifier.openMenuOnDown(
    tracking: PressMenuTracking,
    screenPosition: (Offset) -> Offset,
    start: () -> Boolean,
) =
    pointerInput(tracking) {
        awaitEachGesture {
            val down = awaitFirstDown(pass = PointerEventPass.Initial)
            down.consume()
            val carry = start()
            if (carry) tracking.begin(screenPosition(down.position))
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    val point = screenPosition(change.position)
                    if (change.isConsumed) break
                    change.consume()
                    if (carry) tracking.move(point, viewConfiguration.touchSlop)
                    if (!change.pressed) {
                        if (carry) tracking.release(point)
                        break
                    }
                }
            } finally {
                tracking.cancel()
            }
        }
    }

internal val LocalCarriedMenu = staticCompositionLocalOf<PressMenuTracking?> { null }

@Composable
internal fun Modifier.menuPickTarget(
    tracking: PressMenuTracking?,
    id: Any,
    onPick: () -> Unit,
): Modifier {
    if (tracking == null) return this
    val action by rememberUpdatedState(onPick)
    DisposableEffect(tracking, id) { onDispose { tracking.remove(id) } }
    return onGloballyPositioned { coordinates ->
            tracking.register(
                id,
                {
                    if (!coordinates.isAttached) null
                    else {
                        val start = coordinates.localToScreen(Offset.Zero)
                        val end =
                            coordinates.localToScreen(
                                Offset(
                                    coordinates.size.width.toFloat(),
                                    coordinates.size.height.toFloat(),
                                ),
                            )
                        if (!start.isFinite || !end.isFinite) null else Rect(start, end)
                    }
                },
            ) {
                action()
            }
        }
        .background(
            if (tracking.hovered == id) WhaleTint
            else androidx.compose.ui.graphics.Color.Transparent,
        )
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
internal fun Modifier.pressMenuGesture(
    tracking: PressMenuTracking,
    screenOrigin: () -> Offset,
    enabled: Boolean,
    onHold: () -> Boolean,
): Modifier =
    pointerInput(tracking, enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val held = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
            held.consume()
            val selecting = onHold()
            if (selecting) tracking.begin(screenOrigin() + held.position)
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    change.consume()
                    val position = screenOrigin() + change.position
                    if (!change.pressed) {
                        if (selecting) tracking.release(position)
                        break
                    }
                    if (selecting) tracking.move(position, viewConfiguration.touchSlop)
                }
            } finally {
                tracking.cancel()
            }
        }
    }

@Composable
internal fun PressMenuRow(
    tracking: PressMenuTracking,
    id: String,
    icon: ImageVector,
    title: String,
    onHover: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val action by rememberUpdatedState(onClick)
    val hover by rememberUpdatedState(onHover)
    LaunchedEffect(tracking.hovered == id) {
        if (tracking.hovered == id && hover != null) {
            delay(400)
            hover?.invoke()
        }
    }
    Box(Modifier.menuPickTarget(tracking, id) { action() }) {
        GlassMenuRow(icon, title, onClick = { action() })
    }
}
