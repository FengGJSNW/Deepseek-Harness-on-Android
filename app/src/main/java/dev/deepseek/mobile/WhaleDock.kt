package dev.deepseek.mobile

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private class DragBounds {
    var maxY = 0f
}

/** 身体沿输入框上沿巡游；气泡独立换边，不能缩小身体的移动范围。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WhaleDock(
    allowBubble: Boolean = true,
    wanderEnabled: Boolean = true,
    idleKey: Any? = null,
    onDetails: () -> Unit = {},
) {
    val imeVisible = WindowInsets.isImeVisible
    val density = LocalDensity.current
    val bounds = remember { DragBounds() }
    val maxDragY = remember(bounds) { { bounds.maxY } }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("pet-track")) {
        // Height changes every IME frame; only this inexpensive wrapper sees the constraints.
        // Drag bounds are read in the pointer/layout phases and do not restart a coroutine.
        bounds.maxY =
            (constraints.maxHeight - with(density) { UiTuning.PetSize.dp.toPx() }).coerceAtLeast(0f)
        WhaleDockContent(
            width = constraints.maxWidth.toFloat(),
            bubbleAllowed =
                !imeVisible &&
                    allowBubble &&
                    this.maxHeight >= if (WhaleCompanion.showBalance) 400.dp else 330.dp,
            maxDragY = maxDragY,
            wanderEnabled = wanderEnabled,
            keyboardVisible = imeVisible,
            idleKey = idleKey,
            onDetails = onDetails,
        )
    }
}

@Composable
private fun WhaleDockContent(
    width: Float,
    bubbleAllowed: Boolean,
    maxDragY: () -> Float,
    wanderEnabled: Boolean,
    keyboardVisible: Boolean,
    idleKey: Any?,
    onDetails: () -> Unit = {},
) {
    val density = LocalDensity.current
    Box(Modifier.fillMaxSize()) {
        val bodySize = with(density) { UiTuning.PetSize.dp.toPx() }
        val homeX =
            (width - bodySize - with(density) { UiTuning.PetEndInset.toPx() }).coerceAtLeast(0f)
        val travelX = (homeX - with(density) { UiTuning.PetStartInset.toPx() }).coerceAtLeast(0f)
        val motion = rememberWhaleMotion(travelX, wanderEnabled, idleKey, keyboardVisible)
        val scope = rememberCoroutineScope()
        val bubbleOnRight by
            remember(homeX, travelX, bodySize, width, motion) {
                derivedStateOf {
                    homeX + motion.shownX.coerceIn(-travelX, 0f) + bodySize / 2f < width / 2f
                }
            }
        val widgetWidth =
            with(density) { (if (bubbleAllowed) 220.dp else UiTuning.PetSize.dp).toPx() }
        val bubbleWidth = with(density) { 174.dp.toPx() }
        val bubblePadding = with(density) { 8.dp.toPx() }
        val bubbleOffset =
            remember(
                homeX,
                travelX,
                bubbleOnRight,
                widgetWidth,
                bodySize,
                bubbleWidth,
                bubblePadding,
                width,
                motion,
            ) {
                {
                    val bodyX = homeX + motion.shownX.coerceIn(-travelX, 0f)
                    val widgetX = bodyX - if (bubbleOnRight) 0f else widgetWidth - bodySize
                    val bubbleStart =
                        widgetX +
                            if (bubbleOnRight) widgetWidth - bubblePadding - bubbleWidth
                            else bubblePadding
                    (bubbleStart.coerceIn(0f, (width - bubbleWidth).coerceAtLeast(0f)) -
                            bubbleStart)
                        .roundToInt()
                }
            }
        val patrolCompression = remember(motion) { { motion.squish.value } }
        Box(
            Modifier.align(Alignment.BottomStart).offset {
                val bodyX = homeX + motion.shownX.coerceIn(-travelX, 0f)
                val widgetX = bodyX - if (bubbleOnRight) 0f else widgetWidth - bodySize
                IntOffset(
                    widgetX.roundToInt(),
                    motion.shownY.coerceIn(-maxDragY(), 0f).roundToInt(),
                )
            },
        ) {
            WhalePet(
                size = UiTuning.PetSize,
                onDetails = onDetails,
                showBubble = bubbleAllowed,
                bubbleOnRight = bubbleOnRight,
                bubbleOffsetX = bubbleOffset,
                facingRight = motion.facingRight,
                patrolCompression = patrolCompression,
                gesture =
                    Modifier.testTag("pet-body")
                        .semantics { stateDescription = motion.phase }
                        .pointerInput(motion) {
                            awaitEachGesture {
                                awaitFirstDown(
                                    requireUnconsumed = false,
                                    pass = PointerEventPass.Initial,
                                )
                                motion.held = true
                                motion.facingRight = false
                                WhaleActivity.interact()
                                try {
                                    do {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                    } while (event.changes.any { it.pressed })
                                } finally {
                                    motion.held = false
                                    WhaleActivity.interact()
                                }
                            }
                        }
                        .pointerInput(travelX) {
                            detectDragGestures(
                                onDragStart = {
                                    motion.startDrag()
                                    WhaleCompanion.react("wave", "轻点拎，我自己会走啦。")
                                },
                                onDragEnd = { scope.launch { motion.releaseDrag() } },
                                onDragCancel = { scope.launch { motion.releaseDrag(false) } },
                            ) { change, amount ->
                                change.consume()
                                motion.dragX = (motion.dragX + amount.x).coerceIn(-travelX, 0f)
                                motion.dragY = (motion.dragY + amount.y).coerceIn(-maxDragY(), 0f)
                            }
                        },
            )
        }
    }
}
