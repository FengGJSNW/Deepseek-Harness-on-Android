package dev.deepseek.mobile.harness

import android.content.Context
import android.util.AtomicFile
import dev.deepseek.mobile.PhoneTask
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Restart preserves the request, never permission to execute a formerly pending operation. */
internal class ApprovalLedger(context: Context) {
    private val root = File(context.filesDir, "harness/approvals").apply { mkdirs() }
    private val lock = Any()

    init {
        list()
            .filter { it.optString("status") == "pending" }
            .forEach { settle(it.getString("id"), "interrupted") }
    }

    fun begin(task: PhoneTask, reason: String, detail: String): String =
        synchronized(lock) {
            val id = UUID.randomUUID().toString()
            save(
                id,
                JSONObject()
                    .put("id", id)
                    .put("taskId", task.id)
                    .put("sessionId", task.sessionId)
                    .put("projectId", task.projectId)
                    .put("reason", reason.take(1000))
                    .put("detail", detail.take(2000))
                    .put("status", "pending")
                    .put("at", System.currentTimeMillis()),
            )
            id
        }

    fun settle(id: String, status: String) =
        synchronized(lock) {
            require(status in setOf("approved", "rejected", "interrupted"))
            UUID.fromString(id)
            val item = JSONObject(String(AtomicFile(File(root, "$id.json")).readFully()))
            item.put("status", status).put("settledAt", System.currentTimeMillis())
            save(id, item)
        }

    fun list(): List<JSONObject> =
        synchronized(lock) {
            root
                .listFiles()
                .orEmpty()
                .filter { it.extension == "json" }
                .mapNotNull {
                    runCatching { JSONObject(String(AtomicFile(it).readFully())) }.getOrNull()
                }
                .sortedByDescending { it.optLong("at") }
                .take(200)
        }

    fun json() = JSONArray(list())

    private fun save(id: String, value: JSONObject) {
        val atomic = AtomicFile(File(root, "$id.json"))
        val out = atomic.startWrite()
        try {
            out.write(value.toString().toByteArray())
            atomic.finishWrite(out)
        } catch (error: Throwable) {
            atomic.failWrite(out)
            throw error
        }
    }
}
