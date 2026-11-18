package dev.deepseek.mobile

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

/** One GPU display list of the messages, reused by the translucent chrome. No bitmap readback. */
internal class ChatBackdrop(val layer: GraphicsLayer, val hostView: android.view.View? = null) {
    var viewport: LayoutCoordinates? = null
    private var capturedCoordinates by mutableStateOf<LayoutCoordinates?>(null)

    fun captureAt(coordinates: LayoutCoordinates) {
        capturedCoordinates = coordinates
    }

    /** A sampled layer must never contain the pane that samples it (a RenderNode cycle). */
    fun canSampleFrom(coordinates: LayoutCoordinates?): Boolean {
        val captured = capturedCoordinates?.takeIf { it.isAttached } ?: return false
        var ancestor = coordinates?.takeIf { it.isAttached } ?: return false
        while (true) {
            if (ancestor === captured) return false
            ancestor = ancestor.parentCoordinates ?: return true
        }
    }

    var origin by mutableStateOf(Offset.Zero)
    var revision by mutableIntStateOf(0)
        private set

    fun recorded() {
        // Notify filtered consumers without subscribing the source to its own writes.
        Snapshot.withoutReadObservation { revision++ }
    }

    fun positionInSource(coordinates: LayoutCoordinates, owner: android.view.View): Offset {
        val viewport = viewport
        if (
            viewport != null &&
                viewport.isAttached &&
                viewport.findRootCoordinates() == coordinates.findRootCoordinates()
        ) {
            return positionOf(coordinates)
        }
        val from = IntArray(2)
        val to = IntArray(2)
        owner.getLocationOnScreen(from)
        hostView?.getLocationOnScreen(to)
        return coordinates.positionInRoot() +
            Offset((from[0] - to[0]).toFloat(), (from[1] - to[1]).toFloat()) -
            (viewport?.takeIf { it.isAttached }?.positionInRoot() ?: Offset.Zero)
    }

    // Card transforms affect both layers equally. Sample relative to their shared viewport
    // so translating or scaling the card does not invalidate the glass display lists.
    fun positionOf(coordinates: LayoutCoordinates): Offset =
        viewport?.takeIf { it.isAttached }?.localPositionOf(coordinates, Offset.Zero)
            ?: coordinates.positionInRoot()
}

internal fun Modifier.chatBackdrop(
    source: ChatBackdrop,
    dependencies: List<ChatBackdrop> = emptyList(),
) = onGloballyPositioned {
    source.captureAt(it)
    source.origin = source.positionOf(it)
}
// Keep source recording independent of chrome invalidations. Otherwise notifying
// a lens could redraw the source, notify the lens again, and keep the frame alive.
.graphicsLayer()
    .drawWithContent {
        dependencies.forEach { it.revision }
        if (!drawContext.canvas.nativeCanvas.isHardwareAccelerated) {
            drawContent()
            return@drawWithContent
        }
        source.layer.record(size = IntSize(size.width.toInt(), size.height.toInt())) {
            this@drawWithContent.drawContent()
        }
        source.recorded()
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
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
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
                    Modifier.onGloballyPositioned {
                        coordinates = it
                        position = source.positionOf(it)
                    }
                else Modifier,
            )
            .clipToBounds(),
    ) {
        if (
            source == null ||
                !source.canSampleFrom(coordinates) ||
                Build.VERSION.SDK_INT < 31 ||
                !drawContext.canvas.nativeCanvas.isHardwareAccelerated
        ) {
            drawRect(tint.copy(alpha = 1f))
            return@Canvas
        }
        source.revision
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

/** One cached gradient draw. No backdrop sampling, offscreen targets or Gaussian passes. */
@Composable
internal fun HeaderBackdrop(modifier: Modifier) {
    HeaderFade(modifier)
}

@Composable
internal fun HeaderFade(modifier: Modifier, color: Color = Color.White, fadeIn: Boolean = false) {
    Box(
        modifier.then(
            Modifier.drawWithCache {
                val stops =
                    (0..16)
                        .map { i ->
                            val t = i / 16f
                            val fade = ((t - .44f) / .56f).coerceIn(0f, 1f)
                            val rise = if (fadeIn) (t / .32f).coerceIn(0f, 1f) else 1f
                            val alpha =
                                rise *
                                    rise *
                                    (3f - 2f * rise) *
                                    (1f - fade * fade * (3f - 2f * fade))
                            t to color.copy(alpha = color.alpha * alpha)
                        }
                        .toTypedArray()
                val brush = Brush.verticalGradient(*stops)
                onDrawBehind { drawRect(brush) }
            },
        ),
    )
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
