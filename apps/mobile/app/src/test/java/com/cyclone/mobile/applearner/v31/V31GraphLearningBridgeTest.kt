package com.cyclone.mobile.applearner.v31

import com.cyclone.mobile.brain.graphv2.GraphEvidenceKind
import com.cyclone.mobile.brain.graphv2.GraphVerificationScope
import com.cyclone.mobile.brain.graphv2.GraphVerificationState
import com.cyclone.mobile.brain.graphv2.InMemoryTemporalGraphStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V31GraphLearningBridgeTest {
    @Test
    fun verifiedPhysicalLearningCanReachGraphV2() {
        val store = InMemoryTemporalGraphStore()
        val result = V31GraphLearningBridge(store).recordTransition(
            transition(
                V31LearningEvidence(
                    kind = GraphEvidenceKind.ACTION_AFTER_STATE,
                    evidenceId = "physical-1",
                    producer = "phone-runtime",
                    physicalDeviceEvidence = true,
                    observedAtEpochMillis = 100,
                    succeeded = true,
                    verifiedRequested = true,
                    confidence = 0.96,
                    appVersionCode = 12,
                ),
            ),
        )

        assertEquals(GraphVerificationState.VERIFIED, result.verificationState)
        assertTrue(result.recorded > 0)
        assertTrue(store.currentEdges().any {
            it.evidence.verificationState == GraphVerificationState.VERIFIED &&
                it.evidence.verificationScope == GraphVerificationScope.PHYSICAL_DEVICE
        })
    }

    @Test
    fun ciLearningCannotClaimPhysicalVerification() {
        val store = InMemoryTemporalGraphStore()
        val result = V31GraphLearningBridge(store).recordTransition(
            transition(
                V31LearningEvidence(
                    kind = GraphEvidenceKind.CI_FIXTURE,
                    evidenceId = "ci-1",
                    producer = "unit-test",
                    physicalDeviceEvidence = true,
                    observedAtEpochMillis = 100,
                    succeeded = true,
                    verifiedRequested = true,
                    confidence = 0.99,
                ),
            ),
        )

        assertEquals(GraphVerificationState.OBSERVED, result.verificationState)
        assertTrue(store.currentEdges().none { it.evidence.verificationScope == GraphVerificationScope.PHYSICAL_DEVICE })
    }

    private fun transition(evidence: V31LearningEvidence) = V31LearnedTransition(
        appPackage = "com.android.settings",
        appName = "Settings",
        activityClass = "Settings",
        fromPageKey = "settings.home",
        fromPageTitle = "Settings",
        toPageKey = "settings.apps",
        toPageTitle = "Apps",
        actionName = "open apps",
        elementName = "Apps",
        selectorKey = "text=Apps",
        routineId = "settings.apps",
        evidence = evidence,
    )
}
