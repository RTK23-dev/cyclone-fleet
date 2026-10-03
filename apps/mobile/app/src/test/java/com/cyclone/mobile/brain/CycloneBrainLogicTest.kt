package com.cyclone.mobile.brain

import com.cyclone.mobile.ai.AiTraceEvent
import com.cyclone.mobile.ai.AiTraceSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CycloneBrainLogicTest {
    @Test
    fun goalKeyNormalizesEquivalentRoutineRequests() {
        assertEquals(
            BrainLearningLogic.goalKey("Please open my newest invoice"),
            BrainLearningLogic.goalKey("Open the latest invoice"),
        )
    }

    @Test
    fun repeatedSuccessRaisesRoutineConfidence() {
        val first = BrainLearningLogic.confidence(successCount = 1, failureCount = 0)
        val repeated = BrainLearningLogic.confidence(successCount = 4, failureCount = 0)
        assertTrue(repeated > first)
        assertTrue(repeated >= 0.70)
    }

    @Test
    fun failuresReduceConfidence() {
        val clean = BrainLearningLogic.confidence(successCount = 3, failureCount = 0)
        val noisy = BrainLearningLogic.confidence(successCount = 3, failureCount = 2)
        assertTrue(noisy < clean)
    }

    @Test
    fun taskSummaryCapturesReusableToolSequenceWithoutObservationNoise() {
        val session = AiTraceSession(
            id = "s1",
            goal = "Open Battery settings",
            model = "provider/model",
            status = "COMPLETED",
            startedAt = 1L,
            endedAt = 2L,
            result = "done",
            decisions = 2,
        )
        val events = listOf(
            event("phone.observe", true),
            event("phone.open_app", true),
            event("phone.click", true),
            event("phone.assert", true),
        )
        val learned = BrainLearningLogic.summarize(session, events)
        assertEquals("phone.open_app → phone.click → phone.assert", learned.toolSequence)
        assertFalse(learned.signature.isBlank())
        assertTrue(learned.summary.contains("reusable", ignoreCase = true))
    }

    private fun event(code: String, ok: Boolean) = AiTraceEvent(
        id = code,
        sessionId = "s1",
        timestampMs = 1L,
        kind = "RESULT",
        displayText = if (ok) "completed" else "failed",
        code = code,
        ok = ok,
        detail = null,
    )
}
