package dev.deepseek.mobile

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.dp

/** 页面间复用的小按钮、入口行和任务状态文字。 */
@Composable
internal fun SmallIcon(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    action: () -> Unit,
) {
    DshaIconButton(label = label, onClick = action, enabled = enabled, glass = true) {
        Icon(icon, null, Modifier.size(22.dp), tint = if (enabled) WhaleInk else WhaleMuted)
    }
}

@Composable
internal fun ActionRow(icon: ImageVector, title: String, detail: String, click: () -> Unit) {
    DshaInteractiveRow(
        icon,
        title,
        detail,
        Modifier.padding(vertical = 5.dp),
        fill = WhaleTint,
        trailingIcon = AppIcons.ArrowOut,
        onClick = click,
    )
}

internal fun statusName(s: String) =
    mapOf(
        "queued" to "排队中",
        "running" to "执行中",
        "waiting" to "等待你确认",
        "stopping" to "正在停止",
        "done" to "已结束",
        "failed" to "失败",
        "cancelled" to "已取消",
        "interrupted" to "已中断",
    )[s] ?: s

/** Consistent quick-selection rows, shared by centered islands. */
@Composable
internal fun OptionRow(
    icon: ImageVector,
    title: String,
    detail: String,
    selected: Boolean,
    tint: androidx.compose.ui.graphics.Color = WhaleBlue,
    click: () -> Unit,
) {
    DshaInteractiveRow(
        icon,
        title,
        detail,
        Modifier.padding(vertical = 2.dp),
        selected = selected,
        tint = tint,
        onClick = click,
    )
}
