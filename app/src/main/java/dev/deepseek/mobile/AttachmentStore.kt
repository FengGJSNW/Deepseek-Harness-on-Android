package dev.deepseek.mobile

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import android.webkit.MimeTypeMap
import java.io.File
import java.io.InputStream
import java.util.UUID
import org.json.JSONObject

data class LocalAttachment(
    val id: String,
    val sessionId: String,
    val name: String,
    val mime: String,
    val size: Long,
    val sourcePath: String = "",
    val sourceTask: String = "",
) {
    val link
        get() = "dsha://file/$id"

    fun json() =
        JSONObject()
            .put("id", id)
            .put("sessionId", sessionId)
            .put("name", name)
            .put("mime", mime)
            .put("size", size)
            .put("sourcePath", sourcePath)
            .put("sourceTask", sourceTask)
}

/**
 * Only explicitly imported or published copies live here. No arbitrary path is exposed to viewers.
 */
class AttachmentStore(private val context: Context) {
    private val root = File(context.filesDir, "attachments").canonicalFile

    companion object {
        const val MAX_BYTES = 32 * 1024 * 1024
        private val ids = Regex("[a-f0-9-]{36}")

        fun valid(id: String) = id.matches(ids)
    }

    private fun directory(id: String): File {
        require(valid(id)) { "文件 ID 无效" }
        val dir = File(root, id)
        require(dir.canonicalFile == dir.absoluteFile) { "附件路径不能包含链接" }
        return dir
    }

    fun get(id: String): LocalAttachment {
        val dir = directory(id)
        val value = JSONObject(File(dir, "metadata.json").readText())
        require(value.getString("id") == id && valid(value.getString("sessionId")))
        return LocalAttachment(
            id,
            value.getString("sessionId"),
            value.getString("name"),
            value.getString("mime"),
            value.getLong("size"),
            value.optString("sourcePath"),
            value.optString("sourceTask"),
        )
    }

    fun file(id: String): File {
        val target = File(directory(id), "content")
        require(target.canonicalFile == target.absoluteFile && target.isFile) { "文件不存在" }
        return target
    }

    fun recordSource(id: String, source: String, taskId: String) {
        val file = AtomicFile(File(directory(id), "metadata.json"))
        val data =
            JSONObject(String(file.readFully())).put("sourcePath", source).put("sourceTask", taskId)
        val output = file.startWrite()
        try {
            output.write(data.toString().toByteArray())
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    fun all(): List<LocalAttachment> =
        root.listFiles().orEmpty().mapNotNull { runCatching { get(it.name) }.getOrNull() }

    fun import(uri: Uri, sessionId: String): LocalAttachment {
        var name = uri.lastPathSegment ?: "附件"
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use {
                if (it.moveToFirst()) name = it.getString(0) ?: name
            }
        val mime = mime(name, context.contentResolver.getType(uri))
        return context.contentResolver.openInputStream(uri)!!.use {
            save(it, sessionId, name, mime)
        }
    }

    fun save(
        input: InputStream,
        sessionId: String,
        name: String,
        mime: String,
        id: String = UUID.randomUUID().toString(),
    ): LocalAttachment {
        require(valid(sessionId)) { "会话 ID 无效" }
        val dir = directory(id)
        require(!dir.exists()) { "文件已存在" }
        check(dir.mkdirs()) { "无法创建附件目录" }
        try {
            var count = 0L
            File(dir, "content").outputStream().use { output ->
                val buffer = ByteArray(16384)
                while (true) {
                    val size = input.read(buffer)
                    if (size < 0) break
                    count += size
                    require(count <= MAX_BYTES) { "单个附件最多 32MB" }
                    output.write(buffer, 0, size)
                }
            }
            val safeName =
                name
                    .substringAfterLast('/')
                    .substringAfterLast('\\')
                    .replace(Regex("[\\p{Cntrl}]"), "")
                    .take(180)
                    .ifBlank { "附件" }
            val info = LocalAttachment(id, sessionId, safeName, mime(safeName, mime), count)
            val atomic = AtomicFile(File(dir, "metadata.json"))
            val stream = atomic.startWrite()
            try {
                stream.write(info.json().toString().toByteArray())
                atomic.finishWrite(stream)
            } catch (error: Exception) {
                atomic.failWrite(stream)
                throw error
            }
            context.contentResolver.notifyChange(AttachmentDocuments.rootUri(context), null)
            return info
        } catch (error: Exception) {
            // The directory is newly created and its UUID was checked above.
            dir.deleteRecursively()
            throw error
        }
    }

    fun legacyImage(id: String, sessionId: String): LocalAttachment {
        runCatching { get(id) }
            .getOrNull()
            ?.let {
                return it
            }
        require(valid(id))
        val original = File(context.filesDir, "screens/$id.jpg")
        require(original.isFile && original.canonicalFile == original.absoluteFile)
        return original.inputStream().use { save(it, sessionId, "图片.jpg", "image/jpeg", id) }
    }

    fun text(id: String, maxChars: Int = 100000): String =
        DocumentText.read(file(id), get(id), maxChars)

    private fun mime(name: String, declared: String?): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: declared?.takeIf { it.contains('/') && !it.contains('\n') }
            ?: "application/octet-stream"
    }
}
