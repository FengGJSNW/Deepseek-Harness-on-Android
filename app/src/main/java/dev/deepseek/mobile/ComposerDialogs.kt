package dev.deepseek.mobile

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.deepseek.mobile.terminal.TerminalLevel

/** Options rendered inside their own toolbar-anchored popup. */
@Composable
internal fun ColumnScope.ComposerOptions(kind: String, c: Conversation, close: () -> Unit) {
    Text(
        when (kind) {
            "attach" -> "添加附件"
            "access" -> "操作权限"
            else -> "终端环境"
        },
        Modifier.padding(start = 10.dp, top = 6.dp, bottom = 8.dp),
        fontSize = 12.sp,
        lineHeight = 18.sp,
        color = WhaleMuted,
        fontWeight = FontWeight.Medium,
    )
    when (kind) {
        "attach" -> {
            ToolbarOptionRow(AppIcons.Image, "图片", "最多 4 张", false) {
                MainActivity.current?.pickImage()
                close()
            }
            ToolbarOptionRow(AppIcons.Description, "文件与文档", "PDF、Office、文本等 · 最多 32MB", false) {
                MainActivity.current?.pickText()
                close()
            }
        }
        "access" ->
            listOf("none", "ask", "auto", "full").forEach { value ->
                ToolbarOptionRow(
                    accessIcon(value),
                    accessTitle(value),
                    when (value) {
                        "none" -> "仅聊天"
                        "ask" -> "敏感操作由你确认"
                        "auto" -> "审查 Agent 替你审批"
                        else -> "跳过命令与 Agent 审查"
                    },
                    c.access == value,
                    accessColor(value),
                ) {
                    c.access = value
                    AssistantState.persist()
                    close()
                }
            }
        "terminal" -> {
            ToolbarOptionRow(AppIcons.Terminal, "关闭终端", "", !c.terminalEnabled, WhaleMuted) {
                c.terminalEnabled = false
                AssistantState.persist()
                close()
            }
            TerminalLevel.entries
                .filter { it != TerminalLevel.ISOLATED }
                .forEach { level ->
                    ToolbarOptionRow(
                        terminalIcon(level.key),
                        level.title,
                        "",
                        c.terminalEnabled && c.terminalLevel == level.key,
                        terminalColor(level.key),
                    ) {
                        selectTerminalLevel(c, level)
                        close()
                    }
                }
            HorizontalDivider(Modifier.padding(vertical = 5.dp), color = WhaleLine)
            ToolbarOptionRow(AppIcons.Terminal, "本地 Linux 终端", "直接操作当前工作域", false) {
                LocalTerminalState.open(c.id)
                close()
            }
        }
    }
}

@Composable
private fun ToolbarOptionRow(
    icon: ImageVector,
    title: String,
    detail: String,
    selected: Boolean,
    tint: Color = WhaleBlue,
    click: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clip(ContinuousShape(10.dp))
            .background(if (selected) WhaleTint else Color.Transparent)
            .clickable(onClick = click)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(21.dp), tint = tint)
        Column(Modifier.weight(1f).padding(start = 11.dp, end = 6.dp)) {
            Text(
                title,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = if (selected) tint else WhaleInk,
            )
            if (detail.isNotEmpty())
                Text(
                    detail,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = WhaleMuted,
                    modifier = Modifier.padding(top = 2.dp),
                )
        }
        if (selected) Icon(AppIcons.Check, "已选择", Modifier.size(17.dp), tint = tint)
    }
}
