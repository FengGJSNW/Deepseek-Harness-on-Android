package dev.deepseek.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal val LocalWorkspaceTopInset = compositionLocalOf { 0.dp }

/** Put [topInset] inside the scroll content so rows can travel behind the fading header. */
@Composable
internal fun FadingHeaderLayout(
    modifier: Modifier = Modifier,
    height: Dp = 68.dp,
    color: Color = DshaSurface.page,
    sharedTop: Dp = 0.dp,
    header: @Composable () -> Unit,
    content: @Composable (topInset: Dp) -> Unit,
) {
    val view = LocalView.current
    val layer = rememberGraphicsLayer()
    val backdrop = remember(layer, view) { ChatBackdrop(layer, view) }
    CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
        Box(modifier.fillMaxSize().onPlaced { backdrop.viewport = it }) {
            Box(Modifier.matchParentSize().chatBackdrop(backdrop)) { content(height) }
            HeaderFade(
                Modifier.fillMaxWidth().padding(top = sharedTop).height(height - sharedTop + 24.dp),
                color,
                fadeIn = sharedTop > 0.dp,
            )
            Box(Modifier.fillMaxWidth().height(height)) { header() }
        }
    }
}
