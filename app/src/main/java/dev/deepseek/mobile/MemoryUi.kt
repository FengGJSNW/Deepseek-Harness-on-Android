package dev.deepseek.mobile

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*

@Composable
internal fun MemoryEditor(scopeName: String, projectId: String = "", close: () -> Unit) {
    WhaleSheet(
        when (scopeName) {
            "global" -> "全局记忆"
            "session" -> "会话记忆"
            "persona" -> "人格记忆"
            else -> "项目共享记忆"
        },
        close = close,
    ) {
        val context = LocalContext.current
        val store = remember(context) { MemoryStore(context) }
        val jobs = rememberCoroutineScope()
        var snapshot by remember(scopeName, projectId) { mutableStateOf<MemorySnapshot?>(null) }
        var text by remember(scopeName, projectId) { mutableStateOf("") }
        var busy by remember(scopeName, projectId) { mutableStateOf(false) }
        var message by remember(scopeName, projectId) { mutableStateOf("") }
        LaunchedEffect(scopeName, projectId) {
            runCatching { withContext(Dispatchers.IO) { store.read(scopeName, projectId) } }
                .onSuccess {
                    snapshot = it
                    text = it.text
                }
                .onFailure { message = it.message.orEmpty() }
        }
        Text(
            if (scopeName == "global") "memory.md · 所有 Work 会话共享"
            else
                when (scopeName) {
                    "session" -> "session_memory.md · 仅当前会话"
                    "persona" -> "persona_memory.md · 所有会话的角色偏好"
                    else -> "workplace_memory.md · 此项目的会话共享"
                },
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = WhaleMuted,
        )
        OutlinedTextField(
            text,
            { text = it },
            modifier =
                Modifier.fillMaxWidth()
                    .padding(top = 12.dp)
                    .heightIn(min = 150.dp, max = 300.dp)
                    .testTag("memory-editor-input"),
            enabled = snapshot != null && !busy,
            shape = ContinuousShape(14.dp),
            placeholder = { Text("记下偏好、项目背景与需要长期保留的事实。") },
            textStyle =
                MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 20.sp),
            visualTransformation = UprightSymbols.transformation,
        )
        Text(
            "${text.toByteArray().size} / 32768 字节",
            fontSize = 10.sp,
            color = WhaleMuted,
            modifier = Modifier.padding(top = 7.dp),
        )
        if (snapshot == null && message.isBlank())
            Text("正在读取记忆…", fontSize = 12.sp, color = WhaleMuted)
        if (message.isNotBlank()) Text(message, fontSize = 12.sp, color = WhaleMuted)
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = { MainActivity.current?.importTerminalConfig { text = it } },
                enabled = !busy,
            ) {
                Text("导入")
            }
            TextButton(
                onClick = {
                    MainActivity.current?.exportTerminalConfig(
                        snapshot?.filename ?: "memory.md",
                        text,
                    )
                },
            ) {
                Text("导出")
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = {
                    val before = snapshot ?: return@Button
                    busy = true
                    jobs.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                store.write(scopeName, projectId, text, before.sha256)
                            }
                        }
                            .onSuccess {
                                snapshot = it
                                message = "已保存，之后的新消息会读取最新记忆"
                            }
                            .onFailure { message = it.message.orEmpty() }
                        busy = false
                    }
                },
                enabled =
                    snapshot != null && !busy && text.toByteArray().size <= MemoryStore.MAX_BYTES,
                shape = ContinuousShape(12.dp),
            ) {
                Text(if (busy) "保存中" else "保存")
            }
        }
    }
}
