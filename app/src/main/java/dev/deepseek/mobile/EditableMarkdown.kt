package dev.deepseek.mobile

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em
import org.commonmark.ext.gfm.strikethrough.*
import org.commonmark.node.*
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser

/**
 * The source remains the editable document. Only its presentation changes; no second editor/IME.
 */
internal object EditableMarkdown : VisualTransformation {
    private val parser =
        Parser.builder()
            .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
            .extensions(listOf(StrikethroughExtension.create()))
            .build()

    override fun filter(text: AnnotatedString): TransformedText {
        val source = text.text
        val hidden = BooleanArray(source.length)
        val substitutions = mutableMapOf<Int, Char>()
        val closings = mutableListOf<Int>()
        val styles = mutableListOf<Triple<Int, Int, SpanStyle>>()
        fun hide(start: Int, end: Int, closing: Boolean = false) {
            if (closing) closings.add(start)
            for (i in start.coerceAtLeast(0) until end.coerceAtMost(source.length)) hidden[i] = true
        }
        fun start(n: Node) = n.sourceSpans.firstOrNull()?.inputIndex ?: 0
        fun end(n: Node) = n.sourceSpans.lastOrNull()?.let { it.inputIndex + it.length } ?: start(n)
        fun visit(n: Node, depth: Int = 0) {
            if (depth > 64) return
            val a = start(n)
            val b = end(n)
            var style: SpanStyle? = null
            when (n) {
                is StrongEmphasis,
                is Emphasis,
                is Strikethrough -> {
                    val delimiter = n as Delimited
                    hide(a, a + delimiter.openingDelimiter.length)
                    hide(b - delimiter.closingDelimiter.length, b, true)
                    style =
                        when (n) {
                            is StrongEmphasis -> SpanStyle(fontWeight = FontWeight.Bold)
                            is Emphasis -> SpanStyle(fontStyle = FontStyle.Italic)
                            else -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                        }
                }
                is Heading -> {
                    n.firstChild?.let { hide(a, start(it)) }
                    n.lastChild?.let { hide(end(it), b, true) }
                    style =
                        SpanStyle(
                            fontSize = (1.55f - n.level * .09f).em,
                            fontWeight = FontWeight.SemiBold,
                        )
                }
                is Link,
                is Image -> {
                    if (n.firstChild != null) {
                        hide(a, start(n.firstChild))
                        hide(end(n.lastChild), b, true)
                        style =
                            SpanStyle(
                                color = Color(0xFF4268BD),
                                textDecoration = TextDecoration.Underline,
                            )
                    }
                }
                is Code -> {
                    val ticks = source.substring(a, b).takeWhile { it == '`' }.length
                    hide(a, a + ticks)
                    hide(b - ticks, b, true)
                    style =
                        SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0xFFEFF2F6))
                }
                is FencedCodeBlock -> {
                    val first = n.sourceSpans.firstOrNull()
                    if (first != null && n.sourceSpans.size > 1) {
                        hide(a, (a + first.length + 1).coerceAtMost(source.length))
                        if (n.closingFenceLength != null) {
                            val last = n.sourceSpans.last()
                            hide((last.inputIndex - 1).coerceAtLeast(a), b, true)
                        }
                    }
                    style =
                        SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0xFFF0F3F7))
                }
                is IndentedCodeBlock ->
                    style =
                        SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0xFFF0F3F7))
                is BlockQuote -> {
                    n.sourceSpans.forEach { span ->
                        val i = span.inputIndex
                        if (source.getOrNull(i) == '>') substitutions[i] = '│'
                    }
                    style = SpanStyle(color = Color(0xFF667085))
                }
                is ListItem -> {
                    if (source.getOrNull(a) in listOf('-', '+', '*')) substitutions[a] = '•'
                    val raw = source.substring(a, b)
                    val task = Regex("^[*+\\-] \\[([ xX])] ").find(raw)
                    if (task != null) {
                        substitutions[a] = if (task.groupValues[1] == " ") '☐' else '☑'
                        hide(a + 1, a + task.value.length - 1)
                    }
                }
                is ThematicBreak -> {
                    if (b > a) {
                        substitutions[a] = '─'
                        hide(a + 1, b)
                    }
                    style = SpanStyle(color = Color(0xFFBCC6D4))
                }
            }
            if (style != null && b > a) styles.add(Triple(a, b, style))
            var child = n.firstChild
            while (child != null) {
                visit(child, depth + 1)
                child = child.next
            }
        }
        visit(parser.parse(source))
        val forward = IntArray(source.length + 1)
        val reverse = mutableListOf<Int>()
        val rendered = StringBuilder()
        for (i in source.indices) {
            forward[i] = rendered.length
            if (!hidden[i]) {
                reverse.add(i)
                rendered.append(substitutions[i] ?: source[i])
            }
        }
        forward[source.length] = rendered.length
        reverse.add(source.length)
        // At a closing delimiter, typing continues inside that rendered style.
        for (i in closings.sortedDescending()) if (i in forward.indices) reverse[forward[i]] = i
        // Nested/adjacent markup must never make cursor mappings run backwards.
        for (i in 1 until reverse.size) reverse[i] = maxOf(reverse[i - 1], reverse[i])
        val styled = AnnotatedString.Builder(rendered.toString())
        for ((a, b, style) in styles) {
            val from = forward[a.coerceIn(0, source.length)]
            val to = forward[b.coerceIn(0, source.length)]
            if (to > from) styled.addStyle(style, from, to)
        }
        return TransformedText(
            styled.toAnnotatedString(),
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int) =
                    forward[offset.coerceIn(forward.indices)]

                override fun transformedToOriginal(offset: Int) =
                    reverse[offset.coerceIn(reverse.indices)]
            },
        )
    }
}
