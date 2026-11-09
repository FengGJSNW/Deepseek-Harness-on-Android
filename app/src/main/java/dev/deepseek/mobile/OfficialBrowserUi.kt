package dev.deepseek.mobile

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

private data class OfficialWebRecord(
    val id: String,
    val tool: OfficialWebTool,
    val heading: String,
    val owner: String,
    val result: JSONObject?,
    val status: String,
    val summary: String,
)

@Composable
internal fun OfficialBrowserWorkspace(conversation: Conversation, requested: String) {
    val records by
        remember(conversation, requested) {
            derivedStateOf {
                val sources = buildList {
                    add("" to conversation.messages)
                    TeamState.agents
                        .filter { it.sessionId == conversation.id }
                        .forEach { add(it.name to it.messages) }
                }
                sources
                    .flatMap { (owner, messages) ->
                        val sourceRecords =
                            messages
                                .asReversed()
                                .asSequence()
                                .filter { entry ->
                                    entry.type == "tool" &&
                                        OfficialWebTool.entries.any {
                                            entry.text.contains("\"${it.method}\"")
                                        }
                                }
                                .mapNotNull { entry ->
                                    val json =
                                        runCatching { JSONObject(entry.text) }.getOrNull()
                                            ?: return@mapNotNull null
                                    val tool =
                                        OfficialWebTool.fromMethod(json.optString("name"))
                                            ?: return@mapNotNull null
                                    val id =
                                        json.optString("id").ifBlank {
                                            return@mapNotNull null
                                        }
                                    val result = json.optJSONObject("result")
                                    val args = json.optJSONObject("args")
                                    val heading =
                                        when (tool) {
                                            OfficialWebTool.SEARCH ->
                                                result
                                                    ?.optString("query")
                                                    .orEmpty()
                                                    .ifBlank { args?.optString("query").orEmpty() }
                                                    .ifBlank { "搜索网页" }
                                            OfficialWebTool.FETCH ->
                                                result
                                                    ?.optString("title")
                                                    .orEmpty()
                                                    .ifBlank { args?.optString("url").orEmpty() }
                                                    .ifBlank { "抓取网页" }
                                        }
                                    OfficialWebRecord(
                                        id,
                                        tool,
                                        heading,
                                        owner,
                                        result,
                                        json.optString("status"),
                                        json.optString("summary"),
                                    )
                                }
                        val latest = sourceRecords.take(24).toList()
                        if (requested.isNotBlank() && latest.none { it.id == requested })
                            latest + listOfNotNull(sourceRecords.firstOrNull { it.id == requested })
                        else latest
                    }
                    .distinctBy { it.id }
            }
        }
    var selectedId by rememberSaveable(conversation.id, requested) { mutableStateOf(requested) }
    val selected = records.firstOrNull { it.id == selectedId }
    Column(Modifier.fillMaxSize().testTag("workspace-official-browser")) {
        if (selected == null) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)) {
                Text("DeepSeek 网页搜索与抓取", fontSize = 17.sp, fontWeight = FontWeight.Medium)
                Text(
                    "官方服务返回搜索结果和网页正文，成功调用按次计费。",
                    Modifier.padding(top = 4.dp),
                    color = WhaleMuted,
                    fontSize = 12.sp,
                )
            }
            if (records.isEmpty())
                Text(
                    "Agent 使用官方工具后，结果会显示在这里。",
                    Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                    fontSize = 13.sp,
                    color = WhaleMuted,
                )
            else
                LazyColumn(contentPadding = PaddingValues(horizontal = 18.dp)) {
                    items(records, key = { it.id }) { record ->
                        SettingsLink(
                            if (record.tool == OfficialWebTool.SEARCH) AppIcons.Search
                            else AppIcons.Description,
                            record.heading,
                            listOf(
                                    if (record.tool == OfficialWebTool.SEARCH) "网页搜索" else "网页抓取",
                                    record.owner,
                                    activityStatusLabel(record.status),
                                )
                                .filter { it.isNotBlank() }
                                .joinToString(" · "),
                        ) {
                            selectedId = record.id
                        }
                        HorizontalDivider(color = WhaleLine)
                    }
                }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SmallIcon(AppIcons.ArrowBack, "返回官方浏览记录") { selectedId = "" }
                Text(selected.heading, Modifier.weight(1f), maxLines = 2, fontSize = 16.sp)
            }
            HorizontalDivider(color = WhaleLine)
            OfficialWebResult(selected, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Composable
private fun OfficialWebResult(record: OfficialWebRecord, modifier: Modifier) {
    val result = record.result
    if (record.status != "done" || result == null) {
        Text(
            result
                ?.optString("error")
                .orEmpty()
                .ifBlank { record.summary }
                .ifBlank { activityStatusLabel(record.status) },
            modifier.padding(18.dp),
            fontSize = 13.sp,
            color = WhaleMuted,
        )
        return
    }
    if (record.tool == OfficialWebTool.SEARCH) {
        val results = result.optJSONArray("results")
        LazyColumn(modifier, contentPadding = PaddingValues(18.dp)) {
            if (results == null || results.length() == 0)
                item { Text("未找到可显示的结果", color = WhaleMuted) }
            else
                items(results.length()) { index ->
                    val item = results.getJSONObject(index)
                    SelectionContainer {
                        Column(Modifier.padding(vertical = 10.dp)) {
                            Text(
                                item.optString("title").ifBlank { item.optString("url") },
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(item.optString("url"), color = WhaleBlue, fontSize = 11.sp)
                            Text(
                                item.optString("snippet").ifBlank { item.optString("summary") },
                                Modifier.padding(top = 5.dp),
                                fontSize = 13.sp,
                                lineHeight = 20.sp,
                            )
                        }
                    }
                    HorizontalDivider(color = WhaleLine)
                }
        }
    } else {
        // Official content stays inert and does not make any local page or image requests.
        SelectionContainer(modifier) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(result.optString("url"), fontSize = 12.sp, color = WhaleBlue)
                if (result.optBoolean("truncated"))
                    Text("正文较长，已截取前 48,000 个字符。", fontSize = 12.sp, color = WhaleMuted)
                Text(
                    result.optString("content").ifBlank { "该网页没有可显示的正文。" },
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                )
            }
        }
    }
}
