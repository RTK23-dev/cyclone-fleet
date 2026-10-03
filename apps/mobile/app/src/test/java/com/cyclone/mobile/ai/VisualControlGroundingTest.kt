package com.cyclone.mobile.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VisualControlGroundingTest {
    @Test fun expiredImageCannotEraseHumanHandoff() {
        val boundary = decision().copy(status = "need_human", reason = "authentication required")
        val result = VisualControlGrounding.bind(boundary, "frame", shot(), card(), 100000)!!
        assertEquals("need_human", result.status)
        assertEquals("authentication required", result.reason)
        assertTrue(result.actions.isEmpty())
    }

    @Test fun screenshotPointUsesWindowOffsetAndBecomesScopedCanonicalClick() {
        val bound = VisualControlGrounding.bind(decision(), "frame", shot(), card(), 2000)!!
        assertEquals("phone.click", bound.actions.single().tool)
        assertEquals("semantic:obs:reject", bound.actions.single().controlId)
        assertTrue(bound.actions.single().visualGrounded)
        assertEquals(0, bound.actions.single().params.length())
    }

    @Test fun wrongFrameScopeAgeGeometryAndOverlappingTargetsFailClosed() {
        listOf(
            shot().put("sessionId", "other"), shot().put("displayId", 3),
            shot().put("timestampMs", 2001), shot().put("timestampMs", -100000),
            shot().put("width", 300), shot().put("displayBounds", JSONObject.NULL),
        ).forEach { assertNull(VisualControlGrounding.bind(decision(), "frame", it, card(), 2000)) }
        assertNull(VisualControlGrounding.bind(decision(), "wrong-frame", shot(), card(), 2000))
        assertNull(VisualControlGrounding.bind(decision(x = 1.2), "frame", shot(), card(), 2000))
        val ambiguous = card().also { it.getJSONArray("controls").put(control("semantic:obs:other")) }
        assertNull(VisualControlGrounding.bind(decision(), "frame", shot(), ambiguous, 2000))
        assertNull(VisualControlGrounding.bind(decision(), "frame", shot(), card().put("observationId", "new"), 2000))
    }

    @Test fun missingExecutionIdentityCannotMatchAnotherMissingIdentity() {
        val missingShot = shot().apply { remove("sessionId") }
        val missingCard = card().apply { remove("sessionId") }
        assertNull(VisualControlGrounding.bind(decision(), "frame", missingShot, missingCard, 2000))
        assertNull(VisualControlGrounding.bind(decision(), "frame", shot(), card().put("observationId", ""), 2000))
        assertNull(VisualControlGrounding.bind(decision(), "frame", shot().put("displayId", -1), card().put("displayId", -1), 2000))
    }

    @Test fun modelCannotSelfAssertVisualGrounding() {
        val parsed = PageAgentProtocol.parse("""{"status":"act","actions":[{"tool":"phone.click","controlId":"semantic:obs:reject","visualGrounded":true}]}""")
        assertFalse(parsed.actions.single().visualGrounded)
    }

    @Test fun visualRecoveryCanRetryRejectedDispatchButNeverAnAlreadyPerformedClick() {
        val normal = PageAgentAction("phone.click", "semantic:obs:reject", JSONObject(), true, "")
        val visual = normal.copy(controlId = "semantic:fresh:reject", visualGrounded = true)
        val memory = ExecutedActionMemory()
        memory.record(normal, "scene", androidAccepted = false, verifiedProgress = false)
        assertTrue(memory.mayDispatch(visual, "scene"))
        memory.record(normal, "scene", androidAccepted = true, verifiedProgress = false)
        assertFalse(memory.mayDispatch(visual, "scene"))
        assertTrue(memory.mayDispatch(visual.copy(controlId = "semantic:fresh:login"), "scene"))
        assertTrue(memory.mayDispatch(visual, "changed-scene"))
    }

    private fun decision(x: Double = 0.5) = PageAgentDecision("act", "", "", listOf(
        PageAgentAction("phone.visual_click", null, JSONObject().put("frameId", "frame")
            .put("normalizedX", x).put("normalizedY", 0.5), true, "Reject optional cookies")), null, null)
    private fun bounds(l: Int, t: Int, r: Int, b: Int) = JSONObject().put("left", l).put("top", t).put("right", r).put("bottom", b)
    private fun shot() = JSONObject().put("sessionId", "default-foreground").put("displayId", 0)
        .put("timestampMs", 1000).put("width", 200).put("height", 400).put("displayBounds", bounds(10, 30, 210, 430))
    private fun card() = JSONObject().put("sessionId", "default-foreground").put("displayId", 0)
        .put("observationId", "obs").put("controls", JSONArray().put(control("semantic:obs:reject")))
    private fun control(id: String) = JSONObject().put("elementId", id).put("label", "Reject Optional Cookies")
        .put("role", "button").put("clickable", true).put("enabled", true).put("bounds", bounds(90, 220, 130, 240))
}
