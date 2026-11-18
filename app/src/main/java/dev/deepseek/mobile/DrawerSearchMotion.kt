package dev.deepseek.mobile

import androidx.compose.runtime.*
import kotlin.math.max

internal data class SearchMotionFrame(
    val growth: Float = 0f,
    val field: Float = 0f,
    val content: Float = 0f,
    val cancel: Float = 0f,
    val glyph: Float = 1f,
    val lift: Float = 0f,
    val source: Float = 1f,
    val dropOffset: Float = 0f,
    val dropSize: Float = 1f,
    val flankScale: Float = 1f,
    val backdrop: Float = 0f,
) {
    companion object {
        val Open = SearchMotionFrame(1f, 1f, 1f, 1f, 0f, source = 0f, backdrop = 1f)
    }
}

private val searchDroplet = PrismSpring(165f, .8f)
private val easeOut = PrismCubic(0f, 0f, .58f, 1f)
private val easeInOut = PrismCubic(.42f, 0f, .58f, 1f)

internal class DrawerSearchFlight(
    val opens: Boolean,
    private val from: SearchMotionFrame,
    velocity: Float,
    wasSettled: Boolean,
) {
    private val end = if (opens) 1f else 0f
    private val delay = if (opens && wasSettled) .05f else 0f
    private val growth =
        PrismSpring(
            if (opens) 390f else 165f,
            if (opens) .75f else .8f,
            from.growth,
            end,
            velocity,
        )

    fun settled(time: Float) = growth.settled(max(0f, time - delay))

    fun velocity(time: Float) =
        if (time < delay || settled(time)) 0f else growth.velocity(time - delay)

    fun done(time: Float) = time >= (if (opens) .29f else .8f) && settled(time)

    fun at(time: Float): SearchMotionFrame {
        if (done(time)) return if (opens) SearchMotionFrame.Open else SearchMotionFrame()
        fun mix(
            from: Float,
            to: Float,
            start: Float,
            stop: Float,
            curve: PrismCubic? = null,
        ): Float {
            val t = ((time - start) / (stop - start)).coerceIn(0f, 1f)
            return from + (to - from) * (curve?.transform(t) ?: t)
        }
        val grown = if (settled(time)) end else growth.value(max(0f, time - delay))
        if (opens)
            return SearchMotionFrame(
                growth = grown,
                field = mix(from.field, 1f, 0f, .05f),
                content = mix(from.content, 1f, .07f, .22f),
                cancel = mix(from.cancel, 1f, .08f, .2f),
                glyph = mix(from.glyph, 0f, 0f, .03f),
                lift = mix(mix(from.lift, 1f, .02f, .12f, easeOut), 0f, .16f, .29f, easeInOut),
                source = mix(from.source, 0f, .02f, .08f),
                dropOffset = mix(from.dropOffset, 0f, 0f, .05f),
                dropSize = mix(from.dropSize, 1f, 0f, .05f),
                flankScale = mix(from.flankScale, 1f, 0f, .05f),
                backdrop = mix(from.backdrop, 1f, 0f, .16f),
            )
        return SearchMotionFrame(
            growth = grown,
            field = mix(from.field, 0f, .02f, .12f),
            content = mix(from.content, 0f, 0f, .04f),
            cancel = mix(from.cancel, 0f, 0f, .03f),
            glyph = mix(from.glyph, 1f, .3f, .5f),
            lift = mix(from.lift, 0f, 0f, .1f),
            source = mix(from.source, 1f, 0f, .02f),
            dropOffset =
                .25f * (1f - searchDroplet.value(time) - .71f * prismPulse(time, .19f, 5.7f)),
            dropSize = .25f + .75f * searchDroplet.value(max(0f, time - .31f)),
            flankScale = mix(from.flankScale, 0f, 0f, .05f),
            backdrop = mix(from.backdrop, 0f, .08f, .19f),
        )
    }
}

internal class DrawerSearchMotion {
    var frame by mutableStateOf(SearchMotionFrame())
        private set

    val backdrop = derivedStateOf { frame.backdrop }
    val button = derivedStateOf { if (retained) 0f else 1f }
    var retained by mutableStateOf(false)
        private set

    private var velocity = 0f
    private var growthSettled = true

    suspend fun move(open: Boolean) {
        if (!open && !retained) return
        retained = true
        val flight = DrawerSearchFlight(open, frame, velocity, growthSettled)
        runPrismClock { t ->
            frame = flight.at(t)
            velocity = flight.velocity(t)
            growthSettled = flight.settled(t)
            flight.done(t)
        }
        frame = if (open) SearchMotionFrame.Open else SearchMotionFrame()
        velocity = 0f
        growthSettled = true
        if (!open) retained = false
    }
}

@Composable
internal fun rememberDrawerSearchProgress(search: DrawerSearchController): State<Float> {
    LaunchedEffect(search.visible) { search.motion.move(search.visible) }
    return search.motion.backdrop
}
