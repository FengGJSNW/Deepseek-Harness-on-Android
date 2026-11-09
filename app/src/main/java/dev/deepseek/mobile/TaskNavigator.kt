package dev.deepseek.mobile

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

internal data class TaskAnchor(
    val key: String,
    val item: Int,
    val title: String,
    val activity: String,
    val reply: String,
)

internal fun previewText(text: String) = text.take(600).replace(Regex("\\s+"), " ").take(180)

/** An in-conversation index. It navigates history and never modifies the execution queue. */
@Composable
internal fun TaskNavigator(
    anchors: List<TaskAnchor>,
    visibleItem: () -> Int,
    modifier: Modifier = Modifier,
    onJump: (TaskAnchor) -> Unit,
) {
    if (!ExperienceSettings.taskNavigation || anchors.size < 2) return
    val latest by rememberUpdatedState(anchors)
    val jump by rememberUpdatedState(onJump)
    var preview by remember(anchors.first().key) { mutableIntStateOf(-1) }
    val active by
        remember(anchors, visibleItem) {
            derivedStateOf { anchors.indexOfLast { it.item <= visibleItem() }.coerceAtLeast(0) }
        }
    BoxWithConstraints(modifier.fillMaxSize().testTag("task-navigation")) {
        val railHeight =
            (anchors.size * 14)
                .dp
                .coerceAtMost(maxHeight * .75f)
                .coerceAtLeast(28.dp)
                .coerceAtMost(maxHeight)
        val top = (maxHeight - railHeight) / 2
        val selected = preview.takeIf { it in anchors.indices }
        Canvas(
            Modifier.align(Alignment.CenterStart)
                .width(26.dp)
                .height(railHeight)
                .testTag("task-navigation-rail")
                .semantics {
                    contentDescription = "任务导航，共${anchors.size}项，第${active+1}项"
                    progressBarRangeInfo =
                        ProgressBarRangeInfo(
                            active.toFloat(),
                            0f..(anchors.lastIndex).toFloat(),
                            anchors.size - 2,
                        )
                    setProgress { value ->
                        val i = value.roundToInt().coerceIn(0, latest.lastIndex)
                        preview = i
                        jump(latest[i])
                        true
                    }
                }
                .pointerInput(anchors.size) {
                    detectTapGestures { point ->
                        preview =
                            (point.y / size.height * (latest.lastIndex))
                                .roundToInt()
                                .coerceIn(0, latest.lastIndex)
                    }
                }
                .pointerInput(anchors.size) {
                    detectDragGestures(
                        onDragStart = { point ->
                            preview =
                                (point.y / size.height * latest.lastIndex)
                                    .roundToInt()
                                    .coerceIn(0, latest.lastIndex)
                        },
                        onDragEnd = {
                            if (preview in latest.indices) jump(latest[preview])
                            preview = -1
                        },
                        onDragCancel = { preview = -1 },
                    ) { change, _ ->
                        change.consume()
                        preview =
                            (change.position.y / size.height * latest.lastIndex)
                                .roundToInt()
                                .coerceIn(0, latest.lastIndex)
                    }
                },
        ) {
            val count = anchors.size.coerceAtMost(28)
            for (i in 0 until count) {
                val y = i.toFloat() / (count - 1) * (size.height - 4.dp.toPx()) + 2.dp.toPx()
                drawLine(
                    WhaleMuted.copy(alpha = .35f),
                    Offset(8.dp.toPx(), y),
                    Offset(15.dp.toPx(), y),
                    1.5.dp.toPx(),
                )
            }
            val selectedY =
                (selected ?: active).toFloat() / anchors.lastIndex * (size.height - 4.dp.toPx()) +
                    2.dp.toPx()
            drawLine(
                WhaleBlue,
                Offset(8.dp.toPx(), selectedY),
                Offset(23.dp.toPx(), selectedY),
                2.dp.toPx(),
            )
        }
        if (selected != null) {
            val anchor = anchors[selected]
            val relative = selected.toFloat() / anchors.lastIndex
            val cardHeight = minOf(220.dp, maxHeight)
            val y =
                (top + railHeight * relative - cardHeight / 2).coerceIn(
                    0.dp,
                    (maxHeight - cardHeight).coerceAtLeast(0.dp),
                )
            Surface(
                Modifier.offset(x = 28.dp, y = y)
                    .width(minOf(320.dp, (maxWidth - 52.dp).coerceAtLeast(80.dp)))
                    .heightIn(max = cardHeight)
                    .floatingIslandShadow(ContinuousShape(18.dp))
                    .testTag("task-navigation-preview"),
                shape = ContinuousShape(18.dp),
                color = Color.White,
                border = BorderStroke(.7.dp, WhaleLine),
            ) {
                Column(
                    Modifier.verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "任务 ${selected+1} / ${anchors.size}",
                            Modifier.weight(1f),
                            fontSize = 10.sp,
                            color = WhaleMuted,
                        )
                        IconButton(onClick = { preview = -1 }, modifier = Modifier.size(28.dp)) {
                            Icon(AppIcons.Close, "关闭任务预览", Modifier.size(14.dp), tint = WhaleMuted)
                        }
                    }
                    Text(
                        anchor.title,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        anchor.activity,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 11.sp,
                        color = WhaleBlue,
                    )
                    if (anchor.reply.isNotBlank())
                        Text(
                            anchor.reply,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = WhaleMuted,
                        )
                    TextButton(
                        onClick = {
                            jump(anchor)
                            preview = -1
                        },
                        modifier = Modifier.testTag("task-navigation-jump"),
                    ) {
                        Text("跳转到此任务", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

internal fun workTaskAnchors(c: Conversation, groups: List<List<ChatEntry>>): List<TaskAnchor> =
    groups
        .mapIndexedNotNull { index, entries ->
            val user =
                entries.first().takeIf { it.type == "user" && entries.size == 1 }
                    ?: return@mapIndexedNotNull null
            val task =
                AssistantState.tasks.lastOrNull {
                    it.sessionId == c.id &&
                        (if (user.taskId.isNotBlank()) it.id == user.taskId
                        else it.text == user.text)
                }
            val response =
                groups
                    .getOrNull(index + 1)
                    ?.takeIf { it.first().type != "user" || it.size > 1 }
                    .orEmpty()
                    .lastOrNull { it.type in setOf("assistant", "stream_assistant") }
            TaskAnchor(
                user.taskId.ifBlank { "${c.id}-$index" },
                index,
                previewText(user.text.ifBlank { "附件任务" }),
                task?.let {
                    when (it.status) {
                        "queued" -> "等待执行"
                        "done" -> "已完成"
                        "failed",
                        "error" -> "执行失败"
                        "cancelled",
                        "interrupted" -> "已中断"
                        else -> it.progress
                    }
                } ?: "会话记录",
                previewText(response?.text.orEmpty()),
            )
        }
        .distinctBy { it.key }
