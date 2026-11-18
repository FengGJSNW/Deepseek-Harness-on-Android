package dev.deepseek.mobile

import androidx.compose.animation.core.Easing
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.*

/**
 * Port of FlClash/prism_ui popup.dart (_openingOf / _paintReturn) and motion.dart. Geometry is in
 * dp; time is seconds. Keep these equations in sync with the pinned source. Source and license:
 * integrations/flclash.lock.json, assets/licenses/FlClash-GPL-3.0.txt.
 */
internal data class PrismPopupFrame(
    val platter: Rect,
    val radius: Float,
    val droplet: Rect?,
    val blend: Float,
    val content: Float,
    val contentBlur: Float,
    val glyph: Float,
    val glyphBlur: Float,
    val glass: Float,
    val shadow: Float,
)

internal class PrismSpring(
    stiffness: Float,
    ratio: Float,
    private val from: Float = 0f,
    private val to: Float = 1f,
    velocity: Float = 0f,
) {
    private val omega = sqrt(stiffness)
    private val decay = ratio * omega
    private val frequency = omega * sqrt(1f - ratio * ratio)
    private val a = from - to
    private val speed = velocity + decay * a
    private val b = if (frequency == 0f) 0f else speed / frequency

    fun value(time: Float): Float {
        val t = time.coerceAtLeast(0f)
        if (frequency == 0f) return to + (a + speed * t) * exp(-decay * t)
        return to + exp(-decay * t) * (a * cos(frequency * t) + b * sin(frequency * t))
    }

    fun velocity(time: Float): Float {
        val t = time.coerceAtLeast(0f)
        if (frequency == 0f) return (speed - decay * (a + speed * t)) * exp(-decay * t)
        val wave = a * cos(frequency * t) + b * sin(frequency * t)
        return exp(-decay * t) *
            (-decay * wave + frequency * (-a * sin(frequency * t) + b * cos(frequency * t)))
    }

    fun settled(t: Float, velocityTolerance: Float = .001f) =
        abs(value(t) - to) < .001f && abs(velocity(t)) < velocityTolerance
}

/** Flutter Cubic's bisection tolerance is part of the original channel timing. */
internal class PrismCubic(
    private val a: Float,
    private val b: Float,
    private val c: Float,
    private val d: Float,
) : Easing {
    override fun transform(fraction: Float): Float {
        if (fraction <= 0f || fraction >= 1f) return fraction.coerceIn(0f, 1f)
        fun cubic(a: Double, b: Double, t: Double) =
            3 * a * (1 - t) * (1 - t) * t + 3 * b * (1 - t) * t * t + t * t * t
        var start = 0.0
        var end = 1.0
        repeat(32) {
            val middle = (start + end) / 2
            val estimate = cubic(a.toDouble(), c.toDouble(), middle)
            if (abs(fraction - estimate) < .001)
                return cubic(b.toDouble(), d.toDouble(), middle).toFloat()
            if (estimate < fraction) start = middle else end = middle
        }
        return cubic(b.toDouble(), d.toDouble(), (start + end) / 2).toFloat()
    }
}

internal fun prismPulse(time: Float, peak: Float, sharpness: Float): Float =
    if (time <= 0f) 0f else (time / peak).pow(sharpness) * exp(sharpness * (1f - time / peak))

internal object PrismPopupMotion {
    const val OPEN_SECONDS = .45f
    const val RETURN_SECONDS = .5f
    private val returnSpring = PrismSpring(165f, .8f)
    private val easeIn = PrismCubic(.42f, 0f, 1f, 1f)
    private val easeOut = PrismCubic(0f, 0f, .58f, 1f)
    private val easeInOut = PrismCubic(.42f, 0f, .58f, 1f)
    private val glyphCurve = PrismCubic(.25f, .1f, .25f, 1f)
    private val open = PrismSpring(400f, .754f)
    private val corners = PrismSpring(324f, .75f, 1f, 0f)

    private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun mix(a: Offset, b: Offset, t: Float) = a + (b - a) * t

    private fun rect(center: Offset, width: Float, height: Float) =
        Rect(
            center.x - width / 2f,
            center.y - height / 2f,
            center.x + width / 2f,
            center.y + height / 2f,
        )

    fun opening(time: Float, anchor: Rect, card: Rect, bare: Boolean): PrismPopupFrame {
        if (time >= OPEN_SECONDS)
            return PrismPopupFrame(card, 24f, null, 0f, 1f, 0f, 0f, 0f, 1f, 1f)
        val progress = open.value(time)
        val scale = mix(.41f * min(anchor.width, anchor.height) / card.width, 1f, progress)
        val height = mix(card.width, card.height, progress)
        val toward = card.center.y - anchor.center.y
        val distance = abs(toward)
        val dip =
            sign(toward) *
                (min(1f, distance / 30f) * 23f + .124f * distance) *
                prismPulse(time, .098f, 3.3f)
        val beyond = (1f - progress).coerceIn(0f, 1f)
        val platter =
            rect(
                mix(anchor.center, card.center, progress) + Offset(0f, dip),
                max(0f, card.width * scale + max(0f, anchor.width - anchor.height) * beyond),
                max(0f, height * scale + max(0f, anchor.height - anchor.width) * beyond),
            )
        val round = 24f + card.width * .48f * corners.value(max(0f, time - .07f))
        val extent = mix(1f, .25f, progress)
        val inner = card.deflate(max(anchor.width, anchor.height) * .25f / 2f + 28f + 8f)
        val sunk = mix(anchor.center, card.center, .25f)
        val target =
            if (inner.isEmpty) card.center
            else
                Offset(
                    sunk.x.coerceIn(inner.left, inner.right),
                    sunk.y.coerceIn(inner.top, inner.bottom),
                )
        val droplet =
            rect(
                mix(anchor.center, target, progress),
                anchor.width * extent,
                anchor.height * extent,
            )
        val settled = progress.coerceIn(0f, 1f)
        val under = min(1f, settled / .25f)
        return PrismPopupFrame(
            platter,
            max(0f, min(height / 2f, round) * scale),
            droplet,
            28f,
            settled,
            sqrt((4f * (1f - settled)).pow(2) + (1.9f * prismPulse(time, .069f, 2.1f)).pow(2)),
            1f - under,
            4f * under,
            if (bare) min(1f, time / .1f) else 1f,
            settled,
        )
    }

    fun row(grow: Float, anchor: Rect, card: Rect): PrismPopupFrame {
        val t = grow.coerceIn(0f, 1f)
        val width = mix(anchor.width, card.width, (t / .3f).coerceAtMost(1f))
        val left = mix(anchor.left, card.left, grow)
        val plate =
            Rect(
                left,
                mix(anchor.top, card.top, grow),
                left + width,
                mix(anchor.bottom, card.bottom, grow),
            )
        val items = ((t - .05f) / .35f).coerceIn(0f, 1f)
        val header =
            Rect(
                mix(anchor.left, card.left + 6f, grow),
                mix(anchor.top, card.top + 6f, grow),
                mix(anchor.right, card.right - 6f, grow),
                mix(anchor.bottom, card.top + 54f, grow),
            )
        return PrismPopupFrame(
            plate,
            16f + 8f * t,
            header,
            0f,
            items,
            0f,
            1f - items,
            0f,
            (t / .35f).coerceAtMost(1f),
            t,
        )
    }

    fun closing(
        time: Float,
        anchor: Rect,
        start: PrismPopupFrame,
        bare: Boolean,
    ): PrismPopupFrame {
        val from = start.platter
        val fromDrop =
            start.droplet
                ?: rect(
                    mix(anchor.center, from.center, .25f),
                    anchor.width * .25f,
                    anchor.height * .25f,
                )
        val ringing = 1f - easeIn.transform(((time - .4f) / .1f).coerceIn(0f, 1f))
        val p = 1f + (returnSpring.value(time) - 1f) * ringing
        val dy = from.center.y - anchor.center.y
        val dip = min(.21f * abs(dy), 22f) * prismPulse(time, .072f, 2.4f)
        val lift = min(.25f * abs(dy), 19f) * prismPulse(time, .176f, 4.9f)
        val scale =
            mix(
                1f,
                .41f * min(anchor.width, anchor.height) /
                    max(from.width, from.height).coerceAtLeast(.001f),
                p,
            )
        val beyond = p.coerceIn(0f, 1f)
        val platter =
            rect(
                mix(from.center, anchor.center, p) + Offset(0f, sign(dy) * dip * ringing),
                max(0f, from.width * scale + max(0f, anchor.width - anchor.height) * beyond),
                max(
                    0f,
                    mix(from.height, from.width, p) * scale +
                        max(0f, anchor.height - anchor.width) * beyond,
                ),
            )
        val droplet =
            rect(
                mix(fromDrop.center, anchor.center, p) - Offset(0f, sign(dy) * lift * ringing),
                mix(fromDrop.width, anchor.width, p),
                mix(fromDrop.height, anchor.height, p),
            )
        val absorbed = easeInOut.transform(((time / RETURN_SECONDS - .6f) / .3f).coerceIn(0f, 1f))
        val content = start.content * (1f - easeOut.transform((time / .1f).coerceIn(0f, 1f)))
        return PrismPopupFrame(
            platter,
            max(0f, mix(start.radius, from.width / 2, p) * scale),
            droplet,
            28f * min(1f, time / .05f) * (1f - absorbed),
            content,
            8f * (1f - content),
            mix(.2f, 1f, glyphCurve.transform((time / .47f).coerceIn(0f, 1f))),
            0f,
            if (bare) min(start.glass, 1f - absorbed) else 1f,
            (1f - p).coerceIn(0f, 1f),
        )
    }
}
