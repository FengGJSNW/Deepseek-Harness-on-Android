package dev.deepseek.mobile

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import kotlin.math.*

/** Above its own button, with screen-edge clamping; no modal dimming or keyboard dismissal. */
private class AboveControlPosition(
    private val margin: Int,
    private val gap: Int,
    private val topInset: Int,
    private val viewport: () -> IntSize,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        // Compose 1.7 supplies a visible-frame *height* but window-relative anchor coordinates.
        // Edge-to-edge status bars make those disagree. Clamp against the owning window instead.
        val bounds = viewport().takeIf { it.width > 0 && it.height > 0 } ?: windowSize
        val x =
            (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(
                0,
                max(0, bounds.width - popupContentSize.width),
            )
        val y =
            (anchorBounds.top - popupContentSize.height - gap + 24 * (margin / 12f).roundToInt())
                .coerceIn(
                    topInset + margin,
                    max(topInset + margin, bounds.height - popupContentSize.height - margin),
                )
        return IntOffset(x, y)
    }
}

@Composable
internal fun ToolbarMenuAnchor(
    expanded: Boolean,
    close: () -> Unit,
    width: Dp,
    modifier: Modifier = Modifier,
    framed: Boolean = true,
    anchor: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    var top by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val root = LocalView.current.rootView
    val topInset = WindowInsets.statusBars.getTop(density)
    val position =
        remember(density, root, topInset) {
            with(density) {
                AboveControlPosition(12.dp.roundToPx(), 6.dp.roundToPx(), topInset) {
                    IntSize(root.width, root.height)
                }
            }
        }
    Box(modifier.onGloballyPositioned { top = it.boundsInWindow().top.roundToInt() }) {
        anchor()
        if (expanded) {
            val maxWidth = minOf(width, (LocalConfiguration.current.screenWidthDp - 48).dp)
            val availableHeight =
                with(density) { (top - topInset).toDp() - 48.dp }.coerceIn(64.dp, 500.dp)
            Popup(
                popupPositionProvider = position,
                onDismissRequest = close,
                properties =
                    PopupProperties(
                        focusable = true,
                        clippingEnabled = false,
                        securePolicy = SecureFlagPolicy.Inherit,
                    ),
            ) {
                var shown by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { shown = true }
                val reveal by
                    animateFloatAsState(
                        if (shown) 1f else 0f,
                        UiSpring.navigation(.001f),
                        label = "toolbar-reveal",
                    )
                Box(
                    Modifier.functionIslandReveal { reveal }.padding(24.dp),
                ) {
                    if (!framed)
                        Column(
                            Modifier.width(maxWidth)
                                .heightIn(max = availableHeight)
                                .testTag("toolbar-popover"),
                            content = content,
                        )
                    else
                        Surface(
                            Modifier.width(maxWidth)
                                .floatingIslandShadow(ContinuousShape(18.dp))
                                .heightIn(max = availableHeight)
                                .testTag("toolbar-popover"),
                            color = androidx.compose.ui.graphics.Color.White,
                            shape = ContinuousShape(18.dp),
                            border = BorderStroke(.7.dp, WhaleLine),
                            shadowElevation = 0.dp,
                        ) {
                            Column(
                                Modifier.verticalScroll(rememberScrollState()).padding(10.dp),
                                content = content,
                            )
                        }
                }
            }
        }
    }
}
