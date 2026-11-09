package dev.deepseek.mobile

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontStyle as AndroidFontStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class FontRole {
    UI,
    BODY,
    CODE,
    SERIF,
}

/** Native fallback families, not a list of unrelated fonts masquerading as one Compose family. */
internal object DshaFonts {
    var ready by mutableStateOf(false)
        private set

    var error by mutableStateOf("")
        private set

    private val families = ConcurrentHashMap<String, android.graphics.fonts.FontFamily>()
    private val typefaces = ConcurrentHashMap<String, Typeface>()
    private val buffers = ConcurrentHashMap<String, ByteBuffer>()

    suspend fun prepare(context: Context) {
        if (ready || error.isNotEmpty()) return
        if (FontSettings.preset == FontPreset.OTHER) SystemFontCatalog.load()
        val result =
            withContext(Dispatchers.IO) {
                runCatching {
                    listOf(
                            Triple("sans", "source_sans.ttf", "source_sans_italic.ttf"),
                            Triple("serif", "source_serif.ttf", "source_serif_italic.ttf"),
                            Triple("mono", "jetbrains_mono.ttf", "jetbrains_mono_italic.ttf"),
                            Triple("cjk", "noto_sans_sc.ttf", ""),
                            Triple("cjk-serif", "noto_serif_sc.ttf", ""),
                        )
                        .forEach { (key, regular, italic) ->
                            fun font(file: String, weight: Int, slant: Int): Font {
                                val data =
                                    buffers.getOrPut(file) {
                                        val bytes =
                                            context.assets.open("fonts/$file").use {
                                                it.readBytes()
                                            }
                                        ByteBuffer.allocateDirect(bytes.size)
                                            .apply {
                                                put(bytes)
                                                flip()
                                            }
                                            .asReadOnlyBuffer()
                                    }
                                return Font.Builder(data)
                                    .setWeight(weight)
                                    .setSlant(slant)
                                    .setFontVariationSettings("'wght' $weight")
                                    .build()
                            }
                            val builder =
                                android.graphics.fonts.FontFamily.Builder(
                                    font(regular, 400, AndroidFontStyle.FONT_SLANT_UPRIGHT),
                                )
                            for (w in listOf(300, 500, 600, 700, 800)) builder.addFont(
                                font(regular, w, AndroidFontStyle.FONT_SLANT_UPRIGHT),
                            )
                            if (italic.isNotBlank())
                                for (w in listOf(300, 400, 500, 600, 700, 800)) builder.addFont(
                                    font(italic, w, AndroidFontStyle.FONT_SLANT_ITALIC),
                                )
                            if (italic.isBlank()) {
                                // Preserve upright CJK outlines for italic text; do not synthesize
                                // a skew.
                                for (w in listOf(300, 400, 500, 600, 700, 800)) builder.addFont(
                                    font(regular, w, AndroidFontStyle.FONT_SLANT_ITALIC),
                                )
                            }
                            families[key] = builder.build()
                        }
                }
            }
        if (result.isSuccess) ready = true
        else error = "字体加载失败，已使用系统回退：${result.exceptionOrNull()?.message}"
        MarkdownDocument.invalidate()
    }

    fun native(
        role: FontRole,
        weight: Int = if (FontSettings.knownFeatures) FontSettings.weight else 400,
        italic: Boolean = FontSettings.knownFeatures && FontSettings.italic,
    ): Typeface {
        val preset = FontSettings.preset
        if (preset == FontPreset.SYSTEM || !ready)
            return (if (role == FontRole.CODE) Typeface.MONOSPACE else Typeface.DEFAULT).let {
                if (weight == 400 && !italic) it else Typeface.create(it, weight, italic)
            }
        if (preset == FontPreset.OTHER) {
            if (role != FontRole.CODE)
                SystemFontCatalog.typeface(FontSettings.otherFamily)?.let {
                    return it
                }
            val selected = FontSettings.otherFamily.takeIf { it in systemFamilies } ?: "sans-serif"
            return Typeface.create(
                if (role == FontRole.CODE) "monospace" else selected,
                Typeface.NORMAL,
            )
        }
        val latin =
            when {
                role == FontRole.CODE -> "mono"
                role == FontRole.SERIF ||
                    (role == FontRole.BODY &&
                        preset in setOf(FontPreset.DEFAULT, FontPreset.ANTHROPIC)) -> "serif"
                preset == FontPreset.MONO && role in setOf(FontRole.BODY, FontRole.UI) -> "mono"
                else -> "sans"
            }
        val cjk = if (latin == "serif") "cjk-serif" else "cjk"
        return typefaces.getOrPut("$latin/$cjk/$weight/$italic") {
            Typeface.CustomFallbackBuilder(families.getValue(latin))
                .addCustomFallback(families.getValue(cjk))
                .setSystemFallback("sans-serif")
                .setStyle(
                    AndroidFontStyle(
                        weight,
                        if (italic) AndroidFontStyle.FONT_SLANT_ITALIC
                        else AndroidFontStyle.FONT_SLANT_UPRIGHT,
                    ),
                )
                .build()
        }
    }

    fun family(
        role: FontRole,
        weight: Int = if (FontSettings.knownFeatures) FontSettings.weight else 400,
        italic: Boolean = FontSettings.knownFeatures && FontSettings.italic,
    ) = FontFamily(native(role, weight, italic))

    val mono
        get() = family(FontRole.CODE)

    val systemFamilies =
        listOf(
            "sans-serif",
            "serif",
            "monospace",
            "sans-serif-condensed",
            "sans-serif-light",
            "sans-serif-medium",
            "sans-serif-smallcaps",
            "cursive",
        )
    val features
        get() =
            if (FontSettings.knownFeatures)
                "'liga' ${if (FontSettings.ligatures) 1 else 0}, 'calt' ${if (FontSettings.ligatures) 1 else 0}"
            else null
}

@Composable
internal fun FontEnvironment(content: @Composable () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        FontSettings.initialize(context)
        DshaFonts.prepare(context)
    }
    FontSettings.revision
    DshaFonts.ready
    val base = LocalDensity.current
    val family = DshaFonts.family(FontRole.UI)
    fun TextStyle.adapt() =
        copy(
            fontFamily = family,
            letterSpacing =
                androidx.compose.ui.unit.TextUnit(0f, androidx.compose.ui.unit.TextUnitType.Sp),
            fontFeatureSettings = DshaFonts.features,
            fontSynthesis = FontSynthesis.Weight,
            fontWeight =
                if (FontSettings.knownFeatures) FontWeight(FontSettings.weight) else fontWeight,
            fontStyle =
                if (FontSettings.knownFeatures && FontSettings.italic) FontStyle.Italic
                else fontStyle,
        )
    val defaults = Typography()
    val typography =
        defaults.copy(
            displayLarge = defaults.displayLarge.adapt(),
            displayMedium = defaults.displayMedium.adapt(),
            displaySmall = defaults.displaySmall.adapt(),
            headlineLarge = defaults.headlineLarge.adapt(),
            headlineMedium = defaults.headlineMedium.adapt(),
            headlineSmall = defaults.headlineSmall.adapt(),
            titleLarge = defaults.titleLarge.adapt(),
            titleMedium = defaults.titleMedium.adapt(),
            titleSmall = defaults.titleSmall.adapt(),
            bodyLarge = defaults.bodyLarge.adapt(),
            bodyMedium = defaults.bodyMedium.adapt(),
            bodySmall = defaults.bodySmall.adapt(),
            labelLarge = defaults.labelLarge.adapt(),
            labelMedium = defaults.labelMedium.adapt(),
            labelSmall = defaults.labelSmall.adapt(),
        )
    CompositionLocalProvider(
        LocalDensity provides Density(base.density, base.fontScale * FontSettings.scale),
    ) {
        MaterialTheme(typography = typography, content = content)
    }
}
