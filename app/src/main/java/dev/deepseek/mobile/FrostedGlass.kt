package dev.deepseek.mobile

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

/** One GPU display list of the messages, reused by the translucent chrome. No bitmap readback. */
internal class ChatBackdrop(val layer: GraphicsLayer) {
    var viewport: LayoutCoordinates? = null
    var origin by mutableStateOf(Offset.Zero)

    // Card transforms affect both layers equally. Sample relative to their shared viewport
    // so translating or scaling the card does not invalidate the glass display lists.
    fun positionOf(coordinates: LayoutCoordinates): Offset =
        viewport?.takeIf { it.isAttached }?.localPositionOf(coordinates, Offset.Zero)
            ?: coordinates.positionInRoot()
}

internal fun Modifier.chatBackdrop(source: ChatBackdrop) = onGloballyPositioned {
    source.origin = source.positionOf(it)
}
    .drawWithContent {
        if (!drawContext.canvas.nativeCanvas.isHardwareAccelerated) {
            drawContent()
            return@drawWithContent
        }
        source.layer.record(size = IntSize(size.width.toInt(), size.height.toInt())) {
            this@drawWithContent.drawContent()
        }
        drawLayer(source.layer)
    }

@Composable
internal fun FrostedGlass(
    source: ChatBackdrop?,
    modifier: Modifier,
    tint: Color = Color.White.copy(alpha = .76f),
    radius: Float = 16f,
) {
    var position by remember { mutableStateOf(Offset.Zero) }
    val blurLayer = rememberGraphicsLayer()
    val density = LocalDensity.current
    // Gaussian content has no sharp detail: downsampled filters preserve appearance while
    // avoiding eight full-resolution GPU filter surfaces during a keyboard transition.
    val sample = if (radius <= 0f) 1f else 1f / 3f
    val effect =
        remember(radius, density) {
            if (radius <= 0f) null
            else
                with(density) {
                    BlurEffect(radius.dp.toPx() * sample, radius.dp.toPx() * sample, TileMode.Clamp)
                }
        }
    Canvas(
        modifier
            .then(
                if (source != null)
                    Modifier.onGloballyPositioned { position = source.positionOf(it) }
                else Modifier,
            )
            .clipToBounds(),
    ) {
        if (
            source == null ||
                Build.VERSION.SDK_INT < 31 ||
                !drawContext.canvas.nativeCanvas.isHardwareAccelerated
        ) {
            drawRect(tint.copy(alpha = 1f))
            return@Canvas
        }
        val offset = source.origin - position
        // Filter a halo as well as the visible crop. Clamping a cropped glyph repeats its
        // edge pixels and makes text pop as it crosses the chrome boundary.
        val halo = ceil(radius.coerceAtLeast(0f).dp.toPx() * 3f * sample) / sample
        blurLayer.clip = true
        blurLayer.renderEffect = effect
        blurLayer.record(
            size =
                IntSize(
                    ceil((size.width + halo * 2) * sample).toInt(),
                    ceil((size.height + halo * 2) * sample).toInt(),
                ),
        ) {
            // Cover the clear original before compositing its blurred copy, preventing sharp
            // ghosts.
            drawRect(Color.White)
            scale(sample, sample, pivot = Offset.Zero) {
                translate(offset.x + halo, offset.y + halo) { drawLayer(source.layer) }
            }
            drawRect(tint)
        }
        translate(-halo, -halo) {
            scale(1f / sample, 1f / sample, pivot = Offset.Zero) { drawLayer(blurLayer) }
        }
    }
}

/** Fixed 18dp blur through the header, then 18→12→6→0 with smooth crossfades below it. */
@Composable
internal fun HeaderBackdrop(source: ChatBackdrop?, modifier: Modifier) {
    if (source == null) {
        Box(modifier.background(Color.White))
        return
    }
    Box(modifier.clipToBounds()) {
        FrostedGlass(source, Modifier.matchParentSize(), Color.White.copy(alpha = .60f), 18f)
        val statusTop = WindowInsets.statusBars.getTop(LocalDensity.current).toFloat()
        listOf(12f, 6f, 0f).forEachIndexed { index, radius ->
            Box(
                Modifier.matchParentSize()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        val start = (statusTop + 58.dp.toPx() + index * 8.dp.toPx()) / size.height
                        val end =
                            (statusTop + 58.dp.toPx() + (index + 1) * 8.dp.toPx()) / size.height
                        drawRect(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                start.coerceIn(0f, 1f) to Color.Transparent,
                                end.coerceIn(0f, 1f) to Color.White,
                                1f to Color.White,
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    },
            ) {
                FrostedGlass(
                    source,
                    Modifier.matchParentSize(),
                    Color.White.copy(alpha = .60f * radius / 18f),
                    radius,
                )
            }
        }
    }
}

/** A clipped, progressively stronger Gaussian blur at the scrolling viewport's two edges. */
@Composable
internal fun ScrollEdgeGlass(source: ChatBackdrop, top: Boolean, modifier: Modifier) {
    Box(modifier.clipToBounds()) {
        listOf(3f, 7f, 13f).forEachIndexed { index, radius ->
            val alpha = (index + 1) / 3f
            val gradient =
                if (top) listOf(Color.White.copy(alpha = alpha), Color.Transparent)
                else listOf(Color.Transparent, Color.White.copy(alpha = alpha))
            // DstIn attenuates the blurred layer, allowing the clear text to transition underneath.
            androidx.compose.foundation.layout.Box(
                Modifier.matchParentSize()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(Brush.verticalGradient(gradient), blendMode = BlendMode.DstIn)
                    },
            ) {
                FrostedGlass(
                    source,
                    Modifier.matchParentSize(),
                    Color.White.copy(alpha = .12f),
                    radius,
                )
            }
        }
    }
}
