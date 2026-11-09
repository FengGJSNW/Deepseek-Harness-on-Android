package dev.deepseek.mobile

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.deepseek.mobile.terminal.*
import kotlinx.coroutines.*

internal fun selectTerminalLevel(conversation: Conversation, level: TerminalLevel) {
    conversation.terminalEnabled = true
    conversation.terminalLevel = level.key
    AssistantState.persist()
    val connections = AgentService.current?.terminal?.connections ?: return
    when (level) {
        TerminalLevel.SHELL -> connections.connectShell()
        TerminalLevel.ROOT,
        TerminalLevel.ROOT_GUARDED -> connections.connectRoot()
        else -> Unit
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TerminalConnectionPanel(level: TerminalLevel, connections: TerminalConnections) {
    val context = LocalContext.current
    val state = connections.state(level)
    val root = level != TerminalLevel.SHELL
    val jobs = rememberCoroutineScope()
    var probeText by remember(level) { mutableStateOf("") }
    var probing by remember(level) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 14.dp).testTag("terminal-connection")) {
        SettingsStatusCard(
            terminalIcon(level.key),
            when {
                state.ready -> "身份已验证 · UID ${state.uid}"
                state.pending -> "正在连接…"
                else -> "等待连接"
            },
            state.detail,
            tint = if (state.ready) WhaleBlue else WhaleMuted,
            announce = true,
        )
        if (state.ready && level == TerminalLevel.ROOT_GUARDED && !state.guardAvailable)
            Text(
                "本内核缺少隔离条件，受限 Root 无法执行。",
                Modifier.padding(top = 8.dp),
                fontSize = 12.sp,
                color = WhaleMuted,
            )
        if (!state.ready)
            Button(
                onClick = { if (root) connections.connectRoot() else connections.connectShell() },
                enabled = !state.pending,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text(if (root) "连接并验证 Root" else "连接并验证 Shizuku")
            }
        FlowRow {
            TextButton(onClick = { connections.refresh() }) { Text("检查连接", fontSize = 12.sp) }
            if (!root)
                TextButton(
                    onClick = {
                        runCatching {
                            val intent =
                                context.packageManager.getLaunchIntentForPackage(
                                    "moe.shizuku.privileged.api",
                                ) ?: error("请先安装 Shizuku")
                            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                            .onFailure { AgentService.current?.toast(it.message.orEmpty()) }
                    },
                ) {
                    Text("打开 Shizuku", fontSize = 12.sp)
                }
            if (state.ready || state.pending)
                TextButton(onClick = { connections.disconnect(root) }) {
                    Text("断开", fontSize = 12.sp)
                }
        }
        if (state.ready) {
            TextButton(
                onClick = {
                    probing = true
                    jobs.launch {
                        runCatching {
                            AgentService.current!!.terminal.connectionStatus(level, true)
                        }
                            .onSuccess { proof ->
                                val programs =
                                    proof
                                        .optJSONObject("identity")
                                        ?.optJSONArray("programs")
                                        ?.let { array ->
                                            (0 until array.length()).joinToString("、") {
                                                array.getString(it)
                                            }
                                        }
                                        .orEmpty()
                                probeText =
                                    "检测到程序：${programs.ifEmpty { "无" }}" +
                                        (proof.optJSONObject("probe")?.let {
                                            "\nexit ${it.optInt("exitCode")}\n${it.optString("output")}"
                                        } ?: "\n未运行命令；受限 Root 需要先满足隔离条件。")
                            }
                            .onFailure { probeText = it.message.orEmpty() }
                        probing = false
                    }
                },
                enabled = !probing,
            ) {
                Text(if (probing) "诊断中…" else "只读身份诊断", fontSize = 12.sp)
            }
        }
        if (probeText.isNotBlank())
            Text(probeText, fontSize = 11.sp, lineHeight = 17.sp, color = WhaleMuted)
        Text(
            "${state.phase}${if (state.errorCode.isNotBlank() && !state.ready) " · ${state.errorCode}" else ""}",
            fontSize = 10.sp,
            color = WhaleMuted,
        )
    }
}
