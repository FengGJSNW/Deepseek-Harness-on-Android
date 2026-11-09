package dev.deepseek.mobile

import android.content.Intent
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun SettingsScreen(onCreatePlugin: () -> Unit, back: () -> Unit) {
    val state = AssistantState
    val c = state.current
    var page by remember { mutableStateOf("main") }
    var dialog by remember { mutableStateOf("") }
    var logout by remember { mutableStateOf(false) }
    var terminal by remember { mutableStateOf(false) }
    var memoryScope by remember { mutableStateOf("") }
    var instructionScope by remember { mutableStateOf("") }
    var dataDialog by remember { mutableStateOf("") }
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    when (dataDialog) {
        "proposals" -> MemoryProposals { dataDialog = "" }
        "approvals" -> ApprovalHistory { dataDialog = "" }
    }
    val titles =
        mapOf(
            "main" to "设置",
            "whale" to "鲸鲸设置",
            "connection" to "账户与连接",
            "permissions" to "手机权限",
            "memory" to "记忆与指令",
            "display" to "外观",
            "agents" to "Agent",
            "identity" to "头像与名称",
            "browser" to "内置浏览器",
            "data" to "数据与备份",
            "about" to "关于",
            "execution" to "执行权限与工具",
            "keys" to "DeepSeek API Key",
            "typography" to "字体与排版",
            "runtime" to "运行环境",
            "extensions" to "扩展",
            "mihomo" to "Mihomo 代理",
        )
    fun parentPage() =
        when (page) {
            "keys" -> "connection"
            "execution" -> "permissions"
            "identity" -> "agents"
            "typography" -> "display"
            else -> "main"
        }
    androidx.activity.compose.BackHandler(page != "main") { page = parentPage() }
    Column(Modifier.fillMaxSize().testTag("settings-screen")) {
        Crossfade(
            targetState = page,
            animationSpec =
                tween(160, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            label = "settings-category",
            modifier = Modifier.weight(1f),
        ) { target ->
            Column(Modifier.fillMaxSize().inactiveIf(target != page)) {
                if (target == "keys") CredentialSettings { page = "connection" }
                else {
                    Row(
                        Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SmallIcon(AppIcons.ArrowBack, "返回") {
                            if (page == "main") back() else page = parentPage()
                        }
                        Text(
                            titles[target].orEmpty(),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Column(
                        Modifier.weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp, vertical = 10.dp),
                        verticalArrangement =
                            Arrangement.spacedBy(if (target == "main") 4.dp else 10.dp),
                    ) {
                        when (target) {
                            "main" -> {
                                SettingsSection("工作")
                                SettingsLink(AppIcons.Agents, "Agent", "执行方式、工具与并发") {
                                    page = "agents"
                                }
                                SettingsLink(AppIcons.TerminalBox, "运行环境", "Linux、终端与工作区") {
                                    page = "runtime"
                                }
                                SettingsLink(AppIcons.Description, "记忆与指令", "长期偏好与项目上下文") {
                                    page = "memory"
                                }
                                SettingsLink(AppIcons.Spark, "扩展", "Agent 插件、MCP、Hooks 与 Skills") {
                                    page = "extensions"
                                }
                                SettingsLink(AppIcons.Browser, "Mihomo 代理", "订阅、节点与网络规则") {
                                    page = "mihomo"
                                }
                                SettingsSection("体验")
                                SettingsLink(AppIcons.Image, "外观", "字体、阅读与思考显示") {
                                    page = "display"
                                }
                                SettingsLink(AppIcons.Bow, "鲸鲸", "互动、音效与气泡") { page = "whale" }
                                SettingsLink(AppIcons.Browser, "内置浏览器", "网页与浏览工具") {
                                    page = "browser"
                                }
                                SettingsSection("账户与数据")
                                SettingsLink(AppIcons.Key, "账户与连接", "API Key 与官方聊天账户") {
                                    page = "connection"
                                }
                                SettingsLink(AppIcons.Shield, "手机权限", "无障碍、悬浮窗与系统通知") {
                                    page = "permissions"
                                }
                                SettingsLink(AppIcons.FolderOpen, "数据与备份", "文件、工作记录与导出") {
                                    page = "data"
                                }
                                SettingsLink(AppIcons.Spark, "关于", "版本、许可与诊断") { page = "about" }
                            }

                            "whale" -> {
                                ToggleSetting("聊天岛上的鲸鲸", "点击互动，拖拽松手回到输入框旁", state.petEnabled) {
                                    state.petEnabled = it
                                    state.persist()
                                }
                                ToggleSetting("音效", "点击与回弹时播放", state.petSoundEnabled) {
                                    state.petSoundEnabled = it
                                    state.persist()
                                }
                                SettingsLink(AppIcons.Chat, "气泡效果", "文案、表情包与触发条件") {
                                    dialog = "bubbles"
                                }
                            }
                            "connection" -> {
                                SettingsLink(AppIcons.Key, "DeepSeek API Key", "名称列表、添加、测试连接") {
                                    page = "keys"
                                }
                                SettingsLink(
                                    AppIcons.Chat,
                                    "DeepSeek Chat 账户",
                                    if (OfficialChatNavigation.accessConfirmed) "已登录 · 退出登录"
                                    else "登录官方账户",
                                ) {
                                    if (OfficialChatNavigation.accessConfirmed) logout = true
                                    else OfficialChatNavigation.requestChat(context)
                                }
                                if (OfficialChatNavigation.failure.isNotBlank())
                                    Text(
                                        OfficialChatNavigation.failure,
                                        fontSize = 12.sp,
                                        color = WhaleMuted,
                                    )
                            }
                            "permissions" -> {
                                SettingsLink(AppIcons.Shield, "授权记录", "本会话的单次授权及中断状态") {
                                    dataDialog = "approvals"
                                }
                                SettingsLink(
                                    accessIcon(c.access),
                                    "执行权限与工具",
                                    accessTitle(c.access),
                                ) {
                                    page = "execution"
                                }
                                SettingsLink(
                                    AppIcons.TouchApp,
                                    "无障碍服务",
                                    if (state.accessibility) "已开启" else "去开启",
                                ) {
                                    MainActivity.current?.requestAccessibility()
                                }
                                SettingsLink(
                                    AppIcons.NotificationsNone,
                                    "消息通知",
                                    if (state.notifications) "已允许" else "去开启",
                                ) {
                                    MainActivity.current?.requestNotifications()
                                }
                                SettingsLink(
                                    AppIcons.BubbleChart,
                                    "悬浮球",
                                    if (state.overlay) "已允许" else "去开启",
                                ) {
                                    AgentService.current?.setFloating(true)
                                }
                                TextButton(onClick = { AgentService.current?.setFloating(false) }) {
                                    Text("关闭悬浮球")
                                }
                            }
                            "execution" -> {
                                SectionLabel("当前对话的操作权限")
                                listOf("none", "ask", "auto", "full").forEach { access ->
                                    OptionRow(
                                        accessIcon(access),
                                        accessTitle(access),
                                        when (access) {
                                            "none" -> "仅聊天，关闭执行工具"
                                            "ask" -> "双重审查，敏感操作向你确认"
                                            "auto" -> "双重审查，由审查 Agent 审批"
                                            else -> "当前授权内直接执行；需独立授权的操作会返回原因"
                                        },
                                        c.access == access,
                                        accessColor(access),
                                    ) {
                                        c.access = access
                                        state.persist()
                                    }
                                }
                                HorizontalDivider(color = WhaleLine)
                                Text("终端与审查配置", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                SettingsLink(
                                    terminalIcon(c.terminalLevel),
                                    "终端运行环境",
                                    dev.deepseek.mobile.terminal.TerminalLevel.of(c.terminalLevel)
                                        .title,
                                ) {
                                    terminal = true
                                }
                                Text(
                                    "命令规则 JSON、审查提示词 TOML 与授权连接均在此管理。",
                                    fontSize = 12.sp,
                                    lineHeight = 20.sp,
                                    color = WhaleMuted,
                                )
                                HorizontalDivider(color = WhaleLine)
                                ToggleSetting("手机操控工具", "无障碍点击、输入与观察界面", c.phoneTools) {
                                    c.phoneTools = it
                                    state.persist()
                                }
                                ToggleSetting(
                                    "工作区文件工具",
                                    if (c.terminalLevel == "sandbox") "访问当前项目 / 会话的 /workspace"
                                    else "仅访问你选择的目录",
                                    c.fileTools,
                                ) {
                                    c.fileTools = it
                                    state.persist()
                                }
                                SettingsLink(
                                    AppIcons.FolderOpen,
                                    "工作区",
                                    if (c.terminalLevel == "sandbox") "/workspace · 独立任务工作副本"
                                    else if (c.workspace.isBlank()) "选择目录"
                                    else
                                        android.net.Uri.decode(c.workspace.substringAfterLast('/')),
                                ) {
                                    if (c.terminalLevel == "sandbox") page = "runtime"
                                    else MainActivity.current?.pickWorkspace()
                                }
                            }
                            "memory" -> {
                                SettingsLink(AppIcons.Description, "记忆提案", "已保存、等待确认与中断的修改") {
                                    dataDialog = "proposals"
                                }
                                SettingsSection("工作指令 · Agent 只读")
                                SettingsLink(AppIcons.Description, "全局工作指令", "所有 Work 会话的工作约定") {
                                    instructionScope = "global"
                                }
                                SettingsLink(AppIcons.Bow, "人格指令", "角色、语气与表达偏好") {
                                    instructionScope = "personality"
                                }
                                if (c.projectId.isNotBlank())
                                    SettingsLink(AppIcons.FolderOpen, "项目工作指令", "项目中的工作约定") {
                                        instructionScope = "project"
                                    }
                                SettingsSection("记忆 · 摘要与按需检索")
                                SettingsLink(AppIcons.Description, "全局记忆", "memory.md · 所有会话共享") {
                                    memoryScope = "global"
                                }
                                SettingsLink(
                                    AppIcons.FolderOpen,
                                    "项目记忆",
                                    if (c.projectId.isBlank()) "此会话尚未加入项目"
                                    else "workplace_memory.md",
                                ) {
                                    if (c.projectId.isNotBlank()) memoryScope = "project"
                                    else state.error = "请先将会话加入项目"
                                }
                                SettingsLink(AppIcons.Chat, "会话记忆", "仅用于当前会话") {
                                    memoryScope = "session"
                                }
                                SettingsLink(AppIcons.Bow, "人格记忆", "所有会话的角色与表达偏好") {
                                    memoryScope = "persona"
                                }
                                SettingsLink(AppIcons.FolderOpen, "打包备份", "记忆、会话、文案与文件") {
                                    dialog = "backup"
                                }
                            }
                            "extensions" -> ExtensionSettings(c, onCreatePlugin)
                            "mihomo" -> MihomoSettings()
                            "runtime" -> RuntimeSettings(c)
                            "typography" -> TypographySettings()
                            "display" -> {
                                SettingsLink(
                                    AppIcons.Spark,
                                    "思考链显示方式",
                                    if (ExperienceSettings.reasoningMode == "island") "中心卡片"
                                    else "鲸鲸气泡",
                                ) {
                                    dialog = "reasoning"
                                }
                                SettingsLink(
                                    AppIcons.Description,
                                    "字体与排版",
                                    "${FontSettings.preset.title} · ${"%.0f".format(FontSettings.scale * 100)}%",
                                ) {
                                    page = "typography"
                                }
                                ToggleSetting(
                                    "左侧任务导航",
                                    "点按预览，拖动松手定位任务段",
                                    ExperienceSettings.taskNavigation,
                                ) {
                                    ExperienceSettings.taskNavigation = it
                                    ExperienceSettings.save()
                                }
                            }
                            "identity" -> AgentIdentitySettings()
                            "agents" -> {
                                SettingsLink(AppIcons.Image, "头像与名称", AgentIdentity.name) {
                                    page = "identity"
                                }
                                SettingsLink(
                                    accessIcon(c.access),
                                    "执行权限与工具",
                                    accessTitle(c.access),
                                ) {
                                    page = "execution"
                                }
                                SettingsLink(
                                    terminalIcon(c.terminalLevel),
                                    "终端运行环境",
                                    dev.deepseek.mobile.terminal.TerminalLevel.of(c.terminalLevel)
                                        .title,
                                ) {
                                    terminal = true
                                }

                                SettingsLink(
                                    AppIcons.Agents,
                                    "并发子智能体",
                                    "最多同时 ${ExperienceSettings.maxAgents} 个",
                                ) {
                                    dialog = "agents"
                                }
                                SettingsLink(AppIcons.Checklist, "查看工作", "本会话的子智能体与结果") {
                                    WorkspaceState.open(WorkspaceTab.Agents)
                                }
                                Text(
                                    "子智能体继承提交任务时的权限。停止主任务会停止所有下属；手机操作共享一条执行通道。",
                                    fontSize = 12.sp,
                                    lineHeight = 20.sp,
                                    color = WhaleMuted,
                                )
                            }
                            "browser" -> {
                                ToggleSetting(
                                    "内置浏览器工具",
                                    "官方搜索与抓取、本机网页操作均无需无障碍",
                                    ExperienceSettings.browserEnabled,
                                ) {
                                    ExperienceSettings.browserEnabled = it
                                    ExperienceSettings.save()
                                }
                                SettingsLink(
                                    AppIcons.Browser,
                                    "浏览器内核",
                                    android.webkit.WebView.getCurrentWebViewPackage()
                                        ?.let { "${it.packageName} ${it.versionName}" }
                                        .orEmpty(),
                                ) {
                                    dialog = "engine"
                                }
                                SettingsLink(
                                    AppIcons.Browser,
                                    "打开内置浏览器",
                                    "当前会话使用${c.browserProvider.label}",
                                ) {
                                    WorkspaceState.openBrowser()
                                }
                            }
                            "data" -> {
                                SettingsLink(AppIcons.FolderOpen, "打包备份", "导出 ZIP 到你选择的位置") {
                                    dialog = "backup"
                                }
                                SettingsLink(AppIcons.Diff, "会话文件改动", "查看本会话任务产生的差异") {
                                    ChangeState.show = true
                                }
                            }
                            "about" -> {
                                Text(
                                    "DSHA  ${BuildConfig.VERSION_NAME}",
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    androidx.compose.ui.res.stringResource(R.string.app_name),
                                    fontSize = 12.sp,
                                    color = WhaleMuted,
                                )
                                SettingsLink(AppIcons.Bow, "素材许可", "哦鲸鲸与开源组件") {
                                    dialog = "license"
                                }
                                SettingsLink(AppIcons.Science, "手机操控实验室", "点击、输入与滑动测试") {
                                    val intent =
                                        context.packageManager.getLaunchIntentForPackage(
                                            "dev.deepseek.mobile.lab",
                                        )
                                    if (intent == null) state.error = "请先安装手机操控实验室 APK"
                                    else
                                        context.startActivity(
                                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                        )
                                }
                                SettingsLink(AppIcons.Reset, "重启消息引擎", "中断当前任务并重新连接") {
                                    dialog = "restart"
                                }
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                    }
                }
            }
        }
    }
    if (logout)
        WhaleSheet(
            "退出 DeepSeek Chat？",
            "退出此设备上的官方账户，随后返回 Work。Work 对话与 API Key 会保留。",
            { logout = false },
        ) {
            Row(Modifier.align(Alignment.End)) {
                TextButton(onClick = { logout = false }) { Text("取消") }
                TextButton(
                    onClick = {
                        OfficialChatNavigation.signOut()
                        logout = false
                    },
                    enabled = !OfficialChatNavigation.authBusy,
                ) {
                    Text("退出登录")
                }
            }
        }
    if (terminal) TerminalSettingsSheet(c) { terminal = false }
    if (instructionScope.isNotEmpty())
        InstructionEditor(instructionScope, c.projectId) { instructionScope = "" }
    if (memoryScope.isNotEmpty())
        MemoryEditor(memoryScope, if (memoryScope == "session") c.id else c.projectId) {
            memoryScope = ""
        }
    if (dialog.isNotEmpty())
        WhaleSheet(
            mapOf(
                    "bubbles" to "气泡效果",
                    "reasoning" to "思考链显示方式",
                    "agents" to "并发子智能体",
                    "text" to "字体与排版",
                    "backup" to "打包备份",
                    "engine" to "浏览器内核",
                    "license" to "素材许可",
                    "restart" to "重启消息引擎",
                )[dialog]
                .orEmpty(),
            close = { dialog = "" },
        ) {
            when (dialog) {
                "bubbles" -> BubbleSettings()
                "reasoning" ->
                    listOf("island" to "中心卡片", "bubble" to "鲸鲸气泡").forEach { (id, title) ->
                        OptionRow(
                            AppIcons.Spark,
                            title,
                            "流式更新，可随时关闭",
                            ExperienceSettings.reasoningMode == id,
                        ) {
                            ExperienceSettings.reasoningMode = id
                            ExperienceSettings.save()
                        }
                    }
                "agents" -> {
                    Text("最多同时 ${ExperienceSettings.maxAgents} 个")
                    Slider(
                        ExperienceSettings.maxAgents.toFloat(),
                        {
                            ExperienceSettings.maxAgents = it.toInt()
                            ExperienceSettings.save()
                        },
                        valueRange = 1f..8f,
                        steps = 6,
                    )
                }
                "text" -> TypographySettings()
                "engine" ->
                    Text(
                        "使用 Android 提供的 Chromium WebView。Chrome 与 Edge 的独立内核不能作为 Android 应用内组件自由切换。当前实际内核：${android.webkit.WebView.getCurrentWebViewPackage()?.packageName}。",
                        fontSize = 13.sp,
                        lineHeight = 21.sp,
                    )

                "backup" -> {
                    Text(
                        "包含会话、全局／项目／会话／人格记忆、文案、文件和改动记录。API Key 不包含在备份中。未被会话或记忆引用的历史内部备份不重复嵌套，原文件保留。",
                        fontSize = 13.sp,
                        lineHeight = 21.sp,
                    )
                    Button(
                        onClick = {
                            AgentService.current?.exportBackup()
                            dialog = ""
                        },
                        modifier = Modifier.padding(top = 14.dp),
                    ) {
                        Text("选择备份位置")
                    }
                }
                "license" ->
                    Text(
                        remember {
                            listOf(
                                    "WHALE-PROVENANCE.md",
                                    "MARKDOWN-LICENSES.txt",
                                    "linux/v86-LICENSE.txt",
                                    "linux/musl-LICENSE.txt",
                                    "console/xterm-LICENSE.txt",
                                    "console/fit-LICENSE.txt",
                                    "math/katex-LICENSE.txt",
                                )
                                .joinToString(
                                    "\n\n",
                                ) { name ->
                                    context.assets.open(name).bufferedReader().use { it.readText() }
                                }
                        },
                        fontSize = 12.sp,
                    )
                "restart" -> {
                    Text("当前未完成的任务将中断，会话会保留。", fontSize = 13.sp)
                    TextButton(
                        onClick = {
                            AgentService.current?.restartEngine()
                            dialog = ""
                        },
                    ) {
                        Text("重启")
                    }
                }
            }
        }
}

@Composable
internal fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        color = WhaleMuted,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(bottom = 10.dp),
    )
}

@Composable
internal fun SettingsLink(
    icon: ImageVector,
    title: String,
    detail: String = "",
    click: () -> Unit,
) {
    Surface(
        onClick = click,
        color = Color.Transparent,
        shape = ContinuousShape(13.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 2.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, Modifier.size(21.dp), tint = Color(0xFF676767))
            Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp)) {
                Text(title, fontSize = 14.sp, lineHeight = 20.sp)
                if (detail.isNotEmpty())
                    Text(
                        detail,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = WhaleMuted,
                        modifier = Modifier.padding(top = 3.dp),
                    )
            }
            Icon(AppIcons.ChevronRight, null, Modifier.size(15.dp), tint = Color(0xFFAFB5C1))
        }
    }
}

@Composable
internal fun ToggleSetting(
    title: String,
    detail: String,
    checked: Boolean,
    change: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, lineHeight = 20.sp)
            Text(
                detail,
                fontSize = 11.sp,
                color = WhaleMuted,
                modifier = Modifier.padding(top = 5.dp),
            )
        }
        WhaleToggle(checked, change, title)
    }
}

@Composable
private fun SettingsSection(title: String) {
    Text(
        title,
        Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 2.dp),
        fontSize = 11.sp,
        color = WhaleMuted,
        fontWeight = FontWeight.Medium,
    )
}
