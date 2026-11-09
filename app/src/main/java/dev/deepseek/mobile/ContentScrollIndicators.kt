package dev.deepseek.mobile

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop

/** Draw-only indicators: scrolling never recomposes the table/code content each frame. */
@Composable
internal fun Modifier.contentScrollIndicators(
    horizontal: ScrollState,
    vertical: ScrollState,
): Modifier {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(horizontal, vertical) {
        snapshotFlow { horizontal.value to vertical.value }
            .drop(1)
            .collectLatest {
                visible = true
                delay(750)
                visible = false
            }
    }
    val alpha =
        animateFloatAsState(
            if (visible) .65f else 0f,
            tween(if (visible) 80 else 220),
            label = "content-scrollbar",
        )
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    return drawWithContent {
        drawContent()
        if (alpha.value <= 0f) return@drawWithContent
        val edge = 3.dp.toPx()
        val thickness = 2.5.dp.toPx()
        val corner = CornerRadius(thickness, thickness)
        val color = Color(0xFF77756F).copy(alpha = alpha.value)
        fun range(state: ScrollState) = state.maxValue.takeIf { it in 1 until Int.MAX_VALUE }
        val horizontalRange = range(horizontal)
        val verticalRange = range(vertical)
        if (horizontalRange != null) {
            val track =
                (size.width - edge * 2 - if (verticalRange != null) thickness + edge else 0f)
                    .coerceAtLeast(1f)
            val thumb =
                (track * size.width / (size.width + horizontalRange)).coerceIn(
                    minOf(20.dp.toPx(), track),
                    track,
                )
            val fraction = horizontal.value.toFloat() / horizontalRange
            val start = edge + (track - thumb) * (if (rtl) 1f - fraction else fraction)
            drawRoundRect(
                color,
                Offset(start, size.height - edge - thickness),
                Size(thumb, thickness),
                corner,
            )
        }
        if (verticalRange != null) {
            val track =
                (size.height - edge * 2 - if (horizontalRange != null) thickness + edge else 0f)
                    .coerceAtLeast(1f)
            val thumb =
                (track * size.height / (size.height + verticalRange)).coerceIn(
                    minOf(20.dp.toPx(), track),
                    track,
                )
            val start = edge + (track - thumb) * vertical.value.toFloat() / verticalRange
            drawRoundRect(
                color,
                Offset(if (rtl) edge else size.width - edge - thickness, start),
                Size(thickness, thumb),
                corner,
            )
        }
    }
}
