package dev.deepseek.mobile

import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.ExperimentalComposeApi
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.util.Collections
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** App-window timings only: the system keyboard renders in a separate process. */
@OptIn(ExperimentalComposeApi::class)
class KeyboardPerformanceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private fun imeVisible() =
        ui.activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true

    private fun changes() = Recomposer.runningRecomposers.value.sumOf { it.changeCount }

    @Test
    fun repeatedKeyboardTransitionsKeepComposerAndPetAligned() {
        ui.waitUntil(30000) { AssistantState.ready }
        ui.runOnUiThread { AssistantState.appMode = "work" }
        val originalSession = AssistantState.selectedId
        val frames = Collections.synchronizedList(mutableListOf<LongArray>())
        val thread = HandlerThread("keyboard-frame-metrics").apply { start() }
        val listener =
            android.view.Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
                frames.add(
                    longArrayOf(
                        metrics.getMetric(FrameMetrics.TOTAL_DURATION),
                        metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION),
                        metrics.getMetric(FrameMetrics.DRAW_DURATION),
                        if (android.os.Build.VERSION.SDK_INT >= 31)
                            metrics.getMetric(FrameMetrics.GPU_DURATION)
                        else 0L,
                    ),
                )
            }
        val samples = JSONArray()
        val sessions = mutableListOf<Conversation>()
        val imm = ui.activity.getSystemService(InputMethodManager::class.java)
        ui.runOnUiThread {
            ui.activity.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
        }
        try {
            for (messageCount in listOf(0, 60)) {
                ui.runOnUiThread {
                    val c = AssistantState.addSession()
                    sessions.add(c)
                    repeat(messageCount) { index ->
                        c.messages.add(
                            ChatEntry(
                                if (index % 2 == 0) "user" else "assistant",
                                "第${index + 1}条本地消息：**布局性能测试**。\n\n键盘弹出时保持输入框、消息列表和鲸鲸同步移动。"
                                    .repeat(3),
                            ),
                        )
                    }
                    AssistantState.petEnabled = true
                    WhaleCompanion.settle()
                }
                ui.waitForIdle()
                for (round in 0 until 4) {
                    for (show in listOf(true, false)) {
                        frames.clear()
                        val before = changes()
                        if (show && round == 0) {
                            ui.onNodeWithText("给鲸鲸发送消息").performTouchInput { click() }
                        } else {
                            ui.runOnUiThread {
                                WhaleActivity.interact()
                                if (show) imm.showSoftInput(ui.activity.currentFocus, 0)
                                else
                                    imm.hideSoftInputFromWindow(
                                        ui.activity.window.decorView.windowToken,
                                        0,
                                    )
                            }
                        }
                        ui.waitUntil(5000) { imeVisible() == show }
                        Thread.sleep(450)
                        ui.waitForIdle()
                        val values = synchronized(frames) { frames.toList() }
                        val total = values.map { it[0] / 1_000_000.0 }.sorted()
                        fun mean(column: Int) = values.map { it[column] / 1_000_000.0 }.average()
                        val bounds =
                            ui.onNodeWithTag("pet-body").fetchSemanticsNode().boundsInWindow
                        val send =
                            ui.onNodeWithContentDescription("发送")
                                .assertIsDisplayed()
                                .fetchSemanticsNode()
                                .boundsInWindow
                        ui.runOnUiThread {
                            val keyboard =
                                ui.activity.window.decorView.rootWindowInsets
                                    .getInsets(WindowInsets.Type.ime())
                                    .bottom
                            assertTrue(
                                "Send stays above the IME",
                                send.bottom <= ui.activity.window.decorView.height - keyboard + 2,
                            )
                            assertTrue(
                                "Pet stays above send controls",
                                bounds.bottom <= send.top + 2,
                            )
                        }
                        samples.put(
                            JSONObject()
                                .put("messages", messageCount)
                                .put("round", round)
                                .put("show", show)
                                .put("frames", values.size)
                                .put("recomposerChanges", changes() - before)
                                .put(
                                    "totalP95Ms",
                                    if (total.isEmpty()) 0
                                    else total[((total.size - 1) * .95).toInt()],
                                )
                                .put("layoutMeanMs", if (values.isEmpty()) 0 else mean(1))
                                .put("drawMeanMs", if (values.isEmpty()) 0 else mean(2))
                                .put("gpuMeanMs", if (values.isEmpty()) 0 else mean(3))
                                .put("over32Ms", total.count { it > 32 }),
                        )
                    }
                }
            }
            ui.runOnUiThread {
                val result =
                    JSONObject()
                        .put(
                            "version",
                            ui.activity.packageManager
                                .getPackageInfo(ui.activity.packageName, 0)
                                .versionName,
                        )
                        .put("device", android.os.Build.MODEL)
                        .put("samples", samples)
                java.io
                    .File(ui.activity.filesDir, "keyboard-performance.json")
                    .writeText(result.toString(2))
            }
        } finally {
            ui.runOnUiThread {
                ui.activity.window.removeOnFrameMetricsAvailableListener(listener)
                imm.hideSoftInputFromWindow(ui.activity.window.decorView.windowToken, 0)
                AssistantState.sessions.removeAll(sessions.toSet())
                AssistantState.select(originalSession)
            }
            thread.quitSafely()
        }
    }
}
