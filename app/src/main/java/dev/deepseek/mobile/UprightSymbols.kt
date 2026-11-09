package dev.deepseek.mobile

import android.icu.lang.UCharacter
import android.icu.lang.UProperty
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text as MaterialText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.TextUnit

/** Preserve complete emoji sequences and UTF-16 offsets; never replace the underlying text. */
internal object UprightSymbols {
    private fun symbol(cp: Int): Boolean =
        cp > 0x7f &&
            when (Character.getType(cp)) {
                Character.OTHER_SYMBOL.toInt(),
                Character.MATH_SYMBOL.toInt(),
                Character.MODIFIER_SYMBOL.toInt(),
                Character.CURRENCY_SYMBOL.toInt() -> true
                else -> cp > 0x7f && UCharacter.hasBinaryProperty(cp, UProperty.EMOJI)
            }

    private fun extension(cp: Int) =
        cp in 0xfe00..0xfe0f || cp in 0x1f3fb..0x1f3ff || cp in 0xe0020..0xe007f || cp == 0x20e3

    fun ranges(text: String): List<IntRange> {
        val result = mutableListOf<IntRange>()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val next = i + Character.charCount(cp)
            val keycap = cp in 0x30..0x39 || cp == 0x23 || cp == 0x2a
            val keycapEnd = if (text.getOrNull(next) == '\ufe0f') next + 1 else next
            if (!symbol(cp) && !(keycap && text.getOrNull(keycapEnd) == '\u20e3')) {
                i = next
                continue
            }
            val start = i
            i = next
            while (i < text.length) {
                val following = text.codePointAt(i)
                if (symbol(following) || extension(following)) i += Character.charCount(following)
                else if (
                    following == 0x200d && i + 1 < text.length && symbol(text.codePointAt(i + 1))
                )
                    i++
                else break
            }
            result += start until i
        }
        return result
    }

    fun text(source: AnnotatedString): AnnotatedString {
        val ranges = ranges(source.text)
        if (ranges.isEmpty()) return source
        return AnnotatedString.Builder(source)
            .apply {
                ranges.forEach { range ->
                    addStyle(
                        SpanStyle(
                            fontStyle = FontStyle.Normal,
                            fontFamily = FontFamily.Default,
                            fontSynthesis = FontSynthesis.None,
                            textGeometricTransform = TextGeometricTransform(1f, 0f),
                        ),
                        range.first,
                        range.last + 1,
                    )
                }
            }
            .toAnnotatedString()
    }

    val transformation = VisualTransformation { TransformedText(text(it), OffsetMapping.Identity) }
}

/** App-wide text entry point: typography can be italic, emoji and symbols cannot. */
@Composable
internal fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current,
) =
    Text(
        remember(text) { AnnotatedString(text) },
        modifier,
        color,
        fontSize,
        fontStyle,
        fontWeight,
        fontFamily,
        letterSpacing,
        textDecoration,
        textAlign,
        lineHeight,
        overflow,
        softWrap,
        maxLines,
        minLines,
        onTextLayout = { onTextLayout?.invoke(it) },
        style = style,
    )

@Composable
internal fun Text(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    inlineContent: Map<String, InlineTextContent> = emptyMap(),
    onTextLayout: (TextLayoutResult) -> Unit = {},
    style: TextStyle = LocalTextStyle.current,
) =
    MaterialText(
        text = remember(text) { UprightSymbols.text(text) },
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        fontStyle = fontStyle,
        fontWeight = fontWeight,
        fontFamily = fontFamily,
        letterSpacing = letterSpacing,
        textDecoration = textDecoration,
        textAlign = textAlign,
        lineHeight = lineHeight,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines,
        inlineContent = inlineContent,
        onTextLayout = onTextLayout,
        style = style,
    )
