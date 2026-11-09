package dev.deepseek.mobile

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp

/** Outgoing layers can keep drawing without retaining touch or accessibility actions. */
internal fun Modifier.inactiveIf(inactive: Boolean): Modifier =
    if (!inactive) this
    else
        clearAndSetSemantics {}
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach {
                        it.consume()
                    }
                }
            }

/** No blur/offscreen buffer: the panel resolves with a small, anchored spring movement. */
internal fun Modifier.functionIslandReveal(progress: () -> Float): Modifier = graphicsLayer {
    val p = progress().coerceIn(0f, 1f)
    alpha = p
    translationY = 6.dp.toPx() * (1f - p)
    scaleX = .985f + .015f * p
    scaleY = scaleX
}

/**
 * Apple response/dampingRatio model with unit mass: k = (2 * PI / response)^2, c = 2 *
 * dampingRatio * sqrt(k). App tuning, not private iOS constants. Critical damping avoids bouncing
 * reading surfaces and preserves interruption velocity.
 */
internal object UiSpring {
    const val NavigationResponse = .32f
    const val DrawerResponse = .26f
    const val DisclosureResponse = .30f
    const val DampingRatio = 1f

    fun <T> navigation(visibilityThreshold: T? = null) =
        spec(NavigationResponse, visibilityThreshold)

    fun drawer(visibilityThreshold: Float) = spec(DrawerResponse, visibilityThreshold)

    fun <T> disclosure(visibilityThreshold: T? = null) =
        spec(DisclosureResponse, visibilityThreshold)

    private fun <T> spec(response: Float, threshold: T?) =
        androidx.compose.animation.core.spring<T>(
            dampingRatio = DampingRatio,
            stiffness = ((2.0 * Math.PI / response) * (2.0 * Math.PI / response)).toFloat(),
            visibilityThreshold = threshold,
        )
}
