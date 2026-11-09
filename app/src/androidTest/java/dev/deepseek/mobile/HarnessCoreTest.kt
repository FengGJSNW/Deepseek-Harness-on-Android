package dev.deepseek.mobile

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.deepseek.mobile.harness.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class HarnessCoreTest {
    @Test
    fun broadAskCannotBeClearedByAnAllowForOnlyOnePath() = fixture { context ->
        val manager = PermissionManager(context)
        val policy = PermissionContext(sessionId = UUID.randomUUID().toString())
        val ask =
            PermissionRule(
                scope = PermissionScope.GLOBAL,
                capability = Capability.WORKSPACE_READ,
                resource = "/workspace/*",
                effect = PermissionEffect.ASK,
            )
        val narrow =
            PermissionRule(
                scope = PermissionScope.SESSION,
                owner = policy.sessionId,
                capability = Capability.WORKSPACE_READ,
                resource = "/workspace/__policy_probe__",
                effect = PermissionEffect.ALLOW,
            )
        manager.saveFromUser(JSONArray(listOf(ask.json(), narrow.json())).toString())
        assertTrue(manager.requiresBroker(policy, setOf(Capability.WORKSPACE_READ)))
        manager.saveFromUser(
            JSONArray(listOf(ask.json(), narrow.copy(resource = "/workspace/*").json())).toString(),
        )
        assertFalse(manager.requiresBroker(policy, setOf(Capability.WORKSPACE_READ)))
    }

    @Test
    fun migrationIsSessionScopedIdempotentAndDoesNotFollowLinks() = fixture { context ->
        val manager = WorkspaceManager(context)
        val session = UUID.randomUUID().toString()
        val scope = WorkspaceScope("project", UUID.randomUUID().toString())
        val old = File(context.filesDir, "terminal/workplace/session_$session").apply { mkdirs() }
        File(old, "note.txt").writeText("old session file")
        val secret = File(context.filesDir, "private.json").apply { writeText("do not import") }
        java.nio.file.Files.createSymbolicLink(File(old, "secret-link").toPath(), secret.toPath())
        val result = manager.migrateLegacy(scope, session)
        assertEquals(1, result.getInt("copied"))
        assertEquals(1, result.getInt("skippedLinksOrSpecialFiles"))
        assertEquals(
            "old session file",
            File(manager.workspace(scope), "legacy/$session/note.txt").readText(),
        )
        assertFalse(File(manager.workspace(scope), "legacy/$session/secret-link").exists())
        File(old, "note.txt").writeText("later legacy edit")
        manager.migrateLegacy(scope, session)
        assertEquals(
            "old session file",
            File(manager.workspace(scope), "legacy/$session/note.txt").readText(),
        )
        assertEquals("later legacy edit", File(old, "note.txt").readText())
        assertEquals("do not import", secret.readText())
    }

    @Test
    fun directoryReplacementAndInvalidExportCannotPartiallyOverwriteFiles() = fixture { context ->
        val manager = WorkspaceManager(context)
        val scope = WorkspaceScope("session", UUID.randomUUID().toString())
        manager.importFile(scope, "folder/old.txt", "old".toByteArray())
        val tree = manager.begin(scope, UUID.randomUUID().toString())
        manager.receive(tree, mapOf("folder" to manager.stage("now a file".toByteArray())))
        assertTrue(manager.merge(tree).getBoolean("merged"))
        assertEquals("now a file", File(manager.workspace(scope), "folder").readText())
        val next = manager.begin(scope, UUID.randomUUID().toString())
        val before = manager.current(next)
        val invalid =
            mapOf(
                "x" to manager.stage("file".toByteArray()),
                "x/y" to manager.stage("child".toByteArray()),
            )
        assertTrue(runCatching { manager.receive(next, invalid) }.isFailure)
        assertEquals(before, manager.current(next))
        assertFalse(File(manager.workspace(scope), "x").exists())
    }

    @Test
    fun interruptedApprovalsAndMemoryProposalsNeverExecuteAfterRestart() = fixture { context ->
        val task =
            PhoneTask(
                sessionId = UUID.randomUUID().toString(),
                text = "QA",
                images = emptyList(),
                model = "deepseek-flash",
                effort = "low",
                access = "full",
                phoneTools = false,
                fileTools = false,
                workspace = "",
            )
        val ledger = ApprovalLedger(context)
        ledger.begin(task, "single permission", "literal id -u")
        assertEquals("interrupted", ApprovalLedger(context).list().single().getString("status"))
        val memory = MemoryStore(context)
        val manager = ContextManager(context, memory, PermissionManager(context))
        val original = memory.read("global", "")
        val id = manager.recordProposal(task, original, "long term fact")
        val restored = ContextManager(context, memory, PermissionManager(context))
        assertEquals("interrupted", restored.proposals().getJSONObject(0).getString("status"))
        assertEquals(original.sha256, memory.read("global", "").sha256)
        restored.applyProposalFromUser(id)
        assertEquals("long term fact", memory.read("global", "").text)
        assertTrue(runCatching { restored.applyProposalFromUser(id) }.isFailure)
    }

    private fun fixture(block: (Context) -> Unit) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(target.cacheDir, "harness-qa-${UUID.randomUUID()}").apply { mkdirs() }
        val context =
            object : ContextWrapper(target) {
                override fun getFilesDir() = directory
            }
        try {
            block(context)
        } finally {
            check(
                directory.canonicalPath.startsWith(target.cacheDir.canonicalPath + File.separator),
            )
            directory.deleteRecursively()
        }
    }

    @Test
    fun parallelWorktreesMergeDisjointFilesAndRejectConflicts() = fixture { context ->
        val manager = WorkspaceManager(context)
        val scope = WorkspaceScope("project", UUID.randomUUID().toString())
        manager.importFile(scope, "a.txt", "base-a".toByteArray())
        manager.importFile(scope, "b.txt", "base-b".toByteArray())
        val a = manager.begin(scope, UUID.randomUUID().toString())
        val b = manager.begin(scope, UUID.randomUUID().toString())
        File(a.root, "a.txt").writeText("agent-a")
        File(b.root, "b.txt").writeText("agent-b")
        assertEquals("base-a", File(b.root, "a.txt").readText())
        assertTrue(manager.merge(a).getBoolean("merged"))
        assertTrue(manager.merge(b).getBoolean("merged"))
        assertEquals("agent-a", File(manager.workspace(scope), "a.txt").readText())
        assertEquals("agent-b", File(manager.workspace(scope), "b.txt").readText())
        val c = manager.begin(scope, UUID.randomUUID().toString())
        val d = manager.begin(scope, UUID.randomUUID().toString())
        File(c.root, "a.txt").writeText("new-c")
        File(d.root, "a.txt").writeText("new-d")
        assertTrue(manager.merge(c).getBoolean("merged"))
        val conflict = manager.merge(d)
        assertFalse(conflict.getBoolean("merged"))
        assertEquals("a.txt", conflict.getJSONArray("conflicts").getString(0))
        assertEquals("new-c", File(manager.workspace(scope), "a.txt").readText())
        manager.discard(d)
        assertFalse(d.root.exists())
        assertEquals("discarded", manager.load(scope, d.taskId).state)
        assertFalse(File(manager.workspace(scope), ".git").exists())
    }

    @Test
    fun workspacePathsAndExternalSymlinksCannotReachAppData() = fixture { context ->
        val manager = WorkspaceManager(context)
        val scope = WorkspaceScope("session", UUID.randomUUID().toString())
        listOf("../x", "/data/x", "a/../../b", "a\\b", "a//b", "./a").forEach { path ->
            assertTrue(
                runCatching { manager.importFile(scope, path, "no".toByteArray()) }.isFailure,
            )
        }
        val sentinel = File(context.filesDir, "secret.txt").apply { writeText("private") }
        java.nio.file.Files.createSymbolicLink(
            File(manager.workspace(scope), "link").toPath(),
            sentinel.toPath(),
        )
        assertTrue(
            runCatching { manager.importFile(scope, "link/child", byteArrayOf(1)) }.isFailure,
        )
        assertEquals("private", sentinel.readText())
    }

    @Test
    fun permissionScopesHardDenialAndSingleOperationDoNotLeak() = fixture { context ->
        runBlocking {
            val manager = PermissionManager(context)
            val session = UUID.randomUUID().toString()
            val project = UUID.randomUUID().toString()
            val operation = PermissionContext(sessionId = session, projectId = project)
            val rules =
                listOf(
                    PermissionRule(
                        scope = PermissionScope.GLOBAL,
                        capability = Capability.ROOT_EXEC,
                        effect = PermissionEffect.DENY,
                        reason = "Global security DENY",
                    ),
                    PermissionRule(
                        scope = PermissionScope.OPERATION,
                        owner = operation.operationId,
                        capability = Capability.ROOT_EXEC,
                        effect = PermissionEffect.ALLOW,
                    ),
                    PermissionRule(
                        scope = PermissionScope.PROJECT,
                        owner = project,
                        capability = Capability.NETWORK,
                        effect = PermissionEffect.ASK,
                    ),
                    PermissionRule(
                        scope = PermissionScope.SESSION,
                        owner = session,
                        capability = Capability.NETWORK,
                        effect = PermissionEffect.ALLOW,
                    ),
                )
            manager.saveFromUser(JSONArray(rules.map { it.json() }).toString())
            assertEquals(
                PermissionEffect.DENY,
                manager.evaluate(operation, Capability.ROOT_EXEC).effect,
            )
            assertEquals(
                PermissionEffect.ALLOW,
                manager.evaluate(operation, Capability.NETWORK).effect,
            )
            assertEquals(
                PermissionEffect.ASK,
                manager
                    .evaluate(
                        operation.copy(sessionId = UUID.randomUUID().toString()),
                        Capability.NETWORK,
                    )
                    .effect,
            )
            assertEquals(
                PermissionEffect.ASK,
                manager.evaluate(operation, Capability.SHELL_EXEC).effect,
            )
            manager.authorize(operation, Capability.SHELL_EXEC) { true }
            assertEquals(
                PermissionEffect.ALLOW,
                manager.evaluate(operation, Capability.SHELL_EXEC).effect,
            )
            assertEquals(
                PermissionEffect.ASK,
                manager
                    .evaluate(
                        operation.copy(operationId = UUID.randomUUID().toString()),
                        Capability.SHELL_EXEC,
                    )
                    .effect,
            )
            manager.finish(operation)
            assertEquals(
                PermissionEffect.ASK,
                manager.evaluate(operation, Capability.SHELL_EXEC).effect,
            )
            assertEquals(
                PermissionEffect.DENY,
                manager
                    .evaluate(operation.copy(actor = "hook"), Capability.INSTRUCTIONS_WRITE)
                    .effect,
            )
            assertEquals(
                PermissionEffect.DENY,
                manager
                    .evaluate(
                        operation.copy(
                            actor = "mcp",
                            declaredCapabilities = setOf(Capability.NETWORK),
                        ),
                        Capability.WORKSPACE_READ,
                    )
                    .effect,
            )
        }
    }
}
