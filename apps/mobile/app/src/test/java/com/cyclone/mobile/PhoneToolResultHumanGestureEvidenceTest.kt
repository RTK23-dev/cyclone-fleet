package com.cyclone.mobile

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneToolResultHumanGestureEvidenceTest {
    @Test
    fun `successful click without coordinate trace serializes as semantic action`() {
        val result = PhoneToolResult(
            commandId = "semantic-click-test",
            tool = "phone.click",
            ok = true,
            startedAtMs = 10L,
            finishedAtMs = 11L,
            payload = JSONObject().put("performed", true),
        ).toJson()

        val evidence = result.getJSONObject("payload").getJSONObject("humanGesture")
        assertEquals("semantic_action", evidence.getString("dispatchMode"))
        assertEquals("semantic", evidence.getString("interactionMode"))
        assertEquals("none", evidence.getString("appliedProfile"))
        assertEquals("accessibility_dispatch_gesture", evidence.getString("backend"))
        assertEquals("cyclone.human_gesture.control.v1", evidence.getString("controlVersion"))
        assertEquals("cyclone.human_gesture.trace.v1", evidence.getString("traceVersion"))
        assertTrue(evidence.isNull("requestedHumanize"))
        assertFalse(evidence.getBoolean("correctedOrRejected"))
        assertTrue(evidence.getBoolean("completed"))
    }

    @Test
    fun `named virtual display click without a dispatch trace does not invent endpoint duration`() {
        val result = PhoneToolResult(
            commandId = "vd-click-test",
            tool = "phone.click",
            ok = true,
            startedAtMs = 10L,
            finishedAtMs = 11L,
            payload = JSONObject()
                .put("performed", true)
                .put("sessionId", "workspace-mail")
                .put("displayId", 7),
        ).toJson()

        val evidence = result.getJSONObject("payload").getJSONObject("humanGesture")
        assertEquals("semantic_action", evidence.getString("dispatchMode"))
        assertEquals("accessibility_dispatch_gesture", evidence.getString("backend"))
        assertEquals("workspace-mail", evidence.getString("sessionId"))
        assertEquals(7, evidence.getInt("displayId"))
        assertFalse(evidence.getBoolean("correctedOrRejected"))
        assertTrue(evidence.getBoolean("completed"))
    }

    @Test
    fun `failed click without android dispatch trace does not fabricate execution evidence`() {
        val result = PhoneToolResult(
            commandId = "blocked-click-test",
            tool = "phone.click",
            ok = false,
            startedAtMs = 10L,
            finishedAtMs = 10L,
            error = PhoneToolError(PhoneToolErrorCode.POLICY_DENIED, "blocked before dispatch"),
        ).toJson()

        assertTrue(result.isNull("payload"))
    }
}
