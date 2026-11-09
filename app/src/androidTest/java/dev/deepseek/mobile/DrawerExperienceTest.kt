package dev.deepseek.mobile

import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DrawerExperienceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private fun ready() {
        ui.waitUntil(30000) { AssistantState.ready && DshaFonts.ready }
        ui.runOnUiThread {
            resetTransientUiForTest()
            AssistantState.appMode = "work"
        }
        ui.waitForIdle()
    }

    private fun closeDrawer() {
        ui.runOnUiThread { ui.activity.onBackPressedDispatcher.onBackPressed() }
        ui.waitForIdle()
    }

    @Test
    fun searchSurvivesReopenAndHiddenDrawerHasNoActions() {
        ready()
        ui.onNodeWithContentDescription("会话列表").performClick()
        ui.onNodeWithText("搜索对话").performTextInput("ux-retained-query")
        closeDrawer()
        ui.onNodeWithTag("session-drawer").assertDoesNotExist()
        ui.onNodeWithContentDescription("设置").assertDoesNotExist()
        ui.onNodeWithContentDescription("会话列表").performClick()
        ui.onNodeWithText("ux-retained-query").assertIsDisplayed()
        ui.onNodeWithText("ux-retained-query").performTextClearance()
        ui.onNodeWithContentDescription("设置").performClick()
        ui.onNodeWithContentDescription("返回").performClick()
        ui.onNodeWithTag("session-drawer").assertIsDisplayed()
        closeDrawer()
        ui.onNodeWithContentDescription("会话列表").assertIsDisplayed()
    }

    @Test
    fun changingSessionsWhileDrawerIsHiddenOrVisibleKeepsSortingSafe() {
        ready()
        val fixtures = List(40) { Conversation("sort-${System.nanoTime()}-$it", "Sort $it") }
        try {
            repeat(12) { round ->
                ui.runOnUiThread {
                    AssistantState.sessions.addAll(fixtures)
                    fixtures.forEachIndexed { index, c -> c.updatedAt = round * 1000L + index }
                }
                ui.onNodeWithContentDescription("会话列表").performClick()
                ui.onNodeWithTag("session-drawer").assertIsDisplayed()
                ui.runOnUiThread {
                    fixtures.forEachIndexed { index, c -> c.updatedAt = round * 1000L - index }
                    AssistantState.sessions.removeAll(fixtures.toSet())
                }
                ui.waitForIdle()
                closeDrawer()
            }
        } finally {
            ui.runOnUiThread { AssistantState.sessions.removeAll(fixtures.toSet()) }
        }
    }

    @Test
    fun repeatedDrawerTransitionsRecordFrameCost() {
        ready()
        val original = AssistantState.selectedId
        val pet = AssistantState.petEnabled
        val sessions = mutableListOf<Conversation>()
        val phase = AtomicReference("")
        val opening = Collections.synchronizedList(mutableListOf<LongArray>())
        val closing = Collections.synchronizedList(mutableListOf<LongArray>())
        val thread = HandlerThread("drawer-frame-metrics").apply { start() }
        val listener =
            android.view.Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
                val frames =
                    when (phase.get()) {
                        "open" -> opening
                        "close" -> closing
                        else -> return@OnFrameMetricsAvailableListener
                    }
                if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 0L)
                    frames.add(
                        longArrayOf(
                            metrics.getMetric(FrameMetrics.TOTAL_DURATION),
                            metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION),
                            metrics.getMetric(FrameMetrics.DRAW_DURATION),
                            metrics.getMetric(FrameMetrics.GPU_DURATION),
                        ),
                    )
            }
        try {
            ui.runOnUiThread {
                AssistantState.petEnabled = false
                repeat(80) { index ->
                    sessions.add(Conversation("ux-$index-${System.nanoTime()}", "体验测试 $index"))
                }
                AssistantState.sessions.addAll(sessions)
                AssistantState.selectedId = sessions.first().id
                repeat(60) { index ->
                    sessions
                        .first()
                        .messages
                        .add(
                            ChatEntry(
                                if (index % 2 == 0) "user" else "assistant",
                                "本地测试消息 $index：**侧栏切换**保持正文布局稳定。\n\n正文示例。",
                            ),
                        )
                }
            }
            ui.waitForIdle()
            ui.runOnUiThread {
                ui.activity.window.addOnFrameMetricsAvailableListener(
                    listener,
                    Handler(thread.looper),
                )
            }
            repeat(8) {
                phase.set("open")
                ui.onNodeWithContentDescription("会话列表").performClick()
                Thread.sleep(650)
                phase.set("")
                ui.onNodeWithTag("session-drawer").assertIsDisplayed()
                phase.set("close")
                closeDrawer()
                Thread.sleep(650)
                phase.set("")
            }
            fun summarize(frames: MutableList<LongArray>): JSONObject {
                val sample = synchronized(frames) { frames.toList() }
                val values = sample.map { it[0] / 1_000_000.0 }.sorted()
                assertTrue("FrameMetrics must produce samples", values.isNotEmpty())
                return JSONObject()
                    .put("frames", values.size)
                    .put("over16ms", values.count { it > 16.67 })
                    .put("over32ms", values.count { it > 33.33 })
                    .put("p50ms", values[values.size / 2])
                    .put("p95ms", values[(values.size * .95).toInt()])
                    .put("maxMs", values.last())
                    .put("layoutMeanMs", sample.map { it[1] / 1_000_000.0 }.average())
                    .put("drawMeanMs", sample.map { it[2] / 1_000_000.0 }.average())
                    .put(
                        "gpuMeanMs",
                        sample
                            .filter { it[3] >= 0 }
                            .map { it[3] / 1_000_000.0 }
                            .takeIf { it.isNotEmpty() }
                            ?.average(),
                    )
                    .put("samplesMs", JSONArray(values))
            }
            val result =
                JSONObject()
                    .put(
                        "version",
                        ui.activity.packageManager
                            .getPackageInfo(ui.activity.packageName, 0)
                            .versionName,
                    )
                    .put("opening", summarize(opening))
                    .put("closing", summarize(closing))
            java.io
                .File(ui.activity.filesDir, "drawer-performance.json")
                .writeText(result.toString(2))
        } finally {
            ui.runOnUiThread {
                ui.activity.window.removeOnFrameMetricsAvailableListener(listener)
                AssistantState.sessions.removeAll(sessions.toSet())
                AssistantState.selectedId = original
                AssistantState.petEnabled = pet
                AssistantState.persist()
            }
            thread.quitSafely()
        }
    }
}
