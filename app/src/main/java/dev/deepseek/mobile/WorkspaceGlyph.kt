package dev.deepseek.mobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp
import androidx.core.graphics.PathParser

private class WorkspaceGlyphPath {
    private val panel = PathParser.createNodesFromPathData(AppIcons.rightWorkspacePath)
    private val tabs = PathParser.createNodesFromPathData(AppIcons.workspaceTabsPath)
    private val nodes = PathParser.deepCopyNodes(panel)
    private val nativePath = android.graphics.Path()
    private val path = nativePath.asComposePath()

    fun at(fraction: Float): androidx.compose.ui.graphics.Path {
        PathParser.interpolatePathDataNodes(nodes, fraction, panel, tabs)
        nativePath.rewind()
        PathParser.nodesToPath(nodes, nativePath)
        return path
    }
}

@Composable
internal fun WorkspaceGlyph(progress: State<Float>) {
    val morph = remember { WorkspaceGlyphPath() }
    val pen = remember { Stroke(width = 1.65f, cap = StrokeCap.Round, join = StrokeJoin.Round) }
    Canvas(Modifier.size(22.dp)) {
        val path = morph.at(progress.value.coerceIn(0f, 1f))
        scale(size.minDimension / 24f, pivot = Offset.Zero) {
            drawPath(path, WhaleInk, style = pen)
        }
    }
}
