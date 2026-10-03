package com.cyclone.mobile.agent.recovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgenticRecoveryPolicyTest {
    @Test
    fun samePageInteractionChangeIsVerifiedProgress() {
        val before = ObservationEvidence(
            semanticStateKey = "settings:pip",
            accessibilityFingerprint = "a1",
            contentKey = "c1",
            interactionState = mapOf("pip_switch" to "checked=false"),
        )
        val after = before.copy(interactionState = mapOf("pip_switch" to "checked=true"))

        val result = AgenticProgressClassifier.classify(before, after)

        assertEquals(ProgressClassification.VERIFIED_PROGRESS, result.classification)
        assertFalse(result.incrementsNoProgressCounter)
        assertTrue("interaction_state_changed" in result.reasons)
    }

    @Test
    fun newEvidenceWithoutStateChangeDoesNotPretendActionProgress() {
        val before = ObservationEvidence(
            semanticStateKey = "same",
            accessibilityFingerprint = "same",
            contentKey = "same",
            collectedEvidence = setOf(EvidenceSource.CURRENT_SEMANTIC_PAGE),
        )
        val after = before.copy(collectedEvidence = before.collectedEvidence + EvidenceSource.SEMANTIC_SEARCH)

        assertEquals(ProgressClassification.NEW_EVIDENCE, AgenticProgressClassifier.classify(before, after).classification)
    }

    @Test
    fun appGraphMoveAwayFromGoalIsRegression() {
        val before = ObservationEvidence(appGraphDistanceToGoal = 2)
        val after = ObservationEvidence(appGraphDistanceToGoal = 4)

        assertEquals(ProgressClassification.REGRESSION, AgenticProgressClassifier.classify(before, after).classification)
    }

    @Test
    fun modelBlockedIsRecoverableWhileRecoveryExists() {
        val classification = AgenticFailureClassifier.classify(
            FailureEvidence(modelStatus = "blocked", recoveryRemaining = true),
        )

        assertEquals(TaskFailureClassification.RECOVERABLE, classification)
    }

    @Test
    fun hardBlockerRequiresConcreteEvidence() {
        assertEquals(
            TaskFailureClassification.HARD_BLOCKER,
            AgenticFailureClassifier.classify(
                FailureEvidence(
                    recoveryRemaining = false,
                    hardBlockerEvidence = setOf("accessibility_capability_unavailable"),
                ),
            ),
        )
    }

    @Test
    fun visionRequiresTriggerAndIsBoundedPerUnchangedState() {
        val policy = AgenticVisionPolicy(maxCapturesPerUnchangedSemanticState = 1)
        assertFalse(policy.evaluate(VisionContext()).eligible)
        assertTrue(policy.evaluate(VisionContext(semanticSearchExhausted = true)).eligible)
        assertFalse(
            policy.evaluate(
                VisionContext(
                    semanticSearchExhausted = true,
                    capturesForUnchangedSemanticState = 1,
                ),
            ).eligible,
        )
        assertTrue(
            policy.evaluate(
                VisionContext(
                    semanticSearchExhausted = true,
                    capturesForUnchangedSemanticState = 1,
                    semanticStateChangedSinceLastCapture = true,
                ),
            ).eligible,
        )
    }

    @Test
    fun targetMissingUsesSearchBeforeScreenshot() {
        val request = RecoveryRequest(
            observation = ObservationEvidence(structuredControlCount = 4, rawNodeCount = 30),
            memory = RecoveryMemory(attemptedLevels = setOf(RecoveryLevel.CURRENT_SEMANTIC_PAGE)),
            cause = RecoverableCause.TARGET_MISSING_FROM_COMPACT_CONTROLS,
            targetAbsentFromStructuredControls = true,
        )

        assertEquals(RecoveryLevel.GOAL_RANKED_SEARCH, AgenticRecoveryPolicy().next(request).level)
    }

    @Test
    fun semanticExhaustionCanEscalateToSilentVisionAfterCheaperLayers() {
        val request = RecoveryRequest(
            observation = ObservationEvidence(structuredControlCount = 0, rawNodeCount = 50),
            memory = RecoveryMemory(
                attemptedLevels = setOf(
                    RecoveryLevel.CURRENT_SEMANTIC_PAGE,
                    RecoveryLevel.GOAL_RANKED_SEARCH,
                    RecoveryLevel.ADDITIONAL_ELEMENT_INSPECTION,
                    RecoveryLevel.BOUNDED_PAGE_EXPLORATION,
                ),
                semanticSearchExhausted = true,
            ),
            targetAbsentFromStructuredControls = true,
        )

        val decision = AgenticRecoveryPolicy().next(request)
        assertEquals(RecoveryLevel.SILENT_SCREENSHOT_VISION, decision.level)
        assertTrue(VisionTrigger.SEMANTIC_SEARCH_EXHAUSTED in decision.visionTriggers)
    }

    @Test
    fun wrongBranchCanJumpDirectlyToBacktrack() {
        val request = RecoveryRequest(
            observation = ObservationEvidence(wrongBranch = true),
            cause = RecoverableCause.WRONG_BRANCH,
        )

        assertEquals(RecoveryLevel.BACKTRACK_OR_REPLAN, AgenticRecoveryPolicy().next(request).level)
    }

    @Test
    fun humanGateIsSelectedOnlyForObservedBoundary() {
        val ordinary = RecoveryRequest(
            observation = ObservationEvidence(),
            memory = RecoveryMemory(attemptedLevels = RecoveryLevel.entries.toSet()),
            semanticSearchAvailable = false,
            supplementalInspectionAvailable = false,
            boundedExplorationAvailable = false,
            backtrackOrAlternateBranchAvailable = false,
        )
        assertEquals(null, AgenticRecoveryPolicy().next(ordinary).level)

        val gate = ordinary.copy(observation = ObservationEvidence(humanBoundary = true))
        assertEquals(RecoveryLevel.HUMAN_GATE, AgenticRecoveryPolicy().next(gate).level)
    }
}
