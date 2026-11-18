package dev.deepseek.mobile

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Code-drawn search glyphs: one 24 dp grid, 1.65 dp round strokes, no external icon font or SVG.
 */
internal object SearchIcons {
    private fun draw(name: String, commands: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .apply {
                path(
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.65f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                    pathBuilder = commands,
                )
            }
            .build()

    val All by lazy {
        draw("SearchAll") {
            moveTo(4f, 3.5f)
            lineTo(8.5f, 3.5f)
            quadTo(9.5f, 3.5f, 9.5f, 4.5f)
            lineTo(9.5f, 8.5f)
            quadTo(9.5f, 9.5f, 8.5f, 9.5f)
            lineTo(4.5f, 9.5f)
            quadTo(3.5f, 9.5f, 3.5f, 8.5f)
            lineTo(3.5f, 4.5f)
            quadTo(3.5f, 3.5f, 4.5f, 3.5f)
            moveTo(14.5f, 4f)
            lineTo(20.5f, 4f)
            moveTo(14.5f, 8.5f)
            lineTo(19f, 8.5f)
            moveTo(4f, 14.5f)
            lineTo(8.5f, 14.5f)
            moveTo(4f, 20f)
            lineTo(8.5f, 20f)
            moveTo(20f, 16f)
            curveTo(20f, 13.8f, 18.2f, 12f, 16f, 12f)
            curveTo(13.8f, 12f, 12f, 13.8f, 12f, 16f)
            curveTo(12f, 18.2f, 13.8f, 20f, 16f, 20f)
            curveTo(18.2f, 20f, 20f, 18.2f, 20f, 16f)
            moveTo(19f, 19f)
            lineTo(21.5f, 21.5f)
        }
    }
    val Chats by lazy {
        draw("SearchChats") {
            moveTo(7.5f, 4f)
            lineTo(16.5f, 4f)
            quadTo(21f, 4f, 21f, 8.5f)
            lineTo(21f, 13.5f)
            quadTo(21f, 18f, 16.5f, 18f)
            lineTo(9f, 18f)
            lineTo(4f, 21f)
            lineTo(4f, 16.5f)
            quadTo(3f, 15.3f, 3f, 13.5f)
            lineTo(3f, 8.5f)
            quadTo(3f, 4f, 7.5f, 4f)
            close()
            moveTo(7.5f, 9f)
            lineTo(16.5f, 9f)
            moveTo(7.5f, 13f)
            lineTo(13.5f, 13f)
        }
    }
    val Images by lazy {
        draw("SearchImages") {
            moveTo(6.5f, 3.5f)
            lineTo(17.5f, 3.5f)
            quadTo(21f, 3.5f, 21f, 7f)
            lineTo(21f, 17f)
            quadTo(21f, 20.5f, 17.5f, 20.5f)
            lineTo(6.5f, 20.5f)
            quadTo(3f, 20.5f, 3f, 17f)
            lineTo(3f, 7f)
            quadTo(3f, 3.5f, 6.5f, 3.5f)
            close()
            moveTo(3.5f, 16.5f)
            lineTo(8.5f, 11.5f)
            lineTo(14f, 17f)
            moveTo(12f, 15f)
            lineTo(15f, 12f)
            lineTo(20.5f, 17.5f)
            moveTo(17.5f, 8f)
            curveTo(17.5f, 9.1f, 16.6f, 10f, 15.5f, 10f)
            curveTo(14.4f, 10f, 13.5f, 9.1f, 13.5f, 8f)
            curveTo(13.5f, 6.9f, 14.4f, 6f, 15.5f, 6f)
            curveTo(16.6f, 6f, 17.5f, 6.9f, 17.5f, 8f)
        }
    }
    val Files by lazy {
        draw("SearchFiles") {
            moveTo(8f, 3f)
            lineTo(14f, 3f)
            lineTo(19.5f, 8.5f)
            lineTo(19.5f, 18f)
            quadTo(19.5f, 21f, 16.5f, 21f)
            lineTo(7.5f, 21f)
            quadTo(4.5f, 21f, 4.5f, 18f)
            lineTo(4.5f, 6.5f)
            quadTo(4.5f, 3f, 8f, 3f)
            close()
            moveTo(14f, 3.5f)
            lineTo(14f, 7f)
            quadTo(14f, 8.5f, 15.5f, 8.5f)
            lineTo(19f, 8.5f)
            moveTo(8f, 12.5f)
            lineTo(15.5f, 12.5f)
            moveTo(8f, 16.5f)
            lineTo(13.5f, 16.5f)
        }
    }
    val Projects by lazy {
        draw("SearchProjects") {
            moveTo(3f, 8f)
            lineTo(3f, 6.5f)
            quadTo(3f, 4f, 5.5f, 4f)
            lineTo(9f, 4f)
            lineTo(11f, 6.5f)
            lineTo(18.5f, 6.5f)
            quadTo(21f, 6.5f, 21f, 9f)
            lineTo(21f, 17f)
            quadTo(21f, 20f, 18f, 20f)
            lineTo(6f, 20f)
            quadTo(3f, 20f, 3f, 17f)
            lineTo(3f, 8f)
            close()
            moveTo(3.5f, 10f)
            lineTo(20.5f, 10f)
            moveTo(8.5f, 14.5f)
            lineTo(15.5f, 14.5f)
        }
    }
}
