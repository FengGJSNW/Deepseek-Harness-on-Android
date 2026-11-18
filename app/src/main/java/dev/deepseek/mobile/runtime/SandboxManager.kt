package dev.deepseek.mobile.runtime

import android.content.Context
import android.util.AtomicFile
import dev.deepseek.mobile.AssistantState
import dev.deepseek.mobile.PhoneTask
import dev.deepseek.mobile.harness.HarnessCoordinator
import dev.deepseek.mobile.harness.WorkspaceScope
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

internal enum class SandboxOwner(val title: String) {
    GLOBAL("全局"),
    PROJECT("项目"),
    SESSION("会话"),
}

internal fun sandboxAttachmentPath(id: String, name: String): String {
    UUID.fromString(id)
    val safe = name.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").take(120).trim()
    return "imports/$id/" + (safe.takeUnless { it in setOf("", ".", "..") } ?: "attachment")
}

internal data class SandboxRecord(
    val id: String,
    val name: String,
    val base: RuntimeKind,
    val owner: SandboxOwner,
    val ownerId: String,
    val createdAt: Long = System.currentTimeMillis(),
    val projectLinks: Set<String> = emptySet(),
    val projectGrants: Set<String> = emptySet(),
    val sessionGrants: Set<String> = emptySet(),
    val sessionDenials: Set<String> = emptySet(),
    val internet: Boolean = false,
    val diagnostics: String = "",
    val deleting: Boolean = false,
    val preset: SandboxPreset = SandboxPreset.defaults(base),
) {
    val scope
        get() = WorkspaceScope("sandbox", id)

    fun allows(session: String, project: String): Boolean =
        !deleting &&
            session !in sessionDenials &&
            (session in sessionGrants || project.isNotBlank() && project in projectGrants)

    fun json() =
        JSONObject()
            .put("id", id)
            .put("name", name)
            .put("base", base.id)
            .put("owner", owner.name)
            .put("ownerId", ownerId)
            .put("createdAt", createdAt)
            .put("links", JSONArray(projectLinks.toList()))
            .put("projects", JSONArray(projectGrants.toList()))
            .put("sessions", JSONArray(sessionGrants.toList()))
            .put("denied", JSONArray(sessionDenials.toList()))
            .put("internet", internet)
            .put("diagnostics", diagnostics)
            .put("deleting", deleting)
            .put("preset", preset.json())

    companion object {
        fun from(j: JSONObject) =
            SandboxRecord(
                j.getString("id").also { UUID.fromString(it) },
                j.getString("name"),
                RuntimeKind.of(j.getString("base")),
                SandboxOwner.valueOf(j.getString("owner")),
                j.getString("ownerId"),
                j.getLong("createdAt"),
                AssistantState.strings(j.optJSONArray("links") ?: JSONArray()).toSet(),
                AssistantState.strings(j.optJSONArray("projects") ?: JSONArray()).toSet(),
                AssistantState.strings(j.optJSONArray("sessions") ?: JSONArray()).toSet(),
                AssistantState.strings(j.optJSONArray("denied") ?: JSONArray()).toSet(),
                j.optBoolean("internet"),
                j.optString("diagnostics"),
                j.optBoolean("deleting"),
                SandboxPreset.from(j.optJSONObject("preset"), RuntimeKind.of(j.getString("base"))),
            )
    }
}

/**
 * User-owned registry. Authorizations are checked again immediately before each guest operation.
 */
internal class SandboxManager private constructor(private val context: Context) {
    private val file = AtomicFile(File(context.filesDir, "sandboxes.json"))
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _records = MutableStateFlow(read())
    val records = _records.asStateFlow()
    private val _operations = MutableStateFlow<Map<String, String>>(emptyMap())
    val operations = _operations.asStateFlow()
    private val _errors = MutableStateFlow<Map<String, String>>(emptyMap())
    val errors = _errors.asStateFlow()
    private val leases = mutableMapOf<String, MutableSet<Job>>()
    private val connections = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val activeConnections = connections.asStateFlow()

    private fun read(): List<SandboxRecord> {
        if (!file.baseFile.isFile && !File(file.baseFile.path + ".bak").isFile) return emptyList()
        val values = JSONArray(file.readFully().decodeToString())
        return List(values.length()) { SandboxRecord.from(values.getJSONObject(it)) }
    }

    private fun write(records: List<SandboxRecord>) {
        val out = file.startWrite()
        try {
            out.write(JSONArray(records.map(SandboxRecord::json)).toString().toByteArray())
            file.finishWrite(out)
        } catch (error: Throwable) {
            file.failWrite(out)
            throw error
        }
        _records.value = records
    }

    fun get(id: String): SandboxRecord? = records.value.firstOrNull { it.id == id }

    fun describe(record: SandboxRecord, task: PhoneTask): JSONObject {
        val runtime = HarnessCoordinator.get(context).runtimes.info(record.scope)
        return JSONObject()
            .put("id", record.id)
            .put("name", record.name)
            .put("base", record.preset.profile.title)
            .put("phase", runtime.phase)
            .put("detail", runtime.detail)
            .put("workspace", "/workspace")
            .put(
                "disks",
                JSONArray(
                    SandboxDisks.get(context).mounted(record.id).map {
                        JSONObject()
                            .put("id", it.id)
                            .put("name", it.name)
                            .put("mount", "/disks/${it.id}")
                    },
                ),
            )
            .put(
                "connected",
                task.sandboxId == record.id || record.id in connections.value[task.id].orEmpty(),
            )
            .put("diagnostics", record.diagnostics.substringBefore("\n\n").take(650))
    }

    fun connect(id: String, task: PhoneTask): JSONObject =
        synchronized(lock) {
            val record = requireAccess(id, task)
            val runtime = HarnessCoordinator.get(context).runtimes.info(record.scope)
            check(runtime.phase in setOf("ready", "suspended", "running", "saving")) {
                "Sandbox 未就绪，请先在管理页启动"
            }
            connections.value += task.id to (connections.value[task.id].orEmpty() + id)
            describe(record, task)
        }

    fun isConnected(id: String, task: PhoneTask) =
        task.sandboxId == id || id in connections.value[task.id].orEmpty()

    fun finish(taskId: String) {
        synchronized(lock) { connections.value -= taskId }
    }

    fun create(
        name: String,
        base: RuntimeKind,
        owner: SandboxOwner,
        session: String,
        project: String,
        preset: SandboxPreset = SandboxPreset.defaults(base),
    ): SandboxRecord {
        val title = name.trim()
        require(title.length in 1..60 && !title.any(Char::isISOControl)) { "请输入 1–60 个字符的名称" }
        require(base == RuntimeKind.LIGHT || base == RuntimeKind.ALPINE)
        require(owner != SandboxOwner.PROJECT || project.isNotBlank()) { "当前会话未加入项目" }
        val result =
            synchronized(lock) {
                require(records.value.none { it.name.equals(title, true) }) { "已有同名 Sandbox" }
                SandboxRecord(
                        UUID.randomUUID().toString(),
                        title,
                        base,
                        owner,
                        when (owner) {
                            SandboxOwner.GLOBAL -> ""
                            SandboxOwner.PROJECT -> project
                            SandboxOwner.SESSION -> session
                        },
                        projectLinks =
                            if (owner == SandboxOwner.PROJECT) setOf(project) else emptySet(),
                        projectGrants = emptySet(),
                        sessionGrants = emptySet(),
                        preset = preset,
                    )
                    .also { write(records.value + it) }
            }
        start(result.id)
        return result
    }

    fun change(id: String, transform: (SandboxRecord) -> SandboxRecord) {
        synchronized(lock) {
            val old = requireNotNull(get(id))
            check(!old.deleting)
            val value = transform(old)
            write(records.value.map { if (it.id == id) value else it })
            if (
                !value.projectGrants.containsAll(old.projectGrants) ||
                    !value.sessionGrants.containsAll(old.sessionGrants) ||
                    !old.sessionDenials.containsAll(value.sessionDenials) ||
                    old.internet && !value.internet
            )
                cancelLeases(id)
        }
    }

    fun allowSession(id: String, session: String, allow: Boolean) =
        change(id) { old ->
            old.copy(
                sessionGrants =
                    if (allow) old.sessionGrants + session else old.sessionGrants - session,
                sessionDenials =
                    if (allow) old.sessionDenials - session else old.sessionDenials + session,
            )
        }

    fun requireAccess(id: String, task: PhoneTask): SandboxRecord {
        val record = get(id) ?: error("指定的 Sandbox 已删除或不存在")
        val session =
            AssistantState.sessions.firstOrNull { it.id == task.sessionId } ?: error("会话已删除")
        check(task.acceptsSandbox(id)) { "此 Sandbox 不在本任务获准访问的范围内" }
        check(record.allows(session.id, session.projectId)) { "当前会话没有此 Sandbox 的使用授权" }
        check(_operations.value[id] !in setOf("停止中", "重启中", "删除中")) { "Sandbox 正在切换状态" }
        return record
    }

    suspend fun <T> withAccess(id: String, task: PhoneTask, block: suspend () -> T): T =
        coroutineScope {
            val job = coroutineContext.job
            synchronized(lock) {
                requireAccess(id, task)
                leases.getOrPut(id) { mutableSetOf() }.add(job)
            }
            try {
                block()
            } finally {
                synchronized(lock) { leases[id]?.remove(job) }
            }
        }

    fun cancelDiskLeases() =
        synchronized(lock) {
            leases.keys.toList().forEach(::cancelLeases)
        }

    private fun cancelLeases(id: String) {
        leases.remove(id)?.forEach { it.cancel(CancellationException("Sandbox 已停止或授权已改变")) }
        connections.value =
            connections.value.mapValues { it.value - id }.filterValues { it.isNotEmpty() }
    }

    private fun operate(id: String, label: String, block: suspend (SandboxRecord) -> Unit) {
        val record =
            synchronized(lock) {
                if (id in _operations.value) return
                val record = get(id) ?: return
                _operations.value += id to label
                _errors.value -= id
                if (label in setOf("停止中", "重启中", "删除中")) cancelLeases(id)
                record
            }
        scope.launch {
            try {
                block(record)
            } catch (error: Exception) {
                _errors.value += id to (error.message ?: "操作失败")
            } finally {
                _operations.value -= id
            }
        }
    }

    fun start(id: String) =
        operate(id, "启动中") { record ->
            check(!record.deleting) { "此实例正在等待完成删除" }
            val result = HarnessCoordinator.get(context).runtimes.startManaged(record)
            change(id) { it.copy(diagnostics = result) }
        }

    fun stop(id: String) =
        operate(id, "停止中") {
            HarnessCoordinator.get(context).runtimes.stopManaged(it.scope)
        }

    fun restart(id: String) =
        operate(id, "重启中") { record ->
            val runtimes = HarnessCoordinator.get(context).runtimes
            runtimes.stopManaged(record.scope)
            val result = runtimes.startManaged(record)
            change(id) { it.copy(diagnostics = result) }
        }

    fun delete(id: String) =
        operate(id, "删除中") { record ->
            synchronized(lock) {
                write(records.value.map { if (it.id == id) it.copy(deleting = true) else it })
            }
            HarnessCoordinator.get(context).runtimes.delete(record.scope)
            synchronized(lock) { write(records.value.filterNot { it.id == id }) }
            AssistantState.projects
                .filter { it.defaultSandboxId == id }
                .forEach { it.defaultSandboxId = "" }
            AssistantState.sessions
                .filter { it.sandboxId == id }
                .forEach {
                    it.sandboxId = ""
                }
            AssistantState.persist()
        }

    companion object {
        @Volatile private var instance: SandboxManager? = null

        fun get(context: Context): SandboxManager =
            instance
                ?: synchronized(this) {
                    instance ?: SandboxManager(context.applicationContext).also { instance = it }
                }
    }
}
