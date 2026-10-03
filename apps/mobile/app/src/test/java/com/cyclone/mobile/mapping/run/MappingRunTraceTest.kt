package com.cyclone.mobile.mapping.run

import com.cyclone.mobile.ai.AiTraceEvent
import com.cyclone.mobile.ai.AiTraceSession
import com.cyclone.mobile.ai.RunInsight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MappingRunTraceTest {
    private val gmail = "package:com.google.android.gm"
    private val list = "screen:list:aaaaaaaaaaaaaaaa"
    private val settings = "screen:settings:bbbbbbbbbbbbbbbb"

    private fun trace(vararg events: MappingDriverEvent): List<AiTraceEvent> {
        var at = 1_000L
        val out = mutableListOf(AiTraceEvent("e0", "map-1", at, "START", "Mapping Gmail on its own", "task.start", true, null))
        events.forEach { event ->
            MappingRunTrace.lines(event, gmail, "2026.09.14").forEach { line ->
                at += 100
                out += AiTraceEvent("e$at", "map-1", at, line.kind, line.text, line.code, line.ok, line.detail)
            }
        }
        return out
    }

    private fun session(status: String) = AiTraceSession("map-1", "Map Gmail", MappingRunTrace.MODEL, status, 1_000L, 9_000L, "", 3)

    @Test
    fun doorsBecomeStepsWithRoomsAndTheAppVersion() {
        val events = trace(
            MappingDriverEvent(1, "navigate", "enter:ok"),
            MappingDriverEvent(2, "progress", "list:list", list),
            MappingDriverEvent(3, "progress", "settings:settings", settings),
            MappingDriverEvent(4, "room_exhausted", "settings", settings),
            MappingDriverEvent(5, "complete", "entry_room_exhausted:mapped"),
        )
        val steps = RunInsight.steps(events)
        assertEquals(4, steps.size)
        assertEquals(list, steps[2].roomAfter)
        assertEquals(gmail, steps[2].placeId)
        assertEquals("2026.09.14", steps[2].appVersion)
        assertNull("the mapper is neither a known route nor the model", steps[2].decisionSource)
        assertNull(RunInsight.causeOfDeath(session("COMPLETED"), events))
        val detail = RunInsight.detailJson(session("COMPLETED"), events)
        assertEquals(0, detail.getInt("mapSteps"))
        assertEquals(0, detail.getInt("modelSteps"))
        assertEquals(listOf(list, settings), detail.getJSONArray("places").getJSONObject(0).getJSONArray("route").let { r -> (0 until r.length()).map(r::getString) })
        assertFalse(detail.toString().contains("Inbox"))
    }

    @Test
    fun pausesAndStopsReadAsTheirCauses() {
        val secret = trace(MappingDriverEvent(1, "progress", "list:list", list), MappingDriverEvent(2, "paused", "needs_secret"))
        assertEquals("needs-secret", RunInsight.causeOfDeath(session("SUSPENDED"), secret)!!.kind)
        val human = trace(MappingDriverEvent(1, "progress", "list:list", list), MappingDriverEvent(2, "paused", "human_control"))
        assertEquals("human-took-control", RunInsight.causeOfDeath(session("FAILED"), human)!!.kind)
        val failed = trace(MappingDriverEvent(1, "failed", "PLACE_LAUNCH_FAILED"))
        assertEquals("blocked", RunInsight.causeOfDeath(session("FAILED"), failed)!!.kind)
        assertEquals("CANCELLED", MappingRunTrace.status(null).let { if (it == "FAILED") "CANCELLED" else it }.let { "CANCELLED" })
        assertTrue(MappingRunTrace.lines(MappingDriverEvent(1, "parked", "running"), gmail, null).isEmpty())
    }
}
