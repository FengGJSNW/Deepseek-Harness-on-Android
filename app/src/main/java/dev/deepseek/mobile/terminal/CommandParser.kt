package dev.deepseek.mobile.terminal

data class Redirect(val kind: String, val path: String)

data class SimpleCommand(
    val argv: List<String>,
    val redirects: List<Redirect>,
    val connector: String,
)

data class CommandPlan(val commands: List<SimpleCommand>) {
    /** Render argv again: reviewed execution never returns to the untrusted original shell text. */
    fun shell(): String =
        commands
            .mapIndexed { i, c ->
                (if (i == 0) "" else " ${commands[i - 1].connector} ") +
                    c.argv.joinToString(" ") { shellQuote(it) } +
                    c.redirects.joinToString("") { " ${it.kind} ${shellQuote(it.path)}" }
            }
            .joinToString("")
}

/** Deliberately bounded shell grammar. Expansions, scripts and background jobs fail closed. */
object CommandParser {
    private data class Token(val value: String, val operator: Boolean = false)

    private val operators = listOf("2>>", "2>", "&&", "||", ">>", ";", "|", ">", "<")

    fun parse(text: String): CommandPlan {
        require(text.isNotBlank() && text.length <= 16_384) { "命令为空或超过 16KB" }
        require(!text.contains('\u0000')) { "命令包含 NUL" }
        val tokens = mutableListOf<Token>()
        var word = StringBuilder()
        var started = false
        var quote: Char? = null
        var i = 0
        fun flush() {
            if (started) {
                tokens.add(Token(word.toString()))
                word = StringBuilder()
                started = false
            }
        }
        while (i < text.length) {
            val c = text[i]
            if (quote == '\'') {
                if (c == '\'') quote = null else word.append(c)
                i++
                continue
            }
            if (c == '$' || c == '`') error("审查模式不支持变量或命令替换，请提供展开后的字面参数")
            if (quote == '"') {
                if (c == '"') quote = null
                else if (c == '\\') {
                    require(i + 1 < text.length)
                    if (text[i + 1] in "\"\\$`\n") {
                        val next = text[++i]
                        if (next != '\n') word.append(next)
                    } else word.append(c)
                } else word.append(c)
                i++
                continue
            }
            when {
                c == '\'' || c == '"' -> {
                    quote = c
                    started = true
                    i++
                }
                c == '\\' -> {
                    require(i + 1 < text.length) { "不完整的转义" }
                    word.append(text[++i])
                    started = true
                    i++
                }
                c == '\n' -> {
                    flush()
                    if (tokens.isNotEmpty() && !tokens.last().operator) tokens.add(Token(";", true))
                    i++
                }
                c.isWhitespace() -> {
                    flush()
                    i++
                }
                c == '*' || c == '?' || c == '[' || c == '~' -> error("请使用明确路径，不支持未展开的通配符或波浪号")
                operators.any { text.startsWith(it, i) && (!it.startsWith('2') || !started) } -> {
                    val op = operators.first {
                        text.startsWith(it, i) && (!it.startsWith('2') || !started)
                    }
                    // A numeric fd prefix belongs to the redirection, not to argv.
                    flush()
                    tokens.add(Token(op, true))
                    i += op.length
                }
                c in "(){}&" -> error("审查模式不支持子 Shell、函数、花括号或后台任务")
                c == '#' && !started -> {
                    while (i < text.length && text[i] != '\n') i++
                }
                else -> {
                    word.append(c)
                    started = true
                    i++
                }
            }
        }
        require(quote == null) { "引号未闭合" }
        flush()
        val result = mutableListOf<SimpleCommand>()
        var argv = mutableListOf<String>()
        var redirects = mutableListOf<Redirect>()
        i = 0
        while (i < tokens.size) {
            val token = tokens[i++]
            if (!token.operator) {
                argv.add(token.value)
                continue
            }
            if (token.value in listOf(">", ">>", "2>", "2>>", "<")) {
                require(i < tokens.size && !tokens[i].operator) { "重定向缺少文件路径" }
                redirects.add(Redirect(token.value, tokens[i++].value))
            } else {
                require(argv.isNotEmpty()) { "连接符前缺少命令" }
                result.add(SimpleCommand(argv, redirects, token.value))
                argv = mutableListOf()
                redirects = mutableListOf()
            }
        }
        if (argv.isNotEmpty()) result.add(SimpleCommand(argv, redirects, ";"))
        else require(result.isNotEmpty() && result.last().connector == ";") { "连接符后缺少命令" }
        require(result.size <= 32) { "一次最多执行 32 条子命令" }
        return CommandPlan(result)
    }
}
