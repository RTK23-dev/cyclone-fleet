package com.cyclone.mobile.agent.plan

import com.cyclone.mobile.runtime.background.OutcomeStageProjector
import com.cyclone.mobile.runtime.background.TaskPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5 law 2: the sentence is law. Account words ("my logged in email") must not turn a task into a
 * login-status check, and each destination's stage keeps the user's own verb.
 */
class SentenceIsLawTest {
    private val louella =
        "open Gmail, check my current logged in email, then go to facebook and find the dm of Louella"

    @Test
    fun louellaIsNeverPresentedAsALoginStatusRun() {
        val projected = OutcomeStageProjector.project(
            goal = louella,
            trajectory = TaskTrajectory.seed(louella),
            app = "Gmail",
            packageName = "com.google.android.gm",
            phase = TaskPhase.WORKING,
        )

        assertEquals(2, projected.stages.size)
        assertEquals("Finding the signed-in email address", projected.stages[0].objective)
        assertEquals("Finding the dm of Louella", projected.stages[1].objective)
        val everything = (projected.stages.map { it.objective } + projected.title).joinToString(" | ")
        assertFalse(everything, everything.contains("login status", ignoreCase = true))
        assertFalse(everything, everything.contains("Sign in", ignoreCase = true))
    }

    @Test
    fun louellaDoesNotStopAtTheLoginWallAsTheGoal() {
        val trajectory = TaskTrajectory.seed(louella)
        assertFalse(trajectory.waypoints.any { it.kind == WaypointKind.STOP_HUMAN })
        assertFalse(TaskDifficulty.hasAuthenticatedSession(louella))
    }

    @Test
    fun explicitLoginAsksKeepTheirMeaning() {
        assertTrue(DestinationAuthority.asksAboutLogin("Check my Instagram login status"))
        assertTrue(DestinationAuthority.asksAboutLogin("go to Chrome and log in to Facebook using that email"))
        assertTrue(DestinationAuthority.asksAboutLogin("am I signed in to YouTube"))
        assertFalse(DestinationAuthority.asksAboutLogin("check my current logged in email"))
        assertFalse(DestinationAuthority.asksAboutLogin("which account is signed in"))
        assertFalse(DestinationAuthority.asksAboutLogin("find the login email for my bank"))
    }

    @Test
    fun eachDestinationReadsItsOwnClause() {
        val gmail = TaskDestination("app", "com.google.android.gm", louella.indexOf("Gmail"))
        val facebook = TaskDestination("app", "com.facebook.katana", louella.indexOf("facebook"))
        assertEquals("open Gmail, check my current logged in email", DestinationAuthority.clauseFor(louella, gmail))
        assertEquals("go to facebook and find the dm of Louella", DestinationAuthority.clauseFor(louella, facebook))
        assertTrue(DestinationAuthority.wantsSignedInEmail(louella, gmail))
    }

    @Test
    fun whichAccountQuestionFindsTheSignedInEmail() {
        val goal = "open gmail and tell me which email account is signed in"
        val gmail = TaskDestination("app", "com.google.android.gm", goal.indexOf("gmail"))
        assertEquals("Finding the signed-in email address", DestinationAuthority.objective(goal, gmail, last = true))
    }

    @Test
    fun objectivesNeverCarryAnEmailAddress() {
        val goal = "open facebook and find messages from j.doe@example.com"
        val facebook = TaskDestination("app", "com.facebook.katana", goal.indexOf("facebook"))
        val objective = DestinationAuthority.objective(goal, facebook, last = true)
        assertFalse(objective.contains("@"))
    }
}
