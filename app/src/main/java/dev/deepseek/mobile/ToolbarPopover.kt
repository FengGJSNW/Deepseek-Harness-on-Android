package dev.deepseek.mobile

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import kotlin.math.*

/** Composer menus keep IME focus while their glass expands from the active control. */
@Composable
internal fun ToolbarMenuAnchor(
    expanded: Boolean,
    close: () -> Unit,
    width: Dp,
    modifier: Modifier = Modifier,
    framed: Boolean = true,
    openOnPress: (() -> Unit)? = null,
    anchor: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) =
    LiquidMenuAnchor(
        expanded,
        close,
        width,
        modifier,
        framed = framed,
        openOnPress = openOnPress,
        toggleAnchor = true,
        anchor = anchor,
        content = content,
    )
