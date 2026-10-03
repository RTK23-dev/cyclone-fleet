package com.cyclone.mobile.ai.vision.live

import com.cyclone.mobile.agent.ObservationSurface
import com.cyclone.mobile.agent.SemanticCaptureBoundary
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FreshFrameCaptureTest {
    private fun frame(at: Long, id: Long = 1) = LiveFrame("task", 7, id, at, 100, 200, FrameSourceType.SCRCPY)

    @Test fun healthyBufferedFrameWaitsForPostRequestFrameAndPassesTheActualCaptureBoundary() {
        var now = 1_000L; var captures = 0; var waits = 0
        val frames = mutableListOf(frame(500))
        assertTrue(FrameSelection.eligible(frames.single(), "task", 7, now, 750))
        val surface = ObservationSurface("task", 7, "scope", "window", 100, 200, 0, 1)
        val bundle = SemanticCaptureBoundary.capture({ surface }, { captures++; now += 10; "tree" }, {
            val selected = FrameSelection.awaitFresh("task", 7, null, now, 800, { frames }, { true }, { now }) {
                waits++; now += 25; frames.add(frame(now, 2))
            }!!
            JSONObject().put("sessionId", selected.sessionId).put("displayId", selected.displayId)
                .put("width", selected.width).put("height", selected.height)
                .put("capturedAtMonotonicMs", selected.capturedAtMonotonicMs).put("pngBase64", "fixture")
        }, { now })
        assertEquals(1, captures); assertEquals(1, waits)
        assertEquals(1_035L, bundle.image!!.getLong("capturedAtMonotonicMs"))
        assertTrue(bundle.image.getBoolean("available"))
    }

    @Test fun staleOnlyStreamTimesOutAtTheBoundWithoutRelaxingFreshness() {
        var now = 1_000L; var waits = 0
        val selected = FrameSelection.awaitFresh("task", 7, null, now, 800, { listOf(frame(500)) }, { true }, { now }) {
            waits++; now += it
        }
        assertNull(selected); assertEquals(1_800L, now); assertEquals(1, waits)
    }

    @Test fun newRequestPreservesPostActionAndScopeRestrictions() {
        val after = ActionFrameBoundary("task", 7, 3, 1_010)
        val cases = listOf(frame(1_025, 3), frame(1_010, 4), frame(1_025, 4).copy(displayId = 8),
            frame(1_025, 4).copy(sessionId = "other"), frame(1_031, 4))
        cases.forEach { assertFalse(FrameSelection.eligible(it, "task", 7, 1_030, 750, after, 1_020)) }
        assertTrue(FrameSelection.eligible(frame(1_025, 4), "task", 7, 1_030, 750, after, 1_020))
    }

    @Test fun stoppedOrReplacedSourceCannotReturnANewFrameAfterTheWait() {
        var now = 1_000L; var active = true
        val frames = mutableListOf(frame(500))
        val selected = FrameSelection.awaitFresh("task", 7, null, now, 800, { frames }, { active }, { now }) {
            now += 25; frames.add(frame(now, 2)); active = false
        }
        assertNull(selected)
    }
}
