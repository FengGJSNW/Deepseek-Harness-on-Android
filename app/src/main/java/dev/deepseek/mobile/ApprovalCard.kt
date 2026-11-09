package dev.deepseek.mobile

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal fun PhoneTask.reviewTitle(sharedMemory: Boolean = false): String =
    when {
        sharedMemory -> "共享记忆更新"
        terminalApproval -> "批准终端命令"
        approval -> "批准这项操作"
        else -> "Agent 需要你的回复"
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QuestionCard(task: PhoneTask, modifier: Modifier = Modifier) {
    val proposal = AssistantState.pendingMemory?.takeIf { it.taskId == task.id }
    val title = task.reviewTitle(proposal != null)
    var answer by rememberSaveable(task.id, task.question) { mutableStateOf("") }
    var entered by remember(task.id, task.question) { mutableStateOf(false) }
    val reveal =
        animateFloatAsState(
            if (entered) 1f else 0f,
            UiSpring.navigation(.001f),
            label = "review-dock",
        )
    LaunchedEffect(task.id, task.question) { entered = true }
    Surface(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .functionIslandReveal { reveal.value }
            .testTag("agent-review-card"),
        shape = ContinuousShape(20.dp),
        color = Color(0xFFFCFBF9),
        border = BorderStroke(1.dp, WhaleLine),
        shadowElevation = 4.dp,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(32.dp).background(WhaleTint, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        when {
                            proposal != null -> AppIcons.Description
                            task.terminalApproval -> AppIcons.Terminal
                            task.approval -> AppIcons.Shield
                            else -> AppIcons.Chat
                        },
                        null,
                        Modifier.size(17.dp),
                        tint = WhaleBlue,
                    )
                }
                Column(Modifier.padding(start = 9.dp)) {
                    Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (task.approval) "任务已暂停，等待你的决定" else "回复后 Agent 会继续处理",
                        fontSize = 11.sp,
                        color = WhaleMuted,
                    )
                }
            }
            HorizontalDivider(Modifier.padding(top = 9.dp), color = WhaleLine)
            SelectionContainer {
                Column(
                    Modifier.fillMaxWidth()
                        .heightIn(max = 130.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 9.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    if (proposal != null) {
                        Text(
                            proposal.before.filename,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            if (proposal.before.scope == "global") "影响所有 Work 会话" else "影响项目中的会话",
                            fontSize = 11.sp,
                            color = WhaleMuted,
                        )
                        listOf("原内容" to proposal.before.text, "新内容" to proposal.after).forEach {
                            (label, text) ->
                            Text(label, fontSize = 11.sp, color = WhaleMuted)
                            Text(
                                text.ifBlank { "（空白）" },
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                                modifier =
                                    Modifier.fillMaxWidth()
                                        .background(WhaleMist, ContinuousShape(8.dp))
                                        .padding(8.dp),
                            )
                        }
                    } else
                        Text(
                            task.question,
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            fontFamily =
                                if (task.terminalApproval) DshaFonts.mono
                                else LocalTextStyle.current.fontFamily,
                        )
                }
            }
            if (!task.approval)
                OutlinedTextField(
                    answer,
                    { answer = it.take(3000) },
                    placeholder = { Text("输入回复") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                    shape = ContinuousShape(12.dp),
                    visualTransformation = UprightSymbols.transformation,
                )
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.End),
            ) {
                TextButton(
                    onClick = {
                        val service = AgentService.current
                        when {
                            proposal != null -> service?.answerMemory(proposal, false)
                            task.terminalApproval -> service?.answerTerminal("deny")
                            task.approval -> service?.denyCurrentApproval()
                            else -> service?.stopTask()
                        }
                    },
                ) {
                    Text(if (task.approval) "拒绝" else "中断任务")
                }
                if (task.terminalApproval)
                    TextButton(onClick = { AgentService.current?.answerTerminal("similar") }) {
                        Text("允许类似命令")
                    }
                Button(
                    enabled = task.approval || answer.isNotBlank(),
                    onClick = {
                        val service = AgentService.current
                        when {
                            proposal != null -> service?.answerMemory(proposal, true)
                            task.terminalApproval -> service?.answerTerminal("once")
                            else -> service?.answer(answer.trim(), task.approval)
                        }
                    },
                ) {
                    Text(
                        when {
                            proposal != null -> "允许此次更新"
                            task.terminalApproval -> "允许本次命令"
                            task.approval -> "允许这次操作"
                            else -> "回复"
                        },
                    )
                }
            }
        }
    }
}
