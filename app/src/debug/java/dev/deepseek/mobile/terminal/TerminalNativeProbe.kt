package dev.deepseek.mobile.terminal

import java.io.File
import org.json.JSONObject

/**
 * Debug-only command-line probe for an emulator. Not an exported component or a release entrypoint.
 */
object TerminalNativeProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2)
        val level = TerminalLevel.of(args[0])
        val session = args[1]
        require(level != TerminalLevel.ISOLATED && session.matches(Regex("[a-f0-9-]{36}")))
        val root = level in setOf(TerminalLevel.ROOT, TerminalLevel.ROOT_GUARDED)
        val workspace =
            if (root) "/data/local/tmp/dev.deepseek.mobile/root/session_$session"
            else "/data/local/tmp/dev.deepseek.mobile-shell/session_$session"
        val outside = File("/data/local/tmp/deepseek-guard-probe-$session")
        if (root) outside.writeText("before")
        val worker = ProcessWorker()
        try {
            println("identity=" + worker.info())
            val command =
                if (level == TerminalLevel.ROOT_GUARDED)
                    "id; printf inside > inside.txt; cat inside.txt; if printf changed > ${shellQuote(outside.path)}; then echo OUTSIDE_WRITE_UNEXPECTED; else echo OUTSIDE_WRITE_BLOCKED; fi; grep -E 'CapEff|NoNewPrivs|Seccomp|NSpid' /proc/self/status"
                else "id; pwd; printf stdout; printf stderr >&2"
            val id =
                worker.start(
                    JSONObject()
                        .put("sessionId", session)
                        .put("level", level.key)
                        .put("workspace", workspace)
                        .put("cwd", workspace)
                        .put("command", command)
                        .put("timeout_seconds", 15)
                        .put("output_bytes", 8192)
                        .toString(),
                )
            val deadline = System.currentTimeMillis() + 20000
            while (true) {
                val result = JSONObject(worker.poll(id))
                if (result.getBoolean("done")) {
                    println(result)
                    break
                }
                check(System.currentTimeMillis() < deadline)
                Thread.sleep(50)
            }
            if (root) {
                println("outside=" + outside.readText())
                check(outside.readText() == "before")
            }
        } finally {
            worker.close()
            if (root) outside.delete()
            val folder = File(workspace)
            require(folder.canonicalPath == workspace)
            folder.deleteRecursively()
        }
    }
}
