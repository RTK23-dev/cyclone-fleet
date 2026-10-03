package com.cyclone.mobile.fastpath
import org.junit.Assert.*
import org.junit.Test
class MutationGroundingTest {
    @Test fun staleOrForeignObservationCannotGroundMutation() {
        assertTrue(MutationGrounding.matches("o", "o", "f", "f", "s", 7, "s", 7))
        assertFalse(MutationGrounding.matches("old", "o", "f", "f", "s", 7, "s", 7))
        assertFalse(MutationGrounding.matches("o", "o", "f", "changed", "s", 7, "s", 7))
        assertFalse(MutationGrounding.matches("o", "o", "f", "f", "s", 7, "other", 7))
        assertFalse(MutationGrounding.matches("o", "o", "f", "f", "s", 7, "s", 0))
    }
    @Test fun backDispatchOnStableScreenIsNotNavigationSuccess() {
        assertFalse(MutationGrounding.verifiedTransition(true, false))
        assertFalse(MutationGrounding.verifiedTransition(true, null))
        assertTrue(MutationGrounding.verifiedTransition(true, true))
        assertFalse(MutationGrounding.verifiedTransition(false, true))
    }
    @Test fun plannerLandingsDoNotNeedAFreshObservationId() {
        assertFalse(MutationGrounding.requiredFor("phone.open_app"))
        assertFalse(MutationGrounding.requiredFor("phone.launch_intent"))
        assertFalse(MutationGrounding.requiredFor("phone.home"))
        assertFalse(MutationGrounding.requiredFor("phone.wait_for"))
        assertTrue(MutationGrounding.requiredFor("phone.click"))
        assertTrue(MutationGrounding.requiredFor("phone.type"))
    }
}
