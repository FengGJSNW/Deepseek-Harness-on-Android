package dev.deepseek.mobile

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.deepseek.mobile.harness.*
import dev.deepseek.mobile.runtime.*
import java.io.File
import java.io.StringReader
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ExtensionTest {
    @Test
    fun protocolFramingHeadersAndScopedConfigurationFailClosed() = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(target.cacheDir, "extension-core-${UUID.randomUUID()}").apply { mkdirs() }
        val context =
            object : ContextWrapper(target) {
                override fun getFilesDir() = root
            }
        try {
            listOf(
                    "http://localhost/mcp",
                    "https://user:password@example.com/mcp",
                    "file:///etc/passwd",
                )
                .forEach {
                    assertTrue(runCatching { McpClient.validateEndpoint(it) }.isFailure)
                }
            val schema =
                JSONObject(
                    """{"type":"object","properties":{"region":{"type":"string","x-mcp-header":"Region"},"nested":{"type":"object","properties":{"count":{"type":"integer","x-mcp-header":"Count"}}}}}""",
                )
            val headers =
                McpClient.parameterHeaders(
                    schema,
                    JSONObject("""{"region":"你好\nHeader","nested":{"count":42}}"""),
                )
            assertEquals("42", headers["Mcp-Param-Count"])
            assertTrue(headers.getValue("Mcp-Param-Region").startsWith("=?base64?"))
            assertFalse(headers.values.any { '\n' in it })
            assertTrue(
                runCatching {
                    McpClient.parameterHeaders(
                        JSONObject("""{"oneOf":[{"type":"string","x-mcp-header":"Bad"}]}"""),
                        JSONObject(),
                    )
                }
                    .isFailure,
            )
            assertTrue(
                runCatching {
                    McpClient.parameterHeaders(
                        JSONObject(
                            """{"properties":{"a":{"type":"string","x-mcp-header":"A"},"b":{"type":"string","x-mcp-header":"a"}}}""",
                        ),
                        JSONObject(),
                    )
                }
                    .isFailure,
            )
            val response =
                McpClient.readSse(
                    StringReader(
                            ": ping\n\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n\ndata: {\"jsonrpc\":\"2.0\",\"id\":\"good\",\"result\":{\"ok\":true}}\n\n",
                        )
                        .buffered(),
                    "good",
                )
            assertTrue(response.getJSONObject("result").getBoolean("ok"))
            assertTrue(
                runCatching {
                    McpClient.readSse(
                        StringReader(
                                "data: {\"id\":\"server\",\"method\":\"sampling/createMessage\"}\n\n",
                            )
                            .buffered(),
                        "good",
                    )
                }
                    .isFailure,
            )
            assertTrue(
                runCatching { McpClient.parseMessage("[".repeat(65) + "0" + "]".repeat(65)) }
                    .isFailure,
            )
            assertEquals("{{}}", McpClient.parseMessage("{\"text\":\"{{}}\"}").getString("text"))
            val store = ExtensionStore(context)
            val project = UUID.randomUUID().toString()
            val config =
                JSONObject()
                    .put("id", UUID.randomUUID().toString())
                    .put("projectId", project)
                    .put("name", "public docs")
                    .put("url", "https://mcp.deepwiki.com/mcp")
                    .put("enabled", true)
                    .put("capabilities", JSONArray(listOf("NETWORK", "MCP_TOOL")))
            store.saveFromUser("mcp", config)
            assertTrue(store.enabled("mcp", project).isEmpty())
            store.saveFromUser("mcp", config, approved = true)
            assertEquals(1, store.enabled("mcp", project).size)
            assertTrue(store.enabled("mcp", UUID.randomUUID().toString()).isEmpty())
            val runtime =
                RuntimeManager(context, PermissionManager(context), WorkspaceManager(context))
            val extensions = ExtensionManager(context, PermissionManager(context), runtime)
            extensions.store.saveFromUser(
                "hooks",
                JSONObject()
                    .put("id", UUID.randomUUID().toString())
                    .put("name", "must not run in chat-only")
                    .put("event", "BeforeAgentStart")
                    .put("action", "command")
                    .put("command", "exit 77")
                    .put("enabled", true)
                    .put("capabilities", JSONArray(listOf("HOOK_EXEC", "SANDBOX_EXEC"))),
                approved = true,
            )
            val chatOnly =
                PhoneTask(
                    sessionId = UUID.randomUUID().toString(),
                    text = "chat",
                    images = emptyList(),
                    model = "deepseek-flash",
                    effort = "low",
                    access = "none",
                    phoneTools = false,
                    fileTools = false,
                    workspace = "",
                )
            extensions.hooks(chatOnly, HookEvent.BeforeAgentStart) { _, _ -> false }
            assertTrue(runtime.states.value.isEmpty())
            val permissions = PermissionManager(context)
            val policy =
                PermissionContext(
                    sessionId = UUID.randomUUID().toString(),
                    actor = "hook",
                    extensionId = "a",
                )
            permissions.saveFromUser(
                JSONArray(
                        listOf(
                            PermissionRule(
                                    scope = PermissionScope.GLOBAL,
                                    capability = Capability.NETWORK,
                                    effect = PermissionEffect.ASK,
                                )
                                .json(),
                        ),
                    )
                    .toString(),
            )
            permissions.authorize(policy, Capability.NETWORK, "one.example") { true }
            assertEquals(
                PermissionEffect.ALLOW,
                permissions.evaluate(policy, Capability.NETWORK, "one.example").effect,
            )
            assertEquals(
                PermissionEffect.ASK,
                permissions.evaluate(policy, Capability.NETWORK, "two.example").effect,
            )
            assertEquals(
                PermissionEffect.ASK,
                permissions
                    .evaluate(policy.copy(extensionId = "b"), Capability.NETWORK, "one.example")
                    .effect,
            )
        } finally {
            check(root.canonicalPath.startsWith(target.cacheDir.canonicalPath + "/"))
            root.deleteRecursively()
        }
    }

    @Test
    fun independentHookMachineCannotReadParentRuntimeAndExportsOnlyApprovedFiles() =
        runBlocking<Unit> {
            val target = InstrumentationRegistry.getInstrumentation().targetContext
            val root = File(target.filesDir, "extension-runtime-qa").apply { mkdirs() }
            val context =
                object : ContextWrapper(target) {
                    override fun getFilesDir() = root
                }
            val template = File(root, "toolchains/light-v1").apply { mkdirs() }
            for (name in listOf("machine.state", "ready.json")) File(
                    target.filesDir,
                    "toolchains/light-v1/$name",
                )
                .copyTo(File(template, name), overwrite = true)
            val permissions = PermissionManager(context)
            val workspaces = WorkspaceManager(context)
            val runtime = RuntimeManager(context, permissions, workspaces)
            val extensions = ExtensionManager(context, permissions, runtime)
            val session = UUID.randomUUID().toString()
            val task =
                PhoneTask(
                    sessionId = session,
                    text = "QA",
                    images = emptyList(),
                    model = "deepseek-flash",
                    effort = "low",
                    access = "full",
                    phoneTools = false,
                    fileTools = true,
                    workspace = "",
                    terminalEnabled = true,
                    terminalConfig = "{\"level\":\"sandbox\"}",
                )
            val evidence = JSONObject()
            try {
                workspaces.importFile(
                    runtime.scope(task),
                    "note.txt",
                    "current workspace".toByteArray(),
                )
                val parent = runtime.execute(task, "printf private-parent > /root/parent-secret")
                assertEquals(0, parent.getInt("exitCode"))
                val hook =
                    JSONObject()
                        .put("id", UUID.randomUUID().toString())
                        .put("name", "isolated command")
                        .put("event", "BeforeCommand")
                        .put("action", "command")
                        .put("enabled", true)
                        .put(
                            "capabilities",
                            JSONArray(
                                listOf(
                                    "HOOK_EXEC",
                                    "SANDBOX_EXEC",
                                    "WORKSPACE_READ",
                                    "WORKSPACE_WRITE",
                                ),
                            ),
                        )
                        .put(
                            "command",
                            "set -e; test ! -e /root/parent-secret; test ! -e /data/data/dev.deepseek.mobile; test ! -e /workspace/other-project-secret; cat note.txt; printf hook-output > note.txt; test -f \"$" +
                                "DSHA_HOOK_INPUT\"",
                        )
                extensions.store.saveFromUser("hooks", hook, approved = true)
                extensions.hooks(
                    task,
                    HookEvent.BeforeCommand,
                    "terminal_exec",
                    JSONObject().put("command", "pwd"),
                ) { _, _ ->
                    false
                }
                val tree = workspaces.load(runtime.scope(task), task.id)
                assertEquals(
                    "hook-output",
                    String(workspaces.content(workspaces.current(tree).getValue("note.txt"))),
                )
                val result = runtime.execute(task, "cat note.txt; cat /root/parent-secret")
                assertEquals("hook-outputprivate-parent", result.getString("stdout"))
                evidence
                    .put("parentRuntimePreserved", true)
                    .put("hookImportedCurrentWorkspaceOnly", true)
                    .put("actualCommand", result)
                val denied =
                    hook
                        .put("action", "modify")
                        .put(
                            "arguments",
                            JSONObject().put("_agentId", UUID.randomUUID().toString()),
                        )
                extensions.store.saveFromUser("hooks", denied, approved = true)
                assertTrue(
                    runCatching {
                        extensions.hooks(task, HookEvent.BeforeCommand, "terminal_exec") { _, _ ->
                            false
                        }
                    }
                        .isFailure,
                )
                evidence.put("taskOwnershipModificationDenied", true).put("passed", true)
            } catch (error: Throwable) {
                evidence.put("failure", error.stackTraceToString())
                throw error
            } finally {
                runtime.close()
                File(target.filesDir, "v080-extension-runtime.json").writeText(evidence.toString(2))
                root.deleteRecursively()
            }
        }

    @Test
    fun livePublicMcpListsToolsAndReadsPublicRepositoryStructure() =
        runBlocking<Unit> {
            val evidence = JSONObject()
            try {
                val client = McpClient("https://mcp.deepwiki.com/mcp")
                val tools = client.listTools()
                assertTrue(
                    (0 until tools.length()).any {
                        tools.getJSONObject(it).getString("name") == "read_wiki_structure"
                    },
                )
                val result =
                    client.call(
                        "read_wiki_structure",
                        JSONObject().put("repoName", "modelcontextprotocol/servers"),
                    )
                assertFalse(result.optBoolean("isError"))
                evidence
                    .put("endpoint", "https://mcp.deepwiki.com/mcp")
                    .put("tools", tools)
                    .put("publicReadResult", result)
                    .put("passed", true)
            } catch (error: Throwable) {
                evidence.put("failure", error.stackTraceToString())
                throw error
            } finally {
                File(
                        InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
                        "v080-mcp-public.json",
                    )
                    .writeText(evidence.toString(2))
            }
        }
}
