package com.cyclone.mobile.runtime.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskPresentationSnapshotTest {
    private fun task(
        phase: TaskPhase = TaskPhase.WORKING,
        semantic: List<SemanticTaskStep> = emptyList(),
        interruption: TaskInterruption? = null,
        outcome: String? = null,
        resumable: Boolean = true,
    ) = WorkspaceTaskUi(
        taskId = "task-1",
        sessionId = "session-1",
        app = "Instagram",
        packageName = "com.instagram.android",
        goal = "Open Instagram and check my login status",
        phase = phase,
        semanticSteps = semantic,
        interruption = interruption,
        displayId = 0,
        outcome = outcome,
        resumable = resumable,
    )

    @Test
    fun workingOperationStreamDoesNotInventStableProgressDenominator() {
        val snapshot = TaskPresentationProjector.project(
            task(
                semantic = listOf(
                    SemanticTaskStep(1, "Opening Instagram", SemanticStepState.DONE),
                    SemanticTaskStep(2, "Checking login status", SemanticStepState.ACTIVE),
                ),
            ),
        )

        assertEquals(TaskConsumerState.WORKING, snapshot.state)
        assertEquals("Checking Instagram login status", snapshot.title)
        assertEquals("Checking login status", snapshot.currentMilestone)
        assertEquals(1, snapshot.completedCount)
        assertNull(snapshot.totalCount)
        assertNull(snapshot.progressFraction)
        assertTrue(snapshot.supportingCopy!!.contains("1 verified step"))
    }

    @Test
    fun recoverableFailedOperationDoesNotRenderAsRedMilestoneWhileTaskStillWorks() {
        val snapshot = TaskPresentationProjector.project(
            task(
                semantic = listOf(
                    SemanticTaskStep(1, "Opening Instagram", SemanticStepState.FAILED, "UNVERIFIED"),
                    SemanticTaskStep(2, "Checking login status", SemanticStepState.ACTIVE),
                ),
            ),
        )

        assertEquals(TaskConsumerState.WORKING, snapshot.state)
        assertFalse(snapshot.milestones.any { it.state == SemanticStepState.FAILED })
        assertEquals(listOf("Checking login status"), snapshot.milestones.map { it.label })
    }

    @Test
    fun typedTrajectoryPermitsDeterminateProgressWithoutCountingRawOperations() {
        val snapshot = TaskPresentationProjector.project(
            task(
                semantic = listOf(
                    SemanticTaskStep(1, "Opening Instagram", SemanticStepState.DONE),
                    SemanticTaskStep(2, "Checking login status", SemanticStepState.ACTIVE),
                ),
            ).copy(
                plannedMilestones = listOf(
                    "Opening Instagram",
                    "Clearing interruptions",
                    "Working in Instagram",
                    "Verifying the result",
                ),
                plannedMilestoneIndex = 1,
            ),
        )

        assertEquals(4, snapshot.totalCount)
        assertEquals(1, snapshot.completedCount)
        assertEquals(.25f, snapshot.progressFraction!!, .0001f)
        assertEquals("Checking login status", snapshot.currentMilestone)
        assertEquals(
            listOf(
                SemanticStepState.DONE,
                SemanticStepState.ACTIVE,
                SemanticStepState.PENDING,
                SemanticStepState.PENDING,
            ),
            snapshot.milestones.map { it.state },
        )
    }

    @Test
    fun actionNeededMarksCurrentPlannedMilestoneWithoutPretendingCompletion() {
        val snapshot = TaskPresentationProjector.project(
            task(
                phase = TaskPhase.HUMAN,
                interruption = TaskInterruption(
                    reason = "LOGIN_WALL",
                    prompt = "Finish sign-in, then continue.",
                    canResumeAfterHuman = true,
                ),
            ).copy(
                plannedMilestones = listOf("Opening Instagram", "Checking login status", "Verifying the result"),
                plannedMilestoneIndex = 1,
            ),
        )

        assertEquals(
            listOf(
                SemanticStepState.DONE,
                SemanticStepState.ACTION_NEEDED,
                SemanticStepState.PENDING,
            ),
            snapshot.milestones.map { it.state },
        )
        assertEquals(1, snapshot.completedCount)
        assertEquals(1f / 3f, snapshot.progressFraction!!, .0001f)
    }

    @Test
    fun doneStateUsesOnlyBoundedOutcomeAndOffersViewDetailsRunAgain() {
        val snapshot = TaskPresentationProjector.project(
            task(
                phase = TaskPhase.DONE,
                semantic = listOf(
                    SemanticTaskStep(1, "Opening Instagram", SemanticStepState.DONE),
                    SemanticTaskStep(2, "Checking login status", SemanticStepState.DONE),
                ),
                outcome = "You're logged in to Instagram.",
            ),
        )

        assertEquals(TaskConsumerState.DONE, snapshot.state)
        assertEquals("You're logged in to Instagram.", snapshot.outcomeCopy)
        assertEquals(1f, snapshot.progressFraction!!, .0001f)
        assertEquals(
            listOf(
                TaskFollowUpAction.OPEN_APP,
                TaskFollowUpAction.RUN_AGAIN,
            ),
            snapshot.followUps,
        )
    }

    @Test
    fun humanBoundaryExposesOnlyRuntimeAuthorizedActions() {
        val snapshot = TaskPresentationProjector.project(
            task(
                phase = TaskPhase.HUMAN,
                interruption = TaskInterruption(
                    reason = "LOGIN_WALL",
                    prompt = "Finish sign-in, then continue.",
                    canTakeOver = false,
                    canResumeAfterHuman = true,
                    canAutofill = true,
                ),
            ),
        )

        assertEquals(TaskConsumerState.ACTION_NEEDED, snapshot.state)
        assertTrue(TaskFollowUpAction.AUTOFILL in snapshot.followUps)
        assertTrue(TaskFollowUpAction.CONTINUE in snapshot.followUps)
        assertFalse(TaskFollowUpAction.TAKE_OVER in snapshot.followUps)
        assertFalse(TaskFollowUpAction.VIEW_DETAILS in snapshot.followUps)
    }

    @Test
    fun typedTrajectoryIsSanitizedBeforeEnteringConsumerState() {
        val trajectory = com.cyclone.mobile.agent.plan.TaskTrajectory(
            tier = com.cyclone.mobile.agent.plan.TaskDifficultyTier.MEDIUM,
            from = "current",
            to = "done",
            waypoints = listOf(
                com.cyclone.mobile.agent.plan.TaskWaypoint(
                    com.cyclone.mobile.agent.plan.WaypointKind.OPEN_APP,
                    packageName = "com.instagram.android",
                    summary = "RAW MODEL TEXT MUST NOT SHIP",
                ),
                com.cyclone.mobile.agent.plan.TaskWaypoint(
                    com.cyclone.mobile.agent.plan.WaypointKind.DONE,
                    summary = "also raw",
                ),
            ),
            index = 1,
            horizonPlanned = true,
        )

        val updated = TaskHarnessState.applyTrajectory(task(), trajectory)
        assertEquals(1, updated.plannedStages.size)
        assertEquals("Instagram", updated.plannedStages.single().destinationLabel)
        assertFalse(updated.plannedMilestones.any { it.contains("RAW MODEL") })
    }

    @Test
    fun loginStatusTrajectoryUsesHumanConsumerMilestonesWithoutRawModelProse() {
        val trajectory = com.cyclone.mobile.agent.plan.TaskTrajectory(
            tier = com.cyclone.mobile.agent.plan.TaskDifficultyTier.MEDIUM,
            from = "current",
            to = "done",
            waypoints = listOf(
                com.cyclone.mobile.agent.plan.TaskWaypoint(
                    com.cyclone.mobile.agent.plan.WaypointKind.OPEN_APP,
                    packageName = "com.instagram.android",
                    summary = "provider wording",
                ),
                com.cyclone.mobile.agent.plan.TaskWaypoint(
                    com.cyclone.mobile.agent.plan.WaypointKind.SCENE,
                    summary = "provider wording two",
                ),
                com.cyclone.mobile.agent.plan.TaskWaypoint(
                    com.cyclone.mobile.agent.plan.WaypointKind.DONE,
                    summary = "provider wording three",
                ),
            ),
            index = 1,
            horizonPlanned = true,
        )

        val updated = TaskHarnessState.applyTrajectory(task(), trajectory)
        assertEquals(listOf("Checking Instagram login status"), updated.plannedStages.map { it.objective })
        assertFalse(updated.plannedMilestones.any { it.contains("provider wording") })
    }

    @Test
    fun failedTaskAtEndOfTrajectoryDoesNotPaintEveryMilestoneDone() {
        val snapshot = TaskPresentationProjector.project(
            task(
                phase = TaskPhase.FAILED,
                outcome = "I couldn't finish this task.",
            ).copy(
                plannedMilestones = listOf("Opening Instagram", "Checking login status", "Verifying the result"),
                plannedMilestoneIndex = 3,
            ),
        )

        assertEquals(TaskConsumerState.FAILED, snapshot.state)
        assertEquals(2, snapshot.completedCount)
        assertEquals(SemanticStepState.FAILED, snapshot.milestones.last().state)
        assertTrue(snapshot.progressFraction!! < 1f)
    }

    @Test
    fun confirmationExplanationSurvivesSharedPresentationProjection() {
        val gated = task(phase = TaskPhase.REVIEW).copy(
            confirmation = WorkspaceConfirmation(
                action = "phone.click",
                nodeId = "pay",
                fingerprint = "page-1",
                kind = "pay",
            ),
        )

        val snapshot = TaskPresentationProjector.project(gated)

        assertEquals(TaskConsumerState.ACTION_NEEDED, snapshot.state)
        assertEquals(
            "Review the total and payment details in the live page before confirming.",
            snapshot.supportingCopy,
        )
        assertTrue(snapshot.followUps.isEmpty())
    }

    @Test
    fun actionFlagsCannotCreateMutationCtasWithoutInteractiveSessionGrounding() {
        val ungrounded = task(
            phase = TaskPhase.REVIEW,
            interruption = TaskInterruption(
                reason = "LOGIN_WALL",
                prompt = "Sign in",
                canTakeOver = true,
                canResumeAfterHuman = true,
                canAutofill = true,
            ),
        ).copy(sessionId = null, displayId = null)

        val snapshot = TaskPresentationProjector.project(ungrounded)

        assertFalse(TaskFollowUpAction.AUTOFILL in snapshot.followUps)
        assertFalse(TaskFollowUpAction.TAKE_OVER in snapshot.followUps)
        assertFalse(TaskFollowUpAction.CONTINUE in snapshot.followUps)
        assertTrue(snapshot.followUps.isEmpty())
    }

    @Test
    fun failedStateOffersRetryWithoutPretendingSuccess() {
        val snapshot = TaskPresentationProjector.project(
            task(phase = TaskPhase.FAILED, outcome = "I couldn't finish. Your place is saved."),
        )

        assertEquals(TaskConsumerState.FAILED, snapshot.state)
        assertNull(snapshot.progressFraction)
        assertEquals(
            listOf(TaskFollowUpAction.TRY_AGAIN),
            snapshot.followUps,
        )
    }
}
