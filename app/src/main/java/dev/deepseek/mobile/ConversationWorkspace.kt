package dev.deepseek.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal sealed interface WorkspaceTab {
    data object Home : WorkspaceTab

    data object Agents : WorkspaceTab

    data object Browser : WorkspaceTab

    data object Files : WorkspaceTab

    data object Terminal : WorkspaceTab

    data class Agent(val id: String) : WorkspaceTab

    data class Web(val id: String) : WorkspaceTab

    data class OfficialBrowser(val resultId: String = "") : WorkspaceTab

    data class Document(val file: LocalAttachment) : WorkspaceTab
}

internal object WorkspaceState {
    var visible by mutableStateOf(false)
        private set

    var selected by mutableStateOf<WorkspaceTab>(WorkspaceTab.Home)
        private set

    var sessionId by mutableStateOf("")
        private set

    private val opened = mutableStateListOf<WorkspaceTab>()
    val tabs: List<WorkspaceTab>
        get() = opened

    val hasTerminal: Boolean
        get() = WorkspaceTab.Terminal in opened

    fun forSession(id: String) {
        if (id == sessionId) return
        reset()
        sessionId = id
    }

    fun open(tab: WorkspaceTab? = null) {
        forSession(AssistantState.selectedId)
        val target =
            if (tab == WorkspaceTab.Browser && tab !in opened) {
                BrowserState.tabs
                    .lastOrNull { it.sessionId == sessionId }
                    ?.let { WorkspaceTab.Web(it.id) } ?: WorkspaceTab.Browser
            } else tab
        if (target != null && target != WorkspaceTab.Home) {
            val existing = opened.indexOfFirst { it.saveKey() == target.saveKey() }
            if (existing < 0) opened.add(target) else opened[existing] = target
        }
        if (target != null) selected = target
        visible = true
    }

    fun openBrowser() {
        open(
            if (AssistantState.current.browserProvider == BrowserProvider.OFFICIAL)
                WorkspaceTab.OfficialBrowser()
            else WorkspaceTab.Browser,
        )
    }

    fun hide() {
        visible = false
    }

    fun close(tab: WorkspaceTab) {
        opened.remove(tab)
        when (tab) {
            WorkspaceTab.Terminal -> LocalTerminalState.sessionId = ""
            is WorkspaceTab.Web -> closeBrowserTab(tab.id)
            else -> Unit
        }
        if (selected == tab) {
            val parent =
                when (tab) {
                    is WorkspaceTab.Agent -> WorkspaceTab.Agents
                    is WorkspaceTab.Document -> WorkspaceTab.Files
                    is WorkspaceTab.Web -> WorkspaceTab.Browser
                    else -> WorkspaceTab.Home
                }
            selected = if (parent in opened) parent else opened.lastOrNull() ?: WorkspaceTab.Home
        }
    }

    fun reset() {
        visible = false
        selected = WorkspaceTab.Home
        opened.clear()
        LocalTerminalState.sessionId = ""
    }
}

private data class WorkspaceLabel(
    val tab: WorkspaceTab,
    val title: String,
    val icon: ImageVector,
    val color: Color = WhaleMuted,
)

@Composable
internal fun ConversationWorkspace(progress: State<Float>) {
    val state = WorkspaceState
    val id = AssistantState.selectedId
    LaunchedEffect(id) { state.forSession(id) }
    val open = state.visible && state.sessionId == id
    val placed by
        remember(open, progress) {
            derivedStateOf { open || progress.value > .001f }
        }
    BackHandler(open) { state.hide() }
    val saved = rememberSaveableStateHolder()
    if (!placed && !state.hasTerminal) return

    val agents = TeamState.agents.filter { it.sessionId == id }
    val browserTabs = BrowserState.tabs.filter { it.sessionId == id }
    val tabs =
        state.tabs.mapNotNull { tab ->
            when (tab) {
                WorkspaceTab.Home -> null
                WorkspaceTab.Agents -> WorkspaceLabel(tab, "子 Agent", AppIcons.Agents)
                WorkspaceTab.Browser -> WorkspaceLabel(tab, "浏览器", AppIcons.Globe)
                is WorkspaceTab.OfficialBrowser -> WorkspaceLabel(tab, "官方浏览", AppIcons.Globe)
                WorkspaceTab.Terminal -> WorkspaceLabel(tab, "终端", AppIcons.Terminal)
                WorkspaceTab.Files -> WorkspaceLabel(tab, "文件", AppIcons.FolderOpen)
                is WorkspaceTab.Web -> {
                    val web = browserTabs.firstOrNull { it.id == tab.id }
                    WorkspaceLabel(tab, web?.title?.ifBlank { "网页" } ?: "网页已关闭", AppIcons.Globe)
                }
                is WorkspaceTab.Agent -> {
                    val index = agents.indexOfFirst { it.id == tab.id }
                    if (index < 0) WorkspaceLabel(tab, "子 Agent", AppIcons.Agents)
                    else
                        WorkspaceLabel(tab, agents[index].name, agentIcon(index), agentColor(index))
                }
                is WorkspaceTab.Document -> WorkspaceLabel(tab, tab.file.name, AppIcons.Description)
            }
        }
    val tab = state.selected
    val tabList = rememberLazyListState()
    val index = tabs.indexOfFirst { it.tab == tab }
    LaunchedEffect(index, placed) {
        if (
            index >= 0 &&
                placed &&
                tabList.layoutInfo.visibleItemsInfo.none {
                    it.index == index &&
                        it.offset >= 0 &&
                        it.offset + it.size <= tabList.layoutInfo.viewportEndOffset
                }
        )
            tabList.scrollToItem(index)
    }
    Column(
        Modifier.fillMaxSize()
            .testTag("conversation-workspace")
            .layout { measurable, constraints ->
                val content = measurable.measure(constraints)
                layout(content.width, content.height) {
                    if (placed) content.placeRelative(0, 0)
                }
            }
            .windowInsetsPadding(
                WindowInsets.statusBars
                    .union(WindowInsets.displayCutout)
                    .only(WindowInsetsSides.Top),
            )
            .padding(top = ConversationTitleBarHeight)
            .inactiveIf(!open)
            .clipToBounds()
            .graphicsLayer {
                translationX = size.width * (1f - progress.value.coerceIn(0f, 1f))
            }
            .background(Color(0xFFF9F9F8))
            .windowInsetsPadding(
                WindowInsets.systemBars
                    .union(WindowInsets.displayCutout)
                    .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
            )
            .imePadding(),
    ) {
        if (tabs.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth()
                    .height(48.dp)
                    .padding(start = 10.dp, end = 4.dp)
                    .testTag("workspace-tabs"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LazyRow(
                    state = tabList,
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items(tabs, key = { it.tab.saveKey() }) { label ->
                        WorkspaceTabChip(
                            label,
                            tab == label.tab,
                            onClose = {
                                saved.removeState(label.tab.saveKey())
                                state.close(label.tab)
                            },
                        )
                    }
                }
                SmallIcon(AppIcons.Add, "工作区工具") { state.open(WorkspaceTab.Home) }
            }
        }
        HorizontalDivider(color = WhaleLine)
        Box(Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
            if (placed && tab != WorkspaceTab.Terminal) {
                saved.SaveableStateProvider(tab.saveKey()) {
                    when (tab) {
                        WorkspaceTab.Home -> WorkspaceTools()
                        WorkspaceTab.Agents -> TeamList(agents)
                        is WorkspaceTab.Agent -> {
                            val work = agents.firstOrNull { it.id == tab.id }
                            if (work == null) TeamList(agents) else TeamConversation(work)
                        }
                        WorkspaceTab.Browser ->
                            BrowserWorkspace(
                                AssistantState.current,
                                browserTabs.lastOrNull(),
                                browserTabs,
                            )
                        is WorkspaceTab.Web ->
                            BrowserWorkspace(
                                AssistantState.current,
                                browserTabs.firstOrNull { it.id == tab.id },
                                browserTabs,
                            )
                        is WorkspaceTab.OfficialBrowser ->
                            OfficialBrowserWorkspace(AssistantState.current, tab.resultId)
                        WorkspaceTab.Files -> WorkspaceFiles(id)
                        is WorkspaceTab.Document -> DocumentWorkspace(tab.file)
                        WorkspaceTab.Terminal -> Unit
                    }
                }
            }
            if (state.hasTerminal && state.sessionId == id) {
                Box(
                    Modifier.matchParentSize()
                        .layout { measurable, constraints ->
                            val content = measurable.measure(constraints)
                            layout(content.width, content.height) {
                                if (tab == WorkspaceTab.Terminal && placed)
                                    content.placeRelative(0, 0)
                            }
                        }
                        .inactiveIf(!open || tab != WorkspaceTab.Terminal),
                ) {
                    val conversation = AssistantState.sessions.firstOrNull { it.id == id }
                    if (conversation != null) key(id) { WorkspaceTerminal(conversation) }
                }
            }
        }
    }
}

private fun WorkspaceTab.saveKey(): String =
    when (this) {
        is WorkspaceTab.Agent -> "agent-$id"
        is WorkspaceTab.Web -> "browser-$id"
        is WorkspaceTab.OfficialBrowser -> "official-browser"
        is WorkspaceTab.Document -> "document-${file.id}"
        else -> javaClass.simpleName
    }

@Composable
private fun WorkspaceTabChip(label: WorkspaceLabel, selected: Boolean, onClose: () -> Unit) {
    Row(
        Modifier.height(40.dp)
            .clip(ContinuousShape(9.dp))
            .background(if (selected) Color.White else Color(0xFFF1F1F0))
            .border(1.dp, if (selected) WhaleLine else Color.Transparent, ContinuousShape(9.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.fillMaxHeight()
                .selectable(selected, role = Role.Tab) {
                    WorkspaceState.open(label.tab)
                }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(label.icon, null, Modifier.size(15.dp), tint = label.color)
            Text(
                label.title,
                Modifier.widthIn(max = 126.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 13.sp,
            )
        }
        IconButton(onClick = onClose, modifier = Modifier.size(34.dp)) {
            Icon(AppIcons.Close, "关闭标签 ${label.title}", Modifier.size(13.dp), tint = WhaleMuted)
        }
    }
}

@Composable
private fun WorkspaceTools() {
    val tools =
        listOf(
            WorkspaceLabel(WorkspaceTab.Agents, "子 Agent 会话", AppIcons.Agents) to "查看当前会话的子任务",
            WorkspaceLabel(WorkspaceTab.Browser, "内置浏览器", AppIcons.Globe) to "切换官方搜索抓取与本机网页",
            WorkspaceLabel(WorkspaceTab.Terminal, "会话终端", AppIcons.Terminal) to "沿用当前会话的终端级别",
            WorkspaceLabel(WorkspaceTab.Files, "会话文件", AppIcons.FolderOpen) to "打开文档和附件",
        )
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("工具", Modifier.padding(bottom = 4.dp), fontSize = 16.sp)
        tools.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (tool, detail) ->
                    Surface(
                        onClick = {
                            if (tool.tab == WorkspaceTab.Browser) WorkspaceState.openBrowser()
                            else WorkspaceState.open(tool.tab)
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 88.dp),
                        shape = ContinuousShape(14.dp),
                        color = Color(0xFFF0F0EF),
                    ) {
                        Column(
                            Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(tool.icon, null, Modifier.size(18.dp), tint = WhaleMuted)
                            Text(tool.title, fontSize = 14.sp, lineHeight = 19.sp)
                            Text(detail, fontSize = 11.sp, lineHeight = 15.sp, color = WhaleMuted)
                        }
                    }
                }
            }
        }
    }
}
