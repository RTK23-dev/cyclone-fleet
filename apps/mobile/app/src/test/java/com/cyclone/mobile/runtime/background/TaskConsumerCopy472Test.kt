package com.cyclone.mobile.runtime.background

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskConsumerCopy472Test {
    @Test
    fun typedMilestoneBecomesLegacySubtitleWithoutUsingRawPlanProse() {
        val task = WorkspaceTaskUi(
            taskId = "task-1",
            sessionId = "session-1",
            app = "Instagram",
            packageName = "com.instagram.android",
            goal = "Check my Instagram login status",
            phase = TaskPhase.WORKING,
            displayId = 0,
            plannedMilestones = listOf(
                "Opening Instagram",
                "Checking Instagram login status",
                "Verifying login status",
            ),
            plannedMilestoneIndex = 1,
        )

        assertEquals("Checking Instagram login status", TaskConsumerCopy.subtitle(task))
        assertEquals("Checking Instagram login status", task.subtitle)
    }

    @Test
    fun interruptionStillWinsOverPlanMilestone() {
        val task = WorkspaceTaskUi(
            taskId = "task-2",
            sessionId = "session-2",
            app = "Instagram",
            packageName = "com.instagram.android",
            goal = "Check my Instagram login status",
            phase = TaskPhase.HUMAN,
            displayId = 0,
            interruption = TaskInterruption(
                reason = "LOGIN_WALL",
                prompt = "Finish sign-in, then continue.",
                canResumeAfterHuman = true,
            ),
            plannedMilestones = listOf("Opening Instagram", "Checking Instagram login status"),
            plannedMilestoneIndex = 1,
        )

        assertEquals("Finish sign-in, then continue.", TaskConsumerCopy.subtitle(task))
    }
}
