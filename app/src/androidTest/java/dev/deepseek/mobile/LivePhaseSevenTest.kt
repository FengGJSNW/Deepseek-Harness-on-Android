package dev.deepseek.mobile

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

/** Emulator-only; the existing authorized credential courier deletes the temporary key. */
class LivePhaseSevenTest {
    @Test
    fun streamedTurnsSurviveRepeatedRestoreAndLegacyGap() {
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
        val key = store.save(input.readText().trim(), "临时流式验证")
        input.delete()
        try {
            ins.runOnMainSync {
                AssistantState.addSession().apply {
                    access = "none"
                    phoneTools = false
                    fileTools = false
                    terminalEnabled = false
                    effort = "low"
                }
            }
            var sawStream = false
            for (round in 0..3) {
                if (round == 3) {
                    val events = File(context.filesDir, "events/${AssistantState.selectedId}.json")
                    val seed = JSONArray(events.readText())
                    seed.put(11, org.json.JSONObject.NULL)
                    events.writeText(seed.toString())
                }
                ins.runOnMainSync {
                    AgentService.current!!.send(
                        if (round == 0) "记住本会话口令：鲸鲸070。只回复收到。" else "本会话口令是什么？只回复口令。",
                    )
                }
                val deadline = System.currentTimeMillis() + 180000
                while (AssistantState.busy && System.currentTimeMillis() < deadline) {
                    ins.runOnMainSync {
                        if (AssistantState.current.messages.any { it.type.startsWith("stream_") })
                            sawStream = true
                    }
                    Thread.sleep(30)
                }
                ins.runOnMainSync {
                    assertFalse("streamed turn timed out", AssistantState.busy)
                    assertEquals("done", AssistantState.tasks.last().status)
                    assertTrue(
                        AssistantState.current.messages
                            .lastOrNull { it.type == "assistant" }
                            ?.text
                            ?.isNotBlank() == true,
                    )
                    if (round > 0)
                        assertTrue(
                            AssistantState.current.messages
                                .last { it.type == "assistant" }
                                .text
                                .contains("070"),
                        )
                }
                val seed =
                    JSONArray(
                        File(context.filesDir, "events/${AssistantState.selectedId}.json")
                            .readText(),
                    )
                for (i in 0 until seed.length()) assertEquals(
                    i,
                    seed.getJSONObject(i).getInt("seq"),
                )
            }
            assertTrue("no real streaming update observed", sawStream)
            assertTrue(AssistantState.tasks.last().tokens > 0)
            assertTrue(
                AssistantState.current.messages.any {
                    it.type == "system" && it.text.contains("旧版历史记录已修复")
                },
            )
        } finally {
            store.delete(key)
            if (previous != null) store.select(previous)
            input.delete()
        }
    }
}
