package com.cyclone.mobile.runtime.background

/**
 * Consumer-facing projection of a task.
 *
 * This is deliberately read-only: it never executes tools, changes controller ownership, or
 * manufactures verification. Every field is derived from the authoritative WorkspaceTaskUi /
 * TaskHarnessState evidence already owned by the runtime.
 */
enum class TaskConsumerState(val wireValue: String) {
    WORKING("working"),
    ACTION_NEEDED("action-needed"),
    NEEDS_SECRET("needs-secret"),
    DONE("done"),
    FAILED("failed"),
}

data class TaskPresentationMilestone(
    val label: String,
    val state: SemanticStepState,
)

/**
 * Consumer compaction only. The authoritative semanticSteps list stays untouched for diagnostics.
 * Adjacent operations with the same safe label are folded into one visual milestone so repeated
 * verified taps/scrolls do not turn the chat card into an execution log.
 */
object TaskMilestoneProjector {
    fun project(steps: List<SemanticTaskStep>): List<TaskPresentationMilestone> {
        val out = mutableListOf<TaskPresentationMilestone>()
        steps.forEach { step ->
            val label = step.label.trim().take(90)
            if (label.isBlank()) return@forEach
            val previous = out.lastOrNull()
            if (previous != null && previous.label.equals(label, ignoreCase = true)) {
                out[out.lastIndex] = TaskPresentationMilestone(label, merge(previous.state, step.state))
            } else {
                out += TaskPresentationMilestone(label, step.state)
            }
        }
        return out.takeLast(8)
    }

    private fun merge(previous: SemanticStepState, next: SemanticStepState): SemanticStepState = when {
        next == SemanticStepState.ACTION_NEEDED -> next
        next == SemanticStepState.FAILED -> next
        next == SemanticStepState.ACTIVE -> next
        next == SemanticStepState.DONE -> SemanticStepState.DONE
        previous == SemanticStepState.DONE -> previous
        else -> next
    }
}

enum class TaskFollowUpAction {
    VIEW_DETAILS,
    RUN_AGAIN,
    TRY_AGAIN,
    OPEN_APP,
    TAKE_OVER,
    AUTOFILL,
    CONTINUE,
}

data class TaskPresentationSnapshot(
    val taskId: String,
    val app: String,
    val packageName: String,
    val title: String,
    val state: TaskConsumerState,
    val currentMilestone: String?,
    val milestones: List<TaskPresentationMilestone>,
    val completedMilestones: List<String>,
    val completedCount: Int,
    val totalCount: Int?,
    /** Null means the runtime does not yet know a stable denominator. */
    val progressFraction: Float?,
    val supportingCopy: String?,
    val outcomeCopy: String?,
    val followUps: List<TaskFollowUpAction>,
    val stages: List<OutcomeStage> = emptyList(),
    val destinationChain: String? = null,
    val collapsedSummary: String? = null,
    val runInformation: TaskRunInformation? = null,
    val traceSessionId: String? = null,
)

object TaskPresentationProjector {
    fun project(
        task: WorkspaceTaskUi,
        runInformation: TaskRunInformation? = TaskRunInformationProjector.fromTask(task),
    ): TaskPresentationSnapshot {
        val state = if (
            task.interruption?.kind == TaskInterruptionKind.NEEDS_SECRET &&
            task.phase in setOf(TaskPhase.PAUSED, TaskPhase.REVIEW, TaskPhase.HUMAN)
        ) {
            TaskConsumerState.NEEDS_SECRET
        } else when (task.phase) {
            TaskPhase.STARTING, TaskPhase.WORKING -> TaskConsumerState.WORKING
            TaskPhase.PAUSED, TaskPhase.REVIEW, TaskPhase.HUMAN -> TaskConsumerState.ACTION_NEEDED
            TaskPhase.DONE -> TaskConsumerState.DONE
            TaskPhase.FAILED, TaskPhase.STOPPED -> TaskConsumerState.FAILED
        }

        val semantic = task.semanticSteps
        val compacted = TaskMilestoneProjector.project(semantic).filter { milestone ->
            milestone.state != SemanticStepState.FAILED || state == TaskConsumerState.FAILED
        }
        val stages = task.plannedStages
        val presentationMilestones = when {
            stages.isNotEmpty() -> stages.map { stage ->
                TaskPresentationMilestone(stage.expandedLine, semanticState(stage.status))
            }
            task.plannedMilestones.any(String::isNotBlank) -> {
                val planned = task.plannedMilestones.filter(String::isNotBlank)
                val rawPlanIndex = when (state) {
                    TaskConsumerState.DONE -> planned.size
                    else -> task.plannedMilestoneIndex.coerceIn(0, planned.size)
                }
                val planIndex = when {
                    state == TaskConsumerState.DONE -> planned.size
                    planned.isNotEmpty() && rawPlanIndex >= planned.size -> planned.lastIndex
                    else -> rawPlanIndex
                }
                planned.mapIndexed { index, label ->
                    val milestoneState = when {
                        state == TaskConsumerState.DONE -> SemanticStepState.DONE
                        index < planIndex -> SemanticStepState.DONE
                        index > planIndex -> SemanticStepState.PENDING
                        state == TaskConsumerState.ACTION_NEEDED || state == TaskConsumerState.NEEDS_SECRET ->
                            SemanticStepState.ACTION_NEEDED
                        state == TaskConsumerState.FAILED -> SemanticStepState.FAILED
                        else -> SemanticStepState.ACTIVE
                    }
                    TaskPresentationMilestone(label, milestoneState)
                }
            }
            else -> compacted
        }
        val total = when {
            stages.isNotEmpty() -> stages.size
            task.plannedMilestones.any(String::isNotBlank) -> task.plannedMilestones.count(String::isNotBlank)
            else -> null
        }
        val completedCount = when {
            stages.isNotEmpty() -> stages.count { it.status == OutcomeStageStatus.COMPLETED }
            else -> presentationMilestones.count { it.state == SemanticStepState.DONE }
        }
        val fraction = total?.takeIf { it > 0 }?.let { denominator ->
            (completedCount.toFloat() / denominator.toFloat()).coerceIn(0f, 1f)
        } ?: if (state == TaskConsumerState.DONE) 1f else null

        val activeStage = stages.firstOrNull {
            it.status in setOf(OutcomeStageStatus.ACTIVE, OutcomeStageStatus.NEEDS_INPUT, OutcomeStageStatus.FAILED)
        } ?: stages.lastOrNull { it.status == OutcomeStageStatus.COMPLETED }

        val currentMilestone = activeStage?.collapsedLine
            ?: compacted.lastOrNull {
                it.state == SemanticStepState.ACTIVE || it.state == SemanticStepState.ACTION_NEEDED
            }?.label
            ?: presentationMilestones.firstOrNull {
                it.state == SemanticStepState.ACTIVE || it.state == SemanticStepState.ACTION_NEEDED
            }?.label
            ?: task.subtitle.takeIf(String::isNotBlank)

        val destinationChain = stages.joinToString(" → ") { it.destinationLabel }.takeIf { stages.size >= 2 }
            ?: stages.singleOrNull()?.destinationLabel

        val supportingCopy = when (state) {
            TaskConsumerState.WORKING -> when {
                total != null && stages.isNotEmpty() -> "$completedCount of $total stages complete"
                total != null -> "$completedCount of $total complete"
                compacted.any { it.state == SemanticStepState.DONE } -> {
                    val verified = compacted.count { it.state == SemanticStepState.DONE }
                    "$verified verified step${if (verified == 1) "" else "s"} complete"
                }
                else -> currentMilestone
            }
            TaskConsumerState.ACTION_NEEDED -> OutcomeStageCopy.attention(activeStage, task.interruption)
                .let { copy ->
                    task.confirmation?.explanation?.takeIf { it.isNotBlank() } ?: copy
                }
            TaskConsumerState.NEEDS_SECRET -> task.interruption?.prompt
                ?: "Secure input is required to continue."
            TaskConsumerState.DONE -> task.outcome?.takeIf(String::isNotBlank)
                ?: "The requested result was checked."
            TaskConsumerState.FAILED -> task.outcome?.takeIf(String::isNotBlank)
                ?: OutcomeStageCopy.terminalFailure(stages, task.subtitle, task.resumable)
        }

        val collapsedSummary = buildString {
            destinationChain?.let { append(it) }
            currentMilestone?.takeIf { it.isNotBlank() }?.let { current ->
                if (isNotEmpty()) append(" · ")
                append(current)
            }
            if (state == TaskConsumerState.ACTION_NEEDED || state == TaskConsumerState.NEEDS_SECRET) {
                if (isNotEmpty()) append(" · ")
                append(if (state == TaskConsumerState.NEEDS_SECRET) "Needs secure input" else "Needs your input")
            }
            total?.let {
                if (isNotEmpty()) append('\n')
                append("$completedCount of $it stages complete")
            }
            runInformation?.elapsedLabel?.let { elapsed ->
                if (isNotEmpty()) append(" · ")
                append(elapsed)
                if (runInformation.waiting) append(" waiting")
            }
        }.takeIf { it.isNotBlank() }

        return TaskPresentationSnapshot(
            taskId = task.taskId,
            app = task.app,
            packageName = stages.firstOrNull()?.destinationPackage ?: task.packageName,
            title = if (stages.isNotEmpty()) OutcomeStageCopy.title(task.goal, stages) else consumerTaskTitle(task),
            state = state,
            currentMilestone = currentMilestone,
            milestones = presentationMilestones,
            completedMilestones = presentationMilestones
                .filter { it.state == SemanticStepState.DONE }
                .map { it.label }
                .takeLast(4),
            completedCount = completedCount,
            totalCount = total,
            progressFraction = fraction,
            supportingCopy = supportingCopy,
            outcomeCopy = task.outcome?.takeIf(String::isNotBlank),
            followUps = TaskFollowUpPolicy.actions(task, state),
            stages = stages,
            destinationChain = destinationChain,
            collapsedSummary = collapsedSummary,
            runInformation = runInformation,
            traceSessionId = task.traceSessionId,
        )
    }

    fun semanticState(status: OutcomeStageStatus): SemanticStepState = when (status) {
        OutcomeStageStatus.COMPLETED -> SemanticStepState.DONE
        OutcomeStageStatus.ACTIVE -> SemanticStepState.ACTIVE
        OutcomeStageStatus.NEEDS_INPUT -> SemanticStepState.ACTION_NEEDED
        OutcomeStageStatus.FAILED -> SemanticStepState.FAILED
        else -> SemanticStepState.PENDING
    }

    private fun consumerTaskTitle(task: WorkspaceTaskUi): String {
        val clean = task.goal.trim().replace(Regex("\\s+"), " ")
        if (clean.isBlank()) return task.app.ifBlank { "Phone task" }
        val app = task.app.takeIf { it.isNotBlank() && !it.equals("Other", true) && !it.equals("your app", true) }
        val lower = clean.lowercase()
        return when {
            "battery" in lower && ("setting" in lower || app.equals("Settings", true)) ->
                "Checking battery settings"
            Regex("\\b(logged? ?in|log ?in|login|signed? ?in|sign ?in)\\b", RegexOption.IGNORE_CASE)
                .containsMatchIn(clean) -> "Checking ${app ?: "app"} login status"
            app != null && Regex("\\b(open|launch|start)\\b", RegexOption.IGNORE_CASE).containsMatchIn(clean) ->
                "Opening $app"
            app != null && Regex("\\b(check|verify|see|review)\\b", RegexOption.IGNORE_CASE).containsMatchIn(clean) ->
                "Checking $app"
            app != null && Regex("\\b(find|search|look for)\\b", RegexOption.IGNORE_CASE).containsMatchIn(clean) ->
                "Searching $app"
            app != null -> "Task in $app"
            else -> "Phone task"
        }
    }
}

object TaskFollowUpPolicy {
    fun actions(task: WorkspaceTaskUi, state: TaskConsumerState): List<TaskFollowUpAction> = buildList {
        val interactiveSession = !task.sessionId.isNullOrBlank() && task.displayId != null
        when (state) {
            TaskConsumerState.WORKING -> Unit
            TaskConsumerState.ACTION_NEEDED -> {
                val interruption = task.interruption
                val resumableHumanBoundary =
                    interactiveSession &&
                        task.resumable &&
                        task.confirmation == null &&
                        task.phase in setOf(TaskPhase.HUMAN, TaskPhase.PAUSED, TaskPhase.REVIEW)

                if (resumableHumanBoundary && interruption?.canAutofill == true) {
                    add(TaskFollowUpAction.AUTOFILL)
                }
                if (
                    interactiveSession &&
                    interruption?.canTakeOver == true &&
                    task.phase !in setOf(TaskPhase.HUMAN, TaskPhase.DONE, TaskPhase.FAILED, TaskPhase.STOPPED)
                ) {
                    add(TaskFollowUpAction.TAKE_OVER)
                }
                if (resumableHumanBoundary && interruption?.canResumeAfterHuman == true) {
                    add(TaskFollowUpAction.CONTINUE)
                }
            }
            TaskConsumerState.NEEDS_SECRET -> Unit
            TaskConsumerState.DONE -> {
                if (task.packageName.isNotBlank()) add(TaskFollowUpAction.OPEN_APP)
                add(TaskFollowUpAction.RUN_AGAIN)
            }
            TaskConsumerState.FAILED -> {
                add(TaskFollowUpAction.TRY_AGAIN)
            }
        }
    }
}
