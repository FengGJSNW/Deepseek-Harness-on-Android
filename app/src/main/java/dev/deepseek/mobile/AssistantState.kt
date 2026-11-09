package dev.deepseek.mobile

import android.content.Context
import android.util.AtomicFile
import androidx.compose.runtime.*
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

data class ChatEntry(
    val type: String,
    val text: String,
    val images: List<String> = emptyList(),
    val files: List<String> = emptyList(),
    val taskId: String = "",
)

class Conversation(val id: String = UUID.randomUUID().toString(), title: String = "新对话") {
    var projectId by mutableStateOf("")
    var updatedAt by mutableLongStateOf(System.currentTimeMillis())
    var title by mutableStateOf(title)
    var draft by mutableStateOf("")
    var model by mutableStateOf("deepseek-flash")
    var effort by mutableStateOf("low")
    var childModel by mutableStateOf("deepseek-flash")
    var childEffort by mutableStateOf("low")
    var access by mutableStateOf("ask")
    var phoneTools by mutableStateOf(true)
    var fileTools by mutableStateOf(true)
    var workspace by mutableStateOf("")
    var terminalEnabled by mutableStateOf(true)
    var terminalLevel by mutableStateOf("sandbox")
    internal var browserProvider by mutableStateOf(BrowserProvider.LOCAL)
    internal var creatorMode by mutableStateOf(false)
    val files = mutableStateListOf<String>()
    val images = mutableStateListOf<String>()
    val messages = mutableStateListOf<ChatEntry>()
}

class PhoneTask(
    val id: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val text: String,
    val images: List<String>,
    val model: String,
    val effort: String,
    val access: String,
    val phoneTools: Boolean,
    val fileTools: Boolean,
    val workspace: String,
    val terminalEnabled: Boolean = false,
    val terminalConfig: String = "",
    val projectId: String = "",
    val files: List<String> = emptyList(),
    val childModel: String = "deepseek-flash",
    val childEffort: String = "low",
    val browserProvider: String = "local",
    val creatorMode: Boolean = false,
) {
    var status by mutableStateOf("queued")
    var progress by mutableStateOf("等待执行")
    internal var avatarPhase by mutableStateOf(AvatarPhase.THINKING)
    var question by mutableStateOf("")
    var approval by mutableStateOf(false)
    var terminalApproval by mutableStateOf(false)
    var tokens by mutableStateOf(0)
    val steps = mutableStateListOf<String>()

    fun json() =
        JSONObject()
            .put("id", id)
            .put("sessionId", sessionId)
            .put("text", text)
            .put("images", JSONArray(images))
            .put("model", model)
            .put("effort", effort)
            .put("childModel", childModel)
            .put("childEffort", childEffort)
            .put("browserProvider", browserProvider)
            .put("creatorMode", creatorMode)
            .put("access", access)
            .put("phoneTools", phoneTools)
            .put("fileTools", fileTools)
            .put("workspace", workspace)
            .put("terminalEnabled", terminalEnabled)
            .put("terminalConfig", terminalConfig)
            .put("projectId", projectId)
            .put("files", JSONArray(files))
            .put("status", status)
            .put("progress", progress)
            .put("tokens", tokens)
            .put("steps", JSONArray(steps))
}

object AssistantState {
    var ready by mutableStateOf(false)
    var configured by mutableStateOf(false)
    var accessibility by mutableStateOf(false)
    var showAccessibilityInfo by mutableStateOf(false)
    var notifications by mutableStateOf(false)
    var overlay by mutableStateOf(false)
    var queuePaused by mutableStateOf(false)
    var selectedId by mutableStateOf("")
    var activeTaskId by mutableStateOf("")
    var error by mutableStateOf("")
    var petEnabled by mutableStateOf(true)
    var petStyle by mutableStateOf("ohjing")
    var petSoundEnabled by mutableStateOf(true)
    var theme by mutableStateOf("official")
    var appMode by mutableStateOf("work")
    val credentials = mutableStateListOf<CredentialInfo>()
    var balance by mutableStateOf("未查询")
    var balanceUpdatedAt by mutableLongStateOf(0L)
    var balanceRefreshing by mutableStateOf(false)
    var totalTokens by mutableStateOf(0)
    val sessions = mutableStateListOf<Conversation>()
    val tasks = mutableStateListOf<PhoneTask>()
    val projects = mutableStateListOf<WorkProject>()
    var pendingMemory by mutableStateOf<MemoryProposal?>(null)
    val current
        get() = sessions.firstOrNull { it.id == selectedId } ?: sessions.first()

    val running
        get() = tasks.firstOrNull { it.id == activeTaskId }

    val busy
        get() = running != null

    val messages
        get() = current.messages

    val steps
        get() =
            running?.steps
                ?: tasks.lastOrNull { it.sessionId == selectedId }?.steps
                ?: mutableStateListOf<String>()

    var question: String
        get() = running?.question.orEmpty()
        set(value) {
            running?.question = value
        }

    val progress
        get() = running?.progress.orEmpty()

    private var context: Context? = null
    private val diskWriter = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val diskLock = Any()
    private var pendingSnapshot: Pair<Context, String>? = null
    private var writing = false

    internal fun awaitPersistence() {
        diskWriter.submit {}.get()
    }

    fun initialize(ctx: Context) {
        awaitPersistence()
        context = ctx.applicationContext
        ExperienceSettings.initialize(ctx)
        TeamState.load(ctx)
        balance = "未查询"
        balanceUpdatedAt = 0L
        balanceRefreshing = false
        ready = false
        activeTaskId = ""
        sessions.clear()
        tasks.clear()
        projects.clear()
        pendingMemory = null
        error = ""
        try {
            val file = AtomicFile(File(ctx.filesDir, "conversations-v3.json"))
            if (file.baseFile.exists()) {
                val root = JSONObject(String(file.readFully(), Charsets.UTF_8))
                selectedId = root.optString("selected")
                petEnabled = root.optBoolean("pet", true)
                petStyle = root.optString("petStyle", "ohjing")
                petSoundEnabled = root.optBoolean("petSound", true)
                theme = root.optString("theme", "official")
                appMode = root.optString("appMode", "work")
                totalTokens = root.optInt("tokens")
                val ps = root.optJSONArray("projects") ?: JSONArray()
                for (i in 0 until ps.length()) {
                    val p = ps.getJSONObject(i)
                    if (p.optString("id").matches(Regex("[a-f0-9-]{36}")))
                        projects.add(
                            WorkProject(p.getString("id"), p.optString("title", "项目")).apply {
                                collapsed = p.optBoolean("collapsed")
                            },
                        )
                }
                val values = root.optJSONArray("sessions") ?: JSONArray()
                for (i in 0 until values.length()) {
                    val v = values.getJSONObject(i)
                    val c = Conversation(v.getString("id"), v.optString("title", "新对话"))
                    c.projectId =
                        v.optString("projectId")
                            .takeIf { id -> projects.any { it.id == id } }
                            .orEmpty()
                    c.updatedAt = v.optLong("updatedAt", System.currentTimeMillis())
                    c.draft = v.optString("draft")
                    c.model = v.optString("model", "deepseek-flash")
                    c.effort = normalizeEffort(v.optString("effort", "low"))
                    c.childModel = v.optString("childModel", "deepseek-flash")
                    c.childEffort = normalizeEffort(v.optString("childEffort", "low"), child = true)
                    c.access = v.optString("access", "ask")
                    if (c.access == "read") c.access = "none"
                    c.phoneTools = v.optBoolean("phone", true)
                    c.fileTools = v.optBoolean("files")
                    c.workspace = v.optString("workspace")
                    c.terminalEnabled = v.optBoolean("terminalEnabled")
                    c.terminalLevel =
                        v.optString("terminalLevel", "sandbox").let {
                            if (it == "isolated") "sandbox" else it
                        }
                    c.browserProvider = BrowserProvider.parse(v.optString("browserProvider"))
                    c.creatorMode = v.optBoolean("creatorMode")
                    c.images.addAll(strings(v.optJSONArray("images")))
                    c.files.addAll(strings(v.optJSONArray("attachments")))
                    val ms = v.optJSONArray("messages") ?: JSONArray()
                    for (j in 0 until ms.length()) {
                        val m = ms.getJSONObject(j)
                        c.messages.add(
                            ChatEntry(
                                m.getString("type"),
                                m.getString("text"),
                                strings(m.optJSONArray("images")),
                                strings(m.optJSONArray("files")),
                                m.optString("taskId"),
                            ),
                        )
                    }
                    sessions.add(c)
                }
                val ts = root.optJSONArray("tasks") ?: JSONArray()
                for (i in 0 until ts.length()) {
                    val v = ts.getJSONObject(i)
                    val t =
                        PhoneTask(
                            v.getString("id"),
                            v.getString("sessionId"),
                            v.getString("text"),
                            strings(v.optJSONArray("images")),
                            v.getString("model"),
                            normalizeEffort(v.getString("effort")),
                            v.getString("access"),
                            v.optBoolean("phoneTools"),
                            v.optBoolean("fileTools"),
                            v.optString("workspace"),
                            v.optBoolean("terminalEnabled"),
                            v.optString("terminalConfig"),
                            v.optString("projectId"),
                            strings(v.optJSONArray("files")),
                            v.optString("childModel", "deepseek-flash"),
                            normalizeEffort(v.optString("childEffort", "low"), child = true),
                            BrowserProvider.parse(v.optString("browserProvider")).key,
                            v.optBoolean("creatorMode"),
                        )
                    t.status = v.optString("status")
                    if (t.status in listOf("queued", "running", "waiting", "stopping")) {
                        t.status = "interrupted"
                        t.progress = "进程已结束，任务未自动重试"
                    } else t.progress = v.optString("progress")
                    t.tokens = v.optInt("tokens")
                    t.steps.addAll(strings(v.optJSONArray("steps")))
                    tasks.add(t)
                }
            }
        } catch (_: Exception) {
            val damaged = File(ctx.filesDir, "conversations-v3.json")
            try {
                if (damaged.exists())
                    damaged.copyTo(
                        File(
                            ctx.filesDir,
                            "conversations-v3-corrupt-${System.currentTimeMillis()}.json",
                        ),
                    )
                error = "会话记录读取失败，已备份原文件"
            } catch (_: Exception) {
                error = "会话记录读取失败，无法备份"
                return
            }
            sessions.clear()
            tasks.clear()
        }
        if (sessions.isEmpty()) {
            val c = Conversation()
            val old = File(ctx.filesDir, "session.jsonl")
            if (old.isFile)
                old.readLines().takeLast(300).forEach {
                    try {
                        val v = JSONObject(it)
                        c.messages.add(ChatEntry(v.getString("type"), v.getString("text")))
                    } catch (_: Exception) {}
                }
            if (c.messages.isNotEmpty()) c.title = "导入的旧会话"
            sessions.add(c)
            selectedId = c.id
        }
        if (sessions.none { it.id == selectedId }) selectedId = sessions.first().id
        queuePaused = false
        persist()
    }

    fun strings(array: JSONArray?) =
        if (array == null) emptyList() else (0 until array.length()).map { array.getString(it) }

    fun persist() {
        val ctx = context ?: return
        val root =
            JSONObject()
                .put("selected", selectedId)
                .put("pet", petEnabled)
                .put("petStyle", petStyle)
                .put("petSound", petSoundEnabled)
                .put("theme", theme)
                .put("appMode", appMode)
                .put("tokens", totalTokens)
                .put("projects", JSONArray(projects.map { it.json() }))
                .put(
                    "tasks",
                    JSONArray(
                        tasks
                            .filter {
                                it.status in listOf("queued", "running", "waiting", "stopping") ||
                                    it in tasks.takeLast(150)
                            }
                            .map { it.json() },
                    ),
                )
        root.put(
            "sessions",
            JSONArray(
                sessions.map { c ->
                    JSONObject()
                        .put("id", c.id)
                        .put("title", c.title)
                        .put("projectId", c.projectId)
                        .put("updatedAt", c.updatedAt)
                        .put("draft", c.draft)
                        .put("model", c.model)
                        .put("effort", c.effort)
                        .put("childModel", c.childModel)
                        .put("childEffort", c.childEffort)
                        .put("access", c.access)
                        .put("phone", c.phoneTools)
                        .put("files", c.fileTools)
                        .put("workspace", c.workspace)
                        .put("terminalEnabled", c.terminalEnabled)
                        .put("terminalLevel", c.terminalLevel)
                        .put("browserProvider", c.browserProvider.key)
                        .put("creatorMode", c.creatorMode)
                        .put("images", JSONArray(c.images))
                        .put("attachments", JSONArray(c.files))
                        .put(
                            "messages",
                            JSONArray(
                                c.messages.map {
                                    JSONObject()
                                        .put("type", it.type)
                                        .put("text", it.text)
                                        .put("images", JSONArray(it.images))
                                        .put("files", JSONArray(it.files))
                                        .put("taskId", it.taskId)
                                },
                            ),
                        )
                },
            ),
        )
        synchronized(diskLock) {
            pendingSnapshot = ctx to root.toString()
            if (!writing) {
                writing = true
                diskWriter.execute {
                    while (true) {
                        val next =
                            synchronized(diskLock) {
                                pendingSnapshot.also {
                                    pendingSnapshot = null
                                    if (it == null) writing = false
                                }
                            } ?: break
                        val file = AtomicFile(File(next.first.filesDir, "conversations-v3.json"))
                        var stream: java.io.FileOutputStream? = null
                        try {
                            stream = file.startWrite()
                            stream.write(next.second.toByteArray())
                            file.finishWrite(stream)
                        } catch (_: Exception) {
                            stream?.let { file.failWrite(it) }
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                error = "会话保存失败"
                            }
                        }
                    }
                }
            }
        }
    }

    fun addSession(projectId: String = ""): Conversation {
        val c = Conversation()
        c.projectId = projectId.takeIf { id -> projects.any { it.id == id } }.orEmpty()
        sessions.add(0, c)
        selectedId = c.id
        persist()
        return c
    }

    fun addProject(title: String): WorkProject {
        require(title.isNotBlank()) { "请输入项目名称" }
        val project = WorkProject(title = title.trim().take(60))
        context?.let { MemoryStore(it).read("project", project.id) }
        projects.add(0, project)
        persist()
        return project
    }

    fun moveSession(id: String, projectId: String) {
        require(projectId.isEmpty() || projects.any { it.id == projectId }) { "项目不存在" }
        check(
            tasks.none {
                it.sessionId == id && it.status in setOf("queued", "running", "waiting", "stopping")
            },
        ) {
            "请先结束此会话的任务"
        }
        sessions.first { it.id == id }.projectId = projectId
        persist()
    }

    fun removeProject(id: String) {
        check(
            tasks.none {
                it.projectId == id && it.status in setOf("queued", "running", "waiting", "stopping")
            },
        ) {
            "请先结束项目中运行的任务"
        }
        sessions.filter { it.projectId == id }.forEach { it.projectId = "" }
        projects.removeAll { it.id == id }
        // Keep the Markdown file as an inactive local archive; never discard project knowledge
        // silently.
        persist()
    }

    fun select(id: String) {
        if (sessions.any { it.id == id }) {
            selectedId = id
            persist()
        }
    }

    fun receive(type: String, json: String) {
        val value = JSONTokener(json).nextValue()
        when (type) {
            "ready" -> ready = value == true
            "status" ->
                (value as JSONObject).let {
                    configured = it.optBoolean("configured")
                    accessibility = it.optBoolean("accessibility")
                    notifications = it.optBoolean("notifications")
                    overlay = it.optBoolean("overlay")
                }
            "error" -> error = value.toString()
        }
    }
}
