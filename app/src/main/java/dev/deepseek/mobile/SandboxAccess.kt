package dev.deepseek.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.deepseek.mobile.harness.HarnessCoordinator
import dev.deepseek.mobile.runtime.SandboxManager

@Composable
internal fun SandboxSearchField(
    query: String,
    change: (String) -> Unit,
    hint: String = "搜索 Sandbox",
) {
    Row(
        Modifier.fillMaxWidth()
            .clip(ContinuousShape(16.dp))
            .background(DshaSurface.inset)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.Search, null, Modifier.size(20.dp), tint = WhaleMuted)
        BasicTextField(
            query,
            change,
            Modifier.weight(1f).padding(start = 10.dp),
            singleLine = true,
            textStyle = PrismTypography.body.copy(color = WhaleInk),
            cursorBrush = SolidColor(WhaleBlue),
            decorationBox = { field ->
                Box {
                    if (query.isBlank())
                        Text(hint, color = WhaleMuted, style = PrismTypography.body)
                    field()
                }
            },
        )
    }
}

@Composable
internal fun SandboxAccessPane(conversation: Conversation) {
    val context = LocalContext.current
    val manager = remember(context) { SandboxManager.get(context) }
    val records by manager.records.collectAsState()
    val states by HarnessCoordinator.get(context).runtimes.states.collectAsState()
    val navigate = LocalSettingsNavigation.current
    var query by remember(conversation.id) { mutableStateOf("") }
    val visible = records.filter {
        !it.deleting && (query.isBlank() || it.name.contains(query, true))
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding =
            PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = LocalWorkspaceTopInset.current + 16.dp,
                bottom = 24.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Sandbox 访问", style = PrismTypography.headline) }
        item { SandboxSearchField(query, { query = it }) }
        item {
            Text(
                "只为当前会话授权。独立会话与项目会话都可以选择多个环境；关闭后会停止该环境中的 Agent 操作。",
                style = PrismTypography.footnote,
                color = WhaleMuted,
            )
        }
        item {
            SettingsGroup {
                ToggleSetting(
                    "Only Sandbox",
                    "Agent 仅能使用已授权的 Sandbox，不能回退到手机环境",
                    conversation.sandboxEnabled,
                ) { enabled ->
                    if (
                        enabled &&
                            records.none { it.allows(conversation.id, conversation.projectId) }
                    )
                        AgentService.current?.toast("请先开启至少一个 Sandbox 的访问权限")
                    else {
                        conversation.sandboxEnabled = enabled
                        AssistantState.persist()
                    }
                }
                SettingsLink(AppIcons.TerminalBox, "磁盘挂载", "分别设置实例和所有 Sandbox 的磁盘权限") {
                    WorkspaceState.open(WorkspaceTab.SandboxDisks())
                }
            }
        }
        items(visible, key = { it.id }) { record ->
            SettingsGroup {
                ToggleSetting(
                    record.name,
                    record.preset.profile.title +
                        " · " +
                        sandboxPhase(states[record.scope.key]?.phase),
                    record.allows(conversation.id, conversation.projectId),
                ) {
                    manager.allowSession(record.id, conversation.id, it)
                    if (it && conversation.sandboxId.isBlank()) {
                        conversation.sandboxId = record.id
                        AssistantState.persist()
                    }
                }
                SettingsLink(AppIcons.Terminal, "打开用户终端", "${record.name} · Sandbox 内 root") {
                    WorkspaceState.openUserTerminal(record.id)
                }
                SettingsLink(AppIcons.FolderOpen, "磁盘挂载", "设置 ${record.name} 可以使用的数据磁盘") {
                    WorkspaceState.open(WorkspaceTab.SandboxDisks(record.id))
                }
            }
        }
        if (visible.isEmpty())
            item {
                Text(
                    if (query.isBlank()) "尚未创建 Sandbox" else "没有匹配的 Sandbox",
                    color = WhaleMuted,
                    style = PrismTypography.body,
                )
            }
        item {
            SettingsGroup {
                SettingsLink(AppIcons.TerminalBox, "管理 Sandbox", "创建环境、磁盘和调整设置") {
                    navigate("sandbox")
                }
            }
        }
    }
}
