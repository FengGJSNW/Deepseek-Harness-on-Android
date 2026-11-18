package dev.deepseek.mobile

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.*

/** Direct geometry port of prism_ui/goo.dart: adaptive rays, smooth union and chord reduction. */
internal object PrismGoo {
    fun roundedDistance(rect: Rect, radius: Float, point: Offset): Float {
        val round = min(radius, min(rect.width, rect.height) / 2f)
        val qx = abs(point.x - rect.center.x) - rect.width / 2f + round
        val qy = abs(point.y - rect.center.y) - rect.height / 2f + round
        return hypot(max(qx, 0f), max(qy, 0f)) + min(max(qx, qy), 0f) - round
    }

    fun depth(a: Rect, radius: Float, b: Rect, blend: Float): Float =
        -listOf(b.topLeft, b.topRight, b.bottomLeft, b.bottomRight).maxOf {
            roundedDistance(a, radius, it)
        } - blend

    private fun rounded(rect: Rect, radius: Float): Path {
        val outline =
            ContinuousShape(radius.dp).createOutline(rect.size, LayoutDirection.Ltr, Density(1f))
        return Path().apply {
            when (outline) {
                is Outline.Generic -> addPath(outline.path, rect.topLeft)
                is Outline.Rounded -> {
                    addRoundRect(outline.roundRect)
                    translate(rect.topLeft)
                }
                is Outline.Rectangle -> addRect(rect)
            }
        }
    }

    fun outline(a: Rect, radius: Float, b: Rect?, blend: Float): Path {
        val platter = rounded(a, radius)
        if (b == null || b.isEmpty || depth(a, radius, b, blend) >= 0f) return platter
        val pillRadius = min(b.width, b.height) / 2f
        val pill = rounded(b, pillRadius)
        if (depth(b, pillRadius, a, blend) >= 0f) return pill
        val bridge = bridge(a, radius, b, blend)
        val union = Path.combine(PathOperation.Union, platter, pill)
        return if (bridge == null) union else Path.combine(PathOperation.Union, union, bridge)
    }

    private fun smoothMin(a: Float, b: Float, blend: Float): Float {
        val h = (.5f + .5f * (b - a) / blend).coerceIn(0f, 1f)
        return b + (a - b) * h - blend * h * (1f - h)
    }

    private fun bridge(a: Rect, radius: Float, b: Rect, blend: Float): Path? {
        if (blend <= 0f || b.isEmpty) return null
        val turns = 128
        val tolerance = .05f
        fun toA(point: Offset) = roundedDistance(a, radius, point)
        fun field(point: Offset) =
            smoothMin(toA(point), roundedDistance(b, min(b.width, b.height) / 2f, point), blend)
        val center = b.center
        val limit = max(b.width, b.height) / 2f + max(a.width, a.height) + blend
        fun reach(direction: Offset): Offset {
            var inside = 0f
            var distance = 0f
            while (distance < limit) {
                val point = center + direction * distance
                val platter = toA(point)
                if (platter < -blend) return point
                val depth = field(point)
                if (depth >= 0f) {
                    var outside = distance
                    repeat(4) {
                        val middle = (inside + outside) / 2f
                        if (field(center + direction * middle) < 0f) inside = middle
                        else outside = middle
                    }
                    break
                }
                inside = distance
                distance += max(.5f, min(-depth, platter + blend))
            }
            return center + direction * inside
        }
        val ends = arrayOfNulls<Offset>(turns)
        val depths = FloatArray(turns)
        fun reachOf(turn: Int) =
            when {
                depths[turn % turns] < -blend -> 0
                depths[turn % turns] < blend -> 1
                else -> 2
            }
        fun cast(turn: Int) {
            val angle = turn.toFloat() / turns * 2f * PI.toFloat()
            val end = reach(Offset(cos(angle), sin(angle)))
            ends[turn] = end
            depths[turn] = toA(end)
        }
        fun skip(from: Int, to: Int) {
            for (turn in from + 1 until to) if (ends[turn] == null) depths[turn] = depths[from]
        }
        fun refine(from: Int, to: Int) {
            if (to - from < 2) return
            val alike = reachOf(from) == reachOf(to)
            if (alike && reachOf(from) != 1) {
                skip(from, to)
                return
            }
            val middle = (from + to) / 2
            cast(middle)
            if (
                alike &&
                    reachOf(middle) == reachOf(from) &&
                    offChord(ends[from]!!, ends[to % turns]!!, ends[middle]!!) <= tolerance
            ) {
                skip(from, to)
                return
            }
            refine(from, middle)
            refine(middle, to)
        }
        for (turn in 0 until turns step 4) cast(turn)
        for (turn in 0 until turns step 4) refine(turn, turn + 4)
        val flows = depths.map { it < blend }
        if (flows.none { it }) return null
        val first = flows.indexOf(false).coerceAtLeast(0)
        val path = Path()
        val fan = mutableListOf<Int>()
        fun close() {
            if (fan.size > 1) {
                val rim = fan.mapIndexedNotNull { index, turn ->
                    ends[turn]?.takeIf {
                        index == 0 ||
                            index == fan.lastIndex ||
                            depths[fan[index - 1]] >= -blend ||
                            depths[turn] >= -blend ||
                            depths[fan[index + 1]] >= -blend
                    }
                }
                path.moveTo(center.x, center.y)
                reduce(rim, tolerance).forEach { path.lineTo(it.x, it.y) }
                path.close()
            }
            fan.clear()
        }
        for (index in 0..turns) {
            val turn = (first + index) % turns
            if (flows[turn]) fan.add(turn) else close()
        }
        close()
        return path
    }

    private fun offChord(from: Offset, to: Offset, point: Offset): Float {
        val chord = to - from
        val length = chord.getDistance()
        val offset = point - from
        return if (length == 0f) offset.getDistance()
        else abs(offset.x * chord.y - offset.y * chord.x) / length
    }

    private fun reduce(points: List<Offset>, tolerance: Float): List<Offset> {
        if (points.size < 3) return points
        val kept = BooleanArray(points.size)
        kept[0] = true
        kept[points.lastIndex] = true
        fun split(from: Int, to: Int) {
            var farthest = 0f
            var at = -1
            for (index in from + 1 until to) {
                val off = offChord(points[from], points[to], points[index])
                if (off > farthest) {
                    farthest = off
                    at = index
                }
            }
            if (at >= 0 && farthest > tolerance) {
                kept[at] = true
                split(from, at)
                split(at, to)
            }
        }
        split(0, points.lastIndex)
        return points.filterIndexed { index, _ -> kept[index] }
    }
}
