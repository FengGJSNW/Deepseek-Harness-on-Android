package dev.deepseek.mobile

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import dev.deepseek.mobile.terminal.TerminalLevel
import junit.framework.TestCase
import kotlinx.coroutines.*
import org.json.JSONObject

/** Explicitly opt-in personal-device check. No fixtures, cleanup, file writes, chmod or mounts. */
class PhysicalRootReadOnlyTest : TestCase() {
    fun testRootBinderAndFixedReadOnlyCommands() = runBlocking {
        val ins = InstrumentationRegistry.getInstrumentation()
        val context = ins.targetContext
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        withTimeout(30000) { while (!AssistantState.ready) delay(50) }
        val connections = AgentService.current!!.terminal.connections
        ins.runOnMainSync { connections.connectRoot() }
        withTimeout(30000) {
            while (!connections.status(TerminalLevel.ROOT).contains("\"uid\":0")) delay(100)
        }
        val worker = connections.worker(TerminalLevel.ROOT)
        val identity = JSONObject(worker.info())
        println("ROOT_IDENTITY=$identity")
        assertEquals(0, identity.getInt("uid"))
        val id = withContext(Dispatchers.IO) { worker.readOnlyProbe() }
        val result =
            withTimeout(15000) {
                while (true) {
                    val value = withContext(Dispatchers.IO) { JSONObject(worker.poll(id)) }
                    if (value.getBoolean("done")) return@withTimeout value
                    delay(50)
                }
                error("unreachable")
            }
        println("READ_ONLY_COMMANDS=id; pwd; uname -r; ls /proc/self/ns")
        println("READ_ONLY_RESULT=$result")
        assertEquals(result.toString(), 0, result.getInt("exitCode"))
        assertTrue(result.getString("output").contains("uid=0(root)"))
    }
}
