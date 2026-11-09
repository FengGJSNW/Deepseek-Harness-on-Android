package dev.deepseek.mobile

import androidx.test.platform.app.InstrumentationRegistry
import dev.deepseek.mobile.terminal.*
import java.util.UUID
import junit.framework.TestCase
import kotlinx.coroutines.*
import org.json.JSONObject

/** Emulator-only checks. They never select the physical device or acquire root. */
class TerminalPrivilegeTest : TestCase() {
    private val virtual = "/dev.deepseek.mobile/workplace/session_${UUID.randomUUID()}"

    fun testLiteralInterpretersRetainNestedHardRules() {
        val policy = TerminalPolicy(TerminalPolicy.defaults())
        assertEquals(
            RuleDecision.ALLOW,
            policy.inspect("sh -c 'id -u; pwd'", virtual, virtual).decision,
        )
        assertEquals(
            RuleDecision.ALLOW,
            policy.inspect("which ssh-keygen", virtual, virtual).decision,
        )
        assertEquals(RuleDecision.ALLOW, policy.inspect("command -v sh", virtual, virtual).decision)
        for (script in
            listOf(
                "sh -c 'rm -rf /'",
                "su -c 'echo x > /system/x'",
                "sh -c 'sh -c \\\"echo x > /proc/1/mem\\\"'",
                "sh -c 'echo \u0024(HOME)'",
            )) {
            assertEquals(
                script,
                RuleDecision.DENY,
                policy.inspect(script, virtual, virtual).decision,
            )
        }
        val command = CommandParser.parse("sh -c 'echo hello > note.txt'").commands.single()
        assertTrue(
            TerminalPolicy.pathUses(command, virtual).any {
                it.path == "$virtual/note.txt" && 'w' in it.permissions
            },
        )
        val json = JSONObject(TerminalPolicy.defaults())
        json
            .getJSONArray("paths")
            .put(
                JSONObject()
                    .put("path", "$virtual/note.txt")
                    .put("permissions", "w")
                    .put("effect", "deny"),
            )
        assertEquals(
            RuleDecision.DENY,
            TerminalPolicy(json.toString())
                .inspect("sh -c 'echo hello > note.txt'", virtual, virtual)
                .decision,
        )
    }

    fun testKeygenHasExplicitPrivateAndPublicTargets() {
        val policy = TerminalPolicy(TerminalPolicy.defaults())
        val command = "ssh-keygen -t ed25519 -f notes/key -N '' -C 'DSHA test'"
        assertEquals(RuleDecision.SENSITIVE, policy.inspect(command, virtual, virtual).decision)
        val uses = TerminalPolicy.pathUses(CommandParser.parse(command).commands.single(), virtual)
        assertTrue(uses.any { it.path == "$virtual/notes/key" && 'w' in it.permissions })
        assertTrue(uses.any { it.path == "$virtual/notes/key.pub" && 'w' in it.permissions })
        assertEquals(
            RuleDecision.DENY,
            policy.inspect("ssh-keygen -t ed25519 -f /system/key -N ''", virtual, virtual).decision,
        )
        assertEquals(
            RuleDecision.DENY,
            policy.inspect("ssh-keygen -D arbitrary.so", virtual, virtual).decision,
        )
        assertEquals(RuleDecision.DENY, policy.inspect("nano file.txt", virtual, virtual).decision)
    }

    fun testUnavailableConnectionIsStructuredAndStartsNoCommand() = runBlocking {
        val ins = InstrumentationRegistry.getInstrumentation()
        val context = ins.targetContext
        val manager =
            withContext(Dispatchers.Main) {
                TerminalManager(
                    context,
                    { _, _, _ -> error("No reviewer should run before connection") },
                    { _, _, _ -> error("No approval expected") },
                )
            }
        try {
            val task =
                PhoneTask(
                    sessionId = UUID.randomUUID().toString(),
                    text = "offline connection check",
                    images = emptyList(),
                    model = "deepseek-flash",
                    effort = "low",
                    access = "full",
                    phoneTools = false,
                    fileTools = false,
                    workspace = "",
                    terminalEnabled = true,
                    terminalConfig =
                        TerminalSnapshot(
                                "shell",
                                TerminalPolicy.defaults(),
                                ReviewerConfig.defaultText,
                            )
                            .json()
                            .toString(),
                )
            withContext(Dispatchers.Main) { manager.connections.disconnect(false) }
            val result =
                withContext(Dispatchers.Main) {
                    manager.execute(task, JSONObject().put("command", "id").put("cwd", "/"))
                }
            assertEquals("connection", result.getString("stage"))
            assertFalse(result.getBoolean("executionStarted"))
            assertTrue(result.isNull("exitCode"))
            assertTrue(result.getString("errorCode").isNotBlank())
        } finally {
            withContext(Dispatchers.Main) { manager.close() }
        }
    }

    fun testNestedScriptCannotBypassNativeProtectedPaths() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val folder = java.io.File(context.filesDir, "terminal/v073-$id").apply { mkdirs() }
        val target = java.io.File(folder, "protected.txt").apply { writeText("unchanged") }
        val worker = ProcessWorker()
        try {
            val spec =
                JSONObject()
                    .put("sessionId", id)
                    .put("level", "app")
                    .put("workspace", folder.canonicalPath)
                    .put("cwd", folder.canonicalPath)
                    .put("command", "sh -c 'echo no > protected.txt'")
                    .put("rules", TerminalPolicy.defaults())
                    .put("sensitive_approved", true)
                    .put("protected_paths", org.json.JSONArray(listOf(target.canonicalPath)))
                    .put("timeout_seconds", 5)
                    .put("output_bytes", 4096)
            val error = runCatching { worker.start(spec.toString()) }.exceptionOrNull()
            assertNotNull(error)
            assertTrue(error!!.message.orEmpty().contains("不能修改自身"))
            assertEquals("unchanged", target.readText())
        } finally {
            worker.close()
            target.delete()
            folder.delete()
        }
    }
}
