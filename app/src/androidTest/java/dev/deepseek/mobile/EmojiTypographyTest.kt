package dev.deepseek.mobile

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class EmojiTypographyTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test
    fun sequencesKeepOffsetsAndAnnotationsWhileOverridingItalic() {
        for (emoji in listOf("😀", "👩🏽‍💻", "👨‍👩‍👧‍👦", "🇨🇳", "1️⃣", "❤️", "©️", "→")) {
            val original =
                AnnotatedString.Builder("Italic $emoji end")
                    .apply {
                        addStyle(SpanStyle(fontStyle = FontStyle.Italic), 0, length)
                        addStringAnnotation("URL", "https://example.invalid", 0, length)
                    }
                    .toAnnotatedString()
            val result = UprightSymbols.text(original)
            assertEquals(original.text, result.text)
            assertEquals(
                original.getStringAnnotations(0, original.length),
                result.getStringAnnotations(0, result.length),
            )
            val override = result.spanStyles.last()
            assertEquals(emoji, result.text.substring(override.start, override.end))
            assertEquals(FontStyle.Normal, override.item.fontStyle)
            assertEquals(FontSynthesis.None, override.item.fontSynthesis)
            val transformed = UprightSymbols.transformation.filter(original)
            for (offset in 0..original.length) {
                assertEquals(offset, transformed.offsetMapping.originalToTransformed(offset))
                assertEquals(offset, transformed.offsetMapping.transformedToOriginal(offset))
            }
        }
        assertTrue(UprightSymbols.ranges("Italic 中文 123 # * + =").isEmpty())
    }

    @Test
    fun everyFontKeepsEmojiUprightAndTextItalic() {
        ui.waitUntil(30000) { AssistantState.ready && DshaFonts.ready }
        val preset = FontSettings.preset
        val other = FontSettings.otherFamily
        try {
            for (font in FontPreset.entries) {
                var result: TextLayoutResult? = null
                ui.runOnUiThread {
                    FontSettings.preset = font
                    FontSettings.otherFamily = "serif"
                    FontSettings.changed()
                    ui.activity.setContent {
                        MaterialTheme {
                            FontEnvironment {
                                Surface(color = Color.White) {
                                    Column(
                                        Modifier.fillMaxSize().padding(24.dp),
                                        verticalArrangement = Arrangement.spacedBy(20.dp),
                                    ) {
                                        Text(font.title, fontSize = 18.sp)
                                        Text(
                                            "Normal text 😀 👩🏽‍💻 🇨🇳 1️⃣",
                                            fontSize = 20.sp,
                                            fontFamily =
                                                DshaFonts.family(FontRole.BODY, italic = false),
                                            fontStyle = FontStyle.Normal,
                                        )
                                        Text(
                                            "Italic text 😀 👩🏽‍💻 🇨🇳 1️⃣",
                                            Modifier.testTag("italic-emoji"),
                                            fontSize = 20.sp,
                                            fontFamily =
                                                DshaFonts.family(FontRole.BODY, italic = true),
                                            fontStyle = FontStyle.Italic,
                                            onTextLayout = { result = it },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                ui.onNodeWithTag("italic-emoji").assertIsDisplayed()
                ui.runOnIdle {
                    val layout = requireNotNull(result)
                    assertEquals(FontStyle.Italic, layout.layoutInput.style.fontStyle)
                    assertEquals(
                        4,
                        layout.layoutInput.text.spanStyles.count {
                            it.item.fontStyle == FontStyle.Normal
                        },
                    )
                }
                if (font == FontPreset.ANTHROPIC) TestSnapshots.capture(ui, "ux-italic-emoji")
            }
        } finally {
            ui.runOnUiThread {
                FontSettings.preset = preset
                FontSettings.otherFamily = other
                FontSettings.changed()
            }
        }
    }
}
