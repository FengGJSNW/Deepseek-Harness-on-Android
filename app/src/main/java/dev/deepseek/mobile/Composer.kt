package dev.deepseek.mobile

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.deepseek.mobile.terminal.TerminalLevel

/** Floating composer. Configuration editors live in SettingsScreen. */
@Composable
internal fun Composer(c: Conversation, backdrop: ChatBackdrop? = null, onSend: () -> Unit) {
    var menu by remember { mutableStateOf("") }
    val shape = remember { ContinuousShape(28.dp) }
    val busy = AssistantState.running?.sessionId == c.id
    // Gesture navigation fits below the controls. Reserve more space for 3-button navigation.
    val navigation =
        with(LocalDensity.current) { WindowInsets.navigationBars.getBottom(this).toDp() }
    val bottomMargin =
        12.dp + if (WindowInsets.ime.getBottom(LocalDensity.current) > 0) 0.dp else navigation
    Box(
        Modifier.fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = bottomMargin)
            .testTag("composer-margin"),
    ) {
        Box(
            Modifier.fillMaxWidth()
                .floatingIslandShadow(shape)
                .clip(shape)
                .border(.7.dp, Color(0xFFE9EBF1).copy(alpha = .7f), shape)
                .testTag("chat-island"),
        ) {
            FrostedGlass(backdrop, Modifier.matchParentSize(), Color(0xFFF9FAFC).copy(alpha = .82f))
            Column(Modifier.padding(horizontal = 6.dp, vertical = 5.dp)) {
                if (c.creatorMode)
                    Text(
                        "Creator · 插件创作",
                        Modifier.padding(start = 12.dp, top = 5.dp),
                        fontSize = 12.sp,
                        color = WhaleBlue,
                    )
                if (c.images.isNotEmpty())
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        c.images.toList().forEach { id ->
                            Box {
                                AttachmentImage(id, 48)
                                IconButton(
                                    onClick = {
                                        c.images.remove(id)
                                        AssistantState.persist()
                                    },
                                    modifier =
                                        Modifier.align(Alignment.TopEnd)
                                            .size(24.dp)
                                            .background(Color.White, CircleShape),
                                ) {
                                    Icon(AppIcons.Close, "移除附件", Modifier.size(14.dp))
                                }
                            }
                        }
                    }
                if (c.files.isNotEmpty())
                    Column(
                        Modifier.fillMaxWidth().padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        c.files.toList().forEach { id ->
                            AttachmentCard(id) {
                                c.files.remove(id)
                                AssistantState.persist()
                            }
                        }
                    }
                BasicTextField(
                    value = c.draft,
                    onValueChange = { c.draft = it },
                    maxLines = 4,
                    textStyle =
                        MaterialTheme.typography.bodyMedium.copy(
                            color = WhaleInk,
                            fontSize = 16.sp,
                            lineHeight = 23.sp,
                        ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(WhaleBlue),
                    modifier =
                        Modifier.fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .padding(horizontal = 12.dp, vertical = 12.dp)
                            .testTag("message-input"),
                    decorationBox = { inner ->
                        Box {
                            if (c.draft.isEmpty())
                                Text(
                                    if (c.creatorMode) "描述你想制作的插件" else "给鲸鲸发送消息",
                                    color = WhaleMuted,
                                    fontSize = 16.sp,
                                )
                            inner()
                        }
                    },
                    visualTransformation = UprightSymbols.transformation,
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ToolbarMenuAnchor(
                        menu == "attach",
                        { menu = "" },
                        270.dp,
                        anchor = {
                            ToolbarButton(AppIcons.Paperclip, "添加附件", "toolbar-Paperclip") {
                                menu = "attach"
                            }
                        },
                    ) {
                        ComposerOptions("attach", c) { menu = "" }
                    }
                    ToolbarMenuAnchor(
                        menu == "access",
                        { menu = "" },
                        304.dp,
                        anchor = {
                            ToolbarButton(
                                accessIcon(c.access),
                                "操作权限：${accessTitle(c.access)}",
                                "toolbar-Access",
                                accessColor(c.access),
                            ) {
                                menu = "access"
                            }
                        },
                    ) {
                        ComposerOptions("access", c) { menu = "" }
                    }
                    ToolbarMenuAnchor(
                        menu == "terminal",
                        { menu = "" },
                        272.dp,
                        anchor = {
                            ToolbarButton(
                                terminalIcon(c.terminalLevel),
                                "终端：${if (c.terminalEnabled) TerminalLevel.of(c.terminalLevel).title else "已关闭"}",
                                "toolbar-Terminal",
                                if (c.terminalEnabled) terminalColor(c.terminalLevel)
                                else WhaleMuted,
                            ) {
                                menu = "terminal"
                            }
                        },
                    ) {
                        ComposerOptions("terminal", c) { menu = "" }
                    }
                    ToolbarMenuAnchor(
                        menu == "model",
                        { menu = "" },
                        UiTuning.ReasoningPanelMaxWidth,
                        framed = false,
                        modifier = Modifier.weight(1f).height(44.dp).testTag("model-control"),
                        anchor = {
                            Row(
                                Modifier.fillMaxSize()
                                    .clip(ContinuousShape(12.dp))
                                    .clickable { menu = "model" }
                                    .semantics { contentDescription = "模型与思考设置" },
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    buildAnnotatedString {
                                        append(if (c.model == "deepseek-flash") "Flash" else "Pro")
                                        withStyle(
                                            SpanStyle(
                                                color = composerEffortColor(c.effort),
                                                fontWeight = FontWeight.Medium,
                                            ),
                                        ) {
                                            append(
                                                " · ${effortStops.firstOrNull { it.value == c.effort }?.title ?: "Low"}",
                                            )
                                        }
                                    },
                                    Modifier.weight(1f, fill = false),
                                    fontSize = 13.sp,
                                    color = WhaleInk,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Icon(
                                    AppIcons.ExpandMore,
                                    null,
                                    Modifier.padding(start = 3.dp).size(12.dp),
                                    tint = WhaleMuted,
                                )
                            }
                        },
                    ) {
                        ReasoningCard(c, Modifier.fillMaxWidth())
                    }
                    val stop = busy && c.draft.isBlank() && c.images.isEmpty()
                    val enabled =
                        stop ||
                            (AssistantState.ready &&
                                (c.draft.isNotBlank() ||
                                    c.images.isNotEmpty() ||
                                    c.files.isNotEmpty()))
                    Box(
                        Modifier.size(44.dp)
                            .testTag("composer-send")
                            .clip(CircleShape)
                            .clickable(enabled = enabled) {
                                if (stop) AgentService.current?.stopTask() else onSend()
                            }
                            .semantics {
                                contentDescription = if (stop) "停止回复" else "发送"
                                if (!enabled) disabled()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier.size(32.dp)
                                .clip(CircleShape)
                                .background(if (enabled) WhaleBlue else Color(0xFFE8EBF1)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                if (stop) AppIcons.Stop else AppIcons.ArrowUpward,
                                null,
                                Modifier.size(if (stop) 15.dp else 20.dp)
                                    .testTag(if (stop) "toolbar-Stop" else "toolbar-ArrowUpward"),
                                tint = if (enabled) Color.White else Color(0xFFA6AEBC),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolbarButton(
    icon: ImageVector,
    label: String,
    tag: String,
    tint: Color = WhaleInk,
    action: () -> Unit,
) {
    IconButton(onClick = action, modifier = Modifier.size(44.dp)) {
        Icon(icon, label, Modifier.size(22.dp).testTag(tag), tint = tint)
    }
}

internal fun accessTitle(value: String) =
    when (value) {
        "none",
        "read" -> "完全禁止"
        "auto" -> "替我审批"
        "full" -> "完全访问"
        else -> "受限访问"
    }

internal fun accessIcon(value: String) =
    when (value) {
        "none",
        "read" -> AppIcons.ShieldOff
        "auto" -> AppIcons.ShieldAuto
        "full" -> AppIcons.ShieldOpen
        else -> AppIcons.ShieldCheck
    }

internal fun accessColor(value: String) =
    when (value) {
        "none",
        "read" -> Color(0xFF8C94A3)
        "auto" -> Color(0xFF8B63C8)
        "full" -> Color(0xFFD98543)
        else -> Color(0xFF497CBE)
    }

internal fun terminalIcon(value: String) =
    when (value) {
        "app" -> AppIcons.TerminalUser
        "shell" -> AppIcons.TerminalShell
        "root_guarded" -> AppIcons.TerminalGuard
        "root" -> AppIcons.TerminalRoot
        else -> AppIcons.TerminalBox
    }

internal fun terminalColor(value: String) =
    when (value) {
        "app" -> Color(0xFF3F8C8B)
        "shell" -> Color(0xFF7970CC)
        "root_guarded" -> Color(0xFFAD8740)
        "root" -> Color(0xFFCC6E65)
        else -> WhaleBlue
    }

internal fun composerEffortColor(value: String): Color {
    val index = effortStops.indexOfFirst { it.value == value }.coerceAtLeast(0)
    return androidx.compose.ui.graphics.lerp(
        Color(0xFF4387ED),
        Color(0xFFA266E5),
        index / effortStops.lastIndex.toFloat(),
    )
}
