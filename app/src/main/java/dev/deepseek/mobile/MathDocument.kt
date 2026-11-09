package dev.deepseek.mobile

import org.commonmark.Extension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Node
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.commonmark.renderer.NodeRenderer
import org.commonmark.renderer.html.HtmlRenderer
import org.commonmark.renderer.html.UrlSanitizer

internal object MathDocument {
    private val extensions: List<Extension> =
        listOf(
            TablesExtension.create(),
            StrikethroughExtension.create(),
            TaskListItemsExtension.create(),
        )
    private val parser = Parser.builder().extensions(extensions).build()
    private val renderer =
        HtmlRenderer.builder()
            .extensions(extensions)
            .nodeRendererFactory { context ->
                object : NodeRenderer {
                    override fun getNodeTypes(): Set<Class<out Node>> =
                        setOf(FencedCodeBlock::class.java, IndentedCodeBlock::class.java)

                    override fun render(node: Node) {
                        val language =
                            (node as? FencedCodeBlock)?.info.orEmpty().trim().substringBefore(' ')
                        val code =
                            when (node) {
                                is FencedCodeBlock -> node.literal
                                is IndentedCodeBlock -> node.literal
                                else -> return
                            }
                        val writer = context.writer
                        writer.line()
                        writer.tag("pre")
                        writer.tag(
                            "code",
                            if (language.isBlank()) emptyMap()
                            else mapOf("class" to "language-$language"),
                        )
                        writer.raw(CodeHighlight.html(code, language))
                        writer.tag("/code")
                        writer.tag("/pre")
                        writer.line()
                    }
                }
            }
            .escapeHtml(true)
            .sanitizeUrls(true)
            .urlSanitizer(
                object : UrlSanitizer {
                    override fun sanitizeLinkUrl(url: String): String =
                        if (MarkdownDocument.safeLink(url)) url else ""

                    override fun sanitizeImageUrl(url: String): String = ""
                },
            )
            .build()

    fun containsMath(raw: String): Boolean =
        raw.length <= 65536 &&
            (raw.contains("$$") ||
                raw.contains("\\[") ||
                raw.contains("\\(") ||
                Regex("(?<![\\\\$])\\$[^\\s$][^$\\n]{0,2000}\\$").containsMatchIn(raw))

    fun html(raw: String): String {
        require(raw.length <= 65536)
        // Keep TeX intact: Markdown must not consume underscores, asterisks or escaped braces.
        // Tokens contain no Markdown punctuation. Code spans/fences are copied verbatim.
        val prefix = "DSHAMATH" + java.util.UUID.randomUUID().toString().replace("-", "")
        val formulas = linkedMapOf<String, String>()
        val prepared = StringBuilder()
        var i = 0
        fun escaped(at: Int): Boolean {
            var slashes = 0
            var p = at - 1
            while (p >= 0 && raw[p--] == '\\') slashes++
            return slashes % 2 == 1
        }
        while (i < raw.length) {
            if (raw[i] == '`' || (raw[i] == '~' && raw.startsWith("~~~", i))) {
                val c = raw[i]
                var end = i
                while (end < raw.length && raw[end] == c) end++
                val delimiter = raw.substring(i, end)
                val close = raw.indexOf(delimiter, end)
                if (close >= 0) {
                    prepared.append(raw, i, close + delimiter.length)
                    i = close + delimiter.length
                    continue
                }
            }
            val delimiter =
                when {
                    !escaped(i) && raw.startsWith("$$", i) -> "$$" to "$$"
                    !escaped(i) && raw.startsWith("\\[", i) -> "\\[" to "\\]"
                    !escaped(i) && raw.startsWith("\\(", i) -> "\\(" to "\\)"
                    !escaped(i) &&
                        raw[i] == '$' &&
                        i + 1 < raw.length &&
                        !raw[i + 1].isWhitespace() -> "$" to "$"
                    else -> null
                }
            if (delimiter != null) {
                var close = raw.indexOf(delimiter.second, i + delimiter.first.length)
                while (close >= 0 && escaped(close)) close =
                    raw.indexOf(delimiter.second, close + delimiter.second.length)
                if (close >= 0 && (delimiter.first != "$" || '\n' !in raw.substring(i, close))) {
                    val token = prefix + formulas.size + "END"
                    val content = raw.substring(i + delimiter.first.length, close)
                    val display = delimiter.first in setOf("$$", "\\[")
                    formulas[token] =
                        (if (display) "$$" else "\\(") + content + (if (display) "$$" else "\\)")
                    prepared.append(token)
                    i = close + delimiter.second.length
                    continue
                }
            }
            prepared.append(raw[i++])
        }
        val document = parser.parse(prepared.toString())
        document.accept(
            object : AbstractVisitor() {
                override fun visit(image: Image) {
                    // No unsolicited external requests; attachment images retain their native
                    // preview.
                    image.insertBefore(Text("[图片：${image.destination.take(160)}]"))
                    image.unlink()
                }
            },
        )
        var html = renderer.render(document)
        formulas.forEach { (token, formula) ->
            val safe =
                formula
                    .replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;")
            html = html.replace(token, "<span class=\"tex-source\">$safe</span>")
        }
        return html
    }
}
