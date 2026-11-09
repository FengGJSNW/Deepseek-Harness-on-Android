package dev.deepseek.mobile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GlassMotionTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private fun snapshot(name: String): Bitmap {
        TestSnapshots.capture(ui, name)
        return BitmapFactory.decodeFile(File(ui.activity.filesDir, "v060-$name.png").path)
    }

    private fun origin(): IntArray =
        IntArray(2).also { xy ->
            ui.runOnUiThread {
                (ui.activity
                        .findViewById<android.view.ViewGroup>(android.R.id.content)
                        .getChildAt(0))
                    .getLocationInWindow(xy)
            }
        }

    @Test
    fun blurSamplesGlyphsOutsideTheVisibleCropBeforeClipping() {
        ui.waitUntil(30000) { AssistantState.ready }
        val stripe = mutableFloatStateOf(92f)
        ui.runOnUiThread {
            ui.activity.setContent {
                MaterialTheme {
                    val source =
                        rememberGraphicsLayer().let { layer ->
                            remember(layer) { ChatBackdrop(layer) }
                        }
                    Box(Modifier.fillMaxSize().background(Color.White)) {
                        Canvas(Modifier.fillMaxSize().chatBackdrop(source)) {
                            drawRect(Color.White)
                            drawRect(
                                Color.Black,
                                Offset(60.dp.toPx(), stripe.floatValue.dp.toPx()),
                                Size(180.dp.toPx(), 4.dp.toPx()),
                            )
                        }
                        FrostedGlass(
                            source,
                            Modifier.offset(y = 100.dp)
                                .width(300.dp)
                                .height(100.dp)
                                .testTag("glass-crop"),
                            Color.Transparent,
                            12f,
                        )
                    }
                }
            }
        }
        val bounds = ui.onNodeWithTag("glass-crop").fetchSemanticsNode().boundsInRoot
        val xy = origin()
        val x = (bounds.left + bounds.width / 2 + xy[0]).toInt()
        val y = (bounds.top + xy[1] + with(ui.density) { 2.dp.toPx() }).toInt()
        val samples = mutableListOf<Int>()
        listOf(92f, 96f, 100f, 104f, 108f).forEachIndexed { index, value ->
            ui.runOnUiThread { stripe.floatValue = value }
            val bitmap = snapshot("v072-glass-boundary-$index")
            samples.add(android.graphics.Color.red(bitmap.getPixel(x, y)))
            bitmap.recycle()
        }
        assertTrue(
            "A glyph outside the crop must contribute to the filter; got $samples",
            samples.first() < 248,
        )
        assertTrue(
            "Crossing the boundary must not flash to an empty white crop: $samples",
            samples.max() - samples.min() < 45,
        )
        File(ui.activity.filesDir, "v072-glass-metrics.json")
            .writeText(
                org.json
                    .JSONObject()
                    .put("edgeSamples", org.json.JSONArray(samples))
                    .put("haloDp", 36)
                    .toString(2),
            )
    }

    @Test
    fun functionIslandFadesInWithSmallTranslationAndNoBlur() {
        ui.waitUntil(30000) { AssistantState.ready }
        val progress = mutableFloatStateOf(0f)
        ui.runOnUiThread {
            ui.activity.setContent {
                MaterialTheme {
                    Box(
                        Modifier.fillMaxSize().background(Color.White),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.functionIslandReveal { progress.floatValue }.padding(24.dp)) {
                            Box(
                                Modifier.size(260.dp, 90.dp)
                                    .background(Color.White)
                                    .testTag("reveal-card"),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("模型与强度 DeepSeek Flash", fontSize = 18.sp, color = Color.Black)
                            }
                        }
                    }
                }
            }
        }
        val initial = ui.onNodeWithTag("reveal-card").fetchSemanticsNode().boundsInRoot
        val xy = origin()
        val black = mutableListOf<Int>()
        listOf(0f, .4f, 1f).forEachIndexed { index, value ->
            ui.runOnUiThread { progress.floatValue = value }
            val bounds = ui.onNodeWithTag("reveal-card").fetchSemanticsNode().boundsInRoot
            assertTrue(
                "Reveal must stay near its anchor",
                kotlin.math.abs(bounds.center.y - initial.center.y) < 24f,
            )
            assertTrue("Reveal must not collapse", bounds.width >= initial.width)
            val bitmap = snapshot("v072-island-reveal-$index")
            var count = 0
            for (y in (bounds.top + xy[1]).toInt() until (bounds.bottom + xy[1]).toInt()) for (x in
                (bounds.left + xy[0]).toInt() until (bounds.right + xy[0]).toInt()) if (
                android.graphics.Color.red(bitmap.getPixel(x, y)) < 40
            )
                count++
            black.add(count)
            bitmap.recycle()
        }
        assertEquals("First phase must be transparent", 0, black[0])
        assertTrue(
            "Final text must resolve from the translucent phase: $black",
            black[2] > black[1] + 50,
        )
        File(ui.activity.filesDir, "v072-reveal-metrics.json")
            .writeText(
                org.json
                    .JSONObject()
                    .put("responseSeconds", UiSpring.NavigationResponse)
                    .put("blackPixels", org.json.JSONArray(black))
                    .put("anchoredMovement", true)
                    .toString(2),
            )
    }
}
