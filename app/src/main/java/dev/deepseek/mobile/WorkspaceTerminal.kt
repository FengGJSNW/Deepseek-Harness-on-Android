package dev.deepseek.mobile

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.deepseek.mobile.harness.PermissionContext
import dev.deepseek.mobile.terminal.TerminalLevel
import dev.deepseek.mobile.terminal.TerminalRun
import kotlinx.coroutines.*

@Composable
internal fun WorkspaceTerminal(conversation: Conversation) {
    if (LocalTerminalState.sessionId == conversation.id) {
        LocalTerminalPane(conversation)
        return
    }
    val level = TerminalLevel.of(conversation.terminalLevel)
    val enabled = conversation.terminalEnabled && conversation.access !in setOf("none", "read")
    val manager = AgentService.current?.terminal
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val runs = remember { mutableStateListOf<TerminalRun>() }
    var approval by remember { mutableStateOf<Pair<String, CompletableDeferred<Boolean>>?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var command by remember { mutableStateOf("") }
    var directory by remember(level) { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(runs.size) {
        if (runs.isNotEmpty()) list.scrollToItem(runs.lastIndex)
    }
    val active = job != null
    Column(Modifier.fillMaxSize().background(Color(0xFFFAFAF9)).testTag("workspace-terminal")) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("终端 · ${level.title}", fontSize = 16.sp)
                Text(
                    if (enabled) manager?.workspace(level, conversation.id).orEmpty()
                    else "当前会话未允许终端执行",
                    fontSize = 11.sp,
                    color = WhaleMuted,
                )
            }
            if (active) TextButton(onClick = { job?.cancel() }) { Text("停止") }
        }
        HorizontalDivider(color = WhaleLine)
        if (error.isNotBlank())
            Text(error, Modifier.padding(12.dp), color = Color(0xFFA7443F), fontSize = 12.sp)
        SelectionContainer(Modifier.weight(1f)) {
            LazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (runs.isEmpty())
                    item {
                        Text(
                            "命令在会话当前的终端级别执行。可以为每条命令指定工作目录。",
                            fontSize = 13.sp,
                            color = WhaleMuted,
                        )
                    }
                items(runs, key = { it.id }) { run ->
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            "$ " + run.command,
                            fontFamily = DshaFonts.mono,
                            fontSize = 13.sp,
                            color = WhaleInk,
                        )
                        if (run.output.isNotEmpty())
                            Text(run.output, fontFamily = DshaFonts.mono, fontSize = 12.sp)
                        if (run.stderr.isNotEmpty())
                            Text(
                                run.stderr,
                                fontFamily = DshaFonts.mono,
                                fontSize = 12.sp,
                                color = Color(0xFFA7443F),
                            )
                        val status =
                            run.exitCode?.let { "退出码 $it" }
                                ?: when (run.status) {
                                    "denied" -> "命令未执行"
                                    "cancelled" -> "已停止"
                                    "failed" -> "执行失败"
                                    "waiting" -> "等待确认"
                                    else -> "正在处理…"
                                }
                        Text(
                            listOf(
                                    status,
                                    run.reason.takeIf { it.isNotBlank() },
                                    "输出已截断".takeIf { run.truncated },
                                )
                                .filterNotNull()
                                .joinToString(" · "),
                            fontSize = 11.sp,
                            color = WhaleMuted,
                        )
                    }
                }
            }
        }
        HorizontalDivider(color = WhaleLine)
        OutlinedTextField(
            directory,
            { directory = it.take(1024) },
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            enabled = enabled && !active,
            singleLine = true,
            label = { Text("工作目录（可选）") },
        )
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                command,
                { command = it.take(16_384) },
                Modifier.weight(1f),
                enabled = enabled && !active,
                maxLines = 3,
                label = { Text("命令") },
                textStyle = LocalTextStyle.current.copy(fontFamily = DshaFonts.mono),
            )
            TextButton(
                enabled = enabled && !active && command.isNotBlank() && manager != null,
                onClick = {
                    val terminal = manager ?: return@TextButton
                    val input = command
                    val cwd = directory
                    focus.clearFocus()
                    error = ""
                    command = ""
                    job =
                        scope
                            .launch(start = CoroutineStart.LAZY) {
                                try {
                                    val snapshot =
                                        withContext(Dispatchers.IO) {
                                            terminal.config.load(level.key)
                                        }
                                    val request =
                                        PhoneTask(
                                            sessionId = conversation.id,
                                            projectId = conversation.projectId,
                                            text = "用户终端",
                                            images = emptyList(),
                                            model = conversation.model,
                                            effort = "low",
                                            access = conversation.access,
                                            phoneTools = false,
                                            fileTools = conversation.fileTools,
                                            workspace = conversation.workspace,
                                            terminalEnabled = conversation.terminalEnabled,
                                            terminalConfig = snapshot.json().toString(),
                                        )
                                    val result =
                                        terminal.executeManually(
                                            request,
                                            input,
                                            cwd,
                                            onRun = {
                                                if (runs.size >= 32) runs.removeAt(0)
                                                runs.add(it)
                                            },
                                        ) { reason ->
                                            val decision = CompletableDeferred<Boolean>()
                                            approval = reason to decision
                                            try {
                                                decision.await()
                                            } finally {
                                                approval = null
                                            }
                                        }
                                    val treeId = result.optString("worktreeId")
                                    if (treeId.isNotBlank())
                                        withContext(NonCancellable + Dispatchers.IO) {
                                            val harness = terminal.harness
                                            val tree =
                                                harness.workspaces.load(
                                                    harness.runtimes.scope(request),
                                                    treeId,
                                                )
                                            val result =
                                                harness.runtimes.resolveWorktree(
                                                    tree,
                                                    true,
                                                    PermissionContext(
                                                        sessionId = request.sessionId,
                                                        projectId = request.projectId,
                                                        actor = "user",
                                                    ),
                                                ) {
                                                    true
                                                }
                                            if (!result.getBoolean("merged"))
                                                withContext(Dispatchers.Main) {
                                                    error = "终端工作区的改动已保留，请在运行环境中查看"
                                                }
                                        }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    error = e.message ?: "命令未执行"
                                } finally {
                                    job = null
                                }
                            }
                            .also { it.start() }
                },
            ) {
                Text("运行")
            }
        }
    }
    approval
        ?.takeIf { WorkspaceState.visible && WorkspaceState.selected == WorkspaceTab.Terminal }
        ?.let { (reason, decision) ->
            AlertDialog(
                onDismissRequest = { decision.complete(false) },
                title = { Text("允许这条终端命令？") },
                text = { Text(reason.ifBlank { "当前终端规则要求人工确认。" }) },
                confirmButton = {
                    TextButton(onClick = { decision.complete(true) }) { Text("执行一次") }
                },
                dismissButton = {
                    TextButton(onClick = { decision.complete(false) }) { Text("取消") }
                },
            )
        }
}
