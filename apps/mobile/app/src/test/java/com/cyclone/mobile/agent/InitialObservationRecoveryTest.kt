package com.cyclone.mobile.agent

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class InitialObservationRecoveryTest {
    private fun health(state: ObservationState) = ObservationHealth(state, "task", 0, attempts = 1)
    @Test fun transientCaptureUsesOneBoundedRetry() = runBlocking {
        var captures = 0; val waits = mutableListOf<Long>()
        val result = InitialObservationRecovery.capture(
            { if (++captures == 2) "tree" else null }, { health(ObservationState.CAPTURE_CHANGED) }, { waits += it })
        assertEquals("tree", result); assertEquals(2, captures); assertEquals(listOf(550L), waits)
    }
    @Test fun deterministicBlockerDoesNotRetry() = runBlocking {
        var captures = 0
        val result = InitialObservationRecovery.capture<String>(
            { captures++; null }, { health(ObservationState.PERMISSION_REQUIRED) }, { fail("Must not wait") })
        assertNull(result); assertEquals(1, captures)
    }
    @Test fun repeatedCaptureFailureCannotLoop() = runBlocking {
        var captures = 0
        assertNull(InitialObservationRecovery.capture<String>(
            { captures++; null }, { health(ObservationState.CAPTURE_CHANGED) }, {}))
        assertEquals(2, captures)
    }
    @Test fun stopDuringSettleNeverCapturesAgain() = runBlocking {
        var captures = 0
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            InitialObservationRecovery.capture<String>({ captures++; null },
                { health(ObservationState.CAPTURE_CHANGED) }, { delay(Long.MAX_VALUE) })
        }
        job.cancelAndJoin(); assertEquals(1, captures)
    }
    @Test fun settlingChangesAreOutsideMeasuredCapture() {
        var revision = 1L
        val result = SemanticCaptureBoundary.capture(
            surface = { ObservationSurface("task", 0, "scope", "window", 100, 200, 0, revision) },
            semantic = { "current-tree" }, clock = { 10L }, settle = { revision++ })
        assertEquals(2L, result.surface.revision)
        assertEquals("current-tree", result.semantic)
    }
}
