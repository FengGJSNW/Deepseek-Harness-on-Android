package dev.deepseek.mobile

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.deepseek.mobile.terminal.*
import kotlinx.coroutines.*

@Composable
internal fun TerminalCard(id: String) {
    val manager = AgentService.current?.terminal
    val run = manager?.runs?.get(id)
    if (run == null) {
        Text("终端记录暂不可用", color = WhaleMuted, fontSize = 12.sp)
        return
    }
    var commandExpanded by remember(id) { mutableStateOf(false) }
    var outputExpanded by remember(id) { mutableStateOf(false) }
    var copying by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val outputHeight by
        animateDpAsState(
            if (outputExpanded) 420.dp else 125.dp,
            UiSpring.disclosure(),
            label = "terminal-output-height",
        )
    Column(
        Modifier.fillMaxWidth()
            .background(ReadingStyle.workSurface, ContinuousShape(16.dp))
            .border(.7.dp, ReadingStyle.workLine, ContinuousShape(16.dp))
            .padding(12.dp),
    ) {
        if (run.level == "sandbox")
            Text(
                "Linux Sandbox",
                fontSize = 10.sp,
                color = WhaleMuted,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        Row(verticalAlignment = Alignment.Top) {
            SelectionContainer(Modifier.weight(1f)) {
                Text(
                    "$ ${run.command}",
                    fontFamily = DshaFonts.mono,
                    color = WhaleInk,
                    fontSize = 12.sp,
                    lineHeight = 19.sp,
                    maxLines = if (commandExpanded) Int.MAX_VALUE else 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            Box {
                IconButton(onClick = { copying = true }, modifier = Modifier.size(30.dp)) {
                    Icon(
                        AppIcons.ContentCopy,
                        "复制命令或执行结果",
                        Modifier.size(15.dp),
                        tint = WhaleMuted,
                    )
                }
                DropdownMenu(copying, { copying = false }) {
                    DropdownMenuItem(
                        text = { Text("复制命令") },
                        onClick = {
                            clipboard.setText(AnnotatedString(run.command))
                            copying = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("复制执行结果") },
                        onClick = {
                            clipboard.setText(
                                AnnotatedString(
                                    run.output +
                                        if (run.stderr.isBlank()) "" else "\n" + run.stderr,
                                ),
                            )
                            copying = false
                        },
                    )
                }
            }
        }
        TextButton(onClick = { commandExpanded = !commandExpanded }) {
            Text(
                if (commandExpanded) "收起命令" else "完整命令",
                fontSize = 10.sp,
                color = WhaleMuted,
            )
        }
        SelectionContainer {
            Column(
                Modifier.fillMaxWidth()
                    .heightIn(max = outputHeight)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    run.output.ifBlank { if (run.status == "running") "等待输出…" else "（无标准输出）" },
                    fontFamily = DshaFonts.mono,
                    color = WhaleInk,
                    fontSize = 11.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    softWrap = false,
                )
                if (run.stderr.isNotBlank())
                    Text(
                        run.stderr,
                        fontFamily = DshaFonts.mono,
                        color = Color(0xFFB05B50),
                        fontSize = 11.sp,
                    )
            }
        }
        if (run.stage == "runtime" && run.level == "sandbox") {
            val runtimeStates by manager.harness.runtimes.states.collectAsState()
            val session = AssistantState.sessions.firstOrNull { it.id == run.sessionId }
            val domainKey =
                if (session?.projectId?.isNotBlank() == true) "project:${session.projectId}"
                else "session:${run.sessionId}"
            Text(
                runtimeStates[domainKey]?.detail ?: "准备运行环境…",
                fontSize = 11.sp,
                color = WhaleMuted,
            )
        }
        if (run.reason.isNotBlank())
            Text(
                run.reason,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                fontSize = 10.sp,
                color = WhaleMuted,
            )
        if (run.executionStarted == false && run.status in setOf("failed", "denied"))
            Text("命令未启动 · ${run.errorCode}", fontSize = 10.sp, color = Color(0xFFB05B50))
        run.verifiedUid?.let { Text("执行通道 UID $it", fontSize = 10.sp, color = WhaleMuted) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { outputExpanded = !outputExpanded }) {
                Text(
                    if (outputExpanded) "收起输出" else "展开输出",
                    fontSize = 10.sp,
                    color = WhaleMuted,
                )
            }
            if (run.status in setOf("running", "reviewing", "waiting"))
                TextButton(onClick = { manager.stop(id) }) {
                    Text("停止", color = Color(0xFFB05B50), fontSize = 10.sp)
                }
            Spacer(Modifier.weight(1f))
            Text(
                (if (run.exitCode != null) "exit ${run.exitCode} · " else "") +
                    when (run.status) {
                        "completed" -> "已完成"
                        "running" -> "运行中"
                        "reviewing" -> "审查中"
                        "waiting" -> "等待批准"
                        "denied" -> "已拒绝"
                        "cancelled" -> "已停止"
                        "failed" -> "执行失败"
                        "interrupted" -> "已中断"
                        else -> "执行结束"
                    },
                fontSize = 10.sp,
                color = WhaleMuted,
            )
        }
    }
}

@Composable
internal fun TerminalSettingsSheet(c: Conversation, close: () -> Unit) {
    val context = LocalContext.current
    val manager = AgentService.current?.terminal
    val store = remember(context) { TerminalConfigStore(context) }
    var tab by remember { mutableStateOf("environment") }
    val editors = remember { mutableMapOf<String, MutableState<String>>() }
    var text by
        remember(c.terminalLevel, tab) {
            editors.getOrPut("${c.terminalLevel}/$tab") {
                mutableStateOf(
                    when (tab) {
                        "json" -> store.load(c.terminalLevel).rules
                        "toml" -> store.load(c.terminalLevel).reviewer
                        else -> ""
                    },
                )
            }
        }
    var message by remember { mutableStateOf("") }
    var inspectCommand by remember { mutableStateOf("pwd; ls") }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    WhaleSheet("对话终端", "运行权限和执行审批分开选择。设置用于之后提交的任务。", close) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("environment" to "运行环境", "json" to "命令 JSON", "toml" to "审查 TOML").forEach {
                (key, title) ->
                FilterChip(
                    selected = tab == key,
                    onClick = {
                        tab = key
                        message = ""
                    },
                    label = { Text(title, fontSize = 11.sp) },
                )
            }
        }
        if (tab == "environment") {
            TerminalLevel.entries
                .filter { it != TerminalLevel.ISOLATED }
                .forEachIndexed { index, level ->
                    Surface(
                        onClick = {
                            selectTerminalLevel(c, level)
                        },
                        color =
                            if (c.terminalLevel == level.key) Color(0xFFEEF4FF) else Color.White,
                        shape = ContinuousShape(16.dp),
                        border =
                            BorderStroke(
                                1.dp,
                                if (c.terminalLevel == level.key) Color(0xFFB9D0FF) else WhaleLine,
                            ),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    ) {
                        Row(
                            Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "0${index + 1}",
                                color = WhaleBlue,
                                fontFamily = DshaFonts.mono,
                                fontSize = 13.sp,
                            )
                            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                                Text(
                                    level.title,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    level.detail,
                                    color = WhaleMuted,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                            if (c.terminalLevel == level.key)
                                Icon(
                                    AppIcons.Check,
                                    "已选择",
                                    Modifier.size(18.dp),
                                    tint = WhaleBlue,
                                )
                        }
                    }
                }
            val level = TerminalLevel.of(c.terminalLevel)
            val home = manager?.workspace(level, c.id).orEmpty()
            SelectionContainer {
                Text(
                    home,
                    fontSize = 10.sp,
                    color = WhaleMuted,
                    fontFamily = DshaFonts.mono,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
            Text(
                when (level) {
                    TerminalLevel.SANDBOX ->
                        "项目或会话独立的 Linux 环境。工具与包修改保存在当前环境；命令使用独立工作树，完成后可查看并合并。首次准备会下载并校验发行版与工具。"
                    TerminalLevel.ISOLATED ->
                        "虚拟路径映射到应用私有文件。仅当前会话可写，其他会话文件只读；不暴露 API Key、审查配置或会话数据库。不运行外部程序。"
                    TerminalLevel.APP ->
                        "以本应用 UID 运行真实 sh；其他应用私有目录由 Android 隔离。系统允许的共享文件仍可能可访问。JSON 文件规则是命令预检，不是额外的内核沙箱。"
                    TerminalLevel.SHELL ->
                        "授权后自动绑定并验证 UID 2000；Shizuku 以 Root 启动时，先将独立执行进程降为 Shell 身份。"
                    TerminalLevel.ROOT_GUARDED ->
                        "只允许当前会话工作区可写；隔离 /proc、/dev，禁用网络和提权通道。需要内核、Root 管理器与 SELinux 支持；任一隔离步骤失败直接拒绝。"
                    TerminalLevel.ROOT -> "由 Magisk、SukiSU Ultra 等管理器授予 UID 0。与完全访问模式组合时，命令跳过双重审查。"
                },
                fontSize = 12.sp,
                lineHeight = 19.sp,
                color = WhaleMuted,
            )
            if (
                level in setOf(TerminalLevel.SHELL, TerminalLevel.ROOT, TerminalLevel.ROOT_GUARDED)
            ) {
                manager?.connections?.let { TerminalConnectionPanel(level, it) }
            }
            OutlinedTextField(
                inspectCommand,
                { inspectCommand = it },
                label = { Text("命令预检（不执行、不联网）") },
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
                maxLines = 3,
                visualTransformation = UprightSymbols.transformation,
            )
            TextButton(
                onClick = {
                    try {
                        val result =
                            TerminalPolicy(store.load(c.terminalLevel).rules).let {
                                if (c.terminalLevel == "sandbox")
                                    it.inspectSandbox(inspectCommand, home, home)
                                else it.inspect(inspectCommand, home, home)
                            }
                        message =
                            "${result.decision}\n${result.reasons.joinToString("\n").ifBlank { "静态检查通过，执行时仍需独立审查" }}"
                    } catch (e: Exception) {
                        message = e.message.orEmpty()
                    }
                },
            ) {
                Text("检查命令结构与规则")
            }
            TextButton(
                onClick = {
                    manager?.revokeGrants()
                    message = "已清除所有类似命令授权"
                },
            ) {
                Text("撤销类似命令授权")
            }
        } else {
            Text(
                if (tab == "json") "${c.terminalLevel}.commands.json · deny 优先，sensitive 需要审批"
                else "${c.terminalLevel}.reviewer.toml · 独立临时审查会话，无工具、无主会话历史",
                fontSize = 11.sp,
                lineHeight = 18.sp,
                color = WhaleMuted,
                modifier = Modifier.padding(vertical = 10.dp),
            )
            OutlinedTextField(
                text,
                { text = it },
                modifier = Modifier.fillMaxWidth().height(320.dp),
                textStyle =
                    androidx.compose.ui.text.TextStyle(
                        fontFamily = DshaFonts.mono,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    ),
                visualTransformation = UprightSymbols.transformation,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = {
                        MainActivity.current?.importTerminalConfig { imported ->
                            text = imported
                            message = "已载入编辑器，校验并保存后生效"
                        }
                    },
                ) {
                    Text("导入文件")
                }
                TextButton(
                    onClick = {
                        MainActivity.current?.exportTerminalConfig(
                            "${c.terminalLevel}.${if (tab == "json") "commands.json" else "reviewer.toml"}",
                            text,
                        )
                    },
                ) {
                    Text("导出文件")
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(text))
                        message = "已复制配置"
                    },
                ) {
                    Text("复制")
                }
                TextButton(
                    onClick = {
                        text =
                            if (tab == "json") TerminalPolicy.defaults()
                            else ReviewerConfig.defaultText
                        message = "默认配置已载入编辑器，保存后生效"
                    },
                ) {
                    Text("默认")
                }
                Button(
                    onClick = {
                        try {
                            val old = store.load(c.terminalLevel)
                            store.save(
                                if (tab == "json") old.copy(rules = text)
                                else old.copy(reviewer = text),
                            )
                            manager?.revokeGrants()
                            message = "配置已校验并保存；用于新任务"
                        } catch (e: Exception) {
                            message = "未保存：${e.message}"
                        }
                    },
                ) {
                    Text("校验并保存")
                }
            }
            Text(
                "路径规则支持 r/w/x、recursive 与 \${workspace}；命令和参数规则支持 allow/sensitive/deny。审查模式仅执行已建模的命令和参数，无法分析的写法直接拒绝。",
                fontSize = 11.sp,
                lineHeight = 18.sp,
                color = WhaleMuted,
            )
        }
        if (message.isNotBlank())
            SelectionContainer {
                Text(
                    message,
                    fontSize = 12.sp,
                    lineHeight = 19.sp,
                    color = WhaleBlue,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
    }
}
