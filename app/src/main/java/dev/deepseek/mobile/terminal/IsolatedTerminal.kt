package dev.deepseek.mobile.terminal

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Capability-based virtual filesystem. It never invokes an OS shell or exposes application secrets.
 */
class IsolatedTerminal(context: Context, sessionId: String) {
    private val root = File(context.filesDir, "terminal/workplace").apply { mkdirs() }.canonicalFile
    val workspace =
        "/dev.deepseek.mobile/workplace/session_" +
            sessionId.also { require(it.matches(Regex("[a-f0-9-]{36}"))) }
    private val home = File(root, "session_$sessionId").apply { mkdirs() }.canonicalFile
    private val virtualRoot = "/dev.deepseek.mobile"
    private val virtualWorkplace = "$virtualRoot/workplace"
    private var cwd = workspace
    private var operations = 0

    private fun resolve(path: String, write: Boolean): File {
        val virtual = normalizePath(path, cwd)
        require(virtual.split('/').size <= 128) { "目录层级过深" }
        require(within(virtual, virtualWorkplace)) { "隔离终端不能访问 $virtual" }
        if (write) require(within(virtual, workspace)) { "当前会话之外的目录只读" }
        val f =
            if (virtual == virtualWorkplace) root
            else File(root, virtual.removePrefix("$virtualWorkplace/"))
        var cursor: File? = f
        while (cursor != null && within(cursor.absolutePath, root.path)) {
            try {
                require(!OsConstants.S_ISLNK(Os.lstat(cursor.path).st_mode)) { "隔离终端不跟随符号链接" }
            } catch (e: android.system.ErrnoException) {
                if (e.errno != OsConstants.ENOENT) throw e
            }
            cursor = cursor.parentFile
        }
        require(within(f.canonicalPath, root.path)) { "路径越出隔离文件系统" }
        return f
    }

    private fun read(path: String): String {
        val f = resolve(path, false)
        require(f.isFile) { "不是文件：$path" }
        require(f.length() <= 1_048_576) { "内置终端最多读取 1MB 文件" }
        return f.inputStream().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (output.size() <= 1_048_576) {
                val n = input.read(buffer, 0, minOf(buffer.size, 1_048_577 - output.size()))
                if (n < 0) break
                output.write(buffer, 0, n)
            }
            require(output.size() <= 1_048_576) { "文件在读取过程中超过 1MB" }
            output.toString("UTF-8")
        }
    }

    private fun combine(paths: List<String>, input: String): String {
        if (paths.isEmpty()) return input
        val output = StringBuilder()
        var bytes = 0
        paths.forEach {
            val text = read(it)
            bytes += text.toByteArray().size
            require(bytes <= 1_048_576) { "单条命令输入最多 1MB" }
            output.append(text)
        }
        return output.toString()
    }

    private fun write(path: String, text: String, append: Boolean = false) {
        val f = resolve(path, true)
        require(f.parentFile?.isDirectory == true) { "父目录不存在" }
        require(text.toByteArray().size + (if (append) f.length() else 0) <= 1_048_576) {
            "内置终端单文件上限为 1MB"
        }
        // The private capability filesystem has no link-creation or external-exec primitive.
        if (append) f.appendText(text) else f.writeText(text)
    }

    suspend fun execute(plan: CommandPlan, onOutput: (String, String) -> Unit): Int {
        var lastCode = 0
        var previous = ";"
        var pipe = ""
        for (command in plan.commands) {
            currentCoroutineContext().ensureActive()
            if ((previous == "&&" && lastCode != 0) || (previous == "||" && lastCode == 0)) {
                previous = command.connector
                continue
            }
            require(++operations <= 32)
            var output = ""
            var error = ""
            try {
                var input = if (previous == "|") pipe else ""
                command.redirects.firstOrNull { it.kind == "<" }?.let { input = read(it.path) }
                output = run(command.argv, input)
                lastCode = if (command.argv[0] == "false") 1 else 0
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = (e.message ?: "执行失败") + "\n"
                lastCode = 1
            }
            command.redirects
                .filter { it.kind != "<" }
                .forEach {
                    try {
                        if (it.kind.startsWith("2")) {
                            write(it.path, error, it.kind.endsWith(">>"))
                            error = ""
                        } else {
                            write(it.path, output, it.kind.endsWith(">>"))
                            output = ""
                        }
                    } catch (e: Exception) {
                        error += (e.message ?: "重定向失败") + "\n"
                        lastCode = 1
                    }
                }
            pipe = output
            onOutput(if (command.connector == "|") "" else pipe, error)
            previous = command.connector
        }
        return lastCode
    }

    private suspend fun run(argv: List<String>, input: String): String {
        val name = argv[0]
        val args = argv.drop(1)
        val files = args.filterNot { it.startsWith('-') }
        return when (name) {
            "pwd" -> "$cwd\n"
            "cd" -> {
                val target = normalizePath(args.firstOrNull() ?: workspace, cwd)
                require(resolve(target, false).isDirectory)
                cwd = target
                ""
            }
            "ls" -> {
                val targets = files.ifEmpty { listOf(cwd) }
                targets.joinToString("\n") { target ->
                    val v = normalizePath(target, cwd)
                    if (v == virtualRoot) "workplace/"
                    else {
                        val f = resolve(v, false)
                        require(f.exists()) { "目录不存在" }
                        if (f.isDirectory)
                            f.listFiles()
                                .orEmpty()
                                .sortedBy { it.name }
                                .joinToString("\n") { it.name + if (it.isDirectory) "/" else "" }
                        else f.name
                    }
                } + "\n"
            }
            "echo" -> args.joinToString(" ") + "\n"
            "printf" -> {
                require(args.size in 1..2 && args[0] in listOf("%s", "%s\\n")) {
                    "内置 printf 支持 %s 和 %s\\n"
                }
                args.getOrElse(1) { "" } + if (args[0].endsWith("\\n")) "\n" else ""
            }
            "cat" -> combine(files, input)
            "mkdir" -> {
                require(files.isNotEmpty())
                files.forEach {
                    val f = resolve(it, true)
                    require(if ("-p" in args) f.isDirectory || f.mkdirs() else f.mkdir()) {
                        "无法创建目录：$it"
                    }
                }
                ""
            }
            "touch" -> {
                require(files.isNotEmpty())
                files.forEach {
                    val f = resolve(it, true)
                    require(f.isFile || f.createNewFile())
                    f.setLastModified(System.currentTimeMillis())
                }
                ""
            }
            "rm" -> {
                require(files.isNotEmpty())
                files.forEach {
                    val f = resolve(it, true)
                    require(f != home) { "不能删除会话工作区本身" }
                    if (f.isDirectory) {
                        require(
                            args.any { a ->
                                a.startsWith('-') && (a.contains('r') || a.contains('R'))
                            },
                        )
                        removeTree(f)
                    } else
                        require(
                            f.delete() || args.any { a -> a.startsWith('-') && a.contains('f') },
                        )
                }
                ""
            }
            "cp",
            "mv" -> {
                require(files.size == 2) { "内置 cp/mv 每次处理一个文件" }
                val source = resolve(files[0], name == "mv")
                require(source.isFile) { "请逐个复制文件" }
                var target = resolve(files[1], true)
                if (target.isDirectory) target = resolve(files[1] + "/" + source.name, true)
                require(source != target)
                write(
                    target.path.removePrefix(root.path).let { virtualWorkplace + it },
                    read(files[0]),
                )
                if (name == "mv") require(source.delete())
                ""
            }
            "head",
            "tail" -> {
                require(args.none { it.startsWith('-') && it != "-n" })
                val count =
                    if ("-n" in args)
                        args.getOrNull(args.indexOf("-n") + 1)?.toIntOrNull()?.coerceIn(0, 1000)
                            ?: error("-n 需要数量")
                    else 10
                val path =
                    if ("-n" in args) args.drop(args.indexOf("-n") + 2).firstOrNull()
                    else files.firstOrNull()
                val lines = (path?.let { read(it) } ?: input).lines()
                (if (name == "head") lines.take(count) else lines.takeLast(count)).joinToString(
                    "\n",
                ) + "\n"
            }
            "wc" -> {
                val data = combine(files, input)
                "${data.count { it == '\n' }} ${data.split(Regex("\\s+")).count { it.isNotEmpty() }} ${data.toByteArray().size}\n"
            }
            "grep" -> {
                require(files.isNotEmpty())
                val data = combine(files.drop(1), input)
                data.lineSequence().filter { it.contains(files[0]) }.take(1000).joinToString("\n") +
                    "\n"
            }
            "sort",
            "uniq" -> {
                val data = combine(files, input)
                (if (name == "sort") data.lines().sorted()
                    else
                        data.lines().fold(mutableListOf<String>()) { acc, s ->
                            if (acc.lastOrNull() != s) acc.add(s)
                            acc
                        })
                    .joinToString("\n")
            }
            "date" -> java.time.Instant.now().toString() + "\n"
            "id" -> "uid=virtual isolated-workspace (not an OS shell)\n"
            "true",
            "false" -> ""
            else ->
                error(
                    "内置隔离终端不支持 $name。支持 pwd/cd/ls/cat/echo/printf/mkdir/touch/cp/mv/rm/head/tail/wc/grep/sort/uniq/date/id/true/false；不能运行 sh、脚本或二进制",
                )
        }
    }

    private suspend fun removeTree(file: File) {
        currentCoroutineContext().ensureActive()
        require(++operations <= 4096) { "一次最多删除 4096 个目录项" }
        val virtual = virtualWorkplace + file.path.removePrefix(root.path)
        val safe = resolve(virtual, true)
        if (safe.isDirectory) safe.listFiles().orEmpty().forEach { removeTree(it) }
        require(safe.delete()) { "无法删除 ${safe.name}" }
    }
}
