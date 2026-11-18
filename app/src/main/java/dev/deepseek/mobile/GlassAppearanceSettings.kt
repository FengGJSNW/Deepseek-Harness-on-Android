package dev.deepseek.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
internal fun GlassAppearanceSettings() {
    val settings = ExperienceSettings
    SettingsGroup {
        ToggleSetting("液态玻璃（实验性）", "默认使用高斯毛玻璃；开启后启用实验性折射效果", settings.liquidGlassEnabled) {
            settings.liquidGlassEnabled = it
            settings.save()
        }
    }
    DshaCard {
        Text(
            "透明度 · ${(settings.glassTransparency * 100).roundToInt()}%",
            style = PrismTypography.headline,
        )
        Text("透明度越低，白色混合越多", style = PrismTypography.footnote, color = WhaleMuted)
        CleanSlider(
            settings.glassTransparency,
            { settings.glassTransparency = it },
            Modifier.fillMaxWidth(),
            valueRange = 0f..1f,
            steps = 19,
            onValueChangeFinished = settings::save,
        )
    }
    DshaCard {
        Text(
            "背景模糊 · ${if (settings.liquidGlassBlur < .01f) "关闭" else "${settings.liquidGlassBlur.roundToInt()}"}",
            style = PrismTypography.headline,
        )
        Text("单独调整液态玻璃的背景模糊，默认关闭。", style = PrismTypography.footnote, color = WhaleMuted)
        CleanSlider(
            settings.liquidGlassBlur,
            { settings.liquidGlassBlur = it },
            valueRange = 0f..24f,
            steps = 23,
            onValueChangeFinished = settings::save,
            enabled = settings.liquidGlassEnabled,
        )
    }
    val owner = LocalView.current
    val layer = rememberGraphicsLayer()
    val backdrop = remember(layer, owner) { ChatBackdrop(layer, owner) }
    Box(
        Modifier.fillMaxWidth().height(150.dp).clip(ContinuousShape(24.dp)).onPlaced {
            backdrop.viewport = it
        },
    ) {
        Row(Modifier.fillMaxSize().chatBackdrop(backdrop)) {
            listOf(Color(0xFFD8E7FF), Color(0xFFB6CFFF), Color(0xFFFFDECB)).forEach {
                Box(Modifier.weight(1f).fillMaxHeight().background(it))
            }
        }
        Box(
            Modifier.align(Alignment.Center).size(210.dp, 78.dp).clip(ContinuousShape(24.dp)),
            contentAlignment = Alignment.Center,
        ) {
            LiquidGlass(Modifier.matchParentSize(), source = backdrop, finish = GlassFinish.Menu)
            Text("材质预览", style = PrismTypography.body, color = WhaleInk)
        }
    }
}
