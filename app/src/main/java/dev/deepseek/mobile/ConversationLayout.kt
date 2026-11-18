package dev.deepseek.mobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp

/** One measure per child during IME animation. Messages extend beneath floating chrome. */
@Composable
internal fun ConversationLayout(
    modifier: Modifier = Modifier,
    backdrop: ChatBackdrop? = null,
    composer: @Composable () -> Unit,
    companion: @Composable () -> Unit,
    header: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val owner = LocalView.current
    val companionLayer = rememberGraphicsLayer()
    val companionBackdrop = remember(companionLayer, owner) { ChatBackdrop(companionLayer, owner) }
    val menuOverlays =
        remember(backdrop, companionBackdrop) {
            if (backdrop == null) emptyList() else listOf(companionBackdrop)
        }
    Layout(
        modifier =
            modifier.onPlaced {
                backdrop?.viewport = it
                companionBackdrop.viewport = it
            },
        content = {
            Box(Modifier.fillMaxSize()) { content() }
            Box(Modifier.fillMaxWidth()) {
                CompositionLocalProvider(
                    LocalGlassBackdrop provides backdrop,
                    LocalMenuGlassOverlays provides menuOverlays,
                ) {
                    composer()
                }
            }
            Box(
                Modifier.fillMaxSize()
                    .then(
                        if (backdrop == null) Modifier
                        else Modifier.chatBackdrop(companionBackdrop),
                    )
                    .padding(horizontal = UiTuning.ComposerHorizontalMargin),
            ) {
                companion()
            }
            Box(Modifier.fillMaxWidth()) { header() }
        },
    ) { children, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val composer = children[1].measure(constraints.copy(minHeight = 0))
        val contentHeight = (height - composer.height).coerceAtLeast(0)
        val content = children[0].measure(Constraints.fixed(width, height))
        val companionHeight =
            (contentHeight +
                    UiTuning.ComposerVerticalMargin.roundToPx() +
                    UiTuning.PetLipOverlap.roundToPx())
                .coerceIn(0, height)
        val companion = children[2].measure(Constraints.fixed(width, companionHeight))
        val header = children[3].measure(constraints.copy(minHeight = 0))
        layout(width, height) {
            content.placeRelative(0, 0)
            composer.placeRelative(0, contentHeight)
            companion.placeRelative(0, 0)
            header.placeRelative(0, 0)
        }
    }
}

internal val Conversation.headerTitle: String
    get() = if (messages.isEmpty()) "DSHA" else title

internal val ConversationTitleBarHeight = 58.dp

@Composable
internal fun ConversationTitleBar(
    title: String,
    navigationLabel: String,
    onNavigate: () -> Unit,
    workspaceMotion: State<Float>,
    workspaceOpen: Boolean,
    fontWeight: FontWeight?,
    usage: ConversationUsage? = null,
    modifier: Modifier = Modifier,
    backdrop: ChatBackdrop? = null,
) {
    CompositionLocalProvider(
        LocalGlassBackdrop provides backdrop,
        LocalGlassBackdropMotion provides workspaceMotion,
    ) {
        Box(modifier.fillMaxWidth()) {
            CenteredTitleRow(
                Modifier.fillMaxWidth()
                    .windowInsetsPadding(
                        WindowInsets.statusBars
                            .union(WindowInsets.displayCutout)
                            .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                    )
                    .height(ConversationTitleBarHeight)
                    .padding(horizontal = 10.dp)
                    .testTag("conversation-title-bar"),
                centerTitle = false,
                leading = {
                    ConversationNavigationButton(
                        navigationLabel,
                        workspaceOpen,
                        workspaceMotion,
                        onNavigate,
                    ) {
                        NavigationMorph(workspaceMotion)
                    }
                },
                title = {
                    Text(
                        title,
                        Modifier.testTag("conversation-title"),
                        style =
                            PrismTypography.material.titleLarge.copy(
                                fontWeight = FontWeight.Normal,
                            ),
                        textAlign = TextAlign.Start,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                trailing = {
                    DshaGlassGroup {
                        Layout(
                            content = {
                                Box(
                                    Modifier.inactiveIf(workspaceOpen).clipToBounds(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Box(
                                        Modifier.requiredSize(52.dp).graphicsLayer {
                                            val t = workspaceMotion.value.coerceIn(0f, 1f)
                                            alpha = 1f - t
                                        },
                                    ) {
                                        ContextUsageButton(usage, enabled = !workspaceOpen)
                                    }
                                }
                                DshaIconButton(
                                    when {
                                        !workspaceOpen -> "打开功能区"
                                        WorkspaceState.tabsVisible -> "隐藏标签页"
                                        else -> "显示标签页"
                                    },
                                    {
                                        if (workspaceOpen) WorkspaceState.toggleTabs()
                                        else WorkspaceState.open()
                                    },
                                    modifier = Modifier.testTag("workspace-tabs-toggle"),
                                    size = 52.dp,
                                ) {
                                    WorkspaceGlyph(workspaceMotion)
                                }
                            },
                        ) { children, _ ->
                            val button = Constraints.fixed(52.dp.roundToPx(), 52.dp.roundToPx())
                            val workspaceButton = children[1].measure(button)
                            val t = workspaceMotion.value.coerceIn(0f, 1f)
                            val width = (52.dp * (2f - t)).roundToPx()
                            val usageButton =
                                children[0].measure(
                                    Constraints.fixed(
                                        (width - workspaceButton.width).coerceAtLeast(0),
                                        workspaceButton.height,
                                    ),
                                )
                            layout(width, workspaceButton.height) {
                                if (t < 1f) usageButton.placeRelative(0, 0)
                                workspaceButton.placeRelative(width - workspaceButton.width, 0)
                            }
                        }
                    }
                },
            )
        }
    }
}

/** Center against the viewport, even when the two action groups have different widths. */
@Composable
internal fun CenteredTitleRow(
    modifier: Modifier = Modifier,
    centerTitle: Boolean = true,
    leading: @Composable () -> Unit,
    title: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
) {
    Layout(
        modifier = modifier,
        content = {
            Box { leading() }
            Box { trailing() }
            Box(contentAlignment = Alignment.Center) { title() }
        },
    ) { children, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val start = children[0].measure(loose)
        val end = children[1].measure(loose)
        val gutter = maxOf(start.width, end.width) + 8.dp.roundToPx()
        val available =
            (constraints.maxWidth -
                    if (centerTitle) 2 * gutter else start.width + end.width + 16.dp.roundToPx())
                .coerceAtLeast(0)
        val text = children[2].measure(loose.copy(maxWidth = available))
        val height = constraints.constrainHeight(maxOf(start.height, end.height, text.height))
        layout(constraints.maxWidth, height) {
            start.placeRelative(0, (height - start.height) / 2)
            end.placeRelative(constraints.maxWidth - end.width, (height - end.height) / 2)
            val titleX =
                if (centerTitle) (constraints.maxWidth - text.width) / 2
                else start.width + 8.dp.roundToPx()
            text.placeRelative(titleX, (height - text.height) / 2)
        }
    }
}

@Composable
private fun NavigationMorph(progress: State<Float>) {
    Canvas(Modifier.size(22.dp)) {
        val unit = size.minDimension / 24f
        val t = progress.value.coerceIn(0f, 1f)
        fun line(x: Float, y: Float, length: Float, angle: Float) {
            val radians = angle * kotlin.math.PI / 180
            val center = Offset(x * unit, y * unit)
            val half = length * unit / 2f
            val direction =
                Offset(
                    (half * kotlin.math.cos(radians)).toFloat(),
                    (half * kotlin.math.sin(radians)).toFloat(),
                )
            drawLine(
                WhaleInk,
                center - direction,
                center + direction,
                1.65f * unit,
                StrokeCap.Round,
            )
        }
        line(12f - 4.5f * t, 6f + 2.5f * t, 16f - 6.1f * t, -45f * t)
        line(10f + 2f * t, 12f, 12f + 4f * t, 0f)
        line(12f - 4.5f * t, 18f - 2.5f * t, 16f - 6.1f * t, 45f * t)
    }
}
