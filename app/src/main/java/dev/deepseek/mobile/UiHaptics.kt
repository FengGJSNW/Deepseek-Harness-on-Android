package dev.deepseek.mobile

import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import kotlin.math.roundToInt

internal enum class UiHaptic {
    Selection,
    ToggleOn,
    ToggleOff,
    Step,
    Submit,
}

internal val LocalUiHaptics = staticCompositionLocalOf<UiHaptics?> { null }

/** Event-only feedback. No permissions, global-setting overrides or background vibrations. */
internal class UiHaptics(
    private val enabled: () -> Boolean,
    private val emit: (Int) -> Boolean,
    private val now: () -> Long = SystemClock::uptimeMillis,
    private val sdk: Int = Build.VERSION.SDK_INT,
) {
    private var lastTick: Long? = null

    fun perform(event: UiHaptic) {
        if (!enabled()) return
        val time = now()
        if (event == UiHaptic.Step && lastTick?.let { time - it < 65 } == true) return
        val effect =
            when (event) {
                UiHaptic.Selection -> HapticFeedbackConstants.CONTEXT_CLICK
                UiHaptic.ToggleOn ->
                    if (sdk >= 34) HapticFeedbackConstants.TOGGLE_ON
                    else HapticFeedbackConstants.CONTEXT_CLICK
                UiHaptic.ToggleOff ->
                    if (sdk >= 34) HapticFeedbackConstants.TOGGLE_OFF
                    else HapticFeedbackConstants.CONTEXT_CLICK
                UiHaptic.Step ->
                    if (sdk >= 34) HapticFeedbackConstants.SEGMENT_TICK
                    else HapticFeedbackConstants.CLOCK_TICK
                UiHaptic.Submit -> HapticFeedbackConstants.CONFIRM
            }
        if (emit(effect) && event == UiHaptic.Step) lastTick = time
    }

    fun toggle(checked: Boolean) = perform(if (checked) UiHaptic.ToggleOn else UiHaptic.ToggleOff)
}

@Composable
internal fun rememberUiHaptics(): UiHaptics {
    LocalUiHaptics.current?.let {
        return it
    }
    val view = LocalView.current
    return remember(view) {
        UiHaptics(
            enabled = { ExperienceSettings.hapticsEnabled && view.isHapticFeedbackEnabled },
            emit = { view.performHapticFeedback(it) },
        )
    }
}

/** Continuous parameters tick every 5% of travel; their values remain fully continuous. */
internal fun sliderHapticBucket(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
): Int {
    val span = range.endInclusive - range.start
    if (!value.isFinite() || span <= 0) return 0
    return (((value - range.start) / span).coerceIn(0f, 1f) * if (steps > 0) steps + 1 else 20)
        .roundToInt()
}
