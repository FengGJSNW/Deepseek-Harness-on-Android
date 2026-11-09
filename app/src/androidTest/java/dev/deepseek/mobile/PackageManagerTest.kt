package dev.deepseek.mobile

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.deepseek.mobile.harness.*
import dev.deepseek.mobile.runtime.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PackageManagerTest {
    @Test
    fun globalInstallRequiresConcreteApprovalAndDoesNotModifyExistingProjectRuntime() =
        runBlocking<Unit> {
            val target = InstrumentationRegistry.getInstrumentation().targetContext
            val directory = File(target.filesDir, "package-manager-qa").apply { mkdirs() }
            val context =
                object : ContextWrapper(target) {
                    override fun getFilesDir() = directory
                }
            val template = File(directory, "toolchains/light-v1").apply { mkdirs() }
            for (name in listOf("machine.state", "ready.json")) File(
                    target.filesDir,
                    "toolchains/light-v1/$name",
                )
                .copyTo(File(template, name), overwrite = true)
            val permissions = PermissionManager(context).apply { saveFromUser("[]") }
            val workspaces = WorkspaceManager(context)
            val runtime = RuntimeManager(context, permissions, workspaces)
            val packages = dev.deepseek.mobile.runtime.PackageManager(context, permissions, runtime)
            fun task() =
                PhoneTask(
                    sessionId = UUID.randomUUID().toString(),
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
            val a = task()
            val evidence = JSONObject()
            try {
                listOf("--root", "jq;id", "$(id)", "jq\nother").forEach { invalid ->
                    assertTrue(runCatching { packages.request(a, listOf(invalid)) }.isFailure)
                }
                val baseline =
                    runtime.execute(
                        a,
                        "set -e; test ! -e /usr/bin/jq; printf preserved > /root/project-proof",
                    )
                assertEquals(0, baseline.getInt("exitCode"))
                val request = packages.request(a, listOf("jq"))
                assertEquals("pending", request.getString("status"))
                val stillOld = runtime.execute(a, "test ! -e /usr/bin/jq")
                assertEquals(0, stillOld.getInt("exitCode"))
                val install = packages.installAfterUserApproval(request.getString("id"))
                assertEquals(0, install.getInt("exitCode"))
                assertEquals(
                    "installed",
                    packages
                        .requests()
                        .first { it.getString("id") == request.getString("id") }
                        .getString("status"),
                )
                val oldProject =
                    runtime.execute(a, "set -e; test ! -e /usr/bin/jq; cat /root/project-proof")
                assertEquals("preserved", oldProject.getString("stdout"))
                val fresh = runtime.execute(task(), "jq --version")
                assertEquals(0, fresh.getInt("exitCode"))
                assertTrue(fresh.getString("stdout").contains("jq"))
                permissions.saveFromUser(
                    JSONArray(
                            listOf(
                                PermissionRule(
                                        scope = PermissionScope.GLOBAL,
                                        capability = Capability.GLOBAL_TOOLCHAIN,
                                        effect = PermissionEffect.DENY,
                                    )
                                    .json(),
                            ),
                        )
                        .toString(),
                )
                assertTrue(runCatching { packages.request(a, listOf("file")) }.isFailure)
                evidence
                    .put("globalInstall", install)
                    .put("existingProject", oldProject)
                    .put("freshRuntime", fresh)
                    .put("denyCannotBeOverridden", true)
                    .put("passed", true)
            } catch (error: Throwable) {
                evidence.put("failure", error.stackTraceToString())
                throw error
            } finally {
                runtime.close()
                File(target.filesDir, "v080-package-manager.json").writeText(evidence.toString(2))
                directory.deleteRecursively()
            }
        }
}
