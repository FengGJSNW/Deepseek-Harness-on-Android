package dev.deepseek.mobile

import androidx.compose.animation.core.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign

internal val LocalGlassPress = staticCompositionLocalOf<GlassPressMotion?> { null }

internal data class GlassPressTransform(val offset: Offset, val scaleX: Float, val scaleY: Float)

/** Drawing-only movement: targets and popup anchors retain their resting bounds. */
internal class GlassPressMotion {
    var held by mutableStateOf(false)
    var drag by mutableStateOf(Offset.Zero)
    var panel by mutableStateOf(Size.Zero)
    val lift = mutableFloatStateOf(0f)
    var sequence by mutableIntStateOf(0)
        private set

    private var liftVelocity = 0f
    private var hasLifted = false
    var pull: State<Offset> = mutableStateOf(Offset.Zero)
    var maxGrowth = 16f
    var inset = 8f

    fun press() {
        drag = Offset.Zero
        held = true
        hasLifted = false
        sequence++
    }

    suspend fun followPress() {
        if (held || !hasLifted) {
            val rise = PrismSpring(311f, .593f, lift.floatValue, 1f, liftVelocity)
            runPrismClock { t ->
                lift.floatValue = rise.value(t)
                liftVelocity = rise.velocity(t)
                if (lift.floatValue >= .975f) hasLifted = true
                (!held && hasLifted) || rise.settled(t, .01764f)
            }
            if (held) {
                lift.floatValue = 1f
                liftVelocity = 0f
                hasLifted = true
                return
            }
        }
        val fall = PrismSpring(311f, .593f, lift.floatValue, 0f, liftVelocity)
        runPrismClock { t ->
            lift.floatValue = fall.value(t)
            liftVelocity = fall.velocity(t)
            fall.settled(t, .01764f)
        }
        lift.floatValue = 0f
        liftVelocity = 0f
    }

    fun transform(): GlassPressTransform {
        val width = panel.width.coerceAtLeast(1f)
        val height = panel.height.coerceAtLeast(1f)
        val swell = 1f + lift.value * min(.25f, maxGrowth / maxOf(width, height))
        val offset = pull.value
        return GlassPressTransform(
            offset,
            swell * (1f + abs(offset.x) * .5f / width),
            swell * (1f + abs(offset.y) * .5f / height),
        )
    }
}

internal fun glassPull(delta: Offset, panel: Size): Offset {
    val limit = min(panel.width, panel.height).coerceAtLeast(1f) * .21875f
    fun bend(delta: Float) = sign(delta) * limit * (1f - 1f / (abs(delta) * .55f / limit + 1f))
    return Offset(bend(delta.x), bend(delta.y))
}

@Composable
internal fun rememberGlassPress(): GlassPressMotion {
    val motion = remember { GlassPressMotion() }
    val density = LocalDensity.current
    motion.maxGrowth = with(density) { 16.dp.toPx() }
    motion.inset = with(density) { 8.dp.toPx() }
    motion.pull =
        animateOffsetAsState(
            if (motion.held) glassPull(motion.drag, motion.panel) else Offset.Zero,
            if (motion.held) spring(1f, 2741.557f) else spring(.593f, 311f),
            label = "glass-pull",
        )
    LaunchedEffect(motion.sequence, motion.held) { if (motion.sequence != 0) motion.followPress() }
    return motion
}

/** Isolate chrome repainting while allowing the menu to record just the source ink. */
@Composable
internal fun Modifier.glassPress(motion: GlassPressMotion, enabled: Boolean = true): Modifier =
    onSizeChanged {
        // Glass is inset by 4dp on each side; normalize growth in the visible pane.
        motion.panel =
            Size(
                (it.width - motion.inset).coerceAtLeast(1f),
                (it.height - motion.inset).coerceAtLeast(1f),
            )
    }
    .pointerInput(motion, enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            motion.press()
            try {
                do {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (
                        !pointer.pressed ||
                            (pointer.isConsumed && pointer.position != pointer.previousPosition)
                    )
                        break
                    motion.drag = pointer.position - down.position
                } while (true)
            } finally {
                motion.held = false
                motion.drag = Offset.Zero
            }
        }
    }
    .then(if (LocalGlassInkCapture.current == null) Modifier.graphicsLayer() else Modifier)
    .drawWithContent {
        val transform = motion.transform()
        translate(transform.offset.x, transform.offset.y) {
            scale(transform.scaleX, transform.scaleY, center) { this@drawWithContent.drawContent() }
        }
    }
