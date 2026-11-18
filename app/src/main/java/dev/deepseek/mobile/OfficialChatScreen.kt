package dev.deepseek.mobile

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** Native conversation and composer; the isolated official page remains a background transport. */
@Composable
internal fun ColumnScope.OfficialChatScreen(cardAside: State<Boolean>, backdrop: ChatBackdrop) {
    val context = LocalContext.current
    val chat = OfficialChatNavigation
    LaunchedEffect(Unit) { chat.ensure(context) }
    val snapshot = chat.snapshot
    var draft by rememberSaveable { mutableStateOf("") }
    var preview by rememberDraftPreview()
    LaunchedEffect(draft.isBlank()) { if (draft.isBlank()) preview = false }
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    val jobs = rememberCoroutineScope()
    val density = LocalDensity.current
    var followTail by rememberSaveable { mutableStateOf(true) }
    var trackedUrl by rememberSaveable { mutableStateOf(snapshot.url) }
    LaunchedEffect(snapshot.url) {
        if (trackedUrl != snapshot.url) {
            trackedUrl = snapshot.url
            followTail = true
            list.scrollToConversationEnd()
        }
    }
    val dragging by list.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragging) { if (dragging) followTail = false }
    LaunchedEffect(list.isScrollInProgress) {
        if (!list.isScrollInProgress && !list.canScrollForward) followTail = true
    }
    val anchors =
        snapshot.messages.mapIndexedNotNull { index, message ->
            if (message.role != "user") null
            else
                TaskAnchor(
                    "official-$index",
                    index,
                    previewText(message.text),
                    if (
                        snapshot.generating &&
                            index == snapshot.messages.indexOfLast { it.role == "user" }
                    )
                        "正在回复"
                    else "官方对话",
                    previewText(
                        snapshot.messages
                            .getOrNull(index + 1)
                            ?.takeIf { it.role == "assistant" }
                            ?.text
                            .orEmpty(),
                    ),
                )
        }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(
        snapshot.messages.size,
        snapshot.messages.lastOrNull()?.text?.length,
        snapshot.messages.lastOrNull()?.reasoning?.length,
        followTail,
    ) {
        if (followTail && !dragging) list.scrollToConversationEnd()
    }
    ConversationLayout(
        Modifier.weight(1f).fillMaxWidth().testTag("official-native-chat"),
        backdrop = backdrop,
        header = {
            Box(
                Modifier.fillMaxWidth()
                    .height(
                        82.dp +
                            with(LocalDensity.current) {
                                WindowInsets.statusBars.getTop(this).toDp()
                            },
                    ),
            ) {
                HeaderBackdrop(
                    Modifier.matchParentSize(),
                )
                if (chat.loading)
                    LinearProgressIndicator(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp),
                    )
            }
        },
        companion = {
            if (AssistantState.petEnabled)
                WhaleDock(idleKey = "official" to draft, wanderEnabled = !cardAside.value)
        },
        composer = {
            Column(Modifier.fillMaxWidth()) {
                if (chat.failure.isNotBlank() || snapshot.unsupported)
                    Row(
                        Modifier.padding(horizontal = 22.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            chat.failure.ifBlank { "官方页面结构已变化，请重试连接。" },
                            Modifier.weight(1f),
                            color = WhaleMuted,
                            fontSize = 12.sp,
                            maxLines = 2,
                        )
                        DshaTextButton(onClick = { chat.retry(context) }) {
                            Text("重试", fontSize = 12.sp)
                        }
                    }
                ComposerFrame {
                    ComposerDraft(
                        draft,
                        { draft = it.take(32000) },
                        "给 DeepSeek 发送消息",
                        "official-composer",
                        modifier = Modifier.weight(1f, fill = false),
                        preview = preview,
                    )
                    if (chat.attachments.isNotEmpty())
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            chat.attachments.toList().forEach { file ->
                                Surface(
                                    shape = ContinuousShape(10.dp),
                                    color = Color.White,
                                    border = BorderStroke(.7.dp, WhaleLine),
                                ) {
                                    Row(
                                        Modifier.padding(start = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            AppIcons.Description,
                                            null,
                                            Modifier.size(13.dp),
                                            tint = WhaleMuted,
                                        )
                                        Text(
                                            file.name,
                                            Modifier.widthIn(max = 140.dp).padding(start = 4.dp),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            fontSize = 11.sp,
                                        )
                                        IconButton(
                                            onClick = { chat.attachments.remove(file) },
                                            enabled = !chat.sending,
                                            modifier = Modifier.size(30.dp),
                                        ) {
                                            Icon(
                                                AppIcons.Close,
                                                "移除官方附件 ${file.name}",
                                                Modifier.size(13.dp),
                                                tint = WhaleMuted,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        OfficialModePill(
                            "深度思考",
                            AppIcons.Spark,
                            snapshot.deepThinking,
                            snapshot.thinkingAvailable &&
                                !chat.sending &&
                                !snapshot.generating &&
                                !chat.modeBusy,
                            "official-thinking",
                        ) {
                            chat.toggleMode("thinking")
                        }
                        OfficialModePill(
                            "智能搜索",
                            AppIcons.Search,
                            snapshot.search,
                            snapshot.searchAvailable &&
                                !chat.sending &&
                                !snapshot.generating &&
                                !chat.modeBusy,
                            "official-search",
                        ) {
                            chat.toggleMode("search")
                        }
                        Spacer(Modifier.weight(1f))
                        MarkdownPreviewToggle(preview, draft.isNotBlank()) { preview = it }
                        ToolbarButton(
                            AppIcons.Paperclip,
                            "添加官方附件",
                            "official-attachments",
                            enabled =
                                snapshot.attachmentsAvailable &&
                                    !chat.sending &&
                                    !snapshot.generating &&
                                    !chat.loading,
                        ) {
                            MainActivity.current?.let { chat.pickAttachments(it) }
                        }
                        ComposerSendButton(
                            enabled =
                                (draft.isNotBlank() || chat.attachments.isNotEmpty()) &&
                                    !chat.sending &&
                                    !snapshot.generating &&
                                    !snapshot.unsupported &&
                                    !chat.loading,
                            tag = "official-send",
                            label = "发送官方消息",
                            waiting = chat.sending,
                            onClick = {
                                val sent = draft
                                followTail = true
                                list.requestScrollToItem(
                                    snapshot.messages.lastIndex.coerceAtLeast(0),
                                )
                                jobs.launch { list.scrollToConversationEnd() }
                                keyboard?.hide()
                                chat.send(sent) { ok ->
                                    if (ok && draft == sent) {
                                        draft = ""
                                        list.requestScrollToItem(
                                            chat.snapshot.messages.lastIndex.coerceAtLeast(0),
                                        )
                                        jobs.launch { list.scrollToConversationEnd() }
                                    }
                                }
                            },
                        )
                    }
                }
            }
        },
    ) {
        Box(
            Modifier.fillMaxSize().chatBackdrop(backdrop),
        ) {
            if (snapshot.messages.isEmpty())
                Text(
                    "你想在 DeepSeek 中构建什么？",
                    Modifier.align(Alignment.Center).padding(24.dp),
                    fontSize = 18.sp,
                    color = WhaleMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            else
                LazyColumn(
                    state = list,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding =
                        PaddingValues(
                            start =
                                if (anchors.size >= 2 && ExperienceSettings.taskNavigation) 32.dp
                                else 22.dp,
                            end = 22.dp,
                            top =
                                82.dp +
                                    with(LocalDensity.current) {
                                        WindowInsets.statusBars.getTop(this).toDp()
                                    },
                            bottom = 220.dp,
                        ),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    itemsIndexed(snapshot.messages) { index, message ->
                        Column(
                            Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (message.role == "assistant") AgentMessageHeader(AgentIdentity.name)
                            if (message.reasoning.isNotBlank()) {
                                val url = snapshot.url
                                val running =
                                    snapshot.generating && index == snapshot.messages.lastIndex
                                ThinkingDisclosure(
                                    ChatEntry(
                                        if (running) "stream_reasoning" else "reasoning",
                                        message.reasoning,
                                        taskId = index.toString(),
                                    ),
                                    owner = "official:$url",
                                    liveText = {
                                        chat.snapshot
                                            .takeIf { it.url == url }
                                            ?.messages
                                            ?.getOrNull(index)
                                            ?.reasoning ?: message.reasoning
                                    },
                                )
                            }
                            if (message.text.isNotBlank())
                                ChatMessage(
                                    ChatEntry(message.role, message.text),
                                    showHeader = false,
                                )
                        }
                    }
                }
            TaskNavigator(
                anchors,
                { list.firstVisibleItemIndex },
                Modifier.padding(
                    top = with(density) { WindowInsets.statusBars.getTop(this).toDp() } + 72.dp,
                    bottom = 190.dp,
                ),
            ) { anchor ->
                followTail = false
                jobs.launch { list.scrollToItem(anchor.item, -with(density) { 90.dp.roundToPx() }) }
            }
        }
    }
}

@Composable
private fun OfficialModePill(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    enabled: Boolean,
    tag: String,
    click: () -> Unit,
) {
    Row(
        Modifier.heightIn(min = 34.dp)
            .clip(ContinuousShape(10.dp))
            .testTag(tag)
            .semantics {
                this.selected = selected
                contentDescription = title
                role = Role.Checkbox
            }
            .background(if (selected) WhaleTint else Color.Transparent)
            .clickable(enabled = enabled, onClick = click)
            .padding(horizontal = 7.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val color = if (selected) WhaleBlue else if (enabled) WhaleInk else WhaleMuted
        Icon(icon, null, Modifier.size(15.dp), tint = color)
        Text(title, fontSize = 12.sp, maxLines = 1, color = color)
    }
}
