package dev.deepseek.mobile.terminal

import android.os.Process
import java.io.File
import org.json.JSONObject

/** Fixed pipe protocol, entered only after the native child has irreversibly become Shell. */
object ShellWorkerMain {
    @JvmStatic
    fun main(args: Array<String>) {
        check(Process.myUid() == 2000 && args.size == 1)
        System.setProperty("dsha.nativeLibrary", args[0])
        val status =
            File("/proc/self/status").readLines().associate {
                it.substringBefore(':') to it.substringAfter(':').trim()
            }
        check(status["Uid"]!!.split(Regex("\\s+")).all { it == "2000" })
        check(status["Gid"]!!.split(Regex("\\s+")).all { it == "2000" })
        for (key in listOf("CapInh", "CapPrm", "CapEff", "CapBnd", "CapAmb")) check(
            status[key]?.all { it == '0' } == true,
        ) {
            "Shell retains $key"
        }
        check(status["NoNewPrivs"] == "1")
        check(File("/proc/self/attr/current").readText().trim().trimEnd('\u0000') == "u:r:shell:s0")
        val worker = ProcessWorker()
        try {
            // app_process may replace System.in/out with Android log streams.
            val input = java.io.FileInputStream(java.io.FileDescriptor.`in`).bufferedReader()
            val output = java.io.FileOutputStream(java.io.FileDescriptor.out).bufferedWriter()
            while (true) {
                val line = input.readLine() ?: break
                check(line.length <= 524288) { "Shell RPC too large" }
                val reply = JSONObject()
                try {
                    val request = JSONObject(line)
                    val argument = request.optString("argument")
                    val method = request.getString("method")
                    val value =
                        when (method) {
                            "info" -> worker.info()
                            "inspect" -> worker.inspect(argument)
                            "start" -> worker.start(argument)
                            "poll" -> worker.poll(argument)
                            "probe" -> worker.readOnlyProbe()
                            "cancel" -> {
                                worker.cancel(argument)
                                ""
                            }
                            "shutdown" -> {
                                worker.close()
                                ""
                            }
                            else -> error("Unknown Shell RPC")
                        }
                    reply.put("value", value)
                    if (method == "shutdown") {
                        output.write(reply.toString())
                        output.newLine()
                        output.flush()
                        break
                    }
                } catch (e: Exception) {
                    reply.put("error", e.message ?: e.javaClass.simpleName)
                }
                output.write(reply.toString())
                output.newLine()
                output.flush()
            }
        } finally {
            worker.close()
        }
        kotlin.system.exitProcess(0)
    }
}
