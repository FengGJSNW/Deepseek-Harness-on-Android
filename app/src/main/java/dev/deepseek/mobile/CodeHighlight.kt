package dev.deepseek.mobile

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle

/**
 * Bounded, language-aware reading lexer. Palette: locally installed VS Code Light+. This is syntax
 * coloring, not a compiler or semantic diagnostic engine.
 */
internal object CodeHighlight {
    val keyword = Color(0xFFAF00DB)
    val declaration = Color(0xFF0000FF)
    val string = Color(0xFFA31515)
    val comment = Color(0xFF008000)
    val number = Color(0xFF098658)
    val function = Color(0xFF795E26)
    val type = Color(0xFF267F99)
    val property = Color(0xFF0451A5)
    val tag = Color(0xFF800000)

    private fun words(value: String) = value.split(' ').toSet()

    private val aliases =
        mapOf(
            "kt" to "kotlin",
            "js" to "javascript",
            "jsx" to "javascript",
            "ts" to "typescript",
            "tsx" to "typescript",
            "py" to "python",
            "sh" to "bash",
            "shell" to "bash",
            "zsh" to "bash",
            "rs" to "rust",
            "yml" to "yaml",
            "c++" to "cpp",
            "cc" to "cpp",
            "htm" to "html",
            "xml" to "html",
        )
    private val common =
        words(
            "if else for while return break continue try catch finally throw switch case default do in as is new",
        )
    private val declarations =
        words(
            "val var fun function let const class object interface struct enum type def fn import from package namespace public private protected static void int float double boolean bool string char long short byte export extends implements data async await suspend override open abstract final",
        )
    private val languageWords =
        mapOf(
            "kotlin" to
                common +
                    declarations +
                    words(
                        "when null true false this super by constructor init companion internal lateinit sealed out inline reified",
                    ),
            "java" to
                common +
                    declarations +
                    words(
                        "null true false this super throws synchronized native transient volatile instanceof",
                    ),
            "javascript" to
                common +
                    declarations +
                    words("null true false this super undefined typeof instanceof delete yield of"),
            "typescript" to
                common +
                    declarations +
                    words(
                        "null true false this super undefined typeof instanceof keyof readonly declare infer unknown never any",
                    ),
            "python" to
                words(
                    "def class import from return if elif else for while in is not and or pass break continue try except finally raise with as lambda yield async await None True False global nonlocal del assert",
                ),
            "bash" to
                words(
                    "if then else elif fi for in do done while until case esac function select time export local readonly true false",
                ),
            "c" to
                common +
                    declarations +
                    words("typedef union sizeof unsigned signed volatile extern NULL"),
            "cpp" to
                common +
                    declarations +
                    words(
                        "typedef union sizeof unsigned signed volatile extern nullptr template typename virtual constexpr using auto true false",
                    ),
            "rust" to
                common +
                    declarations +
                    words(
                        "impl trait pub use mod mut self Self match move loop crate where unsafe dyn ref Some None true false",
                    ),
            "go" to
                common + declarations + words("func defer go chan range select map nil true false"),
            "swift" to
                common +
                    declarations +
                    words(
                        "func guard defer protocol extension nil true false self Self some associatedtype",
                    ),
            "sql" to
                words(
                    "select from where join left right inner outer on as and or not null true false insert into values update set delete create alter drop table index group by order having limit offset distinct union all asc desc count sum case when then else end primary key references",
                ),
            "json" to words("true false null"),
            "yaml" to words("true false null yes no"),
            "css" to words("important inherit initial unset auto none"),
            "html" to emptySet(),
        )

    fun text(code: String, language: String): AnnotatedString {
        val requested = language.trim().substringBefore(' ').lowercase(java.util.Locale.ROOT)
        val lang = aliases[requested] ?: requested
        if (code.length > 65536) return AnnotatedString(code)
        if (lang == "diff" || lang == "patch") {
            val spans = mutableListOf<AnnotatedString.Range<SpanStyle>>()
            var start = 0
            code.splitToSequence('\n').forEach { line ->
                val color =
                    when {
                        line.startsWith("+") -> number
                        line.startsWith("-") -> string
                        line.startsWith("@@") -> property
                        else -> null
                    }
                if (color != null)
                    spans +=
                        AnnotatedString.Range(SpanStyle(color = color), start, start + line.length)
                start += line.length + 1
            }
            return AnnotatedString(code, spans)
        }
        val keywords = languageWords[lang] ?: return AnnotatedString(code)
        val hashComments = lang in setOf("python", "bash", "yaml")
        val slashComments =
            lang in
                setOf(
                    "kotlin",
                    "java",
                    "javascript",
                    "typescript",
                    "c",
                    "cpp",
                    "rust",
                    "go",
                    "swift",
                )
        val blockComments = slashComments || lang in setOf("css", "sql")
        val spans = mutableListOf<AnnotatedString.Range<SpanStyle>>()
        fun nextNonSpace(at: Int): Int {
            var pos = at
            while (pos < code.length && code[pos].isWhitespace()) pos++
            return pos
        }
        var i = 0
        var insideTag = false
        var tagName = false
        while (i < code.length) {
            val start = i
            val c = code[i]
            val color: Color? =
                when {
                    (hashComments && c == '#') ||
                        (slashComments && code.startsWith("//", i)) ||
                        (lang == "sql" && code.startsWith("--", i)) -> {
                        i = code.indexOf('\n', i).let { if (it < 0) code.length else it }
                        comment
                    }
                    blockComments && code.startsWith("/*", i) -> {
                        i = code.indexOf("*/", i + 2).let { if (it < 0) code.length else it + 2 }
                        comment
                    }
                    lang == "html" && code.startsWith("<!--", i) -> {
                        i = code.indexOf("-->", i + 4).let { if (it < 0) code.length else it + 3 }
                        comment
                    }
                    lang == "html" && c == '<' -> {
                        insideTag = true
                        tagName = true
                        i++
                        tag
                    }
                    lang == "html" && c == '>' -> {
                        insideTag = false
                        i++
                        tag
                    }
                    c == '\'' ||
                        c == '"' ||
                        (c == '`' && lang in setOf("javascript", "typescript", "go", "bash")) -> {
                        val triple =
                            lang in setOf("python", "kotlin", "swift") &&
                                code.startsWith("$c$c$c", i)
                        val delimiter = if (triple) "$c$c$c" else "$c"
                        i += delimiter.length
                        while (i < code.length) {
                            if (code[i] == '\\') i = minOf(code.length, i + 2)
                            else if (code.startsWith(delimiter, i)) {
                                i += delimiter.length
                                break
                            } else i++
                        }
                        if (lang in setOf("json", "yaml") && code.getOrNull(nextNonSpace(i)) == ':')
                            property
                        else string
                    }
                    c == '$' && lang == "bash" -> {
                        i++
                        if (code.getOrNull(i) == '{') {
                            i = code.indexOf('}', i + 1).let { if (it < 0) code.length else it + 1 }
                        } else
                            while (
                                i < code.length && (code[i].isLetterOrDigit() || code[i] == '_')
                            ) i++
                        property
                    }
                    c.isDigit() && (lang != "html" || insideTag) -> {
                        i++
                        while (
                            i < code.length &&
                                (code[i].isLetterOrDigit() ||
                                    code[i] in "._" ||
                                    (code[i] in "+-" && code[i - 1] in "eE"))
                        ) i++
                        number
                    }
                    c.isLetter() || c == '_' || (c == '-' && lang == "css") -> {
                        i++
                        while (
                            i < code.length &&
                                (code[i].isLetterOrDigit() ||
                                    code[i] == '_' ||
                                    (code[i] == '-' && lang in setOf("css", "html", "yaml")))
                        ) i++
                        val word = code.substring(start, i)
                        val normalized =
                            if (lang == "sql") word.lowercase(java.util.Locale.ROOT) else word
                        val next = code.getOrNull(nextNonSpace(i))
                        when {
                            lang == "html" ->
                                if (!insideTag) null
                                else if (tagName) {
                                    tagName = false
                                    tag
                                } else property
                            lang in setOf("css", "yaml") && next == ':' -> property
                            normalized in keywords ->
                                if (normalized in declarations) declaration else keyword
                            next == '(' && lang !in setOf("json", "yaml", "css") -> function
                            word.first().isUpperCase() &&
                                lang !in setOf("json", "yaml", "bash", "css", "sql") -> type
                            else -> null
                        }
                    }
                    else -> {
                        i++
                        null
                    }
                }
            if (color != null) spans += AnnotatedString.Range(SpanStyle(color = color), start, i)
        }
        return AnnotatedString(code, spans)
    }

    /** The same tokens serve WebView messages; source is always escaped before markup is added. */
    fun html(code: String, language: String): String {
        val highlighted = text(code, language)
        fun escape(value: String) =
            value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
        return buildString {
            var cursor = 0
            highlighted.spanStyles.forEach { range ->
                append(escape(code.substring(cursor, range.start)))
                val hex = (range.item.color.toArgb() and 0xffffff).toString(16).padStart(6, '0')
                append("<span style=\"color:#$hex\">")
                append(escape(code.substring(range.start, range.end)))
                append("</span>")
                cursor = range.end
            }
            append(escape(code.substring(cursor)))
        }
    }
}
