package dev.deepseek.mobile

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

data class MemorySnapshot(
    val scope: String,
    val projectId: String,
    val text: String,
    val sha256: String,
) {
    val filename
        get() =
            when (scope) {
                "global" -> "memory.md"
                "persona" -> "persona_memory.md"
                "session" -> "session_memory.md"
                else -> "workplace_memory.md"
            }

    val logicalPath
        get() =
            if (scope == "global") "/dev.deepseek.mobile/memory.md"
            else if (scope == "persona") "/dev.deepseek.mobile/persona_memory.md"
            else if (scope == "session")
                "/dev.deepseek.mobile/sessions/$projectId/session_memory.md"
            else "/dev.deepseek.mobile/projects/$projectId/workplace_memory.md"

    fun json() =
        JSONObject()
            .put("scope", scope)
            .put("name", filename)
            .put("text", text)
            .put("sha256", sha256)
            .put("path", logicalPath)
}

data class MemoryProposal(val taskId: String, val before: MemorySnapshot, val after: String)

/**
 * Shared Markdown files, bounded and atomic. CAS prevents one conversation erasing another's edit.
 */
class MemoryStore(context: Context) {
    private val root = context.filesDir.canonicalFile

    companion object {
        const val MAX_BYTES = 32768
        private val lock = Any()

        fun hash(text: String) =
            MessageDigest.getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }

    private fun file(scope: String, projectId: String): File {
        require(scope in setOf("global", "project", "session", "persona")) { "记忆范围无效" }
        val target =
            if (scope == "global") File(root, "memory.md")
            else if (scope == "persona") File(root, "persona_memory.md")
            else {
                require(projectId.matches(Regex("[a-f0-9-]{36}"))) { "项目 ID 无效" }
                File(
                    root,
                    if (scope == "session") "sessions/$projectId/session_memory.md"
                    else "projects/$projectId/workplace_memory.md",
                )
            }
        require(target.canonicalFile == target.absoluteFile) { "记忆路径不可含符号链接" }
        target.parentFile!!.mkdirs()
        return target
    }

    fun read(scope: String, projectId: String = ""): MemorySnapshot =
        synchronized(lock) {
            val file = file(scope, projectId)
            if (!file.exists()) writeAtomic(file, "")
            require(file.length() <= MAX_BYTES) { "记忆文件超过 32KB，请在编辑器中缩减" }
            val text = String(AtomicFile(file).readFully(), Charsets.UTF_8)
            MemorySnapshot(
                scope,
                if (scope in setOf("project", "session")) projectId else "",
                text,
                hash(text),
            )
        }

    fun write(
        scope: String,
        projectId: String,
        text: String,
        expectedSha256: String,
    ): MemorySnapshot =
        synchronized(lock) {
            require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "记忆最多 32KB" }
            require(!text.contains('\u0000')) { "记忆不能含 NUL 字符" }
            val current = read(scope, projectId)
            check(current.sha256 == expectedSha256) { "记忆已被其他会话更新，请重新读取后合并，避免覆盖" }
            writeAtomic(file(scope, projectId), text)
            read(scope, projectId)
        }

    private fun writeAtomic(target: File, text: String) {
        val file = AtomicFile(target)
        val out = file.startWrite()
        try {
            out.write(text.toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (error: Exception) {
            file.failWrite(out)
            throw error
        }
    }
}
