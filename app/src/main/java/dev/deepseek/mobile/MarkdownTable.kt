package dev.deepseek.mobile

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import org.commonmark.ext.gfm.tables.TableCell

/** Cached Claude reply table: no outer frame, horizontal separators, open column spacing. */
@Composable
internal fun MarkdownTable(block: MarkdownBlock.Table) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val font = DshaFonts.family(FontRole.BODY)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = block.rows.maxOfOrNull { it.size } ?: return@BoxWithConstraints
        if (columns == 0) return@BoxWithConstraints
        val desired =
            remember(block, font, density.fontScale) {
                List(columns) { column ->
                    val sample =
                        block.rows
                            .take(80)
                            .mapNotNull { it.getOrNull(column)?.text }
                            .maxByOrNull { it.length }
                    val pixels =
                        measurer
                            .measure(
                                sample ?: androidx.compose.ui.text.AnnotatedString(""),
                                TextStyle(fontFamily = font, fontSize = 14.5.sp),
                                maxLines = 1,
                            )
                            .size
                            .width
                    with(density) { pixels.toDp() }
                        .plus(if (column == 0) 4.dp else 26.dp)
                        .coerceIn(76.dp, 230.dp)
                }
            }
        // Inline filenames/code are unbreakable. Compress descriptive text first, never
        // force the last character of a short filename onto a new row.
        val minimum =
            remember(block, font, density.fontScale) {
                List(columns) { column ->
                    val tokens =
                        block.rows
                            .take(80)
                            .mapNotNull { it.getOrNull(column)?.text }
                            .flatMap { text ->
                                text
                                    .getStringAnnotations(ReadingStyle.codeTag, 0, text.length)
                                    .map { it.item }
                            }
                    val longest = tokens.maxByOrNull { it.length }.orEmpty()
                    val pixels =
                        measurer
                            .measure(
                                androidx.compose.ui.text.AnnotatedString(longest),
                                TextStyle(fontFamily = DshaFonts.mono, fontSize = 13.5.sp),
                                maxLines = 1,
                            )
                            .size
                            .width
                    val shortLabelPixels =
                        block.rows
                            .take(80)
                            .mapNotNull { it.getOrNull(column)?.text }
                            .filter { it.length <= 4 }
                            .maxOfOrNull {
                                measurer
                                    .measure(
                                        it,
                                        TextStyle(fontFamily = font, fontSize = 14.5.sp),
                                        maxLines = 1,
                                    )
                                    .size
                                    .width
                            } ?: 0
                    val word =
                        block.rows
                            .take(80)
                            .mapNotNull { it.getOrNull(column)?.text?.text }
                            .flatMap {
                                Regex("[A-Za-z][A-Za-z0-9_-]*")
                                    .findAll(it)
                                    .map { match -> match.value }
                                    .toList()
                            }
                            .maxByOrNull { it.length }
                            .orEmpty()
                    val wordPixels =
                        measurer
                            .measure(
                                androidx.compose.ui.text.AnnotatedString(word),
                                TextStyle(fontFamily = font, fontSize = 14.5.sp),
                                maxLines = 1,
                            )
                            .size
                            .width
                    with(density) { maxOf(pixels, shortLabelPixels, wordPixels).toDp() }
                        .plus(if (column == 0) 4.dp else 26.dp)
                        .coerceIn(76.dp, 260.dp)
                }
            }
        val natural = desired.mapIndexed { i, w -> maxOf(w, minimum[i]) }
        val total = natural.fold(0.dp) { a, b -> a + b }
        val minTotal = minimum.fold(0.dp) { a, b -> a + b }
        val widths =
            if (total < maxWidth)
                desired.mapIndexed { i, w -> maxOf(w, minimum[i]) + (maxWidth - total) / columns }
            else if (columns <= 3 && minTotal <= maxWidth) {
                val extra = maxWidth - minTotal
                val flexible = desired.mapIndexed { i, w -> (w - minimum[i]).coerceAtLeast(1.dp) }
                val sum = flexible.fold(0.dp) { a, b -> a + b }
                minimum.mapIndexed { i, w -> w + extra * (flexible[i].value / sum.value) }
            } else desired.mapIndexed { i, w -> maxOf(w, minimum[i]) }
        val contentWidth = widths.fold(0.dp) { a, b -> a + b }
        val horizontal = rememberScrollState()
        val vertical = rememberScrollState()
        Box(
            Modifier.fillMaxWidth()
                .heightIn(max = 360.dp)
                .contentScrollIndicators(horizontal, vertical)
                .testTag("markdown-table"),
        ) {
            Column(
                Modifier.verticalScroll(vertical).horizontalScroll(horizontal).width(contentWidth),
            ) {
                block.rows.forEachIndexed { index, cells ->
                    if (index > 0) HorizontalDivider(color = ReadingStyle.line, thickness = 1.dp)
                    Row {
                        widths.forEachIndexed { column, width ->
                            val cell = cells.getOrNull(column)
                            ProseText(
                                cell?.text ?: androidx.compose.ui.text.AnnotatedString(""),
                                Modifier.width(width)
                                    .padding(
                                        start = if (column == 0) 0.dp else 21.75.dp,
                                        top = 7.34375.dp,
                                        bottom = 7.34375.dp,
                                    ),
                                size = 14.5.sp,
                                balanced = true,
                                weight = if (index == 0) FontWeight(580) else FontWeight.Normal,
                                align =
                                    when (cell?.align) {
                                        TableCell.Alignment.RIGHT -> TextAlign.Right
                                        TableCell.Alignment.CENTER -> TextAlign.Center
                                        else -> TextAlign.Start
                                    },
                            )
                        }
                    }
                }
            }
        }
    }
}
