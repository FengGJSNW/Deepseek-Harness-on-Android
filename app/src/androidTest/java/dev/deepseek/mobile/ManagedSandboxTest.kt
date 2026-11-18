package dev.deepseek.mobile

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.deepseek.mobile.harness.HarnessCoordinator
import dev.deepseek.mobile.runtime.*
import dev.deepseek.mobile.terminal.TerminalConfigStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ManagedSandboxTest {
    // Match normal creation in the foreground; background WebViews are timer-throttled.
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)

    @Before
    fun emulatorOnly() {
        assumeTrue(android.os.Build.MODEL.contains("sdk_gphone"))
    }

    @Test
    fun twoGuestsKeepIndependentFilesAndRevocationStopsAccess() =
        runBlocking<Unit> {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val harness = HarnessCoordinator.get(context)
            val manager = SandboxManager.get(context)
            val session =
                withContext(Dispatchers.Main) {
                    AssistantState.initialize(context)
                    AssistantState.addSession()
                }
            val snapshot = TerminalConfigStore(context).load("sandbox").json().toString()
            val suffix = UUID.randomUUID().toString().take(8)
            fun task(record: SandboxRecord, files: List<String> = emptyList()) =
                PhoneTask(
                    sessionId = session.id,
                    text = "测试独立虚拟机",
                    images = emptyList(),
                    model = "deepseek-flash",
                    effort = "low",
                    access = "full",
                    phoneTools = false,
                    fileTools = true,
                    workspace = "",
                    terminalEnabled = true,
                    terminalConfig = snapshot,
                    sandboxId = record.id,
                    sandboxOnly = true,
                    files = files,
                )
            suspend fun waitForOperation(record: SandboxRecord) {
                withTimeout(240000) {
                    while (record.id in manager.operations.value) delay(80)
                }
                assertNull(manager.errors.value[record.id])
            }
            fun readArgs() = JSONObject().put("name", "marker.txt")
            val first =
                withContext(Dispatchers.Main) {
                    manager.create(
                        "Alpha-$suffix",
                        RuntimeKind.ALPINE,
                        SandboxOwner.SESSION,
                        session.id,
                        "",
                    )
                }
            var second: SandboxRecord? = null
            try {
                waitForOperation(first)
                manager.allowSession(first.id, session.id, true)
                assertTrue(manager.get(first.id)!!.diagnostics.contains("Alpine:"))
                val attachment =
                    AttachmentStore(context)
                        .save(
                            "explicit import".byteInputStream(),
                            session.id,
                            "fixture.txt",
                            "text/plain",
                        )
                val firstTask = task(first, listOf(attachment.id))
                val command =
                    harness.runtimes.execute(firstTask, "printf alpha > /workspace/marker.txt")
                assertEquals(0, command.getInt("exitCode"))
                assertEquals(
                    "alpha",
                    harness.runtimes
                        .workspaceTool(firstTask, "workspace_read", readArgs())
                        .getString("text"),
                )
                assertEquals(
                    "explicit import",
                    harness.runtimes
                        .workspaceTool(
                            firstTask,
                            "workspace_read",
                            JSONObject()
                                .put("name", sandboxAttachmentPath(attachment.id, attachment.name)),
                        )
                        .getString("text"),
                )
                val beta =
                    withContext(Dispatchers.Main) {
                        manager.create(
                            "Beta-$suffix",
                            RuntimeKind.ALPINE,
                            SandboxOwner.SESSION,
                            session.id,
                            "",
                        )
                    }
                second = beta
                waitForOperation(beta)
                manager.allowSession(beta.id, session.id, true)
                val secondTask = task(beta)
                val betaFiles =
                    harness.runtimes.workspaceTool(secondTask, "workspace_list", JSONObject())
                assertEquals(0, betaFiles.getInt("total"))
                assertTrue(
                    runCatching {
                        harness.runtimes.workspaceTool(
                            firstTask,
                            "workspace_list",
                            JSONObject(),
                            sandboxId = beta.id,
                        )
                    }
                        .isFailure,
                )
                assertEquals(
                    "alpha",
                    harness.runtimes
                        .workspaceTool(firstTask, "workspace_read", readArgs())
                        .getString("text"),
                )
                withContext(Dispatchers.Main) { manager.stop(first.id) }
                waitForOperation(first)
                assertTrue(
                    runCatching {
                        harness.runtimes.workspaceTool(firstTask, "workspace_read", readArgs())
                    }
                        .isFailure,
                )
                withContext(Dispatchers.Main) { manager.start(first.id) }
                waitForOperation(first)
                assertEquals(
                    "alpha",
                    harness.runtimes
                        .workspaceTool(firstTask, "workspace_read", readArgs())
                        .getString("text"),
                )
                val entered = CompletableDeferred<Unit>()
                val pending = launch {
                    manager.withAccess(first.id, firstTask) {
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                }
                entered.await()
                manager.change(first.id) { it.copy(sessionDenials = setOf(session.id)) }
                pending.join()
                assertTrue(pending.isCancelled)
                assertTrue(
                    runCatching {
                        harness.runtimes.workspaceTool(firstTask, "workspace_read", readArgs())
                    }
                        .isFailure,
                )
                withContext(Dispatchers.Main) { manager.delete(beta.id) }
                waitForOperation(beta)
                assertNull(manager.get(beta.id))
                assertFalse(
                    File(context.filesDir, "runtimes/instances/sandbox/" + beta.id).exists(),
                )
                assertNotNull(manager.get(first.id))
            } finally {
                withContext(Dispatchers.Main) {
                    if (manager.get(first.id) != null) manager.delete(first.id)
                    second?.let { if (manager.get(it.id) != null) manager.delete(it.id) }
                }
                waitForOperation(first)
                second?.let { waitForOperation(it) }
            }
        }

    @Test
    fun defaultStartsFromImportedToolchainInItsOwnInstance() =
        runBlocking<Unit> {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val manager = SandboxManager.get(context)
            val record =
                withContext(Dispatchers.Main) {
                    AssistantState.initialize(context)
                    val session = AssistantState.addSession()
                    manager.create(
                        "Light-" + UUID.randomUUID().toString().take(8),
                        RuntimeKind.LIGHT,
                        SandboxOwner.SESSION,
                        session.id,
                        "",
                    )
                }
            suspend fun settled() =
                withTimeout(900000) {
                    while (record.id in manager.operations.value) delay(100)
                }
            try {
                settled()
                assertNull(manager.errors.value[record.id])
                assertTrue(manager.get(record.id)!!.diagnostics.contains("/usr/bin/ssh"))
                assertTrue(RuntimeImages.get(context).hasImage("tools"))
                assertTrue(
                    File(
                            context.filesDir,
                            "runtimes/instances/sandbox/${record.id}/preset-ready.json",
                        )
                        .isFile,
                )
                assertTrue(manager.get(record.id)!!.diagnostics.contains("/usr/bin/python3"))
                assertTrue(manager.get(record.id)!!.diagnostics.contains("/usr/bin/node"))
                manager.allowSession(record.id, record.ownerId, true)
                val probeTask =
                    PhoneTask(
                        sessionId = record.ownerId,
                        text = "toolchain probe",
                        images = emptyList(),
                        model = "deepseek-flash",
                        effort = "low",
                        access = "full",
                        phoneTools = false,
                        fileTools = true,
                        workspace = "",
                        terminalEnabled = true,
                        terminalConfig =
                            TerminalConfigStore(context).load("sandbox").json().toString(),
                        sandboxId = record.id,
                        sandboxIds = setOf(record.id),
                        sandboxOnly = true,
                    )
                val probe =
                    HarnessCoordinator.get(context)
                        .runtimes
                        .execute(
                            probeTask,
                            "set -e; uname -m; uname -r; id; git --version; ssh -V 2>&1; python3 --version; node --version; npm --version; printf 'Optional tools: '; for tool in docker gcc make systemctl openrc; do command -v \"\$tool\" || true; done; printf 'Cgroups: '; ls /sys/fs/cgroup 2>/dev/null || true",
                            timeout = 180,
                        )
                assertEquals(0, probe.getInt("exitCode"))
                File(context.cacheDir, "lite-capabilities.txt").writeText(probe.getString("stdout"))
            } finally {
                withContext(Dispatchers.Main) { manager.delete(record.id) }
                settled()
            }
        }
}
