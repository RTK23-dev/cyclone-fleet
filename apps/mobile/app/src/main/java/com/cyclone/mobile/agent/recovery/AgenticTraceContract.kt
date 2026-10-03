package com.cyclone.mobile.agent.recovery

enum class AgenticTraceEventType {
    TASK_STARTED,
    OBSERVATION,
    KNOWN_ROUTE_LOOKUP,
    SEMANTIC_SEARCH,
    ELEMENT_INSPECTION,
    VISION_CAPTURE,
    MODEL_DECISION,
    ACTION_REQUESTED,
    ANDROID_EXECUTION,
    AFTER_OBSERVATION,
    VERIFICATION,
    PROGRESS_CLASSIFIED,
    RECOVERY_SELECTED,
    BACKTRACK,
    GATE_SUSPEND,
    GATE_RESUME,
    LEARNING_ACCEPTED,
    LEARNING_REJECTED,
    TASK_COMPLETE,
    TASK_BLOCKED,
    TASK_NON_CONVERGENCE,
    TASK_CANCELLED,
}

data class AgenticTraceEvent(
    val type: AgenticTraceEventType,
    val cycle: Int = 0,
    val semanticStateKey: String? = null,
    val verified: Boolean? = null,
    val progress: ProgressClassification? = null,
    val code: String? = null,
)

object AgenticTraceContract {
    fun violations(events: List<AgenticTraceEvent>): List<String> {
        if (events.isEmpty()) return listOf("trace_empty")
        val errors = mutableListOf<String>()
        if (events.first().type != AgenticTraceEventType.TASK_STARTED) errors += "task_started_must_be_first"

        events.forEachIndexed { index, event ->
            when (event.type) {
                AgenticTraceEventType.ACTION_REQUESTED -> validateActionCycle(events, index, event.cycle, errors)
                AgenticTraceEventType.LEARNING_ACCEPTED -> validateLearning(events, index, event.cycle, errors)
                AgenticTraceEventType.TASK_COMPLETE -> validateCompletion(events, index, errors)
                AgenticTraceEventType.GATE_RESUME -> {
                    val suspended = events.take(index).any { it.type == AgenticTraceEventType.GATE_SUSPEND }
                    if (!suspended) errors += "gate_resume_without_suspend@$index"
                }
                else -> Unit
            }
        }

        val captureIndicesByState = events.withIndex()
            .filter { it.value.type == AgenticTraceEventType.VISION_CAPTURE }
            .groupBy({ it.value.semanticStateKey ?: "<unknown>" }, { it.index })
        captureIndicesByState.forEach { (state, captureIndices) ->
            if (state != "<unknown>" && captureIndices.size > 1) {
                val progressBetween = captureIndices.zipWithNext().all { (firstIndex, secondIndex) ->
                    events.subList(firstIndex + 1, secondIndex).any {
                        it.type == AgenticTraceEventType.PROGRESS_CLASSIFIED &&
                            it.progress == ProgressClassification.VERIFIED_PROGRESS
                    }
                }
                if (!progressBetween) errors += "vision_repolled_unchanged_state:$state"
            }
        }
        return errors.distinct()
    }

    private fun validateActionCycle(
        events: List<AgenticTraceEvent>,
        requestIndex: Int,
        cycle: Int,
        errors: MutableList<String>,
    ) {
        val nextRequest = events.indexOfFirstAfter(requestIndex) { it.type == AgenticTraceEventType.ACTION_REQUESTED }
            .let { if (it == -1) events.size else it }
        val segment = events.subList(requestIndex + 1, nextRequest)
        val execution = segment.indexOfFirst { it.type == AgenticTraceEventType.ANDROID_EXECUTION && it.cycle == cycle }
        val after = segment.indexOfFirst { it.type == AgenticTraceEventType.AFTER_OBSERVATION && it.cycle == cycle }
        val verification = segment.indexOfFirst { it.type == AgenticTraceEventType.VERIFICATION && it.cycle == cycle }
        if (execution < 0) errors += "action_missing_android_execution@$requestIndex"
        if (after < 0) errors += "action_missing_after_observation@$requestIndex"
        if (verification < 0) errors += "action_missing_verification@$requestIndex"
        if (execution >= 0 && after >= 0 && verification >= 0 && !(execution < after && after < verification)) {
            errors += "action_evidence_order_invalid@$requestIndex"
        }
    }

    private fun validateLearning(
        events: List<AgenticTraceEvent>,
        learningIndex: Int,
        cycle: Int,
        errors: MutableList<String>,
    ) {
        val prior = events.take(learningIndex)
        val verificationIndex = prior.indexOfLast {
            it.type == AgenticTraceEventType.VERIFICATION && it.cycle == cycle && it.verified == true
        }
        val progressIndex = prior.indexOfLast {
            it.type == AgenticTraceEventType.PROGRESS_CLASSIFIED && it.cycle == cycle &&
                it.progress == ProgressClassification.VERIFIED_PROGRESS
        }
        if (verificationIndex < 0) errors += "learning_without_verified_action@$learningIndex"
        if (progressIndex < 0) errors += "learning_without_verified_progress@$learningIndex"
        if (verificationIndex >= 0 && progressIndex >= 0 && verificationIndex > progressIndex) {
            errors += "learning_progress_must_follow_verification@$learningIndex"
        }
    }

    private fun validateCompletion(events: List<AgenticTraceEvent>, completionIndex: Int, errors: MutableList<String>) {
        val prior = events.take(completionIndex)
        val hasVerifiedAssertion = prior.any {
            it.type == AgenticTraceEventType.VERIFICATION && it.verified == true
        }
        val hasProgressEvidence = prior.any {
            it.type == AgenticTraceEventType.PROGRESS_CLASSIFIED &&
                it.progress == ProgressClassification.VERIFIED_PROGRESS
        }
        if (!hasVerifiedAssertion || !hasProgressEvidence) errors += "task_complete_without_observed_completion_evidence@$completionIndex"
    }

    private inline fun List<AgenticTraceEvent>.indexOfFirstAfter(
        index: Int,
        predicate: (AgenticTraceEvent) -> Boolean,
    ): Int {
        for (i in index + 1 until size) if (predicate(this[i])) return i
        return -1
    }
}
