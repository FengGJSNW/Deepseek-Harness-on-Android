package dev.deepseek.mobile

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun TypographySettings() {
    var otherExpanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var licenses by remember { mutableStateOf(false) }
    var installed by remember { mutableStateOf(emptyList<InstalledFont>()) }
    var shown by remember { mutableIntStateOf(30) }
    LaunchedEffect(otherExpanded) { if (otherExpanded) installed = SystemFontCatalog.load() }
    Text("字体", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    Text("界面、阅读和代码分别使用合适的字族，中文与表情自动回退。", color = WhaleMuted, fontSize = 12.sp)
    FontPreset.entries
        .filter { it != FontPreset.OTHER }
        .forEach { preset ->
            Surface(
                modifier = Modifier.fillMaxWidth().testTag("font-preset-${preset.key}"),
                shape = ContinuousShape(14.dp),
                color = if (FontSettings.preset == preset) Color(0xFFF3F3F3) else Color.Transparent,
                onClick = {
                    FontSettings.preset = preset
                    FontSettings.changed()
                },
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(preset.title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(preset.detail, fontSize = 11.sp, color = WhaleMuted)
                    }
                    if (FontSettings.preset == preset)
                        Icon(AppIcons.Check, "已选", Modifier.size(17.dp), tint = WhaleInk)
                }
            }
        }
    TextButton(onClick = { otherExpanded = !otherExpanded }) {
        Text(if (otherExpanded) "收起其他字体" else "其他字体…")
    }
    DisclosureBody(otherExpanded) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                query,
                { query = it },
                label = { Text("搜索系统字族") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = UprightSymbols.transformation,
            )
            DshaFonts.systemFamilies
                .filter { it.contains(query, true) }
                .forEach { family ->
                    TextButton(
                        onClick = {
                            FontSettings.preset = FontPreset.OTHER
                            FontSettings.otherFamily = family
                            FontSettings.changed()
                        },
                    ) {
                        Text(
                            family,
                            fontFamily =
                                androidx.compose.ui.text.font.FontFamily(
                                    android.graphics.Typeface.create(
                                        family,
                                        android.graphics.Typeface.NORMAL,
                                    ),
                                ),
                        )
                        if (
                            FontSettings.preset == FontPreset.OTHER &&
                                FontSettings.otherFamily == family
                        )
                            Icon(AppIcons.Check, null, Modifier.padding(start = 10.dp).size(16.dp))
                    }
                }
            installed
                .filter { it.title.contains(query, true) }
                .take(shown)
                .forEach { face ->
                    TextButton(
                        onClick = {
                            FontSettings.preset = FontPreset.OTHER
                            FontSettings.otherFamily = face.id
                            FontSettings.changed()
                        },
                    ) {
                        Text(face.title, fontSize = 12.sp, maxLines = 1)
                        if (
                            FontSettings.preset == FontPreset.OTHER &&
                                FontSettings.otherFamily == face.id
                        )
                            Icon(AppIcons.Check, null, Modifier.padding(start = 8.dp).size(16.dp))
                    }
                }
            if (installed.count { it.title.contains(query, true) } > shown)
                TextButton(onClick = { shown += 30 }) { Text("显示更多") }
            Text("可用字形取决于系统；未公开的字体特性不提供模拟开关。", fontSize = 11.sp, color = WhaleMuted)
        }
    }
    HorizontalDivider(color = WhaleLine)
    Text("全局大小 · ${"%.0f".format(FontSettings.scale * 100)}%", fontSize = 13.sp)
    CleanSlider(
        FontSettings.scale,
        { FontSettings.scale = it },
        modifier = Modifier.testTag("typography-font-slider"),
        valueRange = .85f..1.35f,
        onValueChangeFinished = { FontSettings.changed() },
    )
    Text("阅读行距 · ${"%.1f".format(ExperienceSettings.lineSpacing)} 倍", fontSize = 13.sp)
    CleanSlider(
        ExperienceSettings.lineSpacing,
        { ExperienceSettings.lineSpacing = it },
        modifier = Modifier.testTag("typography-spacing-slider"),
        valueRange = 1.3f..2f,
        onValueChangeFinished = { ExperienceSettings.save() },
    )
    if (FontSettings.knownFeatures) {
        Text("字重 · ${FontSettings.weight}", fontSize = 13.sp)
        CleanSlider(
            FontSettings.weight.toFloat(),
            { FontSettings.weight = ((it / 100).toInt() * 100).coerceIn(300, 700) },
            valueRange = 300f..700f,
            steps = 3,
            onValueChangeFinished = { FontSettings.changed() },
        )
        ToggleSetting("斜体", "使用字库提供的真实斜体；中文保留原字形", FontSettings.italic) {
            FontSettings.italic = it
            FontSettings.changed()
        }
        ToggleSetting("连字", "启用字体原有的 liga / calt（代码如 !=、=>）", FontSettings.ligatures) {
            FontSettings.ligatures = it
            FontSettings.changed()
        }
    }
    Text("实时预览", fontSize = 11.sp, color = WhaleMuted)
    Surface(
        Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 300.dp).testTag("typography-preview"),
        color = Color(0xFFFAF9F6),
        shape = ContinuousShape(16.dp),
    ) {
        Box(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            MarkdownText(
                "### 让想法慢慢成形\n\n清晰的 **中文阅读**，搭配 *a little italic*。界面、正文与代码拥有各自的节奏。\n\n> 认真阅读，也轻松创造。\n\n```kotlin\nval answer = if (a != b) 42 else 0\nval result = { x: Int -> x * 2 }\n```\n\n- [x] 中文与 Emoji 字形回退",
            )
        }
    }
    if (DshaFonts.error.isNotBlank())
        Text(DshaFonts.error, color = Color(0xFFAF503E), fontSize = 12.sp)
    Row {
        TextButton(onClick = { FontSettings.reset() }) { Text("恢复默认") }
        TextButton(onClick = { licenses = true }) { Text("字体许可") }
    }
    if (licenses) {
        val context = LocalContext.current
        val body by
            produceState("正在读取许可…") {
                value =
                    withContext(Dispatchers.IO) {
                        listOf(
                                "sourcesans3",
                                "sourceserif4",
                                "jetbrainsmono",
                                "notosanssc",
                                "notoserifsc",
                            )
                            .joinToString("\n\n────────\n\n") { name ->
                                context.assets.open("fonts/$name-OFL.txt").bufferedReader().use {
                                    it.readText()
                                }
                            }
                    }
            }
        AlertDialog(
            onDismissRequest = { licenses = false },
            shape = ContinuousShape(24.dp),
            title = { Text("开源字体 · SIL OFL 1.1") },
            text = {
                Text(
                    body,
                    Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    fontSize = 11.sp,
                )
            },
            confirmButton = { TextButton(onClick = { licenses = false }) { Text("完成") } },
        )
    }
}
