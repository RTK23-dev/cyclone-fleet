package com.cyclone.mobile.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression coverage for the handoff/resume paths that used to re-expand the full overlay. */
class OverlayResumePresentationTest {
    @Test
    fun returningControlToAgentKeepsContinuationCompact() {
        val machine = OverlayChromeMachine()
        machine.enterWorking("resume")
        machine.enterLive()

        // Agent hands control to the human; the action surface is intentionally expanded.
        machine.dispatch(OverlayUserAction.TAKE_CONTROL)
        assertTrue(machine.snapshot().userPaused)
        assertFalse(machine.snapshot().minimized)

        // Human finishes the step. Continuing work should immediately yield back to compact glass.
        machine.dispatch(OverlayUserAction.TAKE_CONTROL)
        assertFalse(machine.snapshot().userPaused)
        assertTrue(machine.snapshot().minimized)
        assertFalse(machine.snapshot().idleChipVisible)

        machine.enterWorking("resume")
        assertEquals(OverlayChromeState.WORKING, machine.state())
        assertTrue(machine.snapshot().minimized)
        assertFalse(machine.snapshot().idleChipVisible)
    }

    @Test
    fun confirmedGateResumesBehindCompactTaskGlass() {
        val machine = OverlayChromeMachine()
        machine.enterWorking("gate")
        machine.enterGate(OverlayGateClass.SEND, sessionId = "gate")

        machine.dispatch(OverlayUserAction.GATE_CONFIRM)
        assertEquals(OverlayChromeState.DONE, machine.state())
        assertTrue(machine.snapshot().minimized)

        // Mirrors OverlayChromeRuntime.resumeSuspendedTask(): DONE -> ANALYSIS -> WORKING.
        machine.startAnalysis("gate")
        machine.enterWorking("gate")
        assertEquals(OverlayChromeState.WORKING, machine.state())
        assertTrue(machine.snapshot().minimized)
        assertFalse(machine.snapshot().idleChipVisible)
    }
}
