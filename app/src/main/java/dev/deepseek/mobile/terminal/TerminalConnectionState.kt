package dev.deepseek.mobile.terminal

import org.json.JSONObject

data class TerminalConnectionState(
    val phase: String = "idle",
    val detail: String = "尚未连接",
    val uid: Int? = null,
    val serverUid: Int? = null,
    val pid: Int? = null,
    val selinux: String = "",
    val errorCode: String = "NOT_CONNECTED",
    val guardAvailable: Boolean = false,
) {
    val ready
        get() = phase == "ready"

    val pending
        get() = phase in setOf("requesting", "permission", "binding", "verifying")

    fun json() =
        JSONObject()
            .put("phase", phase)
            .put("connected", ready)
            .put("detail", detail)
            .put("uid", uid ?: JSONObject.NULL)
            .put("serverUid", serverUid ?: JSONObject.NULL)
            .put("pid", pid ?: JSONObject.NULL)
            .put("selinux", selinux)
            .put("errorCode", if (ready) JSONObject.NULL else errorCode)
            .put("guardAvailable", guardAvailable)
}

class TerminalConnectionException(val connection: TerminalConnectionState) :
    IllegalStateException(connection.detail)
