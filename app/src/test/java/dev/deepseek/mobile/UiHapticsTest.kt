package dev.deepseek.mobile

import android.view.HapticFeedbackConstants
import org.junit.Assert.*
import org.junit.Test

class UiHapticsTest {
    @Test
    fun settingIsReadAtEventTimeAndTicksAreRateLimited() {
        var enabled = true
        var time = 1000L
        val effects = mutableListOf<Int>()
        val haptics =
            UiHaptics(
                { enabled },
                {
                    effects.add(it)
                    true
                },
                { time },
                35,
            )
        haptics.perform(UiHaptic.Step)
        time += 20
        haptics.perform(UiHaptic.Step)
        time += 45
        haptics.perform(UiHaptic.Step)
        assertEquals(2, effects.size)
        enabled = false
        haptics.perform(UiHaptic.Submit)
        haptics.toggle(true)
        assertEquals(2, effects.size)
        enabled = true
        haptics.toggle(true)
        haptics.toggle(false)
        assertEquals(
            listOf(HapticFeedbackConstants.TOGGLE_ON, HapticFeedbackConstants.TOGGLE_OFF),
            effects.takeLast(2),
        )
    }

    @Test
    fun android30UsesSupportedFallbacksAndNoHardwareIsHarmless() {
        val effects = mutableListOf<Int>()
        val haptics =
            UiHaptics(
                { true },
                {
                    effects.add(it)
                    false
                },
                { 0L },
                30,
            )
        haptics.toggle(true)
        haptics.toggle(false)
        haptics.perform(UiHaptic.Step)
        haptics.perform(UiHaptic.Step)
        haptics.perform(UiHaptic.Submit)
        assertEquals(
            listOf(
                HapticFeedbackConstants.CONTEXT_CLICK,
                HapticFeedbackConstants.CONTEXT_CLICK,
                HapticFeedbackConstants.CLOCK_TICK,
                HapticFeedbackConstants.CLOCK_TICK,
                HapticFeedbackConstants.CONFIRM,
            ),
            effects,
        )
    }

    @Test
    fun continuousMotionTicksAtBoundariesWithoutQuantizingTheValue() {
        assertEquals(sliderHapticBucket(.50f, 0f..1f, 0), sliderHapticBucket(.51f, 0f..1f, 0))
        assertNotEquals(sliderHapticBucket(.50f, 0f..1f, 0), sliderHapticBucket(.56f, 0f..1f, 0))
        assertEquals(3, sliderHapticBucket(4f, 1f..8f, 6))
        assertEquals(0, sliderHapticBucket(Float.NaN, 0f..1f, 0))
    }
}
