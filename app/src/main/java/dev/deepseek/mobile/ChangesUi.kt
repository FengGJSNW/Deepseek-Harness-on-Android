package dev.deepseek.mobile

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.deepseek.mobile.harness.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ChangesCard(task: String) {
    val count = ChangeState.changes.count { it.taskId == task }
    SettingsLink(AppIcons.Diff, if (count > 0) "$count 个文件产生改动" else "查看任务工作副本", "查看差异、合并与丢弃") {
        ChangeState.taskId = task
        ChangeState.show = true
    }
}

@Composable
internal fun ChangesPanel() {
    if (ChangeState.show)
        WhaleSheet(
            "文件改动",
            "查看文件修改，确认后合并到项目。",
            {
                ChangeState.show = false
                ChangeState.taskId = ""
            },
        ) {
            val values =
                ChangeState.changes.filter {
                    it.sessionId == AssistantState.selectedId &&
                        (ChangeState.taskId.isBlank() || it.taskId == ChangeState.taskId)
                }
            PendingWorktreeRows(ChangeState.taskId)
            if (values.isEmpty()) Text("还没有记录到文件改动。", fontSize = 13.sp, color = WhaleMuted)
            values.forEach { change ->
                SettingsLink(AppIcons.Diff, change.path, change.kind) {
                    ChangeState.selected = change
                }
            }
        }
    val change = ChangeState.selected
    if (change != null) {
        var mode by remember(change.id) { mutableStateOf("diff") }
        WhaleSheet(change.path, change.kind, { ChangeState.selected = null }) {
            Row {
                listOf("diff" to "差异", "before" to "修改前", "after" to "修改后").forEach { (id, title) ->
                    TextButton(onClick = { mode = id }) {
                        if (id == mode)
                            Icon(AppIcons.Check, null, Modifier.padding(end = 4.dp).size(13.dp))
                        Text(title, fontSize = 12.sp)
                    }
                }
            }
            if (change.binary)
                Text("这是二进制文件或超过 64KB 的文本，已记录内容指纹变化。", fontSize = 13.sp, color = WhaleMuted)
            else if (mode != "diff")
                SelectionContainer {
                    Text(
                        (if (mode == "before") change.before else change.after) ?: "（文件不存在）",
                        fontFamily = DshaFonts.mono,
                        fontSize = 12.sp,
                    )
                }
            else {
                val lines =
                    remember(change.id) {
                        lineDiff(change.before.orEmpty(), change.after.orEmpty())
                    }
                SelectionContainer {
                    Column(Modifier.horizontalScroll(rememberScrollState())) {
                        lines.forEach { (kind, text) ->
                            Text(
                                "$kind $text",
                                Modifier.fillMaxWidth()
                                    .background(
                                        when (kind) {
                                            '+' -> Color(0xFFE4F7EB)
                                            '-' -> Color(0xFFFFECEC)
                                            else -> Color.Transparent
                                        },
                                    )
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                color =
                                    when (kind) {
                                        '+' -> Color(0xFF237447)
                                        '-' -> Color(0xFFA23A3A)
                                        else -> WhaleMuted
                                    },
                                fontSize = 11.sp,
                                fontFamily = DshaFonts.mono,
                                softWrap = false,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PendingWorktreeRows(taskId: String) {
    val context = LocalContext.current
    val harness = remember { HarnessCoordinator.get(context) }
    val conversation = AssistantState.current
    val scope =
        if (conversation.projectId.isBlank()) WorkspaceScope("session", conversation.id)
        else WorkspaceScope("project", conversation.projectId)
    var revision by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<Worktree?>(null) }
    val trees by
        produceState(emptyList<Worktree>(), scope, taskId, revision) {
            value =
                withContext(Dispatchers.IO) {
                    harness.workspaces.tasks(scope, taskId.takeIf { it.isNotBlank() }).filter {
                        it.state in setOf("active", "ready", "conflicted") &&
                            harness.workspaces.review(it).isNotEmpty()
                    }
                }
        }
    trees.forEach { tree ->
        SettingsLink(
            AppIcons.FolderOpen,
            "工作副本 ${tree.taskId.take(8)}",
            if (tree.state == "conflicted") "发生冲突，未覆盖共享工作区" else "审阅后合并到工作区",
        ) {
            selected = tree
        }
    }
    selected?.let { tree ->
        WorktreeReview(tree) {
            selected = null
            revision++
        }
    }
}
