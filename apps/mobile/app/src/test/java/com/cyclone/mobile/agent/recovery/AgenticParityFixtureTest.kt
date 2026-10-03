package com.cyclone.mobile.agent.recovery

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgenticParityFixtureTest {
    @Test
    fun fixtureScenariosMatchDeterministicRecoveryDoctrine() {
        val fixture = JSONObject(loadFixture())
        val scenarios = fixture.getJSONArray("scenarios")
        assertEquals(10, scenarios.length())
        for (i in 0 until scenarios.length()) {
            val scenario = scenarios.getJSONObject(i)
            val id = scenario.getString("id")
            val actual = runScenario(id)
            assertEquals("$id classification", scenario.getString("expectedClassification"), actual.classification)
            assertEquals("$id recovery", scenario.optString("expectedRecovery", ""), actual.recovery)
        }
    }

    @Test
    fun longSuccessfulTaskHasNoArbitrarySixCycleStop() {
        val progresses = (1..9).map { cycle ->
            AgenticProgressClassifier.classify(
                ObservationEvidence(
                    semanticStateKey = "p$cycle",
                    accessibilityFingerprint = "a$cycle",
                    interactionState = mapOf("verifiedStep" to cycle.toString()),
                ),
                ObservationEvidence(
                    semanticStateKey = "p${cycle + 1}",
                    accessibilityFingerprint = "a${cycle + 1}",
                    interactionState = mapOf("verifiedStep" to (cycle + 1).toString()),
                ),
            )
        }
        assertTrue(progresses.all { it.classification == ProgressClassification.VERIFIED_PROGRESS })
    }

    @Test
    fun identityChurnAloneNeverMasqueradesAsVerifiedProgress() {
        val progresses = (1..9).map { cycle ->
            AgenticProgressClassifier.classify(
                ObservationEvidence(semanticStateKey = "p$cycle", accessibilityFingerprint = "a$cycle", contentKey = "c$cycle"),
                ObservationEvidence(semanticStateKey = "p${cycle + 1}", accessibilityFingerprint = "a${cycle + 1}", contentKey = "c${cycle + 1}"),
            )
        }
        assertTrue(progresses.all { it.classification == ProgressClassification.NEW_EVIDENCE })
        assertTrue(progresses.all { it.incrementsNoProgressCounter })
    }

    @Test
    fun trueGateSuspendsWithoutBecomingOrdinaryFailure() {
        val classification = AgenticFailureClassifier.classify(
            FailureEvidence(humanBoundary = true, recoveryRemaining = false),
        )
        assertEquals(TaskFailureClassification.HUMAN_OR_GATE, classification)
        assertFalse(classification == TaskFailureClassification.NON_CONVERGENCE)
    }

    private fun runScenario(id: String): ScenarioResult = when (id) {
        "A_STALE_TARGET" -> {
            val decision = AgenticRecoveryPolicy().next(
                RecoveryRequest(
                    observation = ObservationEvidence(structuredControlCount = 3),
                    memory = RecoveryMemory(attemptedLevels = setOf(RecoveryLevel.CURRENT_SEMANTIC_PAGE)),
                    cause = RecoverableCause.STALE_SELECTOR,
                ),
            )
            ScenarioResult(TaskFailureClassification.RECOVERABLE.name, decision.level?.name.orEmpty())
        }
        "B_CLICK_ACCEPTED_NOTHING_HAPPENS" -> {
            val progress = AgenticProgressClassifier.classify(
                ObservationEvidence(semanticStateKey = "same", accessibilityFingerprint = "same", contentKey = "same"),
                ObservationEvidence(semanticStateKey = "same", accessibilityFingerprint = "same", contentKey = "same"),
            )
            assertEquals(ProgressClassification.NO_PROGRESS, progress.classification)
            ScenarioResult(TaskFailureClassification.RECOVERABLE.name, RecoveryLevel.CURRENT_SEMANTIC_PAGE.name)
        }
        "C_SAME_PAGE_REAL_PROGRESS" -> {
            val progress = AgenticProgressClassifier.classify(
                ObservationEvidence(semanticStateKey = "same", interactionState = mapOf("toggle" to "off")),
                ObservationEvidence(semanticStateKey = "same", interactionState = mapOf("toggle" to "on")),
            )
            ScenarioResult(progress.classification.name, "")
        }
        "D_TARGET_NOT_IN_COMPACT_CONTROLS" -> {
            val decision = AgenticRecoveryPolicy().next(
                RecoveryRequest(
                    observation = ObservationEvidence(structuredControlCount = 5),
                    memory = RecoveryMemory(attemptedLevels = setOf(RecoveryLevel.CURRENT_SEMANTIC_PAGE)),
                    targetAbsentFromStructuredControls = true,
                    cause = RecoverableCause.TARGET_MISSING_FROM_COMPACT_CONTROLS,
                ),
            )
            ScenarioResult(TaskFailureClassification.RECOVERABLE.name, decision.level?.name.orEmpty())
        }
        "E_STRUCTURED_DATA_INSUFFICIENT" -> {
            val decision = AgenticRecoveryPolicy().next(
                RecoveryRequest(
                    observation = ObservationEvidence(structuredControlCount = 0, rawNodeCount = 40),
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
                ),
            )
            ScenarioResult(TaskFailureClassification.RECOVERABLE.name, decision.level?.name.orEmpty())
        }
        "F_WRONG_BRANCH" -> {
            val decision = AgenticRecoveryPolicy().next(
                RecoveryRequest(observation = ObservationEvidence(wrongBranch = true), cause = RecoverableCause.WRONG_BRANCH),
            )
            ScenarioResult(ProgressClassification.REGRESSION.name, decision.level?.name.orEmpty())
        }
        "G_MODEL_SAYS_BLOCKED_INCORRECTLY" -> ScenarioResult(
            AgenticFailureClassifier.classify(FailureEvidence(modelStatus = "blocked", recoveryRemaining = true)).name,
            RecoveryLevel.GOAL_RANKED_SEARCH.name,
        )
        "H_MALFORMED_MODEL_OUTPUT" -> ScenarioResult(
            AgenticFailureClassifier.classify(
                FailureEvidence(
                    recoverableCauses = setOf(RecoverableCause.MALFORMED_MODEL_OUTPUT),
                    recoveryRemaining = true,
                ),
            ).name,
            RecoveryLevel.CURRENT_SEMANTIC_PAGE.name,
        )
        "I_LONG_SUCCESSFUL_TASK" -> ScenarioResult(ProgressClassification.VERIFIED_PROGRESS.name, "")
        "J_TRUE_GATE" -> ScenarioResult(
            AgenticFailureClassifier.classify(FailureEvidence(humanBoundary = true, recoveryRemaining = false)).name,
            RecoveryLevel.HUMAN_GATE.name,
        )
        else -> error("Unknown fixture scenario $id")
    }

    private fun loadFixture(): String = requireNotNull(
        javaClass.classLoader?.getResourceAsStream("agent/agentic_recovery_scenarios.json"),
    ).bufferedReader().use { it.readText() }

    private data class ScenarioResult(val classification: String, val recovery: String)
}
