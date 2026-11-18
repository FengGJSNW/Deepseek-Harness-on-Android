package dev.deepseek.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.deepseek.mobile.harness.HarnessCoordinator
import dev.deepseek.mobile.runtime.*
import java.text.DateFormat
import java.util.Date

@Composable
internal fun SandboxComposerBadge(conversation: Conversation) {
    val task = AssistantState.running?.takeIf { it.sessionId == conversation.id }
    val context = LocalContext.current
    val manager = remember(context) { SandboxManager.get(context) }
    val connections by manager.activeConnections.collectAsState()
    if (!conversation.sandboxEnabled && connections[task?.id].isNullOrEmpty()) return
    val records by manager.records.collectAsState()
    val states by HarnessCoordinator.get(context).runtimes.states.collectAsState()
    var choosing by remember(conversation.id) { mutableStateOf(false) }
    val id = if (task?.sandboxOnly == true) task.sandboxId else conversation.sandboxId
    val record = records.firstOrNull { it.id == id }
    val label =
        when {
            !conversation.sandboxEnabled ->
                records
                    .filter { it.id in connections[task?.id].orEmpty() }
                    .joinToString("、") { it.name }
            record != null -> record.name + " · " + sandboxPhase(states[record.scope.key]?.phase)
            else -> "未选择或不可用"
        }
    SandboxPicker(conversation, choosing, { choosing = false }) {
        Text(
            "Sandbox · $label",
            Modifier.clickable { choosing = !choosing }
                .padding(horizontal = 12.dp, vertical = 4.dp),
            fontSize = 11.sp,
            color = WhaleMuted,
            maxLines = 1,
        )
    }
}

@Composable
internal fun SandboxSwitch(conversation: Conversation) {
    var picking by remember(conversation.id) { mutableStateOf(false) }
    SandboxPicker(conversation, picking, { picking = false }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Only Sandbox", fontSize = 12.sp, color = WhaleMuted)
            Spacer(Modifier.width(8.dp))
            DshaSwitch(
                checked = conversation.sandboxEnabled,
                onCheckedChange = { enabled ->
                    if (picking) picking = false
                    else if (enabled) picking = true
                    else {
                        conversation.sandboxEnabled = false
                        AssistantState.persist()
                    }
                },
                modifier =
                    Modifier.testTag("sandbox-switch").semantics {
                        contentDescription = "Only Sandbox"
                    },
            )
        }
    }
}

@Composable
internal fun SandboxSelection(conversation: Conversation) {
    if (!conversation.sandboxEnabled) return
    val context = LocalContext.current
    val manager = remember(context) { SandboxManager.get(context) }
    val records by manager.records.collectAsState()
    val states by HarnessCoordinator.get(context).runtimes.states.collectAsState()
    var picking by remember(conversation.id) { mutableStateOf(false) }
    val record = records.firstOrNull { it.id == conversation.sandboxId }
    val detail =
        if (record != null) record.name + " · " + sandboxPhase(states[record.scope.key]?.phase)
        else "请选择一个可访问的实例"
    SandboxPicker(conversation, picking, { picking = false }, rowTitle = "首选 Sandbox") {
        SettingsLink(AppIcons.TerminalBox, "首选 Sandbox", detail) { picking = !picking }
    }
}

@Composable
internal fun SandboxPicker(
    conversation: Conversation,
    expanded: Boolean,
    close: () -> Unit,
    rowTitle: String? = null,
    anchor: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val manager = remember(context) { SandboxManager.get(context) }
    val records by manager.records.collectAsState()
    val states by HarnessCoordinator.get(context).runtimes.states.collectAsState()
    val connections by manager.activeConnections.collectAsState()
    val running = AssistantState.running?.takeIf { it.sessionId == conversation.id }
    fun select(record: SandboxRecord) {
        conversation.sandboxEnabled = true
        conversation.sandboxId = record.id
        AssistantState.persist()
        close()
    }
    LiquidMenuAnchor(
        expanded,
        close,
        width = 300.dp,
        tag = "sandbox-picker",
        placement = if (rowTitle != null) MenuPlacement.SelectedRow else MenuPlacement.Above,
        menuTitle = rowTitle,
        toggleAnchor = true,
        anchor = anchor,
    ) {
        if (conversation.sandboxEnabled)
            GlassMenuRow(AppIcons.TerminalUser, "使用手机终端", "", false) {
                conversation.sandboxEnabled = false
                AssistantState.persist()
                close()
            }
        records
            .filter { it.allows(conversation.id, conversation.projectId) }
            .forEach { record ->
                val linked =
                    record.id in connections[running?.id].orEmpty() ||
                        running?.sandboxId == record.id
                GlassMenuRow(
                    AppIcons.TerminalBox,
                    record.name,
                    record.preset.profile.title +
                        " · " +
                        sandboxPhase(states[record.scope.key]?.phase) +
                        if (linked) " · 本任务已连接" else "",
                    conversation.sandboxEnabled && conversation.sandboxId == record.id,
                ) {
                    select(record)
                }
            }
        val hideMenus = LocalDismissMenuStack.current
        GlassMenuRow(AppIcons.TerminalBox, "管理访问权限", "在右侧工作区选择多个 Sandbox") {
            hideMenus()
            WorkspaceState.open(WorkspaceTab.SandboxAccess)
        }
    }
}

@Composable
private fun SandboxCreateSheet(
    conversation: Conversation,
    close: () -> Unit,
    created: (SandboxRecord) -> Unit,
) {
    val context = LocalContext.current
    val manager = remember(context) { SandboxManager.get(context) }
    val images = remember(context) { RuntimeImages.get(context) }
    val availableImages by images.availableImages.collectAsState()
    var profile by remember { mutableStateOf(SandboxProfile.DEFAULT) }
    var owner by remember { mutableStateOf(SandboxOwner.SESSION) }
    var custom by remember { mutableStateOf(SandboxPreset(profile = SandboxProfile.CUSTOM)) }
    val base = if (profile == SandboxProfile.LITE) RuntimeKind.ALPINE else RuntimeKind.LIGHT
    val preset = if (profile == SandboxProfile.CUSTOM) custom else SandboxPreset.defaults(base)
    var advanced by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    WhaleSheet("创建 Sandbox", close = close) {
        SandboxProfile.entries.forEach { option ->
            OptionRow(AppIcons.TerminalBox, option.title, option.detail, profile == option) {
                profile = option
            }
        }
        OutlinedTextField(
            name,
            { name = it.take(60) },
            singleLine = true,
            label = { Text("名称") },
            placeholder = {
                Text(profile.title)
            },
            modifier = Modifier.fillMaxWidth().testTag("sandbox-name"),
            shape = ContinuousShape(14.dp),
            visualTransformation = UprightSymbols.transformation,
        )
        Spacer(Modifier.height(10.dp))
        if (profile == SandboxProfile.CUSTOM)
            SettingsLink(AppIcons.Settings, "自定义选项", "发行版、工具来源与语言版本") {
                advanced = true
            }
        val localBase = preset.release == AlpineRelease.V324 && "alpine" in availableImages
        val localTools =
            preset.packages.isEmpty() ||
                preset.offlineTools && !preset.nodeCurrent && "tools" in availableImages
        Text(
            if (localBase && localTools) "本机镜像与工具包已就绪，可离线创建" else "创建时将下载缺少的基础镜像或软件包",
            color = WhaleMuted,
            style = PrismTypography.footnote,
        )
        SectionLabel("归属")
        SandboxOwner.entries
            .filter { it != SandboxOwner.PROJECT || conversation.projectId.isNotBlank() }
            .forEach { item ->
                OptionRow(
                    AppIcons.FolderOpen,
                    item.title,
                    "创建后在会话右侧工作区授权访问",
                    owner == item,
                ) {
                    owner = item
                }
            }
        if (error.isNotBlank())
            Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        DshaButton(
            onClick = {
                runCatching {
                        manager.create(
                            name,
                            base,
                            owner,
                            conversation.id,
                            conversation.projectId,
                            preset,
                        )
                    }
                    .onSuccess(created)
                    .onFailure { error = it.message.orEmpty() }
            },
            enabled = name.isNotBlank(),
            modifier = Modifier.fillMaxWidth().testTag("sandbox-create"),
        ) {
            Text("创建并初始化")
        }
    }
    if (advanced)
        WhaleSheet("自定义选项", close = { advanced = false }) {
            SandboxPresetEditor(custom) { custom = it }
        }
}

@Composable
internal fun SandboxSettings(conversation: Conversation, openTerminal: (String) -> Unit) {
    val context = LocalContext.current
    val manager = remember(context) { SandboxManager.get(context) }
    val runtime = HarnessCoordinator.get(context).runtimes
    val records by manager.records.collectAsState()
    val states by runtime.states.collectAsState()
    val operations by manager.operations.collectAsState()
    val errors by manager.errors.collectAsState()
    var creating by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<SandboxRecord?>(null) }
    val record = records.firstOrNull { it.id == selected }
    if (record == null) {
        SandboxImageImport()
        SettingsLink(AppIcons.Add, "创建 Sandbox", "选择基础镜像并初始化") { creating = true }
        for (item in records) {
            val status = operations[item.id] ?: sandboxPhase(states[item.scope.key]?.phase)
            SettingsLink(
                AppIcons.TerminalBox,
                item.name,
                item.preset.profile.title + " · " + status,
            ) {
                selected = item.id
            }
        }
        if (records.isEmpty()) Text("还没有 Sandbox", color = WhaleMuted, fontSize = 13.sp)
        SandboxDiskManager()
    } else {
        val info = states[record.scope.key]
        val operation = operations[record.id]
        val detail = info?.detail.orEmpty()
        SettingsLink(AppIcons.ArrowBack, "全部 Sandbox") { selected = "" }
        SettingsStatusCard(
            AppIcons.TerminalBox,
            record.name,
            record.preset.profile.title + " · " + (operation ?: sandboxPhase(info?.phase)),
        )
        if (operation != null) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (detail.isNotBlank()) Text(detail, fontSize = 12.sp, color = WhaleMuted)
        errors[record.id]?.let {
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!record.deleting) {
                DshaTextButton(
                    enabled = operation == null,
                    onClick = { manager.start(record.id) },
                ) {
                    Text(
                        if (info?.phase == "broken" || info?.phase == "interrupted") "重试" else "启动",
                    )
                }
                DshaTextButton(
                    enabled =
                        operation == null &&
                            info?.phase in setOf("ready", "running", "saving", "suspended"),
                    onClick = { manager.stop(record.id) },
                ) {
                    Text("停止")
                }
                DshaTextButton(
                    enabled = operation == null,
                    onClick = { manager.restart(record.id) },
                ) {
                    Text("重启")
                }
            }
            DshaTextButton(enabled = operation == null, onClick = { deleting = record }) {
                Text(if (record.deleting) "继续删除" else "删除")
            }
        }
        SettingsLink(AppIcons.Terminal, "进入终端", "此 Sandbox 内的 root Shell") {
            openTerminal(record.id)
        }
        var advanced by remember(record.id) { mutableStateOf(false) }
        SettingsLink(AppIcons.Checklist, "高级属性", "归属、网络与环境信息") { advanced = !advanced }
        DisclosureBody(advanced) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val storage by
                    produceState(0L, record.id, info?.savedAt) {
                        value = runtime.storage(record.scope).optLong("runtimeBytes")
                    }
                val date = DateFormat.getDateTimeInstance()
                Text(
                    "归属 " + record.owner.title + " · 创建 " + date.format(Date(record.createdAt)),
                    fontSize = 12.sp,
                )
                if ((info?.savedAt ?: 0) > 0)
                    Text(
                        "最近检查点 " + date.format(Date(requireNotNull(info).savedAt)),
                        fontSize = 12.sp,
                    )
                Text(
                    "实例存储 %.1f MB · 同时保留一台运行中的虚拟机".format(storage / 1048576.0),
                    fontSize = 12.sp,
                    color = WhaleMuted,
                )
                if (record.diagnostics.isNotBlank()) {
                    SectionLabel("诊断和启动日志")
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(record.diagnostics, fontFamily = DshaFonts.mono, fontSize = 11.sp)
                    }
                }
                if (!record.deleting) {
                    ToggleSetting("互联网访问", "允许公网 IPv4。局域网、手机服务和设备不开放。", record.internet) {
                        manager.change(record.id) { r -> r.copy(internet = it) }
                    }
                }
            }
        }
    }
    if (creating)
        SandboxCreateSheet(conversation, { creating = false }) {
            creating = false
            selected = it.id
        }
    deleting?.let { target ->
        WhaleSheet(
            "删除 Sandbox？",
            "将清除 " + target.name + " 的系统文件、软件和配置。独立数据磁盘会保留。",
            { deleting = null },
        ) {
            DshaButton(
                onClick = {
                    manager.delete(target.id)
                    deleting = null
                },
            ) {
                Text("确认删除")
            }
        }
    }
}

internal fun sandboxPhase(phase: String?): String =
    when (phase) {
        "initializing" -> "初始化中"
        "ready" -> "运行就绪"
        "running" -> "执行中"
        "saving" -> "保存中"
        "suspended" -> "已保存"
        "stopped" -> "已停止"
        "interrupted" -> "已中断"
        "broken" -> "异常"
        else -> "待初始化"
    }
