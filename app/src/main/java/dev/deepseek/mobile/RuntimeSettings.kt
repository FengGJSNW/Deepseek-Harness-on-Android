package dev.deepseek.mobile

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.deepseek.mobile.harness.*
import dev.deepseek.mobile.runtime.*
import kotlinx.coroutines.*
import org.json.JSONObject

@Composable
internal fun RuntimeSettings(conversation: Conversation) {
    val context = LocalContext.current
    val harness = remember { HarnessCoordinator.get(context) }
    val scope =
        remember(conversation.projectId, conversation.id) {
            if (conversation.projectId.isBlank()) WorkspaceScope("session", conversation.id)
            else WorkspaceScope("project", conversation.projectId)
        }
    val states by harness.runtimes.states.collectAsState()
    val record = states[scope.key] ?: RuntimeInfo(scope)
    val jobs = rememberCoroutineScope()
    var storage by remember(scope) { mutableStateOf(JSONObject()) }
    var message by remember(scope) { mutableStateOf("") }
    var resetting by remember { mutableStateOf(false) }
    var resetDialog by remember { mutableStateOf(false) }
    var kind by remember { mutableStateOf<RuntimeKind?>(null) }
    var trees by remember(scope) { mutableStateOf(emptyList<Worktree>()) }
    var review by remember { mutableStateOf<Worktree?>(null) }
    var packages by remember { mutableStateOf(false) }
    var reviewRevision by remember { mutableIntStateOf(0) }
    LaunchedEffect(scope, record.savedAt, reviewRevision) {
        storage = harness.runtimes.storage(scope)
        trees =
            withContext(Dispatchers.IO) {
                harness.workspaces.tasks(scope).filter {
                    it.state in setOf("ready", "conflicted") &&
                        harness.workspaces.review(it).isNotEmpty()
                }
            }
    }
    Text(if (scope.kind == "project") "项目环境" else "会话环境", fontSize = 12.sp, color = WhaleMuted)
    Surface(
        color = Color(0xFFF5F5F2),
        shape = ContinuousShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.TerminalBox, null, Modifier.size(22.dp), tint = WhaleBlue)
                Text(
                    record.kind.title,
                    Modifier.padding(start = 10.dp),
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp,
                )
            }
            Text(record.detail, fontSize = 12.sp, color = WhaleMuted, lineHeight = 19.sp)
            Text("/workspace", fontFamily = DshaFonts.mono, fontSize = 11.sp, color = WhaleMuted)
        }
    }
    Text(
        if (scope.kind == "project") "项目内的会话共享开发工具与包。每个 Agent 任务使用独立工作副本，确认后合并。"
        else "此会话拥有独立的工具、包和工作区。创建对话不会立即启动环境。",
        fontSize = 12.sp,
        lineHeight = 20.sp,
        color = WhaleMuted,
    )
    SettingsLink(AppIcons.Terminal, "打开本地终端", "直接输入命令、交互操作与 Ctrl+C") {
        LocalTerminalState.open(conversation.id)
    }
    SettingsLink(AppIcons.FolderOpen, "运行环境", "当前：${record.kind.title}") { kind = record.kind }
    SettingsLink(AppIcons.TerminalBox, "共享工具链安装申请", "全局安装必须由你单独批准") { packages = true }
    HorizontalDivider(color = WhaleLine)
    Text("空间占用", fontSize = 13.sp, fontWeight = FontWeight.Medium)
    fun mb(value: Long) = "%.1f MB".format(value / 1048576.0)
    listOf(
            "运行环境" to "runtimeBytes",
            "当前工作区" to "workspaceBytes",
            "共享工具模板" to "sharedToolchainBytes",
            "发行版缓存" to "distributionBytes",
        )
        .forEach { (label, key) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(label, Modifier.weight(1f), fontSize = 12.sp, color = WhaleMuted)
                Text(mb(storage.optLong(key)), fontSize = 12.sp, fontFamily = DshaFonts.mono)
            }
        }
    SettingsLink(AppIcons.Reset, "重置运行环境", "保留工作区、版本历史、记忆和指令；清除环境里的包与自定义修改") { resetDialog = true }
    if (trees.isNotEmpty()) {
        HorizontalDivider(color = WhaleLine)
        Text("待合并的文件", fontSize = 13.sp, fontWeight = FontWeight.Medium)
        trees.forEach { tree ->
            SettingsLink(
                AppIcons.Diff,
                "工作副本 ${tree.taskId.take(8)}",
                if (tree.state == "conflicted") "有冲突 · 原工作区未覆盖" else "查看改动并合并",
            ) {
                review = tree
            }
        }
    }
    if (message.isNotBlank()) Text(message, color = WhaleMuted, fontSize = 12.sp)
    if (kind != null && !resetDialog)
        WhaleSheet("选择运行环境", close = { kind = null }) {
            RuntimeKind.entries
                .filter { it.available }
                .forEach { option ->
                    OptionRow(
                        AppIcons.TerminalBox,
                        option.title,
                        if (option == RuntimeKind.LIGHT) "预置 Git、SSH、Python、Node 等工具"
                        else "完整 Alpine userspace，按需安装包",
                        record.kind == option,
                    ) {
                        if (record.phase in setOf("not_initialized", "deleted"))
                            jobs.launch {
                                runCatching { harness.runtimes.selectKind(scope, option) }
                                    .onFailure { message = it.message.orEmpty() }
                                kind = null
                            }
                        else if (option != record.kind) {
                            kind = option
                            resetDialog = true
                        } else kind = null
                    }
                }
        }
    if (resetDialog)
        WhaleSheet(
            "重置运行环境？",
            "工作区、Git 历史、待合并副本、记忆与工作指令都会保留。环境内额外安装的包和临时文件会清除。",
            { if (!resetting) resetDialog = false },
        ) {
            Row(Modifier.align(Alignment.End)) {
                TextButton(onClick = { resetDialog = false }, enabled = !resetting) { Text("取消") }
                TextButton(
                    onClick = {
                        resetting = true
                        jobs.launch {
                            runCatching {
                                harness.runtimes.reset(scope)
                                kind?.let { harness.runtimes.selectKind(scope, it) }
                            }
                                .onSuccess {
                                    message = "环境已重置"
                                    kind = null
                                    resetDialog = false
                                }
                                .onFailure { message = it.message.orEmpty() }
                            resetting = false
                        }
                    },
                    enabled = !resetting,
                    modifier = Modifier.testTag("runtime-reset-confirm"),
                ) {
                    Text(if (resetting) "重置中…" else "重置")
                }
            }
        }
    review?.let { tree ->
        WorktreeReview(tree) {
            review = null
            reviewRevision++
        }
    }
    if (packages) ToolchainRequests { packages = false }
}

@Composable
internal fun WorktreeReview(tree: Worktree, close: () -> Unit) {
    val context = LocalContext.current
    val harness = remember { HarnessCoordinator.get(context) }
    var changes by remember(tree.taskId) { mutableStateOf(emptyList<WorkspaceChange>()) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val jobs = rememberCoroutineScope()
    LaunchedEffect(tree.taskId) {
        changes = withContext(Dispatchers.IO) { harness.workspaces.review(tree) }
    }
    WhaleSheet("${changes.size} 个文件改动", "合并前会再次检查当前版本。发生冲突时拒绝覆盖，原工作区会保留。", close) {
        Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
            changes.forEach { change ->
                SettingsLink(
                    AppIcons.Description,
                    change.path,
                    if (change.before == null) "新增" else if (change.after == null) "删除" else "修改",
                ) {
                    jobs.launch {
                        withContext(Dispatchers.IO) {
                            fun read(version: dev.deepseek.mobile.harness.FileVersion?): String? =
                                version?.let {
                                    val bytes = harness.workspaces.content(it)
                                    if (bytes.size > 65536 || bytes.any { b -> b == 0.toByte() })
                                        null
                                    else
                                        runCatching {
                                            Charsets.UTF_8.newDecoder()
                                                .decode(java.nio.ByteBuffer.wrap(bytes))
                                                .toString()
                                        }
                                            .getOrNull()
                                }
                            val before = read(change.before)
                            val after = read(change.after)
                            ChangeState.selected =
                                WorkChange(
                                    java.util.UUID.randomUUID().toString(),
                                    tree.taskId,
                                    AssistantState.selectedId,
                                    change.path,
                                    before,
                                    after,
                                    "工作副本",
                                    (change.before != null && before == null) ||
                                        (change.after != null && after == null),
                                )
                        }
                    }
                }
            }
        }
        if (message.isNotBlank())
            Text(
                message,
                fontSize = 12.sp,
                color = Color(0xFFB17A2C),
                modifier = Modifier.padding(vertical = 10.dp),
            )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(
                onClick = {
                    busy = true
                    jobs.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                harness.runtimes.resolveWorktree(
                                    tree,
                                    false,
                                    PermissionContext(
                                        sessionId = AssistantState.selectedId,
                                        projectId = AssistantState.current.projectId,
                                        actor = "user",
                                    ),
                                ) {
                                    true
                                }
                            }
                        }
                            .onSuccess { close() }
                            .onFailure { message = it.message.orEmpty() }
                        busy = false
                    }
                },
                enabled = !busy,
            ) {
                Text("丢弃副本")
            }
            TextButton(
                onClick = {
                    busy = true
                    jobs.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                harness.runtimes.resolveWorktree(
                                    tree,
                                    true,
                                    PermissionContext(
                                        sessionId = AssistantState.selectedId,
                                        projectId = AssistantState.current.projectId,
                                        actor = "user",
                                    ),
                                ) {
                                    true
                                }
                            }
                        }
                            .onSuccess {
                                if (it.getBoolean("merged")) close()
                                else
                                    message =
                                        "以下文件已被其他任务修改，请基于最新工作区重新合并：\n" +
                                            (0 until it.getJSONArray("conflicts").length())
                                                .joinToString("\n") { i ->
                                                    it.getJSONArray("conflicts").getString(i)
                                                }
                            }
                            .onFailure { message = it.message.orEmpty() }
                        busy = false
                    }
                },
                enabled = !busy && changes.isNotEmpty(),
                modifier = Modifier.testTag("worktree-merge"),
            ) {
                Text("合并到工作区")
            }
        }
    }
}

@Composable
internal fun InstructionEditor(scopeName: String, projectId: String, close: () -> Unit) {
    val context = LocalContext.current
    val manager = remember { HarnessCoordinator.get(context).contexts }
    var text by remember { mutableStateOf("") }
    var hash by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    val jobs = rememberCoroutineScope()
    LaunchedEffect(scopeName, projectId) {
        val value = withContext(Dispatchers.IO) { manager.instruction(scopeName, projectId) }
        text = value.getString("text")
        hash = value.getString("sha256")
    }
    WhaleSheet(
        if (scopeName == "project") "项目工作指令"
        else if (scopeName == "personality") "人格指令" else "全局工作指令",
        "由你维护，Agent 只读。项目指令细化全局指令；记忆只保存偏好与事实。",
        close,
    ) {
        OutlinedTextField(
            text,
            { text = it },
            Modifier.fillMaxWidth()
                .heightIn(min = 180.dp, max = 380.dp)
                .testTag("instruction-editor"),
            textStyle =
                MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 21.sp),
            shape = ContinuousShape(14.dp),
            visualTransformation = UprightSymbols.transformation,
        )
        if (message.isNotBlank()) Text(message, fontSize = 12.sp, color = WhaleMuted)
        TextButton(
            onClick = {
                jobs.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            manager.saveUserInstruction(scopeName, projectId, text, hash)
                        }
                    }
                        .onSuccess { close() }
                        .onFailure { message = it.message.orEmpty() }
                }
            },
            modifier = Modifier.align(Alignment.End),
        ) {
            Text("保存指令")
        }
    }
}
