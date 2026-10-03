package com.cyclone.mobile.ui.overlay

import org.junit.Assert.*
import org.junit.Test

class OverlayTerminalLifecycle427Test {
    private class Effects : OverlayCycloneStateEffects {
        var controller = "AGENT"
        override fun pauseAgentForUser() { controller = "HUMAN" }
        override fun resumeAgent() { controller = "AGENT" }
    }

    @Test fun failureReturnsToUsableMinimizedComposer() {
        val machine = OverlayChromeMachine(emit = {})
        machine.startAnalysis("task")
        machine.enterWorking("task")
        machine.finishStopped("Failed")
        assertFalse(machine.snapshot().idleChipVisible)
        assertTrue(machine.snapshot().minimized)
        assertFalse(machine.snapshot().launcherCollapsed)
        machine.dispatch(OverlayUserAction.ASK_CYCLONE)
        machine.updateComposer("open Chrome")
        machine.submitRequest()
        assertEquals(listOf("open Chrome"), machine.snapshot().bullets)
    }

    @Test fun terminalStopReleasesTemporaryHumanPauseForNextMission() {
        val effects = Effects()
        val machine = OverlayChromeMachine(emit = {}, cycloneState = effects)
        machine.enterWorking("first")
        machine.dispatch(OverlayUserAction.STOP_TASK)
        assertEquals("HUMAN", effects.controller)
        machine.finishStopped("Stopped by owner")
        assertEquals("AGENT", effects.controller)
        machine.enterWorking("second")
        assertEquals(OverlayChromeState.WORKING, machine.state())
    }

    @Test fun terminalFailureKeepsExplicitOwnerTakeover() {
        val effects = Effects()
        val machine = OverlayChromeMachine(emit = {}, cycloneState = effects)
        machine.enterWorking("first")
        machine.dispatch(OverlayUserAction.TAKE_CONTROL)
        assertTrue(machine.snapshot().userPaused)
        machine.finishStopped("Failed while owner had phone")
        assertEquals("HUMAN", effects.controller)
        assertTrue(machine.snapshot().userPaused)
        machine.dispatch(OverlayUserAction.TAKE_CONTROL)
        assertEquals("AGENT", effects.controller)
    }
}
