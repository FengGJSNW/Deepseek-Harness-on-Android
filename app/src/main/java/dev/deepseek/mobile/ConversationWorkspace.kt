package dev.deepseek.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
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

    data object SandboxAccess : WorkspaceTab

    data class SandboxDisks(val sandboxId: String = "") : WorkspaceTab

    data class RemoteAccess(val target: RemoteTarget) : WorkspaceTab

    data object Workflow : WorkspaceTab

    data object Agents : WorkspaceTab

    data object Browser : WorkspaceTab

    data object Files : WorkspaceTab

    data object Terminal : WorkspaceTab

    data class Agent(val id: String) : WorkspaceTab

    data class Web(val id: String) : WorkspaceTab

    data class OfficialBrowser(val resultId: String = "") : WorkspaceTab

    data class Document(val file: LocalAttachment) : WorkspaceTab

    data class Html(val file: LocalAttachment) : WorkspaceTab
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

    private var tabsPreference by mutableStateOf<Boolean?>(null)
    val tabsVisible: Boolean
        get() = tabsPreference ?: opened.isNotEmpty()

    fun toggleTabs() {
        tabsPreference = !tabsVisible
    }

    val hasTerminal: Boolean
        get() = WorkspaceTab.Terminal in opened

    var navigationRevision by mutableIntStateOf(0)
        private set

    var terminalInstance by mutableIntStateOf(0)
        private set

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
                    .lastOrNull { it.sessionId == sessionId && !it.userOwned }
                    ?.let { WorkspaceTab.Web(it.id) } ?: WorkspaceTab.Browser
            } else tab
        if (target != null && target != WorkspaceTab.Home) {
            val existing = opened.indexOfFirst { it.saveKey() == target.saveKey() }
            if (existing < 0) opened.add(target) else opened[existing] = target
        }
        if (target != null) selected = target
        visible = true
        navigationRevision++
    }

    fun openUserBrowser(context: android.content.Context) {
        runCatching { AgentBrowser(context.applicationContext).newUserTab(AssistantState.current) }
            .onSuccess { open(WorkspaceTab.Web(it.id)) }
            .onFailure { AssistantState.error = it.message.orEmpty() }
    }

    fun openUserTerminal(sandboxId: String = "") {
        open(WorkspaceTab.Terminal)
        LocalTerminalState.sessionId = if (sandboxId.isBlank()) "" else AssistantState.selectedId
        LocalTerminalState.sandboxId = sandboxId
        terminalInstance++
    }

    fun closeChromePages() {
        BrowserState.tabs.map { it.id }.forEach { closeBrowserTab(it) }
        opened.filterIsInstance<WorkspaceTab.Web>().forEach { close(it) }
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
                    is WorkspaceTab.Document,
                    is WorkspaceTab.Html -> WorkspaceTab.Files
                    is WorkspaceTab.Web -> WorkspaceTab.Browser
                    else -> WorkspaceTab.Home
                }
            selected = if (parent in opened) parent else opened.lastOrNull() ?: WorkspaceTab.Home
        }
    }

    fun reset() {
        visible = false
        selected = WorkspaceTab.Home
        tabsPreference = null
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
                WorkspaceTab.Workflow -> WorkspaceLabel(tab, "计划与验证", AppIcons.Checklist)
                WorkspaceTab.SandboxAccess ->
                    WorkspaceLabel(tab, "Sandbox 访问", AppIcons.TerminalBox)
                is WorkspaceTab.SandboxDisks -> WorkspaceLabel(tab, "磁盘挂载", AppIcons.FolderOpen)
                is WorkspaceTab.RemoteAccess ->
                    WorkspaceLabel(tab, tab.target.title, tab.target.icon)
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
                is WorkspaceTab.Document ->
                    WorkspaceLabel(
                        tab,
                        if (tab.file.isHtml) "源码 · ${tab.file.name}" else tab.file.name,
                        AppIcons.Description,
                    )
                is WorkspaceTab.Html -> WorkspaceLabel(tab, tab.file.name, AppIcons.Globe)
            }
        }
    val tab = state.selected
    val pageColor =
        if (tab == WorkspaceTab.Workflow || tab == WorkspaceTab.Home) DshaSurface.page
        else Color.White
    val tabsVisible = state.tabsVisible
    val stripHeight by
        animateDpAsState(
            if (tabsVisible) 48.dp else 0.dp,
            tween(210),
            label = "workspace-tab-strip",
        )
    val tabList = rememberLazyListState()
    val index = tabs.indexOfFirst { it.tab == tab }
    LaunchedEffect(index, placed, tabsVisible) {
        if (
            index >= 0 &&
                tabsVisible &&
                placed &&
                tabList.layoutInfo.visibleItemsInfo.none {
                    it.index == index &&
                        it.offset >= 0 &&
                        it.offset + it.size <= tabList.layoutInfo.viewportEndOffset
                }
        )
            tabList.scrollToItem(index)
    }
    val titleInset =
        with(androidx.compose.ui.platform.LocalDensity.current) {
            WindowInsets.statusBars.union(WindowInsets.displayCutout).getTop(this).toDp()
        } + ConversationTitleBarHeight
    FadingHeaderLayout(
        Modifier.fillMaxSize()
            .testTag("conversation-workspace")
            .layout { measurable, constraints ->
                val content = measurable.measure(constraints)
                layout(content.width, content.height) {
                    if (placed) content.placeRelative(0, 0)
                }
            }
            .inactiveIf(!open)
            .clipToBounds()
            .graphicsLayer {
                translationX = size.width * (1f - progress.value.coerceIn(0f, 1f))
            }
            .background(pageColor)
            .windowInsetsPadding(
                WindowInsets.systemBars
                    .union(WindowInsets.displayCutout)
                    .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
            )
            .imePadding(),
        height = titleInset + stripHeight,
        color = pageColor.copy(alpha = .46f),
        header = {
            Column {
                Spacer(Modifier.height(titleInset))
                if (stripHeight > 0.dp) {
                    Row(
                        Modifier.fillMaxWidth()
                            .height(stripHeight)
                            .clipToBounds()
                            .inactiveIf(!tabsVisible)
                            .graphicsLayer { alpha = (stripHeight / 48.dp).coerceIn(0f, 1f) }
                            .wrapContentHeight(Alignment.Bottom, unbounded = true)
                            .height(48.dp)
                            .padding(start = 10.dp, end = 4.dp)
                            .testTag("workspace-tabs"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (tabs.isEmpty()) {
                            Text(
                                "暂无打开的标签页",
                                Modifier.weight(1f).padding(start = 10.dp),
                                style = PrismTypography.footnote,
                                color = WhaleMuted,
                            )
                        } else
                            LazyRow(
                                state = tabList,
                                modifier = Modifier.weight(1f).testTag("workspace-tab-list"),
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
            }
        },
    ) { topInset ->
        CompositionLocalProvider(LocalWorkspaceTopInset provides topInset) {
            Box(Modifier.fillMaxSize().clipToBounds()) {
                if (placed && tab != WorkspaceTab.Terminal) {
                    saved.SaveableStateProvider(tab.saveKey()) {
                        when (tab) {
                            WorkspaceTab.Home -> WorkspaceTools()
                            WorkspaceTab.SandboxAccess -> SandboxAccessPane(AssistantState.current)
                            is WorkspaceTab.SandboxDisks -> SandboxDiskAccessPane(tab.sandboxId)
                            is WorkspaceTab.RemoteAccess -> RemoteAccessPane(tab.target)
                            WorkspaceTab.Workflow -> WorkflowWorkspace(AssistantState.current)
                            WorkspaceTab.Agents -> TeamList(agents)
                            is WorkspaceTab.Agent -> {
                                val work = agents.firstOrNull { it.id == tab.id }
                                if (work == null) TeamList(agents) else TeamConversation(work)
                            }
                            WorkspaceTab.Browser ->
                                Box(Modifier.padding(top = topInset)) {
                                    BrowserWorkspace(browserTabs.lastOrNull { !it.userOwned })
                                }
                            is WorkspaceTab.Web ->
                                Box(Modifier.padding(top = topInset)) {
                                    BrowserWorkspace(browserTabs.firstOrNull { it.id == tab.id })
                                }
                            is WorkspaceTab.OfficialBrowser ->
                                OfficialBrowserWorkspace(AssistantState.current, tab.resultId)
                            WorkspaceTab.Files -> WorkspaceFiles(id)
                            is WorkspaceTab.Document -> DocumentWorkspace(tab.file)
                            is WorkspaceTab.Html -> HtmlWorkspace(tab.file)
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
                        if (conversation != null)
                            Box(Modifier.padding(top = topInset)) {
                                key(id, state.terminalInstance) { WorkspaceTerminal(conversation) }
                            }
                    }
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
        is WorkspaceTab.Html -> "html-${file.id}"
        is WorkspaceTab.SandboxDisks -> "sandbox-disks-$sandboxId"
        is WorkspaceTab.RemoteAccess -> "remote-${target.entry}"
        else -> javaClass.simpleName
    }

@Composable
private fun WorkspaceTabChip(label: WorkspaceLabel, selected: Boolean, onClose: () -> Unit) {
    Row(
        Modifier.height(40.dp)
            .clip(ContinuousShape(22.dp))
            .background(if (selected) WhaleTint else DshaSurface.inset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.fillMaxHeight()
                .testTag("workspace-tab-${label.tab.saveKey()}")
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val tools =
        listOf(
            WorkspaceLabel(WorkspaceTab.Workflow, "计划与验证", AppIcons.Checklist) to "跟踪步骤与实际检查",
            WorkspaceLabel(WorkspaceTab.Agents, "子 Agent 会话", AppIcons.Agents) to "查看当前会话的子任务",
            WorkspaceLabel(WorkspaceTab.Browser, "内置浏览器", AppIcons.Globe) to "打开新标签页",
            WorkspaceLabel(WorkspaceTab.Terminal, "会话终端", AppIcons.Terminal) to "新建用户终端",
            WorkspaceLabel(WorkspaceTab.Files, "会话文件", AppIcons.FolderOpen) to "打开文档和附件",
        )
    Column(
        Modifier.fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(
                start = 16.dp,
                end = 16.dp,
                top = LocalWorkspaceTopInset.current + 12.dp,
                bottom = 12.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "工作",
            Modifier.padding(start = 16.dp, top = 8.dp),
            style = PrismTypography.footnote,
            color = WhaleMuted,
        )
        SettingsGroup {
            SettingsLink(AppIcons.TerminalBox, "Sandbox 访问", "按会话授权环境与磁盘挂载") {
                WorkspaceState.open(WorkspaceTab.SandboxAccess)
            }
            SettingsLink(RemoteTarget.SSH.icon, "Remote SSH", "管理此会话可访问的服务器") {
                WorkspaceState.open(WorkspaceTab.RemoteAccess(RemoteTarget.SSH))
            }
            SettingsLink(RemoteTarget.COMPUTER.icon, "Remote Computer", "管理此会话可访问的远程电脑") {
                WorkspaceState.open(WorkspaceTab.RemoteAccess(RemoteTarget.COMPUTER))
            }
            tools
                .filter {
                    it.first.tab in
                        listOf(WorkspaceTab.Workflow, WorkspaceTab.Agents, WorkspaceTab.Files)
                }
                .forEach { (tool, detail) ->
                    SettingsLink(tool.icon, tool.title, detail) { WorkspaceState.open(tool.tab) }
                }
        }
        Text(
            "终端与浏览",
            Modifier.padding(start = 16.dp, top = 8.dp),
            style = PrismTypography.footnote,
            color = WhaleMuted,
        )
        SettingsGroup {
            SettingsLink(AppIcons.Globe, "内置浏览器", "打开新标签页") {
                WorkspaceState.openUserBrowser(context)
            }
            SettingsLink(AppIcons.Terminal, "会话终端", "新建用户终端") { WorkspaceState.openUserTerminal() }
        }
        val pages =
            BrowserState.tabs.filter { it.sessionId == AssistantState.selectedId && !it.userOwned }
        val session = AssistantState.current
        val official =
            ExperienceSettings.browserMode.official ||
                (sequenceOf(session.messages) +
                        TeamState.agents
                            .asSequence()
                            .filter { it.sessionId == session.id }
                            .map { it.messages })
                    .any { entries ->
                        entries.any {
                            it.type == "tool" &&
                                OfficialWebTool.entries.any { tool ->
                                    it.text.contains(tool.method)
                                }
                        }
                    }
        if (pages.isNotEmpty() || official) {
            Text(
                "浏览记录",
                Modifier.padding(start = 16.dp, top = 8.dp),
                style = PrismTypography.footnote,
                color = WhaleMuted,
            )
            SettingsGroup {
                pages.forEach { page ->
                    SettingsLink(AppIcons.Globe, page.title.ifBlank { "Agent 网页" }, page.url) {
                        WorkspaceState.open(WorkspaceTab.Web(page.id))
                    }
                }
                if (official)
                    SettingsLink(AppIcons.Globe, "官方浏览记录", "查看 Agent 搜索与抓取结果") {
                        WorkspaceState.open(WorkspaceTab.OfficialBrowser())
                    }
            }
        }
    }
}
