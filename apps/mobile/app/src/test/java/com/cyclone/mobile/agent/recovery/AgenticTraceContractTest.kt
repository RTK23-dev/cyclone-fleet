package com.cyclone.mobile.agent.recovery

import org.junit.Assert.assertTrue
import org.junit.Test

class AgenticTraceContractTest {
    @Test
    fun learningRequiresActionExecutionAfterObservationVerificationAndProgress() {
        val events = listOf(
            e(AgenticTraceEventType.TASK_STARTED),
            e(AgenticTraceEventType.OBSERVATION),
            e(AgenticTraceEventType.ACTION_REQUESTED, cycle = 1),
            e(AgenticTraceEventType.ANDROID_EXECUTION, cycle = 1),
            e(AgenticTraceEventType.AFTER_OBSERVATION, cycle = 1),
            e(AgenticTraceEventType.VERIFICATION, cycle = 1, verified = true),
            e(AgenticTraceEventType.PROGRESS_CLASSIFIED, cycle = 1, progress = ProgressClassification.VERIFIED_PROGRESS),
            e(AgenticTraceEventType.LEARNING_ACCEPTED, cycle = 1),
            e(AgenticTraceEventType.TASK_COMPLETE),
        )

        assertTrue(AgenticTraceContract.violations(events).isEmpty())
    }

    @Test
    fun androidExecutionSuccessAloneCannotBeLearned() {
        val events = listOf(
            e(AgenticTraceEventType.TASK_STARTED),
            e(AgenticTraceEventType.OBSERVATION),
            e(AgenticTraceEventType.ACTION_REQUESTED, cycle = 1),
            e(AgenticTraceEventType.ANDROID_EXECUTION, cycle = 1),
            e(AgenticTraceEventType.AFTER_OBSERVATION, cycle = 1),
            e(AgenticTraceEventType.VERIFICATION, cycle = 1, verified = false),
            e(AgenticTraceEventType.PROGRESS_CLASSIFIED, cycle = 1, progress = ProgressClassification.NO_PROGRESS),
            e(AgenticTraceEventType.LEARNING_ACCEPTED, cycle = 1),
        )

        val violations = AgenticTraceContract.violations(events)
        assertTrue(violations.any { it.startsWith("learning_without_verified_action") })
        assertTrue(violations.any { it.startsWith("learning_without_verified_progress") })
    }

    @Test
    fun actionEvidenceOrderingIsMandatory() {
        val events = listOf(
            e(AgenticTraceEventType.TASK_STARTED),
            e(AgenticTraceEventType.ACTION_REQUESTED, cycle = 2),
            e(AgenticTraceEventType.VERIFICATION, cycle = 2, verified = true),
            e(AgenticTraceEventType.ANDROID_EXECUTION, cycle = 2),
            e(AgenticTraceEventType.AFTER_OBSERVATION, cycle = 2),
        )

        assertTrue(AgenticTraceContract.violations(events).any { it.startsWith("action_evidence_order_invalid") })
    }

    @Test
    fun completionRequiresObservedVerificationAndProgress() {
        val events = listOf(
            e(AgenticTraceEventType.TASK_STARTED),
            e(AgenticTraceEventType.MODEL_DECISION),
            e(AgenticTraceEventType.TASK_COMPLETE),
        )

        assertTrue(AgenticTraceContract.violations(events).any { it.startsWith("task_complete_without_observed_completion_evidence") })
    }

    @Test
    fun repeatedVisionCaptureOnSameStateRequiresProgressBetweenCaptures() {
        val events = listOf(
            e(AgenticTraceEventType.TASK_STARTED),
            e(AgenticTraceEventType.VISION_CAPTURE, state = "same"),
            e(AgenticTraceEventType.MODEL_DECISION),
            e(AgenticTraceEventType.VISION_CAPTURE, state = "same"),
        )

        assertTrue(AgenticTraceContract.violations(events).any { it == "vision_repolled_unchanged_state:same" })
    }

    private fun e(
        type: AgenticTraceEventType,
        cycle: Int = 0,
        state: String? = null,
        verified: Boolean? = null,
        progress: ProgressClassification? = null,
    ) = AgenticTraceEvent(type, cycle, state, verified, progress)
}
