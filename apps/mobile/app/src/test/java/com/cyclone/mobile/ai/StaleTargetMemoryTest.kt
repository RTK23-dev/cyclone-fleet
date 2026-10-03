package com.cyclone.mobile.ai

import com.cyclone.mobile.agent.contract.AgentElementCandidate
import com.cyclone.mobile.agent.contract.AgentPageCard
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaleTargetMemoryTest {
    @Test
    fun sameLogicalTargetAcrossFreshObservationIdsIsQuarantinedAfterTwoStaleRejects() {
        val memory = StaleTargetMemory(maxRejectionsPerLogicalTarget = 2)
        val action1 = action("semantic:obs-1:email")
        val key1 = memory.key(action1, page("obs-1", "Email", "id/email"))
        memory.recordRejected(key1)

        val action2 = action("semantic:obs-2:email")
        val key2 = memory.key(action2, page("obs-2", "Email", "id/email"))
        assertTrue(memory.mayDispatch(key2))
        memory.recordRejected(key2)

        val action3 = action("semantic:obs-3:email")
        val key3 = memory.key(action3, page("obs-3", "Email", "id/email"))
        assertFalse(memory.mayDispatch(key3))
        assertTrue(memory.mayDispatch(memory.key(action("semantic:obs-3:password"), page("obs-3", "Password", "id/password"))))
    }

    @Test
    fun verifiedProgressOrHumanHandoffClearsQuarantine() {
        val memory = StaleTargetMemory(maxRejectionsPerLogicalTarget = 1)
        val action = action("semantic:obs-1:email")
        val key = memory.key(action, page("obs-1", "Email", "id/email"))
        memory.recordRejected(key)
        assertFalse(memory.mayDispatch(key))

        memory.markVerifiedProgress()
        assertTrue(memory.mayDispatch(key))

        memory.recordRejected(key)
        memory.resetAfterHandoff()
        assertTrue(memory.mayDispatch(key))
    }

    private fun action(id: String) = PageAgentAction(
        tool = "phone.click",
        controlId = id,
        params = JSONObject(),
        expectedPageChange = false,
        displaySummary = "Focus field",
    )

    private fun page(obs: String, label: String, resource: String): AgentPageCard {
        val control = AgentElementCandidate(
            elementId = "semantic:" + obs + ":" + resource.substringAfterLast('/'),
            observationId = obs,
            label = label,
            semanticName = label.lowercase(),
            role = "edittext",
            source = "semantic",
            relevance = 1.0,
            evidence = JSONObject().put("resourceId", resource).put("enabled", true).put("visibleToUser", true),
        )
        return AgentPageCard(
            observationId = obs,
            generation = 1,
            actionable = true,
            capturedAtMs = 1,
            packageName = "com.android.chrome",
            activity = null,
            pageKey = "page",
            structuralKey = "structure",
            contentKey = "content",
            accessibilityFingerprint = "fp",
            pageSummary = JSONObject(),
            pageText = JSONObject(),
            pageEvidence = JSONObject(),
            controls = listOf(control),
            nextHopHints = JSONArray(),
        )
    }
}
