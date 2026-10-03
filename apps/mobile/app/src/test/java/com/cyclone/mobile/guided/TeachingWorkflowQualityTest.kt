package com.cyclone.mobile.guided

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeachingWorkflowQualityTest {
    @Test
    fun stableSelectorAndVerifierAreApprovedOnlyForReview() {
        val report = TeachingWorkflowQuality.evaluate(session(step(
            selector = JSONObject().put("resourceId", "com.example:id/apps").put("text", "Apps").toString(),
            before = "before",
            after = "after",
            verified = true,
        )))

        assertEquals(WorkflowCompileGate.APPROVED_FOR_REVIEW, report.gate)
        assertTrue(report.score >= 0.55)
    }

    @Test
    fun coordinateOnlyEvidenceIsHeldForRepair() {
        val report = TeachingWorkflowQuality.evaluate(session(step(
            selector = JSONObject().put("x", 120).put("y", 640).toString(),
            before = null,
            after = null,
            verified = null,
        )))

        assertEquals(WorkflowCompileGate.NEEDS_REPAIR, report.gate)
        assertTrue(report.repairs.any { "selector" in it })
    }

    @Test
    fun sensitivePlaintextRejectsCompilation() {
        val unsafe = step(
            selector = JSONObject().put("resourceId", "com.example:id/field").toString(),
            before = "before",
            after = "after",
            verified = true,
        ).copy(note = "otp=123456")

        assertEquals(WorkflowCompileGate.REJECTED, TeachingWorkflowQuality.evaluate(session(unsafe)).gate)
    }

    @Test
    fun repairPrefersSemanticSelectorAndDropsCoordinates() {
        val repaired = JSONObject(requireNotNull(TeachingWorkflowQuality.repairSelector(
            JSONObject().put("resourceId", "com.example:id/apps").put("x", 2).put("y", 3).toString(),
        )))

        assertEquals("com.example:id/apps", repaired.getString("resourceId"))
        assertFalse(repaired.has("x"))
        assertFalse(repaired.has("y"))
    }

    private fun session(step: RoutineTeachingStep) = RoutineTeachingSession(
        id = "teach-1",
        name = "Teach Apps",
        modelId = "local",
        startedAt = 1,
        steps = listOf(step),
    )

    private fun step(
        selector: String,
        before: String?,
        after: String?,
        verified: Boolean?,
    ) = RoutineTeachingStep(
        id = "step-1",
        index = 1,
        kind = "click",
        title = "Tap Apps",
        summary = "Open Apps",
        packageName = "com.example",
        pageKey = "settings-home",
        selectorJson = selector,
        beforeFingerprint = before,
        afterFingerprint = after,
        actionSucceeded = true,
        verificationSucceeded = verified,
        confidence = 0.9,
    )
}
