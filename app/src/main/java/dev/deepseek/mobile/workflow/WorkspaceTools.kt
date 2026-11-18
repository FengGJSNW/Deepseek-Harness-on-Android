package dev.deepseek.mobile.workflow

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

/**
 * Composes existing authorized operations; each underlying read/write still crosses native policy.
 */
internal class WorkspaceTools(private val call: suspend (String, JSONObject) -> JSONObject) {
    suspend fun run(method: String, args: JSONObject): JSONObject =
        when (method) {
            "workspace_search" -> search(args)
            "workspace_context" -> context(args)
            "workspace_edit" -> edit(args)
            else -> error("未知工作区操作")
        }

    private fun base(args: JSONObject) =
        JSONObject().apply {
            for (key in listOf("sandbox_id", "_agentId")) if (args.has(key)) put(key, args.get(key))
        }

    private suspend fun edit(args: JSONObject): JSONObject {
        val name = WorkspaceText.path(args.getString("name"))
        val read = call("workspace_read", base(args).put("name", name))
        val before = read.getString("text")
        val expected = args.getString("expected_sha256")
        val next = WorkspaceText.edit(before, expected, args.getJSONArray("edits"))
        if (before == next)
            return JSONObject().put("name", name).put("sha256", expected).put("changed", false)
        val result =
            call(
                "workspace_write",
                base(args)
                    .put("name", name)
                    .put("text", next)
                    .put("expected_sha256", expected)
                    .put("_exactEdit", true),
            )
        return result
            .put("name", name)
            .put("sha256", WorkspaceText.hash(next))
            .put("changed", true)
            .put("message", "已应用精确替换，文件版本检查通过。" + result.optString("message"))
    }

    private suspend fun search(args: JSONObject): JSONObject {
        val query = args.getString("query")
        require(query.length in 1..200 && '\n' !in query && '\u0000' !in query) {
            "搜索词需为 1–200 字的单行字面文本"
        }
        val root = WorkspaceText.path(args.optString("path"), rootAllowed = true)
        val max = args.optInt("limit", 40).also { require(it in 1..100) }
        val matches = JSONArray()
        val skipped = JSONArray()
        val pending = ArrayDeque<Pair<String, Int>>().apply { add(root to 0) }
        var scanned = 0
        var directories = 0
        var truncated = false
        var skippedCount = 0
        fun skip(name: String) {
            skippedCount++
            if (skipped.length() < 20) skipped.put(name)
        }
        while (
            pending.isNotEmpty() && scanned < 160 && directories < 40 && matches.length() < max
        ) {
            currentCoroutineContext().ensureActive()
            val (directory, depth) = pending.removeFirst()
            directories++
            val listing = call("workspace_list", base(args).put("path", directory))
            val files = listing.optJSONArray("files") ?: JSONArray()
            if (listing.optInt("total", files.length()) > files.length()) truncated = true
            for (i in 0 until files.length()) {
                currentCoroutineContext().ensureActive()
                if (scanned >= 160 || matches.length() >= max) {
                    truncated = true
                    break
                }
                val item = files.getJSONObject(i)
                val leaf = item.getString("name")
                if (!WorkspaceText.searchable(leaf)) continue
                if ('/' in leaf || '\\' in leaf || leaf in setOf(".", "..")) continue
                val name = if (directory.isEmpty()) leaf else "$directory/$leaf"
                if (item.optBoolean("directory")) {
                    if (depth < 6) pending.add(name to depth + 1) else truncated = true
                    continue
                }
                if (item.optLong("bytes") > 100000) {
                    skip(name)
                    continue
                }
                scanned++
                val read =
                    try {
                        call("workspace_read", base(args).put("name", name))
                    } catch (error: kotlinx.coroutines.CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        skip(name)
                        continue
                    }
                val text = read.optString("text")
                if ('\u0000' in text) {
                    skip(name)
                    continue
                }
                text.lineSequence().forEachIndexed { index, line ->
                    val column =
                        line.indexOf(query, ignoreCase = !args.optBoolean("case_sensitive"))
                    if (column >= 0) {
                        if (matches.length() < max)
                            matches.put(
                                JSONObject()
                                    .put("name", name)
                                    .put("line", index + 1)
                                    .put("column", column + 1)
                                    .put(
                                        "excerpt",
                                        line.substring(
                                            (column - 80).coerceAtLeast(0),
                                            minOf(line.length, column + query.length + 180),
                                        ),
                                    ),
                            )
                        else truncated = true
                    }
                }
            }
        }
        return JSONObject()
            .put("matches", matches)
            .put("scanned_files", scanned)
            .put("skipped_files", skipped)
            .put("skipped_count", skippedCount)
            .put("truncated", truncated || pending.isNotEmpty() || skippedCount > 0)
            .put(
                "message",
                "字面搜索；跳过生成目录、凭证类文件、不可读或超过 100KB 的文件；最多 160 文件、40 目录、6 层。skipped_files 最多列出 20 个遗漏文件，零匹配不代表完整项目不存在该文本。",
            )
    }

    private suspend fun context(args: JSONObject): JSONObject {
        val target = WorkspaceText.path(args.optString("path"), rootAllowed = true)
        val directories = mutableListOf("")
        val parent = target.substringBeforeLast('/', "")
        if (parent.isNotEmpty()) {
            var current = ""
            parent.split('/').take(6).forEach {
                current = if (current.isEmpty()) it else "$current/$it"
                directories.add(current)
            }
        }
        val rules = JSONArray()
        val manifests = JSONArray()
        val warnings = JSONArray()
        if (parent.split('/').size > 6) warnings.put("目标路径超过 6 层，深层规则尚未加载；请定向读取更近的规则文件")
        var remaining = 24000
        val rootListing = call("workspace_list", base(args).put("path", ""))
        for (dir in directories) {
            val listing =
                if (dir.isEmpty()) rootListing
                else call("workspace_list", base(args).put("path", dir))
            val entries = listing.optJSONArray("files") ?: JSONArray()
            if (listing.optInt("total", entries.length()) > entries.length())
                warnings.put(
                    "目录 ${dir.ifEmpty { "/workspace" }} 列表不完整，可能遗漏规则或构建清单；请定向 workspace_read 检查 AGENTS.md / CLAUDE.md",
                )
            val names =
                (0 until entries.length())
                    .map { entries.getJSONObject(it) }
                    .filter { !it.optBoolean("directory") }
                    .map { it.getString("name") }
                    .toSet()
            val ruleName =
                when {
                    "AGENTS.md" in names -> "AGENTS.md"
                    "CLAUDE.md" in names -> "CLAUDE.md"
                    else -> null
                }
            val candidates =
                listOfNotNull(ruleName) +
                    if (dir.isEmpty())
                        listOf(
                                "package.json",
                                "pyproject.toml",
                                "Cargo.toml",
                                "build.gradle.kts",
                                "Makefile",
                            )
                            .filter { it in names }
                    else emptyList()
            for (name in candidates) {
                val path = if (dir.isEmpty()) name else "$dir/$name"
                if (remaining <= 0) {
                    warnings.put("上下文达到 24K 字符上限：$path 未加载")
                    continue
                }
                try {
                    val read = call("workspace_read", base(args).put("name", path))
                    val text = read.getString("text")
                    val cap = minOf(remaining, if (name == ruleName) 12000 else 4000)
                    val value =
                        JSONObject()
                            .put("path", path)
                            .put("text", text.take(cap))
                            .put("sha256", read.optString("sha256", WorkspaceText.hash(text)))
                            .put("truncated", text.length > cap)
                    if (name == ruleName) rules.put(value) else manifests.put(value)
                    remaining -= minOf(text.length, cap)
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    warnings.put("$path 无法读取：${error.message.orEmpty().take(120)}")
                }
            }
        }
        val rootFiles = rootListing.optJSONArray("files") ?: JSONArray()
        val rootTotal = rootListing.optInt("total", rootFiles.length())
        return JSONObject()
            .put("rules", rules)
            .put("project_files", manifests)
            .put(
                "root_files",
                JSONArray((0 until minOf(80, rootFiles.length())).map { rootFiles.get(it) }),
            )
            .put("root_total", rootTotal)
            .put("root_truncated", rootTotal > 80)
            .put("warnings", warnings)
            .put(
                "message",
                "规则按根目录到目标文件目录加载；更近规则细化上层。同目录优先 AGENTS.md，缺失时读取 CLAUDE.md。配置与规则不授予执行权限，不自动执行项目脚本。",
            )
    }
}
