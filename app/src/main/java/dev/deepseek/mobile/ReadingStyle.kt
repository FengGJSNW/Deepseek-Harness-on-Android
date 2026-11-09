package dev.deepseek.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*

/**
 * Reading and work surfaces have separate visual roles. Geometry follows the supplied snapshots.
 */
internal object ReadingStyle {
    val ink = Color(0xFF292824)
    val secondary = Color(0xFF77756F)
    val line = Color(0xFFE5E3DF)
    val header = Color(0xFFF5F4F1)
    val inline = Color(0xFFF1F0ED)
    val link = Color(0xFF326B92)
    val workSurface = Color(0xFFF7F7F8)
    val workLine = Color(0xFFE5E5E5)
    const val codeTag = "dsha.inline-code"
}

/** Paint inline-code shoulders behind the real text, preserving selection, links and wrapping. */
@Composable
internal fun ProseText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = ReadingStyle.ink,
    size: TextUnit = 15.sp,
    weight: FontWeight = FontWeight(if (FontSettings.knownFeatures) FontSettings.weight else 400),
    decoration: androidx.compose.ui.text.style.TextDecoration? = null,
    align: androidx.compose.ui.text.style.TextAlign =
        androidx.compose.ui.text.style.TextAlign.Start,
    balanced: Boolean = false,
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val codes = remember(text) { text.getStringAnnotations(ReadingStyle.codeTag, 0, text.length) }
    Text(
        text,
        modifier.drawBehind {
            val result = layout ?: return@drawBehind
            for (code in codes) {
                if (code.start >= code.end || code.end > result.layoutInput.text.length) continue
                val first = result.getLineForOffset(code.start)
                val last = result.getLineForOffset(code.end - 1)
                for (line in first..last) {
                    val start = maxOf(code.start, result.getLineStart(line))
                    val end = minOf(code.end, result.getLineEnd(line, visibleEnd = true))
                    if (end <= start) continue
                    val firstBox = result.getBoundingBox(start)
                    val lastBox = result.getBoundingBox(end - 1)
                    val bounds =
                        Rect(
                            minOf(firstBox.left, lastBox.left),
                            minOf(firstBox.top, lastBox.top),
                            maxOf(firstBox.right, lastBox.right),
                            maxOf(firstBox.bottom, lastBox.bottom),
                        )
                    val pillHeight = (15.5.sp.toPx() + 1.dp.toPx()).coerceAtMost(bounds.height)
                    val rect =
                        Rect(
                            bounds.left - 1.5.dp.toPx(),
                            bounds.center.y - pillHeight / 2,
                            bounds.right + 1.5.dp.toPx(),
                            bounds.center.y + pillHeight / 2,
                        )
                    translate(rect.left, rect.top) {
                        val outline =
                            ContinuousShape(3.dp).createOutline(rect.size, layoutDirection, this)
                        drawOutline(outline, ReadingStyle.inline)
                        drawOutline(outline, ReadingStyle.line, style = Stroke(.5.dp.toPx()))
                    }
                }
            }
        },
        color = color,
        fontFamily = DshaFonts.family(FontRole.BODY, weight = weight.weight),
        fontSize = size,
        letterSpacing = 0.sp,
        lineHeight = size * ExperienceSettings.lineSpacing,
        fontWeight = weight,
        textDecoration = decoration,
        textAlign = align,
        style =
            androidx.compose.material3.LocalTextStyle.current.copy(
                lineBreak =
                    if (balanced) androidx.compose.ui.text.style.LineBreak.Heading
                    else androidx.compose.ui.text.style.LineBreak.Paragraph,
            ),
        onTextLayout = { layout = it },
    )
}
