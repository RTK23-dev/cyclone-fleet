package com.cyclone.mobile.applearner

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcVerifiedRouteLearningTest {
    @Test
    fun transportSuccessAloneIsNotAReusableRoute() {
        val outcome = PcRouteOutcomeEvidence(
            transportOk = true,
            androidExecutionOk = true,
            verificationStatus = "OBSERVED",
            before = page("Home"),
            after = page("Settings"),
        )

        assertFalse(outcome.isVerifiedPageOutcome)
    }

    @Test
    fun passedVerificationStillRequiresAnActualPageOutcome() {
        val home = page("Home")
        val outcome = PcRouteOutcomeEvidence(
            transportOk = true,
            androidExecutionOk = true,
            verificationStatus = "PASSED",
            before = home,
            after = home,
        )

        assertFalse(outcome.isVerifiedPageOutcome)
    }

    @Test
    fun passedSemanticAfterStateOnDifferentPageCanBeLearned() {
        val outcome = PcRouteOutcomeEvidence(
            transportOk = true,
            androidExecutionOk = true,
            verificationStatus = "PASSED",
            before = page("Home"),
            after = page("Settings"),
        )

        assertTrue(outcome.isVerifiedPageOutcome)
    }

    private fun page(title: String) = PageContext(
        pageKey = "pkg:$title",
        packageName = "pkg",
        className = "pkg.$title",
        title = title,
        structuralKey = title,
        contentKey = title,
        controls = emptyList(),
        observationCount = 1,
        firstSeenAt = 1,
        lastSeenAt = 1,
    )
}
