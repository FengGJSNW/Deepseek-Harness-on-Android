package dev.deepseek.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun ProjectNameDialog(
    project: WorkProject? = null,
    close: () -> Unit,
    created: (WorkProject) -> Unit = {},
) {
    var title by remember { mutableStateOf(project?.title.orEmpty()) }
    WhaleSheet(if (project == null) "新建项目" else "项目名称", close = close) {
        OutlinedTextField(
            title,
            { title = it },
            label = { Text("项目名称") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = ContinuousShape(14.dp),
            visualTransformation = UprightSymbols.transformation,
        )
        DshaButton(
            onClick = {
                runCatching {
                    if (project == null) created(AssistantState.addProject(title))
                    else {
                        project.title = title.trim().take(60)
                        AssistantState.persist()
                    }
                    close()
                }
                    .onFailure { AssistantState.error = it.message.orEmpty() }
            },
            enabled = title.isNotBlank(),
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            shape = ContinuousShape(14.dp),
        ) {
            Text("保存")
        }
    }
}

@Composable
internal fun ProjectActions(
    project: WorkProject,
    expanded: Boolean,
    close: () -> Unit,
    openConversation: () -> Unit,
    anchor: @Composable () -> Unit,
) {
    var page by remember(project.id) { mutableStateOf("") }
    val finish = {
        page = ""
        close()
    }
    LiquidMenuAnchor(
        expanded && page.isEmpty(),
        close,
        placement = MenuPlacement.SelectedRow,
        toggleAnchor = true,
        tag = "project-actions-${project.id}",
        menuTitle = project.title,
        anchor = anchor,
    ) {
        val pick = LocalAfterMenuReturn.current
        GlassMenuRow(AppIcons.Chat, "新建会话") {
            pick {
                AgentService.current?.newSession(project.id)
                openConversation()
            }
        }
        GlassMenuRow(AppIcons.Description, "项目共享记忆") {
            pick { page = "memory" }
        }
        GlassMenuRow(AppIcons.Edit, "项目名称") {
            pick { page = "rename" }
        }
        GlassMenuDivider()
        GlassMenuRow(AppIcons.Close, "删除项目", tint = DshaSurface.danger) {
            pick { page = "delete" }
        }
    }
    when (page) {
        "rename" -> ProjectNameDialog(project, finish)
        "memory" -> MemoryEditor("project", project.id, finish)
        "delete" ->
            WhaleSheet("删除项目？", "会话将移至最近列表，项目记忆保留在本机归档中。", finish) {
                DshaButton(
                    onClick = {
                        runCatching {
                            AssistantState.removeProject(project.id)
                            finish()
                        }
                            .onFailure { AssistantState.error = it.message.orEmpty() }
                    },
                ) {
                    Text("删除项目")
                }
            }
    }
}
