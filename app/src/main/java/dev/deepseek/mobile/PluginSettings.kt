package dev.deepseek.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.deepseek.mobile.harness.HarnessCoordinator
import dev.deepseek.mobile.harness.InstalledPlugin
import dev.deepseek.mobile.harness.PluginManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
internal fun PluginSettings(conversation: Conversation, onCreate: () -> Unit) {
    val context = LocalContext.current
    val manager = remember(context) { HarnessCoordinator.get(context).plugins }
    val revision by manager.store.revision.collectAsState()
    val inventory by
        produceState<Result<List<InstalledPlugin>>?>(null, manager, revision) {
            value = withContext(Dispatchers.IO) { runCatching { manager.store.list() } }
        }
    val jobs = rememberCoroutineScope()
    var opened by remember { mutableStateOf("") }
    var removal by remember { mutableStateOf<InstalledPlugin?>(null) }
    var error by remember { mutableStateOf("") }
    fun change(operation: () -> Unit) {
        jobs.launch {
            error = ""
            try {
                withContext(Dispatchers.IO) { operation() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message.orEmpty()
            }
        }
    }
    SettingsLink(AppIcons.Spark, "让 Agent 创建插件", "开启 Creator 会话，描述所需工具") { onCreate() }
    Text(
        "插件安装后可在所有工作会话使用。工具在离线沙箱中运行，只接收本次调用的参数。",
        fontSize = 12.sp,
        lineHeight = 19.sp,
        color = WhaleMuted,
    )
    val plugins = inventory?.getOrNull().orEmpty()
    if (inventory?.isSuccess == true && plugins.isEmpty())
        Text(
            "还没有已安装的插件",
            color = WhaleMuted,
            fontSize = 13.sp,
            modifier = Modifier.padding(vertical = 14.dp),
        )
    plugins
        .sortedBy { it.bundle.name }
        .forEach { plugin ->
            key(plugin.bundle.id) {
                Surface(color = Color(0xFFF5F5F2), shape = ContinuousShape(18.dp)) {
                    Column(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(plugin.bundle.name, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text(
                            "${plugin.bundle.version} · ${plugin.bundle.tools.size} 个工具 · " +
                                if (plugin.enabled) "已启用" else "已停用",
                            fontSize = 12.sp,
                            color = WhaleMuted,
                        )
                        Text(plugin.bundle.description, fontSize = 13.sp, maxLines = 3)
                        Row {
                            TextButton({ opened = plugin.bundle.id }) { Text("详情") }
                            TextButton(
                                {
                                    change { manager.store.setEnabled(plugin, !plugin.enabled) }
                                },
                            ) {
                                Text(if (plugin.enabled) "停用" else "启用")
                            }
                            TextButton({ removal = plugin }) { Text("卸载", color = WhaleMuted) }
                        }
                    }
                }
            }
        }
    val message = error.ifBlank { inventory?.exceptionOrNull()?.message.orEmpty() }
    if (message.isNotBlank())
        Text(message, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
    plugins
        .firstOrNull { it.bundle.id == opened }
        ?.let { plugin ->
            key(plugin.revision) { PluginDetail(plugin, conversation, manager) { opened = "" } }
        }
    removal?.let { plugin ->
        WhaleSheet("卸载 ${plugin.bundle.name}？", "插件将从所有工作会话移除。", { removal = null }) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton({ removal = null }) { Text("取消") }
                TextButton(
                    {
                        removal = null
                        change { manager.store.remove(plugin) }
                    },
                ) {
                    Text("卸载")
                }
            }
        }
    }
}

@Composable
private fun PluginDetail(
    plugin: InstalledPlugin,
    conversation: Conversation,
    manager: PluginManager,
    close: () -> Unit,
) {
    val jobs = rememberCoroutineScope()
    var selected by remember { mutableStateOf(plugin.bundle.tools.first()) }
    var input by remember(selected) { mutableStateOf("{}") }
    var output by remember(selected) { mutableStateOf("") }
    var showCode by remember(selected) { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    WhaleSheet(plugin.bundle.name, plugin.bundle.description, close) {
        plugin.bundle.tools.forEach { tool ->
            FilterChip(
                selected == tool,
                { selected = tool },
                label = { Text(tool.name) },
                enabled = !running,
            )
        }
        Text(selected.description, fontSize = 13.sp, color = WhaleMuted)
        TextButton({ showCode = !showCode }) { Text(if (showCode) "收起源码" else "查看源码") }
        if (showCode)
            SelectionContainer {
                Text(selected.code, fontFamily = DshaFonts.mono, fontSize = 12.sp)
            }
        OutlinedTextField(
            input,
            { input = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("JSON 参数") },
            maxLines = 5,
            enabled = plugin.enabled && !running,
        )
        TextButton(
            onClick = {
                val tool = selected
                val raw = input
                running = true
                jobs.launch {
                    output = ""
                    try {
                        val args =
                            JSONObject()
                                .put("plugin_id", plugin.bundle.id)
                                .put("tool", tool.name)
                                .put("arguments", JSONObject(raw))
                        val result =
                            manager.call(
                                args,
                                conversation.id,
                                conversation.projectId,
                                ask = { _, reason -> error(reason) },
                                expectedRevision = plugin.revision,
                            )
                        output = result.toString(2)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        output = failure.message.orEmpty().take(1000)
                    } finally {
                        running = false
                    }
                }
            },
            enabled = plugin.enabled && !running,
        ) {
            Text(if (running) "运行中…" else "运行工具")
        }
        if (output.isNotEmpty())
            SelectionContainer { Text(output, fontFamily = DshaFonts.mono, fontSize = 12.sp) }
    }
}
