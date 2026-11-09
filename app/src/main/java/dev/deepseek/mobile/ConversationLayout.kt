package dev.deepseek.mobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    Layout(
        modifier = if (backdrop == null) modifier else modifier.onPlaced { backdrop.viewport = it },
        content = {
            Box(Modifier.fillMaxSize()) { content() }
            Box(Modifier.fillMaxWidth()) { composer() }
            Box(Modifier.fillMaxSize().padding(horizontal = UiTuning.ComposerHorizontalMargin)) {
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
    get() = if (messages.isEmpty()) "DeepSeek" else title

internal val ConversationTitleBarHeight = 58.dp

@Composable
internal fun ConversationTitleBar(
    title: String,
    navigationLabel: String,
    onNavigate: () -> Unit,
    workspaceMotion: State<Float>,
    workspaceOpen: Boolean,
    fontWeight: FontWeight?,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxWidth()) {
        Box(
            Modifier.matchParentSize()
                .graphicsLayer { alpha = workspaceMotion.value.coerceIn(0f, 1f) }
                .background(Color.White),
        )
        Row(
            Modifier.fillMaxWidth()
                .windowInsetsPadding(
                    WindowInsets.statusBars
                        .union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                )
                .height(ConversationTitleBarHeight)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onNavigate) {
                Box(Modifier.semantics { contentDescription = navigationLabel }) {
                    NavigationMorph(workspaceMotion)
                }
            }
            Text(
                title,
                Modifier.weight(1f).testTag("conversation-title"),
                fontSize = 17.sp,
                fontWeight = fontWeight,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(
                onClick = { WorkspaceState.open() },
                enabled = !workspaceOpen,
                modifier =
                    Modifier.inactiveIf(workspaceOpen).graphicsLayer {
                        alpha = (1f - 2f * workspaceMotion.value).coerceIn(0f, 1f)
                    },
            ) {
                Icon(AppIcons.RightWorkspace, "打开功能区", Modifier.size(22.dp), tint = WhaleInk)
            }
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
