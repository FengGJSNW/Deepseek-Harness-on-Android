package dev.deepseek.mobile

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.deepseek.mobile.harness.*
import dev.deepseek.mobile.runtime.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RuntimeManagerTest {
    private fun task(session: String, project: String = "") =
        PhoneTask(
            sessionId = session,
            projectId = project,
            text = "只在模拟器测试私有工作域",
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

    @Test
    fun fileDenyAndSensitiveSymlinkCannotBeBypassedByVmOrAlias() =
        runBlocking<Unit> {
            val target = InstrumentationRegistry.getInstrumentation().targetContext
            val directory =
                File(target.cacheDir, "file-policy-${UUID.randomUUID()}").apply { mkdirs() }
            val context =
                object : ContextWrapper(target) {
                    override fun getFilesDir() = directory
                }
            val permissions = PermissionManager(context)
            val workspaces = WorkspaceManager(context)
            val manager = RuntimeManager(context, permissions, workspaces)
            val t = task(UUID.randomUUID().toString())
            val scope = manager.scope(t)
            try {
                workspaces.importFile(scope, "secret.txt", "private to the rule".toByteArray())
                java.nio.file.Files.createSymbolicLink(
                    File(workspaces.workspace(scope), "alias.txt").toPath(),
                    java.nio.file.Paths.get("secret.txt"),
                )
                val deny =
                    PermissionRule(
                        scope = PermissionScope.GLOBAL,
                        capability = Capability.WORKSPACE_READ,
                        resource = "/workspace/secret.txt",
                        effect = PermissionEffect.DENY,
                    )
                permissions.saveFromUser(org.json.JSONArray(listOf(deny.json())).toString())
                assertTrue(runCatching { manager.execute(t, "cat alias.txt") }.isFailure)
                assertEquals("not_initialized", manager.info(scope).phase)
                assertTrue(
                    runCatching {
                        manager.workspaceTool(
                            t,
                            "workspace_read",
                            JSONObject().put("name", "alias.txt"),
                        )
                    }
                        .isFailure,
                )
                permissions.saveFromUser(
                    org.json
                        .JSONArray(listOf(deny.copy(effect = PermissionEffect.ASK).json()))
                        .toString(),
                )
                assertTrue(
                    runCatching {
                        manager.workspaceTool(
                            t,
                            "workspace_read",
                            JSONObject().put("name", "alias.txt"),
                        )
                    }
                        .isFailure,
                )
                permissions.saveFromUser("[]")
                assertEquals(
                    "private to the rule",
                    manager
                        .workspaceTool(t, "workspace_read", JSONObject().put("name", "alias.txt"))
                        .getString("text"),
                )
            } finally {
                manager.close()
                check(directory.canonicalPath.startsWith(target.cacheDir.canonicalPath + "/"))
                directory.deleteRecursively()
            }
        }

    @Test
    fun temporaryLifecycleDropsEnvironmentButKeepsVersionedWorkspace() =
        runBlocking<Unit> {
            val target = InstrumentationRegistry.getInstrumentation().targetContext
            val directory = File(target.filesDir, "runtime-manager-qa").apply { mkdirs() }
            val context =
                object : ContextWrapper(target) {
                    override fun getFilesDir() = directory
                }
            val permissions = PermissionManager(context)
            val workspaces = WorkspaceManager(context)
            val manager = RuntimeManager(context, permissions, workspaces)
            val session = UUID.randomUUID().toString()
            val t = task(session)
            val scope = manager.scope(t)
            val evidence = JSONObject()
            try {
                manager.selectKind(scope, RuntimeKind.ALPINE)
                manager.selectLifetime(scope, RuntimeLifetime.TEMPORARY)
                val first =
                    manager.execute(
                        t,
                        "printf ephemeral > /root/custom-proof; printf retained > note.txt",
                    )
                assertEquals(0, first.getInt("exitCode"))
                val merged =
                    manager.resolveWorktree(
                        workspaces.load(scope, t.id),
                        true,
                        PermissionContext(sessionId = session, actor = "user"),
                    ) {
                        true
                    }
                assertTrue(merged.getBoolean("merged"))
                assertEquals("not_initialized", manager.info(scope).phase)
                assertEquals("retained", File(workspaces.workspace(scope), "note.txt").readText())
                val second =
                    manager.execute(task(session), "test ! -e /root/custom-proof && cat note.txt")
                assertEquals(0, second.getInt("exitCode"))
                assertEquals("retained", second.getString("stdout"))
                manager.close()
                assertEquals("not_initialized", manager.info(scope).phase)
                evidence
                    .put("first", first)
                    .put("second", second)
                    .put("workspacePreserved", true)
                    .put("passed", true)
            } catch (error: Throwable) {
                evidence.put("failure", error.stackTraceToString())
                throw error
            } finally {
                manager.close()
                File(target.filesDir, "v080-temporary-runtime.json").writeText(evidence.toString(2))
                File(directory, "runtimes").deleteRecursively()
            }
        }

    @Test
    fun copyVerifiedEmptyToolchainForAppUiChecks() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.filesDir, "runtime-manager-qa/toolchains/light-v1")
        assertTrue(File(source, "ready.json").isFile)
        val probes = JSONObject(File(source, "probes.json").readText())
        for (name in listOf("git", "ssh", "python", "node", "npm")) assertEquals(
            0,
            probes.getJSONObject(name).getInt("exitCode"),
        )
        val target = File(context.filesDir, "toolchains/light-v1").apply { mkdirs() }
        if (!File(target, "ready.json").exists()) {
            File(source, "machine.state").copyTo(File(target, "machine.state"), overwrite = true)
            File(source, "probes.json").copyTo(File(target, "probes.json"), overwrite = true)
            File(source, "ready.json").copyTo(File(target, "ready.json"), overwrite = true)
        }
    }

    @Test
    fun projectWorktreesPackagesResetAndRealDevelopmentTools() =
        runBlocking<Unit> {
            val target = InstrumentationRegistry.getInstrumentation().targetContext
            val directory = File(target.filesDir, "runtime-manager-qa").apply { mkdirs() }
            val context =
                object : ContextWrapper(target) {
                    override fun getFilesDir() = directory
                }
            val permissions = PermissionManager(context)
            val workspaces = WorkspaceManager(context)
            val manager = RuntimeManager(context, permissions, workspaces)
            val project = UUID.randomUUID().toString()
            val scope = WorkspaceScope("project", project)
            val sessionA = UUID.randomUUID().toString()
            val sessionB = UUID.randomUUID().toString()
            val a = task(sessionA, project)
            val b = task(sessionB, project)
            val evidence = JSONObject()
            try {
                manager.selectKind(scope, RuntimeKind.ALPINE)
                workspaces.importFile(scope, "note.txt", "base".toByteArray())
                val aResult =
                    manager.execute(
                        a,
                        "set -e; cat note.txt; printf agent-a > note.txt; mkdir -p /usr/local/share; printf shared > /usr/local/share/project-proof",
                    )
                assertEquals(0, aResult.getInt("exitCode"))
                val bResult =
                    manager.execute(
                        b,
                        "set -e; cat note.txt; cat /usr/local/share/project-proof; printf agent-b > note.txt",
                    )
                evidence.put("a", aResult).put("b", bResult)
                assertEquals("baseshared", bResult.getString("stdout"))
                val treeA = workspaces.load(scope, a.id)
                val treeB = workspaces.load(scope, b.id)
                assertTrue(workspaces.merge(treeA).getBoolean("merged"))
                assertFalse(workspaces.merge(treeB).getBoolean("merged"))
                assertEquals("agent-a", File(workspaces.workspace(scope), "note.txt").readText())
                File(context.filesDir, "memory.md").writeText("保留的长期记忆")
                manager.reset(scope)
                assertEquals("保留的长期记忆", File(context.filesDir, "memory.md").readText())
                val reset =
                    manager.execute(
                        task(sessionA, project),
                        "set -e; cat note.txt; test ! -e /usr/local/share/project-proof",
                    )
                evidence.put("reset", reset)
                assertEquals(0, reset.getInt("exitCode"))
                assertEquals("agent-a", reset.getString("stdout"))
                val light = task(UUID.randomUUID().toString())
                val tools =
                    manager.execute(
                        light,
                        "set -e; python3 -c 'print(6 * 7)'; node -e 'console.log(6 * 7)'; git init -q; git -c user.name=DSHA -c user.email=qa@example.invalid commit --allow-empty -qm proof; git log -1 --format=%s; ssh-keygen -q -t ed25519 -N '' -f qa_key; ssh-keygen -l -f qa_key.pub",
                        timeout = 120,
                    )
                evidence.put("tools", tools)
                assertEquals(0, tools.getInt("exitCode"))
                assertTrue(tools.getString("stdout").contains("42\n42"))
                assertTrue(tools.getString("stdout").contains("proof"))
                assertTrue(tools.getString("stdout").contains("ED25519"))
                evidence.put("passed", true)
            } catch (error: Throwable) {
                evidence.put("failure", error.stackTraceToString())
                throw error
            } finally {
                withContext(NonCancellable) { manager.close() }
                File(target.filesDir, "qa-v080")
                    .apply { mkdirs() }
                    .resolve("runtime-manager.json")
                    .writeText(evidence.toString(2))
                File(directory, "runtimes").deleteRecursively()
            }
        }
}
