package dev.deepseek.mobile

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import dev.deepseek.mobile.runtime.AlpineRelease
import dev.deepseek.mobile.runtime.SandboxPreset
import dev.deepseek.mobile.runtime.sandboxToolVersions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SandboxPresetEditor(value: SandboxPreset, change: (SandboxPreset) -> Unit) {
    val context = LocalContext.current
    val jobs = rememberCoroutineScope()
    var versions by remember { mutableStateOf<Map<AlpineRelease, Map<String, String>>>(emptyMap()) }
    var versionStatus by remember { mutableStateOf("") }
    var loadingVersions by remember { mutableStateOf(false) }
    fun python(release: AlpineRelease) = versions[release]?.get("python3") ?: release.python
    fun node(release: AlpineRelease, current: Boolean) =
        versions[release]?.get(if (current) "nodejs-current" else "nodejs")
            ?: if (current) release.currentNode else release.node

    Column {
        ToggleSetting("优先使用 DSHA Tool", "已导入时使用随包版本，否则从 Alpine 下载", value.offlineTools) {
            change(value.copy(offlineTools = it))
        }
        SettingsLink(
            AppIcons.Globe,
            "查询官方软件源版本",
            if (loadingVersions) "查询中…" else versionStatus.ifBlank { "查询可用于当前 x86 镜像的版本" },
        ) {
            if (!loadingVersions) {
                loadingVersions = true
                change(value.copy(offlineTools = false))
                jobs.launch {
                    try {
                        versions = withContext(Dispatchers.IO) { sandboxToolVersions(context) }
                        versionStatus = "已更新 Alpine x86 版本"
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        versionStatus = error.message ?: "无法读取 Alpine 软件源"
                    } finally {
                        loadingVersions = false
                    }
                }
            }
        }
        SettingsChoice(
            AppIcons.TerminalBox,
            "Alpine Linux",
            value.release.version,
        ) { dismiss ->
            for (release in AlpineRelease.entries) GlassMenuRow(
                AppIcons.TerminalBox,
                release.version,
                "Python ${python(release)} · Node ${node(release, false)}",
                value.release == release,
            ) {
                change(value.copy(release = release, nodeCurrent = false))
                dismiss()
            }
        }
        ToggleSetting("Python", "包含 pip，版本与 Alpine 分支对应", value.python) {
            change(value.copy(python = it))
        }
        if (value.python)
            SettingsChoice(AppIcons.Code, "Python 版本", python(value.release)) { dismiss ->
                for (release in AlpineRelease.entries) GlassMenuRow(
                    AppIcons.Code,
                    "Python ${python(release)}",
                    "Alpine ${release.version}",
                    value.release == release,
                ) {
                    change(value.copy(release = release, nodeCurrent = false))
                    dismiss()
                }
            }
        ToggleSetting("Node.js", "包含 npm", value.node) { change(value.copy(node = it)) }
        if (value.node)
            SettingsChoice(
                AppIcons.Code,
                "Node.js 版本",
                node(value.release, value.nodeCurrent),
            ) { dismiss ->
                GlassMenuRow(
                    AppIcons.Code,
                    node(value.release, false),
                    "长期支持版",
                    !value.nodeCurrent,
                ) {
                    change(value.copy(nodeCurrent = false))
                    dismiss()
                }
                if (
                    value.release.currentNode != "24" ||
                        versions[value.release]?.containsKey("nodejs-current") == true
                )
                    GlassMenuRow(
                        AppIcons.Code,
                        node(value.release, true),
                        "官方 current 软件包",
                        value.nodeCurrent,
                    ) {
                        change(value.copy(nodeCurrent = true, offlineTools = false))
                        dismiss()
                    }
            }
        Text("开发工具链", style = PrismTypography.headline)
        ToggleSetting("基础开发工具", "Bash、Git、Midnight Commander、SSH、Curl", value.developerTools) {
            change(value.copy(developerTools = it))
        }
        ToggleSetting("C/C++ 与 CMake", "编译器、Make、头文件和 CMake", value.cpp) {
            change(value.copy(cpp = it))
        }
        ToggleSetting("Rust", "Rust 编译器与 Cargo", value.rust) {
            change(value.copy(rust = it))
        }
        ToggleSetting("Lua", "Lua 5.4 解释器", value.lua) {
            change(value.copy(lua = it))
        }
    }
}
