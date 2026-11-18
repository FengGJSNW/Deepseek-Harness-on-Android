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
    fun retiredImplicitRuntimesDoNotCreateMachinesOrFallbackToPhone() =
        runBlocking<Unit> {
            org.junit.Assume.assumeTrue(android.os.Build.MODEL.contains("sdk_gphone"))
            val target = InstrumentationRegistry.getInstrumentation().targetContext
            val directory =
                File(target.cacheDir, "retired-runtime-${UUID.randomUUID()}").apply { mkdirs() }
            val context =
                object : ContextWrapper(target) {
                    override fun getFilesDir() = directory
                }
            val manager =
                RuntimeManager(context, PermissionManager(context), WorkspaceManager(context))
            try {
                for (project in listOf("", UUID.randomUUID().toString())) {
                    val request = task(UUID.randomUUID().toString(), project)
                    val execute = runCatching { manager.execute(request, "id") }
                    assertTrue(execute.exceptionOrNull()?.message.orEmpty().contains("Sandbox"))
                    val terminal = runCatching { manager.openTerminal(request) }
                    assertTrue(terminal.exceptionOrNull()?.message.orEmpty().contains("Sandbox"))
                }
                assertTrue(manager.states.value.isEmpty())
                assertTrue(File(directory, "runtimes/instances").listFiles().orEmpty().isEmpty())
            } finally {
                manager.close()
                check(directory.canonicalFile.parentFile == target.cacheDir.canonicalFile)
                directory.deleteRecursively()
            }
        }
}
