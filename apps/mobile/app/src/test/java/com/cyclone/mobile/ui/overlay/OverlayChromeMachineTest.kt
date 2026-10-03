package com.cyclone.mobile.ui.overlay

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayChromeMachineTest {
    @Test
    fun stateMachineAcceptsOnlyTheSixFrozenStates() {
        assertEquals(OverlayChromeContract.overlayStates, OverlayChromeState.NAMES)
        assertEquals(6, OverlayChromeState.entries.size)
        OverlayChromeContract.overlayStates.forEach { name ->
            assertEquals(name, OverlayChromeState.parse(name).name)
        }
        assertThrows(IllegalArgumentException::class.java) { OverlayChromeState.parse("PLANNING") }
        assertThrows(IllegalArgumentException::class.java) { OverlayChromeState.parse("THINKING") }
        assertThrows(IllegalArgumentException::class.java) { OverlayChromeState.parse("CONFIRMING") }
        assertThrows(IllegalArgumentException::class.java) { OverlayChromeState.parse("SEVENTH") }
    }

    @Test
    fun confirmStopTakeControlAndGateConfirmEmitCycloneEventsNotAccessibilityClicks() {
        val events = mutableListOf<OverlayChromeEvent>()
        val effects = RecordingEffects()
        val machine = OverlayChromeMachine(emit = { events += it }, cycloneState = effects)

        machine.startAnalysis("s1", bullets = listOf("Pad thai", "Spring rolls"), cta = OverlayAnalysisCta.CONFIRM)
        machine.dispatch(OverlayUserAction.CONFIRM)
        assertEvent(events.removeAt(0), OverlayChromeEventKind.CONFIRM, clicksHost = false)
        assertEquals(OverlayChromeState.WORKING, machine.state())
        assertEquals(1, effects.resumes)

        machine.dispatch(OverlayUserAction.STOP_TASK)
        assertEvent(events.removeAt(0), OverlayChromeEventKind.STOP_TASK, clicksHost = false)
        assertEquals(OverlayChromeState.IDLE, machine.state())
        assertEquals(1, effects.pauses)

        machine.startAnalysis("s2", cta = OverlayAnalysisCta.COMMERCE)
        machine.dispatch(OverlayUserAction.COMMERCE)
        assertEvent(events.removeAt(0), OverlayChromeEventKind.COMMERCE, clicksHost = false)
        machine.dispatch(OverlayUserAction.VIEW_PROGRESS)
        assertEvent(events.removeAt(0), OverlayChromeEventKind.VIEW_PROGRESS, clicksHost = false)
        assertEquals(OverlayChromeState.LIVE, machine.state())

        machine.dispatch(OverlayUserAction.TAKE_CONTROL)
        assertEvent(events.removeAt(0), OverlayChromeEventKind.TAKE_CONTROL, clicksHost = false)
        assertEquals(OverlayChromeState.LIVE, machine.state())
        assertTrue(machine.snapshot().userPaused)
        assertEquals(2, effects.pauses)
        machine.dispatch(OverlayUserAction.TAKE_CONTROL)
        assertEvent(events.removeAt(0), OverlayChromeEventKind.TAKE_CONTROL, clicksHost = false)
        assertFalse(machine.snapshot().userPaused)

        machine.enterWorking("s3")
        machine.enterGate(OverlayGateClass.PAY, pcAutoApprove = true)
        val gate = events.removeAt(0)
        assertEvent(gate, OverlayChromeEventKind.GATE, clicksHost = false)
        assertTrue(gate.pcAutoApproveIgnored)
        assertEquals("pay", gate.gateClass?.wire)
        assertEquals(OverlayChromeState.GATE, machine.state())
        assertEquals(3, effects.pauses)

        machine.dispatch(OverlayUserAction.GATE_CONFIRM)
        assertEvent(events.removeAt(0), OverlayChromeEventKind.GATE_CONFIRM, clicksHost = false)
        assertEvent(events.removeAt(0), OverlayChromeEventKind.DONE, clicksHost = false)
        assertEquals(OverlayChromeState.DONE, machine.state())
        assertTrue(events.none { it.dispatchAccessibilityAction })
        assertTrue(events.none { it.clicksHost })
    }

    @Test
    fun pcAutoApproveCannotDismissGateAndCompleteDoneCannotSkipGate() {
        val events = mutableListOf<OverlayChromeEvent>()
        val machine = OverlayChromeMachine(emit = { events += it })
        machine.enterWorking("gated")
        machine.enterGate(OverlayGateClass.SEND, pcAutoApprove = true)
        assertEquals(OverlayChromeState.GATE, machine.state())
        machine.completeDone()
        assertEquals(OverlayChromeState.GATE, machine.state())
        assertEquals(listOf(OverlayChromeEventKind.GATE), events.map { it.kind })
        machine.dispatch(OverlayUserAction.STOP_TASK)
        assertEquals(OverlayChromeState.GATE, machine.state())
        machine.dispatch(OverlayUserAction.TAKE_CONTROL)
        assertEquals(OverlayChromeState.GATE, machine.state())
    }

    @Test
    fun enterGateFromIdleShowsGateCopyWithoutHostClick() {
        val events = mutableListOf<OverlayChromeEvent>()
        val machine = OverlayChromeMachine(emit = { events += it })
        assertEquals(OverlayChromeState.IDLE, machine.state())
        machine.enterGate(OverlayGateClass.DELETE)
        assertEquals(OverlayChromeState.GATE, machine.state())
        assertEquals(
            listOf(
                OverlayCopy.AI_MODE,
                "Cyclone needs you to confirm before finishing this.",
                "Do this",
                OverlayCopy.MINIMIZE,
                OverlayCopy.EXIT,
                OverlayCopy.LEGAL,
            ),
            OverlayCopy.visibleFor(machine.snapshot()),
        )
        assertFalse(events.single().clicksHost)
        assertFalse(events.single().dispatchAccessibilityAction)
    }

    @Test
    fun idleOrbActivationEntersAiModeAndEmitsEvent() {
        val events = mutableListOf<OverlayChromeEvent>()
        val machine = OverlayChromeMachine(emit = { events += it })
        machine.dispatch(OverlayUserAction.ASK_CYCLONE)
        assertEquals(OverlayChromeState.ANALYSIS, machine.state())
        assertEvent(events.single(), OverlayChromeEventKind.ASK_CYCLONE, clicksHost = false)
        machine.dispatch(OverlayUserAction.CONFIRM)
        machine.dispatch(OverlayUserAction.GATE_CONFIRM)
        assertEquals(listOf(OverlayChromeEventKind.ASK_CYCLONE, OverlayChromeEventKind.CONFIRM), events.map { it.kind })
    }

    @Test
    fun eventJsonUsesFrozenKeysAndNeverMarksHostClicks() {
        val event = OverlayChromeEvent(
            kind = OverlayChromeEventKind.GATE_CONFIRM,
            state = OverlayChromeState.GATE,
            sessionId = "sess",
            gateClass = OverlayGateClass.DELETE,
        )
        val json: JSONObject = event.toJson()
        OverlayChromeContract.jsonKeys.forEach { key -> assertTrue(json.has(key)) }
        assertEquals(OverlayChromeContract.JSON_TYPE, json.getString("type"))
        assertEquals("GATE_CONFIRM", json.getString("kind"))
        assertEquals("GATE", json.getString("state"))
        assertEquals("sess", json.getString("sessionId"))
        assertFalse(json.getBoolean("clicksHost"))
        assertFalse(json.getBoolean("dispatchAccessibilityAction"))
        assertEquals("delete", json.getString("gateClass"))
        assertEquals(OverlayChromeContract.eventKinds.toSet(), OverlayChromeEventKind.entries.map { it.name }.toSet())
    }

    @Test
    fun stopRemainsLimitedToWorkingAndLive() {
        val events = mutableListOf<OverlayChromeEvent>()
        val machine = OverlayChromeMachine(emit = { events += it })
        machine.startAnalysis("s")
        machine.dispatch(OverlayUserAction.STOP_TASK)
        assertTrue(events.isEmpty())
        machine.dispatch(OverlayUserAction.CONFIRM)
        events.clear()
        assertTrue(machine.snapshot().state == OverlayChromeState.WORKING)
        machine.dispatch(OverlayUserAction.STOP_TASK)
        assertEquals(OverlayChromeEventKind.STOP_TASK, events.single().kind)
    }

    @Test
    fun activeWorkCollapsesToComposerThenLauncherAndTripleTapRestoresComposerWithoutChangingRunState() {
        val events = mutableListOf<OverlayChromeEvent>()
        val effects = RecordingEffects()
        val machine = OverlayChromeMachine(emit = { events += it }, cycloneState = effects)
        machine.enterWorking("restore")
        machine.enterLive()

        machine.dispatch(OverlayUserAction.MINIMIZE)
        assertEquals(OverlayChromeState.LIVE, machine.state())
        assertTrue(machine.snapshot().minimized)
        assertFalse(machine.snapshot().launcherCollapsed)
        assertFalse(machine.snapshot().idleChipVisible)
        assertFalse(machine.snapshot().voiceListening)

        machine.updateComposer("next request")
        assertEquals("next request", machine.snapshot().composerText)

        machine.dispatch(OverlayUserAction.MINIMIZE)
        assertEquals(OverlayChromeState.LIVE, machine.state())
        assertTrue(machine.snapshot().minimized)
        assertTrue(machine.snapshot().launcherCollapsed)
        // Plan 27: folded away while a task runs, only the live notification remains (its Show brings the island back).
        assertFalse(machine.snapshot().idleChipVisible)
        assertEquals("next request", machine.snapshot().composerText)

        // This is the action emitted after the launcher receives its deliberate triple tap.
        machine.dispatch(OverlayUserAction.ASK_CYCLONE)
        assertEquals(OverlayChromeState.LIVE, machine.state())
        assertTrue(machine.snapshot().minimized)
        assertFalse(machine.snapshot().launcherCollapsed)
        assertFalse(machine.snapshot().idleChipVisible)
        assertEquals("next request", machine.snapshot().composerText)

        // Explicit expand from the minimized composer's handle restores the full drawer.
        machine.dispatch(OverlayUserAction.ASK_CYCLONE)
        assertFalse(machine.snapshot().minimized)
        assertFalse(machine.snapshot().launcherCollapsed)

        machine.enterGate(OverlayGateClass.DELETE)
        events.clear()
        machine.dispatch(OverlayUserAction.EXIT)
        assertEquals(OverlayChromeState.IDLE, machine.state())
        assertEquals(listOf(OverlayChromeEventKind.STOP_TASK), events.map { it.kind })
        assertTrue(events.none { it.kind == OverlayChromeEventKind.GATE_CONFIRM })
        assertTrue(effects.pauses >= 2)
    }

    @Test
    fun composerSubmissionIsBoundedAndNeverClicksTheHost() {
        val events = mutableListOf<OverlayChromeEvent>()
        val machine = OverlayChromeMachine(emit = { events += it })
        machine.dispatch(OverlayUserAction.ASK_CYCLONE)
        events.clear()
        machine.updateComposer("  Find my latest invoice  ")
        machine.submitRequest()
        val request = events.single()
        assertEquals(OverlayChromeEventKind.ASK_CYCLONE, request.kind)
        assertEquals("Find my latest invoice", request.requestText)
        assertEquals(listOf("Find my latest invoice"), machine.snapshot().bullets)
        assertFalse(request.clicksHost || request.dispatchAccessibilityAction)
    }


    @Test fun aNewTaskOpensProgressAfterThePreviousTaskWasCollapsed() {
        val machine = OverlayChromeMachine()
        machine.enterWorking("first")
        machine.dispatch(OverlayUserAction.MINIMIZE)
        machine.dispatch(OverlayUserAction.MINIMIZE)
        assertTrue(machine.snapshot().launcherCollapsed)
        machine.finishStopped("stopped")
        machine.enterWorking("second")
        assertFalse(machine.snapshot().minimized)
        assertFalse(machine.snapshot().launcherCollapsed)
        // Ordinary progress updates do not undo a deliberate collapse during this task.
        machine.dispatch(OverlayUserAction.MINIMIZE)
        machine.updateStatus("Opening Chrome")
        assertTrue(machine.snapshot().minimized)
    }

    @Test fun backgroundTaskOverviewOpensWithoutResumingOrConfirmingTheAgent() {
        val effects = RecordingEffects()
        val machine = OverlayChromeMachine(cycloneState = effects)
        machine.showTaskProgress()
        assertEquals(OverlayChromeState.ANALYSIS, machine.state())
        assertFalse(machine.snapshot().minimized)
        assertFalse(machine.snapshot().idleChipVisible)
        assertEquals(0, effects.resumes)
        machine.enterGate(OverlayGateClass.PAY)
        machine.showTaskProgress()
        assertEquals(OverlayChromeState.GATE, machine.state())
        assertEquals(0, effects.resumes)
    }

    private fun assertEvent(event: OverlayChromeEvent, kind: OverlayChromeEventKind, clicksHost: Boolean) {
        assertEquals(kind, event.kind)
        assertEquals(clicksHost, event.clicksHost)
        assertFalse(event.dispatchAccessibilityAction)
    }

    private class RecordingEffects : OverlayCycloneStateEffects {
        var pauses = 0
        var resumes = 0
        override fun pauseAgentForUser() {
            pauses += 1
        }
        override fun resumeAgent() {
            resumes += 1
        }
    }
}

class OverlayNotificationOnlyTest {
    @Test fun foldingAFinishedTaskAwayLeavesTheLauncher() {
        val machine = OverlayChromeMachine(emit = {})
        machine.enterWorking("done-task")
        machine.completeDone("done-task")
        machine.dispatch(OverlayUserAction.MINIMIZE)
        machine.dispatch(OverlayUserAction.MINIMIZE)
        assertTrue(machine.snapshot().launcherCollapsed)
        assertTrue(machine.snapshot().idleChipVisible)
    }
}
