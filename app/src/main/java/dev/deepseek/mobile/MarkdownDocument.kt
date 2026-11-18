package dev.deepseek.mobile

import android.util.LruCache
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.*
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.*
import org.commonmark.node.Text as MdText
import org.commonmark.parser.Parser

internal sealed interface MarkdownBlock {
    data class Paragraph(val text: AnnotatedString) : MarkdownBlock

    data class Heading(val level: Int, val text: AnnotatedString) : MarkdownBlock

    data class Code(val language: String, val text: String) : MarkdownBlock

    data class Fold(val title: String, val body: String, val initiallyOpen: Boolean) : MarkdownBlock

    data class Formula(val raw: String) : MarkdownBlock

    data class Quote(val children: List<MarkdownBlock>) : MarkdownBlock

    data class Listing(val items: List<Item>) : MarkdownBlock

    data class Item(val marker: String, val checked: Boolean?, val children: List<MarkdownBlock>)

    data class Cell(val text: AnnotatedString, val align: TableCell.Alignment?)

    data class Table(val rows: List<List<Cell>>) : MarkdownBlock

    data class Image(val alt: String, val url: String) : MarkdownBlock

    data object Rule : MarkdownBlock
}

/** CommonMark AST to immutable native blocks. HTML remains text, and links use explicit schemes. */
internal object MarkdownDocument {
    private val parser =
        Parser.builder()
            .extensions(
                listOf(
                    TablesExtension.create(),
                    StrikethroughExtension.create(),
                    AutolinkExtension.create(),
                    TaskListItemsExtension.create(),
                ),
            )
            .build()
    private val cache =
        object : LruCache<String, List<MarkdownBlock>>(4 * 1024 * 1024) {
            override fun sizeOf(key: String, value: List<MarkdownBlock>) = key.length * 4 + 1024
        }

    fun cached(raw: String): List<MarkdownBlock>? = cache.get(raw)

    fun invalidate() = cache.evictAll()

    fun parse(raw: String): List<MarkdownBlock> =
        cache.get(raw) ?: parseDocument(raw).also { cache.put(raw, it) }

    private val disclosureStart =
        Regex(
            "^ {0,3}<details( +open)? *> *(?:<summary>(.*?)</summary>)? *$",
            RegexOption.IGNORE_CASE,
        )
    private val disclosureEnd = Regex("^ {0,3}</details> *$", RegexOption.IGNORE_CASE)
    private val summaryLine = Regex("^ *<summary>(.*?)</summary> *$", RegexOption.IGNORE_CASE)
    private val fenceLine = Regex("^ {0,3}(`{3,}|~{3,})(.*)$")

    private fun parseDocument(raw: String): List<MarkdownBlock> {
        if (!raw.contains("<details", ignoreCase = true)) return blocks(parser.parse(raw), 0)
        val output = mutableListOf<MarkdownBlock>()
        var fence = ""
        var position = 0
        var previousEnd = 0
        var bodyStart = 0
        var level = 0
        var title: String? = null
        var open = false
        fun addPlain(text: String) {
            if (text.isBlank()) return
            if (MathDocument.containsMath(text)) output.add(MarkdownBlock.Formula(text))
            else output.addAll(blocks(parser.parse(text), 0))
        }
        fun emit(end: Int) {
            val rawBody = raw.substring(bodyStart, end).trim()
            val summary =
                if (title == null)
                    summaryLine.matchEntire(rawBody.substringBefore('\n').trimEnd('\r'))
                else null
            val body =
                if (summary != null) rawBody.substringAfter('\n', "").trimStart() else rawBody
            output.add(
                MarkdownBlock.Fold(title ?: summary?.groupValues?.get(1) ?: "详细内容", body, open),
            )
        }
        for (original in raw.splitToSequence('\n')) {
            val line = original.trimEnd('\r', ' ', '\t')
            val begin = position
            position += original.length + 1
            val delimiter = fenceLine.matchEntire(line)
            if (delimiter != null) {
                val run = delimiter.groupValues[1]
                if (fence.isEmpty()) fence = run
                else if (
                    run.first() == fence.first() &&
                        run.length >= fence.length &&
                        delimiter.groupValues[2].isBlank()
                )
                    fence = ""
                continue
            }
            if (fence.isNotEmpty()) continue
            val opening = disclosureStart.matchEntire(line)
            if (opening != null) {
                if (level++ == 0) {
                    addPlain(raw.substring(previousEnd, begin))
                    bodyStart = position.coerceAtMost(raw.length)
                    title = opening.groups[2]?.value
                    open = opening.groupValues[1].isNotBlank()
                }
            } else if (level > 0 && disclosureEnd.matches(line) && --level == 0) {
                emit(begin)
                previousEnd = position.coerceAtMost(raw.length)
            }
        }
        if (level > 0) emit(raw.length) else addPlain(raw.substring(previousEnd))
        return output
    }

    private fun Node.nodes(): List<Node> = buildList {
        var n = firstChild
        while (n != null) {
            add(n)
            n = n.next
        }
    }

    fun safeLink(url: String): Boolean =
        when {
            url.startsWith("dsha://file/") ->
                AttachmentStore.valid(url.removePrefix("dsha://file/"))
            url.startsWith("mailto:") -> !url.contains(Regex("[\\r\\n]")) && url.length < 400
            else ->
                runCatching {
                        java.net.URI(url).let {
                            it.scheme in listOf("https", "http") &&
                                !it.host.isNullOrBlank() &&
                                it.userInfo == null
                        }
                    }
                    .getOrDefault(false)
        }

    private fun blocks(parent: Node, depth: Int): List<MarkdownBlock> =
        parent.nodes().flatMap { node ->
            if (depth > 24) listOf(MarkdownBlock.Paragraph(inline(node.nodes())))
            else
                when (node) {
                    is org.commonmark.node.Heading ->
                        listOf(MarkdownBlock.Heading(node.level, inline(node.nodes())))
                    is org.commonmark.node.Paragraph -> {
                        val out = mutableListOf<MarkdownBlock>()
                        val pending = mutableListOf<Node>()
                        fun flush() {
                            if (pending.isNotEmpty()) {
                                val text = inline(pending)
                                if (text.isNotBlank()) out.add(MarkdownBlock.Paragraph(text))
                                pending.clear()
                            }
                        }
                        node.nodes().forEach { child ->
                            if (child is org.commonmark.node.Image) {
                                flush()
                                out.add(MarkdownBlock.Image(plain(child), child.destination))
                            } else pending.add(child)
                        }
                        flush()
                        out
                    }
                    is FencedCodeBlock ->
                        listOf(
                            MarkdownBlock.Code(
                                node.info.orEmpty().substringBefore(' ').take(40),
                                node.literal.removeSuffix("\n"),
                            ),
                        )
                    is IndentedCodeBlock ->
                        listOf(MarkdownBlock.Code("", node.literal.removeSuffix("\n")))
                    is BlockQuote -> listOf(MarkdownBlock.Quote(blocks(node, depth + 1)))
                    is BulletList,
                    is OrderedList -> {
                        val start = (node as? OrderedList)?.startNumber ?: 1
                        listOf(
                            MarkdownBlock.Listing(
                                node.nodes().mapIndexed { i, item ->
                                    val marker =
                                        (item.firstChild as? TaskListItemMarker)
                                            ?: (item.firstChild?.firstChild as? TaskListItemMarker)
                                    MarkdownBlock.Item(
                                        if (node is OrderedList) "${start+i}." else "•",
                                        marker?.isChecked,
                                        blocks(item, depth + 1),
                                    )
                                },
                            ),
                        )
                    }
                    is TableBlock ->
                        listOf(
                            MarkdownBlock.Table(
                                node.nodes().flatMap { section ->
                                    section.nodes().map { row ->
                                        row.nodes().filterIsInstance<TableCell>().map {
                                            MarkdownBlock.Cell(inline(it.nodes()), it.alignment)
                                        }
                                    }
                                },
                            ),
                        )
                    is ThematicBreak -> listOf(MarkdownBlock.Rule)
                    is HtmlBlock ->
                        listOf(MarkdownBlock.Paragraph(AnnotatedString(node.literal.trimEnd())))
                    else -> blocks(node, depth + 1)
                }
        }

    private fun plain(node: Node): String = buildString {
        fun walk(n: Node) {
            if (n is MdText) append(n.literal)
            else if (n is Code) append(n.literal) else n.nodes().forEach(::walk)
        }
        walk(node)
    }

    private fun inline(nodes: List<Node>): AnnotatedString = buildAnnotatedString {
        fun appendNode(n: Node, depth: Int) {
            if (depth > 64) {
                append(plain(n))
                return
            }
            fun children() = n.nodes().forEach { appendNode(it, depth + 1) }
            when (n) {
                is MdText -> append(n.literal)
                is SoftLineBreak,
                is HardLineBreak -> append("\n")
                is StrongEmphasis ->
                    withStyle(
                        SpanStyle(
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = DshaFonts.family(FontRole.BODY, weight = 600),
                        ),
                    ) {
                        pushStringAnnotation(MarkdownSelection.inline, "**")
                        children()
                        pop()
                    }
                is Emphasis ->
                    withStyle(
                        SpanStyle(
                            fontStyle = FontStyle.Italic,
                            fontFamily = DshaFonts.family(FontRole.BODY, italic = true),
                        ),
                    ) {
                        pushStringAnnotation(MarkdownSelection.inline, "*")
                        children()
                        pop()
                    }
                is Strikethrough -> {
                    pushStringAnnotation(MarkdownSelection.inline, "~~")
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { children() }
                    pop()
                }
                is Code -> {
                    pushStringAnnotation(ReadingStyle.codeTag, n.literal)
                    pushStringAnnotation(MarkdownSelection.inline, "code")
                    withStyle(
                        SpanStyle(
                            fontFamily = DshaFonts.mono,
                            fontSize =
                                androidx.compose.ui.unit.TextUnit(
                                    13.5f,
                                    androidx.compose.ui.unit.TextUnitType.Sp,
                                ),
                            color = ReadingStyle.ink,
                        ),
                    ) {
                        append(n.literal)
                    }
                    pop()
                    pop()
                }
                is Link -> {
                    val url = n.destination
                    val style =
                        TextLinkStyles(
                            SpanStyle(
                                color = ReadingStyle.link,
                                textDecoration = TextDecoration.Underline,
                            ),
                        )
                    if (!safeLink(url)) children()
                    else {
                        pushStringAnnotation(MarkdownSelection.inline, "link:$url")
                        if (url.startsWith("dsha://file/"))
                            withLink(
                                LinkAnnotation.Clickable(url, style) {
                                    FilePreviewState.open(url.substringAfterLast('/'))
                                },
                            ) {
                                children()
                            }
                        else withLink(LinkAnnotation.Url(url, style)) { children() }
                        pop()
                    }
                }
                is org.commonmark.node.Image -> append(plain(n))
                is HtmlInline ->
                    append(
                        if (n.literal.equals("<br>", true) || n.literal.equals("<br/>", true)) "\n"
                        else n.literal,
                    )
                is TaskListItemMarker -> Unit
                else -> children()
            }
        }
        nodes.forEach { appendNode(it, 0) }
    }
}
