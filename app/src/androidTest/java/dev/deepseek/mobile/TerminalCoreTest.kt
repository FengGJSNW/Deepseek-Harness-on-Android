package dev.deepseek.mobile

import androidx.test.platform.app.InstrumentationRegistry
import dev.deepseek.mobile.terminal.*
import java.io.File
import java.util.UUID
import junit.framework.TestCase
import kotlinx.coroutines.*
import org.json.JSONObject

class TerminalCoreTest : TestCase() {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val sid = UUID.randomUUID().toString()
    private val virtual
        get() = "/dev.deepseek.mobile/workplace/session_$sid"

    private fun task(mode: String, rules: String = TerminalPolicy.defaults()) =
        PhoneTask(
            sessionId = sid,
            text = "测试当前会话文件",
            images = emptyList(),
            model = "deepseek-flash",
            effort = "off",
            access = mode,
            phoneTools = false,
            fileTools = false,
            workspace = "",
            terminalEnabled = true,
            terminalConfig =
                TerminalSnapshot("isolated", rules, ReviewerConfig.defaultText).json().toString(),
        )

    fun testParserPreservesLiteralArgumentsAndRejectsHiddenExecution() {
        val plan = CommandParser.parse("echo 'a; rm /' | grep 'a' && cat < 'a b.txt' > output.txt")
        assertEquals(3, plan.commands.size)
        assertEquals("a; rm /", plan.commands[0].argv[1])
        assertEquals(plan, CommandParser.parse(plan.shell()))
        for (text in
            listOf("echo $(id)", "echo `id`", "sh -c 'rm /'", "echo a &", "echo \"\$HOME\"")) {
            val result = TerminalPolicy(TerminalPolicy.defaults()).inspect(text, virtual, virtual)
            assertEquals(text, RuleDecision.DENY, result.decision)
        }
    }

    fun testHardRulesWinAndAncestorTargetsAreInspected() {
        val json = JSONObject(TerminalPolicy.defaults())
        json.getJSONArray("commands").put(JSONObject().put("name", "rm").put("effect", "allow"))
        val policy = TerminalPolicy(json.toString())
        for (command in
            listOf(
                "rm -rf /",
                "rm -rf /system",
                "echo x > /system/x",
                "cat a > /proc/1/mem",
                "rm --no-preserve-root -rf /",
            )) assertEquals(
            command,
            RuleDecision.DENY,
            policy.inspect(command, virtual, virtual).decision,
        )
        assertEquals(
            RuleDecision.SENSITIVE,
            policy.inspect("rm ./a.txt", virtual, virtual).decision,
        )
        assertEquals(
            RuleDecision.ALLOW,
            policy.inspect("echo 'rm -rf /'", virtual, virtual).decision,
        )
        for (command in
            listOf(
                "find . -exec sh -c x",
                "sort -o /system/x a",
                "curl -K secrets",
                "grep -f secrets a",
                "date 010100002026",
            )) assertEquals(
            command,
            RuleDecision.DENY,
            policy.inspect(command, virtual, virtual).decision,
        )
    }

    fun testRwxAndArgumentRulesUseNormalizedPaths() {
        val json = JSONObject(TerminalPolicy.defaults())
        json
            .getJSONArray("paths")
            .put(
                JSONObject()
                    .put("path", "\${workspace}/secret")
                    .put("permissions", "r")
                    .put("effect", "deny"),
            )
        json
            .getJSONArray("arguments")
            .put(
                JSONObject()
                    .put("command", "echo")
                    .put("contains", "private")
                    .put("effect", "sensitive"),
            )
        val policy = TerminalPolicy(json.toString())
        assertEquals(
            RuleDecision.DENY,
            policy.inspect("cat folder/../secret", virtual, virtual).decision,
        )
        assertEquals(
            RuleDecision.SENSITIVE,
            policy.inspect("echo private-value", virtual, virtual).decision,
        )
    }

    fun testReviewerConfigValidation() {
        val config = ReviewerConfig.parse(ReviewerConfig.defaultText)
        assertEquals("inherit", config.model)
        assertTrue(config.system.contains("decision"))
        for (suffix in listOf("\nunknown = 5", "\n[prompt]\nsystem = 'override'")) {
            try {
                ReviewerConfig.parse(ReviewerConfig.defaultText + suffix)
                fail("Invalid config accepted")
            } catch (_: IllegalArgumentException) {}
        }
    }

    fun testIsolatedFilesystemCannotEscapeOrFollowSymlinks() = runBlocking {
        val terminal = IsolatedTerminal(context, sid)
        val source = File(context.filesDir, "terminal/workplace/session_$sid").canonicalFile
        val otherSid = UUID.randomUUID().toString()
        val other = File(source.parentFile, "session_$otherSid").apply { mkdirs() }
        File(other, "readme").writeText("read-only sibling")
        android.system.Os.symlink(context.filesDir.path, File(source, "escape").path)
        try {
            val out = StringBuilder()
            val err = StringBuilder()
            val code =
                terminal.execute(CommandParser.parse("echo hello > note; cat note | grep hell")) {
                    a,
                    b ->
                    out.append(a)
                    err.append(b)
                }
            assertEquals(0, code)
            assertTrue(out.contains("hello"))
            assertEquals("", err.toString())
            for (command in
                listOf(
                    "echo bad > ../session_$otherSid/readme",
                    "cat ../../../../proc/version",
                    "echo x > escape/nope",
                    "sh -c id",
                )) {
                assertTrue(command, terminal.execute(CommandParser.parse(command)) { _, _ -> } != 0)
            }
            assertEquals("read-only sibling", File(other, "readme").readText())
            val read = StringBuilder()
            assertEquals(
                0,
                terminal.execute(CommandParser.parse("cat ../session_$otherSid/readme")) { a, _ ->
                    read.append(a)
                },
            )
            assertEquals("read-only sibling", read.toString())
        } finally {
            // These targets are direct, validated app-private fixture directories.
            File(source, "escape").delete()
            source.deleteRecursively()
            other.deleteRecursively()
        }
    }

    fun testModeGatesAndIndependentReview() = runBlocking {
        var reviews = 0
        var approvals = 0
        var verdict = RuleDecision.ALLOW
        val manager =
            TerminalManager(
                context,
                { _, _, _ ->
                    reviews++
                    ReviewVerdict(verdict, "test reviewer")
                },
                { _, _, _ ->
                    approvals++
                    "deny"
                },
            )
        try {
            try {
                manager.execute(task("none"), JSONObject().put("command", "pwd"))
                fail("chat-only ran a command")
            } catch (_: IllegalStateException) {}
            val deny = manager.execute(task("ask"), JSONObject().put("command", "rm -rf /"))
            assertEquals("denied", deny.getString("status"))
            assertEquals(0, reviews)
            val full = manager.execute(task("full"), JSONObject().put("command", "pwd"))
            assertEquals(0, full.getInt("exitCode"))
            assertEquals(0, reviews)
            verdict = RuleDecision.SENSITIVE
            val auto = manager.execute(task("auto"), JSONObject().put("command", "rm note"))
            assertEquals("denied", auto.getString("status"))
            assertEquals(0, approvals)
            assertEquals(1, reviews)
            verdict = RuleDecision.ALLOW
            val ask = manager.execute(task("ask"), JSONObject().put("command", "rm note"))
            assertEquals("denied", ask.getString("status"))
            assertEquals(1, approvals)
        } finally {
            manager.close()
        }
    }

    fun testSimilarGrantCannotSurviveDirectoryPolicyOrArgumentChanges() {
        val snapshot =
            TerminalSnapshot("isolated", TerminalPolicy.defaults(), ReviewerConfig.defaultText)
        val policy = TerminalPolicy(snapshot.rules)
        val a = policy.grantKey(sid, snapshot, virtual, CommandParser.parse("rm note"))
        assertEquals(a, policy.grantKey(sid, snapshot, virtual, CommandParser.parse("rm 'note'")))
        assertFalse(a == policy.grantKey(sid, snapshot, virtual, CommandParser.parse("rm other")))
        assertFalse(
            a ==
                policy.grantKey(
                    sid,
                    snapshot.copy(reviewer = snapshot.reviewer + "\n"),
                    virtual,
                    CommandParser.parse("rm note"),
                ),
        )
        assertFalse(
            a ==
                policy.grantKey(
                    UUID.randomUUID().toString(),
                    snapshot,
                    virtual,
                    CommandParser.parse("rm note"),
                ),
        )
    }

    fun testRealAppProcessCapturesStreamsTimeoutAndIdentity() = runBlocking {
        val worker = ProcessWorker()
        val home = File(context.filesDir, "terminal/workplace/session_$sid").apply { mkdirs() }
        suspend fun execute(command: String, timeout: Int = 10): JSONObject {
            val id =
                worker.start(
                    JSONObject()
                        .put("sessionId", sid)
                        .put("level", "app")
                        .put("workspace", home.path)
                        .put("cwd", home.path)
                        .put("command", command)
                        .put("timeout_seconds", timeout)
                        .put("output_bytes", 4096)
                        .toString(),
                )
            return withTimeout(20000) {
                while (true) {
                    val result = JSONObject(worker.poll(id))
                    if (result.getBoolean("done")) return@withTimeout result
                    delay(50)
                }
                error("unreachable")
            }
        }
        try {
            assertTrue(JSONObject(worker.info()).getInt("uid") >= 10000)
            val result = execute("printf 'stdout'; printf 'stderr' >&2; exit 7")
            assertEquals("stdout", result.getString("output"))
            assertEquals("stderr", result.getString("stderr"))
            assertEquals(7, result.getInt("exitCode"))
            assertEquals("鲸鲸🐳", execute("printf '鲸鲸🐳'").getString("output"))
            val capped = execute("printf '%5000s' x")
            assertEquals(4096, capped.getString("output").toByteArray().size)
            assertTrue(capped.getBoolean("truncated"))
            val timeout = execute("sleep 30", 1)
            assertEquals(137, timeout.getInt("exitCode"))
            assertTrue(timeout.getString("reason").contains("超过"))
            try {
                worker.start(JSONObject().put("sessionId", sid).put("level", "root").toString())
                fail("app UID claimed root")
            } catch (_: IllegalStateException) {}
        } finally {
            worker.close()
            home.deleteRecursively()
        }
    }
}
