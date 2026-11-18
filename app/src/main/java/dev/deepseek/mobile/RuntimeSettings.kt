package dev.deepseek.mobile

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.deepseek.mobile.harness.*
import dev.deepseek.mobile.runtime.*
import kotlinx.coroutines.*

@Composable
internal fun WorktreeReview(tree: Worktree, close: () -> Unit) {
    val context = LocalContext.current
    val harness = remember { HarnessCoordinator.get(context) }
    var changes by remember(tree.taskId) { mutableStateOf(emptyList<WorkspaceChange>()) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val jobs = rememberCoroutineScope()
    LaunchedEffect(tree.taskId) {
        changes = withContext(Dispatchers.IO) { harness.workspaces.review(tree) }
    }
    WhaleSheet("${changes.size} 个文件改动", "合并前会再次检查当前版本。发生冲突时拒绝覆盖，原工作区会保留。", close) {
        Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
            changes.forEach { change ->
                SettingsLink(
                    AppIcons.Description,
                    change.path,
                    if (change.before == null) "新增" else if (change.after == null) "删除" else "修改",
                ) {
                    jobs.launch {
                        withContext(Dispatchers.IO) {
                            fun read(version: dev.deepseek.mobile.harness.FileVersion?): String? =
                                version?.let {
                                    val bytes = harness.workspaces.content(it)
                                    if (bytes.size > 65536 || bytes.any { b -> b == 0.toByte() })
                                        null
                                    else
                                        runCatching {
                                            Charsets.UTF_8.newDecoder()
                                                .decode(java.nio.ByteBuffer.wrap(bytes))
                                                .toString()
                                        }
                                            .getOrNull()
                                }
                            val before = read(change.before)
                            val after = read(change.after)
                            ChangeState.selected =
                                WorkChange(
                                    java.util.UUID.randomUUID().toString(),
                                    tree.taskId,
                                    AssistantState.selectedId,
                                    change.path,
                                    before,
                                    after,
                                    "工作副本",
                                    (change.before != null && before == null) ||
                                        (change.after != null && after == null),
                                )
                        }
                    }
                }
            }
        }
        if (message.isNotBlank())
            Text(
                message,
                fontSize = 12.sp,
                color = Color(0xFFB17A2C),
                modifier = Modifier.padding(vertical = 10.dp),
            )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            DshaTextButton(
                onClick = {
                    busy = true
                    jobs.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                harness.runtimes.resolveWorktree(
                                    tree,
                                    false,
                                    PermissionContext(
                                        sessionId = AssistantState.selectedId,
                                        projectId = AssistantState.current.projectId,
                                        actor = "user",
                                    ),
                                ) {
                                    true
                                }
                            }
                        }
                            .onSuccess { close() }
                            .onFailure { message = it.message.orEmpty() }
                        busy = false
                    }
                },
                enabled = !busy,
            ) {
                Text("丢弃副本")
            }
            DshaTextButton(
                onClick = {
                    busy = true
                    jobs.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                harness.runtimes.resolveWorktree(
                                    tree,
                                    true,
                                    PermissionContext(
                                        sessionId = AssistantState.selectedId,
                                        projectId = AssistantState.current.projectId,
                                        actor = "user",
                                    ),
                                ) {
                                    true
                                }
                            }
                        }
                            .onSuccess {
                                if (it.getBoolean("merged")) close()
                                else
                                    message =
                                        "以下文件已被其他任务修改，请基于最新工作区重新合并：\n" +
                                            (0 until it.getJSONArray("conflicts").length())
                                                .joinToString("\n") { i ->
                                                    it.getJSONArray("conflicts").getString(i)
                                                }
                            }
                            .onFailure { message = it.message.orEmpty() }
                        busy = false
                    }
                },
                enabled = !busy && changes.isNotEmpty(),
                modifier = Modifier.testTag("worktree-merge"),
            ) {
                Text("合并到工作区")
            }
        }
    }
}

@Composable
internal fun InstructionEditor(scopeName: String, projectId: String, close: () -> Unit) {
    val context = LocalContext.current
    val manager = remember { HarnessCoordinator.get(context).contexts }
    var text by remember { mutableStateOf("") }
    var hash by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    val jobs = rememberCoroutineScope()
    LaunchedEffect(scopeName, projectId) {
        val value = withContext(Dispatchers.IO) { manager.instruction(scopeName, projectId) }
        text = value.getString("text")
        hash = value.getString("sha256")
    }
    WhaleSheet(
        if (scopeName == "project") "项目工作指令"
        else if (scopeName == "personality") "人格指令" else "全局工作指令",
        "由你维护，Agent 只读。项目指令细化全局指令；记忆只保存偏好与事实。",
        close,
    ) {
        OutlinedTextField(
            text,
            { text = it },
            Modifier.fillMaxWidth()
                .heightIn(min = 180.dp, max = 380.dp)
                .testTag("instruction-editor"),
            textStyle =
                MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 21.sp),
            shape = ContinuousShape(14.dp),
            visualTransformation = UprightSymbols.transformation,
        )
        if (message.isNotBlank()) Text(message, fontSize = 12.sp, color = WhaleMuted)
        DshaTextButton(
            onClick = {
                jobs.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            manager.saveUserInstruction(scopeName, projectId, text, hash)
                        }
                    }
                        .onSuccess { close() }
                        .onFailure { message = it.message.orEmpty() }
                }
            },
            modifier = Modifier.align(Alignment.End),
        ) {
            Text("保存指令")
        }
    }
}
