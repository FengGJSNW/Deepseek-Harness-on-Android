package dev.deepseek.mobile

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*

/** Height and opacity move together; interruptions reverse from the currently drawn height. */
@Composable
internal fun DisclosureBody(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        expanded,
        modifier,
        enter =
            expandVertically(
                UiSpring.disclosure(IntSize.VisibilityThreshold),
                expandFrom = Alignment.Top,
                clip = true,
            ) + fadeIn(tween(170, 35)),
        exit =
            shrinkVertically(
                UiSpring.disclosure(IntSize.VisibilityThreshold),
                shrinkTowards = Alignment.Top,
                clip = true,
            ) + fadeOut(tween(100)),
    ) {
        Box(Modifier.fillMaxWidth()) { content() }
    }
}

@Composable
internal fun DisclosureChevron(expanded: Boolean) {
    val angle by
        animateFloatAsState(
            if (expanded) 90f else 0f,
            UiSpring.disclosure(.1f),
            label = "disclosure-chevron",
        )
    Icon(
        AppIcons.ChevronRight,
        null,
        Modifier.size(14.dp).graphicsLayer { rotationZ = angle },
        tint = WhaleMuted,
    )
}

@Composable
internal fun DisclosureHeader(
    title: String,
    expanded: Boolean,
    modifier: Modifier = Modifier,
    detail: String = "",
    icon: ImageVector = AppIcons.Checklist,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 38.dp)
            .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = WhaleMuted)
        Text(
            title,
            Modifier.weight(1f).padding(start = 8.dp, end = 8.dp),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = WhaleMuted,
        )
        if (detail.isNotBlank())
            Text(detail, Modifier.padding(end = 8.dp), fontSize = 10.sp, color = WhaleMuted)
        DisclosureChevron(expanded)
    }
}

@Composable
internal fun ActivityCard(
    modifier: Modifier = Modifier,
    framed: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (framed) {
        Surface(
            modifier.fillMaxWidth(),
            color = Color(0xFFFAFAFA),
            border = BorderStroke(.7.dp, ReadingStyle.workLine),
            shape = ContinuousShape(12.dp),
        ) {
            Column(content = content)
        }
    } else {
        Column(modifier.fillMaxWidth(), content = content)
    }
}

@Composable
internal fun ActivityDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier.padding(horizontal = 11.dp),
        color = ReadingStyle.workLine,
        thickness = .5.dp,
    )
}

@Composable
internal fun ThinkingDisclosure(entry: ChatEntry, owner: String, framed: Boolean = true) {
    var expanded by
        androidx.compose.runtime.saveable.rememberSaveable(owner, entry.taskId) {
            mutableStateOf(false)
        }
    ActivityCard(Modifier.testTag("thinking-disclosure"), framed) {
        DisclosureHeader(
            if (entry.type.startsWith("stream")) "思考中" else "思考过程",
            expanded,
            Modifier.testTag("thinking-toggle"),
            icon = AppIcons.Spark,
            onClick = { expanded = !expanded },
        )
        DisclosureBody(expanded, Modifier.testTag("thinking-body")) {
            Column {
                ActivityDivider()
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(
                            entry.text,
                            Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                            fontFamily = DshaFonts.family(FontRole.BODY),
                            fontSize = 13.sp,
                            lineHeight = 21.sp,
                            color = ReadingStyle.secondary,
                        )
                    }
                    TextButton(
                        onClick = { ReasoningState.open(entry, owner) },
                        contentPadding = PaddingValues(top = 4.dp),
                    ) {
                        Text("单独查看", fontSize = 10.sp, color = WhaleMuted)
                    }
                }
            }
        }
    }
}

internal fun activityStatusLabel(status: String): String =
    when (status) {
        "running" -> "执行中"
        "reviewing" -> "审查中"
        "waiting" -> "待确认"
        "failed",
        "error" -> "失败"
        "denied" -> "已拒绝"
        "cancelled",
        "stopped",
        "interrupted" -> "已停止"
        "paused" -> "已暂停"
        "completed",
        "success",
        "done" -> "已完成"
        else -> "执行记录"
    }
