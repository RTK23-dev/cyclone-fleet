package com.cyclone.mobile.ui.overlay.tracefield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TraceSceneDirectorTest {
    private fun TraceSceneDirector.at(now: Double): TraceSceneFrame = frame(now)

    @Test fun wakeDrawsTheSpiralFromTheCentreThenTheTide() {
        val d = TraceSceneDirector()
        d.wake(0.0)
        val start = d.at(1.0)
        assertEquals(TraceScene.CYCLONE, start.to)
        assertTrue(start.radial)
        assertTrue(start.front in -0.2f..1.2f)
        d.at(6.5)
        assertEquals(TraceScene.TIDE, d.current)
    }

    @Test fun transitionsRunOnATideLineAndThenSettle() {
        val d = TraceSceneDirector()
        d.wake(0.0)
        d.at(6.5)
        val rising = d.at(10.0)
        assertEquals(TraceScene.CYCLONE, rising.from)
        assertEquals(TraceScene.TIDE, rising.to)
        assertTrue(rising.front > -0.2f && rising.front < 1.2f)
        val settled = d.at(14.0)
        assertEquals(TraceSceneDirector.IDLE_FRONT, settled.front)
        assertEquals(settled.from, settled.to)
    }

    @Test fun quickActionsNeverMakeThePictureJump() {
        val d = TraceSceneDirector()
        d.wake(0.0)
        d.at(7.0) // the spiral hands over to the tide here
        d.acted(7.5)
        d.observed(8.0)
        d.acted(9.0)
        assertEquals("minimum hold keeps the tide", TraceScene.TIDE, d.at(12.0).to)
        assertEquals("last request wins once the hold ends", TraceScene.WEAVE, d.at(19.5).to)
    }

    @Test fun thinkingChartsOpeningShowsFirstLight() {
        val d = TraceSceneDirector()
        d.wake(0.0)
        d.at(7.0)
        d.observed(7.0)
        assertEquals(TraceScene.CONTOUR, d.at(19.5).to)
        d.navigated(20.0)
        assertEquals(TraceScene.CONTOUR, d.at(25.0).to)
        assertEquals(TraceScene.LIGHT, d.at(32.0).to)
    }

    @Test fun longSilenceShowsTheClockAndWorkLeavesItQuickly() {
        val d = TraceSceneDirector()
        d.wake(0.0)
        d.at(7.0)
        d.observed(7.0)
        d.at(19.0)
        assertEquals(TraceScene.CONTOUR, d.current)
        assertEquals(TraceScene.CLOCK, d.at(31.0).to)
        d.acted(33.0)
        assertEquals("clock yields after its short hold", TraceScene.WEAVE, d.at(35.5).to)
    }

    @Test fun handoffShowsReachImmediatelyAndHoldsUntilResume() {
        val d = TraceSceneDirector()
        d.wake(0.0)
        d.at(7.0)
        d.handoff(8.0)
        assertEquals(TraceScene.REACH, d.at(8.1).to)
        d.acted(9.0)
        assertEquals(TraceScene.REACH, d.at(200.0).to)
        d.resumed(201.0)
        assertEquals(TraceScene.TIDE, d.at(201.1).to)
    }

    @Test fun doneBloomsTheSpiralRadially() {
        val d = TraceSceneDirector()
        d.wake(0.0)
        d.at(20.0)
        d.done(20.0)
        val f = d.at(20.5)
        assertEquals(TraceScene.CYCLONE, f.to)
        assertTrue(f.radial)
    }

    @Test fun ambientLoopRotatesWhenNothingHappens() {
        val d = TraceSceneDirector()
        d.resumed(0.0)
        d.at(1.0)
        // Without signals the waiting clock would appear, so keep the agent busy on the current scene.
        val seen = mutableListOf<TraceScene>()
        var t = 1.0
        while (t < 170.0) {
            t += 0.5
            if (t % 5.0 == 0.0) d.acted(t)
            seen += d.at(t).to
        }
        assertTrue(seen.contains(TraceScene.WEAVE))
        assertFalse(seen.contains(TraceScene.REACH))
        assertFalse(seen.contains(TraceScene.CYCLONE))
    }

    @Test fun reduceMotionSwapsScenesWithoutATide() {
        val d = TraceSceneDirector(reduceMotion = true)
        d.wake(0.0)
        val f = d.at(0.1)
        assertEquals(TraceSceneDirector.IDLE_FRONT, f.front)
        assertEquals(TraceScene.CYCLONE, f.to)
    }

    @Test fun cogwheelsAreNotAScene() {
        assertEquals(
            listOf("CYCLONE", "TIDE", "CONTOUR", "WEAVE", "LIGHT", "CLOCK", "REACH"),
            TraceScene.entries.map { it.name },
        )
    }
}
