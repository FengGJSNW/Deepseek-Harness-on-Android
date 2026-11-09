package dev.deepseek.mobile

import android.animation.ValueAnimator
import androidx.compose.animation.core.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 用户输入只重置空闲计时；巡游与气泡自身的动画不算用户操作。 */
internal object WhaleActivity {
    var revision by mutableLongStateOf(0L)
        private set

    var touching by mutableStateOf(false)
    // Ambient touches reset the initial idle timer, but never interrupt an ongoing patrol.
    var patrolling = false

    fun ambientInteraction() {
        if (!patrolling) interact()
    }

    fun interact() {
        revision++
    }
}

/** 横坐标相对右侧起点，负数向左；拖动期间直接跟手，松手后由动画接管。 */
@Stable
internal class WhaleMotion {
    val x = Animatable(0f)
    val y = Animatable(0f)
    val squish = Animatable(0f)
    var dragging by mutableStateOf(false)
    var held by mutableStateOf(false)
    var dragX by mutableFloatStateOf(0f)
    var dragY by mutableFloatStateOf(0f)
    var facingRight by mutableStateOf(false)
    var phase by mutableStateOf("停靠")
    val shownX
        get() = if (dragging) dragX else x.value

    val shownY
        get() = if (dragging) dragY else y.value

    fun startDrag() {
        dragX = x.value
        dragY = y.value
        dragging = true
        facingRight = false
        phase = "拖动"
    }

    suspend fun releaseDrag(returnHome: Boolean = true) {
        x.snapTo(dragX)
        y.snapTo(dragY)
        dragging = false
        WhaleActivity.interact()
        if (returnHome) {
            phase = "归位"
            coroutineScope {
                launch { x.animateTo(0f, spring(.72f, 500f)) }
                launch { y.animateTo(0f, spring(.72f, 500f)) }
            }
            phase = "停靠"
        }
    }
}

@Composable
internal fun rememberWhaleMotion(
    travelX: Float,
    enabled: Boolean,
    idleKey: Any?,
    keyboardVisible: Boolean = false,
): WhaleMotion {
    val motion = remember { WhaleMotion() }
    // An IME/layout change returns home once; it is not a permanent patrol veto.
    LaunchedEffect(keyboardVisible, enabled) {
        if (!motion.dragging) {
            motion.phase = "归位"
            coroutineScope {
                launch { motion.x.animateTo(0f, spring(.72f, 500f)) }
                launch { motion.y.animateTo(0f, spring(.72f, 500f)) }
            }
            motion.phase = "停靠"
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val inspecting = LocalInspectionMode.current
    val density = LocalDensity.current.density
    val input = WhaleActivity.revision
    val tuning = ExperienceSettings.petMotion
    val touching = (!WhaleActivity.patrolling && WhaleActivity.touching) || motion.held
    LaunchedEffect(
        lifecycle,
        inspecting,
        travelX,
        enabled,
        idleKey,
        keyboardVisible,
        input,
        touching,
        motion.dragging,
        tuning,
    ) {
        if (inspecting) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                if (touching || motion.dragging) return@repeatOnLifecycle
                if (!enabled || travelX <= 0f || !ValueAnimator.areAnimatorsEnabled())
                    return@repeatOnLifecycle
                delay(UiTuning.PetIdleDelayMillis)
                WhaleActivity.patrolling = true
                val duration =
                    (travelX / density / tuning.movement * 1000f).toInt().coerceAtLeast(1000)
                coroutineScope {
                    launch {
                        while (true) {
                            val pressure = kotlin.random.Random.nextDouble(.82, 1.0).toFloat()
                            motion.squish.playPetBounce(
                                tuning,
                                pressure,
                                kotlin.random.Random.nextInt(-30, 31),
                            )
                        }
                    }
                    while (true) {
                        motion.facingRight = false
                        motion.phase = "向左巡游"
                        motion.x.animateTo(
                            -travelX,
                            tween(
                                (duration * kotlin.math.abs(-travelX - motion.x.value) / travelX)
                                    .toInt()
                                    .coerceAtLeast(16),
                                easing = LinearEasing,
                            ),
                        )
                        motion.facingRight = true
                        motion.phase = "向右巡游"
                        motion.x.animateTo(0f, tween(duration, easing = LinearEasing))
                        delay(16)
                    }
                }
            } finally {
                WhaleActivity.patrolling = false
                withContext(NonCancellable) { motion.squish.snapTo(0f) }
                if (!motion.dragging) {
                    motion.facingRight = false
                    motion.phase = "停靠"
                }
            }
        }
    }
    return motion
}
