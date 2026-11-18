package dev.deepseek.mobile

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Dense, quiet history beneath a Chat / Work mode switch. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SessionDrawer(
    onSelect: () -> Unit,
    onSettings: () -> Unit,
    onFlClash: () -> Unit,
    onSandbox: () -> Unit,
) {
    val hostView = androidx.compose.ui.platform.LocalView.current
    val drawerLayer = rememberGraphicsLayer()
    val drawerBackdrop = remember(drawerLayer) { ChatBackdrop(drawerLayer, hostView) }
    val state = AssistantState
    val context = androidx.compose.ui.platform.LocalContext.current
    val navigateSettings = LocalSettingsNavigation.current
    val searchOverlay = requireNotNull(LocalDrawerSearch.current)
    var revealProject by remember { mutableStateOf<String?>(null) }
    val historyScroll = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val jobs = rememberCoroutineScope()
    val expandedHistory = remember { mutableStateMapOf<String, Boolean>() }
    var modes by remember { mutableStateOf(false) }
    var actions by remember { mutableStateOf<Conversation?>(null) }
    var rename by remember { mutableStateOf<Conversation?>(null) }
    var title by remember { mutableStateOf("") }
    var createProject by remember { mutableStateOf(false) }
    var projectMenu by remember { mutableStateOf<WorkProject?>(null) }
    var moving by remember { mutableStateOf<Conversation?>(null) }
    var delete by remember { mutableStateOf<Conversation?>(null) }
    // Read session fields during composition, not a derived-state observer: session
    // construction itself writes snapshot state and may invalidate observers midway.
    val query = ""
    val work = state.appMode == "work"
    val sandboxRecords by
        remember(context) { dev.deepseek.mobile.runtime.SandboxManager.get(context) }
            .records
            .collectAsState()
    val conversationMenu: @Composable ColumnScope.(Conversation) -> Unit = menu@{ c ->
        val pick = LocalAfterMenuReturn.current
        GlassMenuRow(AppIcons.Edit, "重命名") {
            pick {
                title = c.title
                rename = c
            }
        }
        LiquidMenuAnchor(
            moving?.id == c.id,
            { moving = null },
            menuTitle = "移动到项目",
            placement = MenuPlacement.SelectedRow,
            toggleAnchor = true,
            anchor = { GlassMenuRow(AppIcons.FolderOpen, "移动到项目") { moving = c } },
        ) {
            val pickProject = LocalAfterMenuReturn.current
            fun move(projectId: String) {
                pickProject {
                    pick {
                        runCatching { state.moveSession(c.id, projectId) }
                            .onFailure { state.error = it.message.orEmpty() }
                    }
                }
            }
            GlassMenuRow(AppIcons.Chat, "独立会话", selected = c.projectId.isEmpty()) { move("") }
            state.projects.forEach { p ->
                GlassMenuRow(AppIcons.FolderOpen, p.title, selected = c.projectId == p.id) {
                    move(p.id)
                }
            }
        }
        GlassMenuDivider()
        GlassMenuRow(AppIcons.Close, "删除对话", tint = DshaSurface.danger) {
            pick { delete = c }
        }
    }
    val openSearchResult: (DrawerSearchItem) -> Unit = { result ->
        val dismissSearch = {
            focusManager.clearFocus()
            keyboard?.hide()
            searchOverlay.close()
        }
        when (result.kind) {
            DrawerSearchKind.CHATS -> {
                if (result.official) OfficialChatNavigation.open(result.target)
                else state.select(result.target)
                dismissSearch()
                onSelect()
            }
            DrawerSearchKind.PROJECTS -> {
                state.projects
                    .firstOrNull { it.id == result.target }
                    ?.let {
                        it.collapsed = false
                        state.persist()
                        revealProject = it.id
                    }
                dismissSearch()
            }
            DrawerSearchKind.IMAGES,
            DrawerSearchKind.FILES ->
                jobs.launch(Dispatchers.Main.immediate) {
                    try {
                        withContext(Dispatchers.IO) {
                            val store = AttachmentStore(context)
                            if (result.legacyImage)
                                store.legacyImage(result.target, result.sessionId)
                            store.file(result.target)
                        }
                        state.select(result.sessionId)
                        dismissSearch()
                        FilePreviewState.open(result.target)
                        onSelect()
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        AgentService.current?.toast("附件已不可用")
                    }
                }
            DrawerSearchKind.ALL -> Unit
        }
    }
    CompositionLocalProvider(LocalGlassBackdrop provides drawerBackdrop) {
        Box(
            Modifier.fillMaxSize()
                .onPlaced { drawerBackdrop.viewport = it }
                .padding(horizontal = 12.dp)
                .testTag("session-drawer"),
        ) {
            Column(Modifier.fillMaxSize()) {
                Spacer(
                    Modifier.fillMaxWidth().height(56.dp),
                )
                DrawerRow("FlClash", icon = AppIcons.Globe, prominent = true, click = onFlClash)
                DrawerRow(
                    "Sandbox",
                    icon = AppIcons.TerminalBox,
                    prominent = true,
                    click = onSandbox,
                )
                DrawerRow("内置浏览器", icon = AppIcons.Browser, prominent = true) {
                    navigateSettings("browser")
                }
                DrawerRow(
                    "Remote",
                    icon = AppIcons.RemoteRelay,
                    prominent = true,
                    click = { navigateSettings("remote") },
                )
                Spacer(Modifier.height(12.dp))
                if (work) {
                    val visible =
                        state.sessions
                            .toList()
                            .filter { c ->
                                query.isEmpty() ||
                                    c.title.contains(query, true) ||
                                    c.messages.any { it.text.contains(query, true) }
                            }
                            .map { it to it.updatedAt }
                            .sortedByDescending { it.second }
                            .map { it.first }
                    val byProject = visible.groupBy { it.projectId }
                    val projectIds = state.projects.map { it.id }.toSet()
                    val recent = visible.filter { it.projectId !in projectIds }
                    val selectedProject =
                        state.sessions.firstOrNull { it.id == state.selectedId }?.projectId
                    LaunchedEffect(revealProject) {
                        val target = revealProject ?: return@LaunchedEffect
                        val preceding = state.projects.takeWhile { it.id != target }
                        var index = 1
                        preceding.forEach { project ->
                            index++
                            if (!project.collapsed) {
                                val count = byProject[project.id].orEmpty().size
                                index +=
                                    if (expandedHistory[project.id] == true) count
                                    else minOf(count, PROJECT_VISIBLE_CHATS)
                                if (count > PROJECT_VISIBLE_CHATS) index++
                            }
                        }
                        historyScroll.scrollToItem(index)
                        revealProject = null
                    }
                    LazyColumn(
                        Modifier.weight(1f).chatBackdrop(drawerBackdrop),
                        state = historyScroll,
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        item(key = "projects-heading") {
                            DrawerSection("项目") {
                                DrawerAction("新建项目", icon = AppIcons.Add) { createProject = true }
                            }
                        }
                        state.projects.forEach { project ->
                            val children = byProject[project.id].orEmpty()
                            if (
                                query.isEmpty() ||
                                    project.title.contains(query, true) ||
                                    children.isNotEmpty()
                            ) {
                                item(key = "project-${project.id}") {
                                    ProjectActions(
                                        project,
                                        projectMenu?.id == project.id,
                                        { projectMenu = null },
                                        onSelect,
                                    ) {
                                        DrawerRow(
                                            project.title,
                                            icon = SearchIcons.Projects,
                                            modifier = Modifier.padding(top = 8.dp),
                                            prominent = true,
                                            actionLabel = "项目操作：${project.title}",
                                            action = {
                                                projectMenu =
                                                    if (projectMenu?.id == project.id) null
                                                    else project
                                            },
                                            expanded = !project.collapsed || query.isNotEmpty(),
                                            trailing = {
                                                val count = sandboxRecords.count {
                                                    project.id in it.projectLinks && !it.deleting
                                                }
                                                if (count > 0)
                                                    Text(
                                                        "$count",
                                                        color = WhaleMuted,
                                                        fontSize = 11.sp,
                                                        modifier =
                                                            Modifier.semantics {
                                                                contentDescription =
                                                                    "$count 个关联 Sandbox"
                                                            },
                                                    )
                                                if (project.id == selectedProject)
                                                    DrawerAction("项目操作：${project.title}") {
                                                        projectMenu =
                                                            if (projectMenu?.id == project.id) null
                                                            else project
                                                    }
                                            },
                                        ) {
                                            if (projectMenu?.id == project.id) projectMenu = null
                                            else {
                                                project.collapsed = !project.collapsed
                                                state.persist()
                                            }
                                        }
                                    }
                                }
                                if (!project.collapsed || query.isNotEmpty()) {
                                    val expanded = expandedHistory[project.id] == true
                                    val capped =
                                        children.size > PROJECT_VISIBLE_CHATS && query.isEmpty()
                                    val shown =
                                        if (!capped || expanded) children
                                        else {
                                            val first = children.take(PROJECT_VISIBLE_CHATS)
                                            val selected = children.firstOrNull {
                                                it.id == state.selectedId
                                            }
                                            if (selected != null && selected !in first)
                                                first.dropLast(1) + selected
                                            else first
                                        }
                                    shown.forEach { c ->
                                        item(key = "project-chat-${c.id}") {
                                            DrawerConversationRow(
                                                c,
                                                indent = 38,
                                                actions = { actions = c },
                                                expanded = actions?.id == c.id,
                                                dismiss = {
                                                    actions = null
                                                    moving = null
                                                },
                                                menu = { conversationMenu(c) },
                                            ) {
                                                state.select(c.id)
                                                onSelect()
                                            }
                                        }
                                    }
                                    if (capped) {
                                        item(key = "more-${project.id}") {
                                            DrawerRow(
                                                if (expanded) "收起" else "展开显示",
                                                indent = 38,
                                                subdued = true,
                                            ) {
                                                expandedHistory[project.id] = !expanded
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        item(key = "recent-heading") {
                            DrawerSection("最近") {
                                DrawerAction("新建独立会话", icon = AppIcons.Chat) {
                                    AgentService.current?.newSession("")
                                    onSelect()
                                }
                            }
                        }
                        recent.forEach { c ->
                            item(key = "recent-${c.id}") {
                                DrawerConversationRow(
                                    c,
                                    indent = 9,
                                    actions = { actions = c },
                                    expanded = actions?.id == c.id,
                                    dismiss = {
                                        actions = null
                                        moving = null
                                    },
                                    menu = { conversationMenu(c) },
                                ) {
                                    state.select(c.id)
                                    onSelect()
                                }
                            }
                        }
                        if (visible.isEmpty()) item { DrawerEmpty("没有找到对话") }
                    }
                } else {
                    val official = OfficialChatNavigation.snapshot
                    val chats = official.sessions.filter { it.title.contains(query, true) }
                    LazyColumn(
                        Modifier.weight(1f).chatBackdrop(drawerBackdrop),
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        item(key = "chat-history-heading") { DrawerSection("历史对话") }
                        if (chats.isEmpty())
                            item {
                                DrawerEmpty(
                                    if (official.authenticated) "没有找到官方对话" else "登录官方账户后同步历史记录",
                                )
                            }
                        chats.forEach { chat ->
                            item(key = chat.path) {
                                DrawerRow(chat.title, selected = official.url.endsWith(chat.path)) {
                                    OfficialChatNavigation.open(chat.path)
                                    onSelect()
                                }
                            }
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 20.dp)
                        .testTag("drawer-bottom-actions"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DshaButton(
                        onClick = {
                            if (work) AgentService.current?.newSession()
                            else OfficialChatNavigation.newChat()
                            onSelect()
                        },
                        modifier = Modifier.height(48.dp).testTag("drawer-new-chat"),
                    ) {
                        Icon(AppIcons.Edit, null, Modifier.size(20.dp))
                        Text("新对话", Modifier.padding(start = 8.dp), style = PrismTypography.callout)
                    }
                    DshaIconButton("设置", onSettings, size = 52.dp, glass = true) {
                        Icon(AppIcons.Settings, null, Modifier.size(22.dp), tint = WhaleInk)
                    }
                }
            }
            // Keep the complete pane inside a full-size overlay for drawing AND touch hit testing.
            DrawerSearchHeader(
                work,
                searchOverlay.visible,
                { bounds ->
                    modes = false
                    actions = null
                    moving = null
                    projectMenu = null
                    searchOverlay.backdrop = drawerBackdrop
                    searchOverlay.show(bounds, openSearchResult)
                },
                modifier = Modifier.align(Alignment.TopCenter).zIndex(2f),
                searchButtonAlpha = searchOverlay.motion.button.value,
                modeExpanded = modes,
                modeMenu = {
                    GlassMenuRow(AppIcons.Chat, "Chat", "DeepSeek 官方聊天", selected = !work) {
                        modes = false
                        OfficialChatNavigation.requestChat(context)
                    }
                    GlassMenuRow(AppIcons.Terminal, "Work", "手机与终端工具", selected = work) {
                        modes = false
                        OfficialChatNavigation.requestWork()
                    }
                },
            ) {
                modes = !modes
            }
        }
    }
    if (rename != null)
        WhaleSheet("重命名对话", close = { rename = null }) {
            OutlinedTextField(
                title,
                { title = it },
                singleLine = true,
                shape = ContinuousShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = UprightSymbols.transformation,
            )
            Row(Modifier.align(Alignment.End).padding(top = 12.dp)) {
                DshaTextButton(onClick = { rename = null }) { Text("取消") }
                DshaTextButton(
                    onClick = {
                        rename?.title = title.trim().ifEmpty { "新对话" }.take(50)
                        state.persist()
                        rename = null
                    },
                ) {
                    Text("保存")
                }
            }
        }
    if (delete != null)
        WhaleSheet("删除这段对话？", "聊天记录会从此设备移除。", { delete = null }) {
            Row(Modifier.align(Alignment.End)) {
                DshaTextButton(onClick = { delete = null }) { Text("取消") }
                DshaTextButton(
                    onClick = {
                        AgentService.current?.deleteSession(delete!!.id)
                        delete = null
                    },
                ) {
                    Text("删除", color = Color(0xFFBC656B))
                }
            }
        }
    if (createProject)
        ProjectNameDialog(
            close = { createProject = false },
            created = { p ->
                AgentService.current?.newSession(p.id)
                onSelect()
            },
        )
}

private const val PROJECT_VISIBLE_CHATS = 5

@Composable
private fun DrawerSection(title: String, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().height(42.dp).padding(start = 9.dp, end = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            Modifier.weight(1f),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = WhaleMuted,
        )
        actions()
    }
}

@Composable
private fun DrawerEmpty(text: String) {
    Text(text, Modifier.padding(10.dp), color = WhaleMuted, fontSize = 13.sp)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    selected: Boolean = false,
    prominent: Boolean = false,
    indent: Int = 9,
    subdued: Boolean = false,
    actionLabel: String? = null,
    action: (() -> Unit)? = null,
    expanded: Boolean? = null,
    trailing: @Composable RowScope.() -> Unit = {},
    click: () -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(ContinuousShape(10.dp))
            .background(if (selected) Color(0xFFE5E6E8) else Color.Transparent)
            .semantics {
                if (selected) this.selected = true
                if (expanded != null) stateDescription = if (expanded) "已展开" else "已收起"
            }
            .combinedClickable(
                onClick = click,
                onLongClick = action,
                onLongClickLabel = actionLabel,
                role = Role.Button,
            )
            .padding(start = indent.dp, end = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(20.dp), tint = WhaleMuted)
        Text(
            title,
            Modifier.weight(1f).padding(start = if (icon != null) 8.dp else 0.dp),
            color = if (subdued) WhaleMuted else WhaleInk,
            style =
                PrismTypography.body.copy(
                    fontSize = if (prominent) 17.sp else 16.sp,
                    lineHeight = 23.sp,
                ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        trailing()
    }
}

@Composable
private fun DrawerAction(
    label: String,
    isConversation: Boolean = false,
    icon: ImageVector = AppIcons.MoreHoriz,
    click: () -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val haptics = rememberUiHaptics()
    Box(
        Modifier.size(36.dp)
            .clickable(interactionSource = interactions, indication = null, role = Role.Button) {
                haptics.perform(UiHaptic.Selection)
                click()
            }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            null,
            Modifier.size(
                    if (isConversation) 24.dp else if (icon == AppIcons.MoreHoriz) 19.dp else 22.dp,
                )
                .then(if (isConversation) Modifier.testTag("session-more-glyph") else Modifier),
            tint = if (pressed) WhaleInk else WhaleMuted,
        )
    }
}

@Composable
private fun DrawerConversationRow(
    c: Conversation,
    indent: Int,
    actions: () -> Unit,
    expanded: Boolean,
    dismiss: () -> Unit,
    menu: @Composable ColumnScope.() -> Unit,
    select: () -> Unit,
) {
    val chosen = c.id == AssistantState.selectedId
    LiquidMenuAnchor(
        expanded,
        dismiss,
        width = 272.dp,
        placement = MenuPlacement.SelectedRow,
        tag = "session-context-menu",
        menuTitle = c.title,
        toggleAnchor = true,
        anchor = {
            DrawerRow(
                c.title,
                selected = chosen,
                indent = indent,
                actionLabel = "会话操作：${c.title}",
                action = { if (expanded) dismiss() else actions() },
                trailing = {
                    if (c.sandboxEnabled)
                        Icon(
                            AppIcons.TerminalBox,
                            "使用 Sandbox",
                            Modifier.size(15.dp),
                            tint = WhaleMuted,
                        )
                    ConversationActivityBadge(c.id)
                    if (chosen)
                        DrawerAction("会话操作：${c.title}", isConversation = true) {
                            if (expanded) dismiss() else actions()
                        }
                },
                click = { if (expanded) dismiss() else select() },
            )
        },
        content = menu,
    )
}

@Composable
private fun ConversationActivityBadge(sessionId: String) {
    val task = AssistantState.tasks.lastOrNull { it.sessionId == sessionId }
    val label =
        when (task?.status) {
            "running",
            "stopping" -> "运行中"
            "waiting" -> "待确认"
            "failed",
            "error",
            "interrupted" -> "需处理"
            else -> ""
        }
    if (label.isNotEmpty()) {
        val color =
            when (label) {
                "运行中" -> WhaleBlue
                "待确认" -> Color(0xFFB17A2C)
                else -> Color(0xFFB55950)
            }
        Row(
            Modifier.padding(start = 5.dp).testTag("conversation-status"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(5.dp)
                    .background(color, androidx.compose.foundation.shape.CircleShape),
            )
            Text(label, Modifier.padding(start = 4.dp), fontSize = 9.sp, color = color)
        }
    }
}
