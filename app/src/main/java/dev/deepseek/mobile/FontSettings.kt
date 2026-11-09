package dev.deepseek.mobile

import android.content.Context
import androidx.compose.runtime.*

internal enum class FontPreset(val key: String, val title: String, val detail: String) {
    DEFAULT("default", "DSHA 默认", "界面 Sans · 正文 Serif · 代码 Mono"),
    SYSTEM("system", "跟随系统", "使用当前 ROM、主题与系统字形"),
    MONO("mono", "JetBrains Mono", "英文与代码等宽，中文自动回退"),
    ANTHROPIC("anthropic", "Anthropic 风格", "开源替代：Source Sans / Serif / JetBrains Mono"),
    OTHER("other", "其他字体", "系统提供的其他字族");

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/** User-owned appearance settings; never exposed as an Agent-writable configuration file. */
internal object FontSettings {
    var preset by mutableStateOf(FontPreset.DEFAULT)
    var otherFamily by mutableStateOf("serif")
    var scale by mutableFloatStateOf(1f)
    var weight by mutableIntStateOf(400)
    var italic by mutableStateOf(false)
    var ligatures by mutableStateOf(true)
    var revision by mutableIntStateOf(0)
        private set

    private var context: Context? = null

    val knownFeatures
        get() = preset != FontPreset.SYSTEM && preset != FontPreset.OTHER

    fun initialize(ctx: Context) {
        if (context != null) return
        context = ctx.applicationContext
        val p = ctx.getSharedPreferences("font-settings-v080", Context.MODE_PRIVATE)
        preset = FontPreset.of(p.getString("preset", "default").orEmpty())
        otherFamily = p.getString("other", "serif").orEmpty()
        scale =
            p.getFloat(
                    "scale",
                    ctx.getSharedPreferences("experience-v1", 0).getFloat("textScale", 1f),
                )
                .coerceIn(.85f, 1.35f)
        weight = p.getInt("weight", 400).coerceIn(300, 700)
        italic = p.getBoolean("italic", false)
        ligatures = p.getBoolean("ligatures", true)
        changed()
    }

    fun changed() {
        revision++
        MarkdownDocument.invalidate()
        context
            ?.getSharedPreferences("font-settings-v080", 0)
            ?.edit()
            ?.putString("preset", preset.key)
            ?.putString("other", otherFamily)
            ?.putFloat("scale", scale)
            ?.putInt("weight", weight)
            ?.putBoolean("italic", italic)
            ?.putBoolean("ligatures", ligatures)
            ?.apply()
    }

    fun reset() {
        preset = FontPreset.DEFAULT
        scale = 1f
        weight = 400
        italic = false
        ligatures = true
        ExperienceSettings.lineSpacing = 1.6f
        ExperienceSettings.save()
        changed()
    }
}
