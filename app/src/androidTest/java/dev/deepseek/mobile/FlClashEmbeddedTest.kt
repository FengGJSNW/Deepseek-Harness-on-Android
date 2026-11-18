package dev.deepseek.mobile

import android.content.Context
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.deepseek.mobile.proxy.FlClashRuntime
import dev.deepseek.mobile.proxy.MihomoManager
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Runs only on a disposable emulator after the original FlClash consent screen is completed. */
class FlClashEmbeddedTest {
    @Test
    fun originalUiAndAgentShareTheActualProxyService() = runBlocking {
        assumeTrue(Build.MODEL.contains("sdk_gphone") || Build.FINGERPRINT.contains("generic"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val config =
            context
                .getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
                .getString("flutter.config", "{}")!!
        assumeTrue(
            JSONObject(config)
                .optJSONObject("appSettingProps")
                ?.optInt("acceptedDisclaimerVersion", 0) == 1,
        )

        val legacy = MihomoManager.get(context)
        val profile =
            legacy.import(
                "DSHA integration QA",
                """
                mode: rule
                proxy-groups:
                  - name: DSHA-QA
                    type: select
                    proxies: [DIRECT, REJECT]
                rules: ['MATCH,DSHA-QA']
                """
                    .trimIndent(),
            )
        legacy.activate(profile.id)
        val runtime = FlClashRuntime.get(context)
        runtime.policy.set("start", false)
        runtime.policy.set("nodes", true)
        runtime.policy.set("rules", false)
        assertTrue(runCatching { runtime.agent("start", JSONObject()) }.isFailure)

        // Grant the emulator's VPN consent without changing any connected physical phone.
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(
                    "pm grant dev.deepseek.mobile android.permission.POST_NOTIFICATIONS",
                ),
            )
            .bufferedReader()
            .use { it.readText() }
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(
                    "appops set dev.deepseek.mobile ACTIVATE_VPN allow",
                ),
            )
            .bufferedReader()
            .use { it.readText() }

        runtime.policy.set("start", true)
        runtime.policy.set("rules", true)
        val activity = ActivityScenario.launch(com.follow.clash.MainActivity::class.java)
        try {
            withTimeout(60_000) {
                while (runCatching { runtime.agent("status", JSONObject()) }.isFailure) delay(200)
            }
            assertTrue(runtime.agent("start", JSONObject()).getBoolean("running"))
            assertTrue(com.follow.clash.ServiceState.awaitRunTime() > 0L)
            withTimeout(10_000) { while (runtime.terminalPort() == null) delay(100) }
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 5000
                val upstream =
                    async(Dispatchers.IO) {
                        server.accept().use { socket ->
                            socket.soTimeout = 5000
                            val reader = socket.getInputStream().bufferedReader()
                            while (reader.readLine()?.isNotEmpty() == true) {}
                            socket
                                .getOutputStream()
                                .write(
                                    "HTTP/1.1 200 OK\r\nContent-Length: 4\r\nConnection: close\r\n\r\nDSHA"
                                        .toByteArray(),
                                )
                        }
                    }
                val response =
                    withContext(Dispatchers.IO) {
                        Socket("127.0.0.1", runtime.terminalPort()!!).use { socket ->
                            socket.soTimeout = 5000
                            socket
                                .getOutputStream()
                                .write(
                                    "GET http://127.0.0.1:${server.localPort}/ HTTP/1.1\r\nHost: 127.0.0.1:${server.localPort}\r\nConnection: close\r\n\r\n"
                                        .toByteArray(),
                                )
                            socket.getInputStream().bufferedReader().readText()
                        }
                    }
                upstream.await()
                assertTrue("Traffic must traverse the embedded core", response.endsWith("DSHA"))
            }
            // Closing the original UI must retain the shared engine and service for DSHA.
            activity.close()
            assertTrue(runtime.agent("status", JSONObject()).getBoolean("running"))
            val selection =
                runtime.agent(
                    "select",
                    JSONObject().put("group", "DSHA-QA").put("node", "REJECT"),
                )
            assertEquals("REJECT", selection.getString("node"))
            val rule =
                runtime.agent(
                    "rule",
                    JSONObject().put("domain", "dsha-integration.invalid").put("target", "DIRECT"),
                )
            assertTrue(rule.getBoolean("saved"))
            assertTrue(rule.getBoolean("applied"))
            runtime.policy.set("nodes", false)
            assertTrue(
                runCatching {
                    runtime.agent(
                        "select",
                        JSONObject().put("group", "DSHA-QA").put("node", "DIRECT"),
                    )
                }
                    .isFailure,
            )
            assertFalse(runtime.agent("stop", JSONObject()).getBoolean("running"))
            withTimeout(10_000) { while (runtime.terminalPort() != null) delay(100) }
        } finally {
            runCatching { runtime.agent("stop", JSONObject()) }
            activity.close()
            runtime.policy.set("start", false)
            runtime.policy.set("nodes", false)
            runtime.policy.set("rules", false)
            legacy.remove(profile.id)
        }
    }
}
