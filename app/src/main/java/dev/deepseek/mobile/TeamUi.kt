package dev.deepseek.mobile

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

internal class AgentWork(
    val id: String,
    val sessionId: String,
    val name: String,
    val parent: String,
) {
    var taskId by mutableStateOf("")
    var status by mutableStateOf("running")
    var model by mutableStateOf("deepseek-flash")
    var effort by mutableStateOf("low")
    val messages = mutableStateListOf<ChatEntry>()
}

internal object TeamState {
    val agents = mutableStateListOf<AgentWork>()

    fun event(sessionId: String, e: JSONObject) {
        val id = e.getString("id")
        val work =
            agents.firstOrNull { it.id == id }
                ?: AgentWork(id, sessionId, e.optString("name", "智能体"), e.optString("parent"))
                    .also { agents.add(it) }
        val type = e.getString("type")
        when (type) {
            "created" -> {
                work.taskId = AssistantState.activeTaskId
                val value = e.getJSONObject("value")
                work.model = value.optString("model", "deepseek-flash")
                work.effort = value.optString("effort", "low")
                work.messages.add(ChatEntry("user", value.optString("task")))
            }
            "status" -> work.status = e.getString("value")
            "assistant" -> settleStream(work.messages, "assistant", e.getString("value"))
            "tool" ->
                if (e.getJSONObject("value").optString("name") != "terminal_exec")
                    work.messages.add(
                        ChatEntry(
                            "tool",
                            e.getJSONObject("value").put("status", "running").toString(),
                        ),
                    )
            "tool_result" -> {
                val result = e.getJSONObject("value")
                finishTool(work.messages, result)
                val file =
                    result
                        .optJSONObject("result")
                        ?.optString("preview")
                        .orEmpty()
                        .substringAfterLast('/')
                if (AttachmentStore.valid(file))
                    work.messages.add(ChatEntry("file", "", files = listOf(file)))
            }
            "error" -> {
                work.status = "failed"
                work.messages.add(ChatEntry("system", e.getString("value")))
            }
        }
    }

    fun persist(context: android.content.Context) {
        val root =
            JSONArray(
                agents.map { a ->
                    JSONObject()
                        .put("id", a.id)
                        .put("sessionId", a.sessionId)
                        .put("taskId", a.taskId)
                        .put("name", a.name)
                        .put("parent", a.parent)
                        .put("status", a.status)
                        .put("model", a.model)
                        .put("effort", a.effort)
                        .put(
                            "messages",
                            JSONArray(
                                a.messages.map {
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
            )
        val atomic = android.util.AtomicFile(File(context.filesDir, "agents-v1.json"))
        val output = atomic.startWrite()
        try {
            output.write(root.toString().toByteArray())
            atomic.finishWrite(output)
        } catch (e: Exception) {
            atomic.failWrite(output)
            AssistantState.error = "智能体记录保存失败"
        }
    }

    fun load(context: android.content.Context) {
        agents.clear()
        runCatching {
            val file = android.util.AtomicFile(File(context.filesDir, "agents-v1.json"))
            if (!file.baseFile.exists()) return
            val values = JSONArray(String(file.readFully()))
            for (i in 0 until values.length()) {
                val v = values.getJSONObject(i)
                val work =
                    AgentWork(
                        v.getString("id"),
                        v.getString("sessionId"),
                        v.getString("name"),
                        v.optString("parent"),
                    )
                work.taskId = v.optString("taskId")
                work.model = v.optString("model", "deepseek-flash")
                work.effort = v.optString("effort", "low")
                work.status =
                    v.optString("status").let { if (it == "running") "interrupted" else it }
                val messages = v.getJSONArray("messages")
                for (j in 0 until messages.length()) messages.getJSONObject(j).let {
                    work.messages.add(
                        ChatEntry(
                            it.getString("type"),
                            it.getString("text"),
                            images =
                                (0 until (it.optJSONArray("images")?.length() ?: 0)).map { i ->
                                    it.getJSONArray("images").getString(i)
                                },
                            files =
                                (0 until (it.optJSONArray("files")?.length() ?: 0)).map { i ->
                                    it.getJSONArray("files").getString(i)
                                },
                            taskId = it.optString("taskId"),
                        ),
                    )
                }
                agents.add(work)
            }
        }
            .onFailure { AssistantState.error = "智能体记录读取失败，原文件已保留" }
    }
}

internal fun settleStream(
    messages: MutableList<ChatEntry>,
    type: String,
    text: String,
    taskId: String = "",
) {
    val index = messages.indexOfLast { it.type == "stream_$type" }
    if (index >= 0)
        messages[index] =
            messages[index].copy(
                type = type,
                text = text,
                taskId = taskId.ifBlank { messages[index].taskId },
            )
    else messages.add(ChatEntry(type, text, taskId = taskId))
}

internal fun finishTool(messages: MutableList<ChatEntry>, value: JSONObject) {
    val index = messages.indexOfLast {
        it.type == "tool" &&
            JSONObject(it.text).optString("status") == "running" &&
            (if (value.optString("id").isNotBlank())
                JSONObject(it.text).optString("id") == value.optString("id")
            else JSONObject(it.text).optString("name") == value.optString("name"))
    }
    if (index >= 0)
        messages[index] =
            ChatEntry(
                "tool",
                JSONObject(messages[index].text)
                    .put("status", if (value.optBoolean("ok")) "done" else "failed")
                    .put("result", value.opt("result"))
                    .put("summary", value.optString("summary"))
                    .toString(),
                taskId = messages[index].taskId,
            )
}

private val agentIcons =
    listOf(
        AppIcons.Spark,
        AppIcons.Bolt,
        AppIcons.Hub,
        AppIcons.Checklist,
        AppIcons.Code,
        AppIcons.Science,
        AppIcons.BubbleChart,
        AppIcons.Search,
    )
private val agentColors =
    listOf(
        Color(0xFF6057B1),
        Color(0xFF9A621F),
        Color(0xFF327769),
        Color(0xFF3B65A7),
        Color(0xFF985477),
        Color(0xFF83682F),
        Color(0xFF49697A),
        Color(0xFF626262),
    )

internal fun agentIcon(index: Int) = agentIcons[index % agentIcons.size]

internal fun agentColor(index: Int) = agentColors[index % agentColors.size]

@Composable
internal fun TeamList(agents: List<AgentWork>) {
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Text("子 Agent", Modifier.padding(vertical = 14.dp), fontSize = 17.sp)
        if (agents.isEmpty())
            Text(
                "当前会话启动的子 Agent 将显示在这里。",
                Modifier.padding(vertical = 12.dp),
                fontSize = 13.sp,
                color = WhaleMuted,
            )
        LazyColumn(Modifier.fillMaxWidth()) {
            items(agents.size, key = { agents[it].id }) { index ->
                val work = agents[index]
                Row(
                    Modifier.fillMaxWidth()
                        .clickable {
                            WorkspaceState.open(WorkspaceTab.Agent(work.id))
                        }
                        .padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(agentIcon(index), null, Modifier.size(21.dp), tint = agentColor(index))
                    Text(
                        work.name,
                        Modifier.weight(1f).padding(horizontal = 12.dp),
                        fontSize = 14.sp,
                    )
                    Icon(AppIcons.ChevronRight, null, Modifier.size(16.dp), tint = WhaleMuted)
                }
                HorizontalDivider(color = WhaleLine)
            }
        }
    }
}

@Composable
internal fun TeamConversation(work: AgentWork) {
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SmallIcon(AppIcons.ArrowBack, "返回子 Agent 列表") {
                WorkspaceState.open(WorkspaceTab.Agents)
            }
            Column(Modifier.weight(1f)) {
                Text(work.name, fontSize = 16.sp)
                Text(
                    statusName(work.status) +
                        " · " +
                        (if (work.model == "deepseek-flash") "Flash" else "Pro") +
                        " · " +
                        work.effort,
                    fontSize = 12.sp,
                    color = WhaleMuted,
                )
            }
            if (work.status == "running")
                TextButton(onClick = { AgentService.current?.stopChild(work.id) }) { Text("停止") }
        }
        HorizontalDivider(color = WhaleLine)
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(vertical = 12.dp),
        ) {
            item {
                AgentTimeline(
                    work.messages.toList(),
                    work.name,
                    showHeader = false,
                    owner = work.id,
                    completed = work.status == "done",
                )
            }
        }
    }
}
