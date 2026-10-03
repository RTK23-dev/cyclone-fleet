package com.cyclone.mobile.runtime.session

import com.cyclone.mobile.runtime.background.WorkspaceTasks
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SessionContractTest {
    @Test fun classifyEmptyDefaultsToForegroundDisplayZero() {
        assertForeground(SessionContract.classify(JSONObject()))
    }

    @Test fun classifyExplicitForegroundDisplayZero() {
        assertForeground(
            SessionContract.classify(
                JSONObject().put("sessionId", "default-foreground").put("displayId", 0),
            ),
        )
    }

    @Test fun requireUiEmptyIsSessionRequired() {
        assertErrorClass(SessionContract.SESSION_REQUIRED) { SessionContract.requireUi(JSONObject()) }
    }

    @Test fun requireUiMissingSessionIdWithDisplayZeroIsSessionRequired() {
        assertErrorClass(SessionContract.SESSION_REQUIRED) {
            SessionContract.requireUi(JSONObject().put("displayId", 0))
        }
    }

    @Test fun classifyNamedWithoutDisplayIsSessionDisplayMismatch() {
        assertErrorClass(SessionContract.SESSION_DISPLAY_MISMATCH) {
            SessionContract.classify(JSONObject().put("sessionId", "named-vd"))
        }
    }

    @Test fun classifyNamedDisplayZeroIsSessionDisplayMismatch() {
        assertErrorClass(SessionContract.SESSION_DISPLAY_MISMATCH) {
            SessionContract.classify(JSONObject().put("sessionId", "named-vd").put("displayId", 0))
        }
    }

    @Test fun classifyNamedDisplayIdSnakeZeroIsSessionDisplayMismatch() {
        assertErrorClass(SessionContract.SESSION_DISPLAY_MISMATCH) {
            SessionContract.classify(JSONObject().put("session_id", "named-vd").put("display_id", 0))
        }
    }

    @Test fun classifyNamedDisplaySevenIsSessionKernelVd() {
        val plane = SessionContract.classify(JSONObject().put("sessionId", "named-vd").put("displayId", 7))
        assertEquals(SessionPlaneKind.SESSION_KERNEL_VD, plane.kind)
        assertEquals("session_kernel_vd", plane.wireKind)
        assertEquals("Session Kernel VD", plane.label)
        assertEquals("named-vd", plane.sessionId)
        assertEquals(7, plane.displayId)
        assertEquals("session_kernel_vd", plane.toJson().getString("kind"))
        assertEquals("named-vd", plane.toJson().getString("sessionId"))
        assertEquals(7, plane.toJson().getInt("displayId"))
        assertEquals("Session Kernel VD", plane.toJson().getString("label"))
    }

    @Test fun classifyDefaultForegroundDisplayEightIsSessionDisplayMismatch() {
        assertErrorClass(SessionContract.SESSION_DISPLAY_MISMATCH) {
            SessionContract.classify(
                JSONObject().put("sessionId", "default-foreground").put("displayId", 8),
            )
        }
    }

    @Test fun classifyWorkspaceIdWithoutGenerationIsWorkspaceGenerationRequired() {
        assertErrorClass(SessionContract.WORKSPACE_GENERATION_REQUIRED) {
            SessionContract.classify(JSONObject().put("workspaceId", "ws-a"))
        }
    }

    @Test fun classifyGenerationWithoutWorkspaceIdIsWorkspaceGenerationRequired() {
        assertErrorClass(SessionContract.WORKSPACE_GENERATION_REQUIRED) {
            SessionContract.classify(JSONObject().put("workspaceGeneration", 4))
        }
    }

    @Test fun classifyNamedDisplayAndWorkspaceIdIsPlaneMismatch() {
        assertErrorClass(SessionContract.PLANE_MISMATCH) {
            SessionContract.classify(
                JSONObject()
                    .put("sessionId", "named-vd")
                    .put("displayId", 7)
                    .put("workspaceId", "ws-a")
                    .put("workspaceGeneration", 4),
            )
        }
    }

    @Test fun classifyForegroundWithWorkspaceIdAndGenerationIsLayer2() {
        val plane = SessionContract.classify(
            JSONObject()
                .put("sessionId", "default-foreground")
                .put("displayId", 0)
                .put("workspaceId", "ws-a")
                .put("workspaceGeneration", 4),
        )
        assertEquals(SessionPlaneKind.LAYER2_WORKSPACE, plane.kind)
        assertEquals("layer2_workspace", plane.wireKind)
        assertEquals("Layer 2 workspace", plane.label)
        assertEquals("default-foreground", plane.sessionId)
        assertEquals(0, plane.displayId)
        assertEquals("ws-a", plane.workspaceId)
        assertEquals(4L, plane.workspaceGeneration)
        assertEquals(4L, plane.toJson().getLong("workspaceGeneration"))
        assertEquals("layer2_workspace", plane.toJson().getString("kind"))
        assertEquals("ws-a", plane.toJson().getString("workspaceId"))
        assertEquals("Layer 2 workspace", plane.toJson().getString("label"))
    }

    @Test fun attachWritesPlaneKindLabelSessionIdAndDisplayId() {
        val plane = SessionContract.classify(
            JSONObject().put("sessionId", "default-foreground").put("displayId", 0),
        )
        val attached = SessionContract.attach(JSONObject().put("ok", true), plane)
        val fields = attached.getJSONObject("plane")
        assertEquals(plane.wireKind, fields.getString("kind"))
        assertEquals(plane.label, fields.getString("label"))
        assertEquals(plane.sessionId, fields.getString("sessionId"))
        assertEquals(plane.displayId, fields.getInt("displayId"))
        assertEquals(plane.sessionId, attached.getString("sessionId"))
        assertEquals(plane.displayId, attached.getInt("displayId"))
        assertTrue(attached.getBoolean("ok"))
    }

    @Test fun sessionIdSnakeAliasIsCopiedWhenCamelCaseOmitted() {
        val fromAlias = SessionContract.classify(
            JSONObject().put("session_id", "named-vd").put("displayId", 7),
        )
        assertEquals(SessionPlaneKind.SESSION_KERNEL_VD, fromAlias.kind)
        assertEquals("named-vd", fromAlias.sessionId)
        assertEquals(7, fromAlias.displayId)
        val matching = SessionContract.classify(
            JSONObject()
                .put("sessionId", "named-vd")
                .put("session_id", "named-vd")
                .put("displayId", 7),
        )
        assertEquals("named-vd", matching.sessionId)
        assertEquals(SessionPlaneKind.SESSION_KERNEL_VD, matching.kind)
    }

    @Test fun conflictingSessionIdAliasesFailClosed() {
        val ex = assertThrows(SessionIdentityException::class.java) {
            SessionContract.classify(
                JSONObject()
                    .put("sessionId", "A")
                    .put("session_id", "B")
                    .put("displayId", 7),
            )
        }
        assertEquals(SessionContract.SESSION_DISPLAY_MISMATCH, (ex as SessionIdentityException).errorClass)
    }

    @Test fun layer2RemainsSingleProductHotLock() {
        assertEquals(1, SessionKernel.PRODUCT_HOT_BACKGROUND_LIMIT)
        assertEquals(1, WorkspaceTasks.PRODUCT_HOT_BACKGROUND_LIMIT)
    }

    private fun assertForeground(plane: SessionPlane) {
        assertEquals(SessionPlaneKind.FOREGROUND, plane.kind)
        assertEquals("foreground", plane.wireKind)
        assertEquals("Foreground", plane.label)
        assertEquals("default-foreground", plane.sessionId)
        assertEquals(0, plane.displayId)
        assertEquals("foreground", plane.toJson().getString("kind"))
        assertEquals("default-foreground", plane.toJson().getString("sessionId"))
        assertEquals(0, plane.toJson().getInt("displayId"))
        assertEquals("Foreground", plane.toJson().getString("label"))
    }

    private fun assertErrorClass(expected: String, block: () -> Unit) {
        val ex = assertThrows(SessionIdentityException::class.java) { block() }
        assertEquals(expected, (ex as SessionIdentityException).errorClass)
    }
}
