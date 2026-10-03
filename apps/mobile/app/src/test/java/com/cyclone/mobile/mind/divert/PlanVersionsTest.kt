package com.cyclone.mobile.mind.divert

import com.cyclone.mobile.mind.MindPlanStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanVersionsTest {
    private fun steps(vararg rows: Pair<String, String>) = rows.map { (text, status) -> MindPlanStep(text, status) }

    private val first = steps("Read Louella's address" to "done", "Bike time" to "done", "DM her on Instagram" to "doing",
        "Confirm it's delivered" to "todo")

    @Test fun theFirstPlanAndItsProgressAreNotDiversions() {
        val versions = PlanVersions("Tell Louella when I'll be there")
        assertFalse(versions.planned(first))
        assertFalse(versions.planned(first.map { if (it.status == "doing") it.copy(status = "done") else it }))
        assertEquals(1, versions.versionNumber)
        assertNull(versions.label)
        assertNull(versions.approvalNote())
        assertTrue(versions.display.none { it.dropped || it.branch })
    }

    @Test fun aRewordedStepIsNotADiversion() {
        val versions = PlanVersions("goal")
        versions.planned(first)
        assertFalse(versions.planned(first.map { if (it.text.startsWith("DM")) it.copy(text = "DM Louella on Instagram") else it }))
        assertEquals(1, versions.versionNumber)
    }

    @Test fun aDeclaredDivertShowsTheDroppedStepStruckAndTheNewRouteAsABranch() {
        val versions = PlanVersions("Tell Louella when I'll be there")
        versions.planned(first)
        val next = steps("Read Louella's address" to "done", "Bike time" to "done", "Message her on WhatsApp" to "doing",
            "Confirm it's delivered" to "todo")
        assertTrue(versions.planned(next, PlanVersions.Divert("DM on Instagram", "WhatsApp", "Her Instagram DMs are closed")))
        assertEquals(2, versions.versionNumber)
        assertEquals("Changed course", versions.label)
        val display = versions.display
        val dropped = display.single { it.dropped }
        assertEquals("DM her on Instagram", dropped.text)
        val branch = display.single { it.branch }
        assertEquals("Message her on WhatsApp", branch.text)
        assertEquals("Her Instagram DMs are closed", branch.note)
        assertTrue("the struck row sits right above the branch", display.indexOf(dropped) + 1 == display.indexOf(branch))
        assertEquals(listOf(first), versions.earlierPlans)
        val note = versions.approvalNote()
        assertNotNull(note)
        assertTrue(note!!.contains("WhatsApp") && note.contains("DMs are closed"))
        val record = versions.record()
        assertEquals(1, record.getInt("byModel"))
        assertEquals(0, record.getInt("steered"))
    }

    @Test fun droppingUnfinishedStepsIsADiversionEvenWhenUndeclared() {
        val versions = PlanVersions("goal")
        versions.planned(first)
        assertTrue(versions.planned(steps("Read Louella's address" to "done", "Bike time" to "done", "Call her" to "doing")))
        assertEquals(PlanVersions.REPLANNED, versions.history.last().trigger)
    }

    @Test fun aSteerVersionsTheGoalAndTheFinishIsRemindedOnce() {
        val versions = PlanVersions("Set a timer for 5 minutes")
        versions.planned(steps("Open Clock" to "done", "Set 5 minutes" to "doing"))
        val goal = versions.steer("Make it 3 minutes instead")
        assertEquals(2, goal.number)
        assertEquals("Make it 3 minutes instead", versions.goal.text)
        assertEquals(2, versions.goalHistory.size)
        assertTrue(versions.needsReplan)
        assertNotNull(versions.finishNote())
        assertNull("only once", versions.finishNote())
    }

    @Test fun aReplanAfterASteerIsTheOwnersChangeAndNeedsNoApprovalNote() {
        val versions = PlanVersions("Set a timer for 5 minutes")
        versions.planned(steps("Open Clock" to "done", "Set 5 minutes" to "doing"))
        versions.steer("Make it 3 minutes instead")
        assertTrue(versions.planned(steps("Open Clock" to "done", "Set 3 minutes" to "doing")))
        assertFalse(versions.needsReplan)
        assertNull(versions.finishNote())
        assertEquals("You changed this", versions.label)
        assertEquals("You changed this", versions.display.single { it.branch }.note)
        assertNull("the owner asked for it", versions.approvalNote())
        assertEquals(1, versions.record().getInt("steered"))
    }

    @Test fun droppedRowsStayVisibleWhileThePlanMovesOn() {
        val versions = PlanVersions("goal")
        versions.planned(first)
        versions.planned(steps("Read Louella's address" to "done", "Bike time" to "done", "Message her on WhatsApp" to "doing",
            "Confirm it's delivered" to "todo"), PlanVersions.Divert("Instagram", "WhatsApp", "DMs closed"))
        assertFalse(versions.planned(steps("Read Louella's address" to "done", "Bike time" to "done", "Message her on WhatsApp" to "done",
            "Confirm it's delivered" to "doing")))
        assertEquals(1, versions.display.count { it.dropped })
        assertTrue(versions.display.single { it.branch }.status == "done")
    }

    @Test fun aDivertDeclaredInTheFirstPlanStillCounts() {
        val versions = PlanVersions("goal")
        assertTrue(versions.planned(steps("Message on WhatsApp" to "doing"), PlanVersions.Divert("DM on Instagram", "WhatsApp", "DMs closed")))
        assertEquals(2, versions.versionNumber)
        assertEquals("DM on Instagram", versions.display.single { it.dropped }.text)
        assertEquals("DMs closed", versions.display.single { it.branch }.note)
    }
}
