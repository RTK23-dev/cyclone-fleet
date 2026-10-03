package com.cyclone.mobile.ai

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RequestOutcomeBoundaryTest {
    @Test fun validationFailureRetainsIdentityWithoutAnActiveTask() = runBlocking {
        val reports = mutableListOf<QuickAgentResult>()
        val result = RequestOutcomeBoundary.run("request", "model", { reports += it }) {
            QuickAgentResult(false, "Missing permission", 0, "model")
        }
        assertEquals("request", result.taskId)
        assertEquals(listOf(result), reports)
    }
    @Test fun startupExceptionPersistsSafeFailure() = runBlocking {
        val reports = mutableListOf<QuickAgentResult>()
        val result = RequestOutcomeBoundary.run("startup", "model", { reports += it }) {
            throw IllegalStateException("api-key-secret")
        }
        assertEquals("HARD_BLOCKER", result.classification)
        assertFalse(result.message.contains("api-key-secret"))
        assertEquals(1, reports.size)
    }
    @Test fun cancellationDuringStartupWritesReportBeforeCleanup() = runBlocking {
        val events = mutableListOf<String>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                RequestOutcomeBoundary.run("cancel", "model", { events += it.classification!! }) {
                    delay(Long.MAX_VALUE)
                    error("must not execute")
                }
            } finally { events += "cleared" }
        }
        job.cancelAndJoin()
        assertEquals(listOf("CANCELLED", "cleared"), events)
    }
    @Test fun humanHandoffRemainsResumableAndDoesNotFinish() = runBlocking {
        val reports = mutableListOf<QuickAgentResult>()
        val result = RequestOutcomeBoundary.run("handoff", "model", { reports += it }) {
            QuickAgentResult(false, "Waiting for you", 1, "model", classification = "HUMAN_OR_GATE")
        }
        assertEquals("handoff", result.taskId)
        assertTrue(reports.isEmpty())
    }
    @Test fun normalCompletionProducesOneBoundaryOutcome() = runBlocking {
        val reports = mutableListOf<QuickAgentResult>()
        RequestOutcomeBoundary.run("done", "model", { reports += it }) {
            QuickAgentResult(true, "Verified", 1, "model", classification = "COMPLETE")
        }
        assertEquals(1, reports.size)
        assertTrue(reports.single().ok)
    }
}
