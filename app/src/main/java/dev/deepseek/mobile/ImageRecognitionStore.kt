package dev.deepseek.mobile

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/** Private, image-scoped search metadata. These descriptions are data, never user instructions. */
internal class ImageRecognitionStore(private val context: Context) {
    private val root = File(context.filesDir.canonicalFile, "image-recognition")

    companion object {
        private val changes = MutableStateFlow(0L)
        val revision = changes.asStateFlow()
        private val digestPattern = Regex("[a-f0-9]{64}")
    }

    private fun recordFile(id: String): File {
        require(AttachmentStore.valid(id)) { "图片引用无效" }
        val file = File(root, "$id.json")
        require(file.canonicalFile == file.absoluteFile) { "识别缓存路径无效" }
        return file
    }

    private fun read(id: String, sessionId: String): JSONObject? = runCatching {
        val file = recordFile(id)
        if (!file.isFile || file.length() > 100_000) return@runCatching null
        val record = JSONObject(String(AtomicFile(file).readFully(), Charsets.UTF_8))
        record.takeIf {
            it.optInt("version") == 1 &&
                it.optString("id") == id &&
                it.optString("sessionId") == sessionId &&
                it.optString("text").length in 1..16000
        }
    }
        .getOrNull()

    fun text(id: String, sessionId: String): String =
        read(id, sessionId)?.optString("text").orEmpty()

    private fun imageHash(id: String): String {
        require(AttachmentStore.valid(id))
        val file = File(context.filesDir.canonicalFile, "screens/$id.jpg")
        require(file.isFile && file.canonicalFile == file.absoluteFile) { "图片缓存已失效" }
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(16384)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    fun exchange(task: PhoneTask, args: JSONObject, checkActive: () -> Unit): JSONObject {
        checkActive()
        require(args.getString("taskId") == task.id && args.optString("_agentId").isBlank()) {
            "识别缓存不属于当前用户任务"
        }
        require(AttachmentStore.valid(task.sessionId))
        val allowed = task.images.toSet()
        val ids = AssistantState.strings(args.optJSONArray("ids"))
        require(ids.size <= 4 && ids.size == ids.distinct().size && ids.all { it in allowed }) {
            "只能索引本次用户发送的图片"
        }
        return when (args.getString("action")) {
            "pending" -> {
                val pending = JSONArray()
                ids.forEach { id ->
                    checkActive()
                    val hash = imageHash(id)
                    if (read(id, task.sessionId)?.optString("sha256") != hash)
                        pending.put(JSONObject().put("id", id).put("sha256", hash))
                }
                JSONObject().put("pending", pending)
            }
            "save" -> {
                val rows = args.getJSONArray("entries")
                require(rows.length() in 1..4)
                val entries = (0 until rows.length()).map { rows.getJSONObject(it) }
                val requestedIds = entries.map { it.getString("id") }
                require(requestedIds.size == requestedIds.distinct().size)
                // Validate the entire batch before touching any cache file.
                entries.forEach {
                    val id = it.getString("id")
                    require(id in allowed && AttachmentStore.valid(id))
                    require(it.getString("text").length in 1..16000)
                    require(it.getString("sha256").matches(digestPattern))
                    check(it.getString("sha256") == imageHash(id)) { "图片已变化，请重新识别" }
                }
                checkActive()
                check(root.isDirectory || root.mkdirs())
                entries.forEach { entry ->
                    checkActive()
                    val id = entry.getString("id")
                    val record =
                        JSONObject()
                            .put("version", 1)
                            .put("id", id)
                            .put("sessionId", task.sessionId)
                            .put("sha256", entry.getString("sha256"))
                            .put("text", entry.getString("text"))
                            .put("updatedAt", System.currentTimeMillis())
                    val file = AtomicFile(recordFile(id))
                    val stream = file.startWrite()
                    try {
                        stream.write(record.toString().toByteArray(Charsets.UTF_8))
                        checkActive()
                        file.finishWrite(stream)
                    } catch (error: Exception) {
                        file.failWrite(stream)
                        throw error
                    }
                }
                changes.update { it + 1 }
                JSONObject().put("saved", entries.size)
            }
            else -> error("未知的图片识别缓存操作")
        }
    }
}
