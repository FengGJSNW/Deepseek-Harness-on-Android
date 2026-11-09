package dev.deepseek.mobile

import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.fonts.SystemFonts
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class InstalledFont(val id: String, val title: String, val font: Font)

/** Read the ROM's actual available faces, without bundling them or inventing font features. */
internal object SystemFontCatalog {
    @Volatile
    var fonts: List<InstalledFont> = emptyList()
        private set

    private val typefaces = ConcurrentHashMap<String, Typeface>()

    suspend fun load(): List<InstalledFont> =
        withContext(Dispatchers.IO) {
            if (fonts.isNotEmpty()) return@withContext fonts
            fonts =
                runCatching {
                        SystemFonts.getAvailableFonts()
                            .mapNotNull { font ->
                                val file = font.file ?: return@mapNotNull null
                                if (!file.isFile || !file.canRead()) return@mapNotNull null
                                InstalledFont(
                                    "installed:${file.absolutePath}#${font.ttcIndex}",
                                    file.nameWithoutExtension.replace('-', ' ') +
                                        (if (font.ttcIndex > 0) " · ${font.ttcIndex}" else ""),
                                    font,
                                )
                            }
                            .distinctBy { it.id }
                            .sortedBy { it.title.lowercase() }
                    }
                    .getOrDefault(emptyList())
            fonts
        }

    fun typeface(id: String): Typeface? {
        val face = fonts.firstOrNull { it.id == id } ?: return null
        return typefaces.getOrPut(id) {
            Typeface.CustomFallbackBuilder(FontFamily.Builder(face.font).build())
                .setSystemFallback("sans-serif")
                .build()
        }
    }
}
