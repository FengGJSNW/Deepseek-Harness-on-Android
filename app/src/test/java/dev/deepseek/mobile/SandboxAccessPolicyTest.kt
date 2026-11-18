package dev.deepseek.mobile

import dev.deepseek.mobile.runtime.*
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class SandboxAccessPolicyTest {
    private fun task(ids: Set<String>?, only: Boolean = true, selected: String = "") =
        PhoneTask(
            sessionId = UUID.randomUUID().toString(),
            text = "scope regression",
            images = emptyList(),
            model = "deepseek-flash",
            effort = "low",
            access = "full",
            phoneTools = false,
            fileTools = true,
            workspace = "",
            sandboxOnly = only,
            sandboxId = selected,
            sandboxIds = ids,
        )

    @Test
    fun taskSnapshotAcceptsMultipleEnvironmentsButNeverAnUnlistedOne() {
        val a = UUID.randomUUID().toString()
        val b = UUID.randomUUID().toString()
        val request = task(setOf(a, b), selected = a)
        assertTrue(request.acceptsSandbox(a))
        assertTrue(request.acceptsSandbox(b))
        assertFalse(request.acceptsSandbox(""))
        assertFalse(request.acceptsSandbox(UUID.randomUUID().toString()))
        assertFalse(task(emptySet(), only = false).acceptsSandbox(a))
        assertTrue(task(null, selected = a).acceptsSandbox(a))
        assertFalse(task(null, selected = a).acceptsSandbox(b))
    }

    @Test
    fun sessionDenialOverridesInheritedProjectGrant() {
        val record =
            SandboxRecord(
                UUID.randomUUID().toString(),
                "shared",
                RuntimeKind.ALPINE,
                SandboxOwner.GLOBAL,
                "",
                projectGrants = setOf("project"),
                sessionDenials = setOf("denied"),
            )
        assertTrue(record.allows("allowed", "project"))
        assertFalse(record.allows("denied", "project"))
        assertFalse(record.allows("independent", ""))
        assertTrue(record.copy(sessionGrants = setOf("independent")).allows("independent", ""))
        assertFalse(record.copy(deleting = true).allows("allowed", "project"))
    }

    @Test
    fun diskGrantRoundTripPreservesExplicitDenialOverAllSandboxes() {
        val a = UUID.randomUUID().toString()
        val b = UUID.randomUUID().toString()
        val disk =
            SandboxDisk(
                UUID.randomUUID().toString(),
                "data",
                allSandboxes = true,
                denied = setOf(a),
            )
        val restored = SandboxDisk.from(disk.json())
        assertFalse(restored.allows(a))
        assertTrue(restored.allows(b))
        assertFalse(restored.copy(allSandboxes = false).allows(b))
        assertTrue(restored.copy(allSandboxes = false, allowed = setOf(b)).allows(b))
    }

    @Test
    fun volumeManifestPathsCannotBecomeHostPaths() {
        for (path in
            listOf(
                "../secret",
                "/etc/passwd",
                "dir/../file",
                "dir\\file",
                "dir//file",
                "a\u0000b",
            )) assertTrue(runCatching { SandboxDisks.validPath(path) }.isFailure)
        assertEquals("docs/说明.md", SandboxDisks.validPath("docs/说明.md"))
    }

    @Test
    fun advancedOptionsProduceTheRequestedPackagePlan() {
        val minimal = SandboxPreset.defaults(RuntimeKind.ALPINE)
        assertTrue(minimal.packages.isEmpty())
        val custom =
            SandboxPreset(AlpineRelease.V323, python = true, node = false, developerTools = false)
        assertEquals(listOf("python3 py3-pip"), custom.packages.map { it.second })
        assertEquals(custom, SandboxPreset.from(custom.json(), RuntimeKind.LIGHT))
        assertTrue(SandboxPreset(nodeCurrent = true).packages.any { it.second == "nodejs-current" })
    }
}
