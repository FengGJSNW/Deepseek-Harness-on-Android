package dev.deepseek.mobile

import androidx.compose.runtime.*
import java.util.UUID
import org.json.JSONObject

class WorkProject(val id: String = UUID.randomUUID().toString(), title: String) {
    var title by mutableStateOf(title)
    var collapsed by mutableStateOf(false)

    fun json() = JSONObject().put("id", id).put("title", title).put("collapsed", collapsed)
}
