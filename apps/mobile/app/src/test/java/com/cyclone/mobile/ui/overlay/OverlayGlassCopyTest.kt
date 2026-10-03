package com.cyclone.mobile.ui.overlay

import com.cyclone.mobile.runtime.background.SemanticStepState
import com.cyclone.mobile.runtime.background.TaskPresentationMilestone
import com.cyclone.mobile.runtime.plane.PlaneKind
import com.cyclone.mobile.runtime.plane.PlaneUi
import com.cyclone.mobile.ui.v32.CycloneTaskVisualState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OverlayGlassCopyTest {
    @Test fun anApprovalLeadsWithTheQuotedMessage() {
        val (where, message) = OverlayGlassCopy.approval("Send “I'll be there at 8, and I'll bring your charger.” to Sam in WhatsApp?", "Approve this?")
        assertEquals("I'll be there at 8, and I'll bring your charger.", message)
        assertEquals("Send to Sam in WhatsApp?", where)
    }

    @Test fun anApprovalWithoutAQuoteKeepsTheWholeRequestUnderTheTitle() {
        val (where, message) = OverlayGlassCopy.approval("Delete 3 photos from the album", "Approve this?")
        assertEquals("Approve this?", where)
        assertEquals("Delete 3 photos from the album", message)
    }

    @Test fun theChipAndThePillSpeakInOneWord() {
        assertEquals("Needs you", OverlayGlassCopy.chip(CycloneTaskVisualState.ACTION_NEEDED))
        assertEquals("Working", OverlayGlassCopy.chip(CycloneTaskVisualState.WORKING))
        assertEquals("Screen", OverlayGlassCopy.planeWord(PlanePillLook.SCREEN))
        assertEquals("Background", OverlayGlassCopy.planeWord(PlanePillLook.BACKGROUND))
        assertEquals("Moving", OverlayGlassCopy.planeWord(PlanePillLook.SWITCHING))
        assertEquals("Waiting", OverlayGlassCopy.planeWord(PlanePillLook.WAITING))
        assertEquals("Screen", OverlayGlassCopy.planeWord(PlanePillLook.UNAVAILABLE))
    }

    @Test fun theCardShowsTheLastFinishedStepsAndTheCurrentOne() {
        val steps = listOf("a", "b", "c", "d").map { TaskPresentationMilestone(it, SemanticStepState.DONE) } +
            TaskPresentationMilestone("now", SemanticStepState.ACTIVE) + TaskPresentationMilestone("later", SemanticStepState.PENDING)
        assertEquals(listOf("c", "d", "now"), OverlayGlassCopy.steps(steps).map { it.label })
        assertEquals(emptyList<String>(), OverlayGlassCopy.steps(emptyList()).map { it.label })
    }

    @Test fun theMetaLineAndTheIslandCountSteps() {
        assertEquals("STEP 3 OF 8 · CALENDAR → WHATSAPP", OverlayGlassCopy.meta(2, 8, listOf("Calendar", "WhatsApp")))
        assertEquals("WHATSAPP", OverlayGlassCopy.meta(2, null, listOf("WhatsApp")))
        assertEquals("Typing your reply" to "Replying to Sam · 3 of 7", OverlayGlassCopy.island("Typing your reply", "Replying to Sam", 2, 7))
        assertEquals("Replying to Sam" to "Replying to Sam", OverlayGlassCopy.island(null, "Replying to Sam", 0, null))
    }

    @Test fun theOutcomeLineOnlyShowsARealReason() {
        assertNull(OverlayGlassCopy.outcome(PlaneUi("m", PlaneKind.SCREEN)))
        assertEquals("Couldn't move WhatsApp: it stayed on your screen.",
            OverlayGlassCopy.outcome(PlaneUi("m", PlaneKind.SCREEN, outcome = "Couldn't move WhatsApp: it stayed on your screen.", outcomeSeq = 1)))
    }
}
