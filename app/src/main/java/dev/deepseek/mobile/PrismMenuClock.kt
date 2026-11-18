package dev.deepseek.mobile

import androidx.compose.runtime.*
import androidx.compose.ui.MotionDurationScale
import kotlin.math.PI
import kotlin.math.min
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first

internal suspend fun runPrismClock(frame: (seconds: Float) -> Boolean) {
    val scale = currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f
    if (scale <= 0f) return
    val start = withFrameNanos { it }
    if (frame(0f)) return
    while (true) {
        if (withFrameNanos { frame((it - start) / (1_000_000_000.0 * scale).toFloat()) }) return
    }
}

internal class PrismPopupClock(val rowMorph: Boolean = false) {
    var opening by mutableFloatStateOf(0f)
        private set

    var returning by mutableStateOf<Float?>(null)
        private set

    var unfold by mutableFloatStateOf(0f)
        private set

    private var rowVelocity = 0f
    val motion = derivedStateOf { if (rowMorph) unfold else returning ?: opening }

    var retained by mutableStateOf(false)
        private set

    var ready by mutableStateOf(false)

    fun hide() {
        retained = false
        ready = false
        opening = 0f
        returning = null
        unfold = 0f
        rowVelocity = 0f
    }

    suspend fun move(open: Boolean) {
        if (open) {
            retained = true
            snapshotFlow { ready }.first { it }
        } else if (!retained) return

        if (rowMorph && ready) {
            val target = if (open) 1f else 0f
            val response = if (open) .42 else .34
            val spring =
                PrismSpring(
                    (4 * PI * PI / (response * response)).toFloat(),
                    if (open) .85f else 1f,
                    unfold,
                    target,
                    rowVelocity,
                )
            if (unfold != target || rowVelocity != 0f)
                runPrismClock { t ->
                    unfold = spring.value(t)
                    rowVelocity = spring.velocity(t)
                    spring.settled(t)
                }
            unfold = target
            rowVelocity = 0f
        } else if (!rowMorph && ready) {
            if (!open || returning != null) {
                val from = returning ?: 0f
                returning = from
                runPrismClock { t ->
                    returning = min(PrismPopupMotion.RETURN_SECONDS, from + t)
                    from + t >= PrismPopupMotion.RETURN_SECONDS
                }
                opening = 0f
                returning = null
            }
            if (open) {
                val from = opening
                runPrismClock { t ->
                    opening = min(PrismPopupMotion.OPEN_SECONDS, from + t)
                    from + t >= PrismPopupMotion.OPEN_SECONDS
                }
                opening = PrismPopupMotion.OPEN_SECONDS
            }
        }
        if (!open) hide()
    }

    fun buttonFrame(
        anchor: androidx.compose.ui.geometry.Rect,
        card: androidx.compose.ui.geometry.Rect,
        bare: Boolean,
    ): PrismPopupFrame {
        val start = PrismPopupMotion.opening(opening, anchor, card, bare)
        return returning?.let { PrismPopupMotion.closing(it, anchor, start, bare) } ?: start
    }
}
