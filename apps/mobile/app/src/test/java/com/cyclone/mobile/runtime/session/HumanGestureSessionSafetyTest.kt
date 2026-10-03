package com.cyclone.mobile.runtime.session

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HumanGestureSessionSafetyTest {
    @Test
    fun `humanize cannot change foreground session identity`() {
        val plane = SessionContract.classify(
            JSONObject()
                .put("sessionId", "default-foreground")
                .put("displayId", 0)
                .put("humanize", "normal"),
        )
        assertEquals(SessionPlaneKind.FOREGROUND, plane.kind)
        assertEquals("default-foreground", plane.sessionId)
        assertEquals(0, plane.displayId)
    }

    @Test
    fun `humanize cannot turn a named virtual display into foreground`() {
        val plane = SessionContract.classify(
            JSONObject()
                .put("sessionId", "workspace-mail")
                .put("displayId", 7)
                .put("humanize", "normal"),
        )
        assertEquals(SessionPlaneKind.SESSION_KERNEL_VD, plane.kind)
        assertEquals("workspace-mail", plane.sessionId)
        assertEquals(7, plane.displayId)
    }

    @Test
    fun `humanize cannot bypass illegal foreground display pairing`() {
        assertThrows(SessionIdentityException::class.java) {
            SessionContract.classify(
                JSONObject()
                    .put("sessionId", "default-foreground")
                    .put("displayId", 4)
                    .put("humanize", "normal"),
            )
        }
    }

    @Test
    fun `humanize preserves layer2 workspace identity`() {
        val plane = SessionContract.classify(
            JSONObject()
                .put("sessionId", "default-foreground")
                .put("displayId", 0)
                .put("workspaceId", "shopping")
                .put("workspaceGeneration", 12L)
                .put("humanize", "light"),
        )
        assertEquals(SessionPlaneKind.LAYER2_WORKSPACE, plane.kind)
        assertEquals("shopping", plane.workspaceId)
        assertEquals(12L, plane.workspaceGeneration)
    }
}
