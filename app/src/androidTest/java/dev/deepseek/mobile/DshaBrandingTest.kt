package dev.deepseek.mobile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import junit.framework.TestCase

class DshaBrandingTest : TestCase() {
    fun testNameAndExpandedArtworkFillRightAndBottom() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val info = context.applicationInfo
        assertEquals(
            "Deepseek Harness",
            context.packageManager.getApplicationLabel(info).toString(),
        )
        val icon = context.packageManager.getApplicationIcon(info) as AdaptiveIconDrawable
        icon.setBounds(0, 0, 256, 256)
        val rendered = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        icon.draw(Canvas(rendered))
        File(context.filesDir, "dsha-launcher-icon.png").outputStream().use {
            rendered.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        val foreground = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        icon.foreground.draw(Canvas(foreground))
        File(context.filesDir, "dsha-launcher-foreground.png").outputStream().use {
            foreground.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        // Inspect artwork alpha, not the opaque background, at the two requested edges.
        val right = (96..192).count { Color.alpha(foreground.getPixel(254, it)) > 240 }
        val bottom = (64..192).count { Color.alpha(foreground.getPixel(it, 254)) > 240 }
        println("ICON_EDGE_COVERAGE right=$right/97 bottom=$bottom/129")
        assertTrue("Right artwork edge still has a gap: $right", right >= 70)
        assertTrue("Bottom artwork edge still has a gap: $bottom", bottom >= 110)
        rendered.recycle()
        foreground.recycle()
    }
}
