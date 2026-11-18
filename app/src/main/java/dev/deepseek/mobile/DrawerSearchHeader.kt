package dev.deepseek.mobile

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun DrawerSearchHeader(
    work: Boolean,
    searchVisible: Boolean,
    onSearch: (Rect) -> Unit,
    modifier: Modifier = Modifier,
    searchButtonAlpha: Float = if (searchVisible) 0f else 1f,
    modeExpanded: Boolean = false,
    modeMenu: @Composable ColumnScope.() -> Unit = {},
    onMode: () -> Unit,
) {
    var searchBounds by remember { mutableStateOf(Rect.Zero) }
    Row(
        modifier.fillMaxWidth().height(56.dp).testTag("drawer-search-header"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LiquidMenuAnchor(
            modeExpanded,
            { if (modeExpanded) onMode() },
            width = 268.dp,
            placement = MenuPlacement.BelowControl,
            toggleAnchor = true,
            tag = "drawer-mode-menu",
            openOnPress = onMode,
            anchor = {
                Row(
                    Modifier.testTag("drawer-mode")
                        .clip(ContinuousShape(16.dp))
                        .clickable(onClick = onMode)
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (work) "Work" else "Chat", style = PrismTypography.pageTitle)
                    Icon(
                        AppIcons.ExpandMore,
                        null,
                        Modifier.padding(start = 8.dp).size(17.dp),
                        tint = WhaleMuted,
                    )
                }
            },
            content = modeMenu,
        )
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.onGloballyPositioned { searchBounds = it.boundsInWindow() }
                .graphicsLayer { alpha = searchButtonAlpha }
                .inactiveIf(searchVisible || searchButtonAlpha < .1f),
        ) {
            DshaIconButton("搜索内容", { onSearch(searchBounds) }, size = 48.dp, glass = true) {
                Icon(AppIcons.Search, null, Modifier.size(22.dp), tint = WhaleInk)
            }
        }
    }
}
