package dev.deepseek.mobile.terminal

import androidx.compose.runtime.*
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

enum class TerminalLevel(val key: String, val title: String, val detail: String) {
    SANDBOX("sandbox", "Sandbox", "独立 Linux · 项目共享工具与包环境"),
    ISOLATED("isolated", "旧隔离工作区", "旧版文件终端 · 仅用于历史任务兼容"),
    APP("app", "应用用户", "真实 sh · Android 应用 UID"),
    SHELL("shell", "Shizuku Shell", "真实 sh · 需要 Shizuku 的 Shell 授权"),
    ROOT_GUARDED("root_guarded", "受限 Root", "独立命名空间 · 工作区外只读"),
    ROOT("root", "完全 Root", "真实 su · 由 Root 管理器授权");

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key } ?: SANDBOX
    }
}

enum class RuleDecision {
    ALLOW,
    SENSITIVE,
    DENY,
}

data class Inspection(val decision: RuleDecision, val reasons: List<String>, val plan: CommandPlan?)

data class ReviewVerdict(val decision: RuleDecision, val reason: String)

/** A frozen task configuration: edits only affect subsequently submitted tasks. */
data class TerminalSnapshot(val level: String, val rules: String, val reviewer: String) {
    val digest: String
        get() = sha256("$level\n$rules\n$reviewer")

    fun json() = JSONObject().put("level", level).put("rules", rules).put("reviewer", reviewer)

    companion object {
        fun from(json: JSONObject) =
            TerminalSnapshot(
                json.getString("level"),
                json.getString("rules"),
                json.getString("reviewer"),
            )
    }
}

class TerminalRun(
    val id: String = UUID.randomUUID().toString(),
    val taskId: String,
    val sessionId: String,
    val command: String,
    val cwd: String,
    val level: String,
) {
    var status by mutableStateOf("reviewing")
    var output by mutableStateOf("")
    var stderr by mutableStateOf("")
    var reason by mutableStateOf("")
    var exitCode by mutableStateOf<Int?>(null)
    var stage by mutableStateOf("prepare")
    var executionStarted by mutableStateOf<Boolean?>(false)
    var verifiedUid by mutableStateOf<Int?>(null)
    var errorCode by mutableStateOf("")
    var truncated by mutableStateOf(false)
    val createdAt = System.currentTimeMillis()

    fun json() =
        JSONObject()
            .put("id", id)
            .put("taskId", taskId)
            .put("sessionId", sessionId)
            .put("command", command)
            .put("cwd", cwd)
            .put("level", level)
            .put("status", status)
            .put("output", output)
            .put("stderr", stderr)
            .put("reason", reason)
            .put("exitCode", exitCode ?: JSONObject.NULL)
            .put("stage", stage)
            .put("executionStarted", executionStarted ?: JSONObject.NULL)
            .put("verifiedUid", verifiedUid ?: JSONObject.NULL)
            .put("errorCode", errorCode.ifEmpty { null } ?: JSONObject.NULL)
            .put("truncated", truncated)
}

internal fun sha256(text: String) =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") {
        "%02x".format(it)
    }

internal fun shellQuote(text: String) = "'" + text.replace("'", "'\\''") + "'"

internal fun normalizePath(path: String, cwd: String): String {
    require(path.indexOf('\u0000') < 0) { "路径包含 NUL" }
    val parts = mutableListOf<String>()
    (if (path.startsWith('/')) path else "$cwd/$path").split('/').forEach {
        when (it) {
            "",
            "." -> Unit
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
            else -> parts.add(it)
        }
    }
    return "/" + parts.joinToString("/")
}

internal fun within(path: String, parent: String) =
    path == parent || path.startsWith(parent.trimEnd('/') + "/")

internal fun utf8Prefix(text: String, limit: Int): Pair<String, Int> {
    var end = 0
    var bytes = 0
    while (end < text.length) {
        val point = Character.codePointAt(text, end)
        val size =
            when {
                point <= 0x7f -> 1
                point <= 0x7ff -> 2
                point <= 0xffff -> 3
                else -> 4
            }
        if (bytes + size > limit) break
        bytes += size
        end += Character.charCount(point)
    }
    return text.take(end) to bytes
}
