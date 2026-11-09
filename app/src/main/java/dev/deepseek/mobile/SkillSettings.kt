package dev.deepseek.mobile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.deepseek.mobile.harness.HarnessCoordinator
import dev.deepseek.mobile.harness.InstalledSkill
import kotlinx.coroutines.*

@Composable
internal fun SkillSettings(projectId: String) {
    val context = LocalContext.current
    val store = remember { HarnessCoordinator.get(context).skills }
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf(emptyList<InstalledSkill>()) }
    var currentProject by remember(projectId) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<Pair<InstalledSkill, String>?>(null) }
    val owner = if (currentProject) projectId else ""
    fun change(block: () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                items =
                    withContext(Dispatchers.IO) {
                        block()
                        store.list()
                    }
                status = "已保存"
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                status = error.message.orEmpty()
            } finally {
                busy = false
            }
        }
    }
    LaunchedEffect(store) { items = withContext(Dispatchers.IO) { store.list() } }
    val pick =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null)
                change {
                    val input = context.contentResolver.openInputStream(uri) ?: error("无法打开技能包")
                    store.install(input, owner)
                }
        }
    SettingsStatusCard(
        AppIcons.Spark,
        "安装 Agent Skills",
        "导入 SKILL.md 或包含技能目录的 ZIP。启用后，Agent 可按需读取指令与参考文件。",
    )
    if (projectId.isNotBlank())
        ToggleSetting("仅当前项目", "关闭时管理所有会话共享的技能", currentProject) { currentProject = it }
    Button(onClick = { pick.launch(arrayOf("*/*")) }, enabled = !busy) { Text("导入技能包") }
    val visible = items.filter { it.projectId == owner }
    SectionLabel(if (currentProject) "当前项目的技能" else "共享技能")
    if (visible.isEmpty()) SettingsStatusCard(AppIcons.FolderOpen, "还没有安装技能")
    visible.forEach { item ->
        HorizontalDivider(color = WhaleLine)
        ToggleSetting(item.name, item.description, item.enabled) { enabled ->
            if (enabled)
                scope.launch {
                    try {
                        preview = item to withContext(Dispatchers.IO) { store.preview(item.id) }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        status = error.message.orEmpty()
                    }
                }
            else change { store.enable(item.id, false) }
        }
        Row {
            TextButton(
                enabled = !busy,
                onClick = {
                    scope.launch {
                        try {
                            preview = item to withContext(Dispatchers.IO) { store.preview(item.id) }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            status = error.message.orEmpty()
                        }
                    }
                },
            ) {
                Text("查看指令")
            }
            TextButton(enabled = !busy, onClick = { change { store.remove(item.id) } }) {
                Text("卸载")
            }
        }
    }
    HorizontalDivider(color = WhaleLine)
    SectionLabel("Codex 技能包")
    for ((name, detail) in
        listOf(
            "skill-creator" to "创建、整理和验证技能包",
            "skill-installer" to "查找与安装 Codex 技能的参考工作流",
        )) {
        val installed = visible.any { it.name == name }
        SettingsLink(AppIcons.Spark, name, if (installed) "已安装" else detail) {
            if (!installed) change { store.install(context.assets.open("skills/$name.zip"), owner) }
        }
    }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (status.isNotBlank())
        SettingsStatusCard(
            if (status == "已保存") AppIcons.Check else AppIcons.Description,
            status,
            announce = true,
        )
    preview?.let { (item, body) ->
        WhaleSheet(item.name, "启用后，技能指令将提供给 Agent。工具仍按当前权限执行。", { preview = null }) {
            Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState())) {
                Text(body, fontSize = 13.sp, lineHeight = 20.sp)
            }
            if (!item.enabled)
                Button(
                    onClick = {
                        preview = null
                        change { store.enable(item.id, true) }
                    },
                ) {
                    Text("启用技能")
                }
        }
    }
}
