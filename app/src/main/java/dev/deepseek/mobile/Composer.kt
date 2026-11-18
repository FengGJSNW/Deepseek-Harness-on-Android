package dev.deepseek.mobile

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
internal fun Composer(c: Conversation, onSend: () -> Unit) {
    val haptics = rememberUiHaptics()
    val modelPress = remember { MutableInteractionSource() }
    var menu by remember(c.id) { mutableStateOf("") }
    var preview by rememberDraftPreview(c.id)
    LaunchedEffect(c.id, c.draft.isBlank()) { if (c.draft.isBlank()) preview = false }
    val busy = AssistantState.running?.sessionId == c.id
    ComposerFrame {
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
        SandboxComposerBadge(c)
        key(c.id) {
            ComposerDraft(
                c.draft,
                { c.draft = it },
                if (c.creatorMode) "描述你想制作的插件" else "给鲸鲸发送消息",
                "message-input",
                modifier = Modifier.weight(1f, fill = false),
                preview = preview,
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ToolbarMenuAnchor(
                menu == "attach",
                { if (menu == "attach") menu = "" },
                270.dp,
                openOnPress = { menu = "attach" },
                anchor = {
                    ToolbarButton(AppIcons.Paperclip, "添加附件", "toolbar-Paperclip") {
                        menu = if (menu == "attach") "" else "attach"
                    }
                },
            ) {
                ComposerOptions("attach", c) { menu = "" }
            }
            ToolbarMenuAnchor(
                menu == "access",
                { if (menu == "access") menu = "" },
                304.dp,
                openOnPress = { menu = "access" },
                anchor = {
                    ToolbarButton(
                        accessIcon(c.access),
                        "操作权限：${accessTitle(c.access)}",
                        "toolbar-Access",
                        accessColor(c.access),
                    ) {
                        menu = if (menu == "access") "" else "access"
                    }
                },
            ) {
                ComposerOptions("access", c) { menu = "" }
            }
            ToolbarMenuAnchor(
                menu == "terminal",
                { if (menu == "terminal") menu = "" },
                272.dp,
                openOnPress = { menu = "terminal" },
                anchor = {
                    ToolbarButton(
                        terminalIcon(c.terminalLevel),
                        if (c.sandboxEnabled) "终端：Sandbox"
                        else
                            "终端：${if (c.terminalEnabled) TerminalLevel.of(c.terminalLevel).title else "已关闭"}",
                        "toolbar-Terminal",
                        if (c.terminalEnabled) terminalColor(c.terminalLevel) else WhaleMuted,
                    ) {
                        menu = if (menu == "terminal") "" else "terminal"
                    }
                },
            ) {
                ComposerOptions("terminal", c) { menu = "" }
            }
            key(c.id) {
                MarkdownPreviewToggle(
                    preview,
                    c.draft.isNotBlank(),
                    menuExpanded = menu == "markdown",
                    onMenuExpandedChange = { open ->
                        if (open) menu = "markdown" else if (menu == "markdown") menu = ""
                    },
                ) {
                    preview = it
                }
            }
            ToolbarMenuAnchor(
                menu == "model",
                { if (menu == "model") menu = "" },
                UiTuning.ReasoningPanelMaxWidth,
                openOnPress = { menu = "model" },
                framed = false,
                modifier = Modifier.weight(1f).height(44.dp).testTag("model-control"),
                anchor = {
                    Row(
                        Modifier.fillMaxSize()
                            .dshaPressFeedback(modelPress, shape = ContinuousShape(12.dp))
                            .clickable(interactionSource = modelPress, indication = null) {
                                haptics.perform(UiHaptic.Selection)
                                menu = if (menu == "model") "" else "model"
                            }
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
                                        fontWeight = FontWeight.SemiBold,
                                    ),
                                ) {
                                    append(
                                        " · ${effortStops.firstOrNull { it.value == c.effort }?.title ?: "Low"}",
                                    )
                                }
                            },
                            Modifier.weight(1f, fill = false),
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = DshaFonts.family(FontRole.UI, weight = 600),
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
                        (c.draft.isNotBlank() || c.images.isNotEmpty() || c.files.isNotEmpty()))
            ComposerSendButton(enabled, stop = stop) {
                if (stop) AgentService.current?.stopTask() else onSend()
            }
        }
    }
}

@Composable
internal fun ComposerFrame(content: @Composable ColumnScope.() -> Unit) {
    val shape = remember { ContinuousShape(28.dp) }
    // Gesture navigation fits below the controls. Reserve more space for 3-button navigation.
    val navigation =
        with(LocalDensity.current) { WindowInsets.navigationBars.getBottom(this).toDp() }
    val bottomMargin =
        12.dp + if (WindowInsets.ime.getBottom(LocalDensity.current) > 0) 0.dp else navigation
    BoxWithConstraints(
        Modifier.fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = bottomMargin)
            .testTag("composer-margin"),
    ) {
        val headerClearance =
            82.dp + with(LocalDensity.current) { WindowInsets.statusBars.getTop(this).toDp() }
        val panelLimit =
            minOf(
                LocalConfiguration.current.screenHeightDp.dp / 2,
                (maxHeight - headerClearance).coerceAtLeast(54.dp),
            )
        Box(
            Modifier.fillMaxWidth()
                .floatingIslandShadow(shape)
                .clip(shape)
                .background(Color(0xFFF9FAFC))
                .border(.7.dp, Color(0xFFE9EBF1).copy(alpha = .7f), shape)
                .testTag("chat-island"),
        ) {
            Column(
                Modifier.heightIn(max = panelLimit).padding(horizontal = 6.dp, vertical = 5.dp),
                content = content,
            )
        }
    }
}

@Composable
internal fun ComposerSendButton(
    enabled: Boolean,
    stop: Boolean = false,
    waiting: Boolean = false,
    tag: String = "composer-send",
    label: String = if (stop) "停止回复" else "发送",
    onClick: () -> Unit,
) {
    val haptics = rememberUiHaptics()
    val interactions = remember { MutableInteractionSource() }
    val glassMotion = rememberGlassPress()
    CompositionLocalProvider(LocalGlassPress provides glassMotion) {
        Box(
            Modifier.size(44.dp)
                .testTag(tag)
                .glassPress(glassMotion, enabled)
                .clickable(enabled = enabled, interactionSource = interactions, indication = null) {
                    haptics.perform(if (stop) UiHaptic.Selection else UiHaptic.Submit)
                    onClick()
                }
                .semantics {
                    contentDescription = label
                    if (!enabled) disabled()
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(36.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
                LiquidGlass(
                    Modifier.matchParentSize(),
                    corner = 18f,
                    tint = if (enabled) WhaleBlue.copy(alpha = .68f) else Color(0x80AEB7C6),
                    finish = GlassFinish.Tinted,
                    shape = CircleShape,
                )
                if (waiting)
                    CircularProgressIndicator(
                        Modifier.size(17.dp),
                        color = WhaleBlue,
                        strokeWidth = 2.dp,
                    )
                else
                    Icon(
                        if (stop) AppIcons.Stop else AppIcons.ArrowUpward,
                        null,
                        Modifier.size(if (stop) 15.dp else 20.dp)
                            .testTag(if (stop) "toolbar-Stop" else "toolbar-ArrowUpward"),
                        tint =
                            when {
                                !enabled -> Color(0xFFA6AEBC)
                                else -> Color.White
                            },
                    )
            }
        }
    }
}

@Composable
internal fun ToolbarButton(
    icon: ImageVector,
    label: String,
    tag: String,
    tint: Color = WhaleInk,
    enabled: Boolean = true,
    action: () -> Unit,
) {
    DshaIconButton(label = label, onClick = action, enabled = enabled, size = 44.dp) {
        Icon(
            icon,
            null,
            Modifier.size(22.dp).testTag(tag),
            tint = if (enabled) tint else WhaleMuted.copy(alpha = .5f),
        )
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
        Color(0xFF3269C5),
        Color(0xFF8650BC),
        index / effortStops.lastIndex.toFloat(),
    )
}
