package dev.deepseek.mobile.workflow

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkflowTest {
    private fun request(vararg steps: String, revision: Int = 0) =
        JSONObject()
            .put("expected_revision", revision)
            .put("goal", "完成修改并验证")
            .put("steps", JSONArray(steps.map(::JSONObject)))

    @Test
    fun readOnlyModesRejectEveryMutationRouteAndUnknownTools() {
        val file =
            File("src/main/assets/workflow-policy.json").takeIf { it.exists() }
                ?: File("app/src/main/assets/workflow-policy.json")
        val policy = WorkflowPolicy(file.readText())
        for (mode in listOf(WorkMode.PLAN, WorkMode.REVIEW)) {
            for (tool in
                listOf(
                    "workspace_write",
                    "workspace_edit",
                    "terminal_exec",
                    "verification_run",
                    "terminal_elevate",
                    "worktree_merge",
                    "worktree_discard",
                    "memory_write",
                    "plugin_call",
                    "plugin_manager",
                    "mcp_call",
                    "browser_act",
                    "browser_open",
                    "tap",
                    "input_text",
                    "launch_app",
                    "unknown_future_write",
                )) {
                assertFalse("$mode must deny $tool", policy.permits(mode, tool))
                assertThrows(IllegalStateException::class.java) {
                    policy.requireAllowed(mode, tool)
                }
            }
            for (tool in
                listOf(
                    "workspace_read",
                    "workspace_context",
                    "workspace_search",
                    "work_status",
                    "work_plan",
                    "memory_read",
                    "worktree_review",
                )) assertTrue(policy.permits(mode, tool))
        }
        assertEquals(WorkMode.EXECUTE, WorkMode.from(""))
        assertEquals(WorkMode.PLAN, WorkMode.from("corrupted-value"))
    }

    @Test
    fun planRevisionPreventsLostUpdatesAndRoundTrips() {
        val next =
            WorkPlan().update(request("""{"id":"read","title":"检查代码","status":"in_progress"}"""))
        assertEquals(1, next.revision)
        assertEquals(next, WorkPlan.parse(next.json()))
        assertThrows(IllegalArgumentException::class.java) {
            next.update(request("""{"id":"read","title":"检查代码","status":"completed"}"""))
        }
        assertEquals("in_progress", next.steps.single().status)
    }

    @Test
    fun plansRejectCyclesUnknownDependenciesDuplicateIdsAndPrematureCompletion() {
        val invalid =
            listOf(
                request(
                    """{"id":"a","title":"A","status":"pending","depends_on":["b"]}""",
                    """{"id":"b","title":"B","status":"pending","depends_on":["a"]}""",
                ),
                request("""{"id":"a","title":"A","status":"pending","depends_on":["missing"]}"""),
                request(
                    """{"id":"a","title":"A","status":"pending"}""",
                    """{"id":"a","title":"B","status":"pending"}""",
                ),
                request(
                    """{"id":"a","title":"A","status":"pending"}""",
                    """{"id":"b","title":"B","status":"completed","depends_on":["a"]}""",
                ),
                request(
                    """{"id":"a","title":"A","status":"in_progress"}""",
                    """{"id":"b","title":"B","status":"in_progress"}""",
                ),
                request("""{"id":"a","title":"A","status":"blocked"}"""),
            )
        invalid.forEach {
            assertThrows(IllegalArgumentException::class.java) { WorkPlan().update(it) }
        }
        val valid =
            WorkPlan()
                .update(
                    request(
                        """{"id":"a","title":"A","status":"completed"}""",
                        """{"id":"b","title":"B","status":"in_progress","depends_on":["a"]}""",
                    ),
                )
        assertEquals(2, valid.steps.size)
    }

    @Test
    fun verificationRequiresObservedStartCompletedStatusAndZeroExit() {
        val positive =
            JSONObject(
                """{"executionStarted":true,"exitCode":0,"status":"completed","id":"native-run"}""",
            )
        val record = VerificationRecord.measured("编译", "build", positive, 100)
        assertEquals("passed", record.outcome)
        assertEquals(record, VerificationRecord.parse(record.json()))
        for (result in
            listOf(
                JSONObject(),
                JSONObject("""{"executionStarted":false,"exitCode":0,"status":"completed"}"""),
                JSONObject("""{"executionStarted":true,"exitCode":0,"status":"cancelled"}"""),
            )) {
            assertNotEquals(
                "passed",
                VerificationRecord.measured("测试", "test", result, 100).outcome,
            )
        }
        assertEquals(
            "failed",
            VerificationRecord.measured(
                    "测试",
                    "test",
                    JSONObject("""{"executionStarted":true,"exitCode":1,"status":"failed"}"""),
                    100,
                )
                .outcome,
        )
    }

    @Test
    fun workspacePathsRejectTraversalAndPlatformEscapes() {
        for (path in
            listOf(
                "../file",
                "src/../file",
                "/etc/passwd",
                "C:/secrets",
                "a\\b",
                "a//b",
                "a/./b",
                "a\u0000b",
                "/workspace/../private",
            )) {
            assertThrows(IllegalArgumentException::class.java) { WorkspaceText.path(path) }
        }
        assertEquals("src/文件.kt", WorkspaceText.path("/workspace/src/文件.kt"))
        assertEquals("", WorkspaceText.path("/workspace", true))
    }

    @Test
    fun unfinishedVerificationSurvivesRestartWithoutClaimingSuccess() {
        val pending = VerificationRecord("单元测试", "", "test", "running", null, "", 100)
        val restored = VerificationRecord.parse(pending.json()).recovered()
        assertEquals(pending.id, restored.id)
        assertEquals("interrupted", restored.outcome)
        assertNull(restored.exitCode)
        val passed =
            VerificationRecord.measured(
                "测试",
                "original",
                JSONObject(
                    """{"executionStarted":true,"exitCode":0,"status":"completed","command":"actual command"}""",
                ),
                200,
            )
        assertEquals("actual command", passed.command)
        assertEquals(passed, VerificationRecord.parse(passed.json()).recovered())
    }

    @Test
    fun incompleteDiscoveryAndUnreadableSearchReportTheirCoverage() = runBlocking {
        val tools = WorkspaceTools { method, _ ->
            if (method == "workspace_list")
                JSONObject()
                    .put("total", 300)
                    .put("files", JSONArray().put(JSONObject().put("name", "unreadable.txt")))
            else error("cannot read")
        }
        val search = tools.run("workspace_search", JSONObject().put("query", "absent"))
        assertEquals(0, search.getJSONArray("matches").length())
        assertEquals(1, search.getInt("skipped_count"))
        assertTrue(search.getBoolean("truncated"))
        val context = tools.run("workspace_context", JSONObject())
        assertTrue(context.getJSONArray("warnings").length() > 0)
        assertTrue(context.getBoolean("root_truncated"))
    }

    @Test
    fun exactEditsRetainBomCrLfUnicodeAndUnaffectedText() {
        val original = "\uFEFFfirst\r\n中文😀 = old\r\nlast\r\n"
        val edits =
            JSONArray()
                .put(JSONObject().put("old_text", "中文😀 = old").put("new_text", "中文😀 = new"))
        assertEquals(
            "\uFEFFfirst\r\n中文😀 = new\r\nlast\r\n",
            WorkspaceText.edit(original, WorkspaceText.hash(original), edits),
        )
    }

    @Test
    fun staleOrAmbiguousEditsNeverYieldPartialChanges() {
        val original = "one one two"
        assertThrows(IllegalStateException::class.java) {
            WorkspaceText.edit(
                original,
                WorkspaceText.hash("old"),
                JSONArray("""[{"old_text":"two","new_text":"three"}]"""),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceText.edit(
                original,
                WorkspaceText.hash(original),
                JSONArray(
                    """[{"old_text":"two","new_text":"three"},{"old_text":"one","new_text":"four"}]""",
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceText.edit(
                original,
                WorkspaceText.hash(original),
                JSONArray("""[{"old_text":"","new_text":"four"}]"""),
            )
        }
    }

    @Test
    fun pagedReadsReturnFullFileHashAndAccurateBounds() {
        val source = "a\r\n中😀\r\nc"
        val result =
            WorkspaceText.window(
                JSONObject().put("text", source),
                JSONObject().put("start_line", 2).put("line_count", 1),
            )
        assertEquals("中😀\r", result.getString("text"))
        assertEquals(WorkspaceText.hash(source), result.getString("sha256"))
        assertTrue(result.getBoolean("truncated"))
        assertEquals(3, result.getInt("total_lines"))
        val beyond =
            WorkspaceText.window(
                JSONObject().put("text", source),
                JSONObject().put("start_line", 50),
            )
        assertEquals(0, beyond.getInt("returned_lines"))
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceText.window(
                JSONObject().put("text", source),
                JSONObject().put("line_count", 0),
            )
        }
    }

    @Test
    fun hooksCannotRetargetOrWeakenPreparedExactEdits() = runBlocking {
        var writes = 0
        val tools = WorkspaceTools { method, args ->
            if (method == "workspace_read") JSONObject().put("text", "before")
            else {
                assertTrue(args.getBoolean("_exactEdit"))
                WorkspaceText.checkPreparedWrite(args, JSONObject(args.toString()))
                for (key in listOf("name", "text", "expected_sha256", "sandbox_id", "_agentId")) {
                    val changed = JSONObject(args.toString()).put(key, "altered")
                    assertThrows(IllegalStateException::class.java) {
                        WorkspaceText.checkPreparedWrite(args, changed)
                    }
                }
                val removedHash = JSONObject(args.toString()).apply { remove("expected_sha256") }
                WorkspaceText.checkPreparedWrite(args, removedHash)
                writes++
                JSONObject()
            }
        }
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                tools.run(
                    "workspace_edit",
                    JSONObject()
                        .put("name", "file")
                        .put("expected_sha256", WorkspaceText.hash("before"))
                        .put("edits", JSONArray("""[{"old_text":"before","new_text":"after"}]""")),
                )
            }
        }
        assertEquals(0, writes)
    }

    @Test
    fun composedEditForwardsIdentityAndPreservesAtomicConflictCheck() = runBlocking {
        var disk = "old text"
        val calls = mutableListOf<String>()
        val tools = WorkspaceTools { method, args ->
            calls += method
            assertEquals("sandbox-a", args.getString("sandbox_id"))
            assertEquals("child-a", args.getString("_agentId"))
            if (method == "workspace_read") JSONObject().put("text", disk)
            else {
                WorkspaceText.checkVersion(disk, args.getString("expected_sha256"))
                disk = args.getString("text")
                JSONObject()
            }
        }
        val args =
            JSONObject()
                .put("name", "src/a.txt")
                .put("sandbox_id", "sandbox-a")
                .put("_agentId", "child-a")
                .put("expected_sha256", WorkspaceText.hash(disk))
                .put("edits", JSONArray("""[{"old_text":"old","new_text":"new"}]"""))
        tools.run("workspace_edit", args)
        assertEquals(listOf("workspace_read", "workspace_write"), calls)
        assertEquals("new text", disk)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { tools.run("workspace_edit", args) }
        }
        assertEquals("new text", disk)
    }

    @Test
    fun mutationDuringPreparationIsRejectedByFinalWrite() {
        var disk = "before"
        val tools = WorkspaceTools { method, args ->
            if (method == "workspace_read")
                JSONObject().put("text", disk).also { disk = "external user edit" }
            else {
                WorkspaceText.checkVersion(disk, args.getString("expected_sha256"))
                disk = args.getString("text")
                JSONObject()
            }
        }
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                tools.run(
                    "workspace_edit",
                    JSONObject()
                        .put("name", "file")
                        .put("expected_sha256", WorkspaceText.hash("before"))
                        .put("edits", JSONArray("""[{"old_text":"before","new_text":"after"}]""")),
                )
            }
        }
        assertEquals("external user edit", disk)
    }

    @Test
    fun searchHonorsLimitsSkipsBroadSecretsAndReportsIncompleteResults() = runBlocking {
        val read = mutableListOf<String>()
        val tools = WorkspaceTools { method, args ->
            if (method == "workspace_list")
                JSONObject()
                    .put("total", 400)
                    .put(
                        "files",
                        JSONArray(
                            listOf(".env", "id_rsa", "a.kt", "b.kt").map {
                                JSONObject().put("name", it)
                            },
                        ),
                    )
            else {
                read += args.getString("name")
                JSONObject().put("text", "no\n中文 match\nmatch again")
            }
        }
        val result =
            tools.run("workspace_search", JSONObject().put("query", "match").put("limit", 1))
        assertEquals(listOf("a.kt"), read)
        assertEquals(2, result.getJSONArray("matches").getJSONObject(0).getInt("line"))
        assertTrue(result.getBoolean("truncated"))
    }

    @Test
    fun searchCancellationIsNotSilentlyConvertedToSkippedFiles() {
        val tools = WorkspaceTools { method, _ ->
            if (method == "workspace_list")
                JSONObject().put("files", JSONArray().put(JSONObject().put("name", "a")))
            else throw CancellationException("stopped")
        }
        assertThrows(CancellationException::class.java) {
            runBlocking { tools.run("workspace_search", JSONObject().put("query", "a")) }
        }
    }

    @Test
    fun projectRulesAreScopedOrderedAndNeverExecuteProjectCommands() = runBlocking {
        val files =
            mapOf(
                "AGENTS.md" to "root rules",
                "CLAUDE.md" to "fallback ignored",
                "package.json" to "{\"scripts\":{\"test\":\"arbitrary command\"}}",
                "src/AGENTS.md" to "src rules",
            )
        val operations = mutableListOf<String>()
        val tools = WorkspaceTools { method, args ->
            operations += method
            if (method == "workspace_list") {
                val prefix = args.optString("path").let { if (it.isBlank()) "" else "$it/" }
                JSONObject()
                    .put(
                        "files",
                        JSONArray(
                            files.keys
                                .filter { it.startsWith(prefix) && '/' !in it.removePrefix(prefix) }
                                .map { JSONObject().put("name", it.removePrefix(prefix)) },
                        ),
                    )
            } else JSONObject().put("text", files.getValue(args.getString("name")))
        }
        val result = tools.run("workspace_context", JSONObject().put("path", "src/main.kt"))
        val rules = result.getJSONArray("rules")
        assertEquals(2, rules.length())
        assertEquals("AGENTS.md", rules.getJSONObject(0).getString("path"))
        assertEquals("src/AGENTS.md", rules.getJSONObject(1).getString("path"))
        assertTrue(operations.all { it in listOf("workspace_list", "workspace_read") })
        assertFalse(result.toString().contains("fallback ignored"))
    }
}
