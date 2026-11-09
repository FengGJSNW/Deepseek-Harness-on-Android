package dev.deepseek.mobile

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Explicitly authorized real API acceptance; only runs on the named disposable Android emulator.
 */
class LivePhaseEightTest {
    @Test
    fun browserSourceAndThreeFlashLowChildrenReturnToFlashUltra() {
        val ins = InstrumentationRegistry.getInstrumentation()
        val context = ins.targetContext
        require(
            android.os.Build.MODEL.contains("sdk_gphone") ||
                android.os.Build.FINGERPRINT.contains("generic"),
        )
        ins.runOnMainSync {
            context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        val ready = System.currentTimeMillis() + 30000
        while (!AssistantState.ready && System.currentTimeMillis() < ready) Thread.sleep(100)
        assertTrue(AssistantState.ready)
        val store = KeyStore(context)
        val previous = store.list().firstOrNull { it.selected }?.id
        val input = File(context.filesDir, "test-key")
        require(input.isFile)
        val key = store.save(input.readText().trim(), "临时071验收")
        input.delete()
        val report = JSONObject()
        fun runTask(effort: String, text: String): Conversation {
            lateinit var c: Conversation
            ins.runOnMainSync {
                c =
                    AssistantState.addSession().apply {
                        model = "deepseek-flash"
                        this.effort = effort
                        childModel = "deepseek-flash"
                        childEffort = "low"
                        access = "full"
                        phoneTools = false
                        fileTools = false
                        terminalEnabled = false
                    }
                AssistantState.appMode = "work"
                ExperienceSettings.browserEnabled = true
                ExperienceSettings.maxAgents = 4
                AgentService.current!!.send(text)
            }
            val deadline = System.currentTimeMillis() + 480000
            while (AssistantState.busy && System.currentTimeMillis() < deadline) Thread.sleep(150)
            ins.runOnMainSync {
                assertFalse("Real API task timed out", AssistantState.busy)
                assertEquals(
                    "Real API task failed: " + c.messages.lastOrNull()?.text,
                    "done",
                    AssistantState.tasks.last().status,
                )
                assertTrue(AssistantState.tasks.last().tokens > 0)
            }
            return c
        }
        try {
            val browser =
                runTask(
                    "low",
                    "请只用软件内置浏览器访问 https://example.com ，然后调用 browser_source 查看该网页的 HTML 源码。返回源码中的 title 和 h1 原文，并说明读到的是渲染后 DOM。不要使用外置浏览器、手机无障碍或终端。",
                )
            val tools = browser.messages.filter { it.type == "tool" }.map { JSONObject(it.text) }
            val source = tools.first { it.optString("name") == "browser_source" }
            assertEquals("done", source.getString("status"))
            val result = source.getJSONObject("result")
            assertEquals("rendered-dom", result.getString("kind"))
            assertTrue(result.getString("html").contains("Example Domain"))
            assertTrue(result.getString("html").contains("<html", true))
            assertTrue(
                tools.any {
                    it.optString("name") == "browser_open" && it.optString("status") == "done"
                },
            )
            report.put(
                "browser",
                JSONObject()
                    .put("url", result.getString("url"))
                    .put("kind", result.getString("kind"))
                    .put("htmlCharacters", result.getString("html").length)
                    .put("answer", browser.messages.last { it.type == "assistant" }.text),
            )
            val parent =
                runTask(
                    "ultra",
                    "请必须且只启动三个独立子智能体，子智能体配置已经设置为 DeepSeek Flash / Low。分别交给它们 1+1、3*5、3/4，每个子智能体只需回复算式与结果。你作为主智能体不得代算，必须用 spawn_agent 启动三个，并用 wait_agent 收集每一个的结果，再把三个算式和结果汇总回复。不要打开网页或使用其他执行工具。",
                )
            val children = TeamState.agents.filter { it.sessionId == parent.id }
            assertEquals("Must create exactly three independent workers", 3, children.size)
            children.forEach {
                assertEquals("deepseek-flash", it.model)
                assertEquals("low", it.effort)
                assertEquals("done", it.status)
                assertEquals("", it.parent)
            }
            val finals = children.map { it.messages.last { m -> m.type == "assistant" }.text }
            assertTrue(finals.any { it.contains("2") })
            assertTrue(finals.any { it.contains("15") })
            assertTrue(finals.any { it.contains("0.75") || it.contains("3/4") || it.contains("¾") })
            val rootTools = parent.messages.filter { it.type == "tool" }.map { JSONObject(it.text) }
            assertEquals(
                3,
                rootTools.count {
                    it.optString("name") == "spawn_agent" && it.optString("status") == "done"
                },
            )
            assertEquals(
                3,
                rootTools.count {
                    it.optString("name") == "wait_agent" && it.optString("status") == "done"
                },
            )
            val rootAnswer = parent.messages.last { it.type == "assistant" }.text
            assertTrue(rootAnswer.contains("15"))
            assertTrue(rootAnswer.contains("0.75") || rootAnswer.contains("3/4"))
            report.put(
                "team",
                JSONObject()
                    .put("rootModel", parent.model)
                    .put("rootMode", parent.effort)
                    .put("rootApiEffort", "max")
                    .put(
                        "children",
                        JSONArray(
                            children.mapIndexed { i, a ->
                                JSONObject()
                                    .put("id", a.id)
                                    .put("model", a.model)
                                    .put("effort", a.effort)
                                    .put("status", a.status)
                                    .put("answer", finals[i])
                            },
                        ),
                    )
                    .put("rootAnswer", rootAnswer)
                    .put("tokens", AssistantState.tasks.last().tokens),
            )
            report.put("passed", true)
        } finally {
            File(context.filesDir, "v071-live-results.json").writeText(report.toString(2))
            input.delete()
            store.delete(key)
            if (previous != null) store.select(previous)
            ins.runOnMainSync { if (AssistantState.busy) AgentService.current?.stopTask() }
        }
    }
}
