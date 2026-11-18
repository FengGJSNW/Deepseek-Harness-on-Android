package dev.deepseek.mobile.runtime

import android.content.Context
import android.util.AtomicFile
import dev.deepseek.mobile.harness.HarnessCoordinator
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal data class SandboxDisk(
    val id: String,
    val name: String,
    val capacity: Long = 256L * 1024 * 1024,
    val allSandboxes: Boolean = false,
    val allowed: Set<String> = emptySet(),
    val denied: Set<String> = emptySet(),
) {
    fun allows(sandbox: String) = sandbox !in denied && (allSandboxes || sandbox in allowed)

    fun json() =
        JSONObject()
            .put("id", id)
            .put("name", name)
            .put("capacity", capacity)
            .put("all", allSandboxes)
            .put("allowed", JSONArray(allowed.toList()))
            .put("denied", JSONArray(denied.toList()))

    companion object {
        const val MIN_CAPACITY = 64L * 1024 * 1024
        const val MAX_CAPACITY = 32L * 1024 * 1024 * 1024

        fun from(j: JSONObject): SandboxDisk {
            fun ids(key: String): Set<String> =
                j.optJSONArray(key)
                    ?.let { a ->
                        (0 until a.length()).map { a.getString(it).also(UUID::fromString) }.toSet()
                    }
                    .orEmpty()
            return SandboxDisk(
                j.getString("id").also(UUID::fromString),
                j.getString("name"),
                j.getLong("capacity").also { require(it in MIN_CAPACITY..MAX_CAPACITY) },
                j.optBoolean("all"),
                ids("allowed"),
                ids("denied"),
            )
        }
    }
}

/** Durable data volumes, outside every VM directory. No guest path becomes an Android path. */
internal class SandboxDisks private constructor(private val context: Context) {
    private val root = File(context.filesDir, "sandbox-disks").apply { mkdirs() }.canonicalFile
    private val registry = AtomicFile(File(root, "disks.json"))
    private val guard = Any()
    private val _records = MutableStateFlow(read())
    val records = _records.asStateFlow()

    private fun read(): List<SandboxDisk> {
        if (!registry.baseFile.exists() && !File(registry.baseFile.path + ".bak").exists())
            return emptyList()
        val array = JSONArray(registry.readFully().decodeToString())
        return List(array.length()) { SandboxDisk.from(array.getJSONObject(it)) }
    }

    private fun atomic(file: File, bytes: ByteArray) {
        val target = AtomicFile(file)
        val out = target.startWrite()
        try {
            out.write(bytes)
            target.finishWrite(out)
        } catch (e: Throwable) {
            target.failWrite(out)
            throw e
        }
    }

    private fun write(values: List<SandboxDisk>) {
        atomic(registry.baseFile, JSONArray(values.map(SandboxDisk::json)).toString().toByteArray())
        _records.value = values
    }

    fun create(name: String, capacity: Long): SandboxDisk =
        synchronized(guard) {
            val title = name.trim()
            require(title.length in 1..60 && !title.any(Char::isISOControl)) { "请输入 1–60 个字符的磁盘名称" }
            require(records.value.none { it.name.equals(title, true) }) { "已有同名磁盘" }
            require(capacity in SandboxDisk.MIN_CAPACITY..SandboxDisk.MAX_CAPACITY)
            SandboxDisk(UUID.randomUUID().toString(), title, capacity).also {
                write(records.value + it)
            }
        }

    suspend fun change(id: String, transform: (SandboxDisk) -> SandboxDisk) {
        // Save and remove old mounts before publishing a new grant; commands cannot race this
        // change.
        HarnessCoordinator.get(context).runtimes.changeDiskMounts {
            withContext(Dispatchers.IO) {
                synchronized(guard) {
                    val old = records.value.first { it.id == id }
                    val next = transform(old)
                    require(next.id == old.id && next.capacity == old.capacity)
                    write(records.value.map { if (it.id == id) next else it })
                }
            }
        }
    }

    suspend fun delete(id: String) {
        UUID.fromString(id)
        HarnessCoordinator.get(context).runtimes.changeDiskMounts {
            withContext(Dispatchers.IO) {
                synchronized(guard) {
                    val dir = directory(id)
                    check(dir.canonicalFile.parentFile == root)
                    // Only explicit disk deletion reaches here. Deleting a Sandbox never does.
                    check(dir.deleteRecursively()) { "磁盘文件删除失败，请重试" }
                    write(records.value.filterNot { it.id == id })
                }
            }
        }
    }

    private fun directory(id: String): File {
        UUID.fromString(id)
        return File(root, id).apply { mkdirs() }
    }

    fun mounted(sandbox: String) = records.value.filter { it.allows(sandbox) }

    suspend fun attach(vm: LinuxVm, sandbox: String): List<SandboxDisk> {
        val selected = mounted(sandbox)
        // A restored snapshot can contain an old or revoked mount. Always rebuild it from disk.
        val reset =
            vm.execute(
                "umount /mnt/alpine/disks 2>/dev/null || true; rm -rf /mnt/disks /mnt/alpine/disks; mkdir -p /mnt/disks /mnt/alpine/disks; mount --bind /mnt/disks /mnt/alpine/disks",
            )
        check(reset.optInt("exitCode", -1) == 0) { "无法准备数据磁盘挂载点" }
        for (disk in selected) {
            vm.guestDirectory("/disks/${disk.id}")
            val manifest =
                withContext(Dispatchers.IO) {
                    val file = AtomicFile(File(directory(disk.id), "files.json"))
                    if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists())
                        JSONArray(file.readFully().decodeToString())
                    else JSONArray()
                }
            var bytes = 0L
            require(manifest.length() <= 20000)
            // Directories and files precede links; no restore operation traverses a guest link.
            val entries =
                (0 until manifest.length())
                    .map(manifest::getJSONObject)
                    .sortedWith(
                        compareBy<JSONObject> { it.getString("kind") == "link" }
                            .thenBy { it.getString("path").count { c -> c == '/' } },
                    )
            for (item in entries) {
                val relative = validPath(item.getString("path"))
                val path = "/disks/${disk.id}/$relative"
                when (item.getString("kind")) {
                    "dir" -> vm.guestDirectory(path)
                    "link" -> vm.guestSymlink(path, item.getString("target"))
                    "file" -> {
                        val hash =
                            item.getString("hash").also {
                                require(it.matches(Regex("[a-f0-9]{64}")))
                            }
                        val data =
                            withContext(Dispatchers.IO) {
                                val file = File(directory(disk.id), hash)
                                require(file.length() <= MAX_FILE)
                                file.readBytes().also { require(digest(it) == hash) { "磁盘文件校验失败" } }
                            }
                        bytes += data.size
                        require(bytes <= disk.capacity)
                        vm.guestWrite(path, data, item.optBoolean("executable"))
                    }
                    else -> error("未知磁盘条目")
                }
            }
        }
        return selected
    }

    suspend fun save(vm: LinuxVm, mounted: List<SandboxDisk>) {
        for (disk in mounted) {
            // Interrupted saves may leave unreferenced blobs. Bound recovery to this volume only.
            withContext(Dispatchers.IO) {
                val dir = directory(disk.id)
                val committed = AtomicFile(File(dir, "files.json"))
                val saved =
                    if (
                        committed.baseFile.exists() ||
                            File(committed.baseFile.path + ".bak").exists()
                    )
                        JSONArray(committed.readFully().decodeToString())
                    else JSONArray()
                val references =
                    (0 until saved.length())
                        .map { saved.getJSONObject(it).optString("hash") }
                        .toSet()
                dir.listFiles()
                    .orEmpty()
                    .filter { it.name.matches(Regex("[a-f0-9]{64}")) && it.name !in references }
                    .forEach {
                        check(it.canonicalFile.parentFile == dir.canonicalFile)
                        it.delete()
                    }
            }
            val manifest = JSONArray()
            val blobs = mutableSetOf<String>()
            var bytes = 0L
            suspend fun walk(parent: String, depth: Int) {
                require(depth <= 64) { "磁盘目录层级超过 64 层，旧检查点仍保留" }
                val children =
                    vm.guestList("/disks/${disk.id}" + if (parent.isEmpty()) "" else "/$parent")
                for (i in 0 until children.length()) {
                    val item = children.getJSONObject(i)
                    val name = item.getString("name")
                    require(!name.contains('/'))
                    val path = validPath(if (parent.isEmpty()) name else "$parent/$name")
                    require(manifest.length() < 20000) { "磁盘文件数超过上限，旧检查点仍保留" }
                    val mode = item.getInt("mode")
                    val entry = JSONObject().put("path", path)
                    when (mode and 61440) {
                        16384 -> {
                            manifest.put(entry.put("kind", "dir"))
                            walk(path, depth + 1)
                        }
                        40960 ->
                            manifest.put(
                                entry
                                    .put("kind", "link")
                                    .put(
                                        "target",
                                        item.getString("link").also { require(it.length <= 4096) },
                                    ),
                            )
                        32768 -> {
                            require(item.getLong("size") <= MAX_FILE)
                            bytes += item.getLong("size")
                            require(bytes <= disk.capacity) { "磁盘超出容量，请清理后重试；旧检查点仍保留" }
                            val data = vm.guestRead("/disks/${disk.id}/$path", MAX_FILE)
                            val hash = digest(data)
                            withContext(Dispatchers.IO) {
                                val blob = File(directory(disk.id), hash)
                                if (!blob.exists()) atomic(blob, data)
                            }
                            blobs += hash
                            manifest.put(
                                entry
                                    .put("kind", "file")
                                    .put("hash", hash)
                                    .put("executable", mode and 73 != 0),
                            )
                        }
                        else -> error("磁盘包含不能保存的设备或 Socket：$path")
                    }
                }
            }
            walk("", 0)
            withContext(Dispatchers.IO) {
                val dir = directory(disk.id)
                atomic(File(dir, "files.json"), manifest.toString().toByteArray())
                // Content is committed first. Sweep only obsolete, immutable blob names.
                dir.listFiles()
                    .orEmpty()
                    .filter { it.name.matches(Regex("[a-f0-9]{64}")) && it.name !in blobs }
                    .forEach {
                        check(it.canonicalFile.parentFile == dir.canonicalFile)
                        it.delete()
                    }
            }
        }
    }

    companion object {
        private const val MAX_FILE = 64 * 1024 * 1024

        internal fun validPath(path: String): String {
            require(
                path.isNotEmpty() &&
                    path.length <= 4096 &&
                    !path.startsWith('/') &&
                    !path.contains('\\') &&
                    !path.contains('\u0000'),
            )
            require(path.split('/').none { it in setOf("", ".", "..") })
            return path
        }

        private fun digest(bytes: ByteArray) =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                "%02x".format(it)
            }

        @Volatile private var instance: SandboxDisks? = null

        fun get(context: Context): SandboxDisks =
            instance
                ?: synchronized(this) {
                    instance ?: SandboxDisks(context.applicationContext).also { instance = it }
                }
    }
}
