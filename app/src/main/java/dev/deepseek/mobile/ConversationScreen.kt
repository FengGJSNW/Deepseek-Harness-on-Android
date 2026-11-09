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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** Edge-to-edge conversation, with genuine backdrop blur behind the floating chrome. */
@Composable
internal fun ColumnScope.ConversationScreen(cardAside: State<Boolean>) {
    val state = AssistantState
    val c = state.current
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val layer = rememberGraphicsLayer()
    val backdrop = remember(layer) { ChatBackdrop(layer) }
    val messages = c.messages.toList()
    val hasMessages = messages.isNotEmpty()
    val groups = remember(messages) { conversationGroups(messages) }
    val groupKeys = remember(groups) { conversationGroupKeys(groups) }
    val anchors = workTaskAnchors(c, groups)
    val jobs = rememberCoroutineScope()
    val density = LocalDensity.current
    var followTail by rememberSaveable(c.id) { mutableStateOf(true) }
    val dragging by list.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragging) { if (dragging) followTail = false }
    LaunchedEffect(list.isScrollInProgress) {
        if (!list.isScrollInProgress && !list.canScrollForward) followTail = true
    }
    val task =
        state.tasks.lastOrNull {
            it.sessionId == c.id && it.status in listOf("queued", "running", "waiting", "stopping")
        }
    val question = state.running?.takeIf { it.sessionId == c.id && it.question.isNotBlank() }
    LaunchedEffect(
        c.id,
        groups.size,
        groups.lastOrNull()?.lastOrNull()?.text?.length,
        task?.status,
        followTail,
    ) {
        if (followTail && !dragging) list.scrollToConversationEnd()
    }
    ConversationLayout(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        backdrop = backdrop,
        composer = {
            Column {
                if (question != null) QuestionCard(question)
                Composer(c, if (hasMessages) backdrop else null) {
                    when (AgentService.current?.send(c.draft.trim())) {
                        AgentService.SendResult.MISSING_KEY ->
                            android.widget.Toast.makeText(
                                    context,
                                    "无有效api key",
                                    android.widget.Toast.LENGTH_SHORT,
                                )
                                .show()
                        AgentService.SendResult.QUEUED -> {
                            keyboard?.hide()
                            followTail = true
                            list.requestScrollToItem(conversationGroups(c.messages).size)
                            jobs.launch { list.scrollToConversationEnd() }
                        }
                        null -> state.error = "连接引擎中，请稍后再试。"
                        else -> Unit
                    }
                }
            }
        },
        companion = {
            if (state.petEnabled)
                WhaleDock(
                    allowBubble = question == null,
                    wanderEnabled = !cardAside.value && question == null,
                    idleKey = c.id to c.draft,
                )
        },
        header = {
            Box(
                Modifier.fillMaxWidth()
                    .height(
                        82.dp +
                            with(LocalDensity.current) {
                                WindowInsets.statusBars.getTop(this).toDp()
                            },
                    )
                    .clipToBounds(),
            ) {
                HeaderBackdrop(if (hasMessages) backdrop else null, Modifier.matchParentSize())
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxSize()
                    .then(if (hasMessages) Modifier.chatBackdrop(backdrop) else Modifier),
            ) {
                if (c.messages.isEmpty())
                    Text(
                        "你想在${state.projects.firstOrNull { it.id == c.projectId }?.title?.take(12) ?: "DSHA"}中构建什么？",
                        Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
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
                                    if (anchors.size >= 2 && ExperienceSettings.taskNavigation)
                                        32.dp
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
                        itemsIndexed(groups, key = { index, _ -> groupKeys[index] }) { index, group
                            ->
                            if (group.first().type == "user" && group.size == 1)
                                ChatMessage(group.first())
                            else {
                                val taskId = group.firstOrNull { it.taskId.isNotBlank() }?.taskId
                                val task = AssistantState.tasks.firstOrNull { it.id == taskId }
                                val completed =
                                    task?.status == "done" ||
                                        (task == null &&
                                            group.none { it.type.startsWith("stream_") } &&
                                            (index < groups.lastIndex || !state.busy))
                                AgentTimeline(group, completed = completed)
                            }
                        }
                        if (task != null)
                            item {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(vertical = 8.dp),
                                ) {
                                    val phase =
                                        when (task.status) {
                                            "queued",
                                            "waiting",
                                            "stopping" -> AvatarPhase.WAITING
                                            else -> task.avatarPhase
                                        }
                                    if (
                                        AgentIdentity.mode in
                                            setOf(AvatarMode.WHALE, AvatarMode.DOTS) &&
                                            !cardAside.value
                                    )
                                        AgentAvatar(23.dp, phase)
                                    else
                                        Box(
                                            Modifier.size(5.dp)
                                                .background(
                                                    WhaleBlue,
                                                    androidx.compose.foundation.shape.CircleShape,
                                                ),
                                        )
                                    Text(
                                        when (task.status) {
                                            "queued" -> "上一条回复结束后继续"
                                            "stopping" -> "正在停止"
                                            else -> task.progress
                                        },
                                        fontSize = 12.sp,
                                        color = WhaleMuted,
                                        modifier = Modifier.padding(start = 9.dp).weight(1f),
                                    )
                                    if (task.status == "queued")
                                        SmallIcon(AppIcons.Close, "取消这条消息") {
                                            AgentService.current?.cancelTask(task.id)
                                        }
                                }
                            }
                    }
            }
            if (hasMessages)
                ScrollEdgeGlass(
                    backdrop,
                    top = false,
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(170.dp),
                )
            TaskNavigator(
                anchors,
                { list.firstVisibleItemIndex },
                Modifier.padding(
                    top =
                        with(LocalDensity.current) { WindowInsets.statusBars.getTop(this).toDp() } +
                            72.dp,
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
private fun Suggestion(text: String, click: () -> Unit) {
    Surface(onClick = click, shape = ContinuousShape(14.dp), color = Color(0xFFF5F6F9)) {
        Text(
            text,
            Modifier.padding(horizontal = 15.dp, vertical = 12.dp),
            fontSize = 12.sp,
            color = WhaleMuted,
        )
    }
}
