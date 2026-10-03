package com.cyclone.mobile.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Agent E: GATE for pay/send/delete/grant, plus Stop task / Take control host isolation.
 * Overlay buttons change Cyclone controller state only. They never tap the host app.
 */
class OverlayGateInterruptTest {
    @Test
    fun paySendDeleteGrantEachEnterGateWithExactCopyAndIgnorePcAutoApprove() {
        OverlayGateClass.entries.forEach { gateClass ->
            val events = mutableListOf<OverlayChromeEvent>()
            val effects = RecordingEffects()
            val machine = OverlayChromeMachine(emit = { events += it }, cycloneState = effects)
            machine.enterWorking("gate-${gateClass.wire}")
            machine.enterGate(gateClass, pcAutoApprove = true)

            assertEquals(OverlayChromeState.GATE, machine.state())
            assertEquals(gateClass, machine.snapshot().gateClass)
            assertTrue(machine.snapshot().pcAutoApproveIgnored)
            assertEquals(1, effects.pauses)
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
            assertEquals("Cyclone needs you to confirm before finishing this.", OverlayCopy.GATE)
            val gateEvent = events.single { it.kind == OverlayChromeEventKind.GATE }
            assertEquals(gateClass.wire, gateEvent.gateClass?.wire)
            assertFalse(gateEvent.clicksHost)
            assertFalse(gateEvent.dispatchAccessibilityAction)
            assertTrue(gateEvent.pcAutoApproveIgnored)

            machine.dispatch(OverlayUserAction.STOP_TASK)
            machine.dispatch(OverlayUserAction.TAKE_CONTROL)
            assertEquals(OverlayChromeState.GATE, machine.state())

            machine.dispatch(OverlayUserAction.GATE_CONFIRM)
            assertEquals(OverlayChromeState.DONE, machine.state())
            // Confirmation is the transition back to agent-owned execution. The transient DONE
            // snapshot must stay visually collapsed so resumeSuspendedTask can continue behind the
            // compact task glass instead of flashing a second full result/composer sheet.
            assertTrue(machine.snapshot().minimized)
            assertFalse(machine.snapshot().idleChipVisible)
            assertEquals(2, effects.resumes)
            assertTrue(events.none { it.clicksHost || it.dispatchAccessibilityAction })
        }
    }

    @Test
    fun stopTaskFromWorkingPausesCycloneOnlyAndReturnsIdleAskCyclone() {
        val events = mutableListOf<OverlayChromeEvent>()
        val effects = RecordingEffects()
        val machine = OverlayChromeMachine(emit = { events += it }, cycloneState = effects)
        machine.startAnalysis("stop-1")
        machine.dispatch(OverlayUserAction.CONFIRM)
        events.clear()
        effects.pauses = 0
        effects.resumes = 0

        machine.dispatch(OverlayUserAction.STOP_TASK)
        assertEquals(OverlayChromeState.IDLE, machine.state())
        assertEquals(listOf("Ask Cyclone"), OverlayCopy.visibleFor(machine.snapshot()))
        assertEquals(1, effects.pauses)
        assertEquals(0, effects.resumes)
        val stop = events.single()
        assertEquals(OverlayChromeEventKind.STOP_TASK, stop.kind)
        assertFalse(stop.clicksHost)
        assertFalse(stop.dispatchAccessibilityAction)
    }

    @Test
    fun takeControlFromLivePausesInPlaceAndNeverFlagsHostClick() {
        val events = mutableListOf<OverlayChromeEvent>()
        val effects = RecordingEffects()
        val machine = OverlayChromeMachine(emit = { events += it }, cycloneState = effects)
        machine.enterWorking("take-1")
        machine.enterLive()
        events.clear()
        effects.pauses = 0

        machine.dispatch(OverlayUserAction.TAKE_CONTROL)
        assertEquals(OverlayChromeState.LIVE, machine.state())
        assertTrue(machine.snapshot().userPaused)
        assertEquals("Take control", OverlayCopy.LIVE_RIGHT)
        assertEquals("Stop task", OverlayCopy.LIVE_LEFT)
        assertEquals(1, effects.pauses)
        val take = events.single()
        assertEquals(OverlayChromeEventKind.TAKE_CONTROL, take.kind)
        assertFalse("Take control must not click the host app", take.clicksHost)
        assertFalse("Take control must not dispatch Accessibility actions", take.dispatchAccessibilityAction)
    }

    @Test
    fun liveCopyExposesStopAndTakeControlWithoutHostMutationFlags() {
        val events = mutableListOf<OverlayChromeEvent>()
        val machine = OverlayChromeMachine(emit = { events += it })
        machine.enterWorking("live-copy")
        machine.dispatch(OverlayUserAction.VIEW_PROGRESS)
        assertEquals(OverlayChromeState.LIVE, machine.state())
        val liveCopy = OverlayCopy.visibleFor(machine.snapshot())
        assertEquals(
            listOf(OverlayCopy.AI_MODE, "Working on this task", OverlayCopy.PAUSE, OverlayCopy.MINIMIZE, OverlayCopy.EXIT, OverlayCopy.COMPOSER),
            liveCopy,
        )
        assertTrue(events.none { it.clicksHost || it.dispatchAccessibilityAction })
    }

    @Test
    fun gateFromLiveStillUsesExactGateString() {
        val machine = OverlayChromeMachine()
        machine.enterWorking("live-gate")
        machine.enterLive()
        machine.enterGate(OverlayGateClass.SEND)
        assertEquals(OverlayChromeState.GATE, machine.state())
        assertTrue(OverlayCopy.visibleFor(machine.snapshot()).contains("Cyclone needs you to confirm before finishing this."))
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

@RunWith(Parameterized::class)
class OverlayGateClassWireTest(private val gateClass: OverlayGateClass, private val wire: String) {
    @Test
    fun wireNamesStayPaySendDeleteGrant() {
        assertEquals(wire, gateClass.wire)
        assertEquals(gateClass, OverlayGateClass.parse(wire))
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}={1}")
        fun data(): Collection<Array<Any>> = listOf(
            arrayOf(OverlayGateClass.PAY, "pay"),
            arrayOf(OverlayGateClass.SEND, "send"),
            arrayOf(OverlayGateClass.DELETE, "delete"),
            arrayOf(OverlayGateClass.GRANT, "grant"),
        )
    }
}
