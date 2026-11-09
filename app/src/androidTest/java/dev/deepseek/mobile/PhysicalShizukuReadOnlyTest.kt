package dev.deepseek.mobile

import androidx.test.platform.app.InstrumentationRegistry
import dev.deepseek.mobile.terminal.TerminalLevel
import junit.framework.TestCase
import kotlinx.coroutines.*

/** Explicit opt-in; only native connection metadata and the fixed read-only probe. */
class PhysicalShizukuReadOnlyTest : TestCase() {
    fun testActualShellIdentityOrExactDowngradeFailure() = runBlocking {
        check(InstrumentationRegistry.getArguments().getString("dsha_readonly_shizuku") == "true")
        val ins = InstrumentationRegistry.getInstrumentation()
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
                ins.uiAutomation.executeShellCommand(
                    "am start -n dev.deepseek.mobile/.MainActivity",
                ),
            )
            .use { it.readBytes() }
        withTimeout(30000) {
            while (runCatching { AgentService.current?.terminal }.getOrNull() == null) delay(50)
        }
        check(!AssistantState.busy) { "User task is running" }
        val manager = AgentService.current!!.terminal
        ins.runOnMainSync { manager.connections.connectShell() }
        withTimeout(65000) { while (manager.connections.shellState.pending) delay(100) }
        val proof =
            withContext(Dispatchers.Main) { manager.connectionStatus(TerminalLevel.SHELL, true) }
        if (!manager.connections.shellState.ready) {
            val state = manager.connections.shellState
            assertEquals(0, state.serverUid)
            assertEquals("SHELL_DOWNGRADE_FAILED", state.errorCode)
            assertTrue(
                state.detail.contains("Shell groups") &&
                    state.detail.contains("Operation not permitted"),
            )
            assertFalse(proof.getBoolean("probePerformed"))
            ins.runOnMainSync { manager.connections.connectShell() }
            withTimeout(65000) { while (manager.connections.shellState.pending) delay(100) }
            assertTrue(manager.connections.shellState.detail.contains("Shell groups"))
            assertFalse(manager.connections.shellState.detail.contains("通道已关闭"))
            ins.runOnMainSync { manager.connections.refresh() }
            assertEquals("failed", manager.connections.shellState.phase)
            proof.put("expectedUnsupported", true).put("retryRetainedOriginalFailure", true)
            println("DSHA_PHYSICAL_SHIZUKU_PROOF=$proof")
            return@runBlocking
        }
        println("DSHA_PHYSICAL_SHIZUKU_PROOF=$proof")
        assertEquals(2000, proof.getJSONObject("identity").getInt("uid"))
        assertEquals(0, proof.getJSONObject("probe").getInt("exitCode"))
    }
}
