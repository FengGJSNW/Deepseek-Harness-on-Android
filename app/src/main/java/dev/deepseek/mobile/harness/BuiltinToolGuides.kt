package dev.deepseek.mobile.harness

import android.content.res.AssetManager
import org.json.JSONArray
import org.json.JSONObject

/** Reference-only skills shipped with DSHA. They cannot add permissions or executable tools. */
internal object BuiltinToolGuides {
    val catalog =
        listOf(
                "dsha-start" to "开始 DSHA 工作前的工具发现、记忆、工作模式与验证流程。",
                "dsha-workspace" to "文件、终端、Sandbox、独立工作副本、取消与交付的使用方法。",
                "dsha-mcp" to "发现 MCP 服务与真实工具定义，构造参数，处理返回值与连接错误。",
                "dsha-proxy" to "使用 FlClash proxy_control，区分 Agent 权限、VPN 授权与实际运行状态。",
                "dsha-skills" to "按需读取 Skill 文档和包内资源，发现离线插件，处理权限与范围。",
            )
            .map { (name, description) ->
                InstalledSkill("builtin:$name", name, description, "", true)
            }

    fun read(assets: AssetManager, name: String, path: String, offset: Int): JSONObject {
        require(catalog.any { it.name == name }) { "没有此内置指南" }
        require(path == "SKILL.md") { "此内置指南仅包含 SKILL.md" }
        val text = assets.open("agent-guides/$name/SKILL.md").bufferedReader().use { it.readText() }
        val start = offset.coerceIn(0, text.length)
        val end = (start + 12_000).coerceAtMost(text.length)
        return JSONObject()
            .put("path", path)
            .put("text", text.substring(start, end))
            .put("nextOffset", if (end == text.length) JSONObject.NULL else end)
            .put("files", JSONArray().put("SKILL.md"))
            .put("builtin", true)
    }
}
