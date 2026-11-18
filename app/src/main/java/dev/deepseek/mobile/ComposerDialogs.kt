package dev.deepseek.mobile

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.deepseek.mobile.terminal.TerminalLevel
import org.json.JSONObject

/** Options rendered inside their own toolbar-anchored popup. */
@Composable
internal fun ColumnScope.ComposerOptions(kind: String, c: Conversation, close: () -> Unit) {
    val afterReturn = LocalAfterMenuReturn.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            when (kind) {
                "attach" -> "添加附件"
                "access" -> "操作权限"
                else -> "终端环境"
            },
            Modifier.weight(1f).padding(start = 10.dp, top = 6.dp, bottom = 8.dp),
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = WhaleMuted,
            fontWeight = FontWeight.Medium,
        )
        if (kind == "terminal") SandboxSwitch(c)
    }
    val running = AssistantState.running?.takeIf { it.sessionId == c.id }
    if (running != null && kind in setOf("access", "terminal")) {
        val current =
            if (kind == "access") accessTitle(running.access)
            else
                terminalTitle(
                    running.terminalEnabled,
                    runCatching {
                            JSONObject(running.terminalConfig).getString("level")
                        }
                        .getOrDefault("sandbox"),
                )
        val selected =
            if (kind == "access") accessTitle(c.access)
            else if (c.sandboxEnabled) "Sandbox"
            else terminalTitle(c.terminalEnabled, c.terminalLevel)
        Text(
            if (current == selected) "当前任务：$current。改动对新任务生效。" else "当前任务：$current · 新任务：$selected",
            Modifier.padding(start = 10.dp, end = 10.dp, bottom = 6.dp),
            color = WhaleMuted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
    }
    when (kind) {
        "attach" -> {
            ToolbarOptionRow(AppIcons.Image, "图片", "最多 4 张", false) {
                afterReturn { MainActivity.current?.pickImage() }
            }
            ToolbarOptionRow(AppIcons.Description, "文件与文档", "PDF、Office、文本等 · 最多 32MB", false) {
                afterReturn { MainActivity.current?.pickText() }
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
            SandboxSelection(c)
            ToolbarOptionRow(AppIcons.Terminal, "关闭终端", "", !c.terminalEnabled, WhaleMuted) {
                c.terminalEnabled = false
                AssistantState.persist()
                close()
            }
            TerminalLevel.entries
                .filter { it !in setOf(TerminalLevel.ISOLATED, TerminalLevel.SANDBOX) }
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
            GlassMenuDivider()
            ToolbarOptionRow(AppIcons.Terminal, "Sandbox 终端", "选择一个 Sandbox 进入", false) {
                afterReturn { WorkspaceState.open(WorkspaceTab.SandboxAccess) }
            }
        }
    }
}

private fun terminalTitle(enabled: Boolean, level: String) =
    if (enabled) TerminalLevel.of(level).title else "已关闭"

@Composable
private fun ToolbarOptionRow(
    icon: ImageVector,
    title: String,
    detail: String,
    selected: Boolean,
    tint: Color = WhaleBlue,
    click: () -> Unit,
) {
    GlassMenuRow(icon, title, detail, selected, if (tint == WhaleBlue) WhaleInk else tint, click)
}
