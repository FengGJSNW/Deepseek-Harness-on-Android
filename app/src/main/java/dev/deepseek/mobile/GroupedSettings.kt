package dev.deepseek.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.ParentDataModifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.*

private data class GroupRow(val indent: Dp) : ParentDataModifier {
    override fun Density.modifyParentData(parentData: Any?) = this@GroupRow
}

internal fun Modifier.groupRow(indent: Dp) = then(GroupRow(indent))

private data class GroupDivider(val y: Int, val inset: Float)

/** One 24 dp continuous card; one physical-pixel separator at each title inset. */
@Composable
internal fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    var dividers by remember { mutableStateOf(emptyList<GroupDivider>()) }
    DshaCard(padding = PaddingValues(0.dp), spacing = 0.dp) {
        Layout(
            modifier =
                Modifier.fillMaxWidth().testTag("settings-group").drawWithContent {
                    drawContent()
                    for ((y, inset) in dividers) {
                        drawLine(
                            WhaleLine.copy(alpha = .75f),
                            Offset(inset, y.toFloat() - .5f),
                            Offset(size.width - 16.dp.toPx(), y.toFloat() - .5f),
                            strokeWidth = 1f,
                        )
                    }
                },
            content = { content() },
        ) { children, constraints ->
            val rows = children.map {
                it.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
            }
            var height = 0
            val ends =
                rows.dropLast(1).map { row ->
                    height += row.height
                    GroupDivider(height, ((row.parentData as? GroupRow)?.indent ?: 16.dp).toPx())
                }
            if (dividers != ends) dividers = ends
            layout(constraints.maxWidth, constraints.constrainHeight(rows.sumOf { it.height })) {
                var y = 0
                rows.forEach { row ->
                    row.placeRelative(0, y)
                    y += row.height
                }
            }
        }
    }
}
