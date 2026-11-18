package dev.deepseek.mobile

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.deepseek.mobile.workflow.*

@Composable
internal fun WorkflowComposerRow(c: Conversation) {
    var choosing by remember(c.id) { mutableStateOf(false) }
    val plan = c.workPlan
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolbarMenuAnchor(
            choosing,
            { choosing = false },
            268.dp,
            anchor = {
                Row(
                    Modifier.height(32.dp)
                        .clip(ContinuousShape(8.dp))
                        .clickable { choosing = !choosing }
                        .padding(horizontal = 8.dp)
                        .testTag("workflow-mode"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        c.workMode.title,
                        color = if (c.workMode == WorkMode.EXECUTE) WhaleMuted else WhaleBlue,
                        fontSize = 12.sp,
                    )
                    Icon(
                        AppIcons.ExpandMore,
                        "工作模式：${c.workMode.title}",
                        Modifier.size(14.dp),
                        tint = WhaleMuted,
                    )
                }
            },
        ) {
            Column(Modifier.padding(10.dp)) {
                WorkMode.entries.forEach { mode ->
                    ModeRow(mode, c.workMode == mode) {
                        c.workMode = mode
                        AssistantState.persist()
                        choosing = false
                    }
                }
                Text("切换仅影响下一次提交的任务", Modifier.padding(8.dp), fontSize = 11.sp, color = WhaleMuted)
            }
        }
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.height(32.dp)
                .clip(ContinuousShape(8.dp))
                .clickable { WorkspaceState.open(WorkspaceTab.Workflow) }
                .padding(horizontal = 8.dp)
                .testTag("workflow-open"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(AppIcons.Checklist, null, Modifier.size(14.dp), tint = WhaleMuted)
            Text(
                if (plan.steps.isEmpty()) "计划与验证"
                else "计划 ${plan.steps.count { it.status == "completed" }}/${plan.steps.size}",
                fontSize = 11.sp,
                color = WhaleMuted,
            )
        }
    }
}

@Composable
private fun ModeRow(mode: WorkMode, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(ContinuousShape(10.dp))
            .background(if (selected) WhaleTint else Color.Transparent)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(mode.title, fontSize = 14.sp, color = if (selected) WhaleBlue else WhaleInk)
            Text(mode.description, fontSize = 11.sp, lineHeight = 17.sp, color = WhaleMuted)
        }
        if (selected) Icon(AppIcons.Check, null, Modifier.size(16.dp), tint = WhaleBlue)
    }
}

@Composable
internal fun WorkflowWorkspace(c: Conversation) {
    val tasks = AssistantState.tasks.filter { it.sessionId == c.id }.takeLast(20).asReversed()
    var chosen by
        remember(c.id, tasks.firstOrNull()?.id) {
            mutableStateOf(tasks.firstOrNull()?.id.orEmpty())
        }
    val task = tasks.firstOrNull { it.id == chosen }
    val plan = task?.workPlan ?: c.workPlan
    val records = task?.verifications?.toList().orEmpty()
    val active = AssistantState.running?.sessionId == c.id
    LazyColumn(
        Modifier.fillMaxSize().background(DshaSurface.page).testTag("workflow-workspace"),
        contentPadding =
            PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = LocalWorkspaceTopInset.current + 16.dp,
                bottom = 16.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("计划与验证", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text("工作步骤与实际检查", Modifier.padding(top = 5.dp), fontSize = 12.sp, color = WhaleMuted)
        }
        item {
            DshaCard {
                DshaCardHeader(AppIcons.Terminal, "工作模式") {
                    WorkflowBadge("下一次任务", WhaleMuted)
                }
                DshaSegments(
                    WorkMode.entries.map { it to it.title },
                    c.workMode,
                    {
                        c.workMode = it
                        AssistantState.persist()
                    },
                    optionTag = { "workflow-${it.key}" },
                )
                Text(
                    c.workMode.description,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = WhaleMuted,
                )
            }
        }
        if (tasks.isNotEmpty())
            item {
                DshaCard {
                    DshaCardHeader(AppIcons.Chat, "任务") {
                        WorkflowBadge(task?.let { statusName(it.status) }.orEmpty(), WhaleMuted)
                    }
                    if (tasks.size > 1)
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            tasks.forEachIndexed { index, value ->
                                DshaButton(
                                    onClick = { chosen = value.id },
                                    style =
                                        if (value.id == chosen) DshaButtonStyle.Filled
                                        else DshaButtonStyle.Tonal,
                                    contentPadding =
                                        PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                                ) {
                                    Text(if (index == 0) "最近任务" else value.text.take(12))
                                }
                            }
                        }
                    Text(
                        task?.text.orEmpty(),
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "执行模式：${task?.workMode?.title.orEmpty()}",
                        fontSize = 11.sp,
                        color = WhaleMuted,
                    )
                }
            }
        item {
            val completed = plan.steps.count { it.status == "completed" }
            DshaCard {
                DshaCardHeader(AppIcons.Checklist, "工作计划") {
                    WorkflowBadge(
                        if (plan.steps.isEmpty()) "待规划" else "$completed / ${plan.steps.size}",
                        WhaleBlue,
                    )
                }
                Text(
                    if (plan.steps.isEmpty()) "还没有计划" else plan.goal,
                    fontSize = 17.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (plan.steps.isEmpty()) {
                    Text(
                        "复杂任务会在这里列出步骤；简单问题可以直接完成。",
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = WhaleMuted,
                    )
                } else {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        plan.steps.forEach { step ->
                            Box(
                                Modifier.weight(1f)
                                    .height(6.dp)
                                    .background(
                                        when (step.status) {
                                            "completed" -> WhaleBlue
                                            "in_progress" -> WhaleBlue.copy(alpha = .35f)
                                            else -> DshaSurface.inset
                                        },
                                        ContinuousShape(3.dp),
                                    ),
                            )
                        }
                    }
                    Text("步骤进度由 Agent 更新", fontSize = 11.sp, color = WhaleMuted)
                    Column(
                        Modifier.fillMaxWidth()
                            .clip(ContinuousShape(20.dp))
                            .background(DshaSurface.inset)
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        plan.steps.forEachIndexed { index, step -> WorkflowStep(index, step) }
                    }
                    DshaButton(
                        onClick = {
                            c.workMode = WorkMode.EXECUTE
                            c.workPlan = plan
                            if (c.draft.isBlank())
                                c.draft = "请继续执行已保存的计划：${plan.goal}。先复核现状和未完成步骤，再实施并验证。"
                            AssistantState.persist()
                            WorkspaceState.hide()
                        },
                        enabled = !active,
                        modifier = Modifier.fillMaxWidth().testTag("workflow-continue"),
                    ) {
                        Text(if (active) "当前任务进行中" else "按此计划准备下一次任务", fontSize = 13.sp)
                    }
                }
            }
        }
        item {
            DshaCard {
                DshaCardHeader(AppIcons.Check, "验证记录") {
                    WorkflowBadge("${records.size} 项", WhaleMuted)
                }
                if (records.isEmpty()) {
                    Text("尚未记录验证命令。", fontSize = 13.sp, color = WhaleMuted)
                } else
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WorkflowMetric(
                            "通过",
                            records.count { it.outcome == "passed" },
                            DshaSurface.success,
                            Modifier.weight(1f),
                        )
                        WorkflowMetric(
                            "未通过",
                            records.count { it.outcome !in setOf("passed", "running") },
                            DshaSurface.danger,
                            Modifier.weight(1f),
                        )
                        WorkflowMetric(
                            "进行中",
                            records.count { it.outcome == "running" },
                            WhaleBlue,
                            Modifier.weight(1f),
                        )
                    }
                Text(
                    "通过表示命令正常退出；验证范围仍需审查。",
                    fontSize = 11.sp,
                    lineHeight = 17.sp,
                    color = WhaleMuted,
                )
            }
        }
        items(records.asReversed(), key = { it.id }) { record -> VerificationCard(record) }
        item {
            DshaCard(padding = PaddingValues(6.dp)) {
                SettingsLink(AppIcons.Description, "查看文件改动", "对照本次任务的变更内容") {
                    ChangeState.taskId = task?.id.orEmpty()
                    ChangeState.show = true
                }
            }
        }
    }
}

@Composable
private fun WorkflowStep(index: Int, step: PlanStep) {
    val tint =
        when (step.status) {
            "completed" -> DshaSurface.success
            "in_progress" -> WhaleBlue
            "blocked" -> Color(0xFFAA6D25)
            else -> WhaleMuted
        }
    Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        Box(
            Modifier.size(28.dp).clip(ContinuousShape(14.dp)).background(tint.copy(alpha = .10f)),
            contentAlignment = Alignment.Center,
        ) {
            if (step.status == "completed")
                Icon(AppIcons.Check, null, Modifier.size(16.dp), tint = tint)
            else
                Text(
                    "${index + 1}",
                    color = tint,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(step.title, fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium)
            Text(
                when (step.status) {
                    "completed" -> "已完成"
                    "in_progress" -> "进行中"
                    "blocked" -> "受阻"
                    else -> "待开始"
                },
                fontSize = 11.sp,
                color = tint,
            )
            if (step.note.isNotBlank())
                Text(step.note, fontSize = 12.sp, lineHeight = 18.sp, color = WhaleMuted)
        }
    }
}

@Composable
private fun WorkflowBadge(text: String, tint: Color) {
    Text(
        text,
        Modifier.clip(ContinuousShape(20.dp))
            .background(tint.copy(alpha = .09f))
            .padding(horizontal = 9.dp, vertical = 5.dp),
        color = tint,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun WorkflowMetric(label: String, count: Int, tint: Color, modifier: Modifier) {
    Column(modifier.clip(ContinuousShape(16.dp)).background(DshaSurface.inset).padding(12.dp)) {
        Text("$count", color = tint, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
        Text(label, Modifier.padding(top = 4.dp), color = WhaleMuted, fontSize = 11.sp)
    }
}

@Composable
private fun VerificationCard(record: VerificationRecord) {
    var expanded by remember(record.id) { mutableStateOf(false) }
    val interactions = remember {
        androidx.compose.foundation.interaction.MutableInteractionSource()
    }
    val haptics = rememberUiHaptics()
    val angle by
        androidx.compose.animation.core.animateFloatAsState(
            if (expanded) 180f else 0f,
            label = "verification-expand",
        )
    val tint =
        when (record.outcome) {
            "passed" -> DshaSurface.success
            "failed" -> DshaSurface.danger
            "running" -> WhaleBlue
            else -> WhaleMuted
        }
    DshaCard(Modifier.animateContentSize()) {
        Row(
            Modifier.fillMaxWidth()
                .dshaPressFeedback(interactions)
                .clickable(
                    interactionSource = interactions,
                    indication = null,
                    role = Role.Button,
                ) {
                    haptics.perform(UiHaptic.Selection)
                    expanded = !expanded
                }
                .semantics { stateDescription = if (expanded) "已展开" else "已折叠" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                record.label,
                Modifier.weight(1f),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            WorkflowBadge(
                when (record.outcome) {
                    "passed" -> "通过"
                    "failed" -> "失败"
                    "running" -> "运行中"
                    "cancelled" -> "已取消"
                    "interrupted" -> "已中断"
                    else -> "未完成"
                },
                tint,
            )
            Icon(AppIcons.ExpandMore, null, Modifier.size(17.dp).rotate(angle), tint = WhaleMuted)
        }
        Text(
            record.command,
            Modifier.fillMaxWidth()
                .clip(ContinuousShape(16.dp))
                .background(DshaSurface.inset)
                .padding(12.dp),
            fontFamily = DshaFonts.mono,
            fontSize = 11.sp,
            lineHeight = 17.sp,
            color = WhaleInk,
            maxLines = if (expanded) 12 else 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (record.detail.isNotBlank())
            Text(record.detail, fontSize = 11.sp, lineHeight = 17.sp, color = WhaleMuted)
        if (expanded && record.runId.isNotBlank()) TerminalCard(record.runId)
    }
}
