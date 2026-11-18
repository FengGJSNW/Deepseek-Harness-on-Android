package dev.deepseek.mobile

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import dev.deepseek.mobile.proxy.MihomoState
import java.util.Locale

internal data class ProxyTrafficSample(val timeMs: Long, val upload: Long, val download: Long)

internal data class ProxyTrafficRate(val upload: Float, val download: Float)

internal fun proxyTrafficRate(
    previous: ProxyTrafficSample?,
    next: ProxyTrafficSample,
): ProxyTrafficRate? {
    if (
        previous == null ||
            next.timeMs <= previous.timeMs ||
            next.upload < previous.upload ||
            next.download < previous.download
    )
        return null
    val seconds = (next.timeMs - previous.timeMs) / 1000f
    return ProxyTrafficRate(
        (next.upload - previous.upload) / seconds,
        (next.download - previous.download) / seconds,
    )
}

internal fun proxyTrafficText(bytes: Float): String =
    when {
        bytes >= 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MiB", bytes / (1024 * 1024))
        bytes >= 1024 -> String.format(Locale.ROOT, "%.1f KiB", bytes / 1024)
        else -> "${bytes.coerceAtLeast(0f).toInt()} B"
    }

@Composable
internal fun MihomoDashboard(
    state: MihomoState,
    profile: String?,
    busy: Boolean,
    mode: String,
    connections: Int?,
    sample: ProxyTrafficSample?,
    history: List<ProxyTrafficRate>,
    onPower: () -> Unit,
    onMode: (String) -> Unit,
    onProfiles: () -> Unit,
    onConnections: () -> Unit,
) {
    DshaCard(Modifier.testTag("proxy-status-card")) {
        DshaCardHeader(AppIcons.Globe, "Mihomo") {
            ProxyStatusPill(if (busy) "处理中" else if (state.running) "运行中" else "已停止", state.running)
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    profile ?: "尚未添加配置",
                    fontSize = 21.sp,
                    lineHeight = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = WhaleInk,
                )
                Text(
                    if (state.running) "代理内核已连接" else "为网页与终端提供代理",
                    Modifier.padding(top = 5.dp),
                    fontSize = 12.sp,
                    color = DshaSurface.muted,
                )
            }
            DshaButton(
                onPower,
                enabled = profile != null && !busy,
                modifier = Modifier.testTag("proxy-power"),
            ) {
                Icon(
                    if (state.running) AppIcons.Stop else AppIcons.Play,
                    null,
                    Modifier.size(19.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(if (busy) "请稍候" else if (state.running) "停止" else "启动")
            }
        }
        DshaButton(onProfiles, style = DshaButtonStyle.Tonal, modifier = Modifier.fillMaxWidth()) {
            Icon(AppIcons.Description, null, Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (profile == null) "导入订阅或 YAML" else "管理配置")
        }
    }
    DshaCard {
        DshaCardHeader(AppIcons.Bolt, "网络流量") {
            Text(if (state.running) "实时" else "未运行", fontSize = 11.sp, color = DshaSurface.muted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ProxyTrafficTile(
                "上传",
                history.lastOrNull()?.upload,
                sample?.upload,
                history.map { it.upload },
                WhaleBlue.copy(alpha = .6f),
                Modifier.weight(1f),
            )
            ProxyTrafficTile(
                "下载",
                history.lastOrNull()?.download,
                sample?.download,
                history.map { it.download },
                WhaleBlue,
                Modifier.weight(1f),
            )
        }
        DshaButton(
            onConnections,
            style = DshaButtonStyle.Tonal,
            enabled = state.running,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (connections == null) "查看连接" else "$connections 个活动连接")
            Spacer(Modifier.weight(1f))
            Icon(AppIcons.ChevronRight, null, Modifier.size(16.dp))
        }
    }
    DshaCard {
        DshaCardHeader(AppIcons.Settings, "出站模式")
        DshaSegments(
            listOf("rule" to "规则", "global" to "全局", "direct" to "直连"),
            mode,
            onMode,
            enabled = state.running && !busy,
        )
    }
}

@Composable
internal fun ProxyStatusPill(text: String, active: Boolean) {
    val ink = if (active) DshaSurface.success else DshaSurface.muted
    Row(
        Modifier.clip(ContinuousShape(30.dp))
            .background(ink.copy(alpha = .10f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(5.dp)) { drawCircle(ink) }
        Text(
            text,
            Modifier.padding(start = 5.dp),
            color = ink,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun ProxyTrafficTile(
    title: String,
    rate: Float?,
    total: Long?,
    values: List<Float>,
    tint: Color,
    modifier: Modifier,
) {
    Column(modifier.clip(ContinuousShape(20.dp)).background(DshaSurface.inset)) {
        Column(Modifier.padding(14.dp)) {
            Text(title, fontSize = 12.sp, color = DshaSurface.muted)
            Text(
                rate?.let { proxyTrafficText(it) + "/s" } ?: "—",
                Modifier.padding(top = 8.dp),
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
                color = WhaleInk,
                maxLines = 1,
            )
            Text(
                total?.let { "累计 " + proxyTrafficText(it.toFloat()) } ?: "等待数据",
                Modifier.padding(top = 4.dp),
                fontSize = 10.sp,
                color = DshaSurface.muted,
            )
        }
        Canvas(Modifier.fillMaxWidth().height(38.dp)) {
            if (values.size < 2) return@Canvas
            val max = values.maxOrNull()?.coerceAtLeast(1f) ?: 1f
            val path = Path()
            values.forEachIndexed { i, value ->
                val x = size.width * i / (values.size - 1)
                val y = size.height - 6.dp.toPx() - (size.height - 10.dp.toPx()) * value / max
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path,
                tint,
                style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
            path.lineTo(size.width, size.height)
            path.lineTo(0f, size.height)
            path.close()
            drawPath(path, tint.copy(alpha = .09f))
        }
    }
}
