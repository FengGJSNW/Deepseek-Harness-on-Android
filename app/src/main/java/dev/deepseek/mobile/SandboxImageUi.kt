package dev.deepseek.mobile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.deepseek.mobile.runtime.RuntimeImages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun SandboxImageImport() {
    val context = LocalContext.current
    val images = remember(context) { RuntimeImages.get(context) }
    val available by images.availableImages.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null && !busy) {
                busy = true
                error = false
                scope.launch {
                    try {
                        val result = images.importZip(uri) { status = it }
                        status =
                            (if (result.kind == "tools") "DSHA Tool" else "DSHA Linux Lite") +
                                " 已导入，可重复使用"
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = true
                        status = e.message ?: "导入失败，请重新选择镜像 ZIP"
                    } finally {
                        busy = false
                    }
                }
            }
        }
    SectionLabel("镜像")
    Text(
        "导入一次 DSHA Linux Lite 即可重复克隆。Default 和 Custom 可以从 DSHA Tool 安装工具链。",
        color = WhaleMuted,
        style = PrismTypography.footnote,
    )
    Spacer(Modifier.height(8.dp))
    for ((kind, label) in listOf("alpine" to "DSHA Linux Lite", "tools" to "DSHA Tool")) {
        val origin by
            produceState<Pair<String, Boolean>?>(null, kind, busy) {
                value = images.importSource(kind)
            }
        SettingsStatusCard(
            AppIcons.TerminalBox,
            label,
            if (kind in available) "本机副本可用，可重复创建" else "未导入或本机副本缺失",
        )
        origin?.let { (path, exists) ->
            Text("原文件 · $path", style = PrismTypography.footnote)
            Text(
                if (exists) "能找到原文件" else "原文件已移动，或文件选择授权已失效",
                color = WhaleMuted,
                style = PrismTypography.caption,
            )
        }
    }
    DshaButton(
        enabled = !busy,
        onClick = {
            picker.launch(
                arrayOf(
                    "application/zip",
                    "application/x-zip-compressed",
                    "application/octet-stream",
                ),
            )
        },
        modifier = Modifier.testTag("sandbox-import-image"),
    ) {
        Icon(AppIcons.FolderOpen, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(if (busy) "导入中…" else "导入镜像 ZIP")
    }
    if (status.isNotBlank())
        Text(
            status,
            color = if (error) MaterialTheme.colorScheme.error else WhaleMuted,
            style = PrismTypography.footnote,
        )
}
