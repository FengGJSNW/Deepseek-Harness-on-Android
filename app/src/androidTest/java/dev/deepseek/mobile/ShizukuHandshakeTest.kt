package dev.deepseek.mobile

import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import dev.deepseek.mobile.terminal.*
import java.util.UUID
import junit.framework.TestCase
import kotlinx.coroutines.*
import org.json.JSONObject
import rikka.shizuku.Shizuku

/** Dedicated emulator: the external test driver grants its real Shizuku permission dialog. */
class ShizukuHandshakeTest : TestCase() {
    fun testPermissionCallbackBindsWithoutASecondClickAndExecutesAs2000() = runBlocking {
        val ins = InstrumentationRegistry.getInstrumentation()
        val context = ins.targetContext
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        withTimeout(30000) {
            while (!AssistantState.ready || AgentService.current == null) delay(50)
        }
        assertTrue(Shizuku.pingBinder())
        val neededPermission = Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED
        val manager = AgentService.current!!.terminal
        ins.runOnMainSync { manager.connections.connectShell() }
        withTimeout(65000) {
            while (!manager.connections.shellState.ready) {
                check(
                    manager.connections.shellState.phase !in
                        setOf("failed", "denied", "wrong_identity"),
                ) {
                    manager.connections.shellState.detail
                }
                delay(100)
            }
        }
        assertEquals(2000, manager.connections.shellState.uid)
        val identity =
            org.json.JSONObject(
                withContext(Dispatchers.IO) {
                    manager.connections.worker(TerminalLevel.SHELL).info()
                },
            )
        if (Shizuku.getUid() == 0) {
            assertTrue(identity.getBoolean("downgradedToShell"))
            assertEquals("u:r:shell:s0", identity.getString("selinux"))
            val credentials = identity.getJSONObject("credentials")
            for (key in listOf("Uid", "Gid")) assertTrue(
                credentials.getString(key).split(Regex("\\s+")).all { it == "2000" },
            )
            for (key in listOf("CapInh", "CapPrm", "CapEff", "CapBnd", "CapAmb")) assertTrue(
                credentials.getString(key).all { it == '0' },
            )
            assertEquals("1", credentials.getString("NoNewPrivs"))
        }
        val task =
            PhoneTask(
                sessionId = UUID.randomUUID().toString(),
                text = "Shizuku 只读身份验证",
                images = emptyList(),
                model = "deepseek-flash",
                effort = "low",
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
            withContext(Dispatchers.Main) {
                manager.execute(
                    task,
                    JSONObject()
                        .put("command", "sh -c 'id -u; pwd'")
                        .put("cwd", "/")
                        .put("timeout_seconds", 5),
                )
            }
        assertEquals(result.toString(), 0, result.getInt("exitCode"))
        assertEquals(2000, result.getInt("verifiedUid"))
        assertTrue(result.getBoolean("executionStarted"))
        assertTrue(result.getString("output").contains("2000"))
        var privateFixture: JSONObject? = null
        if (
            InstrumentationRegistry.getArguments().getString("dsha_root_shizuku_fixture") == "true"
        ) {
            check(
                android.os.Build.HARDWARE in setOf("ranchu", "goldfish"),
            )
            assertEquals(0, Shizuku.getUid())
            privateFixture =
                withContext(Dispatchers.Main) {
                    manager.execute(
                        task,
                        JSONObject()
                            .put("command", "cat /data/local/tmp/dsha-v073-root-only")
                            .put("cwd", "/"),
                    )
                }
            assertTrue(privateFixture.getBoolean("executionStarted"))
            assertEquals(2000, privateFixture.getInt("verifiedUid"))
            assertTrue(privateFixture.getInt("exitCode") != 0)
            assertFalse(privateFixture.getString("output").contains("DSHA_ROOT_ONLY_FIXTURE"))
            assertTrue(
                privateFixture.getString("stderr").contains("Permission denied", ignoreCase = true),
            )
        }
        val status =
            withContext(Dispatchers.Main) { manager.connectionStatus(TerminalLevel.SHELL, true) }
        assertEquals(0, status.getJSONObject("probe").getInt("exitCode"))
        assertTrue(status.getJSONObject("probe").getString("output").contains("uid=2000(shell)"))
        println(
            "DSHA_SHIZUKU_PROOF=" +
                JSONObject()
                    .put("permissionNeededAtStart", neededPermission)
                    .put("connectClicks", 1)
                    .put("execution", result)
                    .put("rootOnlyFixtureDenied", privateFixture ?: JSONObject.NULL)
                    .put("status", status),
        )
    }
}
