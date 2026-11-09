package dev.deepseek.mobile

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

internal object ReasoningState {
    var selected by mutableStateOf<ChatEntry?>(null)
    var owner by mutableStateOf("")

    fun open(entry: ChatEntry, agent: String = "") {
        selected = entry
        owner = agent
    }

    fun text(): String {
        val messages =
            if (owner.isBlank()) AssistantState.current.messages
            else TeamState.agents.firstOrNull { it.id == owner }?.messages
        return if (selected?.type == "stream_reasoning")
            messages?.lastOrNull { it.type in setOf("stream_reasoning", "reasoning") }?.text
                ?: selected?.text.orEmpty()
        else selected?.text.orEmpty()
    }
}

/** Task IDs keep replies with their originating prompt even when later prompts are queued. */
internal fun conversationGroups(messages: List<ChatEntry>): List<List<ChatEntry>> {
    val buckets = linkedMapOf<String, MutableList<ChatEntry>>()
    var current = "legacy-0"
    var legacy = 0
    messages.forEach { entry ->
        if (entry.taskId.isNotBlank()) current = "task-${entry.taskId}"
        else if (entry.type == "user") current = "legacy-${++legacy}"
        buckets.getOrPut(current) { mutableListOf() }.add(entry)
    }
    return buckets.values.flatMap { entries ->
        if (entries.first().type == "user")
            listOf(listOf(entries.first()), entries.drop(1)).filter { it.isNotEmpty() }
        else listOf(entries.toList())
    }
}

internal fun conversationGroupKeys(groups: List<List<ChatEntry>>): List<String> {
    val counts = mutableMapOf<String, Int>()
    return groups.mapIndexed { index, entries ->
        val task = entries.firstOrNull { it.taskId.isNotBlank() }?.taskId
        if (task == null) "legacy:$index"
        else {
            val ordinal = counts.getOrDefault(task, 0)
            counts[task] = ordinal + 1
            "$task:$ordinal"
        }
    }
}

@Composable
internal fun AgentTimeline(
    entries: List<ChatEntry>,
    name: String = AgentIdentity.name,
    showHeader: Boolean = true,
    owner: String = "",
    completed: Boolean = false,
) {
    val finalIndex = entries.indexOfLast {
        it.type == "assistant" &&
            (it.text.isNotBlank() || it.files.isNotEmpty() || it.images.isNotEmpty())
    }
    val fold = completed && finalIndex > 0
    var expanded by
        androidx.compose.runtime.saveable.rememberSaveable(
            owner,
            entries.firstOrNull()?.taskId,
            completed,
        ) {
            mutableStateOf(false)
        }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showHeader)
            Row(verticalAlignment = Alignment.CenterVertically) {
                AgentAvatar(28.dp)
                Text(name, Modifier.padding(start = 8.dp), fontSize = 12.sp, color = WhaleMuted)
            }
        if (fold) {
            val count = entries.take(finalIndex).count { it.type in setOf("tool", "terminal") }
            ActivityCard(Modifier.testTag("work-details-card")) {
                DisclosureHeader(
                    "工作详情",
                    expanded,
                    Modifier.testTag("work-details-toggle"),
                    detail = if (count > 0) "$count 项执行" else "",
                    onClick = { expanded = !expanded },
                )
                DisclosureBody(expanded, Modifier.testTag("work-details-body")) {
                    Column {
                        ActivityDivider()
                        TimelineEntries(entries.subList(0, finalIndex), owner, inCard = true)
                    }
                }
            }
            TimelineEntries(entries.drop(finalIndex), owner)
        } else TimelineEntries(entries, owner)
    }
}

private val ChatEntry.isToolEntry: Boolean
    get() = type == "tool" || type == "terminal"

private val ChatEntry.isActivityEntry: Boolean
    get() = isToolEntry || type == "reasoning" || type == "stream_reasoning"

@Composable
private fun TimelineEntries(entries: List<ChatEntry>, owner: String, inCard: Boolean = false) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(if (inCard) 0.dp else 8.dp),
    ) {
        var index = 0
        while (index < entries.size) {
            val entry = entries[index]
            if (inCard && index > 0) ActivityDivider()
            if (!inCard && entry.isActivityEntry) {
                val start = index++
                while (index < entries.size && entries[index].isActivityEntry) index++
                val activity = entries.subList(start, index)
                ActivityCard(Modifier.testTag("agent-activity-card")) {
                    TimelineEntries(activity, owner, inCard = true)
                }
            } else if (entry.isToolEntry) {
                val start = index++
                while (index < entries.size && entries[index].isToolEntry) index++
                ToolGroup(entries.subList(start, index), framed = false)
            } else {
                when (entry.type) {
                    "reasoning",
                    "stream_reasoning" -> ThinkingDisclosure(entry, owner, framed = false)
                    else ->
                        Box(
                            if (inCard) Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
                            else Modifier,
                        ) {
                            if (entry.type == "changes") ChangesCard(entry.text)
                            else
                                ChatMessage(
                                    if (entry.type == "stream_assistant")
                                        entry.copy(type = "assistant")
                                    else entry,
                                    showHeader = false,
                                )
                        }
                }
                index++
            }
        }
    }
}

@Composable
internal fun ToolGroup(entries: List<ChatEntry>, framed: Boolean = true) {
    var expanded by remember { mutableStateOf(false) }
    var detail by remember { mutableIntStateOf(-1) }
    ActivityCard(Modifier.testTag("tool-group"), framed) {
        DisclosureHeader(
            "${entries.size} 项执行",
            expanded,
            Modifier.testTag("tool-group-toggle"),
            onClick = { expanded = !expanded },
        )
        DisclosureBody(expanded, Modifier.testTag("tool-group-body")) {
            Column {
                ActivityDivider()
                Column(Modifier.padding(start = 11.dp, end = 11.dp, bottom = 7.dp)) {
                    entries.forEachIndexed { i, entry ->
                        val run =
                            if (entry.type == "terminal")
                                AgentService.current?.terminal?.runs?.get(entry.text)
                            else null
                        val data =
                            if (entry.type == "tool")
                                runCatching { JSONObject(entry.text) }.getOrNull()
                            else null
                        val label =
                            run?.command
                                ?: data?.optJSONObject("args")?.optString("command")?.takeIf {
                                    it.isNotBlank()
                                }
                                ?: data
                                    ?.let {
                                        val action = toolLabel(it.optString("name"))
                                        val path =
                                            it.optJSONObject("args")?.optString("path").orEmpty()
                                        if (path.isNotBlank())
                                            "$action · ${path.substringAfterLast('/')}"
                                        else action
                                    }
                                    .orEmpty()
                        val status = run?.status ?: data?.optString("status").orEmpty()
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { detail = if (detail == i) -1 else i }
                                .padding(vertical = 10.dp)
                                .testTag("tool-row-$i"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (run != null) AppIcons.Terminal else AppIcons.Checklist,
                                null,
                                Modifier.size(15.dp),
                                tint = WhaleMuted,
                            )
                            Text(
                                label.ifBlank { "执行记录" },
                                Modifier.weight(1f).padding(horizontal = 8.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 12.sp,
                                fontFamily =
                                    if (run != null) DshaFonts.mono
                                    else DshaFonts.family(FontRole.UI),
                            )
                            Text(
                                activityStatusLabel(status),
                                Modifier.padding(end = 7.dp),
                                fontSize = 10.sp,
                                color =
                                    if (status in setOf("failed", "denied", "error"))
                                        androidx.compose.ui.graphics.Color(0xFFB14940)
                                    else WhaleMuted,
                            )
                            DisclosureChevron(detail == i)
                        }
                        DisclosureBody(detail == i, Modifier.testTag("tool-detail-$i")) {
                            Box(Modifier.padding(bottom = 8.dp)) {
                                if (entry.type == "terminal") TerminalCard(entry.text)
                                else ToolDetail(entry)
                            }
                        }
                        if (i < entries.lastIndex)
                            HorizontalDivider(color = ReadingStyle.workLine, thickness = .5.dp)
                    }
                }
            }
        }
    }
}

internal fun toolLabel(name: String) =
    mapOf(
        "terminal_status" to "检查终端连接",
        "observe" to "查看屏幕",
        "tap" to "点击",
        "swipe" to "滑动",
        "input_text" to "输入文字",
        "workspace_read" to "读取文件",
        "workspace_write" to "写入文件",
        "workspace_list" to "列出文件",
        "memory_read" to "读取记忆",
        "memory_write" to "更新记忆",
        "browser_open" to "打开网页",
        "browser_search" to "官方搜索网页",
        "browser_fetch" to "官方抓取网页",
        "plugin_manager" to "管理 Agent 插件",
        "plugin_call" to "运行 Agent 插件",
        "browser_snapshot" to "查看网页",
        "browser_source" to "查看网页源码",
        "browser_act" to "操作网页",
        "spawn_agent" to "启动子智能体",
        "wait_agent" to "收集智能体结果",
        "send_agent" to "追加说明",
        "stop_agent" to "停止子智能体",
        "publish_file" to "发布文件",
        "attachment_read" to "读取附件",
        "wait" to "等待",
        "ask_user" to "等待回复",
    )[name] ?: name

@Composable
private fun ToolDetail(entry: ChatEntry) {
    val data = runCatching { JSONObject(entry.text) }.getOrDefault(JSONObject())
    var full by remember { mutableStateOf(false) }
    var outputFull by remember { mutableStateOf(false) }
    val args = data.optJSONObject("args")?.toString(2).orEmpty()
    Column(
        Modifier.fillMaxWidth()
            .background(ReadingStyle.workSurface, ContinuousShape(12.dp))
            .animateContentSize(UiSpring.disclosure())
            .padding(12.dp),
    ) {
        Text(toolLabel(data.optString("name")), fontSize = 12.sp)
        SelectionContainer {
            Text(
                args,
                fontFamily = DshaFonts.mono,
                lineHeight = 18.sp,
                maxLines = if (full) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                fontSize = 11.sp,
            )
        }
        TextButton(onClick = { full = !full }) {
            Text(if (full) "收起参数" else "查看完整参数", fontSize = 11.sp, color = WhaleMuted)
        }
        SelectionContainer {
            Text(
                data.opt("result")?.toString() ?: data.optString("summary", "执行中…"),
                fontFamily = DshaFonts.mono,
                lineHeight = 18.sp,
                maxLines = if (outputFull) Int.MAX_VALUE else 8,
                overflow = TextOverflow.Ellipsis,
                fontSize = 11.sp,
            )
        }
        Row {
            TextButton(onClick = { outputFull = !outputFull }) {
                Text(if (outputFull) "收起结果" else "展开结果", fontSize = 11.sp, color = WhaleMuted)
            }
            if (data.optString("name").startsWith("browser_"))
                TextButton(
                    onClick = {
                        if (OfficialWebTool.fromMethod(data.optString("name")) != null) {
                            WorkspaceState.open(WorkspaceTab.OfficialBrowser(data.optString("id")))
                            return@TextButton
                        }
                        val id =
                            data.optJSONObject("result")?.optString("tab").orEmpty().ifBlank {
                                data.optJSONObject("args")?.optString("tab").orEmpty()
                            }
                        val tab =
                            BrowserState.tabs.firstOrNull {
                                it.id == id && it.sessionId == AssistantState.selectedId
                            }
                        WorkspaceState.open(
                            tab?.let { WorkspaceTab.Web(it.id) } ?: WorkspaceTab.Browser,
                        )
                    },
                ) {
                    Text("查看网页", fontSize = 11.sp, color = WhaleMuted)
                }
        }
        Text(
            activityStatusLabel(data.optString("status")),
            Modifier.align(Alignment.End).padding(top = 8.dp),
            fontSize = 10.sp,
            color = if (data.optString("status") == "failed") Color(0xFFB14940) else WhaleMuted,
        )
    }
}

@Composable
internal fun ReasoningIsland() {
    if (ReasoningState.selected == null || ExperienceSettings.reasoningMode != "island") return
    WhaleSheet("思考", close = { ReasoningState.selected = null }) {
        SelectionContainer {
            Text(
                ReasoningState.text(),
                Modifier.fillMaxWidth(),
                fontFamily = DshaFonts.family(FontRole.BODY),
                fontSize = 13.sp,
                lineHeight = 21.sp,
            )
        }
    }
}

/** Shares the chat island's horizontal margins; never extends above the upper third. */
@Composable
internal fun BoxScope.ReasoningBubble() {
    if (ReasoningState.selected == null || ExperienceSettings.reasoningMode != "bubble") return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Surface(
            Modifier.align(Alignment.BottomCenter)
                .padding(horizontal = 12.dp)
                .padding(bottom = 150.dp)
                .fillMaxWidth()
                .heightIn(max = (maxHeight * (2f / 3f) - 150.dp).coerceAtLeast(64.dp)),
            shape = ContinuousShape(26.dp),
            color = Color.White,
            border = BorderStroke(1.dp, WhaleLine),
            shadowElevation = 16.dp,
        ) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AgentAvatar(24.dp)
                    Text(
                        "${AgentIdentity.name}的思考",
                        Modifier.weight(1f).padding(start = 8.dp),
                        fontSize = 12.sp,
                        color = WhaleMuted,
                    )
                    SmallIcon(AppIcons.Close, "关闭思考气泡") { ReasoningState.selected = null }
                }
                SelectionContainer {
                    Text(
                        ReasoningState.text(),
                        Modifier.weight(1f, false).verticalScroll(rememberScrollState()),
                        fontSize = 13.sp,
                        lineHeight = 21.sp,
                    )
                }
            }
        }
    }
}
