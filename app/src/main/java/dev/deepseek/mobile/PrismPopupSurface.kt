package dev.deepseek.mobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.*
import kotlin.math.*

internal class GlassInkCapture {
    var recorded by mutableStateOf(false)
    var recording = false
}

internal val LocalGlassInkCapture = staticCompositionLocalOf<GlassInkCapture?> { null }
internal val LocalHideGlassPane = staticCompositionLocalOf { false }

/** A display-list snapshot of the control's ink, excluding its glass, like withoutPanes(). */
internal fun Modifier.popupSourceInk(
    layer: GraphicsLayer,
    capture: GlassInkCapture,
    paneReady: Boolean,
) = drawWithContent {
    if (!capture.recorded && size.width > 0 && size.height > 0) {
        layer.record {
            capture.recording = true
            try {
                this@drawWithContent.drawContent()
            } finally {
                capture.recording = false
            }
        }
        capture.recorded = true
    }
    if (!paneReady || !capture.recorded) drawContent()
}

internal class PopupPlacementState {
    var origin by mutableStateOf(IntOffset.Zero)
}

/**
 * One glass pane holds both moving shapes. Contents and the captured glyph have separate timing.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun PrismPopupSurface(
    clock: PrismPopupClock,
    anchor: Rect,
    window: IntSize,
    safeTop: Int,
    bottomInset: Int,
    width: Dp,
    placement: MenuPlacement,
    position: PopupPlacementState,
    sourceLayer: GraphicsLayer,
    bare: Boolean,
    sharedGlass: Boolean,
    expanded: Boolean,
    receding: Float = 0f,
    recedeAnchorTop: Float = 0f,
    parentCard: Rect? = null,
    onCard: (Rect) -> Unit = {},
    ownerRoot: android.view.View,
    forwardOutside: Boolean,
    canClose: () -> Boolean,
    close: () -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val popupView = LocalView.current
    var forwarding by remember { mutableStateOf(false) }
    val glyphLayer = rememberGraphicsLayer()
    var card by remember { mutableStateOf(Rect.Zero) }
    var source by remember { mutableStateOf(Rect.Zero) }
    val sourcePane =
        when {
            clock.rowMorph -> source
            bare -> Rect(source.left - 12f, source.top - 6f, source.right + 12f, source.bottom + 6f)
            else -> source.deflate(4f)
        }
    val frame =
        when {
            sourcePane.isEmpty || card.isEmpty -> null
            clock.rowMorph -> PrismPopupMotion.row(clock.unfold, source, card)
            else -> clock.buttonFrame(sourcePane, card, bare || sharedGlass)
        }
    val measured = frame != null && clock.retained
    SideEffect { if (measured) clock.ready = true }
    Layout(
        modifier =
            Modifier.graphicsLayer {
                    alpha = if (clock.retained && clock.ready) 1f else 0f
                    scaleX = 1f - .05f * receding
                    scaleY = scaleX
                    transformOrigin =
                        if (receding > 0f && size.width > 0f && size.height > 0f) {
                            TransformOrigin(
                                (card.right * density.density / size.width).coerceIn(0f, 1f),
                                ((recedeAnchorTop - position.origin.y) / size.height).coerceIn(
                                    0f,
                                    1f,
                                ),
                            )
                        } else TransformOrigin.Center
                }
                .pointerInteropFilter { event ->
                    if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                        val point = Offset(event.x, event.y) / density.density
                        forwarding =
                            forwardOutside &&
                                expanded &&
                                canClose() &&
                                !source.contains(point) &&
                                !card.contains(point)
                        if (forwarding) close()
                    }
                    if (forwarding) {
                        // Preserve one physical gesture through the empty part of the popup window.
                        // A View.OnTouchListener cannot intercept a child Compose pointer target.
                        val ownerScreen = IntArray(2)
                        val popupScreen = IntArray(2)
                        ownerRoot.getLocationOnScreen(ownerScreen)
                        popupView.getLocationOnScreen(popupScreen)
                        val copy = android.view.MotionEvent.obtain(event)
                        copy.offsetLocation(
                            (popupScreen[0] - ownerScreen[0]).toFloat(),
                            (popupScreen[1] - ownerScreen[1]).toFloat(),
                        )
                        try {
                            ownerRoot.dispatchTouchEvent(copy)
                        } finally {
                            copy.recycle()
                        }
                        if (
                            event.actionMasked == android.view.MotionEvent.ACTION_UP ||
                                event.actionMasked == android.view.MotionEvent.ACTION_CANCEL
                        )
                            forwarding = false
                        true
                    } else false
                }
                .pointerInput(source, card, expanded) {
                    if (!expanded) return@pointerInput
                    awaitEachGesture {
                        val down =
                            awaitFirstDown(
                                requireUnconsumed = true,
                                pass = PointerEventPass.Initial,
                            )
                        val point = down.position / density.density
                        if (
                            canClose() &&
                                (!card.contains(point) ||
                                    (!clock.rowMorph && source.contains(point)))
                        ) {
                            down.consume()
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                change.consume()
                                if (!change.pressed) {
                                    close()
                                    break
                                }
                            }
                        }
                    }
                },
        content = {
            Box(
                Modifier.graphicsLayer {
                    val f = frame
                    if (f == null || card.isEmpty) {
                        alpha = 0f
                        return@graphicsLayer
                    }
                    val factor =
                        when {
                            clock.rowMorph -> 1f
                            clock.returning != null ->
                                min(f.platter.width / card.width, f.platter.height / card.height)
                            else -> f.platter.width / card.width
                        }
                    scaleX = factor
                    scaleY = factor
                    if (clock.rowMorph) {
                        transformOrigin = TransformOrigin(0f, 0f)
                        translationX = (f.platter.left - card.left) * density.density
                        translationY = (f.platter.top - card.top) * density.density
                    } else {
                        translationX = (f.platter.center.x - card.center.x) * density.density
                        translationY = (f.platter.center.y - card.center.y) * density.density
                    }
                    alpha = f.content
                    this.shape =
                        if (!clock.rowMorph)
                            ContinuousShape((f.radius / factor.coerceAtLeast(.001f)).dp)
                        else
                            object : Shape {
                                override fun createOutline(
                                    size: androidx.compose.ui.geometry.Size,
                                    layoutDirection: LayoutDirection,
                                    density: Density,
                                ): Outline {
                                    val clipSize =
                                        androidx.compose.ui.geometry.Size(
                                            size.width,
                                            f.platter.height * density.density /
                                                factor.coerceAtLeast(.001f),
                                        )
                                    return ContinuousShape(
                                            (f.radius / factor.coerceAtLeast(.001f)).dp,
                                        )
                                        .createOutline(clipSize, layoutDirection, density)
                                }
                            }
                    clip = true
                    renderEffect =
                        if (android.os.Build.VERSION.SDK_INT >= 31 && f.contentBlur > .1f)
                            BlurEffect(
                                f.contentBlur * density.density,
                                f.contentBlur * density.density,
                                TileMode.Decal,
                            )
                        else null
                },
            ) {
                Box {
                    content()
                    if (receding > 0f)
                        Canvas(Modifier.matchParentSize()) {
                            drawRect(Color.White.copy(alpha = .2f * receding))
                        }
                }
            }
            if (frame == null) Spacer(Modifier)
            else
                PrismGlassPane(
                    Modifier,
                    frame.platter,
                    frame.radius,
                    if (clock.rowMorph) null else frame.droplet,
                    frame.blend,
                    frame.glass,
                    (((if (bare || sharedGlass || clock.rowMorph) 0f else 2f) *
                            (1f - frame.shadow) + 8f * frame.shadow) * (1f - receding / 3f))
                        .dp,
                    material = frame.shadow,
                )
            Canvas(Modifier.fillMaxSize()) {
                val f = frame ?: return@Canvas
                val drop = f.droplet ?: return@Canvas
                if (f.glyph <= 0f || sourceLayer.size.width == 0) return@Canvas
                val width =
                    if (clock.rowMorph) source.width
                    else drop.width * source.width / sourcePane.width
                val height =
                    if (clock.rowMorph) source.height
                    else drop.height * source.height / sourcePane.height
                glyphLayer.record(size = sourceLayer.size) { drawLayer(sourceLayer) }
                glyphLayer.alpha = f.glyph
                glyphLayer.renderEffect =
                    if (android.os.Build.VERSION.SDK_INT >= 31 && f.glyphBlur > .1f)
                        BlurEffect(f.glyphBlur * density.density, f.glyphBlur * density.density)
                    else null
                translate(
                    (if (clock.rowMorph) drop.left else drop.center.x - width / 2f) *
                        density.density,
                    (drop.center.y - height / 2f) * density.density,
                ) {
                    scale(
                        width * density.density / sourceLayer.size.width,
                        height * density.density / sourceLayer.size.height,
                        Offset.Zero,
                    ) {
                        if (clock.rowMorph) {
                            clipRect(
                                right = sourceLayer.size.width * (drop.width / width),
                                bottom = sourceLayer.size.height * (drop.height / height),
                            ) {
                                drawLayer(glyphLayer)
                            }
                        } else drawLayer(glyphLayer)
                    }
                }
            }
        },
    ) { children, constraints ->
        val margin = 16.dp.toPx()
        val bottom = (window.height - bottomInset).coerceAtLeast(safeTop + 1)
        val parentWidth = parentCard?.width ?: anchor.width
        val followingRow = placement == MenuPlacement.SelectedRow
        val requestedWidth =
            if (followingRow) min(width.toPx(), parentWidth * .89f) else width.toPx()
        val contentTop =
            when (placement) {
                MenuPlacement.SelectedRow ->
                    (anchor.top - 22.dp.toPx()).coerceIn(
                        safeTop.toFloat(),
                        max(safeTop.toFloat(), bottom - margin - 104.dp.toPx()),
                    )
                MenuPlacement.BelowControl ->
                    (anchor.bottom + 4.dp.toPx()).coerceIn(
                        safeTop.toFloat(),
                        max(safeTop.toFloat(), bottom - margin - 1f),
                    )
                else -> safeTop + margin
            }
        val cardWidth =
            min(requestedWidth.toInt(), (window.width - 2 * margin).toInt()).coerceAtLeast(1)
        val contentWidth =
            if (clock.rowMorph) {
                val t = (clock.unfold / .3f).coerceIn(0f, 1f)
                (anchor.width + (cardWidth - anchor.width) * t)
                    .roundToInt()
                    .coerceIn(1, window.width.coerceAtLeast(1))
            } else cardWidth
        val body =
            children[0].measure(
                Constraints(
                    maxWidth = contentWidth,
                    minWidth = contentWidth,
                    maxHeight = (bottom - margin - contentTop).toInt().coerceAtLeast(1),
                ),
            )
        val cardX =
            (if (followingRow) anchor.left + min(32.dp.toPx(), anchor.width * .09f)
                else anchor.right - cardWidth)
                .coerceIn(
                    margin,
                    max(margin, window.width - margin - cardWidth),
                )
        val cardY =
            when (placement) {
                MenuPlacement.SelectedRow,
                MenuPlacement.BelowControl -> contentTop
                MenuPlacement.Below -> anchor.top - 8.dp.toPx()
                MenuPlacement.Above -> anchor.top - 8.dp.toPx() - body.height
            }.coerceIn(safeTop.toFloat(), max(safeTop.toFloat(), bottom - margin - body.height))
        val pad = max(body.width, body.height) * .1f + 28.dp.toPx()
        val left = max(0f, min(cardX, anchor.left) - pad).toInt()
        val top = max(safeTop.toFloat(), min(cardY, anchor.top) - pad).toInt()
        val right = min(window.width.toFloat(), max(cardX + cardWidth, anchor.right) + pad).toInt()
        val end = min(bottom.toFloat(), max(cardY + body.height, anchor.bottom) + pad).toInt()
        position.origin = IntOffset(left, top)
        onCard(Rect(cardX, cardY, cardX + cardWidth, cardY + body.height))
        val cardRect =
            Rect(
                (cardX - left) / density.density,
                (cardY - top) / density.density,
                (cardX + cardWidth - left) / density.density,
                (cardY + body.height - top) / density.density,
            )
        if (card != cardRect) card = cardRect
        val control = anchor.translate(Offset(-left.toFloat(), -top.toFloat()))
        val sourceRect =
            Rect(
                control.left / density.density,
                control.top / density.density,
                control.right / density.density,
                control.bottom / density.density,
            )
        if (source != sourceRect) source = sourceRect
        val fixed = Constraints.fixed((right - left).coerceAtLeast(1), (end - top).coerceAtLeast(1))
        val glass = children[1].measure(fixed)
        val glyph = children[2].measure(fixed)
        layout(fixed.maxWidth, fixed.maxHeight) {
            glass.place(0, 0, zIndex = 0f)
            glyph.place(0, 0, zIndex = 1f)
            body.place((cardX - left).roundToInt(), (cardY - top).roundToInt(), zIndex = 2f)
        }
    }
}
