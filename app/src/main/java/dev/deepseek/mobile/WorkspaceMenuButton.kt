package dev.deepseek.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun ConversationNavigationButton(
    label: String,
    workspaceOpen: Boolean,
    progress: State<Float>,
    onNavigate: () -> Unit,
    icon: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val owner = LocalView.current
    val navigate = LocalSettingsNavigation.current
    val tracking = remember { PressMenuTracking() }
    var expanded by remember { mutableStateOf(false) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var dialog by remember { mutableStateOf("") }
    val conversation = AssistantState.current
    val project = AssistantState.projects.firstOrNull { it.id == conversation.projectId }
    LaunchedEffect(workspaceOpen, conversation.id) {
        expanded = false
        tracking.cancel()
    }
    fun toggleMenu(): Boolean {
        val showing = !expanded
        expanded = showing
        if (ExperienceSettings.hapticsEnabled)
            owner.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        return showing
    }
    LiquidMenuAnchor(
        expanded,
        {
            expanded = false
            tracking.cancel()
        },
        placement = MenuPlacement.Below,
        width = 272.dp,
        toggleAnchor = true,
        bare = false,
        tag = "workspace-quick-menu",
        anchor = {
            DshaIconButton(
                label,
                { if (expanded) expanded = false else onNavigate() },
                size = 52.dp,
                glass = true,
                modifier =
                    Modifier.onGloballyPositioned {
                            val screen = IntArray(2)
                            val window = IntArray(2)
                            owner.getLocationOnScreen(screen)
                            owner.getLocationInWindow(window)
                            origin =
                                it.boundsInWindow().topLeft +
                                    Offset(
                                        (screen[0] - window[0]).toFloat(),
                                        (screen[1] - window[1]).toFloat(),
                                    )
                        }
                        .pressMenuGesture(tracking, { origin }, !workspaceOpen, ::toggleMenu)
                        .semantics {
                            onLongClick("快捷菜单") {
                                toggleMenu()
                                true
                            }
                        },
            ) {
                icon()
            }
        },
    ) {
        val pick = LocalAfterMenuReturn.current
        Text(
            "工具箱",
            style = PrismTypography.footnote,
            color = WhaleMuted,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
        GlassMenuDivider()
        PressMenuRow(tracking, "flclash", AppIcons.Globe, "FlClash") {
            pick { dev.deepseek.mobile.proxy.FlClashRuntime.get(context).open() }
        }
        PressMenuRow(tracking, "sandbox", AppIcons.TerminalBox, "Sandbox") {
            pick { navigate("sandbox") }
        }
        if (AssistantState.appMode == "work") {
            Text(
                "会话",
                style = PrismTypography.footnote,
                color = WhaleMuted,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
            GlassMenuDivider()
            PressMenuRow(tracking, "rename-chat", AppIcons.Edit, "修改会话标题") {
                pick { dialog = "chat" }
            }
            if (project != null) {
                PressMenuRow(tracking, "rename-project", AppIcons.FolderOpen, "修改项目名称") {
                    pick { dialog = "project" }
                }
                PressMenuRow(tracking, "memory", AppIcons.Description, "查看项目记忆") {
                    pick { dialog = "memory" }
                }
            }
        }
    }
    when (dialog) {
        "project" -> project?.let { ProjectNameDialog(it, { dialog = "" }) }
        "memory" -> project?.let { MemoryEditor("project", it.id) { dialog = "" } }
        "chat" -> {
            var title by remember(conversation.id) { mutableStateOf(conversation.title) }
            WhaleSheet("修改会话标题", close = { dialog = "" }) {
                OutlinedTextField(
                    title,
                    { title = it.take(100) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                DshaButton(
                    onClick = {
                        conversation.title = title.trim()
                        AssistantState.persist()
                        dialog = ""
                    },
                    enabled = title.isNotBlank(),
                ) {
                    Text("保存")
                }
            }
        }
    }
}
