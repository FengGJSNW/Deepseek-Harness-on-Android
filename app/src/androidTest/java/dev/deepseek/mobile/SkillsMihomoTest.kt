package dev.deepseek.mobile

import android.webkit.WebView
import androidx.test.platform.app.InstrumentationRegistry
import dev.deepseek.mobile.harness.HarnessCoordinator
import dev.deepseek.mobile.proxy.MihomoManager
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SkillsMihomoTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun bundledCodexSkillsAreInstallableAndScoped() {
        val store = HarnessCoordinator.get(context).skills
        val project = UUID.randomUUID().toString()
        for (name in listOf("skill-creator", "skill-installer")) {
            val skill = store.install(context.assets.open("skills/$name.zip"), project)
            try {
                assertEquals(name, skill.name)
                assertFalse(skill.enabled)
                assertTrue(runCatching { store.read(project, name, "SKILL.md", 0) }.isFailure)
                store.enable(skill.id, true)
                val row = store.read(project, name, "SKILL.md", 0)
                assertTrue(row.getString("text").contains("name: $name"))
                assertTrue(row.getJSONArray("files").length() > 1)
                assertEquals(
                    name,
                    store.catalog(project) { it == name }.getJSONObject(0).getString("name"),
                )
                assertTrue(
                    runCatching { store.read(project, name, "../manifest.json", 0) }.isFailure,
                )
                assertTrue(
                    runCatching { store.read(UUID.randomUUID().toString(), name, "SKILL.md", 0) }
                        .isFailure,
                )
            } finally {
                store.remove(skill.id)
            }
        }
    }

    @Test
    fun bundledCoreUsesNodesRulesAndBrowserProxy() = runBlocking {
        val manager = MihomoManager.get(context)
        val saved = manager.state.value
        assertFalse("Use a test device without a running proxy", saved.running)
        ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { upstream ->
            upstream.soTimeout = 90_000
            val profile =
                manager.import(
                    "QA Mihomo",
                    """
                mode: rule
                proxies:
                  - name: local-qa
                    type: http
                    server: 127.0.0.1
                    port: ${upstream.localPort}
                proxy-groups:
                  - name: QA
                    type: select
                    proxies: [DIRECT, local-qa]
                rules: ['MATCH,QA']
            """
                        .trimIndent(),
                )
            try {
                manager.activate(profile.id)
                manager.toggle("browser", true)
                manager.toggle("terminal", true)
                manager.toggle("agentNodes", false)
                manager.toggle("agentRules", false)
                manager.start()
                assertEquals(
                    "1.19.33",
                    manager.data("/version").getString("version").removePrefix("v"),
                )
                val args = JSONObject().put("group", "QA").put("node", "local-qa")
                assertTrue(runCatching { manager.agent("select", args) }.isFailure)
                manager.toggle("agentNodes", true)
                manager.agent("select", args)
                assertEquals(
                    "local-qa",
                    manager
                        .data("/proxies")
                        .getJSONObject("proxies")
                        .getJSONObject("QA")
                        .getString("now"),
                )
                manager.mode("global")
                assertEquals("global", manager.data("/configs").getString("mode"))
                manager.mode("rule")
                assertTrue(
                    runCatching {
                        manager.edit(profile.id, "proxies: [{name: broken, type: invalid}]")
                    }
                        .isFailure,
                )
                assertTrue(manager.data("/proxies").getJSONObject("proxies").has("local-qa"))
                val rule =
                    JSONObject()
                        .put("type", "DOMAIN")
                        .put("domain", "blocked-qa.test")
                        .put("target", "REJECT")
                assertTrue(runCatching { manager.agent("rule", rule) }.isFailure)
                manager.toggle("agentRules", true)
                manager.agent("rule", rule)
                assertTrue(
                    manager
                        .data("/rules")
                        .getJSONArray("rules")
                        .getJSONObject(0)
                        .toString()
                        .contains("blocked-qa.test"),
                )
                manager.choose("QA", "local-qa")
                val probe =
                    async(Dispatchers.IO) { upstream.accept().use { it.firstRequestLine() } }
                val view =
                    withContext(Dispatchers.Main) {
                        WebView(context).apply { loadUrl("https://webview-proxy-qa.test/") }
                    }
                try {
                    assertTrue(probe.await().contains("webview-proxy-qa.test:443"))
                } finally {
                    withContext(Dispatchers.Main) { view.destroy() }
                }
                val terminalPort = requireNotNull(manager.terminalPort())
                val fixture = java.io.File(context.filesDir, "toolchains/light-v1/machine.state")
                val relay =
                    async(Dispatchers.IO) {
                        runCatching {
                            awaitUpstream(upstream, "terminal-proxy-qa.test", "mihomo-curl-ok")
                        }
                    }
                if (fixture.isFile) {
                    val dir =
                        java.io.File(context.cacheDir, "proxy-guest-${UUID.randomUUID()}").apply {
                            mkdirs()
                        }
                    try {
                        withContext(Dispatchers.IO) {
                            fixture.copyTo(java.io.File(dir, "machine.state"))
                        }
                        val vm = dev.deepseek.mobile.runtime.LinuxVm(context, dir) { true }
                        try {
                            vm.start()
                            val result =
                                vm.execute(
                                    "curl -fsS --max-time 20 http://terminal-proxy-qa.test/",
                                    root = "/mnt/alpine",
                                    timeout = 25,
                                    useMihomo = true,
                                )
                            assertEquals(result.toString(), 0, result.getInt("exitCode"))
                            assertEquals("mihomo-curl-ok", result.getString("stdout"))
                        } finally {
                            vm.close()
                        }
                    } finally {
                        withContext(Dispatchers.IO) { dir.deleteRecursively() }
                    }
                } else {
                    withContext(Dispatchers.IO) {
                        Socket("127.0.0.1", terminalPort).use { socket ->
                            socket
                                .getOutputStream()
                                .write(
                                    "GET http://terminal-proxy-qa.test/ HTTP/1.1\r\nHost: terminal-proxy-qa.test\r\nConnection: close\r\n\r\n"
                                        .toByteArray(),
                                )
                            assertTrue(
                                socket
                                    .getInputStream()
                                    .bufferedReader()
                                    .readText()
                                    .contains("mihomo-curl-ok"),
                            )
                        }
                    }
                }
                assertTrue(relay.await().getOrThrow().contains("terminal-proxy-qa.test"))
                assertTrue(
                    MihomoManager.withProxyEnvironment("curl https://example.org", terminalPort)
                        .contains("127.0.0.1:$terminalPort"),
                )
            } catch (error: Throwable) {
                throw AssertionError(
                    "${error.message}\n${manager.logs().takeLast(20).joinToString("\n")}",
                    error,
                )
            } finally {
                manager.stop()
                manager.remove(profile.id)
                if (saved.profileId.isNotBlank()) manager.activate(saved.profileId)
                manager.toggle("browser", saved.browser)
                manager.toggle("terminal", saved.terminal)
                manager.toggle("agentNodes", saved.agentNodes)
                manager.toggle("agentRules", saved.agentRules)
            }
        }
    }

    private fun awaitUpstream(server: ServerSocket, host: String, body: String): String {
        while (true) {
            val first = server.accept().use { socket -> socket.firstRequestLine(host, body) }
            if (first.contains(host)) return first
        }
    }

    private fun Socket.firstRequestLine(host: String = "", body: String = ""): String {
        soTimeout = 10000
        val reader = getInputStream().bufferedReader()
        val first = reader.readLine().orEmpty()
        while (!reader.readLine().isNullOrEmpty()) Unit
        val output = getOutputStream()
        if (host.isNotEmpty() && first.contains(host)) {
            if (first.startsWith("CONNECT ")) {
                output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                while (!reader.readLine().isNullOrEmpty()) Unit
            }
            output.write(
                "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                    .toByteArray(),
            )
        } else
            output.write(
                "HTTP/1.1 502 Test\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(),
            )
        return first
    }
}
