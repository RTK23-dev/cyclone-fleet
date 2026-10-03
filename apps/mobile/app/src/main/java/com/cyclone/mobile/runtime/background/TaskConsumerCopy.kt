package com.cyclone.mobile.runtime.background

/** Provider prose is never used as consumer status. */
object TaskConsumerCopy {
    fun subtitle(task: WorkspaceTaskUi): String {
        task.interruption?.let { return it.prompt }
        task.plannedStages.firstOrNull {
            it.status == OutcomeStageStatus.ACTIVE || it.status == OutcomeStageStatus.NEEDS_INPUT
        }?.collapsedLine?.let { return it }
        task.semanticSteps.lastOrNull()?.takeIf { it.state == SemanticStepState.ACTIVE }?.let { return it.label }
        task.plannedMilestones
            .getOrNull(task.plannedMilestoneIndex.coerceAtLeast(0))
            ?.takeIf(String::isNotBlank)
            ?.let { return it }
        return when (task.phase) {
            TaskPhase.STARTING -> "Getting your task ready"
            TaskPhase.WORKING -> "Checking the current page"
            TaskPhase.HUMAN -> "Finish your changes, then select I'm Done."
            TaskPhase.PAUSED -> "Your task is paused."
            TaskPhase.REVIEW -> "Review the current page to continue."
            TaskPhase.DONE -> "The requested result was checked."
            TaskPhase.FAILED -> "The task could not finish."
            TaskPhase.STOPPED -> "The task was stopped."
        }
    }
}
