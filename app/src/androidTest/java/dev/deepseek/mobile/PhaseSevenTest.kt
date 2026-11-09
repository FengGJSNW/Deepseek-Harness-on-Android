package dev.deepseek.mobile

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PhaseSevenTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private fun ready() {
        ui.waitUntil(30000) { AssistantState.ready }
        ui.runOnUiThread {
            resetTransientUiForTest()
            AssistantState.appMode = "work"
            OfficialChatNavigation.cancelLogin()
            AssistantState.activeTaskId = ""
            AssistantState.addSession()
            WhaleCompanion.settle()
            ReasoningState.selected = null
            WorkspaceState.reset()
            FilePreviewState.clear()
            ChangeState.show = false
            ChangeState.selected = null
        }
    }

    @Test
    fun concurrentAgentQuestionsArePresentedOneAtATime() {
        ready()
        val task =
            PhoneTask(
                sessionId = AssistantState.selectedId,
                text = "questions",
                images = emptyList(),
                model = "deepseek-flash",
                effort = "low",
                access = "ask",
                phoneTools = false,
                fileTools = false,
                workspace = "",
            )
        var first = ""
        var second = ""
        ui.runOnUiThread {
            AssistantState.tasks.add(task)
            AssistantState.activeTaskId = task.id
            task.status = "running"
            AgentService.current!!.perform(
                "ask_user",
                JSONObject().put("question", "agent question one"),
            ) {
                first = (it as? JSONObject)?.optString("answer").orEmpty()
            }
            AgentService.current!!.perform(
                "ask_user",
                JSONObject().put("question", "agent question two"),
            ) {
                second = (it as? JSONObject)?.optString("answer").orEmpty()
            }
        }
        try {
            ui.waitUntil(10000) { task.question == "agent question one" }
            ui.runOnUiThread { AgentService.current!!.answer("one") }
            ui.waitUntil(10000) { first == "one" && task.question == "agent question two" }
            ui.runOnUiThread { AgentService.current!!.answer("two") }
            ui.waitUntil(10000) { second == "two" }
        } finally {
            ui.runOnUiThread {
                AssistantState.activeTaskId = ""
                AssistantState.tasks.remove(task)
            }
        }
    }

    @Test
    fun whaleOnlyReturnsOnExplicitReturnOrRelease() {
        ready()
        var motion: WhaleMotion? = null
        var scope: CoroutineScope? = null
        val input = mutableStateOf(0)
        ui.runOnUiThread {
            ui.activity.setContent {
                val value = rememberWhaleMotion(200f, false, input.value)
                val jobs = androidx.compose.runtime.rememberCoroutineScope()
                androidx.compose.runtime.SideEffect {
                    motion = value
                    scope = jobs
                }
            }
        }
        ui.waitForIdle()
        ui.runOnUiThread {
            scope!!.launch {
                motion!!.x.snapTo(-80f)
                motion!!.y.snapTo(-30f)
            }
        }
        ui.waitForIdle()
        ui.runOnUiThread {
            input.value++
            WhaleActivity.interact()
        }
        ui.waitForIdle()
        assertEquals(-80f, motion!!.shownX, .1f)
        assertEquals(-30f, motion!!.shownY, .1f)
        ui.runOnUiThread {
            motion!!.startDrag()
            motion!!.dragX = -120f
            scope!!.launch { motion!!.releaseDrag(false) }
        }
        ui.waitForIdle()
        assertEquals(-120f, motion!!.shownX, .1f)
        ui.runOnUiThread {
            motion!!.startDrag()
            motion!!.dragX = -160f
            scope!!.launch { motion!!.releaseDrag(true) }
        }
        ui.waitForIdle()
        assertEquals(0f, motion!!.shownX, .5f)
        assertEquals(0f, motion!!.shownY, .5f)
    }

    @Test
    fun backupIncludesCanonicalAppFilesAndExcludesCredentials() {
        ready()
        val file =
            File(
                ui.activity.filesDir,
                "terminal/workplace/session_${AssistantState.selectedId}/backup-check.txt",
            )
        file.parentFile!!.mkdirs()
        file.writeText("backup content")
        val store = AttachmentStore(ui.activity)
        val archiveBytes =
            java.io
                .ByteArrayOutputStream()
                .also { output ->
                    java.util.zip.ZipOutputStream(output).use { zip ->
                        listOf("conversations-v3.json", "experience.json").forEach { name ->
                            zip.putNextEntry(java.util.zip.ZipEntry(name))
                            zip.write("{}".toByteArray())
                            zip.closeEntry()
                        }
                    }
                }
                .toByteArray()
        val referenced =
            store.save(
                archiveBytes.inputStream(),
                AssistantState.selectedId,
                "DSHA-backup-2000-01-01.zip",
                "application/zip",
            )
        val unused =
            store.save(
                archiveBytes.inputStream(),
                AssistantState.selectedId,
                "DSHA-backup-2000-01-02.zip",
                "application/zip",
            )
        val previous = store.all().map { it.id }.toSet()
        lateinit var job: kotlinx.coroutines.Job
        ui.runOnUiThread {
            AssistantState.current.files.add(referenced.id)
            MainActivity.current = null
            job = AgentService.current!!.exportBackup()
        }
        try {
            ui.waitUntil(20000) { job.isCompleted }
            assertTrue(
                "Backup job finished without an archive",
                store.all().any { it.id !in previous && it.name.startsWith("DSHA-backup-") },
            )
            val backup =
                store.all().first { it.id !in previous && it.name.startsWith("DSHA-backup-") }
            val names = mutableSetOf<String>()
            java.util.zip.ZipInputStream(store.file(backup.id).inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    names.add(entry.name)
                    zip.closeEntry()
                }
            }
            assertTrue(names.contains("conversations-v3.json"))
            assertTrue(names.contains("experience.json"))
            assertTrue(
                "Referenced backup must remain",
                names.contains("attachments/${referenced.id}/content"),
            )
            assertFalse(
                "Unused internal backups must not nest",
                names.any { it.startsWith("attachments/${unused.id}/") },
            )
            assertTrue("Original unused backup must remain on device", store.file(unused.id).isFile)
            assertTrue(names.any { it.endsWith("backup-check.txt") })
            assertFalse(
                names.any { it.contains("keystore") || it.contains("api-key") || it == "test-key" },
            )
        } finally {
            ui.runOnUiThread { MainActivity.current = ui.activity }
        }
    }

    @Test
    fun settingsCloseHiddenDrawerAndEditBubble() {
        ready()
        ui.onNodeWithContentDescription("会话列表").performClick()
        ui.onNodeWithContentDescription("设置").performClick()
        ui.onNodeWithTag("settings-screen").assertIsDisplayed()
        ui.onNodeWithText("设置", useUnmergedTree = true).performClick()
        ui.onNodeWithText("模式").assertDoesNotExist()
        ui.onNodeWithText("鲸鲸设置").performClick()
        ui.onNodeWithText("气泡效果").performClick()
        ui.onNodeWithText("添加文案").performScrollTo().performClick()
        ui.onNodeWithText("气泡文案").assertIsDisplayed()
        ui.onNode(hasSetTextAction() and hasText("文案")).performTextInput("测试余额{limit}")
        ui.onNodeWithText("保存").performScrollTo().performClick()
        assertTrue(ExperienceSettings.phrases.any { it.text == "测试余额{limit}" })
        TestSnapshots.capture(ui, "v070-bubble-settings")
        ui.runOnUiThread {
            ExperienceSettings.phrases.removeAll { it.text == "测试余额{limit}" }
            ExperienceSettings.save()
        }
    }

    @Test
    fun timelineReasoningAndStackedFilePreview() {
        ready()
        val file =
            AttachmentStore(ui.activity)
                .save(
                    "hello\nchanged file".byteInputStream(),
                    AssistantState.selectedId,
                    "agent-note.md",
                    "text/markdown",
                )
        ui.runOnUiThread {
            AssistantState.current.messages.addAll(
                listOf(
                    ChatEntry("user", "整理文件"),
                    ChatEntry("reasoning", "正在检查文件内容。\n第二段思考。"),
                    ChatEntry("assistant", "第一段回复\n\n第二段回复"),
                    ChatEntry(
                        "tool",
                        JSONObject()
                            .put("name", "workspace_read")
                            .put("args", JSONObject().put("name", "notes.md"))
                            .put("status", "done")
                            .put("result", JSONObject().put("text", "file content"))
                            .toString(),
                    ),
                    ChatEntry("assistant", "已完成。"),
                ),
            )
            val a = AgentWork(UUID.randomUUID().toString(), AssistantState.selectedId, "资料整理", "")
            a.status = "done"
            a.messages.add(ChatEntry("file", "", files = listOf(file.id)))
            TeamState.agents.add(a)
        }
        ui.onNodeWithTag("work-details-toggle").performClick()
        ui.onNodeWithText("1 项执行").performClick()
        ui.onNodeWithText("读取文件").assertIsDisplayed()
        ui.onNodeWithText("查看思考").performClick()
        ui.onNodeWithText("正在检查文件内容。\n第二段思考。").assertIsDisplayed()
        ui.onNodeWithContentDescription("关闭面板").performClick()
        ui.onNodeWithContentDescription("打开功能区").performClick()
        ui.onNodeWithText("子 Agent 会话").performClick()
        ui.onNodeWithText("资料整理").performClick()
        ui.onNodeWithTag("file-card-${file.id}").performClick()
        ui.onNodeWithTag("workspace-document").assertIsDisplayed()
        ui.onNodeWithTag("file-preview").assertDoesNotExist()
        TestSnapshots.capture(ui, "v070-stacked-preview")
        ui.onNodeWithContentDescription("返回会话").performClick()
    }

    @Test
    fun streamParserAndConditionsAndDiff() {
        val stream = CompletionStream()
        stream.accept(JSONObject("""{"choices":[{"delta":{"reasoning_content":"先想"}}]}"""))
        stream.accept(
            JSONObject(
                """{"choices":[{"delta":{"content":"答复","tool_calls":[{"index":0,"id":"t1","function":{"name":"wait","arguments":"{\"milliseconds\":"}}]}}]}""",
            ),
        )
        stream.accept(
            JSONObject(
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"100}"}}]},"finish_reason":"tool_calls"}],"usage":{"total_tokens":12}}""",
            ),
        )
        val result = stream.result()
        assertEquals(
            "先想",
            result
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("reasoning_content"),
        )
        assertEquals(
            "{\"milliseconds\":100}",
            result
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getJSONArray("tool_calls")
                .getJSONObject(0)
                .getJSONObject("function")
                .getString("arguments"),
        )
        assertTrue(
            ExperienceSettings.matches(
                "{cost}>=30 && {busy}==1",
                mapOf("cost" to "31.5", "busy" to "1"),
            ),
        )
        assertFalse(ExperienceSettings.validCondition("{cost}>=30 || exec()"))
        assertFalse(ExperienceSettings.matches("{cost}>=30", mapOf("cost" to "unknown")))
        val diff = lineDiff("first\nold\nend", "first\nnew\nend")
        assertTrue(diff.contains('-' to "old"))
        assertTrue(diff.contains('+' to "new"))
        assertTrue(diff.contains(' ' to "end"))
    }

    @Test
    fun browserDomWorksWithoutAccessibilityAndRejectsStaleOrForeignHandles() = runBlocking {
        ready()
        val task =
            PhoneTask(
                sessionId = AssistantState.selectedId,
                text = "fixture",
                images = emptyList(),
                model = "deepseek-flash",
                effort = "low",
                access = "full",
                phoneTools = false,
                fileTools = false,
                workspace = "",
            )
        val browser = AgentBrowser(ui.activity)
        withContext(Dispatchers.Main) {
            AssistantState.tasks.add(task)
            AssistantState.activeTaskId = task.id
            task.status = "running"
            val view = WebView(ui.activity)
            view.settings.javaScriptEnabled = true
            view.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(
                    600,
                    android.view.View.MeasureSpec.EXACTLY,
                ),
                android.view.View.MeasureSpec.makeMeasureSpec(
                    900,
                    android.view.View.MeasureSpec.EXACTLY,
                ),
            )
            view.layout(0, 0, 600, 900)
            val done = CompletableDeferred<Unit>()
            view.webViewClient =
                object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        done.complete(Unit)
                    }
                }
            val tab = BrowserTab(UUID.randomUUID().toString(), "", task.sessionId, view)
            BrowserState.tabs.add(tab)
            view.loadDataWithBaseURL(
                "https://fixture.example/",
                "<html><body><input id='name'><input type='password' value='hidden-secret'><button onclick=\"document.getElementById('out').innerText=document.getElementById('name').value\">Submit</button><p id='out'></p></body></html>",
                "text/html",
                "UTF-8",
                null,
            )
            try {
                withTimeout(10000) { done.await() }
                val args = JSONObject().put("tab", tab.id)
                var snap = browser.execute("browser_snapshot", args)
                assertFalse(snap.toString().contains("hidden-secret"))
                assertTrue(
                    browser
                        .execute(
                            "browser_act",
                            JSONObject(args.toString())
                                .put("action", "fill")
                                .put("node", 0)
                                .put("text", "browser-ok")
                                .put("snapshot", snap.getString("snapshot")),
                        )
                        .optBoolean("ok"),
                )
                assertTrue(
                    runCatching {
                        browser.execute(
                            "browser_act",
                            JSONObject(args.toString())
                                .put("action", "click")
                                .put("node", 2)
                                .put("snapshot", snap.getString("snapshot")),
                        )
                    }
                        .isFailure,
                )
                snap = browser.execute("browser_snapshot", args)
                browser.execute(
                    "browser_act",
                    JSONObject(args.toString())
                        .put("action", "click")
                        .put("node", 2)
                        .put("snapshot", snap.getString("snapshot")),
                )
                snap = browser.execute("browser_snapshot", args)
                assertTrue(snap.getString("text").contains("browser-ok"))
                assertTrue(
                    runCatching {
                        browser.execute(
                            "browser_snapshot",
                            JSONObject(args.toString()).put("_agentId", "foreign"),
                        )
                    }
                        .isFailure,
                )
                assertTrue(
                    runCatching {
                        browser.execute(
                            "browser_open",
                            JSONObject().put("url", "javascript:alert(1)"),
                        )
                    }
                        .isFailure,
                )
            } finally {
                browser.close()
                AssistantState.activeTaskId = ""
                AssistantState.tasks.remove(task)
            }
        }
    }

    @Test
    fun fileTrackingAndScopedMemories() = runBlocking {
        ready()
        val id = AssistantState.selectedId
        val task =
            PhoneTask(
                sessionId = id,
                text = "diff",
                images = emptyList(),
                model = "deepseek-flash",
                effort = "low",
                access = "full",
                phoneTools = false,
                fileTools = false,
                workspace = "",
                terminalEnabled = true,
                terminalConfig = "{\"level\":\"isolated\"}",
            )
        val root = File(ui.activity.filesDir, "terminal/workplace/session_$id").apply { mkdirs() }
        File(root, "code.kt").writeText("val old = 1")
        val tracker = ChangeTracker(ui.activity)
        tracker.begin(task)
        File(root, "code.kt").writeText("val new = 2")
        File(root, "new.txt").writeText("new file")
        withContext(Dispatchers.Main) { tracker.finish(task) }
        assertTrue(
            ChangeState.changes.any {
                it.taskId == task.id &&
                    it.path == "terminal/code.kt" &&
                    it.before == "val old = 1" &&
                    it.after == "val new = 2"
            },
        )
        val memories = MemoryStore(ui.activity)
        val a = memories.read("session", id)
        memories.write("session", id, "only this chat", a.sha256)
        assertEquals("", memories.read("session", UUID.randomUUID().toString()).text)
        assertEquals("only this chat", memories.read("session", id).text)
        ui.runOnUiThread {
            ChangeState.taskId = task.id
            ChangeState.show = true
        }
        ui.onNodeWithText("terminal/code.kt").performClick()
        ui.onNodeWithText("- val old = 1").assertIsDisplayed()
        TestSnapshots.capture(ui, "v070-diff")
        ui.runOnUiThread {
            ChangeState.show = false
            ChangeState.selected = null
        }
    }
}
