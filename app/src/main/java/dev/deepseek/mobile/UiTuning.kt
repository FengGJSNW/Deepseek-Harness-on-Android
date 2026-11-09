package dev.deepseek.mobile

import androidx.compose.ui.unit.dp

/** Main dimensions for manual Compose adjustments; the pet follows the composer's geometry. */
internal object UiTuning {
    val ComposerCorner = 28.dp
    val ComposerHorizontalMargin = 12.dp
    val ComposerVerticalMargin = 12.dp
    const val PetSize = 104
    const val PetBubbleExtraHeight = 280
    val PetEndInset = 8.dp
    val PetStartInset = 2.dp
    const val PetIdleDelayMillis = 30_000L
    const val PetTravelDpPerSecond = 24f
    const val PetSquishPressMillis = 100
    const val PetSquishPauseMillis = 180L
    // The expanded PNG has transparent breathing room below its curved silhouette.
    val PetLipOverlap = 3.dp
    val ReasoningPanelMaxWidth = 260.dp
    val ReasoningPanelCorner = 25.dp
    val ReasoningTrackHeight = 24.dp
    val ReasoningThumbRadius = 14.dp
}
