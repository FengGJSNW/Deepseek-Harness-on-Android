package dev.deepseek.mobile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.window.DialogWindowProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Capture owned window surfaces without changing the app's FLAG_SECURE protection. */
internal object TestSnapshots {
    private data class Layer(val view: View, val window: Window?, val x: Int, val y: Int)

    private fun dialogWindow(view: View): Window? {
        if (view is DialogWindowProvider) return view.window
        if (view is ViewGroup)
            for (i in 0 until view.childCount) dialogWindow(view.getChildAt(i))?.let {
                return it
            }
        return null
    }

    private fun copy(
        ui: AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>,
        window: Window,
    ): Bitmap {
        val done = CountDownLatch(1)
        var status = -1
        lateinit var bitmap: Bitmap
        ui.runOnUiThread {
            val root = window.decorView
            bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(
                window,
                bitmap,
                {
                    status = it
                    done.countDown()
                },
                Handler(Looper.getMainLooper()),
            )
        }
        check(done.await(5, TimeUnit.SECONDS) && status == PixelCopy.SUCCESS) {
            "Owned window copy failed: $status"
        }
        return bitmap
    }

    fun capture(
        ui: AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>,
        name: String,
    ) {
        ui.waitForIdle()
        Thread.sleep(150)
        val main = ui.activity.window
        var origin = IntArray(2)
        var layers = emptyList<Layer>()
        ui.runOnUiThread {
            main.decorView.getLocationOnScreen(origin)
            val seen = mutableSetOf<Window>()
            layers =
                android.view.inspector.WindowInspector.getGlobalWindowViews()
                    .filter { it !== main.decorView && it.isShown && it.width > 0 }
                    .mapNotNull { view ->
                        val window = dialogWindow(view)
                        if (window != null && !seen.add(window)) return@mapNotNull null
                        val root = window?.decorView ?: view
                        val xy = IntArray(2)
                        root.getLocationOnScreen(xy)
                        Layer(root, window, xy[0] - origin[0], xy[1] - origin[1])
                    }
        }
        val image = copy(ui, main)
        val canvas = Canvas(image)
        layers.forEach { layer ->
            val window = layer.window
            if (window != null) {
                val bitmap = copy(ui, window)
                if (name == "v072-center-shadow") {
                    File(ui.activity.filesDir, "v060-v072-center-shadow-window.png")
                        .outputStream()
                        .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    File(ui.activity.filesDir, "v072-window-metrics.json")
                        .writeText(
                            org.json
                                .JSONObject()
                                .put("x", layer.x)
                                .put("y", layer.y)
                                .put("width", bitmap.width)
                                .put("height", bitmap.height)
                                .toString(2),
                        )
                }
                canvas.drawColor(
                    android.graphics.Color.argb(
                        (window.attributes.dimAmount * 255).toInt(),
                        0,
                        0,
                        0,
                    ),
                )
                canvas.drawBitmap(bitmap, layer.x.toFloat(), layer.y.toFloat(), null)
                bitmap.recycle()
            } else
                ui.runOnUiThread {
                    canvas.save()
                    canvas.translate(layer.x.toFloat(), layer.y.toFloat())
                    layer.view.draw(canvas)
                    canvas.restore()
                }
        }
        File(ui.activity.filesDir, "v060-$name.png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        image.recycle()
    }
}
