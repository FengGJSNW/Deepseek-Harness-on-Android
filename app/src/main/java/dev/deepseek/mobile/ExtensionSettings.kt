package dev.deepseek.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.deepseek.mobile.harness.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExtensionSettings(conversation: Conversation, onCreatePlugin: () -> Unit) {
    val context = LocalContext.current
    val harness = remember { HarnessCoordinator.get(context) }
    val manager = harness.extensions
    val jobs = rememberCoroutineScope()
    var kind by remember { mutableStateOf("plugins") }
    var projectOnly by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var edit by remember { mutableStateOf<JSONObject?>(null) }
    var review by remember { mutableStateOf<JSONObject?>(null) }
    var status by remember { mutableStateOf("") }
    var audit by remember { mutableStateOf(false) }
    val entries =
        remember(revision, kind, projectOnly, conversation.projectId) {
            if (kind in setOf("skills", "plugins")) emptyList()
            else
                manager.store.list(kind).filter {
                    it.optString("projectId") == if (projectOnly) conversation.projectId else ""
                }
        }
    val tabs = listOf("plugins" to "插件", "mcp" to "MCP", "hooks" to "Hooks", "skills" to "Skills")
    PrimaryTabRow(
        selectedTabIndex = tabs.indexOfFirst { it.first == kind },
        containerColor = Color.Transparent,
        contentColor = WhaleInk,
    ) {
        tabs.forEach { (id, title) ->
            Tab(selected = kind == id, onClick = { kind = id }, text = { Text(title) })
        }
    }
    if (kind == "plugins") {
        PluginSettings(conversation, onCreatePlugin)
        return
    }
    if (kind == "skills") {
        SkillSettings(conversation.projectId)
        return
    }
    if (conversation.projectId.isNotBlank())
        ToggleSetting("仅当前项目", "关闭时管理所有会话共享的扩展", projectOnly) { projectOnly = it }
    Text(
        if (kind == "mcp") "连接外部工具" else "让工作流自动响应事件",
        fontSize = 18.sp,
        fontWeight = FontWeight.Medium,
    )
    Text(
        if (kind == "mcp") "支持 HTTPS Streamable HTTP。工具独立申请权限；服务端返回内容作为外部数据处理，不授予手机权限。"
        else "支持上下文注入、拦截、参数修改、日志与独立 Sandbox 命令。修改后的操作仍会重新审查。",
        fontSize = 12.sp,
        lineHeight = 20.sp,
        color = WhaleMuted,
    )
    SettingsLink(
        AppIcons.Add,
        if (kind == "mcp") "添加 MCP" else "添加 Hook",
        if (projectOnly) "作用域：当前项目" else "作用域：全局",
    ) {
        edit =
            JSONObject()
                .put("id", java.util.UUID.randomUUID().toString())
                .put("name", "")
                .put("projectId", if (projectOnly) conversation.projectId else "")
                .put(
                    "capabilities",
                    JSONArray(
                        if (kind == "mcp") listOf("NETWORK", "MCP_TOOL") else listOf("HOOK_EXEC"),
                    ),
                )
                .put("event", "BeforeToolCall")
                .put("action", "log")
                .put("match", "*")
                .put("alwaysAsk", true)
    }
    if (entries.isEmpty())
        Text(
            "还没有扩展",
            color = WhaleMuted,
            fontSize = 13.sp,
            modifier = Modifier.padding(vertical = 18.dp),
        )
    entries.forEach { item ->
        Surface(
            color = Color(0xFFF5F5F2),
            shape = ContinuousShape(18.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(item.getString("name"), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(
                    if (kind == "mcp") item.optString("url")
                    else "${item.optString("event")} · ${item.optString("action")}",
                    fontSize = 11.sp,
                    color = WhaleMuted,
                    maxLines = 2,
                )
                Row {
                    TextButton(
                        onClick = {
                            if (item.optBoolean("enabled")) {
                                manager.store.saveFromUser(
                                    kind,
                                    JSONObject(item.toString()).put("enabled", false),
                                )
                                revision++
                            } else review = JSONObject(item.toString()).put("enabled", true)
                        },
                    ) {
                        Text(if (item.optBoolean("enabled")) "停用" else "启用")
                    }
                    TextButton(onClick = { edit = JSONObject(item.toString()) }) { Text("编辑") }
                    if (kind == "mcp" && item.optBoolean("enabled"))
                        TextButton(
                            onClick = {
                                jobs.launch {
                                    status = "连接中…"
                                    runCatching {
                                        val task =
                                            PhoneTask(
                                                sessionId = conversation.id,
                                                text = "用户测试 MCP",
                                                images = emptyList(),
                                                model = conversation.model,
                                                effort = conversation.effort,
                                                access = "full",
                                                phoneTools = false,
                                                fileTools = false,
                                                workspace = "",
                                                projectId = conversation.projectId,
                                            )
                                        manager.mcp(
                                            task,
                                            item.getString("id"),
                                            null,
                                            JSONObject(),
                                        ) { _, _ ->
                                            false
                                        }
                                    }
                                        .onSuccess {
                                            status =
                                                "已连接 · ${it.getJSONArray("tools").length()} 个工具"
                                        }
                                        .onFailure { status = it.message.orEmpty() }
                                }
                            },
                        ) {
                            Text("测试")
                        }
                    TextButton(
                        onClick = {
                            manager.store.deleteFromUser(kind, item.getString("id"))
                            manager.secrets.set(item.getString("id"), "")
                            revision++
                        },
                    ) {
                        Text("删除", color = WhaleMuted)
                    }
                }
            }
        }
    }
    if (manager.store.failure.isNotBlank())
        Text(manager.store.failure, color = Color(0xFFB17A2C), fontSize = 12.sp)
    if (status.isNotBlank()) Text(status, color = WhaleMuted, fontSize = 12.sp, lineHeight = 19.sp)
    SettingsLink(AppIcons.Shield, "统一权限规则", "DENY 优先 · 扩展可单独限制") { audit = true }
    SettingsLink(AppIcons.Description, "最近扩展记录", "不记录密钥、工具参数和命令输出") {
        status =
            manager.store
                .audit()
                .let { a ->
                    (maxOf(0, a.length() - 10) until a.length()).reversed().joinToString("\n") {
                        a.getJSONObject(it).let { row ->
                            "${row.optString("event")} · ${row.optString("status")}"
                        }
                    }
                }
                .ifBlank { "暂无记录" }
    }
    edit?.let { item ->
        ExtensionEditor(kind, item, manager, close = { edit = null }) { saved ->
            manager.store.saveFromUser(kind, saved.put("enabled", false))
            edit = null
            review = saved.put("enabled", true)
            revision++
        }
    }
    review?.let { item ->
        WhaleSheet(
            "启用 ${item.optString("name")}？",
            "仅授予下面列出的能力。主 Agent 的权限不会自动传给扩展。",
            { review = null },
        ) {
            ExtensionStore.capabilities(item).forEach {
                Text(
                    it.name,
                    fontFamily = DshaFonts.mono,
                    color = WhaleBlue,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 5.dp),
                )
            }
            if (kind == "hooks" && item.optString("action") == "command")
                Text(
                    "命令在干净的临时虚拟机内运行；只导入获准的当前工作副本，不读取主环境中的其他文件。",
                    fontSize = 12.sp,
                    lineHeight = 20.sp,
                    color = WhaleMuted,
                )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { review = null }) { Text("取消") }
                TextButton(
                    onClick = {
                        runCatching { manager.store.saveFromUser(kind, item, approved = true) }
                            .onSuccess {
                                revision++
                                review = null
                            }
                            .onFailure {
                                status = it.message.orEmpty()
                                review = null
                            }
                    },
                ) {
                    Text("允许并启用")
                }
            }
        }
    }
    if (audit) PermissionRulesEditor(harness.permissions) { audit = false }
}

@Composable
private fun ExtensionEditor(
    kind: String,
    item: JSONObject,
    manager: ExtensionManager,
    close: () -> Unit,
    save: (JSONObject) -> Unit,
) {
    var name by remember { mutableStateOf(item.optString("name")) }
    var url by remember { mutableStateOf(item.optString("url")) }
    var token by remember { mutableStateOf("") }
    var replaceToken by remember { mutableStateOf(false) }
    var config by remember {
        mutableStateOf(
            JSONObject(item.toString())
                .apply {
                    remove("id")
                    remove("name")
                    remove("projectId")
                    remove("enabled")
                    remove("approvedCapabilities")
                }
                .toString(2),
        )
    }
    var ask by remember { mutableStateOf(item.optBoolean("alwaysAsk", true)) }
    var error by remember { mutableStateOf("") }
    WhaleSheet(if (kind == "mcp") "MCP 连接" else "Hook 配置", close = close) {
        Column(
            Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                name,
                { name = it },
                label = { Text("名称") },
                singleLine = true,
                shape = ContinuousShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = UprightSymbols.transformation,
            )
            if (kind == "mcp") {
                OutlinedTextField(
                    url,
                    { url = it },
                    label = { Text("HTTPS MCP 地址") },
                    singleLine = true,
                    shape = ContinuousShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = UprightSymbols.transformation,
                )
                OutlinedTextField(
                    token,
                    {
                        token = it
                        replaceToken = true
                    },
                    label = { Text("Bearer Token · 留空保留原密钥") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    shape = ContinuousShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                ToggleSetting("每次工具调用先确认", "关闭表示信任此服务器的工具；统一权限 DENY 仍然生效", ask) { ask = it }
            } else {
                Text(
                    "event：BeforeAgentStart / AfterAgentFinish / BeforeToolCall / AfterToolCall / BeforeFileWrite / AfterFileWrite / BeforeCommand / AfterCommand\naction：log / context / deny / modify / command\ncontext 与 deny 使用 text；modify 使用 arguments；命令使用 command，并声明 SANDBOX_EXEC。",
                    fontSize = 11.sp,
                    lineHeight = 18.sp,
                    color = WhaleMuted,
                )
                OutlinedTextField(
                    config,
                    { config = it },
                    label = { Text("JSON 配置") },
                    textStyle =
                        LocalTextStyle.current.copy(fontFamily = DshaFonts.mono, fontSize = 12.sp),
                    shape = ContinuousShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 6,
                    visualTransformation = UprightSymbols.transformation,
                )
            }
            if (error.isNotBlank()) Text(error, color = Color(0xFFB17A2C), fontSize = 12.sp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = close) { Text("取消") }
            TextButton(
                onClick = {
                    runCatching {
                        val value =
                            if (kind == "mcp")
                                JSONObject()
                                    .put("url", url.trim())
                                    .put("alwaysAsk", ask)
                                    .put("capabilities", JSONArray(listOf("NETWORK", "MCP_TOOL")))
                            else JSONObject(config)
                        value
                            .put("id", item.getString("id"))
                            .put("name", name.trim())
                            .put("projectId", item.optString("projectId"))
                        require(name.isNotBlank()) { "请输入名称" }
                        if (kind == "mcp") {
                            McpClient.validateEndpoint(url.trim())
                            if (replaceToken)
                                manager.secrets.set(item.getString("id"), token.trim())
                        }
                        save(value)
                    }
                        .onFailure { error = it.message.orEmpty() }
                },
            ) {
                Text("保存并检查权限")
            }
        }
    }
}

@Composable
private fun PermissionRulesEditor(manager: PermissionManager, close: () -> Unit) {
    var text by remember { mutableStateOf(manager.export()) }
    var error by remember { mutableStateOf(manager.loadFailure) }
    WhaleSheet(
        "统一权限规则",
        "JSON 数组。可设置 scope、owner、capability、resource、effect，及可选 actor / extensionId。所有命中的 DENY 优先于 ALLOW。",
        close,
    ) {
        Text(
            "例如：{\"id\":\"no-root\",\"scope\":\"GLOBAL\",\"capability\":\"ROOT_EXEC\",\"effect\":\"DENY\",\"resource\":\"*\"}",
            fontSize = 10.sp,
            lineHeight = 16.sp,
            color = WhaleMuted,
        )
        OutlinedTextField(
            text,
            { text = it },
            textStyle = LocalTextStyle.current.copy(fontFamily = DshaFonts.mono, fontSize = 12.sp),
            shape = ContinuousShape(14.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 340.dp),
            visualTransformation = UprightSymbols.transformation,
        )
        if (error.isNotBlank()) Text(error, fontSize = 12.sp, color = Color(0xFFB17A2C))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = close) { Text("取消") }
            TextButton(
                onClick = {
                    runCatching { manager.saveFromUser(text) }
                        .onSuccess { close() }
                        .onFailure { error = it.message.orEmpty() }
                },
            ) {
                Text("保存")
            }
        }
    }
}
