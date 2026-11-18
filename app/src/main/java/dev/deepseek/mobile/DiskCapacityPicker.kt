package dev.deepseek.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import kotlin.math.roundToInt

internal fun diskCapacityLabel(megabytes: Int): String =
    if (megabytes >= 1024 && megabytes % 1024 == 0) "${megabytes / 1024} GB" else "$megabytes MB"

private val DiskCapacitySteps = (1..16).map { it * 64 } + (2..32).map { it * 1024 }

internal fun diskCapacityAt(position: Float): Int =
    DiskCapacitySteps[position.roundToInt().coerceIn(DiskCapacitySteps.indices)]

private fun diskCapacityPosition(megabytes: Int): Float =
    DiskCapacitySteps.indices.minBy { kotlin.math.abs(DiskCapacitySteps[it] - megabytes) }.toFloat()

@Composable
internal fun DiskCapacityPicker(megabytes: Int, change: (Int) -> Unit) {
    DshaCard {
        Text("容量上限 · ${diskCapacityLabel(megabytes)}", style = PrismTypography.headline)
        DshaSegments(
            listOf(256 to "256M", 512 to "512M", 1024 to "1G", 4096 to "4G"),
            megabytes,
            change,
            optionTag = { "disk-capacity-$it" },
        )
        Text("自定义容量", style = PrismTypography.footnote, color = WhaleMuted)
        CleanSlider(
            diskCapacityPosition(megabytes),
            { change(diskCapacityAt(it)) },
            modifier = Modifier.testTag("disk-capacity-slider"),
            valueRange = 0f..DiskCapacitySteps.lastIndex.toFloat(),
            steps = DiskCapacitySteps.size - 2,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("64M", style = PrismTypography.caption, color = WhaleMuted)
            Text("32G", style = PrismTypography.caption, color = WhaleMuted)
        }
        Text("按实际写入的数据占用手机空间，创建时不会预留整块容量。", style = PrismTypography.footnote, color = WhaleMuted)
    }
}
