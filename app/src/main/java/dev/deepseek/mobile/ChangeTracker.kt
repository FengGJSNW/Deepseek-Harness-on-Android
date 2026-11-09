package dev.deepseek.mobile

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.*
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal data class FileVersion(val hash: String, val text: String?, val size: Long)

internal data class WorkChange(
    val id: String,
    val taskId: String,
    val sessionId: String,
    val path: String,
    val before: String?,
    val after: String?,
    val kind: String,
    val binary: Boolean,
) {
    fun json() =
        JSONObject()
            .put("id", id)
            .put("taskId", taskId)
            .put("sessionId", sessionId)
            .put("path", path)
            .put("before", before ?: JSONObject.NULL)
            .put("after", after ?: JSONObject.NULL)
            .put("kind", kind)
            .put("binary", binary)
}

internal object ChangeState {
    var show by mutableStateOf(false)
    var taskId by mutableStateOf("")
    var selected by mutableStateOf<WorkChange?>(null)
    val changes = mutableStateListOf<WorkChange>()
}

internal class ChangeTracker(private val context: Context) {
    private val before = mutableMapOf<String, Map<String, FileVersion>>()

    init {
        runCatching {
            val f = android.util.AtomicFile(File(context.filesDir, "changes-v1.json"))
            ChangeState.changes.clear()
            if (f.baseFile.exists()) {
                val values = JSONArray(String(f.readFully()))
                for (i in 0 until values.length()) values.getJSONObject(i).let {
                    ChangeState.changes.add(
                        WorkChange(
                            it.getString("id"),
                            it.getString("taskId"),
                            it.getString("sessionId"),
                            it.getString("path"),
                            if (it.isNull("before")) null else it.getString("before"),
                            if (it.isNull("after")) null else it.getString("after"),
                            it.getString("kind"),
                            it.optBoolean("binary"),
                        ),
                    )
                }
            }
        }
            .onFailure { AssistantState.error = "文件改动记录读取失败，原文件已保留" }
    }

    private fun version(input: java.io.InputStream, size: Long): FileVersion {
        val digest = MessageDigest.getInstance("SHA-256")
        val prefix = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var count = 0L
        input.use {
            while (true) {
                val n = it.read(buffer)
                if (n < 0) break
                count += n
                require(count <= 8 * 1024 * 1024) { "文件超过差异跟踪大小" }
                digest.update(buffer, 0, n)
                if (prefix.size() < 65536) prefix.write(buffer, 0, minOf(n, 65536 - prefix.size()))
            }
        }
        val bytes = prefix.toByteArray()
        val text =
            if (count <= 65536 && bytes.none { it == 0.toByte() })
                runCatching {
                    Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                }
                    .getOrNull()
            else null
        return FileVersion(digest.digest().joinToString("") { "%02x".format(it) }, text, size)
    }

    private suspend fun snapshot(task: PhoneTask) =
        withContext(Dispatchers.IO) {
            val result = linkedMapOf<String, FileVersion>()
            if (
                task.terminalEnabled &&
                    runCatching { JSONObject(task.terminalConfig).getString("level") }
                        .getOrNull() in setOf("isolated", "app")
            ) {
                val root =
                    File(context.filesDir, "terminal/workplace/session_${task.sessionId}")
                        .canonicalFile
                if (root.isDirectory)
                    root
                        .walkTopDown()
                        .maxDepth(8)
                        .onEnter { it.canonicalFile == it.absoluteFile }
                        .filter { it.isFile && it.canonicalFile == it.absoluteFile }
                        .take(256)
                        .forEach { file ->
                            runCatching {
                                result["terminal/${file.relativeTo(root).path}"] =
                                    version(file.inputStream(), file.length())
                            }
                        }
            }
            val sandbox =
                task.terminalConfig.isNotBlank() &&
                    runCatching { JSONObject(task.terminalConfig).optString("level") == "sandbox" }
                        .getOrDefault(false)
            if (sandbox) {
                val h = dev.deepseek.mobile.harness.HarnessCoordinator.get(context)
                val tree = h.workspaces.begin(h.runtimes.scope(task), task.id)
                h.workspaces.current(tree).forEach { (path, value) ->
                    val bytes = h.workspaces.content(value)
                    val text =
                        if (bytes.size <= 65536 && !value.link && bytes.none { it == 0.toByte() })
                            runCatching {
                                Charsets.UTF_8.newDecoder()
                                    .decode(java.nio.ByteBuffer.wrap(bytes))
                                    .toString()
                            }
                                .getOrNull()
                        else null
                    result["terminal/$path"] =
                        FileVersion(
                            value.hash + if (value.executable) ":x" else "",
                            text,
                            bytes.size.toLong(),
                        )
                }
            }
            if (!sandbox && task.fileTools && task.workspace.isNotBlank()) {
                suspend fun visit(dir: DocumentFile, prefix: String, depth: Int) {
                    if (depth > 8 || result.size >= 256) return
                    dir.listFiles().forEach { file ->
                        if (result.size >= 256) return@forEach
                        val path = "$prefix/${file.name.orEmpty()}"
                        if (file.isDirectory) visit(file, path, depth + 1)
                        else if (file.isFile)
                            runCatching {
                                result[path] =
                                    version(
                                        context.contentResolver.openInputStream(file.uri)!!,
                                        file.length(),
                                    )
                            }
                    }
                }
                runCatching {
                    DocumentFile.fromTreeUri(context, Uri.parse(task.workspace))?.let {
                        visit(it, "workspace", 0)
                    }
                }
            }
            for (scope in listOf("global", "persona", "session", "project")) {
                if (scope == "project" && task.projectId.isBlank()) continue
                runCatching {
                    val memory =
                        MemoryStore(context)
                            .read(scope, if (scope == "session") task.sessionId else task.projectId)
                    result["memory/${memory.filename}"] =
                        FileVersion(
                            memory.sha256,
                            memory.text,
                            memory.text.toByteArray().size.toLong(),
                        )
                }
            }
            result
        }

    private suspend fun confirmedMissing(task: PhoneTask, path: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                    if (
                        path.startsWith("terminal/") &&
                            runCatching {
                                    JSONObject(task.terminalConfig).optString("level") == "sandbox"
                                }
                                .getOrDefault(false)
                    ) {
                        val h = dev.deepseek.mobile.harness.HarnessCoordinator.get(context)
                        val tree = h.workspaces.load(h.runtimes.scope(task), task.id)
                        path.removePrefix("terminal/") !in h.workspaces.current(tree)
                    } else if (path.startsWith("terminal/")) {
                        !File(
                                context.filesDir,
                                "terminal/workplace/session_${task.sessionId}/" +
                                    path.removePrefix("terminal/"),
                            )
                            .exists()
                    } else if (path.startsWith("workspace/")) {
                        var current =
                            DocumentFile.fromTreeUri(context, Uri.parse(task.workspace))
                                ?: return@runCatching false
                        if (!current.canRead()) return@runCatching false
                        for (part in path.removePrefix("workspace/").split('/')) {
                            if (!current.canRead()) return@runCatching false
                            current = current.findFile(part) ?: return@runCatching true
                        }
                        false
                    } else false
                }
                .getOrDefault(false)
        }

    suspend fun begin(task: PhoneTask) {
        if (
            runCatching { JSONObject(task.terminalConfig).optString("level") == "sandbox" }
                .getOrDefault(false)
        )
            dev.deepseek.mobile.harness.HarnessCoordinator.get(context).runtimes.prepareTask(task)
        before[task.id] = snapshot(task)
    }

    suspend fun finish(task: PhoneTask) {
        val initial = before.remove(task.id) ?: return
        val after = snapshot(task)
        val values =
            (initial.keys + after.keys).mapNotNull { path ->
                val a = initial[path]
                val b = after[path]
                if (a?.hash == b?.hash || (a != null && b == null && !confirmedMissing(task, path)))
                    null
                else
                    WorkChange(
                        UUID.randomUUID().toString(),
                        task.id,
                        task.sessionId,
                        path,
                        a?.text,
                        b?.text,
                        if (a == null) "新增" else if (b == null) "删除" else "修改",
                        (a != null && a.text == null) || (b != null && b.text == null),
                    )
            }
        ChangeState.changes.addAll(values)
        val hasWorktrees =
            if (
                runCatching { JSONObject(task.terminalConfig).optString("level") == "sandbox" }
                    .getOrDefault(false)
            )
                withContext(Dispatchers.IO) {
                    val h = dev.deepseek.mobile.harness.HarnessCoordinator.get(context)
                    h.workspaces.tasks(h.runtimes.scope(task), task.id).any {
                        it.state in setOf("active", "ready", "conflicted") &&
                            h.workspaces.review(it).isNotEmpty()
                    }
                }
            else false
        if (values.isNotEmpty() || hasWorktrees)
            AssistantState.sessions
                .firstOrNull { it.id == task.sessionId }
                ?.messages
                ?.add(ChatEntry("changes", task.id, taskId = task.id))
        withContext(Dispatchers.IO) {
            val f = android.util.AtomicFile(File(context.filesDir, "changes-v1.json"))
            val output = f.startWrite()
            try {
                output.write(
                    JSONArray(ChangeState.changes.map { it.json() }).toString().toByteArray(),
                )
                f.finishWrite(output)
            } catch (e: Exception) {
                f.failWrite(output)
                throw e
            }
        }
    }
}

/** Bounded LCS; long files use an explicit replacement without quadratic allocation. */
internal fun lineDiff(before: String, after: String): List<Pair<Char, String>> {
    val a = before.lines()
    val b = after.lines()
    if (a.size.toLong() * b.size > 1_000_000) return a.map { '-' to it } + b.map { '+' to it }
    val table = Array(a.size + 1) { IntArray(b.size + 1) }
    for (i in a.lastIndex downTo 0) for (j in b.lastIndex downTo 0) table[i][j] =
        if (a[i] == b[j]) 1 + table[i + 1][j + 1] else maxOf(table[i + 1][j], table[i][j + 1])
    val out = mutableListOf<Pair<Char, String>>()
    var i = 0
    var j = 0
    while (i < a.size || j < b.size) {
        if (i < a.size && j < b.size && a[i] == b[j]) {
            out.add(' ' to a[i])
            i++
            j++
        } else if (j < b.size && (i == a.size || table[i][j + 1] >= table[i + 1][j]))
            out.add('+' to b[j++])
        else out.add('-' to a[i++])
    }
    return out
}
