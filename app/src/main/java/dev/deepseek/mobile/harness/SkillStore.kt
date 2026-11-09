package dev.deepseek.mobile.harness

import android.content.Context
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipInputStream
import org.json.JSONArray
import org.json.JSONObject

internal data class InstalledSkill(
    val id: String,
    val name: String,
    val description: String,
    val projectId: String,
    val enabled: Boolean,
) {
    fun json() =
        JSONObject()
            .put("id", id)
            .put("name", name)
            .put("description", description)
            .put("projectId", projectId)
            .put("enabled", enabled)
}

internal class SkillStore(context: Context) {
    private val root = File(context.filesDir, "harness/skills").apply { mkdirs() }

    @Synchronized
    fun list(): List<InstalledSkill> =
        root
            .listFiles()
            .orEmpty()
            .mapNotNull { dir ->
                runCatching {
                    UUID.fromString(dir.name)
                    val row =
                        JSONObject(
                            AtomicFile(File(dir, "manifest.json")).readFully().decodeToString(),
                        )
                    InstalledSkill(
                        dir.name,
                        row.getString("name"),
                        row.getString("description"),
                        row.getString("projectId"),
                        row.getBoolean("enabled"),
                    )
                }
                    .getOrNull()
            }
            .sortedBy { it.name }

    @Synchronized
    fun install(input: InputStream, projectId: String, enable: Boolean = false): InstalledSkill {
        val bytes = input.use { it.boundedBytes(maxPackage) }
        val files =
            if (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte()) {
                unpack(bytes)
            } else mapOf("SKILL.md" to bytes)
        val document = files["SKILL.md"] ?: error("技能包缺少 SKILL.md")
        require(document.size <= 128 * 1024) { "SKILL.md 最多 128 KiB" }
        val instructions = document.decodeToString(throwOnInvalidSequence = true)
        val lines = instructions.removePrefix("\uFEFF").lines()
        val end = lines.drop(1).indexOfFirst { it.trim() == "---" } + 1
        require(lines.firstOrNull()?.trim() == "---" && end > 1) { "SKILL.md 缺少 YAML 元数据" }
        val header = SafeYaml.mapping(lines.subList(1, end).joinToString("\n"))
        val name = header["name"] as? String ?: error("技能缺少 name")
        val description = (header["description"] as? String).orEmpty().trim()
        require(name.length in 1..64 && name.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*"))) {
            "技能名称应为小写字母、数字和连字符"
        }
        require(description.length in 1..1024) { "技能说明须为 1 至 1024 字符" }
        val installed = list()
        check(installed.size < 100) { "最多安装 100 个技能" }
        check(installed.none { it.name == name && it.projectId == projectId }) { "该范围已安装 $name" }
        val skill =
            InstalledSkill(UUID.randomUUID().toString(), name, description, projectId, enable)
        val stage = File(root, ".${skill.id}").apply { mkdir() }
        try {
            files.forEach { (path, data) ->
                File(stage, "files/$path").apply {
                    parentFile?.mkdirs()
                    writeBytes(data)
                }
            }
            save(File(stage, "manifest.json"), skill)
            check(stage.renameTo(File(root, skill.id))) { "保存技能失败" }
            return skill
        } finally {
            stage.deleteRecursively()
        }
    }

    @Synchronized
    fun enable(id: String, enabled: Boolean) {
        val skill = list().first { it.id == id }
        save(File(root, "$id/manifest.json"), skill.copy(enabled = enabled))
    }

    @Synchronized
    fun remove(id: String) {
        require(list().any { it.id == id })
        check(File(root, id).deleteRecursively()) { "删除技能失败" }
    }

    private fun visible(projectId: String) =
        list()
            .filter {
                it.enabled && (it.projectId.isBlank() || it.projectId == projectId)
            }
            .sortedByDescending { it.projectId.isNotBlank() }
            .distinctBy { it.name }

    fun catalog(projectId: String, allowed: (String) -> Boolean): JSONArray =
        JSONArray(
            visible(projectId)
                .filter { allowed(it.name) }
                .take(30)
                .map {
                    JSONObject().put("name", it.name).put("description", it.description.take(320))
                },
        )

    @Synchronized
    fun read(projectId: String, name: String, path: String, offset: Int): JSONObject {
        val skill = visible(projectId).firstOrNull { it.name == name } ?: error("技能未启用或不属于当前项目")
        return readFile(skill.id, path, offset)
    }

    @Synchronized
    fun preview(id: String): String {
        require(list().any { it.id == id })
        return File(root, "$id/files/SKILL.md").readText()
    }

    private fun readFile(id: String, path: String, offset: Int): JSONObject {
        val dir = File(root, "$id/files")
        val file = File(dir, safePath(path))
        require(file.isFile) { "技能包中没有此文件" }
        val text = file.readBytes().decodeToString(throwOnInvalidSequence = true)
        require(!text.contains('\u0000')) { "此工具只读取文本文件" }
        val start = offset.coerceIn(0, text.length)
        val end = (start + 12_000).coerceAtMost(text.length)
        return JSONObject()
            .put("path", path)
            .put("text", text.substring(start, end))
            .put("nextOffset", if (end == text.length) JSONObject.NULL else end)
            .put(
                "files",
                JSONArray(
                    dir.walkTopDown()
                        .filter { it.isFile }
                        .map { it.relativeTo(dir).invariantSeparatorsPath }
                        .sorted()
                        .toList(),
                ),
            )
    }

    private fun unpack(bytes: ByteArray): Map<String, ByteArray> {
        val files = linkedMapOf<String, ByteArray>()
        var size = 0
        var count = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++count <= 256) { "技能包文件过多" }
                val path = safePath(entry.name.trimEnd('/'))
                if (entry.isDirectory) continue
                val data = zip.boundedBytes(minOf(1_048_576, maxPackage - size))
                size += data.size
                require(files.put(path, data) == null) { "技能包包含重复路径" }
            }
        }
        val base =
            files.keys
                .filter { it == "SKILL.md" || it.endsWith("/SKILL.md") }
                .singleOrNull()
                ?.removeSuffix("SKILL.md") ?: error("每个 ZIP 须包含一个 SKILL.md")
        require(files.keys.all { it.startsWith(base) }) { "ZIP 中存在技能目录之外的文件" }
        return files.mapKeys { it.key.removePrefix(base) }
    }

    private fun safePath(path: String): String {
        require(
            path.length in 1..240 &&
                path.none { it == '\\' || it == ':' || it.isISOControl() } &&
                path.split('/').all { it.isNotEmpty() && it != "." && it != ".." },
        ) {
            "技能包路径不安全"
        }
        return path
    }

    private fun save(file: File, skill: InstalledSkill) {
        val atomic = AtomicFile(file)
        val out = atomic.startWrite()
        try {
            out.write(skill.json().toString().toByteArray())
            atomic.finishWrite(out)
        } catch (error: Throwable) {
            atomic.failWrite(out)
            throw error
        }
    }

    companion object {
        private const val maxPackage = 8 * 1024 * 1024
    }
}

internal fun InputStream.boundedBytes(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val size = read(buffer)
        if (size < 0) break
        require(output.size() + size <= limit) { "文件超过大小限制" }
        output.write(buffer, 0, size)
    }
    return output.toByteArray()
}
