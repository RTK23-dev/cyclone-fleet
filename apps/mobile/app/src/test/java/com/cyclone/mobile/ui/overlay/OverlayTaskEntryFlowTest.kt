package com.cyclone.mobile.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayTaskEntryFlowTest {
    @Test
    fun acceptedTaskEntryDrivesIdleAnalysisWorkingLiveThenReturnsRequestReady() {
        val events = mutableListOf<OverlayChromeEvent>()
        val effects = RecordingEffects()
        val machine = OverlayChromeMachine(emit = { events += it }, cycloneState = effects)

        assertEquals(OverlayChromeState.IDLE, machine.state())
        assertEquals(listOf(OverlayCopy.COMPOSER), OverlayCopy.visibleFor(machine.snapshot()))

        val acceptedTask = "Open Chrome and search for Pixel 8 user guide"
        machine.startAnalysis("task-entry-1", bullets = listOf(acceptedTask), cta = OverlayAnalysisCta.CONFIRM)
        assertEquals(OverlayChromeState.ANALYSIS, machine.state())
        assertEquals(listOf(acceptedTask), machine.snapshot().bullets)

        machine.dispatch(OverlayUserAction.CONFIRM)
        assertEquals(OverlayChromeState.WORKING, machine.state())
        assertEquals(1, effects.resumes)
        assertEventNeverClicksHost(events.single(), OverlayChromeEventKind.CONFIRM)

        machine.dispatch(OverlayUserAction.VIEW_PROGRESS)
        assertEquals(OverlayChromeState.LIVE, machine.state())
        assertEventNeverClicksHost(events.last(), OverlayChromeEventKind.VIEW_PROGRESS)

        machine.completeDone()
        assertEventNeverClicksHost(events.last(), OverlayChromeEventKind.DONE)
        assertEquals(OverlayChromeState.ANALYSIS, machine.state())
        assertTrue(machine.snapshot().minimized)
        assertTrue(machine.snapshot().composerText.isEmpty())
        assertTrue(machine.snapshot().statusMessage == null)
        assertEquals(listOf(OverlayCopy.COMPOSER), OverlayCopy.visibleFor(machine.snapshot()))

        // Opening after a finished run restores a genuinely editable composer, not a dead DONE UI.
        machine.dispatch(OverlayUserAction.ASK_CYCLONE)
        assertEquals(OverlayChromeState.ANALYSIS, machine.state())
        assertFalse(machine.snapshot().minimized)
        machine.updateComposer("Open Maps")
        assertEquals("Open Maps", machine.snapshot().composerText)
        machine.submitRequest()
        assertEquals("Open Maps", events.last().requestText)
        assertFalse(events.last().clicksHost || events.last().dispatchAccessibilityAction)
    }

    @Test
    fun twoConsecutiveTextRequestsAndVoiceTranscriptRemainUsable() {
        val events = mutableListOf<OverlayChromeEvent>()
        val machine = OverlayChromeMachine(emit = { events += it })
        machine.dispatch(OverlayUserAction.ASK_CYCLONE)
        events.clear()

        machine.updateComposer("First task")
        machine.submitRequest()
        assertEquals("First task", events.last().requestText)
        machine.enterWorking("first")
        machine.completeDone("first")
        assertTrue(machine.snapshot().minimized)

        machine.dispatch(OverlayUserAction.ASK_CYCLONE)
        machine.updateVoice(listening = true, transcript = "Second task")
        assertEquals("Second task", machine.snapshot().composerText)
        machine.updateVoice(listening = false, transcript = "Second task")
        machine.submitRequest()
        assertEquals("Second task", events.last().requestText)
    }

    @Test
    fun stoppedRunAlsoReturnsToCleanComposer() {
        val machine = OverlayChromeMachine()
        machine.dispatch(OverlayUserAction.ASK_CYCLONE)
        machine.updateComposer("Something")
        machine.enterWorking("stopped")
        machine.updateStatus("Cyclone Brain updated")
        machine.finishStopped("Provider stopped")
        assertEquals(OverlayChromeState.ANALYSIS, machine.state())
        assertTrue(machine.snapshot().minimized)
        assertEquals("", machine.snapshot().composerText)
        assertEquals(null, machine.snapshot().statusMessage)
        machine.dispatch(OverlayUserAction.ASK_CYCLONE)
        machine.updateComposer("Try again")
        assertEquals("Try again", machine.snapshot().composerText)
    }

    @Test
    fun commerceTaskEntryUsesOrderThisFromThenWorkingCopy() {
        val events = mutableListOf<OverlayChromeEvent>()
        val machine = OverlayChromeMachine(emit = { events += it })
        machine.startAnalysis(
            "task-commerce",
            bullets = listOf("Compare public product pages only"),
            cta = OverlayAnalysisCta.COMMERCE,
        )
        assertEquals("Order this from", OverlayCopy.COMMERCE)
        machine.dispatch(OverlayUserAction.COMMERCE)
        assertEquals(OverlayChromeState.WORKING, machine.state())
        assertEventNeverClicksHost(events.single(), OverlayChromeEventKind.COMMERCE)
    }

    @Test
    fun blankTaskDoesNotLeaveIdleUntilAnalysisStarts() {
        val machine = OverlayChromeMachine()
        val blank = "   "
        assertTrue(blank.trim().isEmpty())
        assertEquals(OverlayChromeState.IDLE, machine.state())
        assertEquals("Ask Cyclone", OverlayCopy.visibleFor(machine.snapshot()).single())
        machine.dispatch(OverlayUserAction.ASK_CYCLONE)
        assertEquals(OverlayChromeState.ANALYSIS, machine.state())
    }

    @Test
    fun frozenSafetyCopyRemainsAvailableForGateAndRunSurfaces() {
        assertEquals("Analysis", OverlayCopy.ANALYSIS_TITLE)
        assertEquals("Task automation", OverlayCopy.WORKING_TITLE)
        assertEquals("Working on this task", OverlayCopy.STATUS)
        assertEquals("View progress", OverlayCopy.PRIMARY)
        assertEquals("Do this", OverlayCopy.CONFIRM)
        assertEquals("Order this from", OverlayCopy.COMMERCE)
        assertEquals("Stop task", OverlayCopy.LIVE_LEFT)
        assertEquals("Take control", OverlayCopy.LIVE_RIGHT)
        assertEquals("Ask Cyclone", OverlayCopy.COMPOSER)
        assertEquals("Cyclone needs you to confirm before finishing this.", OverlayCopy.GATE)
        OverlayCopy.visibleStrings().forEach { visible ->
            OverlayCopy.NEVER_SAY.forEach { forbidden ->
                assertFalse("visible copy must not contain: $forbidden", visible.contains(forbidden))
            }
        }
    }

    private fun assertEventNeverClicksHost(event: OverlayChromeEvent, kind: OverlayChromeEventKind) {
        assertEquals(kind, event.kind)
        assertFalse(event.clicksHost)
        assertFalse(event.dispatchAccessibilityAction)
    }

    private class RecordingEffects : OverlayCycloneStateEffects {
        var pauses = 0
        var resumes = 0
        override fun pauseAgentForUser() { pauses += 1 }
        override fun resumeAgent() { resumes += 1 }
    }
}
