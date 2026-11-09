package dev.deepseek.mobile

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** Native conversation and composer; the isolated official page remains a background transport. */
@Composable
internal fun ColumnScope.OfficialChatScreen(cardAside: State<Boolean>) {
    val context = LocalContext.current
    val chat = OfficialChatNavigation
    LaunchedEffect(Unit) { chat.ensure(context) }
    val snapshot = chat.snapshot
    var draft by rememberSaveable { mutableStateOf("") }
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
    val layer = rememberGraphicsLayer()
    val backdrop = remember(layer) { ChatBackdrop(layer) }
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
                    if (snapshot.messages.isNotEmpty()) backdrop else null,
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
            val nav =
                with(LocalDensity.current) { WindowInsets.navigationBars.getBottom(this).toDp() }
            val bottom =
                12.dp + if (WindowInsets.ime.getBottom(LocalDensity.current) > 0) 0.dp else nav
            Column(
                Modifier.fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = bottom),
            ) {
                if (chat.failure.isNotBlank() || snapshot.unsupported)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            chat.failure.ifBlank { "官方页面结构已变化，请重试连接。" },
                            Modifier.weight(1f),
                            color = WhaleMuted,
                            fontSize = 12.sp,
                            maxLines = 2,
                        )
                        TextButton(onClick = { chat.retry(context) }) {
                            Text("重试", fontSize = 12.sp)
                        }
                    }
                Surface(
                    Modifier.fillMaxWidth().floatingIslandShadow(ContinuousShape(28.dp)),
                    shape = ContinuousShape(28.dp),
                    color = androidx.compose.ui.graphics.Color(0xFFF9FAFC),
                    border = BorderStroke(.7.dp, WhaleLine),
                ) {
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                        Box(
                            Modifier.fillMaxWidth()
                                .heightIn(min = 48.dp, max = 150.dp)
                                .padding(vertical = 10.dp),
                        ) {
                            if (draft.isBlank())
                                Text("给 DeepSeek 发送消息", fontSize = 15.sp, color = WhaleMuted)
                            BasicTextField(
                                draft,
                                { draft = it.take(32000) },
                                Modifier.fillMaxWidth().testTag("official-composer"),
                                textStyle =
                                    MaterialTheme.typography.bodyMedium.copy(
                                        color = WhaleInk,
                                        fontSize = 15.sp,
                                    ),
                                maxLines = 5,
                                visualTransformation = UprightSymbols.transformation,
                            )
                        }
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
                                                Modifier.widthIn(max = 140.dp)
                                                    .padding(start = 4.dp),
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
                            IconButton(
                                onClick = {
                                    MainActivity.current?.let { chat.pickAttachments(it) }
                                },
                                enabled =
                                    snapshot.attachmentsAvailable &&
                                        !chat.sending &&
                                        !snapshot.generating &&
                                        !chat.loading,
                                modifier = Modifier.size(34.dp).testTag("official-attachments"),
                            ) {
                                Icon(
                                    AppIcons.Paperclip,
                                    "添加官方附件",
                                    Modifier.size(19.dp),
                                    tint = WhaleMuted,
                                )
                            }
                            IconButton(
                                enabled =
                                    (draft.isNotBlank() || chat.attachments.isNotEmpty()) &&
                                        !chat.sending &&
                                        !snapshot.generating &&
                                        !snapshot.unsupported &&
                                        !chat.loading,
                                modifier = Modifier.size(36.dp).testTag("official-send"),
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
                            ) {
                                if (chat.sending)
                                    CircularProgressIndicator(
                                        Modifier.size(18.dp),
                                        color = WhaleBlue,
                                        strokeWidth = 2.dp,
                                    )
                                else
                                    Icon(
                                        AppIcons.ArrowUpward,
                                        "发送官方消息",
                                        Modifier.size(21.dp),
                                        tint = WhaleBlue,
                                    )
                            }
                        }
                    }
                }
            }
        },
    ) {
        Box(
            Modifier.fillMaxSize()
                .then(
                    if (snapshot.messages.isNotEmpty()) Modifier.chatBackdrop(backdrop)
                    else Modifier,
                ),
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
                                90.dp +
                                    with(LocalDensity.current) {
                                        WindowInsets.statusBars.getTop(this).toDp()
                                    },
                            bottom = 220.dp,
                        ),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    itemsIndexed(snapshot.messages) { index, message ->
                        if (message.role == "assistant") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                JingAvatar(30.dp)
                                Text(
                                    "DeepSeek",
                                    Modifier.padding(start = 9.dp),
                                    fontSize = 12.sp,
                                    color = WhaleMuted,
                                )
                            }
                        }
                        if (message.reasoning.isNotBlank()) {
                            var expanded by
                                rememberSaveable(snapshot.url, index) { mutableStateOf(false) }
                            Surface(
                                shape = ContinuousShape(14.dp),
                                color = WhaleMist,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                                    TextButton(onClick = { expanded = !expanded }) {
                                        val running =
                                            snapshot.generating &&
                                                index == snapshot.messages.lastIndex
                                        Text(if (running) "正在思考" else "思考过程")
                                        Text(if (expanded) "  ▴" else "  ▾")
                                    }
                                    if (expanded) {
                                        HorizontalDivider(color = WhaleLine)
                                        androidx.compose.foundation.text.selection
                                            .SelectionContainer {
                                                Text(
                                                    message.reasoning,
                                                    Modifier.padding(vertical = 10.dp),
                                                    fontSize = 13.sp,
                                                    lineHeight = 21.sp,
                                                    color = WhaleMuted,
                                                )
                                            }
                                    }
                                }
                            }
                        }
                        if (message.text.isNotBlank())
                            ChatMessage(ChatEntry(message.role, message.text), showHeader = false)
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
    Surface(
        Modifier.testTag(tag)
            .semantics {
                this.selected = selected
                contentDescription = title
                role = Role.Checkbox
            }
            .clickable(enabled = enabled, onClick = click),
        shape = ContinuousShape(14.dp),
        color = if (selected) WhaleBlue.copy(alpha = .09f) else Color.White,
        border = BorderStroke(.7.dp, if (selected) WhaleBlue.copy(alpha = .35f) else WhaleLine),
    ) {
        Row(
            Modifier.padding(horizontal = 7.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(icon, null, Modifier.size(13.dp), tint = if (selected) WhaleBlue else WhaleMuted)
            Text(
                title,
                fontSize = 12.sp,
                maxLines = 1,
                color = if (selected) WhaleBlue else WhaleInk,
            )
        }
    }
}
