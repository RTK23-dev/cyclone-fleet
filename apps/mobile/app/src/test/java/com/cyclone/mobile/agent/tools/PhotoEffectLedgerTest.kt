package com.cyclone.mobile.agent.tools
import org.junit.Assert.*
import org.junit.Test
class PhotoEffectLedgerTest {
    @Test fun oldGalleryAndAnimationCannotProveCaptureOrAllowAnotherShutter() {
        val ledger = PhotoEffectLedger()
        assertTrue(ledger.request(setOf(1), 1000))
        ledger.observe(mapOf(1L to 2000L, 2L to 999L))
        assertEquals(PhotoEffectLedger.State.AWAITING_PROOF, ledger.state)
        assertFalse(ledger.request(setOf(1, 2), 3000))
    }
    @Test fun newlySavedCameraMediaSurvivesLaterMissingEvidence() {
        val ledger = PhotoEffectLedger()
        ledger.request(setOf(1), 1000)
        ledger.observe(mapOf(2L to 1001L))
        ledger.observe(null)
        assertEquals(PhotoEffectLedger.State.VERIFIED, ledger.state)
        assertFalse(ledger.request(emptySet(), 2000))
    }
    @Test fun missingBeforeWitnessCannotClaimANewCapture() {
        val ledger = PhotoEffectLedger()
        ledger.request(null, 1000)
        ledger.observe(mapOf(2L to 2000L))
        assertEquals(PhotoEffectLedger.State.AWAITING_PROOF, ledger.state)
    }
}
