package dev.deepseek.mobile.runtime

import android.content.Context
import android.util.AtomicFile
import dev.deepseek.mobile.PhoneTask
import dev.deepseek.mobile.harness.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** The only writer of the shared toolchain template. Project package installs remain local. */
internal class PackageManager(
    context: Context,
    private val permissions: PermissionManager,
    private val runtime: RuntimeManager,
) {
    private val root = File(context.filesDir, "harness/package-requests").apply { mkdirs() }
    private val lock = Mutex()

    init {
        requests()
            .filter { it.optString("status") in setOf("pending", "installing") }
            .forEach { update(it.put("status", "interrupted")) }
    }

    fun request(task: PhoneTask, packages: List<String>): JSONObject {
        validate(packages)
        val policy =
            PermissionContext(
                sessionId = task.sessionId,
                projectId = task.projectId,
                actor = "package-manager",
            )
        check(
            permissions
                .evaluate(policy, Capability.GLOBAL_TOOLCHAIN, packages.joinToString(","))
                .effect != PermissionEffect.DENY,
        ) {
            "规则禁止全局工具链安装"
        }
        return JSONObject()
            .put("id", UUID.randomUUID().toString())
            .put("sessionId", task.sessionId)
            .put("projectId", task.projectId)
            .put("packages", JSONArray(packages))
            .put("status", "pending")
            .put("at", System.currentTimeMillis())
            .also { update(it) }
    }

    /** Only called after a concrete native human approval or a settings confirmation button. */
    suspend fun installAfterUserApproval(id: String): JSONObject = lock.withLock {
        UUID.fromString(id)
        val item = JSONObject(String(AtomicFile(File(root, "$id.json")).readFully()))
        check(item.getString("status") in setOf("pending", "interrupted", "failed"))
        val values = item.getJSONArray("packages")
        val packages = (0 until values.length()).map { values.getString(it) }
        validate(packages)
        val policy =
            PermissionContext(
                sessionId = item.getString("sessionId"),
                projectId = item.optString("projectId"),
                actor = "package-manager",
            )
        check(
            permissions
                .evaluate(policy, Capability.GLOBAL_TOOLCHAIN, packages.joinToString(","))
                .effect != PermissionEffect.DENY,
        )
        check(
            permissions.evaluate(policy, Capability.NETWORK).effect == PermissionEffect.ALLOW &&
                !permissions.requiresBroker(policy, setOf(Capability.NETWORK)),
        ) {
            "工具链下载未获网络权限"
        }
        update(item.put("status", "installing"))
        try {
            val result = runtime.installGlobalPackages(policy, packages)
            update(
                item
                    .put("status", "installed")
                    .put("result", result)
                    .put("finishedAt", System.currentTimeMillis()),
            )
            result
        } catch (error: Throwable) {
            update(
                item
                    .put("status", if (error is CancellationException) "interrupted" else "failed")
                    .put("error", error.message.orEmpty().take(500)),
            )
            throw error
        }
    }

    fun deny(id: String) {
        UUID.fromString(id)
        val item = JSONObject(String(AtomicFile(File(root, "$id.json")).readFully()))
        check(item.optString("status") != "installing")
        update(item.put("status", "denied"))
    }

    fun requests(): List<JSONObject> =
        root
            .listFiles()
            .orEmpty()
            .filter { it.extension == "json" }
            .mapNotNull {
                runCatching { JSONObject(String(AtomicFile(it).readFully())) }.getOrNull()
            }
            .sortedByDescending { it.optLong("at") }
            .take(100)

    private fun update(item: JSONObject) =
        synchronized(root.path.intern()) {
            val atomic = AtomicFile(File(root, item.getString("id") + ".json"))
            val out = atomic.startWrite()
            try {
                out.write(item.toString().toByteArray())
                atomic.finishWrite(out)
            } catch (error: Throwable) {
                atomic.failWrite(out)
                throw error
            }
        }

    companion object {
        fun validate(packages: List<String>) {
            require(packages.size in 1..20 && packages.distinct().size == packages.size)
            require(packages.all { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9+_.-]{0,79}")) }) {
                "只接受包名称，不接受命令、参数或安装脚本"
            }
        }
    }
}
