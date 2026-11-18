package dev.deepseek.mobile.workflow

import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Shared, deterministic text operations. No shell, filesystem or model permissions here. */
internal object WorkspaceText {
    fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString(
            "",
        ) {
            "%02x".format(it)
        }

    fun path(value: String, rootAllowed: Boolean = false): String {
        val path = value.removePrefix("/workspace/").let { if (it == "/workspace") "" else it }
        require(
            path.length <= 500 &&
                !path.startsWith('/') &&
                '\\' !in path &&
                ':' !in path &&
                path.none { it.code < 32 },
        ) {
            "请输入工作区内的相对路径"
        }
        if (rootAllowed && path.isEmpty()) return ""
        require(
            path.isNotEmpty() && path.split('/').all { it.isNotEmpty() && it != "." && it != ".." },
        ) {
            "路径不能包含空段、. 或 .."
        }
        return path
    }

    fun checkVersion(text: String?, expected: String?) {
        if (expected == null)
            return // Legacy writes retain their contract; new edit tools require CAS.
        require(expected.matches(Regex("[a-f0-9]{64}"))) { "expected_sha256 必须来自最近一次读取" }
        check(text != null && hash(text) == expected) { "文件已变化，请重新读取后合并修改；没有覆盖文件" }
    }

    fun edit(source: String, expected: String, edits: JSONArray): String {
        checkVersion(source, expected)
        require(edits.length() in 1..20) { "每次需要 1–20 个替换" }
        var next = source
        for (i in 0 until edits.length()) {
            val edit = edits.getJSONObject(i)
            val before = edit.getString("old_text")
            val after = edit.getString("new_text")
            require(before.isNotEmpty()) { "old_text 不能为空；新文件使用 workspace_write" }
            val first = next.indexOf(before)
            require(first >= 0 && next.indexOf(before, first + 1) < 0) {
                "第 ${i + 1} 处旧文本没有唯一匹配，请补充上下文；没有写入文件"
            }
            next = next.substring(0, first) + after + next.substring(first + before.length)
            require(next.toByteArray().size <= 100000) { "编辑后的文件超过 100KB" }
        }
        return next
    }

    fun checkPreparedWrite(original: JSONObject, hooked: JSONObject) {
        if (!original.optBoolean("_exactEdit")) return
        for (key in listOf("name", "text", "expected_sha256", "sandbox_id", "_agentId")) {
            check(original.opt(key) == hooked.opt(key)) {
                "Hook 不能改写已准备的精确编辑参数 $key；本次没有写入文件，请调整 Hook 后重新读取并编辑"
            }
        }
    }

    fun window(result: JSONObject, args: JSONObject): JSONObject {
        val text = result.getString("text")
        result.put("sha256", hash(text))
        if (!args.has("start_line") && !args.has("line_count")) return result
        val lines = text.split('\n')
        val start = args.optInt("start_line", 1)
        val count = args.optInt("line_count", 200)
        require(start >= 1 && count in 1..400) { "start_line 从 1 开始，line_count 为 1–400" }
        val excerpt = lines.drop(start - 1).take(count).joinToString("\n")
        return result
            .put("text", excerpt)
            .put("start_line", start)
            .put("total_lines", lines.size)
            .put("returned_lines", minOf(count, (lines.size - start + 1).coerceAtLeast(0)))
            .put("truncated", start > 1 || start - 1 + count < lines.size)
    }

    fun searchable(name: String): Boolean {
        val lower = name.lowercase()
        return lower !in
            setOf(
                ".git",
                ".gradle",
                ".idea",
                ".android",
                "node_modules",
                "build",
                "dist",
                "vendor",
                ".env",
                "credentials.json",
                "secrets.json",
                "id_rsa",
                "id_ed25519",
            ) &&
            !lower.startsWith(".env.") &&
            !lower.endsWith(".keystore") &&
            !lower.endsWith(".jks") &&
            !lower.endsWith(".pem") &&
            !lower.endsWith(".key")
    }
}
