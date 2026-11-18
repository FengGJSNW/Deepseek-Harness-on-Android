package dev.deepseek.mobile

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.vector.ImageVector

@Composable
internal fun SettingsChoice(
    icon: ImageVector,
    title: String,
    detail: String,
    content: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    LiquidMenuAnchor(
        expanded,
        { expanded = false },
        toggleAnchor = true,
        placement = MenuPlacement.SelectedRow,
        menuTitle = title,
        anchor = { SettingsLink(icon, title, detail) { expanded = !expanded } },
    ) {
        content { expanded = false }
    }
}
