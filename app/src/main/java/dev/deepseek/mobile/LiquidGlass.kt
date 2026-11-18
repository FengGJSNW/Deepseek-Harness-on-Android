package dev.deepseek.mobile

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

internal data class GlassGoo(
    val platter: androidx.compose.ui.geometry.Rect,
    val radius: Float,
    val droplet: androidx.compose.ui.geometry.Rect?,
    val blend: Float,
    val density: Float,
    val bounds: androidx.compose.ui.geometry.Rect? = null,
    val material: Float = 1f,
)

internal enum class GlassFinish(val blur: Float, val haze: Float, val saturation: Float) {
    Regular(2.5f, .35f, 2.08f),
    Pill(15.6f, .72f, 2.1f),
    Menu(12f, 1f, 2f),
    Tinted(2.5f, .35f, 1.2f),
}

internal val LocalGlassBackdrop = staticCompositionLocalOf<ChatBackdrop?> { null }
internal val LocalGlassBackdropMotion = staticCompositionLocalOf<State<Float>?> { null }
// Companion pixels are available to popups, without changing the top-bar/button backdrop.
internal val LocalMenuGlassOverlays = staticCompositionLocalOf<List<ChatBackdrop>> { emptyList() }
internal val LocalGlassBackdropOverlays =
    staticCompositionLocalOf<List<ChatBackdrop>> { emptyList() }

/** A small live display-list sample, refracted on the GPU; never captures a bitmap. */
@Composable
internal fun LiquidGlass(
    modifier: Modifier,
    source: ChatBackdrop? = LocalGlassBackdrop.current,
    corner: Float = 24f,
    tint: Color = Color(0x8AFDFDFD),
    finish: GlassFinish = GlassFinish.Regular,
    shape: Shape = ContinuousShape(corner.dp),
    cornerProvider: (() -> Float)? = null,
    backdropMotion: State<Float>? = LocalGlassBackdropMotion.current,
    goo: GlassGoo? = null,
) {
    val owner = LocalView.current
    val inkCapture = LocalGlassInkCapture.current
    if (LocalHideGlassPane.current) return
    val refract = ExperienceSettings.liquidGlassEnabled
    val transparency = ExperienceSettings.glassTransparency.coerceIn(0f, 1f)
    val opacity =
        if (finish == GlassFinish.Tinted) tint.alpha
        else if (transparency <= .45f) 1f + (tint.alpha - 1f) * transparency / .45f
        else tint.alpha * (1f - transparency) / .55f
    val whiteMix =
        (.45f - transparency).coerceIn(0f, .45f) * if (finish == GlassFinish.Tinted) 0f else 1f
    val surfaceTint = lerp(tint, Color.White, whiteMix).copy(alpha = opacity)
    val overlays = LocalGlassBackdropOverlays.current
    val inputs = remember(source, overlays) { (listOfNotNull(source) + overlays).distinct() }
    var position by remember { mutableStateOf(Offset.Zero) }
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var sameRoot by remember { mutableStateOf(false) }
    val layer = rememberGraphicsLayer()
    val motion = LocalGlassPress.current
    val lens = remember {
        if (Build.VERSION.SDK_INT >= 33) runCatching { GlassLens() }.getOrNull() else null
    }
    val inkFilter =
        if (inkCapture == null) Modifier
        else
            Modifier.drawWithContent {
                if (!inkCapture.recording) drawContent()
            }
    Box(
        inkFilter
            .then(modifier)
            .then(
                if (source != null)
                    Modifier.onGloballyPositioned {
                        coordinates = it
                        sameRoot =
                            source.viewport
                                ?.takeIf { root -> root.isAttached }
                                ?.findRootCoordinates() === it.findRootCoordinates()
                        if (sameRoot) position = source.positionOf(it)
                    }
                else Modifier,
            )
            .drawWithCache {
                val currentCorner = cornerProvider?.invoke() ?: corner
                val currentShape =
                    if (cornerProvider == null) shape else ContinuousShape(currentCorner.dp)
                val outline = currentShape.createOutline(size, layoutDirection, this)
                val fallback =
                    Brush.verticalGradient(
                        listOf(
                            surfaceTint.copy(alpha = .92f).compositeOver(Color.White),
                            surfaceTint.copy(alpha = .82f).compositeOver(Color(0xFFE2E5EC)),
                        ),
                    )
                val rim =
                    Brush.linearGradient(
                        listOf(
                            Color.White.copy(alpha = .95f),
                            Color.White.copy(alpha = .08f),
                            Color(0xFFD9DCE5).copy(alpha = .42f),
                        ),
                    )
                val stroke = Stroke(.7.dp.toPx())
                val separation = Stroke(.6.dp.toPx())
                // Gaussian blur needs a complete halo; a cropped kernel repeats edge glyphs.
                val blur = if (refract) ExperienceSettings.liquidGlassBlur else 16f
                val halo = maxOf(20f, blur * 3f + 2f)
                val gutter = ceil(halo.dp.toPx())
                val frosted =
                    if (blur > .01f) BlurEffect(blur.dp.toPx(), blur.dp.toPx(), TileMode.Clamp)
                    else null
                val sampleSize =
                    IntSize(
                        ceil(size.width + gutter * 2).toInt().coerceAtLeast(1),
                        ceil(size.height + gutter * 2).toInt().coerceAtLeast(1),
                    )
                val radius = currentCorner.dp.toPx()
                onDrawBehind {
                    val sampleable = inputs.filter { it.canSampleFrom(coordinates) }
                    if (
                        source != null &&
                            source in sampleable &&
                            (sameRoot || source.hostView != null) &&
                            Build.VERSION.SDK_INT >= 31 &&
                            drawContext.canvas.nativeCanvas.isHardwareAccelerated
                    ) {
                        // A RenderEffect can cache its input even when a nested display
                        // list changes. Observe source recording to invalidate this pane.
                        sampleable.forEach { it.revision }
                        backdropMotion?.value
                        val hasLens = refract && Build.VERSION.SDK_INT >= 33 && lens != null
                        layer.renderEffect =
                            if (refract && Build.VERSION.SDK_INT >= 33 && lens != null) {
                                lens.resize(
                                    size.width,
                                    size.height,
                                    radius,
                                    density,
                                    gutter,
                                    surfaceTint,
                                    finish,
                                    blur = blur,
                                    goo = goo,
                                )
                                lens.effect
                            } else frosted
                        layer.clip = true
                        // The pane moves in paint coordinates. Undo that transform in the
                        // sampled display list so the background stays anchored to the page.
                        val press = motion?.transform()
                        val sx = press?.scaleX ?: 1f
                        val sy = press?.scaleY ?: 1f
                        val shift =
                            (press?.offset ?: Offset.Zero) +
                                Offset(size.width * .5f * (1f - sx), size.height * .5f * (1f - sy))
                        // Record only the background layers, never the popup or the composer.
                        // Each source has its own origin, including the transparent pet layer.
                        val offsets = sampleable.map { input ->
                            val moved =
                                coordinates
                                    ?.takeIf { it.isAttached }
                                    ?.let { input.positionInSource(it, owner) } ?: position
                            input.origin - moved - shift
                        }
                        layer.record(size = sampleSize) {
                            drawRect(Color.White)
                            sampleable.forEachIndexed { index, input ->
                                val offset = offsets[index]
                                translate(gutter + offset.x / sx, gutter + offset.y / sy) {
                                    scale(1f / sx, 1f / sy, Offset.Zero) { drawLayer(input.layer) }
                                }
                            }
                        }
                        translate(-gutter, -gutter) { drawLayer(layer) }
                        if (!hasLens) drawRect(surfaceTint)
                    } else {
                        if (refract) drawRect(surfaceTint) else drawRect(fallback)
                    }
                    drawOutline(outline, rim, style = stroke)
                    drawOutline(outline, Color(0x24303A4C), style = separation)
                }
            },
    )
}

@RequiresApi(33)
internal class GlassLens {
    private val shader = android.graphics.RuntimeShader(GlassLensSource)
    lateinit var androidEffect: android.graphics.RenderEffect
        private set

    lateinit var effect: RenderEffect
        private set

    private var lastWidth = -1f
    private var lastHeight = -1f
    private var lastCorner = -1f
    private var lastDensity = -1f
    private var lastGutter = -1f
    private var lastTint = Color.Unspecified
    private var lastFinish: GlassFinish? = null
    private var lastBlur = -1f
    private var lastGoo: GlassGoo? = null

    fun resize(
        width: Float,
        height: Float,
        corner: Float,
        density: Float = 1f,
        gutter: Float = 0f,
        tint: Color = Color(0x8AFDFDFD),
        finish: GlassFinish = GlassFinish.Regular,
        blur: Float = 0f,
        goo: GlassGoo? = null,
    ) {
        if (
            lastWidth == width &&
                lastHeight == height &&
                lastCorner == corner &&
                lastDensity == density &&
                lastGutter == gutter &&
                lastTint == tint &&
                lastFinish == finish &&
                lastBlur == blur &&
                lastGoo == goo
        )
            return
        shader.setFloatUniform("corner", corner.coerceAtMost(minOf(width, height) / 2))
        shader.setFloatUniform("density", density)
        shader.setFloatUniform("gutter", gutter)
        shader.setColorUniform("tint", tint.toArgb())
        val sourceSaturation = GlassFinish.Regular.saturation
        shader.setFloatUniform(
            "saturation",
            sourceSaturation + (finish.saturation - sourceSaturation) * (goo?.material ?: 1f),
        )
        shader.setFloatUniform("hasGoo", if (goo == null) 0f else 1f)
        val a = goo?.platter
        val b = goo?.droplet
        val px = goo?.density ?: 1f
        val bounds = goo?.bounds
        shader.setFloatUniform(
            "lensBounds",
            (bounds?.left ?: 0f) * px,
            (bounds?.top ?: 0f) * px,
            bounds?.let { it.right * px } ?: width,
            bounds?.let { it.bottom * px } ?: height,
        )
        shader.setFloatUniform(
            "platter",
            (a?.left ?: 0f) * px,
            (a?.top ?: 0f) * px,
            (a?.right ?: width) * px,
            (a?.bottom ?: height) * px,
        )
        shader.setFloatUniform(
            "drop",
            (b?.left ?: 0f) * px,
            (b?.top ?: 0f) * px,
            (b?.right ?: 0f) * px,
            (b?.bottom ?: 0f) * px,
        )
        shader.setFloatUniform("gooRadius", (goo?.radius ?: corner) * px)
        shader.setFloatUniform("gooBlend", if (b == null) 0f else goo.blend * px)
        // RenderEffect snapshots the builder: uniforms MUST be set before construction.
        val optics = android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "backdrop")
        // Refraction is clear by default. Blur is an independent, explicit preference.
        androidEffect =
            if (blur <= .01f) optics
            else {
                val frost =
                    android.graphics.RenderEffect.createBlurEffect(
                        blur * density,
                        blur * density,
                        android.graphics.Shader.TileMode.CLAMP,
                    )
                android.graphics.RenderEffect.createChainEffect(optics, frost)
            }
        effect = androidEffect.asComposeRenderEffect()
        lastWidth = width
        lastHeight = height
        lastCorner = corner
        lastDensity = density
        lastGutter = gutter
        lastTint = tint
        lastFinish = finish
        lastBlur = blur
        lastGoo = goo
    }
}

// DSHA rounded lens: pixel coordinates, dp optics, axis-correct capsule normals.
internal const val GlassLensSource =
    """
uniform shader backdrop;
uniform float4 lensBounds;
uniform float corner;
uniform float density;
uniform float gutter;
uniform float saturation;
uniform float hasGoo;
uniform float4 platter;
uniform float4 drop;
uniform float gooRadius;
uniform float gooBlend;
float roundDistance(float2 p, float4 rect, float r) {
    float2 halfSize = (rect.zw - rect.xy) * 0.5;
    r = min(r, min(halfSize.x, halfSize.y));
    float2 q = abs(p - (rect.xy + rect.zw) * 0.5) - halfSize + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}
float gooDistance(float2 p) {
    float a = roundDistance(p, platter, gooRadius);
    if (drop.z <= drop.x || drop.w <= drop.y) return a;
    float b = roundDistance(p, drop, min(drop.z - drop.x, drop.w - drop.y) * 0.5);
    if (gooBlend <= 0.001) return min(a, b);
    float h = clamp(0.5 + 0.5 * (b - a) / gooBlend, 0.0, 1.0);
    return mix(b, a, h) - gooBlend * h * (1.0 - h);
}
layout(color) uniform half4 tint;
half4 main(float2 p) {
    float2 halfSize = (lensBounds.zw - lensBounds.xy) * 0.5;
    float2 center = lensBounds.xy + halfSize;
    float2 local = p - gutter;
    float2 v = local - center;
    float2 q = abs(v) - (halfSize - corner);
    float distance = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - corner;
    float2 outside = max(q, 0.0);
    float2 normal = length(outside) > 0.001 ? normalize(outside) :
        (q.x > q.y ? float2(1.0, 0.0) : float2(0.0, 1.0));
    normal *= sign(v);
    if (hasGoo > 0.5) {
        distance = gooDistance(local);
        float e = max(0.5, density * 0.25);
        float2 gradient = float2(gooDistance(local + float2(e, 0)) - gooDistance(local - float2(e, 0)), gooDistance(local + float2(0, e)) - gooDistance(local - float2(0, e)));
        normal = gradient / max(length(gradient), 0.001);
    }
    float bevel = min(10.0 * density, min(halfSize.x, halfSize.y) * 0.65);
    float rise = clamp(1.0 + distance / max(bevel, 0.001), 0.0, 1.0);
    float bend = 1.0 - sqrt(max(0.0, 1.0 - rise * rise));
    float depth = min(14.0 * density, min(halfSize.x, halfSize.y) * 0.8);
    float2 uv = gutter + center + v / 1.035 - normal * bend * depth;
    // Optional Gaussian filtering happens before refraction. Sparse offset taps would
    // replicate sharp glyphs instead of producing a continuous blur.
    half4 color = backdrop.eval(uv);
    half luma = dot(color.rgb, half3(0.2126, 0.7152, 0.0722));
    color.rgb = clamp(mix(half3(luma), color.rgb, saturation), 0.0, 1.0);
    color.rgb = mix(color.rgb, tint.rgb, tint.a);
    float backing = smoothstep(0.15, 1.0, (local.y - lensBounds.y) / max(1.0, halfSize.y * 2.0)) * 0.12 * tint.a;
    color.rgb = mix(color.rgb, half3(1.0), half(backing));
    float light = dot(normal, normalize(float2(-0.6, -1.0)));
    float edge = rise * rise * (0.035 + 0.15 * max(light, 0.0));
    return half4(mix(color.rgb, half3(1.0), half(edge)), 1.0);
}
"""
