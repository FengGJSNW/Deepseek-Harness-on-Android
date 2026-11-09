package dev.deepseek.mobile

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Dense, quiet history beneath a Chat / Work mode switch. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SessionDrawer(onSelect: () -> Unit, onSettings: () -> Unit) {
    val state = AssistantState
    val context = androidx.compose.ui.platform.LocalContext.current
    var search by remember { mutableStateOf("") }
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
    val query = search
    val visible =
        state.sessions
            .toList()
            .filter { c ->
                query.isBlank() ||
                    c.title.contains(query, true) ||
                    c.messages.any { it.text.contains(query, true) }
            }
            .map { it to it.updatedAt }
            .sortedByDescending { it.second }
            .map { it.first }
    val byProject = visible.groupBy { it.projectId }
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp).testTag("session-drawer")) {
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.clip(ContinuousShape(12.dp))
                    .clickable { modes = true }
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (state.appMode == "chat") "Chat" else "Work",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                )
                Icon(
                    AppIcons.ExpandMore,
                    null,
                    Modifier.padding(start = 8.dp).size(15.dp),
                    tint = WhaleMuted,
                )
            }
            Spacer(Modifier.weight(1f))
            SmallIcon(AppIcons.Chat, "开启新对话") {
                if (state.appMode == "work") AgentService.current?.newSession()
                else OfficialChatNavigation.newChat()
                onSelect()
            }
        }
        if (state.appMode == "work") {
            WhaleSearch(search) { search = it }
            Spacer(Modifier.height(12.dp))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                item(key = "projects-heading") {
                    Row(
                        Modifier.fillMaxWidth().height(42.dp).padding(start = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "项目",
                            fontSize = 12.sp,
                            color = WhaleMuted,
                            modifier = Modifier.weight(1f),
                        )
                        SmallIcon(AppIcons.Add, "新建项目") { createProject = true }
                    }
                }
                state.projects.forEach { project ->
                    val children = byProject[project.id].orEmpty()
                    if (
                        search.isBlank() ||
                            project.title.contains(search, true) ||
                            children.isNotEmpty()
                    ) {
                        item(key = "project-${project.id}") {
                            Row(
                                Modifier.fillMaxWidth()
                                    .height(44.dp)
                                    .clip(ContinuousShape(12.dp))
                                    .clickable {
                                        project.collapsed = !project.collapsed
                                        state.persist()
                                    }
                                    .padding(start = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    AppIcons.FolderOpen,
                                    null,
                                    Modifier.size(21.dp),
                                    tint = WhaleMuted,
                                )
                                Text(
                                    project.title,
                                    Modifier.weight(1f).padding(start = 10.dp),
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                IconButton(
                                    onClick = { projectMenu = project },
                                    modifier = Modifier.size(44.dp),
                                ) {
                                    Icon(
                                        AppIcons.MoreHoriz,
                                        "项目操作：${project.title}",
                                        Modifier.size(24.dp),
                                        tint = WhaleInk,
                                    )
                                }
                            }
                        }
                        if (!project.collapsed || search.isNotBlank()) {
                            children.forEach { c ->
                                item(key = "project-chat-${c.id}") {
                                    DrawerConversationRow(
                                        c,
                                        25,
                                        {
                                            state.select(c.id)
                                            onSelect()
                                        },
                                        { actions = c },
                                    )
                                }
                            }
                        }
                    }
                }
                item(key = "recent-heading") {
                    Row(
                        Modifier.fillMaxWidth().height(48.dp).padding(start = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "最近",
                            fontSize = 12.sp,
                            color = WhaleMuted,
                            modifier = Modifier.weight(1f),
                        )
                        SmallIcon(AppIcons.Chat, "新建独立会话") {
                            AgentService.current?.newSession("")
                            onSelect()
                        }
                    }
                }
                visible.forEach { c ->
                    item(key = "recent-${c.id}") {
                        DrawerConversationRow(
                            c,
                            11,
                            {
                                state.select(c.id)
                                onSelect()
                            },
                            { actions = c },
                        )
                    }
                }

                if (visible.isEmpty())
                    item {
                        Text(
                            "没有找到对话",
                            fontSize = 13.sp,
                            color = WhaleMuted,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
            }
        } else {
            WhaleSearch(search) { search = it }
            Spacer(Modifier.height(12.dp))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val official = OfficialChatNavigation.snapshot
                val chats = official.sessions.filter { it.title.contains(search, true) }
                if (chats.isEmpty())
                    item {
                        Text(
                            if (official.authenticated) "没有找到官方对话" else "登录官方账户后同步历史记录",
                            Modifier.padding(12.dp),
                            color = WhaleMuted,
                            fontSize = 13.sp,
                        )
                    }
                chats.forEach { chat ->
                    item(key = chat.path) {
                        Row(
                            Modifier.fillMaxWidth()
                                .height(44.dp)
                                .clip(ContinuousShape(13.dp))
                                .background(
                                    if (official.url.endsWith(chat.path)) Color(0xFFE9E9E9)
                                    else Color.Transparent,
                                )
                                .clickable {
                                    OfficialChatNavigation.open(chat.path)
                                    onSelect()
                                }
                                .padding(horizontal = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                chat.title,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            SmallIcon(AppIcons.Settings, "设置", action = onSettings)
        }
    }
    if (modes)
        WhaleSheet("模式", close = { modes = false }) {
            OptionRow(AppIcons.Chat, "Chat", "DeepSeek 官方聊天 · 登录官方账户", state.appMode == "chat") {
                modes = false
                OfficialChatNavigation.requestChat(context)
            }
            OptionRow(AppIcons.Terminal, "Work", "API 驱动 · 手机与终端工具", state.appMode == "work") {
                if (OfficialChatNavigation.loginOpen) OfficialChatNavigation.cancelLogin()
                state.appMode = "work"
                state.persist()
                modes = false
            }
        }
    actions?.let { c ->
        WhaleSheet(c.title, close = { actions = null }) {
            OptionRow(AppIcons.Edit, "重命名", "", false) {
                rename = c
                title = c.title
                actions = null
            }
            OptionRow(AppIcons.FolderOpen, "移动到项目", "", false) {
                moving = c
                actions = null
            }
            OptionRow(AppIcons.Close, "删除对话", "", false, Color(0xFFBC656B)) {
                delete = c
                actions = null
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
                TextButton(onClick = { rename = null }) { Text("取消") }
                TextButton(
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
                TextButton(onClick = { delete = null }) { Text("取消") }
                TextButton(
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
    projectMenu?.let { ProjectActions(it, { projectMenu = null }, onSelect) }
    moving?.let { MoveConversationDialog(it) { moving = null } }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerConversationRow(
    c: Conversation,
    indent: Int,
    select: () -> Unit,
    actions: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .height(44.dp)
            .clip(ContinuousShape(13.dp))
            .background(
                if (c.id == AssistantState.selectedId) Color(0xFFE9E9E9) else Color.Transparent,
            )
            .combinedClickable(onClick = select, onLongClick = actions)
            .padding(start = indent.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            c.title,
            Modifier.weight(1f),
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        ConversationActivityBadge(c.id)
        IconButton(onClick = actions, modifier = Modifier.size(44.dp)) {
            Icon(
                AppIcons.MoreHoriz,
                "会话操作：${c.title}",
                Modifier.size(24.dp).testTag("session-more-glyph"),
                tint = WhaleInk,
            )
        }
    }
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
