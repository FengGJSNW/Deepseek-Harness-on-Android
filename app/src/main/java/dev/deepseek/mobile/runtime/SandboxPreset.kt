package dev.deepseek.mobile.runtime

import org.json.JSONObject

internal enum class AlpineRelease(
    val version: String,
    val branch: String,
    val python: String,
    val node: String,
    val currentNode: String,
    val sha256: String,
    val bytes: Long,
) {
    V324(
        "3.24.3",
        "v3.24",
        "3.14",
        "24 LTS",
        "26",
        "a73bd9ed20a5ed1503a0688957add539e10340c7ca1d9e8c06c34a76ddc5f715",
        3545963,
    ),
    V323(
        "3.23.8",
        "v3.23",
        "3.12",
        "24 LTS",
        "24",
        "2e8ba8a8801fb74cd0d32ab8ba8bae3f6ba9f271ed6a086f5ce4cb0226ee4ed4",
        3544284,
    );

    fun artifact() =
        RuntimeArtifacts.Artifact(
            "alpine-$version.tar.gz",
            "https://dl-cdn.alpinelinux.org/alpine/$branch/releases/x86/alpine-minirootfs-$version-x86.tar.gz",
            sha256,
            bytes,
        )
}

internal enum class SandboxProfile(val title: String, val detail: String) {
    LITE("DSHA Linux Lite", "Alpine 基础命令"),
    DEFAULT("DSHA Linux Default", "Git、Midnight Commander、SSH、Python 和 Node.js"),
    CUSTOM("DSHA Linux Custom", "选择发行版、语言与工具链"),
}

internal data class SandboxPreset(
    val release: AlpineRelease = AlpineRelease.V324,
    val python: Boolean = true,
    val node: Boolean = true,
    val nodeCurrent: Boolean = false,
    val developerTools: Boolean = true,
    val cpp: Boolean = false,
    val rust: Boolean = false,
    val lua: Boolean = false,
    val offlineTools: Boolean = true,
    val profile: SandboxProfile = SandboxProfile.DEFAULT,
) {
    val packages: List<Pair<String, String>>
        get() = buildList {
            if (developerTools) add("Shell、Git、MC、SSH 与 Curl" to "bash git mc openssh-client curl")
            if (python) add("Python ${release.python}" to "python3 py3-pip")
            if (node) {
                add("Node.js" to if (nodeCurrent) "nodejs-current" else "nodejs")
                add("npm" to "npm")
            }
            if (cpp) add("C/C++ 与 CMake" to "build-base cmake")
            if (rust) add("Rust 与 Cargo" to "rust cargo")
            if (lua) add("Lua" to "lua5.4")
        }

    fun json() =
        JSONObject()
            .put("release", release.name)
            .put("python", python)
            .put("node", node)
            .put("nodeCurrent", nodeCurrent)
            .put("developerTools", developerTools)
            .put("cpp", cpp)
            .put("rust", rust)
            .put("lua", lua)
            .put("offlineTools", offlineTools)
            .put("profile", profile.name)

    companion object {
        fun defaults(kind: RuntimeKind) =
            if (kind == RuntimeKind.LIGHT) SandboxPreset()
            else
                SandboxPreset(
                    python = false,
                    node = false,
                    developerTools = false,
                    profile = SandboxProfile.LITE,
                )

        fun from(j: JSONObject?, kind: RuntimeKind): SandboxPreset =
            if (j == null) defaults(kind)
            else
                SandboxPreset(
                    AlpineRelease.valueOf(j.getString("release")),
                    j.optBoolean("python"),
                    j.optBoolean("node"),
                    j.optBoolean("nodeCurrent"),
                    j.optBoolean("developerTools"),
                    j.optBoolean("cpp"),
                    j.optBoolean("rust"),
                    j.optBoolean("lua"),
                    j.optBoolean("offlineTools", true),
                    SandboxProfile.valueOf(j.optString("profile", defaults(kind).profile.name)),
                )
    }
}
