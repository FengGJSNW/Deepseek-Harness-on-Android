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
        Button(
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
internal fun ProjectActions(project: WorkProject, close: () -> Unit, openConversation: () -> Unit) {
    var page by remember { mutableStateOf("menu") }
    when (page) {
        "rename" -> ProjectNameDialog(project, close)
        "memory" -> MemoryEditor("project", project.id, close)
        "delete" ->
            WhaleSheet("删除项目？", "会话将移至最近列表，项目记忆保留在本机归档中。", close) {
                Button(
                    onClick = {
                        runCatching {
                            AssistantState.removeProject(project.id)
                            close()
                        }
                            .onFailure { AssistantState.error = it.message.orEmpty() }
                    },
                ) {
                    Text("删除项目")
                }
            }
        else ->
            WhaleSheet(project.title, close = close) {
                OptionRow(AppIcons.Chat, "新建会话", "", false) {
                    AgentService.current?.newSession(project.id)
                    close()
                    openConversation()
                }
                OptionRow(AppIcons.Description, "项目共享记忆", "workplace_memory.md", false) {
                    page = "memory"
                }
                OptionRow(AppIcons.Edit, "项目名称", "", false) { page = "rename" }
                OptionRow(AppIcons.Close, "删除项目", "", false) { page = "delete" }
            }
    }
}

@Composable
internal fun MoveConversationDialog(c: Conversation, close: () -> Unit) {
    WhaleSheet("移动会话", close = close) {
        fun move(id: String) {
            runCatching {
                AssistantState.moveSession(c.id, id)
                close()
            }
                .onFailure { AssistantState.error = it.message.orEmpty() }
        }
        OptionRow(AppIcons.Chat, "独立会话", "不属于任何项目", c.projectId.isEmpty()) { move("") }
        AssistantState.projects.forEach { p ->
            OptionRow(AppIcons.FolderOpen, p.title, "", c.projectId == p.id) { move(p.id) }
        }
    }
}
