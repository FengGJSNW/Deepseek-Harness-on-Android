package dev.deepseek.mobile

import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FontSystemTest {
    @Test
    fun bundledFontsHaveCjkFallbackAndRealMonospace() =
        runBlocking<Unit> {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            FontSettings.initialize(context)
            DshaFonts.prepare(context)
            assertTrue(DshaFonts.error, DshaFonts.ready)
            val old = FontSettings.preset
            try {
                FontPreset.entries
                    .filter { it != FontPreset.OTHER }
                    .forEach { preset ->
                        FontSettings.preset = preset
                        val paint =
                            Paint().apply {
                                typeface = DshaFonts.native(FontRole.BODY)
                                textSize = 24f
                            }
                        assertTrue("Chinese fallback in $preset", paint.hasGlyph("鲸"))
                        assertTrue("Emoji fallback in $preset", paint.hasGlyph("🐳"))
                    }
                FontSettings.preset = FontPreset.MONO
                val mono =
                    Paint().apply {
                        typeface = DshaFonts.native(FontRole.CODE)
                        textSize = 24f
                    }
                assertEquals(mono.measureText("iiii"), mono.measureText("WWWW"), .01f)
                FontSettings.preset = FontPreset.SYSTEM
                assertSame(Typeface.DEFAULT, DshaFonts.native(FontRole.UI))
                assertFalse(FontSettings.knownFeatures)
            } finally {
                FontSettings.preset = old
                FontSettings.changed()
            }
        }
}
