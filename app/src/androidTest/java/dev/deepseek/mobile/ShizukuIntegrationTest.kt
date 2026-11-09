package dev.deepseek.mobile

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import dev.deepseek.mobile.terminal.*
import junit.framework.TestCase
import kotlinx.coroutines.*
import org.json.JSONObject

/** Opt-in test: run only on a test device with Shizuku started as Shell and this app authorized. */
class ShizukuIntegrationTest : TestCase() {
    fun testRealShizukuUserServiceExecutesAsShell() = runBlocking {
        val ins = InstrumentationRegistry.getInstrumentation()
        val ctx = ins.targetContext
        ins.startActivitySync(
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        withTimeout(30000) { while (!AssistantState.ready) delay(50) }
        val terminal = AgentService.current!!.terminal
        assertTrue(rikka.shizuku.Shizuku.pingBinder())
        assertEquals(
            android.content.pm.PackageManager.PERMISSION_GRANTED,
            rikka.shizuku.Shizuku.checkSelfPermission(),
        )
        ins.runOnMainSync { terminal.connections.connectShell() }
        withTimeout(15000) {
            while (
                !terminal.connections.status(TerminalLevel.SHELL).contains("\"uid\":2000")
            ) delay(100)
        }
        val session = java.util.UUID.randomUUID().toString()
        val task =
            PhoneTask(
                sessionId = session,
                text = "Read-only Shizuku identity test",
                images = emptyList(),
                model = "deepseek-flash",
                effort = "off",
                access = "full",
                phoneTools = false,
                fileTools = false,
                workspace = "",
                terminalEnabled = true,
                terminalConfig =
                    TerminalSnapshot("shell", TerminalPolicy.defaults(), ReviewerConfig.defaultText)
                        .json()
                        .toString(),
            )
        val result =
            terminal.execute(
                task,
                JSONObject()
                    .put("command", "id; pwd; printf shizuku-ok")
                    .put("timeout_seconds", 10),
            )
        assertEquals(result.toString(), 0, result.getInt("exitCode"))
        assertTrue(result.getString("output").contains("uid=2000(shell)"))
        assertTrue(result.getString("output").contains("shizuku-ok"))
    }
}
