package dev.deepseek.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.deepseek.mobile.harness.*
import kotlinx.coroutines.*
import org.json.JSONObject

@Composable
internal fun ToolchainRequests(close: () -> Unit) {
    val context = LocalContext.current
    val manager = remember { HarnessCoordinator.get(context).packages }
    val jobs = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    val requests = remember(revision) { manager.requests() }
    var selected by remember { mutableStateOf<JSONObject?>(null) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    WhaleSheet("共享工具链安装申请", "此处安装影响未来新建或重置的 Light 环境；当前项目内的 pip、npm、apk 仍在各自 Runtime 中。", close) {
        Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
            if (requests.isEmpty()) SettingsStatusCard(AppIcons.Terminal, "暂无安装申请")
            requests.forEach { item ->
                SettingsLink(
                    AppIcons.TerminalBox,
                    item.getJSONArray("packages").let { a ->
                        (0 until a.length()).joinToString("、") { a.getString(it) }
                    },
                    item.optString("status"),
                ) {
                    selected = item
                }
            }
        }
        if (status.isNotBlank())
            Text(status, fontSize = 12.sp, lineHeight = 19.sp, color = WhaleMuted)
        selected?.let { item ->
            Text(
                item.getJSONArray("packages").toString(),
                fontFamily = DshaFonts.mono,
                fontSize = 12.sp,
            )
            if (item.optString("status") in setOf("pending", "interrupted", "failed"))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    DshaTextButton(
                        onClick = {
                            manager.deny(item.getString("id"))
                            revision++
                            selected = null
                        },
                        enabled = !busy,
                    ) {
                        Text("拒绝")
                    }
                    DshaTextButton(
                        onClick = {
                            busy = true
                            status = "下载、验证并发布工具链…"
                            jobs.launch {
                                runCatching {
                                    manager.installAfterUserApproval(item.getString("id"))
                                }
                                    .onSuccess {
                                        status = "已安装；新建或重置 Light 环境后使用"
                                        selected = null
                                    }
                                    .onFailure { status = it.message.orEmpty() }
                                busy = false
                                revision++
                            }
                        },
                        enabled = !busy,
                    ) {
                        Text("允许安装")
                    }
                }
            else Text(item.optString("error"), fontSize = 12.sp, color = WhaleMuted)
        }
    }
}

@Composable
internal fun ApprovalHistory(close: () -> Unit) {
    val context = LocalContext.current
    val items = remember {
        HarnessCoordinator.get(context).approvals.list().filter {
            it.optString("sessionId") == AssistantState.selectedId
        }
    }
    var selected by remember { mutableStateOf<JSONObject?>(null) }
    WhaleSheet("本会话的授权记录", "已中断的请求不会恢复执行。这里只查看记录，重新操作必须重新批准。", close) {
        Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
            if (items.isEmpty()) Text("暂无记录", color = WhaleMuted, fontSize = 13.sp)
            items.forEach { item ->
                SettingsLink(AppIcons.Shield, item.optString("reason"), item.optString("status")) {
                    selected = item
                }
            }
        }
        selected?.let {
            Text(
                it.optString("detail"),
                fontSize = 12.sp,
                lineHeight = 20.sp,
                modifier = Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState()),
            )
        }
    }
}
