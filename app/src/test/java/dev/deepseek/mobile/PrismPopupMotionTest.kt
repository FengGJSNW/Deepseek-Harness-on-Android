package dev.deepseek.mobile

import androidx.compose.ui.geometry.Rect
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class PrismPopupMotionTest {
    private fun rect(values: JSONArray) =
        Rect(
            values.getDouble(0).toFloat(),
            values.getDouble(1).toFloat(),
            values.getDouble(2).toFloat(),
            values.getDouble(3).toFloat(),
        )

    private fun matches(expected: JSONArray, actual: Rect) {
        val values = listOf(actual.left, actual.top, actual.right, actual.bottom)
        for (i in values.indices) assertEquals(
            "coordinate $i",
            expected.getDouble(i),
            values[i].toDouble(),
            .003,
        )
    }

    @Test
    fun openingAndReturnMatchThePinnedDartFrames() {
        val source =
            requireNotNull(javaClass.classLoader?.getResourceAsStream("prism-popup-reference.json"))
        val rows = JSONArray(source.bufferedReader().use { it.readText() })
        val card = Rect(0f, 0f, 232f, 250f)
        var checked = 0
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            checked++
            val anchor = rect(row.getJSONArray("anchor"))
            val time = row.getDouble("time").toFloat()
            val frame =
                if (row.getString("kind") == "open") {
                    PrismPopupMotion.opening(time, anchor, card, false)
                } else {
                    val from =
                        if (row.isNull("start")) PrismPopupMotion.OPEN_SECONDS
                        else row.getDouble("start").toFloat()
                    val start = PrismPopupMotion.opening(from, anchor, card, false)
                    PrismPopupMotion.closing(time, anchor, start, false)
                }
            matches(row.getJSONArray("platter"), frame.platter)
            matches(row.getJSONArray("droplet"), requireNotNull(frame.droplet))
            assertEquals("frame $i radius", row.getDouble("radius"), frame.radius.toDouble(), .003)
            if (row.has("blend"))
                assertEquals("frame $i blend", row.getDouble("blend"), frame.blend.toDouble(), .003)
        }
        assertEquals(90, checked)
    }

    @Test
    fun shrinkingRowWidthDoesNotThrowTheLeftEdgeSideways() {
        val row = Rect(16f, 390f, 380f, 454f)
        val card = Rect(48f, 368f, 282f, 536f)
        for (step in 0..100) {
            val t = step / 100f
            val frame = PrismPopupMotion.row(t, row, card)
            assertEquals(16f + 32f * t, frame.platter.left, .001f)
            assertEquals(390f - 22f * t, frame.platter.top, .001f)
        }
        assertEquals(row, PrismPopupMotion.row(0f, row, card).platter)
        assertEquals(card, PrismPopupMotion.row(1f, row, card).platter)
    }

    @Test
    fun searchChannelsMatchFlClashWhenOpenedClosedOrInterrupted() {
        val opening = DrawerSearchFlight(true, SearchMotionFrame(), 0f, true)
        val closing = DrawerSearchFlight(false, SearchMotionFrame.Open, 0f, true)
        fun turn(previous: DrawerSearchFlight, at: Float) =
            DrawerSearchFlight(
                !previous.opens,
                previous.at(at),
                previous.velocity(at),
                previous.settled(at),
            )
        val motions =
            mapOf(
                "open" to opening,
                "close" to closing,
                "interruptOpen" to turn(opening, .12f),
                "interruptClose" to turn(closing, .16f),
            )
        val source =
            requireNotNull(
                javaClass.classLoader?.getResourceAsStream("prism-search-reference.json"),
            )
        val rows = JSONArray(source.bufferedReader().use { it.readText() })
        assertEquals(52, rows.length())
        for (i in 0 until rows.length()) {
            val row = rows.getJSONArray(i)
            val frame = motions.getValue(row.getString(0)).at(row.getInt(1) / 1000f)
            val channels =
                listOf(
                    frame.growth,
                    frame.field,
                    frame.content,
                    frame.cancel,
                    frame.glyph,
                    frame.lift,
                    frame.source,
                    frame.dropOffset,
                    frame.dropSize,
                    frame.flankScale,
                )
            channels.forEachIndexed { channel, value ->
                assertEquals(
                    "search frame $i channel $channel",
                    row.getDouble(channel + 2),
                    value.toDouble(),
                    .001,
                )
            }
        }
    }

    @Test
    fun diskCapacityPresetsAndThirtyTwoGiBPersistWithoutOverflow() {
        assertEquals(
            (1..16).map { it * 64 } + (2..32).map { it * 1024 },
            (0..46).map { diskCapacityAt(it.toFloat()) },
        )
        assertEquals(64, diskCapacityAt(-1f))
        assertEquals(32768, diskCapacityAt(47f))
        val disk =
            dev.deepseek.mobile.runtime.SandboxDisk(
                java.util.UUID.randomUUID().toString(),
                "32G",
                32L * 1024 * 1024 * 1024,
            )
        assertEquals(disk, dev.deepseek.mobile.runtime.SandboxDisk.from(disk.json()))
        assertTrue(
            runCatching {
                dev.deepseek.mobile.runtime.SandboxDisk.from(
                    disk.json().put("capacity", disk.capacity + 1),
                )
            }
                .isFailure,
        )
    }
}
