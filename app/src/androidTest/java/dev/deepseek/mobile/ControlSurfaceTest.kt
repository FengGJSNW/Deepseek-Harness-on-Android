package dev.deepseek.mobile

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.RemoteViews
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import junit.framework.TestCase
import org.json.JSONObject

class ControlSurfaceTest : TestCase() {
    private val ins
        get() = InstrumentationRegistry.getInstrumentation()

    private fun main(block: () -> Unit) = ins.runOnMainSync(block)

    private fun js(source: String): String {
        val done = CountDownLatch(1)
        var result = "null"
        main {
            AgentService.current!!.webView.evaluateJavascript(source) {
                result = it
                done.countDown()
            }
        }
        check(done.await(10, TimeUnit.SECONDS))
        return result
    }

    private fun waitFor(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 15000
        while (!condition() && System.currentTimeMillis() < until) Thread.sleep(50)
        assertTrue(condition())
    }

    private fun rpc(method: String, args: String = "{}"): JSONObject {
        js(
            "window.__policy=null;rpc('$method',$args).then(v=>window.__policy={ok:true},e=>window.__policy={ok:false,error:e.message})",
        )
        waitFor { js("window.__policy") != "null" }
        return JSONObject(js("window.__policy"))
    }

    private fun screen(view: View, name: String) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(ins.targetContext.filesDir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    fun testNativePoliciesOverlayAndWidget() {
        val ctx = ins.targetContext
        val automation =
            ins.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun shell(cmd: String) {
            automation.executeShellCommand(cmd).use {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
            }
        }
        shell("appops set dev.deepseek.mobile SYSTEM_ALERT_WINDOW allow")
        shell("pm grant dev.deepseek.mobile android.permission.POST_NOTIFICATIONS")
        ins.startActivitySync(
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        waitFor { AssistantState.ready }
        val service = AgentService.current!!
        val previous = KeyStore(ctx).read()
        KeyStore(ctx).save("local-policy-test-only")
        js("window.__realRunTask=window.runTask;window.runTask=t=>{}")
        fun finish() {
            main {
                val id = AssistantState.activeTaskId
                service.onUiEvent("task_done", JSONObject().put("taskId", id).toString())
            }
        }
        try {
            main {
                val c = AssistantState.addSession()
                c.access = "read"
                service.resumeQueue()
                service.send("read-only policy")
            }
            assertTrue(rpc("begin").getBoolean("ok"))
            val denied = rpc("global_action", "{action:'home'}")
            assertFalse(denied.getBoolean("ok"))
            assertTrue(denied.getString("error").contains("只读"))
            rpc("end")
            finish()
            main {
                AssistantState.current.phoneTools = false
                AssistantState.current.access = "full"
                service.send("tools disabled")
            }
            rpc("begin")
            val disabled = rpc("observe")
            assertFalse(disabled.getBoolean("ok"))
            assertTrue(disabled.getString("error").contains("未启用手机工具"))
            rpc("end")
            finish()
            main {
                AssistantState.current.phoneTools = true
                AssistantState.current.access = "ask"
                service.send("approval gate")
            }
            rpc("begin")
            js(
                "window.__policy=null;Promise.all([rpc('global_action',{action:'home'}),rpc('global_action',{action:'back'})]).then(v=>window.__policy={ok:true},e=>window.__policy={ok:false,error:e.message})",
            )
            waitFor { AssistantState.running?.approval == true }
            assertEquals("null", js("window.__policy"))
            main { service.setFloating(true) }
            Thread.sleep(500)
            main {
                val field =
                    AgentService::class.java.getDeclaredField("floating").apply {
                        isAccessible = true
                    }
                val control = field.get(service)
                val rootField =
                    FloatingControl::class.java.getDeclaredField("root").apply {
                        isAccessible = true
                    }
                val root = rootField.get(control) as android.widget.LinearLayout
                root.getChildAt(0).performClick()
            }
            Thread.sleep(300)
            main {
                val field =
                    AgentService::class.java.getDeclaredField("floating").apply {
                        isAccessible = true
                    }
                val control = field.get(service)
                val rootField =
                    FloatingControl::class.java.getDeclaredField("root").apply {
                        isAccessible = true
                    }
                val root = rootField.get(control) as android.widget.LinearLayout
                screen(root, "v03-overlay")
                root.getChildAt(0).performLongClick()
                assertTrue(AssistantState.queuePaused)
                assertEquals("stopping", AssistantState.running!!.status)
                val manager = ctx.getSystemService(android.app.NotificationManager::class.java)
                assertTrue(
                    manager.activeNotifications.any { it.notification.channelId == "events" },
                )
                service.setFloating(false)
                val widget = RemoteViews(ctx.packageName, R.layout.whale_widget).apply(ctx, null)
                widget.measure(
                    View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                )
                widget.layout(0, 0, 600, 300)
                assertNotNull(widget.findViewById<TextView>(R.id.widget_status))
                screen(widget, "v03-widget")
            }
            waitFor { js("window.__policy") != "null" }
            assertFalse(JSONObject(js("window.__policy")).getBoolean("ok"))
            finish()
        } finally {
            main { service.setFloating(false) }
            js("window.runTask=window.__realRunTask")
            if (previous.isEmpty()) KeyStore(ctx).clear() else KeyStore(ctx).save(previous)
        }
    }
}
