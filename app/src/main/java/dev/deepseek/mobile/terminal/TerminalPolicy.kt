package dev.deepseek.mobile.terminal

import org.json.JSONArray
import org.json.JSONObject

data class PathUse(val path: String, val permissions: String)

/** The policy engine makes no claim to replace the kernel sandbox for arbitrary programs. */
class TerminalPolicy(text: String) {
    val json = JSONObject(text)
    val timeoutSeconds = json.getInt("timeout_seconds").also { require(it in 1..300) }
    val outputBytes = json.getInt("output_bytes").also { require(it in 1024..262144) }
    private val commands = json.getJSONArray("commands")
    private val paths = json.getJSONArray("paths")
    val hasPathRules: Boolean
        get() = paths.length() > 0

    private val argumentRules = json.getJSONArray("arguments")
    private val network = json.getString("network").also { require(it in effects) }

    init {
        require(text.length <= 65536) { "命令策略最多 64KB" }
        fun keys(value: JSONObject, allowed: Set<String>) {
            require(value.keys().asSequence().all { it in allowed }) { "策略包含未知字段" }
        }
        keys(
            json,
            setOf(
                "version",
                "timeout_seconds",
                "output_bytes",
                "network",
                "commands",
                "paths",
                "arguments",
            ),
        )
        require(json.getInt("version") == 1) { "仅支持命令策略 version=1" }
        require(commands.length() + paths.length() + argumentRules.length() <= 512) { "最多 512 条规则" }
        for (i in 0 until commands.length()) commands.getJSONObject(i).let {
            keys(it, setOf("name", "effect", "reason"))
            require(it.getString("effect") in effects)
            require(it.getString("name").matches(Regex("[a-zA-Z0-9_.+-]+")))
        }
        for (i in 0 until paths.length()) paths.getJSONObject(i).let {
            keys(it, setOf("path", "effect", "permissions", "recursive", "reason"))
            require(it.getString("effect") in effects)
            require(
                it.getString("permissions").isNotEmpty() &&
                    it.getString("permissions").all { c -> c in "rwx" },
            )
            require(
                it.getString("path").startsWith('/') ||
                    it.getString("path").startsWith("\${workspace}"),
            )
        }
        for (i in 0 until argumentRules.length()) argumentRules.getJSONObject(i).let {
            keys(it, setOf("command", "equals", "contains", "effect", "reason"))
            require(it.getString("effect") in effects)
            require(it.has("equals") || it.has("contains"))
        }
    }

    fun inspect(
        command: String,
        cwd: String,
        workspace: String,
        canonicalize: (String) -> String = { it },
    ): Inspection = inspectAtDepth(command, cwd, workspace, 0, canonicalize)

    /**
     * Rich programs are allowed only inside the real VM and without fine-grained path policy. A
     * path restriction falls back to the strict modeled grammar rather than claiming to inspect
     * arbitrary Python, dynamic shell scripts or native binaries.
     */
    fun inspectSandbox(command: String, cwd: String, workspace: String): Inspection =
        inspectSandboxDepth(command, cwd, workspace, 0)

    private fun inspectSandboxDepth(
        command: String,
        cwd: String,
        workspace: String,
        depth: Int,
    ): Inspection {
        if (depth >= 4) return Inspection(RuleDecision.DENY, listOf("嵌套命令超过审查深度"), null)
        if (paths.length() > 0) return inspect(command, cwd, workspace)
        val plan = runCatching { CommandParser.parse(command) }.getOrNull()
        val hasHardRules =
            network == "deny" ||
                (0 until commands.length()).any {
                    commands.getJSONObject(it).getString("effect") == "deny"
                } ||
                (0 until argumentRules.length()).any {
                    argumentRules.getJSONObject(it).getString("effect") == "deny"
                }
        if (plan == null)
            return Inspection(
                if (hasHardRules) RuleDecision.DENY else RuleDecision.SENSITIVE,
                listOf(if (hasHardRules) "存在禁止规则，无法完整审查的脚本必须拆分为字面命令" else "复杂脚本交由独立审查，不推断其文件行为"),
                null,
            )
        var decision = RuleDecision.ALLOW
        val reasons = mutableListOf<String>()
        fun hit(effect: String, reason: String) {
            val next =
                when (effect) {
                    "deny" -> RuleDecision.DENY
                    "sensitive" -> RuleDecision.SENSITIVE
                    else -> RuleDecision.ALLOW
                }
            if (next.ordinal > decision.ordinal) decision = next
            if (next != RuleDecision.ALLOW) reasons += reason
        }
        for (simple in plan.commands) {
            val name = simple.argv.first().substringAfterLast('/')
            for (i in 0 until commands.length()) {
                val rule = commands.getJSONObject(i)
                if (rule.getString("name") == name)
                    hit(rule.getString("effect"), rule.optString("reason", "命中命令规则"))
            }
            for (i in 0 until argumentRules.length()) {
                val rule = argumentRules.getJSONObject(i)
                if (
                    rule.optString("command", name) == name &&
                        simple.argv.drop(1).any {
                            (rule.has("equals") && it == rule.getString("equals")) ||
                                (rule.has("contains") && it.contains(rule.getString("contains")))
                        }
                )
                    hit(rule.getString("effect"), rule.optString("reason", "命中参数规则"))
            }
            if (name in networkCommands) hit(network, "命中联网规则")
            if (
                name in setOf("sh", "bash", "su") &&
                    simple.argv.size >= 3 &&
                    simple.argv[1] in setOf("-c", "-lc")
            ) {
                val nested = inspectSandboxDepth(simple.argv[2], cwd, workspace, depth + 1)
                if (nested.decision.ordinal > decision.ordinal) decision = nested.decision
                reasons += nested.reasons
            } else if (hasHardRules && name !in known) hit("deny", "存在禁止规则，未建模程序无法保证不会间接调用被禁止的命令")
        }
        return Inspection(decision, reasons.distinct(), plan)
    }

    private fun inspectAtDepth(
        command: String,
        cwd: String,
        workspace: String,
        depth: Int,
        canonicalize: (String) -> String,
    ): Inspection {
        if (depth > 3) return Inspection(RuleDecision.DENY, listOf("解释器嵌套超过3层，请拆分命令"), null)
        val reasons = mutableListOf<String>()
        var decision = RuleDecision.ALLOW
        fun hit(effect: String, reason: String) {
            val next =
                when (effect) {
                    "deny" -> RuleDecision.DENY
                    "sensitive" -> RuleDecision.SENSITIVE
                    else -> RuleDecision.ALLOW
                }
            if (next.ordinal > decision.ordinal) decision = next
            if (next != RuleDecision.ALLOW) reasons.add(reason)
        }
        val plan =
            try {
                CommandParser.parse(command)
            } catch (e: Exception) {
                return Inspection(RuleDecision.DENY, listOf(e.message ?: "命令无法解析"), null)
            }
        var current = normalizePath(cwd, workspace)
        for (c in plan.commands) {
            val name = c.argv.first()
            if (name in setOf("sh", "su") && c.argv.size == 3 && c.argv[1] == "-c") {
                val nested = inspectAtDepth(c.argv[2], current, workspace, depth + 1, canonicalize)
                if (nested.decision.ordinal > decision.ordinal) decision = nested.decision
                reasons.addAll(nested.reasons.map { "$name -c：$it" })
            }
            if (name == "ssh-keygen" && "-t" in c.argv) hit("sensitive", "生成 SSH 私钥，需确认目标文件和用户意图")
            if (!validOptions(c)) hit("deny", "$name 包含未建模的选项或参数；请使用明确的基础文件命令")
            // Only the system binaries below have reviewed, known argument semantics.
            // Never let a workspace executable shadow a trusted command name.
            if (name !in known || name.contains('/')) hit("deny", "未建模的命令 $name：审查模式不能确认其文件或执行行为")
            for (i in 0 until commands.length()) commands.getJSONObject(i).let {
                if (it.getString("name") == name)
                    hit(it.getString("effect"), "命令 $name：${it.optString("reason", "命中命令规则")}")
            }
            for (i in 0 until argumentRules.length()) argumentRules.getJSONObject(i).let { rule ->
                if (
                    rule.optString("command", name) == name &&
                        c.argv.drop(1).any {
                            (rule.has("equals") && it == rule.getString("equals")) ||
                                (rule.has("contains") && it.contains(rule.getString("contains")))
                        }
                )
                    hit(rule.getString("effect"), "$name 参数：${rule.optString("reason", "命中参数规则")}")
            }
            if (name in networkCommands) hit(network, "命中联网命令 $name")
            // These options execute programs, load plugins, traverse arbitrary paths or write
            // outside named targets.
            if (
                (name == "find" &&
                    c.argv.any {
                        it.startsWith("-exec") ||
                            it in listOf("-delete", "-ok", "-fprint", "-fprintf")
                    }) ||
                    (name == "sort" &&
                        c.argv.any {
                            it == "-o" || it.startsWith("--output") || it.startsWith("--compress")
                        }) ||
                    (name == "date" &&
                        c.argv.any { it.startsWith("-s") || it.startsWith("--set") }) ||
                    (name == "curl" &&
                        c.argv.any { it != "--" && it.startsWith('-') && it !in curlFlags }) ||
                    (name in listOf("grep", "ls", "cp", "mv", "rm", "chmod") &&
                        c.argv.any { it.startsWith("--") && it !in longFlags }) ||
                    (name == "chmod" &&
                        c.argv.any { it in listOf("--reference", "--preserve-root") })
            ) {
                hit("deny", "$name 包含尚未建模的参数，请拆分为明确的文件命令")
            }
            val uses =
                try {
                    pathUses(c, current)
                        .flatMap { use -> listOf(use, use.copy(path = canonicalize(use.path))) }
                        .distinct()
                } catch (error: Exception) {
                    hit("deny", error.message ?: "无法解析嵌套命令路径")
                    emptyList()
                }
            for (use in uses) {
                for (i in 0 until paths.length()) paths.getJSONObject(i).let {
                    val configured = it.getString("path").replace("\${workspace}", workspace)
                    val parent = canonicalize(normalizePath(configured, workspace))
                    if (
                        (if (it.optBoolean("recursive", true))
                            within(use.path, parent) ||
                                (use.permissions.contains('w') && within(parent, use.path))
                        else use.path == parent) &&
                            use.permissions.any { p -> p in it.getString("permissions") }
                    ) {
                        hit(
                            it.getString("effect"),
                            "${use.permissions} ${use.path}：${it.optString("reason", "命中路径规则")}",
                        )
                    }
                }
            }
            if (name == "cd") {
                if (plan.commands.size != 1)
                    hit("deny", "审查模式请用 terminal_exec 的 cwd 指定目录，避免复合命令中 cd 的条件分支导致路径歧义")
                current = normalizePath(c.argv.getOrElse(1) { workspace }, current)
            }
        }
        return Inspection(decision, reasons.distinct(), plan)
    }

    /**
     * Similar grants deliberately match exact argv, cwd and redirects, never a bare command prefix.
     */
    /**
     * Path-only inspection for native shared-memory operations; never pretend to run a shell
     * command.
     */
    fun inspectPaths(uses: List<PathUse>, workspace: String): Inspection {
        var decision = RuleDecision.ALLOW
        val reasons = mutableListOf<String>()
        for (use in uses) for (i in 0 until paths.length()) {
            val rule = paths.getJSONObject(i)
            val parent =
                normalizePath(rule.getString("path").replace("\${workspace}", workspace), workspace)
            val target = normalizePath(use.path, workspace)
            val matches =
                if (rule.optBoolean("recursive", true)) within(target, parent) else target == parent
            if (matches && use.permissions.any { it in rule.getString("permissions") }) {
                val effect =
                    when (rule.getString("effect")) {
                        "deny" -> RuleDecision.DENY
                        "sensitive" -> RuleDecision.SENSITIVE
                        else -> RuleDecision.ALLOW
                    }
                if (effect.ordinal > decision.ordinal) decision = effect
                if (effect != RuleDecision.ALLOW) reasons.add(rule.optString("reason", "命中记忆路径规则"))
            }
        }
        return Inspection(decision, reasons.distinct(), null)
    }

    fun grantKey(session: String, snapshot: TerminalSnapshot, cwd: String, plan: CommandPlan) =
        sha256("$session\n${snapshot.digest}\n$cwd\n${plan.shell()}")

    companion object {
        private val effects = setOf("allow", "sensitive", "deny")
        val known =
            setOf(
                "pwd",
                "cd",
                "ls",
                "cat",
                "echo",
                "printf",
                "mkdir",
                "touch",
                "cp",
                "mv",
                "rm",
                "head",
                "tail",
                "wc",
                "grep",
                "sort",
                "uniq",
                "cut",
                "tr",
                "date",
                "id",
                "uname",
                "df",
                "du",
                "stat",
                "chmod",
                "find",
                "true",
                "false",
                "sleep",
                "curl",
                "sh",
                "su",
                "which",
                "command",
                "ssh-keygen",
            )
        private val networkCommands = setOf("curl")
        private val curlFlags =
            setOf(
                "-I",
                "--head",
                "-s",
                "--silent",
                "-S",
                "--show-error",
                "--fail",
                "-f",
                "--http1.1",
                "--https-only",
            )
        private val longFlags =
            setOf(
                "--",
                "--recursive",
                "--force",
                "--verbose",
                "--parents",
                "--directory",
                "--all",
                "--human-readable",
                "--no-preserve-root",
            )
        private val shortFlags =
            mapOf(
                "ls" to "alhRrdtS1",
                "cat" to "nbsETv",
                "mkdir" to "pv",
                "touch" to "c",
                "rm" to "rfRvi",
                "cp" to "rfRvip",
                "mv" to "fiv",
                "chmod" to "Rv",
                "head" to "n",
                "tail" to "n",
                "wc" to "lwcmL",
                "grep" to "FinvcrRhH",
                "sort" to "rnuf",
                "uniq" to "cdu",
                "du" to "sah",
                "df" to "h",
                "stat" to "",
                "find" to "",
                "cd" to "",
                "echo" to "n",
                "printf" to "",
                "date" to "",
                "pwd" to "",
                "id" to "ugGnrz",
                "uname" to "asmnrpv",
                "true" to "",
                "false" to "",
                "sleep" to "",
            )

        private fun validOptions(c: SimpleCommand): Boolean {
            val name = c.argv[0]
            val args = c.argv.drop(1)
            if (name in setOf("sh", "su")) return args.size == 2 && args[0] == "-c"
            if (name == "command")
                return args.size == 2 &&
                    args[0] == "-v" &&
                    args[1].matches(Regex("[A-Za-z0-9_.+-]+"))
            if (name == "which")
                return args.isNotEmpty() && args.all { it.matches(Regex("[A-Za-z0-9_.+-]+")) }
            if (name == "ssh-keygen") return validKeygen(args)
            if (name == "curl")
                return args.filterNot { it.startsWith('-') }.all { it.startsWith("https://") } &&
                    args.filter { it.startsWith('-') }.all { it in curlFlags }
            if (name == "find" || name == "cut" || name == "tr") return false
            if (name == "date" && args.any { !it.startsWith('+') }) return false
            var literal = false
            for ((index, arg) in args.withIndex()) {
                if (literal) continue
                if (arg == "--") {
                    literal = true
                    continue
                }
                if (arg.startsWith("--")) {
                    if (arg !in longFlags) return false
                } else if (arg.startsWith('-') && arg != "-") {
                    if (arg.drop(1).any { it !in shortFlags.getOrElse(name) { "" } }) return false
                    if (
                        name in setOf("head", "tail") &&
                            (arg != "-n" || args.getOrNull(index + 1)?.toIntOrNull() == null)
                    )
                        return false
                }
            }
            return true
        }

        private fun validKeygen(args: List<String>): Boolean {
            val values = mutableMapOf<String, String>()
            var index = 0
            while (index < args.size) {
                val key = args[index++]
                if (key in setOf("-q", "-y", "-l")) {
                    if (values.put(key, "") != null) return false
                    continue
                }
                if (
                    key !in setOf("-t", "-f", "-N", "-C") ||
                        index >= args.size ||
                        values.containsKey(key)
                )
                    return false
                values[key] = args[index++]
            }
            if (values["-f"].isNullOrBlank()) return false
            return if (values["-t"] == "ed25519")
                values.containsKey("-N") && "-y" !in values && "-l" !in values
            else
                values.keys.all { it in setOf("-f", "-y", "-l") } &&
                    (values.containsKey("-y") xor values.containsKey("-l"))
        }

        fun pathUses(c: SimpleCommand, cwd: String): List<PathUse> = pathUses(c, cwd, 0)

        private fun pathUses(c: SimpleCommand, cwd: String, depth: Int): List<PathUse> {
            require(depth <= 3) { "解释器嵌套超过3层" }
            val name = c.argv[0]
            var literal = false
            val args =
                c.argv.drop(1).filter { arg ->
                    if (literal) true
                    else if (arg == "--") {
                        literal = true
                        false
                    } else !arg.startsWith('-')
                }
            fun use(path: String, p: String) = PathUse(normalizePath(path, cwd), p)
            val result = mutableListOf<PathUse>()
            if (name !in setOf("cd", "echo", "printf", "pwd", "true", "false", "command"))
                result.add(PathUse("/system/bin/$name", "x"))
            result.add(use(cwd, "rx"))
            when (name) {
                "sh",
                "su" ->
                    if (c.argv.size == 3 && c.argv[1] == "-c") {
                        CommandParser.parse(c.argv[2]).commands.forEach {
                            result.addAll(pathUses(it, cwd, depth + 1))
                        }
                    }
                "ssh-keygen" ->
                    c.argv
                        .indexOf("-f")
                        .takeIf { it >= 0 }
                        ?.let { index ->
                            val path = c.argv.getOrNull(index + 1) ?: return@let
                            val generating = "-t" in c.argv
                            result.add(use(path, if (generating) "w" else "r"))
                            if (generating) result.add(use("$path.pub", "w"))
                        }
                "which",
                "command" -> Unit
                "echo",
                "printf",
                "pwd",
                "id",
                "uname",
                "true",
                "false",
                "sleep",
                "curl",
                "date" -> Unit
                "cp" -> {
                    args.dropLast(1).forEach { result.add(use(it, "r")) }
                    args.lastOrNull()?.let { result.add(use(it, "w")) }
                }
                "mv" -> args.forEach { result.add(use(it, "rw")) }
                "rm",
                "mkdir",
                "touch" -> args.forEach { result.add(use(it, "w")) }
                "chmod" -> args.drop(1).forEach { result.add(use(it, "wx")) }
                "grep" -> args.drop(1).forEach { result.add(use(it, "r")) }
                "cut",
                "tr" -> args.drop(1).forEach { result.add(use(it, "r")) }
                else -> args.forEach { result.add(use(it, "r")) }
            }
            c.redirects.forEach { result.add(use(it.path, if (it.kind == "<") "r" else "w")) }
            val traversal = mutableListOf<PathUse>()
            result.forEach { target ->
                var parent = target.path.substringBeforeLast('/').ifEmpty { "/" }
                while (true) {
                    traversal.add(PathUse(parent, "x"))
                    if (parent == "/") break
                    parent = parent.substringBeforeLast('/').ifEmpty { "/" }
                }
            }
            return (result + traversal).distinct()
        }

        fun sandboxDefaults(): String =
            JSONObject(defaults())
                .put("network", "allow")
                .put("paths", JSONArray())
                .put("arguments", JSONArray())
                .toString(2)

        fun defaults(): String =
            JSONObject()
                .put("version", 1)
                .put("timeout_seconds", 30)
                .put("output_bytes", 65536)
                .put("network", "sensitive")
                .put(
                    "commands",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("name", "rm")
                                .put("effect", "sensitive")
                                .put("reason", "删除文件需要批准"),
                        )
                        .put(
                            JSONObject()
                                .put("name", "chmod")
                                .put("effect", "sensitive")
                                .put("reason", "修改文件权限"),
                        ),
                )
                .put(
                    "paths",
                    JSONArray(
                        listOf(
                                "/system",
                                "/vendor",
                                "/product",
                                "/apex",
                                "/proc",
                                "/sys",
                                "/dev",
                                "/data/adb",
                            )
                            .map {
                                JSONObject()
                                    .put("path", it)
                                    .put("recursive", true)
                                    .put("permissions", "w")
                                    .put("effect", "deny")
                                    .put("reason", "保护系统及 Root 管理器数据")
                            },
                    ),
                )
                .put(
                    "arguments",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("command", "rm")
                                .put("equals", "--no-preserve-root")
                                .put("effect", "deny")
                                .put("reason", "禁止移除根目录保护"),
                        ),
                )
                .toString(2)
    }
}
