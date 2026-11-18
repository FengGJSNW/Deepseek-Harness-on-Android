package dev.deepseek.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.deepseek.mobile.harness.HarnessCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun MemorySummarySheet(close: () -> Unit) {
    val context = LocalContext.current
    val projects = remember { AssistantState.projects.map { it.id to it.title } }
    val result by
        produceState<Result<String>?>(null) {
            value =
                withContext(Dispatchers.IO) {
                    runCatching {
                        val store = MemoryStore(context)
                        val contexts = HarnessCoordinator.get(context).contexts
                        val sources = buildList {
                            add("全局记忆" to store.read("global").text)
                            add("人格设置" to contexts.instruction("personality").getString("text"))
                            add("人格记忆" to store.read("persona").text)
                            for ((id, title) in projects) add(
                                title to store.read("project", id).text,
                            )
                        }
                        sources
                            .mapNotNull { (title, source) ->
                                val excerpts =
                                    source
                                        .lineSequence()
                                        .map { it.trim().trimStart('-', '*', '>', '#').trim() }
                                        .filter { it.isNotBlank() && !it.startsWith("```") }
                                        .distinct()
                                        .take(5)
                                        .map { if (it.length <= 180) it else it.take(177) + "…" }
                                        .toList()
                                if (excerpts.isEmpty()) null
                                else title + "\n" + excerpts.joinToString("\n") { "• $it" }
                            }
                            .joinToString("\n\n")
                            .ifBlank { "目前没有可摘要的记忆。" }
                    }
                }
        }
    WhaleSheet("记忆摘要", "从本机记忆和人格设置提取要点。", close) {
        val completed = result
        if (completed == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            SelectionContainer {
                Text(
                    completed.getOrElse { "读取失败：" + it.message.orEmpty() },
                    Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    fontSize = 13.sp,
                    lineHeight = 21.sp,
                )
            }
        }
    }
}
