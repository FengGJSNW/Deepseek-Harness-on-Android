package dev.deepseek.mobile

import androidx.test.platform.app.InstrumentationRegistry
import com.topjohnwu.superuser.Shell
import dev.deepseek.mobile.terminal.*
import junit.framework.TestCase
import kotlinx.coroutines.*

/** Physical-device opt-in. Fixed read-only diagnostics and a single literal id -u only. */
class PrivilegedRootReadOnlyTest : TestCase() {
    fun testSukiSuTransportWithCachedNonRootMainShell() = runBlocking {
        val ins = InstrumentationRegistry.getInstrumentation()
        check(InstrumentationRegistry.getArguments().getString("dsha_readonly_root") == "true") {
            "Physical Root test requires explicit opt-in"
        }
        val context = ins.targetContext
        // Launch from the authorized instrumentation shell so an OEM background-launch freeze
        // cannot be mistaken for missing su authorization. Never navigate or approve the manager.
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
                ins.uiAutomation.executeShellCommand(
                    "am start -n dev.deepseek.mobile/.MainActivity",
                ),
            )
            .use { it.readBytes() }
        withTimeout(30000) {
            while (runCatching { AgentService.current?.terminal }.getOrNull() == null) delay(50)
        }
        check(!AssistantState.busy) { "User task is running; diagnostic will not interrupt it" }
        val manager = AgentService.current!!.terminal
        // Prove that reconnecting does not depend on libsu's cached non-root main shell.
        withContext(Dispatchers.IO) {
            Shell.getCachedShell()?.close()
            Shell.setDefaultBuilder(Shell.Builder.create().setFlags(Shell.FLAG_NON_ROOT_SHELL))
            assertFalse(Shell.getShell().isRoot)
        }
        ins.runOnMainSync { manager.connections.connectRoot() }
        withTimeout(65000) {
            while (!manager.connections.rootState.ready) {
                check(manager.connections.rootState.pending) {
                    manager.connections.rootState.detail
                }
                delay(100)
            }
        }
        val proof =
            withContext(Dispatchers.Main) { manager.connectionStatus(TerminalLevel.ROOT, true) }
        assertEquals(context.packageName, proof.getString("requesterPackage"))
        assertTrue(proof.getInt("requesterUid") >= 10000)
        assertEquals(0, proof.getJSONObject("identity").getInt("uid"))
        assertTrue(proof.getBoolean("probePerformed"))
        assertEquals(0, proof.getJSONObject("probe").getInt("exitCode"))
        assertTrue(proof.getJSONObject("probe").getString("output").contains("uid=0(root)"))
        val task =
            PhoneTask(
                sessionId = java.util.UUID.randomUUID().toString(),
                text = "DSHA 0.7.3 固定只读身份验收",
                images = emptyList(),
                model = "deepseek-flash",
                effort = "low",
                access = "full",
                phoneTools = false,
                fileTools = false,
                workspace = "",
                terminalEnabled = true,
                terminalConfig =
                    TerminalSnapshot("root", TerminalPolicy.defaults(), ReviewerConfig.defaultText)
                        .json()
                        .toString(),
            )
        val execution =
            withContext(Dispatchers.Main) {
                manager.execute(
                    task,
                    org.json
                        .JSONObject()
                        .put("command", "id -u")
                        .put("cwd", "/")
                        .put("timeout_seconds", 5),
                )
            }
        assertEquals(0, execution.getInt("verifiedUid"))
        assertTrue(execution.getBoolean("executionStarted"))
        assertEquals(0, execution.getInt("exitCode"))
        assertEquals("0", execution.getString("output").trim())
        proof.put("terminalExecution", execution)
        println("DSHA_ROOT_READONLY_PROOF=" + proof.toString())
        ins.runOnMainSync { manager.connections.disconnect(true, forget = false) }
        val reconnected =
            withContext(Dispatchers.Main) { manager.connections.awaitWorker(TerminalLevel.ROOT) }
        assertEquals(
            0,
            org.json.JSONObject(withContext(Dispatchers.IO) { reconnected.info() }).getInt("uid"),
        )
        println("ROOT_RECONNECT_WITH_RECORDED_AUTHORIZATION=UID_0")
        println(
            "ROOT_TEST_SCOPE=fixed id/pwd/uname/namespace names; no root file writes, policy edits, chmod, mounts, or user data reads",
        )
    }
}
