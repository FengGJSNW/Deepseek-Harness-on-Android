package dev.deepseek.mobile

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import dev.deepseek.mobile.terminal.TerminalSnapshot
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import junit.framework.TestCase
import org.json.JSONObject
import org.json.JSONTokener

/** Actual native bridge authorization. Dummy key and held scheduler prevent all paid requests. */
class MemoryBridgeTest : TestCase() {
    private val ins
        get() = InstrumentationRegistry.getInstrumentation()

    private fun main(block: () -> Unit) = ins.runOnMainSync(block)

    private fun js(source: String): String {
        var value = ""
        val done = CountDownLatch(1)
        main {
            AgentService.current!!.webView.evaluateJavascript(source) {
                value = it
                done.countDown()
            }
        }
        check(done.await(10, TimeUnit.SECONDS))
        return value
    }

    private fun rpc(method: String, args: JSONObject = JSONObject()): JSONObject {
        js(
            "window.__memoryResult=null;rpc(${JSONObject.quote(method)},$args).then(v=>window.__memoryResult={ok:true,value:v},e=>window.__memoryResult={ok:false,error:e.message})",
        )
        val until = System.currentTimeMillis() + 10000
        while (System.currentTimeMillis() < until) {
            val result = js("JSON.stringify(window.__memoryResult)")
            if (result != "\"null\"" && result != "null")
                return JSONObject(JSONTokener(result).nextValue() as String)
            Thread.sleep(50)
        }
        error("Native memory RPC timed out")
    }

    fun testNativeScopeFrozenContextPermissionsAndCompareAndSwap() {
        val ctx = ins.targetContext
        ins.startActivitySync(
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val until = System.currentTimeMillis() + 30000
        while (!AssistantState.ready && System.currentTimeMillis() < until) Thread.sleep(100)
        val service = AgentService.current!!
        val keys = KeyStore(ctx)
        val previous = keys.list().firstOrNull { it.selected }?.id
        val key = keys.save("local-memory-test-only", "离线记忆测试")
        val original = service.memories.read("global")
        val config = service.terminal.config.load("isolated")
        js("window.__realMemoryRunTask=window.runTask;window.runTask=t=>{window.__heldTask=t}")
        fun finish() {
            rpc("end")
            main {
                val id = AssistantState.activeTaskId
                service.onUiEvent("task_done", JSONObject().put("taskId", id).toString())
            }
        }
        fun start(access: String): PhoneTask {
            lateinit var task: PhoneTask
            main {
                AssistantState.current.access = access
                service.send("离线测试共享记忆")
                task = AssistantState.tasks.last()
            }
            assertTrue(rpc("begin").getBoolean("ok"))
            return task
        }
        try {
            main {
                AssistantState.appMode = "work"
                AssistantState.addSession()
            }
            start("none")
            var request =
                JSONObject()
                    .put("scope", "global")
                    .put("text", "禁止模式不能写")
                    .put("expected_sha256", original.sha256)
            assertFalse(rpc("memory_write", request).getBoolean("ok"))
            assertEquals(original.text, service.memories.read("global").text)
            finish()
            lateinit var project: WorkProject
            main {
                project = AssistantState.addProject("桥接测试项目")
                AssistantState.addSession(project.id)
            }
            main { AssistantState.current.terminalEnabled = true }
            val task = start("full")
            val folder =
                java.io.File(ctx.filesDir, "terminal/workplace/session_${task.sessionId}").apply {
                    mkdirs()
                }
            java.io.File(folder, "result.md").writeText("# 可点击的工作结果")
            val published =
                rpc("publish_file", JSONObject().put("path", "result.md").put("source", "terminal"))
            assertTrue(published.getBoolean("ok"))
            assertTrue(
                published.getJSONObject("value").getString("preview").startsWith("dsha://file/"),
            )
            assertFalse(
                rpc(
                        "publish_file",
                        JSONObject()
                            .put("path", "../conversations-v3.json")
                            .put("source", "terminal"),
                    )
                    .getBoolean("ok"),
            )
            request =
                JSONObject()
                    .put("scope", "project")
                    .put("text", "项目记忆只属于冻结项目")
                    .put("expected_sha256", MemoryStore.hash(""))
            assertTrue(rpc("memory_write", request).getBoolean("ok"))
            assertFalse(rpc("memory_write", request).getBoolean("ok"))
            assertEquals("项目记忆只属于冻结项目", service.memories.read("project", project.id).text)
            main { AssistantState.addSession() }
            val context =
                rpc("memory_context", JSONObject().put("sessionId", task.sessionId))
                    .getJSONObject("value")
            assertEquals("项目记忆只属于冻结项目", context.getJSONObject("project").getString("text"))
            assertFalse(
                rpc("memory_context", JSONObject().put("sessionId", AssistantState.current.id))
                    .getBoolean("ok"),
            )
            finish()
            val rules = JSONObject(config.rules)
            rules
                .getJSONArray("paths")
                .put(
                    JSONObject()
                        .put("path", original.logicalPath)
                        .put("permissions", "w")
                        .put("effect", "deny")
                        .put("recursive", false),
                )
            service.terminal.config.save(
                TerminalSnapshot(config.level, rules.toString(), config.reviewer),
            )
            start("ask")
            request =
                JSONObject()
                    .put("scope", "global")
                    .put("text", "规则禁止")
                    .put("expected_sha256", original.sha256)
            val rejected = rpc("memory_write", request)
            assertFalse(rejected.getBoolean("ok"))
            assertTrue(rejected.getString("error").contains("路径规则禁止"))
            assertEquals(original.text, service.memories.read("global").text)
            finish()
        } finally {
            js("window.runTask=window.__realMemoryRunTask")
            main { service.stopTask() }
            service.terminal.config.save(config)
            keys.delete(key)
            if (previous != null) keys.select(previous)
        }
    }
}
