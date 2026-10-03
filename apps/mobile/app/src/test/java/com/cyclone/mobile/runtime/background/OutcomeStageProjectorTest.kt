package com.cyclone.mobile.runtime.background

import com.cyclone.mobile.agent.plan.TaskDifficulty
import com.cyclone.mobile.agent.plan.TaskDifficultyTier
import com.cyclone.mobile.agent.plan.TaskTrajectory
import com.cyclone.mobile.agent.plan.WaypointKind
import com.cyclone.mobile.applearner.ActionRisk
import com.cyclone.mobile.applearner.PageContext
import com.cyclone.mobile.applearner.PageControl
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutcomeStageProjectorTest {
    private val goal =
        "Open Gmail and find my current logged-in email, then go to Chrome and log in to Facebook using that email."

    @Test
    fun gmailThenFacebookInChromeIsTwoDependentStages() {
        val assessment = TaskDifficulty.assess(goal)
        assertEquals(TaskDifficultyTier.HARD, assessment.tier)
        assertEquals(2, assessment.destinationCount)
        assertTrue(assessment.destinations.any { it.kind == "app" && it.value == "com.google.android.gm" })
        assertTrue(assessment.destinations.any { it.kind == "host" && it.value.contains("facebook") })
        assertFalse(assessment.destinations.any { it.value == "com.facebook.katana" })
        assertFalse(assessment.destinations.any { it.value == "com.android.chrome" })

        val trajectory = TaskTrajectory.seed(goal)
        val projected = OutcomeStageProjector.project(
            goal = goal,
            trajectory = trajectory,
            app = "your app",
            packageName = "",
            phase = TaskPhase.WORKING,
        )
        assertEquals(2, projected.stages.size)
        assertEquals("Gmail", projected.stages[0].destinationLabel)
        assertEquals("Chrome · Facebook", projected.stages[1].destinationLabel)
        assertEquals("Finding the signed-in email address", projected.stages[0].objective)
        assertEquals("Signing in with the selected email", projected.stages[1].objective)
        assertEquals("Sign in to Facebook using your Gmail address", projected.title)
        assertEquals("Gmail → Chrome · Facebook", projected.destinationChain)
        assertEquals(OutcomeStageStatus.ACTIVE, projected.stages[0].status)
        assertEquals(OutcomeStageStatus.BLOCKED, projected.stages[1].status)
        assertEquals(listOf("stage-0"), projected.stages[1].dependsOn)
    }

    @Test
    fun stageTwoCannotCompleteBeforeGmail() {
        val trajectory = TaskTrajectory.seed(goal)
        val projected = OutcomeStageProjector.project(
            goal, trajectory, "Gmail", "com.google.android.gm", TaskPhase.WORKING,
        )
        assertTrue(projected.stages[1].status == OutcomeStageStatus.BLOCKED || projected.stages[1].status == OutcomeStageStatus.QUEUED)
        assertFalse(projected.stages[1].status == OutcomeStageStatus.COMPLETED)
    }

    @Test
    fun passwordWallMarksFacebookNeedsInputWithoutFailingGmail() {
        val trajectory = TaskTrajectory.seed(goal).copy(index = 3)
        val projected = OutcomeStageProjector.project(
            goal = goal,
            trajectory = trajectory,
            app = "Gmail",
            packageName = "com.google.android.gm",
            phase = TaskPhase.HUMAN,
            interruption = TaskInterruption("LOGIN_WALL", "This screen needs your sign-in.", canResumeAfterHuman = true),
        )
        assertEquals(OutcomeStageStatus.COMPLETED, projected.stages[0].status)
        assertEquals(OutcomeStageStatus.NEEDS_INPUT, projected.stages[1].status)
        assertEquals("Email address found", projected.stages[0].resultOrError)
    }

    @Test
    fun failedFacebookKeepsGmailComplete() {
        val trajectory = TaskTrajectory.seed(goal).copy(index = 3)
        val projected = OutcomeStageProjector.project(
            goal, trajectory, "Gmail", "com.google.android.gm", TaskPhase.FAILED, outcome = null,
        )
        assertEquals(OutcomeStageStatus.COMPLETED, projected.stages[0].status)
        assertEquals(OutcomeStageStatus.FAILED, projected.stages[1].status)
        val copy = OutcomeStageCopy.terminalFailure(projected.stages, recordedReason = null, resumable = false)
        assertTrue(copy.contains("Gmail completed") || copy.contains("Stopped before Chrome · Facebook"))
        assertTrue(copy.contains("Run history saved"))
        assertFalse(copy.contains("Your place is saved"))
    }

    @Test
    fun projectorCompletesGmailOnlyAfterEmailEvidence() {
        val opened = TaskTrajectory.seed(goal).advanceIfSatisfied(
            page("com.google.android.gm", listOf(control("Compose"), control("Inbox"))),
        )
        val afterOpen = OutcomeStageProjector.project(
            goal, opened, "Gmail", "com.google.android.gm", TaskPhase.WORKING,
        )
        assertEquals(OutcomeStageStatus.ACTIVE, afterOpen.stages[0].status)
        assertEquals(OutcomeStageStatus.BLOCKED, afterOpen.stages[1].status)

        val found = opened.advanceIfSatisfied(
            page("com.google.android.gm", listOf(control("Compose"), control("ada@gmail.com"))),
        )
        assertEquals(WaypointKind.LAUNCH_INTENT, found.current?.kind)
        val afterEmail = OutcomeStageProjector.project(
            goal, found, "Gmail", "com.google.android.gm", TaskPhase.WORKING,
        )
        assertEquals(OutcomeStageStatus.COMPLETED, afterEmail.stages[0].status)
        assertEquals("Email address found", afterEmail.stages[0].resultOrError)
        assertEquals(OutcomeStageStatus.ACTIVE, afterEmail.stages[1].status)
    }

    @Test
    fun instagramLoginIsOneOutcomeNotEightWaypoints() {
        val instagram = "Open Instagram and check my login status"
        val updated = TaskHarnessState.applyTrajectory(
            WorkspaceTaskUi(
                taskId = "t",
                app = "Instagram",
                packageName = "com.instagram.android",
                goal = instagram,
                phase = TaskPhase.WORKING,
            ),
            TaskTrajectory.seed(instagram),
        )
        assertEquals(1, updated.plannedStages.size)
        assertEquals("Checking Instagram login status", updated.plannedStages.single().objective)
        assertFalse(updated.plannedMilestones.any { it.contains("RAW") })
        assertFalse(updated.plannedMilestones.size > 2)
        val snapshot = TaskPresentationProjector.project(updated)
        assertEquals(1, snapshot.totalCount)
        assertEquals(0, snapshot.completedCount)
        assertTrue(snapshot.supportingCopy!!.contains("stages complete"))
    }

    @Test
    fun tokensStayUnreportedRatherThanZero() {
        val info = TaskRunInformationProjector.combine(
            task = WorkspaceTaskUi("t", app = "Gmail", packageName = "com.google.android.gm", goal = "open gmail"),
            modelName = "test-model",
            startedAtMs = 1_000L,
            endedAtMs = 4_000L,
            modelRequests = 3,
            toolActions = 4,
            verifiedActions = 1,
            tokensInput = null,
            tokensOutput = null,
            nowMs = 4_000L,
        )
        assertEquals(3_000L, info.elapsedMs)
        assertEquals("3s", info.elapsedLabel)
        assertFalse(info.tokensReported)
        assertEquals(null, info.tokensTotal)
        assertEquals("3s", TaskRunInformationProjector.formatElapsed(3_200))
        assertEquals("1m 2s", TaskRunInformationProjector.formatElapsed(62_000))
    }

    @Test
    fun singleDestinationSearchTitleDoesNotInventALoginCheck() {
        val goal = "Search Gmail for last week's receipts"
        val projected = OutcomeStageProjector.project(
            goal, TaskTrajectory.seed(goal), "Gmail", "com.google.android.gm", TaskPhase.WORKING,
        )
        assertEquals(1, projected.stages.size)
        assertEquals("Gmail", projected.stages.single().destinationLabel)
        assertEquals("Searching Gmail", projected.title)
    }

    @Test
    fun usageParserNeverInventedZeroFromEmptyDetails() {
        assertEquals(null to null, TaskRunInformationLive.parseUsage(emptyList()))
        assertEquals(820L to 96L, TaskRunInformationLive.parseUsage(listOf("prompt_tokens=820 completion_tokens=96")))
    }

    private fun control(label: String) = PageControl(
        key = label.lowercase(),
        label = label,
        semanticName = label.lowercase(),
        role = "button",
        selector = JSONObject(),
        androidActions = listOf("ACTION_CLICK"),
        risk = ActionRisk.SAFE,
    )

    private fun page(packageName: String, controls: List<PageControl>) = PageContext(
        pageKey = "$packageName:page",
        packageName = packageName,
        className = null,
        title = packageName,
        structuralKey = "s",
        contentKey = "c",
        controls = controls,
        observationCount = 1,
        firstSeenAt = 0,
        lastSeenAt = 0,
    )
}
