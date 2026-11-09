package dev.deepseek.mobile.harness

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal data class WorkspaceScope(val kind: String, val id: String) {
    init {
        require(kind in setOf("project", "session"))
        require(id.matches(Regex("[a-f0-9-]{36}")))
    }

    val key
        get() = "$kind:$id"
}

internal data class FileVersion(
    val hash: String,
    val link: Boolean = false,
    val executable: Boolean = false,
) {
    fun json() = JSONObject().put("hash", hash).put("link", link).put("executable", executable)
}

internal data class Worktree(
    val scope: WorkspaceScope,
    val taskId: String,
    val root: File,
    val base: Map<String, FileVersion>,
    val state: String,
)

internal data class WorkspaceChange(
    val path: String,
    val before: FileVersion?,
    val after: FileVersion?,
) {
    fun json() =
        JSONObject().put("path", path).put("before", before?.json()).put("after", after?.json())
}

/** Independent, versioned working copies. No hard links and no metadata inside the user's .git. */
internal class WorkspaceManager(private val context: Context) {
    private val root = File(context.filesDir, "workplace").canonicalFile.apply { mkdirs() }
    private val metadata = File(context.filesDir, "harness/workspaces").apply { mkdirs() }
    private val blobs = File(metadata, "objects").apply { mkdirs() }

    companion object {
        private val lock = Any()
        const val MAX_BYTES = 256L * 1024 * 1024

        fun digest(bytes: ByteArray) =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                "%02x".format(it)
            }

        fun validPath(path: String): String {
            require(
                path.isNotEmpty() &&
                    path.length <= 1024 &&
                    !path.startsWith('/') &&
                    !path.contains('\\') &&
                    !path.contains('\u0000'),
            ) {
                "Invalid workspace path"
            }
            require(path.split('/').none { it.isEmpty() || it == "." || it == ".." }) {
                "Workspace path traversal rejected"
            }
            return path
        }

        private fun write(file: File, bytes: ByteArray) {
            file.parentFile!!.mkdirs()
            val atomic = AtomicFile(file)
            val out = atomic.startWrite()
            try {
                out.write(bytes)
                atomic.finishWrite(out)
                check(atomic.readFully().contentEquals(bytes)) { "工作区元数据未能完整提交" }
            } catch (error: Throwable) {
                atomic.failWrite(out)
                throw error
            }
        }

        private fun versions(json: JSONObject): Map<String, FileVersion> =
            json.keys().asSequence().associateWith { path ->
                validPath(path)
                val value = json.getJSONObject(path)
                FileVersion(
                    value.getString("hash").also { require(it.matches(Regex("[a-f0-9]{64}"))) },
                    value.optBoolean("link"),
                    value.optBoolean("executable"),
                )
            }

        private fun json(map: Map<String, FileVersion>) =
            JSONObject().apply { map.toSortedMap().forEach { (p, v) -> put(p, v.json()) } }
    }

    fun workspace(scope: WorkspaceScope): File =
        File(
                root,
                "${if (scope.kind == "project") "projects" else "sessions"}/${scope.id}/workspace",
            )
            .apply { mkdirs() }

    /**
     * Copies only this session's former private home; never follows links or imports a SAF tree.
     */
    fun migrateLegacy(scope: WorkspaceScope, sessionId: String): JSONObject =
        synchronized(lock) {
            UUID.fromString(sessionId)
            val marker = File(metadata, "migrations/$sessionId.json")
            if (marker.isFile)
                return@synchronized JSONObject(String(AtomicFile(marker).readFully()))
            val legacyParent = File(context.filesDir.canonicalFile, "terminal/workplace")
            require(legacyParent.absoluteFile == legacyParent.canonicalFile) { "旧工作目录不可含符号链接" }
            val parent = legacyParent.canonicalFile
            val source = File(parent, "session_$sessionId")
            require(
                source.canonicalFile == source.absoluteFile &&
                    source.canonicalFile.parentFile == parent,
            )
            val destination = workspace(scope)
            var copied = 0
            var skipped = 0
            var total = 0L
            if (source.isDirectory)
                Files.walk(source.toPath()).use { entries ->
                    entries
                        .filter { it != source.toPath() }
                        .forEach { path ->
                            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return@forEach
                            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                                skipped++
                                return@forEach
                            }
                            require(copied < 20000 && Files.size(path) <= MAX_BYTES)
                            val bytes = Files.readAllBytes(path)
                            total += bytes.size
                            require(total <= MAX_BYTES) { "旧工作区过大，原文件保留，请手动选择导入" }
                            val name =
                                validPath(
                                    source
                                        .toPath()
                                        .relativize(path)
                                        .toString()
                                        .replace(File.separatorChar, '/'),
                                )
                            val relative = "legacy/$sessionId/$name"
                            val target = safeFile(destination, relative)
                            if (target.exists()) {
                                check(
                                    !Files.isSymbolicLink(target.toPath()) &&
                                        target.isFile &&
                                        target.readBytes().contentEquals(bytes),
                                ) {
                                    "迁移目标已存在不同内容：$relative"
                                }
                            } else
                                install(
                                    destination,
                                    relative,
                                    store(bytes, false, Files.isExecutable(path)),
                                )
                            copied++
                        }
                }
            val result =
                JSONObject()
                    .put("sessionId", sessionId)
                    .put("scope", scope.key)
                    .put("copied", copied)
                    .put("skippedLinksOrSpecialFiles", skipped)
                    .put("bytes", total)
                    .put("sourcePreserved", true)
            write(marker, result.toString().toByteArray())
            result
        }

    private fun record(scope: WorkspaceScope, taskId: String): File {
        UUID.fromString(taskId)
        return File(metadata, "${scope.kind}/${scope.id}/tasks/$taskId.json")
    }

    private fun treeRoot(scope: WorkspaceScope, taskId: String) =
        File(
            root,
            "${if (scope.kind == "project") "projects" else "sessions"}/${scope.id}/worktrees/$taskId",
        )

    private fun objectBytes(version: FileVersion): ByteArray {
        val bytes = File(blobs, version.hash).readBytes()
        check(digest(bytes) == version.hash) { "Workspace object checksum failed" }
        return bytes
    }

    private fun store(bytes: ByteArray, link: Boolean, executable: Boolean = false): FileVersion {
        val hash = digest(bytes)
        val file = File(blobs, hash)
        if (!file.exists()) write(file, bytes)
        return FileVersion(hash, link, executable)
    }

    fun snapshot(directory: File): Map<String, FileVersion> =
        synchronized(lock) {
            require(directory.canonicalPath.startsWith(root.path + File.separator))
            val values = sortedMapOf<String, FileVersion>()
            var count = 0L
            Files.walk(directory.toPath()).use { paths ->
                paths
                    .filter { it != directory.toPath() }
                    .forEach { path ->
                        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return@forEach
                        val relative =
                            validPath(
                                directory
                                    .toPath()
                                    .relativize(path)
                                    .toString()
                                    .replace(File.separatorChar, '/'),
                            )
                        val link = Files.isSymbolicLink(path)
                        require(link || Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                            "Unsupported workspace object: $relative"
                        }
                        require(link || Files.size(path) <= MAX_BYTES) {
                            "Workspace file too large"
                        }
                        val bytes =
                            if (link) Files.readSymbolicLink(path).toString().toByteArray()
                            else Files.readAllBytes(path)
                        count += bytes.size
                        require(count <= MAX_BYTES && values.size < 20000) {
                            "Workspace quota exceeded"
                        }
                        values[relative] = store(bytes, link, !link && Files.isExecutable(path))
                    }
            }
            values
        }

    fun begin(scope: WorkspaceScope, taskId: String, parentTaskId: String = taskId): Worktree =
        synchronized(lock) {
            val existing = record(scope, taskId)
            if (existing.isFile) return@synchronized load(scope, taskId)
            val base = snapshot(workspace(scope))
            val tree = treeRoot(scope, taskId).apply { mkdirs() }
            val doc =
                JSONObject()
                    .put("scope", scope.key)
                    .put("taskId", taskId)
                    .put("parentTaskId", parentTaskId)
                    .put("base", json(base))
                    .put("state", "creating")
                    .put("createdAt", System.currentTimeMillis())
            write(existing, doc.toString().toByteArray())
            base.forEach { (path, version) -> install(tree, path, version) }
            doc.put("state", "active")
            write(existing, doc.toString().toByteArray())
            Worktree(scope, taskId, tree, base, "active")
        }

    fun tasks(scope: WorkspaceScope, parentTaskId: String? = null): List<Worktree> =
        synchronized(lock) {
            val folder = File(metadata, "${scope.kind}/${scope.id}/tasks")
            folder
                .listFiles()
                .orEmpty()
                .filter { it.extension == "json" }
                .mapNotNull { file ->
                    val doc = JSONObject(String(AtomicFile(file).readFully()))
                    if (
                        parentTaskId == null ||
                            doc.optString("parentTaskId", doc.getString("taskId")) == parentTaskId
                    )
                        load(scope, doc.getString("taskId"))
                    else null
                }
        }

    fun load(scope: WorkspaceScope, taskId: String): Worktree =
        synchronized(lock) {
            val doc = JSONObject(String(AtomicFile(record(scope, taskId)).readFully()))
            check(doc.getString("scope") == scope.key)
            val tree = treeRoot(scope, taskId)
            val base = versions(doc.getJSONObject("base"))
            if (doc.getString("state") == "creating") {
                // An interrupted initial copy has never been exposed to a task; recreate from its
                // base.
                base.forEach { (p, v) -> install(tree, p, v) }
                doc.put("state", "active")
                write(record(scope, taskId), doc.toString().toByteArray())
            }
            Worktree(scope, taskId, tree, base, doc.getString("state"))
        }

    fun content(version: FileVersion): ByteArray = synchronized(lock) { objectBytes(version) }

    fun stage(bytes: ByteArray, link: Boolean = false, executable: Boolean = false): FileVersion =
        synchronized(lock) {
            require(bytes.size <= MAX_BYTES)
            store(bytes, link, executable)
        }

    fun current(tree: Worktree): Map<String, FileVersion> =
        synchronized(lock) {
            val doc = JSONObject(String(AtomicFile(record(tree.scope, tree.taskId)).readFully()))
            doc.optJSONObject("head")?.let { versions(it) } ?: snapshot(tree.root)
        }

    /** Commit a complete guest export only after every file has passed validation and hashing. */
    fun receive(tree: Worktree, entries: Map<String, FileVersion>) =
        synchronized(lock) {
            require(entries.size <= 20000)
            entries.keys.forEach { path ->
                var parent = path.substringBeforeLast('/', "")
                while (parent.isNotEmpty()) {
                    require(parent !in entries) { "文件导出存在父子路径冲突：$path" }
                    parent = parent.substringBeforeLast('/', "")
                }
            }
            var bytes = 0L
            entries.forEach { (path, value) ->
                validPath(path)
                val data = objectBytes(value)
                bytes += data.size
                if (value.link) {
                    val target = java.nio.file.Paths.get(String(data, Charsets.UTF_8))
                    val parent = java.nio.file.Paths.get(path).parent ?: java.nio.file.Paths.get("")
                    require(
                        !target.isAbsolute && !parent.resolve(target).normalize().startsWith(".."),
                    ) {
                        "External symlink in export"
                    }
                }
            }
            require(bytes <= MAX_BYTES)
            val file = record(tree.scope, tree.taskId)
            val doc = JSONObject(String(AtomicFile(file).readFully()))
            check(doc.getString("state") in setOf("active", "ready", "conflicted"))
            doc.put("head", json(entries))
                .put("state", "ready")
                .put("updatedAt", System.currentTimeMillis())
            write(file, doc.toString().toByteArray())
        }

    fun review(tree: Worktree): List<WorkspaceChange> =
        synchronized(lock) {
            val after = current(tree)
            (tree.base.keys + after.keys).sorted().mapNotNull { path ->
                if (tree.base[path] == after[path]) null
                else WorkspaceChange(path, tree.base[path], after[path])
            }
        }

    fun merge(tree: Worktree): JSONObject =
        synchronized(lock) {
            check(load(tree.scope, tree.taskId).state in setOf("active", "ready", "conflicted")) {
                "Worktree is not mergeable"
            }
            val changes =
                review(tree)
                    .sortedWith(
                        compareBy<WorkspaceChange> { if (it.after == null) 0 else 1 }
                            .thenBy {
                                if (it.after == null) -it.path.count { c -> c == '/' }
                                else it.path.count { c -> c == '/' }
                            }
                            .thenBy { it.path },
                    )
            val current = snapshot(workspace(tree.scope))
            val conflicts =
                changes
                    .filter { current[it.path] != it.before && current[it.path] != it.after }
                    .map { it.path }
            val removals = changes.filter { it.after == null }.map { it.path }.toSet()
            val structural =
                changes
                    .filter { it.after != null }
                    .filter { change ->
                        current.keys.any { old ->
                            (old.startsWith(change.path + "/") ||
                                change.path.startsWith(old + "/")) && old !in removals
                        }
                    }
                    .map { it.path }
            val allConflicts = (conflicts + structural).distinct()
            val file = record(tree.scope, tree.taskId)
            val doc = JSONObject(String(AtomicFile(file).readFully()))
            if (allConflicts.isNotEmpty()) {
                doc.put("state", "conflicted").put("conflicts", JSONArray(allConflicts))
                write(file, doc.toString().toByteArray())
                return@synchronized JSONObject()
                    .put("merged", false)
                    .put("conflicts", JSONArray(allConflicts))
            }
            doc.put("mergeId", UUID.randomUUID().toString())
                .put("state", "merging")
                .put("changes", JSONArray(changes.map { it.json() }))
                .put("next", 0)
            write(file, doc.toString().toByteArray())
            applyJournal(tree.scope, file, doc)
        }

    private fun applyJournal(scope: WorkspaceScope, file: File, doc: JSONObject): JSONObject {
        val all = doc.getJSONArray("changes")
        val target = workspace(scope)
        val progress =
            AtomicFile(File(file.parentFile, file.nameWithoutExtension + ".merge-progress"))
        val saved = runCatching { JSONObject(String(progress.readFully())) }.getOrNull()
        val next =
            if (saved?.optString("mergeId") == doc.optString("mergeId")) saved!!.optInt("next")
            else doc.optInt("next")
        val currentFiles = snapshot(target).toMutableMap()
        for (i in next until all.length()) {
            val operation = all.getJSONObject(i)
            val path = validPath(operation.getString("path"))
            fun version(name: String) =
                operation.optJSONObject(name)?.let {
                    FileVersion(
                        it.getString("hash"),
                        it.optBoolean("link"),
                        it.optBoolean("executable"),
                    )
                }
            val before = version("before")
            val after = version("after")
            val current = currentFiles[path]
            if (current != before && current != after) {
                doc.put("state", "conflicted").put("conflicts", JSONArray(listOf(path)))
                write(file, doc.toString().toByteArray())
                return JSONObject()
                    .put("merged", false)
                    .put("conflicts", JSONArray(listOf(path)))
                    .put("recovery", true)
            }
            if (current != after) {
                if (after == null) safeFile(target, path).let { Files.deleteIfExists(it.toPath()) }
                else install(target, path, after)
            }
            if (after == null) currentFiles.remove(path) else currentFiles[path] = after
            write(
                progress.baseFile,
                JSONObject()
                    .put("mergeId", doc.optString("mergeId"))
                    .put("next", i + 1)
                    .toString()
                    .toByteArray(),
            )
        }
        doc.put("state", "merged").put("mergedAt", System.currentTimeMillis())
        write(file, doc.toString().toByteArray())
        progress.delete()
        return JSONObject().put("merged", true).put("files", all.length())
    }

    fun recover(scope: WorkspaceScope): List<JSONObject> =
        synchronized(lock) {
            val folder = File(metadata, "${scope.kind}/${scope.id}/tasks")
            folder
                .listFiles()
                .orEmpty()
                .filter { it.extension == "json" }
                .mapNotNull { f ->
                    val doc = JSONObject(String(AtomicFile(f).readFully()))
                    if (doc.optString("state") == "merging") applyJournal(scope, f, doc) else null
                }
        }

    fun discard(tree: Worktree) =
        synchronized(lock) {
            val file = record(tree.scope, tree.taskId)
            val doc = JSONObject(String(AtomicFile(file).readFully()))
            check(doc.getString("state") != "merging")
            doc.put("state", "discarded")
            write(file, doc.toString().toByteArray())
            require(
                tree.root.absolutePath.startsWith(root.path + File.separator) &&
                    !Files.isSymbolicLink(tree.root.toPath()),
            )
            if (tree.root.exists())
                Files.walk(tree.root.toPath()).use { paths ->
                    paths.sorted(java.util.Comparator.reverseOrder()).forEach { Files.delete(it) }
                }
            Unit
        }

    fun importFile(scope: WorkspaceScope, name: String, bytes: ByteArray) =
        synchronized(lock) {
            require(bytes.size <= MAX_BYTES)
            install(workspace(scope), validPath(name), store(bytes, false))
        }

    private fun safeFile(directory: File, relative: String): File {
        val target = File(directory, validPath(relative))
        require(target.parentFile!!.canonicalFile == target.parentFile!!.absoluteFile) {
            "工作区父目录不可含符号链接"
        }
        require(
            target.parentFile!!.canonicalPath == directory.canonicalPath ||
                target.parentFile!!
                    .canonicalPath
                    .startsWith(directory.canonicalPath + File.separator),
        ) {
            "Workspace symlink escape rejected"
        }
        return target
    }

    private fun install(directory: File, relative: String, version: FileVersion) {
        val target = safeFile(directory, relative)
        target.parentFile!!.mkdirs()
        if (Files.isDirectory(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            check(target.list().orEmpty().isEmpty() && target.delete()) { "目录中仍有文件，拒绝覆盖：$relative" }
        }
        if (Files.isSymbolicLink(target.toPath())) Files.delete(target.toPath())
        val bytes = objectBytes(version)
        if (version.link) {
            val link = java.nio.file.Paths.get(String(bytes, Charsets.UTF_8))
            require(
                !link.isAbsolute &&
                    target.parentFile!!
                        .toPath()
                        .resolve(link)
                        .normalize()
                        .startsWith(directory.toPath()),
            ) {
                "External workspace symlink rejected: $relative"
            }
            Files.deleteIfExists(target.toPath())
            Files.createSymbolicLink(target.toPath(), link)
        } else {
            write(target, bytes)
            check(target.setExecutable(version.executable, true)) { "Cannot restore file mode" }
        }
    }
}
