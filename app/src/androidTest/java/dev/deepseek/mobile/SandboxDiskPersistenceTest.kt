package dev.deepseek.mobile

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import dev.deepseek.mobile.harness.HarnessCoordinator
import dev.deepseek.mobile.runtime.*
import dev.deepseek.mobile.terminal.TerminalConfigStore
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class SandboxDiskPersistenceTest {
    @Test
    fun diskSurvivesDeletingItsFirstComputerAndCanBeMountedByAnother() = runBlocking {
        assumeTrue(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = SandboxManager.get(context)
        val disks = SandboxDisks.get(context)
        val runtime = HarnessCoordinator.get(context).runtimes
        val session =
            withContext(Dispatchers.Main) {
                AssistantState.initialize(context)
                AssistantState.addSession()
            }
        val suffix = UUID.randomUUID().toString().take(8)
        val disk = disks.create("persistent-$suffix", 64L * 1024 * 1024)
        val created = mutableListOf<SandboxRecord>()
        suspend fun waitFor(record: SandboxRecord) {
            withTimeout(240000) { while (record.id in manager.operations.value) delay(80) }
            assertNull(manager.errors.value[record.id])
        }
        suspend fun computer(name: String): SandboxRecord {
            val record =
                withContext(Dispatchers.Main) {
                    manager.create(
                        "$name-$suffix",
                        RuntimeKind.ALPINE,
                        SandboxOwner.SESSION,
                        session.id,
                        "",
                    )
                }
            created += record
            waitFor(record)
            manager.allowSession(record.id, session.id, true)
            return record
        }
        fun task(record: SandboxRecord) =
            PhoneTask(
                sessionId = session.id,
                text = "disk regression",
                images = emptyList(),
                model = "deepseek-flash",
                effort = "low",
                access = "full",
                phoneTools = false,
                fileTools = true,
                workspace = "",
                terminalEnabled = true,
                terminalConfig = TerminalConfigStore(context).load("sandbox").json().toString(),
                sandboxId = record.id,
                sandboxIds = setOf(record.id),
                sandboxOnly = true,
            )
        try {
            val first = computer("first")
            disks.change(disk.id) { it.copy(allowed = setOf(first.id)) }
            assertEquals(
                0,
                runtime
                    .execute(task(first), "printf persistent > /disks/${disk.id}/marker")
                    .getInt("exitCode"),
            )
            withContext(Dispatchers.Main) { manager.delete(first.id) }
            waitFor(first)
            assertTrue(disks.records.value.any { it.id == disk.id })
            val second = computer("second")
            assertEquals(
                0,
                runtime.execute(task(second), "test ! -e /disks/${disk.id}").getInt("exitCode"),
            )
            disks.change(disk.id) { it.copy(allSandboxes = true) }
            assertEquals(
                "persistent",
                runtime
                    .execute(task(second), "cat /disks/${disk.id}/marker")
                    .getString("stdout")
                    .trim(),
            )
            disks.change(disk.id) { it.copy(denied = setOf(second.id)) }
            assertEquals(
                0,
                runtime.execute(task(second), "test ! -e /disks/${disk.id}").getInt("exitCode"),
            )
        } finally {
            for (record in created) if (manager.get(record.id) != null) {
                withContext(Dispatchers.Main) { manager.delete(record.id) }
                waitFor(record)
            }
            disks.delete(disk.id)
        }
    }
}
