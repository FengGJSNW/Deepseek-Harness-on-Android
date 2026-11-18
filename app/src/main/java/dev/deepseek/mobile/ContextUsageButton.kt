package dev.deepseek.mobile

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import java.text.NumberFormat

@Composable
internal fun ContextUsageButton(
    usage: ConversationUsage?,
    enabled: Boolean = true,
    glass: Boolean = false,
) {
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) open = false }
    LiquidMenuAnchor(
        expanded = open,
        close = { open = false },
        openOnPress = if (enabled) ({ open = true }) else null,
        width = 320.dp,
        placement = MenuPlacement.Below,
        toggleAnchor = true,
        bare = false,
        sharedGlass = !glass,
        anchor = {
            DshaIconButton(
                label = "上下文与 Token 用量",
                onClick = { open = !open },
                enabled = enabled,
                size = 52.dp,
                glass = glass,
                modifier =
                    Modifier.testTag("context-usage-toggle").semantics {
                        contentDescription = "上下文与 Token 用量"
                    },
            ) {
                Canvas(Modifier.size(22.dp)) {
                    drawArc(
                        WhaleMuted,
                        135f,
                        270f,
                        false,
                        Offset(2.dp.toPx(), 2.dp.toPx()),
                        Size(size.width - 4.dp.toPx(), size.height - 4.dp.toPx()),
                        style = Stroke(1.65.dp.toPx(), cap = StrokeCap.Round),
                    )
                    val angle =
                        Math.toRadians(135.0 + (usage?.contextRatio ?: .28f).coerceIn(0f, 1f) * 270)
                    drawLine(
                        WhaleMuted,
                        center,
                        center +
                            Offset(
                                kotlin.math.cos(angle).toFloat(),
                                kotlin.math.sin(angle).toFloat(),
                            ) * 6.dp.toPx(),
                        1.65.dp.toPx(),
                        StrokeCap.Round,
                    )
                }
            }
        },
    ) {
        Column(
            Modifier.fillMaxWidth().padding(6.dp).testTag("context-usage-card"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("上下文", style = PrismTypography.headline)
            if (usage == null || usage.requests == 0L) {
                Text(
                    if (usage == null) "Chat 模式暂不提供 Token 用量。" else "发送请求后显示接口返回的用量。",
                    fontSize = 13.sp,
                    color = WhaleMuted,
                )
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.fillMaxSize().padding(5.dp)) {
                            drawArc(WhaleTint, 0f, 360f, false, style = Stroke(5.dp.toPx()))
                            usage.contextRatio?.let {
                                drawArc(
                                    WhaleBlue,
                                    -90f,
                                    360f * it.coerceIn(0f, 1f),
                                    false,
                                    style = Stroke(5.dp.toPx(), cap = StrokeCap.Round),
                                )
                            }
                        }
                        Text(
                            percent(usage.contextRatio),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "${count(usage.prompt)} tokens",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            "最近一次输入 · ${usage.model}",
                            fontSize = 11.sp,
                            color = WhaleMuted,
                        )
                        Text(
                            "配置预算 ${count(usage.contextBudget)}",
                            fontSize = 11.sp,
                            color = WhaleMuted,
                        )
                    }
                }
                HorizontalDivider(color = WhaleLine)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("缓存命中率", fontSize = 13.sp)
                    Text(percent(usage.cacheRatio), fontSize = 13.sp, color = WhaleBlue)
                }
                Canvas(Modifier.fillMaxWidth().height(6.dp)) {
                    drawRoundRect(WhaleTint, cornerRadius = CornerRadius(size.height / 2))
                    usage.cacheRatio?.let {
                        drawRoundRect(
                            WhaleBlue,
                            size = Size(size.width * it.coerceIn(0f, 1f), size.height),
                            cornerRadius = CornerRadius(size.height / 2),
                        )
                    }
                }
                Text(
                    if (usage.cached == null) "接口未返回缓存数据"
                    else "命中 ${count(usage.cached)} / 输入 ${count(usage.prompt)}",
                    fontSize = 11.sp,
                    color = WhaleMuted,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    UsageStat("本次输出", count(usage.output))
                    UsageStat("会话累计", count(usage.total))
                    UsageStat("请求次数", count(usage.requests))
                }
                Text(
                    "主 Agent 最近一次完成的请求；预算为当前配置值。累计用量从本版本开始记录，不包含子 Agent。",
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = WhaleMuted,
                )
            }
        }
    }
}

@Composable
private fun UsageStat(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 11.sp, color = WhaleMuted)
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

private fun count(value: Long?): String =
    value?.let { NumberFormat.getIntegerInstance().format(it) } ?: "—"

private fun percent(value: Float?): String = value?.let { "%.1f%%".format(it * 100) } ?: "—"
