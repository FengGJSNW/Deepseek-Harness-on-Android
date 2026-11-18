package dev.deepseek.mobile

import android.graphics.Rect as AndroidRect
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.AnnotatedString

internal object MarkdownSelection {
    const val inline = "dsha.markdown.inline"
    private const val block = "dsha.markdown.block"
    private val backticks = Regex("`+")

    fun block(text: AnnotatedString, kind: String): AnnotatedString =
        AnnotatedString.Builder(text)
            .apply { addStringAnnotation(block, kind, 0, text.length) }
            .toAnnotatedString()

    fun copy(text: AnnotatedString, markdown: Boolean): String {
        val chunks = text.getStringAnnotations(block, 0, text.length).filter { it.start < it.end }
        if (chunks.isEmpty()) return if (markdown) inlineMarkdown(text) else text.text
        val output = mutableListOf<String>()
        var table = ""
        val rows = linkedMapOf<Int, MutableMap<Int, String>>()
        fun flushTable() {
            if (rows.isEmpty()) return
            val columns = rows.values.flatMap { it.keys }.distinct().sorted()
            if (columns.size == 1 && rows.size == 1) output.add(rows.values.first().values.first())
            else if (markdown) {
                fun row(cells: List<String>) = cells.joinToString(" | ", "| ", " |")
                output.add(
                    rows.values
                        .mapIndexed { index, cells ->
                            row(
                                columns.map {
                                    cells[it].orEmpty().replace("|", "\\|").replace("\n", " ")
                                },
                            ) + if (index == 0) "\n" + row(columns.map { "---" }) else ""
                        }
                        .joinToString("\n"),
                )
            } else {
                output.add(
                    rows.values.joinToString("\n") { row ->
                        columns.joinToString("\t") { row[it].orEmpty() }
                    },
                )
            }
            rows.clear()
        }
        var end = 0
        for (chunk in chunks) {
            val gap = text.text.substring(end, chunk.start)
            if (gap.isNotBlank()) {
                flushTable()
                output.add(if (markdown) escape(gap) else gap)
            }
            val selection = text.subSequence(chunk.start, chunk.end)
            val kind = chunk.item
            end = chunk.end
            if (kind.startsWith("cell:")) {
                val (_, path, row, column) = kind.split(':')
                if (path != table) flushTable()
                table = path
                rows.getOrPut(row.toInt()) { linkedMapOf() }[column.toInt()] =
                    if (markdown) inlineMarkdown(selection) else selection.text
                continue
            }
            flushTable()
            output.add(
                if (!markdown) selection.text
                else if (kind.startsWith("code:")) {
                    val fence = "`".repeat(maxOf(3, fenceSize(selection.text)))
                    "$fence${kind.substringAfter(':').replace("`", "")}\n${selection.text}\n$fence"
                } else {
                    val body = inlineMarkdown(selection)
                    if (kind.startsWith("prefix:")) {
                        val prefix = kind.substringAfter(':')
                        val quote = prefix.takeWhile { it == '>' || it == ' ' }
                        prefix + body.replace("\n", "\n$quote")
                    } else body
                },
            )
        }
        flushTable()
        val tail = text.text.substring(end)
        if (tail.isNotBlank()) output.add(if (markdown) escape(tail) else tail)
        return output.joinToString(if (markdown) "\n\n" else "\n")
    }

    private fun escape(text: String) = buildString {
        for (c in text) {
            if (c in "\\`*_[]<>~") append('\\')
            append(c)
        }
    }

    private fun fenceSize(text: String) =
        (backticks.findAll(text).maxOfOrNull { it.value.length } ?: 0) + 1

    private fun inlineMarkdown(selection: AnnotatedString): String {
        val spans =
            selection
                .getStringAnnotations(inline, 0, selection.length)
                .filter { it.start < it.end }
                .sortedWith(compareBy({ it.start }, { -it.end }))
        val source = selection.text
        var next = 0
        fun render(start: Int, end: Int, depth: Int): String = buildString {
            var pos = start
            while (next < spans.size && spans[next].start < end && depth < 64) {
                val span = spans[next++]
                if (span.start < pos || span.end > end) continue
                append(escape(source.substring(pos, span.start)))
                val raw = source.substring(span.start, span.end)
                if (span.item == "code") {
                    val fence = "`".repeat(fenceSize(raw))
                    val padding =
                        if (
                            raw.startsWith('`') ||
                                raw.endsWith('`') ||
                                raw.startsWith(' ') && raw.endsWith(' ') && raw.isNotBlank()
                        )
                            " "
                        else ""
                    append(fence + padding + raw + padding + fence)
                } else {
                    val content = render(span.start, span.end, depth + 1)
                    if (span.item.startsWith("link:")) {
                        val url =
                            span.item.substringAfter(':').replace("<", "%3C").replace(">", "%3E")
                        append("[$content](<$url>)")
                    } else if (raw.isBlank()) append(content)
                    else {
                        // CommonMark emphasis delimiters must be inside leading/trailing spaces.
                        val left = raw.takeWhile(Char::isWhitespace).length
                        val right = raw.takeLastWhile(Char::isWhitespace).length
                        append(
                            raw.take(left) +
                                span.item +
                                content.trim() +
                                span.item +
                                raw.takeLast(right),
                        )
                    }
                }
                pos = span.end
            }
            append(escape(source.substring(pos, end)))
        }
        return render(0, source.length, 0)
    }
}

/** Keep Compose's selection handles and use the platform's floating menu with two copy actions. */
@Composable
internal fun MarkdownSelectionContainer(modifier: Modifier, content: @Composable () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val view = LocalView.current
    val fallback = LocalTextToolbar.current
    val toolbar = remember(view, clipboard, fallback) { MarkdownToolbar(view, clipboard, fallback) }
    DisposableEffect(toolbar) { onDispose { toolbar.hide() } }
    CompositionLocalProvider(
        LocalTextToolbar provides toolbar,
        LocalClipboardManager provides toolbar,
    ) {
        SelectionContainer(modifier, content = content)
    }
}

private class MarkdownToolbar(
    private val view: View,
    private val clipboard: ClipboardManager,
    private val fallback: TextToolbar,
) : TextToolbar, ClipboardManager {
    private var action: ActionMode? = null
    private var anchor = Rect.Zero
    private var copy: (() -> Unit)? = null
    private var format: Boolean? = null
    private var fallbackShown = false

    override val status: TextToolbarStatus
        get() =
            if (action != null || fallbackShown && fallback.status == TextToolbarStatus.Shown)
                TextToolbarStatus.Shown
            else TextToolbarStatus.Hidden

    override fun getText(): AnnotatedString? = clipboard.getText()

    override fun setText(annotatedString: AnnotatedString) {
        val useMarkdown = format
        clipboard.setText(
            if (useMarkdown == null) annotatedString
            else AnnotatedString(MarkdownSelection.copy(annotatedString, useMarkdown)),
        )
    }

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        if (onCopyRequested == null) return
        anchor = rect
        copy = onCopyRequested
        if (action == null) {
            action =
                view.startActionMode(
                    object : ActionMode.Callback2() {
                        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                            menu
                                .add(0, 1, 0, "复制文本")
                                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                            menu
                                .add(0, 2, 1, "复制 Markdown")
                                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                            return true
                        }

                        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean =
                            false

                        override fun onGetContentRect(
                            mode: ActionMode,
                            view: View,
                            outRect: AndroidRect,
                        ) {
                            outRect.set(
                                anchor.left.toInt(),
                                anchor.top.toInt(),
                                anchor.right.toInt(),
                                anchor.bottom.toInt(),
                            )
                        }

                        override fun onActionItemClicked(
                            mode: ActionMode,
                            item: MenuItem,
                        ): Boolean {
                            if (item.itemId !in 1..2) return false
                            copyAs(item.itemId == 2)
                            mode.finish()
                            return true
                        }

                        override fun onDestroyActionMode(mode: ActionMode) {
                            if (action === mode) action = null
                        }
                    },
                    ActionMode.TYPE_FLOATING,
                )
        }
        if (action != null) action?.invalidateContentRect()
        else {
            fallbackShown = true
            fallback.showMenu(rect, { copyAs(false) }, null, null, onSelectAllRequested)
        }
    }

    private fun copyAs(markdown: Boolean) {
        format = markdown
        try {
            copy?.invoke()
        } finally {
            format = null
        }
    }

    override fun hide() {
        action?.finish()
        action = null
        if (fallbackShown) fallback.hide()
        fallbackShown = false
    }
}
