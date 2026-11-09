package dev.deepseek.mobile

import android.graphics.*
import androidx.compose.animation.core.*
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import junit.framework.TestCase
import org.json.JSONObject

/** Offline artifact exporter. Run on the emulator only; renders the existing artwork unchanged. */
class WhaleGifExport : TestCase() {
    fun testExportRhythmicSquashFrames() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.filesDir, "gif-rhythm-v040").apply { mkdirs() }
        val source =
            BitmapFactory.decodeResource(context.resources, R.drawable.whale_chibi_expanded)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val values = mutableListOf<Float>()
        // 200 BPM: 6 frames down, 9 frames up; the next beat starts immediately.
        for (index in 0 until 30) {
            val phase = index % 15
            val compression = if (phase <= 6) phase / 6f else (15 - phase) / 9f
            values.add(compression)
            val frame = Bitmap.createBitmap(768, 768, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(frame)
            canvas.translate(384f, 752f)
            canvas.scale(1f + compression * .10f, 1f - compression * .24f)
            canvas.drawBitmap(source, null, RectF(-345f, -690f, 345f, 0f), paint)
            File(folder, "frame%04d.png".format(index)).outputStream().use {
                frame.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            frame.recycle()
        }
        val metadata =
            JSONObject()
                .put("fps", 50)
                .put("width", 768)
                .put("height", 768)
                .put("frames", 30)
                .put("duration_ms", 600)
                .put("bpm", 200)
                .put("press_ms", 120)
                .put("return_ms", 180)
                .put("pause_ms", 0)
                .put("damping", false)
                .put("overshoot", false)
                .put("compression", org.json.JSONArray(values))
                .put("scale_x", "1 + compression * 0.10")
                .put("scale_y", "1 - compression * 0.24")
                .put("pivot", "bottom-center")
                .put("transparent", true)
        File(folder, "metadata.json").writeText(metadata.toString(2))
        source.recycle()
        println("RHYTHM_GIF=$metadata")
        assertEquals(0f, values[0])
        assertEquals(1f, values[6])
        assertEquals(0f, values[15])
    }

    fun testExportHighFrameRateJellyFrames() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.filesDir, "gif-export-v040").apply { mkdirs() }
        val source =
            BitmapFactory.decodeResource(context.resources, R.drawable.whale_chibi_expanded)
        val spring =
            FloatSpringSpec(dampingRatio = .4f, stiffness = 500f, visibilityThreshold = .01f)
        val press =
            FloatTweenSpec(duration = UiTuning.PetSquishPressMillis, easing = FastOutSlowInEasing)
        val pressNanos = UiTuning.PetSquishPressMillis * 1_000_000L
        val releaseNanos = spring.getDurationNanos(1f, 0f, 0f)
        val durationNanos = pressNanos + releaseNanos + UiTuning.PetSquishPauseMillis * 1_000_000L
        val frames = ((durationNanos + 19_999_999L) / 20_000_000L).toInt()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        for (index in 0 until frames) {
            val time = index * 20_000_000L
            val compression =
                when {
                    time < pressNanos -> press.getValueFromNanos(time, 0f, 1f, 0f)
                    time < pressNanos + releaseNanos ->
                        spring.getValueFromNanos(time - pressNanos, 1f, 0f, 0f)
                    else -> 0f
                }.coerceIn(-.4f, 1f)
            val frame = Bitmap.createBitmap(768, 768, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(frame)
            canvas.translate(384f, 752f)
            // Identical .5/1 pivot, 5% width and 12% height deformation as WhalePet.
            canvas.scale(1f + compression * .05f, 1f - compression * .12f)
            canvas.drawBitmap(source, null, RectF(-345f, -690f, 345f, 0f), paint)
            File(folder, "frame%04d.png".format(index)).outputStream().use {
                frame.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            frame.recycle()
        }
        val metadata =
            JSONObject()
                .put("fps", 50)
                .put("width", 768)
                .put("height", 768)
                .put("frames", frames)
                .put("duration_ms", frames * 20)
                .put("press_ms", UiTuning.PetSquishPressMillis)
                .put("pause_ms", UiTuning.PetSquishPauseMillis)
                .put("damping_ratio", .4)
                .put("stiffness", 500)
                .put("scale_x", "1 + compression * 0.05")
                .put("scale_y", "1 - compression * 0.12")
                .put("source", "whale_chibi_expanded.png")
                .put("pivot", "bottom-center")
                .put("transparent", true)
        File(folder, "metadata.json").writeText(metadata.toString(2))
        source.recycle()
        println("GIF_EXPORT=$metadata")
        assertTrue(frames >= 30)
    }
}
