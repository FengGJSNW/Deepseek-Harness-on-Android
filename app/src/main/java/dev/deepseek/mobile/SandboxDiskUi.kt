package dev.deepseek.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.deepseek.mobile.runtime.SandboxDisk
import dev.deepseek.mobile.runtime.SandboxDisks
import dev.deepseek.mobile.runtime.SandboxManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun SandboxDiskManager() {
    val context = LocalContext.current
    val store = remember { SandboxDisks.get(context) }
    val disks by store.records.collectAsState()
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<SandboxDisk?>(null) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    SectionLabel("独立数据磁盘")
    Text(
        "删除 Sandbox 后仍保留。挂载后在 /disks/<磁盘 ID> 中使用，按实际数据占用手机空间。",
        color = WhaleMuted,
        fontSize = 12.sp,
    )
    SettingsLink(AppIcons.Add, "创建磁盘", "64 MB–32 GB · 按实际数据占用空间") { creating = true }
    for (disk in disks) {
        SettingsChoice(
            AppIcons.FolderOpen,
            disk.name,
            "${diskCapacityLabel((disk.capacity / 1048576).toInt())} · ${disk.id.take(8)}",
        ) { dismiss ->
            GlassMenuRow(AppIcons.TerminalBox, "挂载权限", "选择可以访问的 Sandbox") {
                dismiss()
                WorkspaceState.open(WorkspaceTab.SandboxDisks())
            }
            GlassMenuRow(AppIcons.Close, "删除磁盘", "将永久删除此磁盘的数据") {
                dismiss()
                deleting = disk
            }
        }
    }
    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    if (creating) {
        var name by remember { mutableStateOf("") }
        var capacity by remember { mutableIntStateOf(256) }
        var issue by remember { mutableStateOf("") }
        WhaleSheet("创建数据磁盘", close = { creating = false }) {
            OutlinedTextField(
                name,
                { name = it.take(60) },
                label = { Text("磁盘名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = ContinuousShape(14.dp),
            )
            DiskCapacityPicker(capacity) { capacity = it }
            Text("单文件最多 64 MB，最多 20,000 个条目；超出限制会保留旧检查点并提示。", color = WhaleMuted, fontSize = 12.sp)
            if (issue.isNotBlank()) Text(issue, color = MaterialTheme.colorScheme.error)
            DshaButton(
                onClick = {
                    runCatching { store.create(name, capacity * 1048576L) }
                        .onSuccess { creating = false }
                        .onFailure { issue = it.message.orEmpty() }
                },
                enabled = name.isNotBlank(),
            ) {
                Text("创建磁盘")
            }
        }
    }
    deleting?.let { disk ->
        WhaleSheet(
            "删除 ${disk.name}？",
            "此操作会删除独立磁盘内的全部数据，其他 Sandbox 也无法再挂载它。",
            { if (!busy) deleting = null },
        ) {
            DshaButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            store.delete(disk.id)
                            deleting = null
                            error = ""
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = e.message.orEmpty()
                            deleting = null
                        } finally {
                            busy = false
                        }
                    }
                },
            ) {
                Text(if (busy) "保存并删除中…" else "确认删除磁盘")
            }
        }
    }
}

@Composable
internal fun SandboxDiskAccessPane(sandboxId: String) {
    val context = LocalContext.current
    val store = remember { SandboxDisks.get(context) }
    val manager = remember { SandboxManager.get(context) }
    val disks by store.records.collectAsState()
    val sandboxes by manager.records.collectAsState()
    val scope = rememberCoroutineScope()
    var selected by remember(sandboxId) { mutableStateOf(sandboxId) }
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val target = sandboxes.firstOrNull { it.id == selected && !it.deleting }
    fun change(disk: SandboxDisk, checked: Boolean) {
        if (busy) return
        if (selected.isNotBlank() && target == null) {
            error = "此 Sandbox 已删除，请重新选择挂载范围"
            return
        }
        busy = true
        error = ""
        scope.launch {
            try {
                store.change(disk.id) { old ->
                    if (target == null) old.copy(allSandboxes = checked)
                    else
                        old.copy(
                            allowed =
                                if (checked) old.allowed + target.id else old.allowed - target.id,
                            denied =
                                if (checked) old.denied - target.id else old.denied + target.id,
                        )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message.orEmpty()
            } finally {
                busy = false
            }
        }
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding =
            PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = LocalWorkspaceTopInset.current + 16.dp,
                bottom = 24.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { SandboxSearchField(query, { query = it }, "搜索磁盘") }
        item {
            SettingsChoice(
                AppIcons.TerminalBox,
                "挂载范围",
                target?.name ?: if (selected.isBlank()) "所有 Sandbox" else "Sandbox 已删除",
            ) { dismiss ->
                GlassMenuRow(AppIcons.TerminalBox, "所有 Sandbox", "新建实例也适用；单独关闭优先", target == null) {
                    selected = ""
                    dismiss()
                }
                for (record in sandboxes.filterNot { it.deleting }) GlassMenuRow(
                    AppIcons.TerminalBox,
                    record.name,
                    "",
                    target?.id == record.id,
                ) {
                    selected = record.id
                    dismiss()
                }
            }
        }
        item {
            Text(
                "挂载权限按电脑设置；会话还必须获得该 Sandbox 的访问权限。更改挂载前会保存当前环境。",
                color = WhaleMuted,
                fontSize = 12.sp,
            )
        }
        items(
            disks.filter { it.name.contains(query, true) || it.id.contains(query, true) },
            key = { it.id },
        ) { disk ->
            ToggleSetting(
                disk.name,
                if (busy) "正在保存挂载…"
                else if (target == null) "所有 Sandbox 可挂载 · 单独关闭优先" else "/disks/${disk.id}",
                target?.let { disk.allows(it.id) } ?: disk.allSandboxes,
            ) {
                change(disk, it)
            }
        }
        if (disks.isEmpty()) item { Text("还没有数据磁盘，请在侧边栏 Sandbox 中创建。", color = WhaleMuted) }
        if (error.isNotBlank()) item { Text(error, color = MaterialTheme.colorScheme.error) }
    }
}
