package dev.deepseek.mobile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.*

@Composable
internal fun PrismGlassPane(
    modifier: Modifier,
    plate: Rect,
    radius: Float,
    drop: Rect?,
    blend: Float,
    opacity: Float,
    elevation: Dp,
    finish: GlassFinish = GlassFinish.Menu,
    material: Float = 1f,
) {
    if (plate.isEmpty || opacity <= 0f) {
        Spacer(modifier)
        return
    }
    val density = LocalDensity.current.density
    val (contour, glass) =
        remember(plate, radius, drop, blend, density) {
            val outline = PrismGoo.outline(plate, radius, drop, blend)
            val bounds = outline.getBounds()
            outline.transform(Matrix().apply { scale(density, density) })
            val shape =
                object : Shape {
                    override fun createOutline(
                        size: Size,
                        layoutDirection: LayoutDirection,
                        density: Density,
                    ) = Outline.Generic(outline)
                }
            shape to GlassGoo(plate, radius, drop, blend, density, bounds)
        }
    Box(
        modifier.graphicsLayer {
            shape = contour
            clip = true
            alpha = opacity
            shadowElevation = elevation.toPx()
            ambientShadowColor = Color(0x150F172A)
            spotShadowColor = Color(0x220F172A)
        },
    ) {
        CompositionLocalProvider(LocalHideGlassPane provides false) {
            LiquidGlass(
                Modifier.matchParentSize(),
                shape = contour,
                finish = finish,
                goo = glass.copy(material = material),
            )
        }
    }
}
