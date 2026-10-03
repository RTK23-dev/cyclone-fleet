package com.cyclone.mobile.gateway

import com.cyclone.mobile.runtime.session.SessionContract
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayPlaneMetadataTest {
    @Test fun foregroundPlaneJsonExposesOneGlassFields() {
        assertOneGlassFields(
            SessionContract.classify(
                JSONObject().put("sessionId", "default-foreground").put("displayId", 0),
            ).toJson(),
            kind = "foreground",
            label = "Foreground",
            sessionId = "default-foreground",
            displayId = 0,
        )
    }

    @Test fun sessionKernelVdPlaneJsonExposesOneGlassFields() {
        assertOneGlassFields(
            SessionContract.classify(
                JSONObject().put("sessionId", "named-vd").put("displayId", 7),
            ).toJson(),
            kind = "session_kernel_vd",
            label = "Session Kernel VD",
            sessionId = "named-vd",
            displayId = 7,
        )
    }

    @Test fun layer2PlaneJsonExposesOneGlassFields() {
        val json = SessionContract.classify(
            JSONObject()
                .put("sessionId", "default-foreground")
                .put("displayId", 0)
                .put("workspaceId", "ws-a")
                .put("workspaceGeneration", 4),
        ).toJson()
        assertOneGlassFields(
            json,
            kind = "layer2_workspace",
            label = "Layer 2 workspace",
            sessionId = "default-foreground",
            displayId = 0,
        )
        assertEquals("ws-a", json.getString("workspaceId"))
        assertEquals(4L, json.getLong("workspaceGeneration"))
    }

    @Test fun attachWritesNestedPlaneMetadataForOneGlass() {
        val plane = SessionContract.classify(
            JSONObject().put("sessionId", "named-vd").put("displayId", 7),
        )
        val attached = SessionContract.attach(JSONObject().put("ok", true), plane)
        val fields = attached.getJSONObject("plane")
        assertOneGlassFields(
            fields,
            kind = plane.wireKind,
            label = plane.label,
            sessionId = plane.sessionId,
            displayId = plane.displayId,
        )
        assertEquals(plane.sessionId, attached.getString("sessionId"))
        assertEquals(plane.displayId, attached.getInt("displayId"))
        assertTrue(attached.getBoolean("ok"))
    }

    private fun assertOneGlassFields(
        json: JSONObject,
        kind: String,
        label: String,
        sessionId: String,
        displayId: Int,
    ) {
        assertTrue(json.has("kind"))
        assertTrue(json.has("label"))
        assertTrue(json.has("sessionId"))
        assertTrue(json.has("displayId"))
        assertEquals(kind, json.getString("kind"))
        assertEquals(label, json.getString("label"))
        assertEquals(sessionId, json.getString("sessionId"))
        assertEquals(displayId, json.getInt("displayId"))
    }
}
