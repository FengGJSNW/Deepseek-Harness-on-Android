package dev.deepseek.mobile

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import junit.framework.TestCase
import org.json.JSONObject

/** Deterministic scheduler tests: never send network requests or perform phone gestures. */
class TaskQueueTest : TestCase() {
    private val ins
        get() = InstrumentationRegistry.getInstrumentation()

    private fun main(block: () -> Unit) = ins.runOnMainSync(block)

    private fun js(source: String) {
        val done = CountDownLatch(1)
        main { AgentService.current!!.webView.evaluateJavascript(source) { done.countDown() } }
        check(done.await(10, TimeUnit.SECONDS))
    }

    fun testQueueIsolationStopAndRecovery() {
        val ctx = ins.targetContext
        ins.startActivitySync(
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val deadline = System.currentTimeMillis() + 30000
        while (!AssistantState.ready && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertTrue(AssistantState.ready)
        val service = AgentService.current!!
        val keys = KeyStore(ctx)
        val previous = keys.list().firstOrNull { it.selected }?.id
        val fixture = keys.save("local-scheduler-test-only", "调度测试")
        js("window.__realRunTask=window.runTask;window.runTask=t=>{window.__heldTask=t}")
        try {
            lateinit var a: Conversation
            lateinit var first: PhoneTask
            lateinit var second: PhoneTask
            main {
                assertFalse(AssistantState.busy)
                a = AssistantState.addSession()
                a.access = "read"
                service.resumeQueue()
                service.send("queue-a")
                first = AssistantState.running!!
                assertEquals(a.id, first.sessionId)
                a.access = "full"
                assertEquals("read", first.access)
                val b = AssistantState.addSession()
                service.send("queue-b")
                second = AssistantState.tasks.last()
                assertEquals("queued", second.status)
                service.send("queue-c")
                val cancelled = AssistantState.tasks.last()
                service.cancelTask(cancelled.id)
                assertEquals("cancelled", cancelled.status)
                service.onUiEvent(
                    "task_event",
                    JSONObject()
                        .put("taskId", first.id)
                        .put("type", "assistant")
                        .put("value", "reply-for-a")
                        .toString(),
                )
                assertTrue(a.messages.any { it.text == "reply-for-a" })
                assertFalse(b.messages.any { it.text == "reply-for-a" })
                service.stopTask()
                assertEquals("stopping", first.status)
                assertEquals(first.id, AssistantState.activeTaskId)
                service.onUiEvent("task_done", JSONObject().put("taskId", first.id).toString())
            }
            // Finalization writes context and file changes asynchronously on the service.
            val stopDeadline = System.currentTimeMillis() + 15_000L
            while (AssistantState.busy && System.currentTimeMillis() < stopDeadline) Thread.sleep(
                50,
            )
            main {
                assertTrue(AssistantState.queuePaused)
                assertFalse(AssistantState.busy)
                assertEquals("cancelled", second.status)
                assertEquals("cancelled", first.status)
                service.send("queue-after-stop")
                val resumed = AssistantState.tasks.last()
                assertFalse(AssistantState.queuePaused)
                assertEquals(resumed.id, AssistantState.activeTaskId)
                service.onUiEvent("task_done", JSONObject().put("taskId", first.id).toString())
                assertEquals(resumed.id, AssistantState.activeTaskId)
                service.send("queue-d")
                val queued = AssistantState.tasks.last()
                AssistantState.persist()
                AssistantState.initialize(ctx)
                assertFalse(AssistantState.busy)
                assertFalse(AssistantState.queuePaused)
                assertEquals(
                    "interrupted",
                    AssistantState.tasks.first { it.id == resumed.id }.status,
                )
                assertEquals(
                    "interrupted",
                    AssistantState.tasks.first { it.id == queued.id }.status,
                )
                assertTrue(
                    AssistantState.sessions
                        .first { it.id == a.id }
                        .messages
                        .any { it.text == "reply-for-a" },
                )
                service.cancelTask(queued.id)
            }
        } finally {
            js("window.runTask=window.__realRunTask")
            keys.delete(fixture)
            if (previous != null) keys.select(previous)
            main {
                AssistantState.ready = true
                AssistantState.persist()
            }
        }
    }
}
