package dev.deepseek.mobile.harness

import android.content.Context
import android.util.AtomicFile
import dev.deepseek.mobile.*
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Instructions are user-owned; memories are facts; session summaries are retrievable episodes. */
internal class ContextManager(
    private val context: Context,
    private val memories: MemoryStore,
    private val permissions: PermissionManager,
) {
    private val root = File(context.filesDir, "harness/context").apply { mkdirs() }
    private val lock = Any()

    init {
        proposals()
            .let { values -> (0 until values.length()).map { values.getJSONObject(it) } }
            .filter { it.optString("status") == "pending" }
            .forEach { settleProposal(it.getString("id"), "interrupted") }
    }

    private fun instructionFile(scope: String, project: String): File {
        require(scope in setOf("global", "project", "personality"))
        if (scope == "project") UUID.fromString(project)
        return File(
            root,
            if (scope == "project") "instructions/project-$project.md"
            else "instructions/$scope.md",
        )
    }

    fun instruction(scope: String, project: String = ""): JSONObject =
        synchronized(lock) {
            val file = instructionFile(scope, project)
            val text =
                if (file.exists()) String(AtomicFile(file).readFully(), Charsets.UTF_8) else ""
            require(text.toByteArray().size <= 32768)
            JSONObject()
                .put("scope", scope)
                .put("text", text)
                .put("sha256", MemoryStore.hash(text))
                .put("agentWritable", false)
        }

    /** Called only by the user editor. No Agent/MCP/Hook API writes these files. */
    fun saveUserInstruction(scope: String, project: String, text: String, expectedHash: String) =
        synchronized(lock) {
            require(text.toByteArray().size <= 32768 && !text.contains('\u0000'))
            check(instruction(scope, project).getString("sha256") == expectedHash) {
                "指令已更新，请重新读取后合并"
            }
            write(instructionFile(scope, project), text.toByteArray())
            instruction(scope, project)
        }

    private fun scopes(task: PhoneTask) =
        listOf("global" to "", "persona" to "", "session" to task.sessionId) +
            if (task.projectId.isNotBlank()) listOf("project" to task.projectId) else emptyList()

    fun startup(task: PhoneTask): JSONObject =
        synchronized(lock) {
            val result = JSONObject()
            scopes(task).forEach { (scope, id) ->
                if (
                    permissions
                        .evaluate(
                            PermissionContext(
                                sessionId = task.sessionId,
                                projectId = task.projectId,
                            ),
                            Capability.MEMORY_READ,
                            if (scope in setOf("project", "session")) "$scope:$id" else scope,
                        )
                        .effect != PermissionEffect.ALLOW
                )
                    return@forEach
                val memory = memories.read(scope, id)
                val lines =
                    memory.text
                        .lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotBlank() && !it.startsWith("```") }
                        .take(8)
                        .toList()
                result.put(
                    scope,
                    JSONObject()
                        .put("scope", scope)
                        .put("name", memory.filename)
                        .put("summary", lines.joinToString("\n").take(480))
                        .put("totalBytes", memory.text.toByteArray().size)
                        .put("sha256", memory.sha256)
                        .put(
                            "detailAvailable",
                            memory.text.trim() != lines.joinToString("\n").take(480),
                        ),
                )
            }
            val instructions = JSONArray()
            for (name in
                listOf("personality", "global") +
                    if (task.projectId.isNotBlank()) listOf("project") else emptyList()) {
                if (
                    permissions
                        .evaluate(
                            PermissionContext(
                                sessionId = task.sessionId,
                                projectId = task.projectId,
                            ),
                            Capability.INSTRUCTIONS_READ,
                            name,
                        )
                        .effect == PermissionEffect.ALLOW
                )
                    instructions.put(instruction(name, task.projectId))
            }
            result
                .put("instructions", instructions)
                .put(
                    "sessionSummary",
                    if (
                        permissions
                            .evaluate(
                                PermissionContext(
                                    sessionId = task.sessionId,
                                    projectId = task.projectId,
                                ),
                                Capability.MEMORY_READ,
                                "session:${task.sessionId}",
                            )
                            .effect == PermissionEffect.ALLOW
                    )
                        sessionSummary(task.sessionId)
                    else JSONObject(),
                )
                .put(
                    "relatedSessions",
                    if (task.text.isBlank()) JSONArray()
                    else searchSessions(task, task.text.take(256), false, 4),
                )
        }

    fun searchMemory(task: PhoneTask, query: String, limit: Int = 8): JSONArray {
        require(query.length in 1..256)
        val matches = mutableListOf<Pair<Int, JSONObject>>()
        scopes(task).forEach { (scope, id) ->
            if (
                permissions
                    .evaluate(
                        PermissionContext(sessionId = task.sessionId, projectId = task.projectId),
                        Capability.MEMORY_READ,
                        if (scope in setOf("project", "session")) "$scope:$id" else scope,
                    )
                    .effect != PermissionEffect.ALLOW
            )
                return@forEach
            val memory = memories.read(scope, id)
            val lines = memory.text.lines()
            lines.forEachIndexed { index, line ->
                val score = score(query, line)
                if (score > 0)
                    matches +=
                        score to
                            JSONObject()
                                .put("scope", scope)
                                .put("line", index + 1)
                                .put(
                                    "text",
                                    lines
                                        .subList(
                                            (index - 1).coerceAtLeast(0),
                                            (index + 3).coerceAtMost(lines.size),
                                        )
                                        .joinToString("\n")
                                        .take(1600),
                                )
                                .put("sha256", memory.sha256)
            }
        }
        return JSONArray(
            matches.sortedByDescending { it.first }.take(limit.coerceIn(1, 20)).map { it.second },
        )
    }

    private fun summaryFile(id: String): File {
        UUID.fromString(id)
        return File(root, "summaries/$id.json")
    }

    fun sessionSummary(id: String): JSONObject =
        synchronized(lock) {
            val file = summaryFile(id)
            if (file.isFile) JSONObject(String(AtomicFile(file).readFully()))
            else {
                val conversation = AssistantState.sessions.firstOrNull { it.id == id }
                JSONObject()
                    .put("sessionId", id)
                    .put("projectId", conversation?.projectId.orEmpty())
                    .put("title", conversation?.title.orEmpty())
                    .put("summary", "")
            }
        }

    fun complete(task: PhoneTask, status: String) =
        synchronized(lock) {
            val conversation =
                AssistantState.sessions.firstOrNull { it.id == task.sessionId }
                    ?: return@synchronized
            val answer =
                conversation.messages
                    .lastOrNull {
                        it.taskId == task.id && it.type in setOf("assistant", "stream_assistant")
                    }
                    ?.text
                    .orEmpty()
            // Episode summary only. Raw tool logs and transient output never enter long-term
            // memory.
            val summary =
                JSONObject()
                    .put("sessionId", task.sessionId)
                    .put("projectId", task.projectId)
                    .put("title", conversation.title)
                    .put("goal", task.text.take(240))
                    .put("summary", answer.take(900))
                    .put("status", status)
                    .put("updatedAt", System.currentTimeMillis())
            write(summaryFile(task.sessionId), summary.toString().toByteArray())
        }

    fun sameProject(task: PhoneTask, other: Conversation) =
        other.id == task.sessionId ||
            (task.projectId.isNotBlank() && other.projectId == task.projectId)

    fun searchSessions(
        task: PhoneTask,
        query: String,
        includeOther: Boolean,
        limit: Int = 8,
    ): JSONArray {
        require(query.length <= 256)
        val candidates =
            AssistantState.sessions.filter {
                it.id != task.sessionId &&
                    (includeOther || sameProject(task, it)) &&
                    permissions
                        .evaluate(
                            PermissionContext(
                                sessionId = task.sessionId,
                                projectId = task.projectId,
                            ),
                            Capability.MEMORY_READ,
                            "session:${it.id}",
                        )
                        .effect == PermissionEffect.ALLOW
            }
        val results =
            candidates
                .map { conversation ->
                    val summary = sessionSummary(conversation.id)
                    val text =
                        summary.optString("title") +
                            " " +
                            summary.optString("goal") +
                            " " +
                            summary.optString("summary")
                    score(query, text) to summary
                }
                .filter { it.first > 0 || query.isBlank() }
                .sortedByDescending { it.first }
                .take(limit.coerceIn(1, 20))
        return JSONArray(results.map { it.second })
    }

    fun readSession(id: String, offset: Int, limit: Int): JSONObject {
        val conversation = AssistantState.sessions.firstOrNull { it.id == id } ?: error("会话不存在")
        val entries =
            conversation.messages.filter { it.type in setOf("user", "assistant", "system") }
        val text = entries.joinToString("\n\n") { "${it.type}: ${it.text}" }
        val start = offset.coerceIn(0, text.length)
        return sessionSummary(id)
            .put("text", text.drop(start).take(limit.coerceIn(100, 12000)))
            .put("offset", start)
            .put("totalChars", text.length)
    }

    fun recordProposal(task: PhoneTask, before: MemorySnapshot, after: String): String =
        synchronized(lock) {
            val id = UUID.randomUUID().toString()
            write(
                File(root, "proposals/$id.json"),
                JSONObject()
                    .put("id", id)
                    .put("taskId", task.id)
                    .put("sessionId", task.sessionId)
                    .put("projectId", task.projectId)
                    .put("scope", before.scope)
                    .put("expectedSha256", before.sha256)
                    .put("before", before.text)
                    .put("after", after)
                    .put("status", "pending")
                    .put("createdAt", System.currentTimeMillis())
                    .toString()
                    .toByteArray(),
            )
            id
        }

    fun settleProposal(id: String, status: String) =
        synchronized(lock) {
            UUID.fromString(id)
            require(status in setOf("applied", "denied", "interrupted", "conflicted"))
            val file = File(root, "proposals/$id.json")
            val json = JSONObject(String(AtomicFile(file).readFully()))
            json.put("status", status)
            write(file, json.toString().toByteArray())
        }

    fun proposals(): JSONArray =
        synchronized(lock) {
            JSONArray(
                File(root, "proposals")
                    .listFiles()
                    .orEmpty()
                    .filter { it.extension == "json" }
                    .mapNotNull {
                        runCatching { JSONObject(String(AtomicFile(it).readFully())) }.getOrNull()
                    }
                    .sortedByDescending { it.optLong("createdAt") }
                    .take(100),
            )
        }

    fun applyProposalFromUser(id: String): JSONObject =
        synchronized(lock) {
            UUID.fromString(id)
            val item = JSONObject(String(AtomicFile(File(root, "proposals/$id.json")).readFully()))
            check(item.getString("status") == "interrupted") { "当前提案请在对话中确认，版本冲突请手动合并记忆" }
            val scope = item.getString("scope")
            val policy =
                PermissionContext(
                    sessionId = item.getString("sessionId"),
                    projectId = item.optString("projectId"),
                    actor = "user",
                )
            check(
                permissions
                    .evaluate(
                        policy,
                        Capability.MEMORY_WRITE,
                        memoryResource(
                            scope,
                            item.getString("sessionId"),
                            item.optString("projectId"),
                        ),
                    )
                    .effect != PermissionEffect.DENY,
            ) {
                "权限规则禁止该记忆写入"
            }
            try {
                val saved =
                    memories.write(
                        scope,
                        if (scope == "session") item.getString("sessionId")
                        else item.optString("projectId"),
                        item.getString("after"),
                        item.getString("expectedSha256"),
                    )
                settleProposal(id, "applied")
                saved.json()
            } catch (error: Throwable) {
                settleProposal(id, "conflicted")
                throw error
            }
        }

    companion object {
        fun memoryResource(scope: String, sessionId: String, projectId: String): String =
            when (scope) {
                "session" -> "session:$sessionId"
                "project" -> "project:$projectId"
                else -> scope
            }

        private fun write(file: File, bytes: ByteArray) {
            file.parentFile!!.mkdirs()
            val atomic = AtomicFile(file)
            val out = atomic.startWrite()
            try {
                out.write(bytes)
                atomic.finishWrite(out)
            } catch (error: Throwable) {
                atomic.failWrite(out)
                throw error
            }
        }

        private fun score(query: String, text: String): Int {
            if (query.isBlank()) return 0
            val value = text.lowercase()
            val words =
                query
                    .lowercase()
                    .split(Regex("[^\\p{L}\\p{N}_-]+"))
                    .filter { it.length >= 2 }
                    .take(20)
            var count = words.count { value.contains(it) } * 8
            if (query.length <= 96)
                query
                    .windowed(2)
                    .filter { it.all { c -> c.code > 127 } }
                    .distinct()
                    .forEach { if (value.contains(it)) count++ }
            return count
        }
    }
}
