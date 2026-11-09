package dev.deepseek.mobile

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** Native overlay adapter for the same continuous geometry used by Compose. */
internal class ContinuousDrawable(
    private val fill: Int,
    private val radiusPx: Float,
    private val border: Int = 0,
    private val borderWidth: Float = 0f,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var path = android.graphics.Path()
    private var opacity = 255

    override fun onBoundsChange(bounds: Rect) {
        val shape = ContinuousShape(radiusPx.dp)
        val size =
            Size(
                (bounds.width() - borderWidth).coerceAtLeast(0f),
                (bounds.height() - borderWidth).coerceAtLeast(0f),
            )
        path =
            (shape.createOutline(size, LayoutDirection.Ltr, Density(1f)) as Outline.Generic)
                .path
                .asAndroidPath()
        path.offset(bounds.left + borderWidth / 2, bounds.top + borderWidth / 2)
    }

    override fun draw(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = fill
        paint.alpha = android.graphics.Color.alpha(fill) * opacity / 255
        canvas.drawPath(path, paint)
        if (borderWidth > 0) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = borderWidth
            paint.color = border
            paint.alpha = android.graphics.Color.alpha(border) * opacity / 255
            canvas.drawPath(path, paint)
        }
    }

    override fun getOutline(outline: android.graphics.Outline) {
        outline.setPath(path)
    }

    override fun setAlpha(alpha: Int) {
        opacity = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Suppress("DEPRECATION") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
