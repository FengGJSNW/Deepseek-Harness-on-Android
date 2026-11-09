package dev.deepseek.mobile

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Emulator-only visual/navigation fixtures. Never execute a terminal command. */
class RedesignTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private fun screen(name: String) {
        ui.waitForIdle()
        Thread.sleep(150)
        val done = CountDownLatch(1)
        var result = -1
        lateinit var bitmap: Bitmap
        ui.runOnUiThread {
            val view =
                android.view.inspector.WindowInspector.getGlobalWindowViews().last {
                    it.isShown && it.width > 0
                }
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            // Capture our own GPU surface, including RenderEffect, without changing FLAG_SECURE.
            val window = if (view === ui.activity.window.decorView) ui.activity.window else null
            if (window != null)
                PixelCopy.request(
                    window,
                    bitmap,
                    {
                        result = it
                        done.countDown()
                    },
                    Handler(Looper.getMainLooper()),
                )
            else {
                view.draw(android.graphics.Canvas(bitmap))
                result = PixelCopy.SUCCESS
                done.countDown()
            }
        }
        check(done.await(5, TimeUnit.SECONDS) && result == PixelCopy.SUCCESS) {
            "Own-window copy failed: $result"
        }
        File(ui.activity.filesDir, "v050-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Test
    fun islandsSidebarAndSettings() {
        ui.waitUntil(30_000) { AssistantState.ready }
        ui.runOnUiThread {
            AssistantState.appMode = "work"
            AssistantState.petEnabled = true
            AssistantState.activeTaskId = ""
            AssistantState.queuePaused = false
            AssistantState.addSession().apply {
                title = "和鲸鲸一起，把想法变成现实"
                effort = "high"
            }
            WhaleCompanion.settle()
        }
        ui.onNodeWithText("今天，想做点什么？").assertIsDisplayed()
        ui.onNodeWithTag("toolbar-Paperclip", true).assertWidthIsEqualTo(22.dp)
        ui.onNodeWithTag("toolbar-Access", true).assertWidthIsEqualTo(22.dp)
        ui.onNodeWithTag("toolbar-Terminal", true).assertWidthIsEqualTo(22.dp)
        ui.onNodeWithContentDescription("语音输入").assertDoesNotExist()
        ui.onNodeWithText("任务中心").assertDoesNotExist()
        screen("home")
        ui.runOnUiThread {
            AssistantState.current.messages.add(ChatEntry("user", "把今天的想法整理成一个小计划吧。"))
            AssistantState.current.messages.add(
                ChatEntry(
                    "assistant",
                    "当然。我们先留一点空间，做最重要的事。\n\n**上午 · 专注**\n完成手里的一个小目标。\n\n**下午 · 探索**\n把新的想法写下来，再慢慢实现。\n\n**晚上 · 休息**\n给自己留一点轻松的时间。",
                ),
            )
        }
        screen("conversation")
        ui.onNodeWithContentDescription("模型与思考设置").performClick()
        assertTrue(
            ui.onNodeWithTag("reasoning-panel").fetchSemanticsNode().boundsInRoot.width <=
                300 * ui.activity.resources.displayMetrics.density + 1,
        )
        ui.onNodeWithContentDescription("开启思考").assertDoesNotExist()
        ui.onNodeWithContentDescription("恢复默认思考强度").assertDoesNotExist()
        screen("model")
        InstrumentationRegistry.getInstrumentation()
            .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        ui.onNodeWithContentDescription("操作权限：受限访问").performClick()
        screen("permissions")
        ui.onNodeWithText("完全禁止").performClick()
        ui.runOnUiThread { assertEquals("none", AssistantState.current.access) }
        ui.onNodeWithContentDescription("终端：已关闭").performClick()
        screen("terminals")
        ui.onNodeWithText("隔离工作区").performClick()
        ui.runOnUiThread { assertTrue(AssistantState.current.terminalEnabled) }
        ui.onNodeWithContentDescription("会话列表").performClick()
        screen("sidebar")
        ui.onNodeWithText("Work").assertIsDisplayed()
        ui.onNodeWithText("任务中心").assertDoesNotExist()
        ui.onNodeWithContentDescription("设置").performClick()
        screen("settings")
        ui.onNodeWithText("DeepSeek API Key").performClick()
        screen("keys")
        ui.onNodeWithText("添加密钥").performClick()
        screen("key-add")
        ui.onNodeWithContentDescription("关闭面板").performClick()
        ui.onNodeWithContentDescription("返回").performClick()
        ui.onNodeWithText("无障碍服务").performScrollTo().performClick()
        ui.onNodeWithText("允许助手查看和操作界面").assertIsDisplayed()
        screen("accessibility-disclosure")
        ui.onNodeWithText("暂不开启").performScrollTo().performClick()
    }

    @Test
    fun scrollingBlurAndOfficialChatSurface() {
        ui.waitUntil(30_000) { AssistantState.ready }
        ui.runOnUiThread {
            AssistantState.appMode = "work"
            AssistantState.petEnabled = true
            AssistantState.addSession().apply {
                title = "滚动与玻璃过渡"
                repeat(12) { index ->
                    messages.add(
                        ChatEntry(
                            if (index % 3 == 0) "user" else "assistant",
                            "第 ${index + 1} 个想法\n\n留一些时间给喜欢的事情。让想法成为清晰的计划，也让每一次开始都轻松一点。\n\n我们一步一步来。",
                        ),
                    )
                }
            }
        }
        ui.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToIndex(5)
        ui.onAllNodes(hasScrollToIndexAction()).onLast().performTouchInput {
            swipeUp(startY = height * .7f, endY = height * .45f)
        }
        screen("scroll-glass")
        ui.onNodeWithTag("message-input").performTextInput("一起把事情做好")
        ui.waitUntil(5_000) {
            ui.activity.window.decorView.rootWindowInsets?.isVisible(
                android.view.WindowInsets.Type.ime(),
            ) == true
        }
        screen("keyboard")
        InstrumentationRegistry.getInstrumentation()
            .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        ui.onNodeWithContentDescription("会话列表").performClick()
        ui.onNodeWithText("Work").performClick()
        screen("modes")
        ui.onNodeWithText("Chat").performClick()
        ui.waitUntil(10_000) { OfficialChatNavigation.view != null }
        val until = System.currentTimeMillis() + 20_000
        var progress = 0
        while (System.currentTimeMillis() < until && progress != 100) {
            ui.runOnUiThread { progress = OfficialChatNavigation.view?.progress ?: 0 }
            Thread.sleep(200)
        }
        screen("official-chat")
        ui.runOnUiThread {
            assertEquals(
                "chat.deepseek.com",
                android.net.Uri.parse(OfficialChatNavigation.view!!.url).host,
            )
            // No Work bridge is added by this browser surface.
            AssistantState.appMode = "work"
            AssistantState.persist()
        }
    }

    @Test
    fun stoppedQueueAcceptsANewTurnThroughPackagedKernel() {
        ui.waitUntil(30_000) { AssistantState.ready }
        val keys = KeyStore(ui.activity)
        val previous = keys.list().firstOrNull { it.selected }?.id
        val testKey = keys.save("local-redesign-scheduler-fixture", "调度测试")
        fun js(code: String) {
            val done = CountDownLatch(1)
            ui.runOnUiThread {
                AgentService.current!!.webView.evaluateJavascript(code) { done.countDown() }
            }
            check(done.await(5, TimeUnit.SECONDS))
        }
        js(
            """
            window.__originalRpc = rpc;
            rpc = async (method, args, signal) => method === 'complete'
                ? {choices:[{message:{content:'fixture-reply-after-stop'},finish_reason:'stop'}]}
                : window.__originalRpc(method,args,signal);
            """
                .trimIndent(),
        )
        try {
            ui.runOnUiThread {
                AssistantState.activeTaskId = ""
                AssistantState.appMode = "work"
                AssistantState.addSession().apply {
                    access = "none"
                    phoneTools = false
                }
                AgentService.current!!.stopTask()
                assertTrue(AssistantState.queuePaused)
                AgentService.current!!.send("你好，停止后重新发送")
                assertFalse(AssistantState.queuePaused)
            }
            ui.waitUntil(20_000) { !AssistantState.busy }
            ui.runOnUiThread {
                assertEquals("done", AssistantState.tasks.last().status)
                assertTrue(
                    AssistantState.current.messages.any { it.text == "fixture-reply-after-stop" },
                )
            }
        } finally {
            js("rpc = window.__originalRpc")
            keys.delete(testKey)
            if (previous != null) keys.select(previous)
        }
    }

    @Test
    fun namedCredentialsAreEncryptedAndIndependentlySelectable() {
        ui.waitUntil(30_000) { AssistantState.ready }
        val keys = KeyStore(ui.activity)
        val old = keys.list().firstOrNull { it.selected }?.id
        val a = keys.save("test-secret-aaaaaaa", "测试 A")
        val b = keys.save("test-secret-bbbbbbb", "测试 B")
        try {
            keys.select(a)
            assertEquals("test-secret-aaaaaaa", keys.read())
            assertEquals("test-secret-bbbbbbb", keys.read(b))
            assertTrue(keys.list().first { it.id == a }.selected)
            assertFalse(keys.list().first { it.id == b }.masked.contains("bbbbbbb"))
            val persisted =
                ui.activity.getSharedPreferences("credentials", 0).getString("entries", "")!!
            assertFalse(persisted.contains("test-secret"))
            keys.delete(a)
            assertTrue(keys.read(a).isEmpty())
            assertTrue(keys.read(b).isNotEmpty())
        } finally {
            keys.delete(a)
            keys.delete(b)
            if (old != null) keys.select(old)
        }
    }
}
