package dev.deepseek.mobile

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import dev.deepseek.mobile.runtime.RuntimeDownload

@Composable
internal fun SandboxDownloadPrompt() {
    val prompt by RuntimeDownload.prompt.collectAsState()
    val error by RuntimeDownload.error.collectAsState()
    prompt?.let { request ->
        WhaleSheet(
            "尝试代理加速？",
            "Sandbox 下载较慢，检测到 FlClash 已有配置。是否开启代理尝试加速？",
            { request.answer.complete(false) },
        ) {
            DshaButton(onClick = { request.answer.complete(true) }) { Text("开启代理并继续下载") }
            DshaTextButton(onClick = { request.answer.complete(false) }) { Text("继续直连") }
        }
    }
    if (error.isNotBlank())
        WhaleSheet("代理加速未完成", error, RuntimeDownload::dismissError) {
            DshaTextButton(onClick = RuntimeDownload::dismissError) { Text("知道了") }
        }
}
