package dev.deepseek.mobile

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.UUID

/** A single stack shared by all modal islands, including file previews above agent details. */
internal object IslandStack {
    val layers = mutableStateListOf<String>()
    val heights = mutableStateMapOf<String, Int>()
}

@Composable
internal fun CenterIsland(close: () -> Unit, content: @Composable () -> Unit) {
    val id = remember { UUID.randomUUID().toString() }
    var entered by remember { mutableStateOf(false) }
    DisposableEffect(id) {
        IslandStack.layers.add(id)
        onDispose {
            IslandStack.layers.remove(id)
            IslandStack.heights.remove(id)
        }
    }
    LaunchedEffect(Unit) { entered = true }
    val level = IslandStack.layers.indexOf(id)
    val behind = if (level < 0) 0 else (IslandStack.layers.lastIndex - level).coerceIn(0, 3)
    val depth =
        animateFloatAsState(behind.toFloat(), UiSpring.navigation(.001f), label = "island-stack")
    val reveal =
        animateFloatAsState(
            if (entered) 1f else 0f,
            UiSpring.navigation(.002f),
            label = "island-enter",
        )
    val transform = remember {
        val update: GraphicsLayerScope.() -> Unit = {
            val depthValue = depth.value
            val progress = reveal.value.coerceIn(0f, 1f)
            alpha = progress
            scaleX = (.94f + .06f * progress) * (1f - depthValue * .035f)
            scaleY = scaleX
            translationY = (-20f * depthValue).dp.toPx()
        }
        update
    }
    val dismiss by rememberUpdatedState(close)
    Dialog(
        { if (behind == 0) dismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val view = LocalView.current
        val window =
            (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
                ?: (view as? androidx.compose.ui.window.DialogWindowProvider)?.window
        val first = level == 0 || (level < 0 && IslandStack.layers.isEmpty())
        DisposableEffect(window, first) {
            window?.setDimAmount(if (first) .18f else .10f)
            onDispose {}
        }
        val screen = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
        // A stable window size prevents WindowManager relayout during stack motion.
        // Keep the viewport within the available window instead of measuring to the full display.
        val viewport = (screen * .86f).coerceAtLeast(120.dp)
        val maxCardHeight = (screen * .79f).coerceAtMost(viewport - 40.dp).coerceAtLeast(80.dp)
        val shape = remember { ContinuousShape(28.dp) }
        Box(
            Modifier.widthIn(max = 560.dp)
                .fillMaxWidth()
                .height(viewport)
                .inactiveIf(behind > 0)
                .testTag("island-backdrop")
                .pointerInput(Unit) { detectTapGestures(onTap = { dismiss() }) },
            contentAlignment = Alignment.Center,
        ) {
            if (behind > 0) {
                val topHeight =
                    IslandStack.layers.lastOrNull()?.let { IslandStack.heights[it] } ?: 0
                val ownHeight = IslandStack.heights[id] ?: 0
                // A taller front card only changes the rear outline, never its preview layout.
                if (topHeight > ownHeight) {
                    val height = with(LocalDensity.current) { topHeight.toDp() }
                    Box(Modifier.fillMaxWidth().graphicsLayer(transform).padding(20.dp)) {
                        Surface(
                            Modifier.fillMaxWidth().height(height).floatingIslandShadow(shape),
                            shape = shape,
                            color = Color.White,
                            border = BorderStroke(.7.dp, WhaleLine),
                        ) {}
                    }
                }
            }
            Box(Modifier.fillMaxWidth().graphicsLayer(transform).padding(20.dp)) {
                Surface(
                    Modifier.fillMaxWidth()
                        .heightIn(max = maxCardHeight)
                        .onSizeChanged { IslandStack.heights[id] = it.height }
                        .floatingIslandShadow(shape)
                        .pointerInput(Unit) { detectTapGestures(onTap = {}) }
                        .testTag("center-island"),
                    shape = shape,
                    color = Color.White,
                    border = BorderStroke(.7.dp, WhaleLine),
                ) {
                    content()
                }
            }
        }
    }
}
