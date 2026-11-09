package dev.deepseek.mobile

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.delay

internal data class PetMotionSettings(
    val movement: Float = 24f,
    val damping: Float = .42f,
    val interval: Int = 600,
    val rate: Float = 1f,
    val pressUnits: Int = 10,
)

internal suspend fun Animatable<Float, AnimationVector1D>.playPetBounce(
    tuning: PetMotionSettings,
    pressure: Float = 1f,
    variation: Int = 0,
) {
    val push = (95 / tuning.rate).roundToInt()
    val hold = tuning.pressUnits * 10
    val rebound = (255 / tuning.rate).roundToInt()
    val damping = tuning.damping.toDouble()
    fun springTail(fraction: Float): Double {
        val time = fraction * 12.0
        val frequency = sqrt(1 - damping * damping)
        if (frequency < .001) return exp(-time) * (1 + time)
        return exp(-damping * time) *
            (cos(frequency * time) + damping / frequency * sin(frequency * time))
    }
    animateTo(pressure, tween(push, easing = FastOutSlowInEasing))
    delay(hold.toLong())
    animateTo(
        0f,
        tween(
            rebound,
            easing =
                Easing { fraction ->
                    ((1 - springTail(fraction)) / (1 - springTail(1f))).toFloat()
                },
        ),
    )
    delay((tuning.interval + variation - push - hold - rebound).coerceAtLeast(0).toLong())
}

@Composable
internal fun WhaleMotionSettings() {
    val settings = ExperienceSettings.petMotion
    val squish = remember { Animatable(0f) }
    val travel = remember { Animatable(0f) }
    var preview by remember { mutableStateOf(true) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    LaunchedEffect(settings, preview) {
        squish.snapTo(0f)
        if (preview && android.animation.ValueAnimator.areAnimatorsEnabled())
            while (true) squish.playPetBounce(settings)
    }
    LaunchedEffect(settings.movement, preview, density) {
        travel.snapTo(0f)
        if (preview && android.animation.ValueAnimator.areAnimatorsEnabled()) {
            val duration = (48 / settings.movement * 1000).roundToInt()
            val distance = with(density) { 24.dp.toPx() }
            while (true) {
                travel.animateTo(distance, tween(duration / 2, easing = LinearEasing))
                travel.animateTo(-distance, tween(duration, easing = LinearEasing))
                travel.animateTo(0f, tween(duration / 2, easing = LinearEasing))
            }
        }
    }
    fun save(value: PetMotionSettings) {
        ExperienceSettings.petMotion = value
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("弹动预览", Modifier.weight(1f), fontSize = 14.sp)
        TextButton(onClick = { preview = !preview }) { Text(if (preview) "暂停" else "播放") }
        TextButton(
            onClick = {
                save(PetMotionSettings())
                ExperienceSettings.save()
            },
        ) {
            Text("恢复默认")
        }
    }
    Box(Modifier.fillMaxWidth().height(94.dp), contentAlignment = Alignment.Center) {
        WhalePet(
            size = 72,
            gesture = Modifier.graphicsLayer { translationX = travel.value },
            showBubble = false,
            patrolCompression = { squish.value },
        )
    }
    MotionSlider("移动速度", "${settings.movement.roundToInt()} dp/s", settings.movement, 8f..64f) {
        save(settings.copy(movement = it.roundToInt().toFloat()))
    }
    MotionSlider("弹动阻尼系数", "%.2f".format(settings.damping), settings.damping, .25f..1f) {
        save(settings.copy(damping = (it * 100).roundToInt() / 100f))
    }
    MotionSlider("弹动间隔", "${settings.interval} ms", settings.interval.toFloat(), 400f..1800f) {
        save(settings.copy(interval = (it / 10).roundToInt() * 10))
    }
    MotionSlider("动画速率", "%.2f×".format(settings.rate), settings.rate, .5f..2f) {
        save(settings.copy(rate = (it * 20).roundToInt() / 20f))
    }
    MotionSlider(
        "模拟用户按下时间",
        "${settings.pressUnits * 10} ms",
        settings.pressUnits.toFloat(),
        0f..30f,
    ) {
        save(settings.copy(pressUnits = it.roundToInt()))
    }
}

@Composable
private fun MotionSlider(
    label: String,
    display: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    change: (Float) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 13.sp)
            Text(display, fontSize = 12.sp, color = WhaleMuted)
        }
        Slider(value, change, valueRange = range, onValueChangeFinished = ExperienceSettings::save)
    }
}
