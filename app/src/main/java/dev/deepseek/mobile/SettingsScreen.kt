package dev.deepseek.mobile

import android.content.Intent
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun SettingsScreen(
    entry: String = "main",
    onCreatePlugin: () -> Unit,
    openSandboxTerminal: (String) -> Unit = WorkspaceState::openUserTerminal,
    back: () -> Unit,
) {
    val state = AssistantState
    val c = state.current
    val rootPage = if (entry == "toolbox") "main" else entry
    var page by remember(entry) { mutableStateOf(rootPage) }
    var dialog by remember { mutableStateOf("") }
    var memorySummary by remember { mutableStateOf(false) }
    var logout by remember { mutableStateOf(false) }
    var terminal by remember { mutableStateOf(false) }
    var memoryScope by remember { mutableStateOf("") }
    var instructionScope by remember { mutableStateOf("") }
    var dataDialog by remember { mutableStateOf("") }
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    when (dataDialog) {
        "approvals" -> ApprovalHistory { dataDialog = "" }
    }
    val titles =
        mapOf(
            "main" to "设置",
            "whale" to "桌宠",
            "sandbox" to "Sandbox",
            "remote" to "Remote",
            "remoteDsh" to "Remote DSH",
            "remoteSsh" to "Remote SSH",
            "remoteComputer" to "Remote Computer",
            "connection" to "API Key",
            "permissions" to "权限",
            "memory" to "记忆",
            "display" to "外观",
            "glass" to "液态玻璃（实验性）",
            "agents" to "Agent 行为",
            "identity" to "头像与名称",
            "browser" to "内置浏览器",
            "data" to "数据备份",
            "about" to "关于",
            "execution" to "执行权限与工具",
            "keys" to "DeepSeek API Key",
            "typography" to "字体",
            "extensions" to "扩展",
            "mihomo" to "Mihomo 代理",
            "taskPower" to "后台保护",
        )
    fun parentPage() =
        when (page) {
            "keys" -> "connection"
            "remoteDsh",
            "remoteSsh",
            "remoteComputer" -> "remote"
            "execution" -> "permissions"
            "taskPower" -> "main"
            "identity",
            "glass",
            "typography",
            "whale" -> "display"
            "extensions",
            "mihomo" -> "main"
            else -> rootPage
        }
    androidx.activity.compose.BackHandler(page != rootPage) { page = parentPage() }
    Column(Modifier.fillMaxSize().background(DshaSurface.page).testTag("settings-screen")) {
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
                    FadingHeaderLayout(
                        header = {
                            Row(
                                Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                SmallIcon(AppIcons.ArrowBack, "返回") {
                                    if (page == rootPage) back() else page = parentPage()
                                }
                                Text(
                                    titles[target].orEmpty(),
                                    modifier = Modifier.padding(start = 8.dp),
                                    style = PrismTypography.pageTitle,
                                )
                            }
                        },
                    ) { topInset ->
                        Column(
                            Modifier.fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(
                                    start = 16.dp,
                                    end = 16.dp,
                                    top = topInset + 10.dp,
                                    bottom = 10.dp,
                                ),
                            verticalArrangement =
                                Arrangement.spacedBy(if (target == "main") 4.dp else 10.dp),
                        ) {
                            if (target == "typography")
                                Column(Modifier.padding(horizontal = 24.dp)) {
                                    TypographyPreview()
                                }
                            when (target) {
                                "glass" -> GlassAppearanceSettings()
                                "main" -> {
                                    SettingsSection("偏好")
                                    SettingsGroup {
                                        SettingsLink(AppIcons.Image, "外观", "桌宠、字体与阅读") {
                                            page = "display"
                                        }
                                        SettingsLink(AppIcons.Description, "记忆", "管理、全局记忆与摘要") {
                                            page = "memory"
                                        }
                                    }
                                    SettingsSection("工作与连接")
                                    SettingsGroup {
                                        SettingsLink(AppIcons.Key, "API Key", "DeepSeek 密钥与官方账户") {
                                            page = "connection"
                                        }
                                        SettingsLink(
                                            AppIcons.Agents,
                                            "Agent 行为",
                                            "Ultra 并发数量",
                                        ) {
                                            page = "agents"
                                        }
                                        SettingsLink(AppIcons.Shield, "权限", "手机和执行权限") {
                                            page = "permissions"
                                        }
                                        SettingsLink(AppIcons.Bolt, "后台保护", "会话和 Sandbox 的后台运行") {
                                            page = "taskPower"
                                        }
                                    }
                                    SettingsSection("工具与环境")
                                    SettingsGroup {
                                        SettingsLink(AppIcons.Globe, "FlClash", "配置、节点、规则与代理权限") {
                                            dev.deepseek.mobile.proxy.FlClashRuntime.get(context)
                                                .open()
                                        }
                                        SettingsLink(AppIcons.TerminalBox, "Sandbox", "沙箱与会话关联") {
                                            page = "sandbox"
                                        }
                                        SettingsLink(
                                            AppIcons.Browser,
                                            "内置浏览器",
                                            "DeepSeek 官方搜索与内置 Chrome",
                                        ) {
                                            page = "browser"
                                        }
                                        SettingsLink(
                                            AppIcons.Spark,
                                            "扩展",
                                            "Agent 插件、MCP、Hooks 与 Skills",
                                        ) {
                                            page = "extensions"
                                        }
                                    }
                                    SettingsSection("应用")
                                    SettingsGroup {
                                        SettingsLink(AppIcons.FolderOpen, "数据备份", "下载或导出备份") {
                                            page = "data"
                                        }
                                        SettingsLink(AppIcons.Spark, "关于", "版本与许可") {
                                            page = "about"
                                        }
                                    }
                                }
                                "sandbox" -> SandboxSettings(c, openSandboxTerminal)
                                "remote" -> RemoteOverview { page = it }
                                "remoteDsh" -> RemotePairingSettings(RemoteTarget.DSH)
                                "remoteSsh" -> RemotePairingSettings(RemoteTarget.SSH)
                                "remoteComputer" -> RemotePairingSettings(RemoteTarget.COMPUTER)
                                "whale" -> {
                                    ToggleSetting("桌宠开关", "在输入框旁显示桌宠", state.petEnabled) {
                                        state.petEnabled = it
                                        state.persist()
                                    }
                                    if (state.petEnabled) {
                                        SettingsChoice(
                                            AppIcons.Bow,
                                            "桌宠形象",
                                            if (state.petStyle == "animated") "鲸鲸动图" else "鲸鲸",
                                        ) { dismiss ->
                                            for ((id, name) in
                                                listOf("ohjing" to "鲸鲸", "animated" to "鲸鲸动图")) {
                                                GlassMenuRow(
                                                    AppIcons.Bow,
                                                    name,
                                                    "",
                                                    state.petStyle == id,
                                                ) {
                                                    state.petStyle = id
                                                    state.persist()
                                                    dismiss()
                                                }
                                            }
                                        }
                                        ToggleSetting("音效", "点击与回弹时播放", state.petSoundEnabled) {
                                            state.petSoundEnabled = it
                                            state.persist()
                                        }
                                        SettingsSection("动画设置")
                                        WhaleMotionSettings()
                                        SettingsSection("气泡设置")
                                        BubbleSettings()
                                    }
                                }
                                "connection" -> {
                                    SettingsGroup {
                                        SettingsLink(
                                            AppIcons.Key,
                                            "DeepSeek API Key",
                                            "名称列表、添加、测试连接",
                                        ) {
                                            page = "keys"
                                        }
                                        SettingsLink(
                                            AppIcons.Chat,
                                            "DeepSeek Chat 账户",
                                            if (OfficialChatNavigation.accessConfirmed) "已登录 · 退出登录"
                                            else "登录官方账户",
                                        ) {
                                            if (OfficialChatNavigation.accessConfirmed)
                                                logout = true
                                            else OfficialChatNavigation.requestChat(context)
                                        }
                                    }
                                    if (OfficialChatNavigation.failure.isNotBlank())
                                        Text(
                                            OfficialChatNavigation.failure,
                                            fontSize = 12.sp,
                                            color = WhaleMuted,
                                        )
                                }
                                "permissions" -> {
                                    SettingsSection("手机权限")
                                    SettingsGroup {
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
                                            MainActivity.current?.requestFloating()
                                        }
                                    }
                                    DshaButton(
                                        style = DshaButtonStyle.Plain,
                                        onClick = { AgentService.current?.setFloating(false) },
                                    ) {
                                        Text("关闭悬浮球")
                                    }
                                }
                                "execution" -> {
                                    SectionLabel("当前对话的操作权限")
                                    SettingsGroup {
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
                                    }
                                    HorizontalDivider(color = WhaleLine)
                                    Text(
                                        "终端与审查配置",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    SettingsLink(
                                        terminalIcon(c.terminalLevel),
                                        "终端运行环境",
                                        dev.deepseek.mobile.terminal.TerminalLevel.of(
                                                c.terminalLevel,
                                            )
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
                                    SettingsGroup {
                                        ToggleSetting("手机操控工具", "无障碍点击、输入与观察界面", c.phoneTools) {
                                            c.phoneTools = it
                                            state.persist()
                                        }
                                        ToggleSetting(
                                            "工作区文件工具",
                                            if (c.sandboxEnabled) "访问已授权 Sandbox 的 /workspace"
                                            else "仅访问你选择的目录",
                                            c.fileTools,
                                        ) {
                                            c.fileTools = it
                                            state.persist()
                                        }
                                        SettingsLink(
                                            AppIcons.FolderOpen,
                                            "工作区",
                                            if (c.sandboxEnabled) "/workspace · Sandbox 访问授权"
                                            else if (c.workspace.isBlank()) "选择目录"
                                            else
                                                android.net.Uri.decode(
                                                    c.workspace.substringAfterLast('/'),
                                                ),
                                        ) {
                                            if (c.sandboxEnabled)
                                                WorkspaceState.open(WorkspaceTab.SandboxAccess)
                                            else MainActivity.current?.pickWorkspace()
                                        }
                                    }
                                }
                                "memory" -> {
                                    SettingsSection("管理")
                                    SettingsGroup {
                                        SettingsLink(
                                            AppIcons.FolderOpen,
                                            "打包备份",
                                            "将全部本机会话保存到 Download",
                                        ) {
                                            dialog = "download-backup"
                                        }
                                        ToggleSetting(
                                            "启用记忆",
                                            "允许 Agent 检索、使用与保存长期记忆",
                                            ExperienceSettings.memoryEnabled,
                                        ) {
                                            ExperienceSettings.memoryEnabled = it
                                            ExperienceSettings.save()
                                        }
                                    }
                                    SettingsSection("记忆详情")
                                    SettingsGroup {
                                        SettingsLink(AppIcons.Description, "全局记忆", "所有会话共享") {
                                            memoryScope = "global"
                                        }
                                        SettingsChoice(AppIcons.Bow, "人格设置", "角色、语气与长期表达偏好") {
                                            dismiss ->
                                            GlassMenuRow(AppIcons.Bow, "人格指令", "角色、语气与表达偏好") {
                                                dismiss()
                                                instructionScope = "personality"
                                            }
                                            GlassMenuRow(AppIcons.Description, "人格记忆", "长期角色偏好") {
                                                dismiss()
                                                memoryScope = "persona"
                                            }
                                        }
                                        SettingsLink(AppIcons.Spark, "摘要生成", "汇总全局记忆、人格设置与项目记忆") {
                                            memorySummary = true
                                        }
                                        SettingsLink(
                                            AppIcons.Description,
                                            "全局工作指令",
                                            "所有 Work 会话的工作约定",
                                        ) {
                                            instructionScope = "global"
                                        }
                                        if (c.projectId.isNotBlank())
                                            SettingsLink(
                                                AppIcons.FolderOpen,
                                                "项目工作指令",
                                                "项目中的工作约定",
                                            ) {
                                                instructionScope = "project"
                                            }
                                    }
                                }
                                "extensions" -> ExtensionSettings(c, onCreatePlugin)
                                "taskPower" -> TaskPowerSettings(c)
                                "mihomo" ->
                                    SettingsLink(
                                        AppIcons.Globe,
                                        "FlClash",
                                        "代理配置已统一由内置 FlClash 管理",
                                    ) {
                                        dev.deepseek.mobile.proxy.FlClashRuntime.get(context).open()
                                    }
                                "typography" -> TypographySettings()
                                "display" -> {
                                    SettingsGroup {
                                        SettingsLink(AppIcons.Image, "液态玻璃（实验性）", "默认毛玻璃，可调整透明度") {
                                            page = "glass"
                                        }
                                    }
                                    SettingsGroup {
                                        SettingsLink(AppIcons.Bow, "桌宠", "形象、音效、动画与气泡") {
                                            page = "whale"
                                        }
                                        SettingsLink(
                                            AppIcons.Description,
                                            "字体",
                                            FontSettings.preset.title,
                                        ) {
                                            page = "typography"
                                        }
                                        SettingsLink(AppIcons.Image, "头像与名称", AgentIdentity.name) {
                                            page = "identity"
                                        }
                                        SettingsChoice(
                                            AppIcons.Spark,
                                            "思考链显示方式",
                                            if (ExperienceSettings.reasoningMode == "island") "中心卡片"
                                            else "鲸鲸气泡",
                                        ) { dismiss ->
                                            listOf("island" to "中心卡片", "bubble" to "鲸鲸气泡")
                                                .forEach { (id, title) ->
                                                    GlassMenuRow(
                                                        AppIcons.Spark,
                                                        title,
                                                        "流式更新，可随时关闭",
                                                        ExperienceSettings.reasoningMode == id,
                                                    ) {
                                                        ExperienceSettings.reasoningMode = id
                                                        ExperienceSettings.save()
                                                        dismiss()
                                                    }
                                                }
                                        }
                                    }
                                    SettingsSection("交互")
                                    SettingsGroup {
                                        ToggleSetting(
                                            "触感反馈",
                                            "开关、换挡与发送时轻振动，遵循系统设置",
                                            ExperienceSettings.hapticsEnabled,
                                        ) {
                                            ExperienceSettings.hapticsEnabled = it
                                            ExperienceSettings.save()
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
                                }
                                "identity" -> AgentIdentitySettings()
                                "agents" -> {
                                    Text(
                                        "Ultra 并发量 · 最多同时 ${ExperienceSettings.maxAgents} 个",
                                        fontSize = 14.sp,
                                    )
                                    CleanSlider(
                                        ExperienceSettings.maxAgents.toFloat(),
                                        { ExperienceSettings.maxAgents = it.toInt() },
                                        valueRange = 1f..8f,
                                        steps = 6,
                                        onValueChangeFinished = ExperienceSettings::save,
                                    )
                                }
                                "browser" -> {
                                    SettingsSection("Agent 浏览能力")
                                    SettingsGroup {
                                        BrowserProvider.entries.forEach { mode ->
                                            OptionRow(
                                                AppIcons.Browser,
                                                mode.label,
                                                when (mode) {
                                                    BrowserProvider.LOCAL -> "使用应用内浏览器打开网页"
                                                    BrowserProvider.OFFICIAL ->
                                                        "使用 DeepSeek 官方搜索和网页读取 API"
                                                    BrowserProvider.BOTH -> "同时提供应用内浏览器和官方 API"
                                                    BrowserProvider.BLOCKED -> "关闭 Agent 的浏览和搜索工具"
                                                },
                                                ExperienceSettings.browserMode == mode,
                                            ) {
                                                ExperienceSettings.browserMode = mode
                                                if (!mode.chrome) WorkspaceState.closeChromePages()
                                                ExperienceSettings.save()
                                            }
                                        }
                                    }
                                }
                                "data" -> {
                                    SettingsGroup {
                                        SettingsLink(AppIcons.FolderOpen, "保存到下载", "将本机会话打包为 ZIP") {
                                            dialog = "download-backup"
                                        }
                                        SettingsLink(AppIcons.FolderOpen, "打包备份", "选择 ZIP 的保存位置") {
                                            dialog = "backup"
                                        }
                                        SettingsLink(AppIcons.Diff, "会话文件改动", "查看本会话任务产生的差异") {
                                            ChangeState.show = true
                                        }
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
                                    SettingsGroup {
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
                            }
                            Spacer(Modifier.height(20.dp))
                        }
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
                DshaButton(style = DshaButtonStyle.Plain, onClick = { logout = false }) {
                    Text("取消")
                }
                DshaButton(
                    style = DshaButtonStyle.Plain,
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
        MemoryEditor(memoryScope, c.projectId) {
            memoryScope = ""
        }
    if (memorySummary) MemorySummarySheet { memorySummary = false }

    if (dialog.isNotEmpty())
        WhaleSheet(
            mapOf(
                    "download-backup" to "打包备份",
                    "personality" to "人格设置",
                    "reasoning" to "思考链显示方式",
                    "backup" to "打包备份",
                    "engine" to "浏览器内核",
                    "license" to "素材许可",
                    "restart" to "重启消息引擎",
                )[dialog]
                .orEmpty(),
            close = { dialog = "" },
        ) {
            when (dialog) {
                "engine" ->
                    Text(
                        "使用 Android 提供的 Chromium WebView。Chrome 与 Edge 的独立内核不能作为 Android 应用内组件自由切换。当前实际内核：${android.webkit.WebView.getCurrentWebViewPackage()?.packageName}。",
                        fontSize = 13.sp,
                        lineHeight = 21.sp,
                    )

                "backup",
                "download-backup" -> {
                    Text(
                        "包含会话、全局／项目／会话／人格记忆、文案、文件和改动记录。API Key 不包含在备份中。未被会话或记忆引用的历史内部备份不重复嵌套，原文件保留。",
                        fontSize = 13.sp,
                        lineHeight = 21.sp,
                    )
                    DshaButton(
                        onClick = {
                            AgentService.current?.exportBackup(
                                downloads = dialog == "download-backup",
                            )
                            dialog = ""
                        },
                        modifier = Modifier.padding(top = 14.dp),
                    ) {
                        Text(if (dialog == "download-backup") "保存到下载" else "选择备份位置")
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
                    DshaButton(
                        style = DshaButtonStyle.Plain,
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
    DshaInteractiveRow(icon, title, detail, tint = Color(0xFF676767), onClick = click)
}

@Composable
internal fun ToggleSetting(
    title: String,
    detail: String,
    checked: Boolean,
    enabled: Boolean = true,
    change: (Boolean) -> Unit,
) {
    val haptics = rememberUiHaptics()
    val interactions = remember { MutableInteractionSource() }
    Row(
        Modifier.groupRow(16.dp)
            .fillMaxWidth()
            .then(
                if (LocalDshaCard.current) Modifier
                else Modifier.clip(ContinuousShape(24.dp)).background(DshaSurface.card),
            )
            .dshaPressFeedback(interactions)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                interactionSource = interactions,
                indication = null,
            ) {
                if (it != checked) {
                    change(it)
                    haptics.toggle(it)
                }
            }
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = PrismTypography.body)
            Text(
                detail,
                style = PrismTypography.footnote,
                color = WhaleMuted,
                modifier = Modifier.padding(top = 5.dp),
            )
        }
        DshaSwitch(
            checked,
            onCheckedChange = null,
            enabled = enabled,
            interactionSource = interactions,
        )
    }
}

@Composable
private fun SettingsSection(title: String) {
    Text(
        title,
        Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, bottom = 6.dp),
        style = PrismTypography.footnote,
        color = WhaleMuted,
    )
}
