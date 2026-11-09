package dev.deepseek.mobile

import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import junit.framework.TestCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class PhoneObservationPrivacyTest : TestCase() {
    private val ins
        get() = InstrumentationRegistry.getInstrumentation()

    fun testReviewOverlayIsRestoredButExcludedFromPhoneObservation() {
        val automation = ins.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun shell(command: String) =
            automation.executeShellCommand(command).let {
                ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader ->
                    reader.readText().trim()
                }
            }
        fun waitFor(condition: () -> Boolean) {
            val timeout = System.currentTimeMillis() + 15000L
            while (!condition() && System.currentTimeMillis() < timeout) Thread.sleep(100)
            assertTrue(condition())
        }
        val activity =
            ins.startActivitySync(
                Intent(ins.targetContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as MainActivity
        assertTrue(Settings.canDrawOverlays(activity))
        waitFor { AssistantState.ready && AgentService.current != null }
        if (PhoneAccessibility.current == null) {
            val configured = shell("settings get secure enabled_accessibility_services")
            assertTrue(configured.contains("dev.deepseek.mobile"))
            shell("settings delete secure enabled_accessibility_services")
            Thread.sleep(400)
            shell("settings put secure enabled_accessibility_services $configured")
            shell("settings put secure accessibility_enabled 1")
            waitFor { PhoneAccessibility.current != null }
        }
        val service = checkNotNull(AgentService.current)
        val prefs = activity.getSharedPreferences("ui", Context.MODE_PRIVATE)
        val previous = prefs.getBoolean("floating", false)
        lateinit var task: PhoneTask
        val prompt = "仅用户可见的确认正文"
        fun observe() = runBlocking {
            withContext(Dispatchers.Main) { checkNotNull(PhoneAccessibility.current).observe() }
        }
        try {
            ins.runOnMainSync {
                resetTransientUiForTest()
                AssistantState.appMode = "work"
                service.setFloating(false)
                task =
                    PhoneTask(
                            sessionId = AssistantState.addSession().id,
                            text = "测试会话概括",
                            images = emptyList(),
                            model = "deepseek-flash",
                            effort = "low",
                            access = "ask",
                            phoneTools = true,
                            fileTools = false,
                            workspace = "",
                        )
                        .apply {
                            question = prompt
                            approval = true
                            status = "waiting"
                        }
                AssistantState.tasks.add(task)
                AssistantState.activeTaskId = task.id
            }
            Thread.sleep(400)
            val own = observe()
            assertFalse(own.toString().contains(prompt))
            assertFalse(own.has("image"))
            assertEquals(0, own.getJSONArray("nodes").length())

            ins.runOnMainSync { activity.startActivity(Intent(Settings.ACTION_SETTINGS)) }
            waitFor { !MainActivity.inForeground }
            Thread.sleep(850)
            val baseline = observe()
            assertEquals("com.android.settings", baseline.optString("package"))
            assertTrue(baseline.toString(), baseline.has("image"))
            ins.runOnMainSync { service.setFloating(true) }
            Thread.sleep(500)
            lateinit var review: android.view.View
            ins.runOnMainSync {
                review =
                    android.view.inspector.WindowInspector.getGlobalWindowViews().first {
                        it.isShown &&
                            (it.layoutParams as? WindowManager.LayoutParams)?.let { params ->
                                params.type ==
                                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY &&
                                    params.gravity and Gravity.VERTICAL_GRAVITY_MASK ==
                                        Gravity.BOTTOM
                            } == true
                    }
                assertTrue(
                    (review.layoutParams as WindowManager.LayoutParams).flags and
                        WindowManager.LayoutParams.FLAG_SECURE != 0,
                )
                val screenshot =
                    Bitmap.createBitmap(review.width, review.height, Bitmap.Config.ARGB_8888)
                review.draw(Canvas(screenshot))
                File(activity.filesDir, "v082-background-review.png").outputStream().use {
                    screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                screenshot.recycle()
            }
            val masked = observe()
            assertEquals("com.android.settings", masked.optString("package"))
            assertFalse(masked.toString().contains(prompt))
            assertTrue(masked.toString(), masked.has("image"))
            fun bitmap(observation: org.json.JSONObject) =
                BitmapFactory.decodeFile(
                    File(
                            activity.filesDir,
                            "screens/" +
                                observation.getJSONObject("image").getString("attachmentId") +
                                ".jpg",
                        )
                        .path,
                )
            val a = bitmap(baseline)
            val b = bitmap(masked)
            val coords = IntArray(2)
            ins.runOnMainSync {
                assertTrue(review.isShown)
                review.getLocationOnScreen(coords)
            }
            val y =
                ((coords[1] + review.height / 2) * b.height / masked.getInt("screenHeight"))
                    .coerceIn(0, b.height - 1)
            val x =
                ((coords[0] + review.width / 2) * b.width / masked.getInt("screenWidth")).coerceIn(
                    0,
                    b.width - 1,
                )
            assertEquals(a.getPixel(x, y), b.getPixel(x, y))
            a.recycle()
            b.recycle()
        } finally {
            ins.runOnMainSync {
                AssistantState.activeTaskId = ""
                AssistantState.tasks.remove(task)
                service.setFloating(previous)
                activity.finish()
            }
        }
    }
}
