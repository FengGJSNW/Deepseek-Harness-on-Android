package dev.deepseek.mobile

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Explicit opt-in, emulator-only live API check. No tools or root commands are exposed. */
class LiveChatRecoveryTest {
    @Test
    fun connectionAndNewMessageAfterStop() {
        val ins = InstrumentationRegistry.getInstrumentation()
        val context = ins.targetContext
        require(
            android.os.Build.FINGERPRINT.contains("generic") ||
                android.os.Build.MODEL.contains("sdk_gphone"),
        )
        ins.runOnMainSync {
            context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        val readyUntil = System.currentTimeMillis() + 30_000
        while (!AssistantState.ready && System.currentTimeMillis() < readyUntil) Thread.sleep(100)
        assertTrue(AssistantState.ready)
        val store = KeyStore(context)
        val previous = store.list().firstOrNull { it.selected }?.id
        val input = File(context.filesDir, "test-key")
        require(input.isFile) { "No temporary test credential supplied" }
        val keyId = store.save(input.readText().trim(), "临时连接验证")
        input.delete()
        try {
            val connected = CountDownLatch(1)
            var ok = false
            ins.runOnMainSync {
                AgentService.current!!.perform("test_connection", JSONObject().put("id", keyId)) {
                    ok = it != null
                    connected.countDown()
                }
            }
            assertTrue(connected.await(120, TimeUnit.SECONDS))
            assertTrue("Live API connection failed", ok)
            ins.runOnMainSync {
                AssistantState.appMode = "work"
                AssistantState.addSession().apply {
                    access = "none"
                    phoneTools = false
                    fileTools = false
                    terminalEnabled = false
                }
                AgentService.current!!.stopTask()
                AgentService.current!!.send("这是连接测试，只回复两个字：收到")
            }
            val until = System.currentTimeMillis() + 180_000
            while (AssistantState.busy && System.currentTimeMillis() < until) Thread.sleep(200)
            ins.runOnMainSync {
                assertFalse("Reply timed out", AssistantState.busy)
                val task = AssistantState.tasks.last()
                assertEquals("done", task.status)
                assertTrue(
                    AssistantState.current.messages.any {
                        it.type == "assistant" && it.text.isNotBlank()
                    },
                )
                assertEquals(0, task.steps.size)
                File(context.filesDir, "v050-live-chat.json")
                    .writeText(
                        JSONObject()
                            .put("connection", true)
                            .put("stoppedThenSent", true)
                            .put("status", task.status)
                            .put("replyReceived", true)
                            .put("tokens", task.tokens)
                            .put("executionTools", 0)
                            .toString(2),
                    )
            }
        } finally {
            store.delete(keyId)
            if (previous != null) store.select(previous)
        }
    }
}
