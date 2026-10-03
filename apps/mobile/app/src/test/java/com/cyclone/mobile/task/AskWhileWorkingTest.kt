package com.cyclone.mobile.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AskWhileWorkingTest {
    @Test fun steerQueueParallelTopToBottomWithSteerSuggestedForAChange() {
        val rows = AskWhileWorking.options(question = null, parallelBlocker = null, looksNew = false)
        assertEquals(listOf(AskWhileWorking.Choice.STEER, AskWhileWorking.Choice.QUEUE, AskWhileWorking.Choice.PARALLEL), rows.map { it.choice })
        assertTrue(rows[0].suggested)
        assertEquals(1, rows.count { it.suggested })
        assertTrue(rows.all { it.enabled })
    }

    @Test fun aNewTaskSuggestsQueueButNeverChooses() {
        val rows = AskWhileWorking.options(null, null, looksNew = true)
        assertTrue(rows.single { it.suggested }.choice == AskWhileWorking.Choice.QUEUE)
    }

    @Test fun anOpenQuestionMakesTheFirstRowAnAnswer() {
        val rows = AskWhileWorking.options("Which account?", null, looksNew = false)
        assertEquals(AskWhileWorking.Choice.ANSWER, rows[0].choice)
        assertEquals("Which account?", rows[0].subtitle)
        assertTrue(rows.none { it.choice == AskWhileWorking.Choice.STEER })
    }

    @Test fun parallelIsGreyedWithItsReason() {
        val rows = AskWhileWorking.options(null, "Needs your screen", looksNew = true)
        val parallel = rows.single { it.choice == AskWhileWorking.Choice.PARALLEL }
        assertFalse(parallel.enabled)
        assertEquals("Needs your screen", parallel.reason)
    }

    @Test fun everyChoiceIsATaskKitCommand() {
        assertEquals(TaskCommand.Steer("x"), AskWhileWorking.command(AskWhileWorking.Choice.STEER, "x"))
        assertEquals(TaskCommand.Queue("x"), AskWhileWorking.command(AskWhileWorking.Choice.QUEUE, "x"))
        assertEquals(TaskCommand.Parallel("x"), AskWhileWorking.command(AskWhileWorking.Choice.PARALLEL, "x"))
        assertEquals(TaskCommand.Reply("x"), AskWhileWorking.command(AskWhileWorking.Choice.ANSWER, "x"))
        assertEquals("steer", TaskCommand.Steer("x").wire)
        assertEquals(TaskCommand.Unpause, TaskCommand.parse("unpause"))
    }
}
