package com.cyclone.mobile.gateway

import com.cyclone.mobile.runtime.background.WorkspaceLease
import com.cyclone.mobile.runtime.background.WorkspaceLifecycle
import com.cyclone.mobile.runtime.background.WorkspaceState
import com.cyclone.mobile.runtime.session.ExecutionBackendKind
import com.cyclone.mobile.runtime.session.ExecutionContext
import com.cyclone.mobile.runtime.session.ExecutionRequestScope
import com.cyclone.mobile.runtime.session.ExecutionSession
import com.cyclone.mobile.runtime.session.ExecutionSessionStore
import com.cyclone.mobile.runtime.session.SessionIdentityException
import com.cyclone.mobile.runtime.session.SessionKernel
import com.cyclone.mobile.runtime.session.SessionObservationStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewaySessionBindingTest {
    @Test
    fun bindAllowsDefaultForegroundAndWorkspaceIdentity() {
        assertEquals(ExecutionContext.DEFAULT, ExecutionRequestScope.bind(JSONObject()))
        assertEquals(
            ExecutionContext("workspace-a", 12),
            ExecutionRequestScope.bind(JSONObject().put("sessionId", "workspace-a").put("displayId", 12)),
        )
    }

    @Test
    fun requireForegroundStillRejectsWorkspace() {
        assertThrows(SessionIdentityException::class.java) {
            ExecutionRequestScope.requireForeground(JSONObject().put("sessionId", "workspace-a").put("displayId", 12))
        }
        assertEquals(ExecutionContext.DEFAULT, ExecutionRequestScope.requireForeground(JSONObject()
            .put("sessionId", ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID).put("displayId", 0)))
    }

    @Test
    fun mergeCarriesSnakeCaseIdentityAndBindRejectsNamedDisplayZero() {
        val merged = ExecutionRequestScope.merge(
            JSONObject().put("session_id", "workspace-a").put("display_id", 12),
            JSONObject().put("elementId", "semantic:1"),
        )
        assertEquals("workspace-a", merged.getString("sessionId"))
        assertEquals(12, merged.getInt("displayId"))
        assertEquals("semantic:1", merged.getString("elementId"))
        assertThrows(SessionIdentityException::class.java) {
            ExecutionRequestScope.bind(
                JSONObject().put("session_id", "workspace-a").put("display_id", 0),
            )
        }
        assertThrows(SessionIdentityException::class.java) {
            ExecutionRequestScope.merge(
                JSONObject().put("session_id", "workspace-a").put("displayId", 0),
                JSONObject(),
            ).also { ExecutionRequestScope.bind(it) }
        }
    }

    @Test
    fun mergeAndAttachCarryIdentityOntoParamsWithoutDroppingKeys() {
        val envelope = JSONObject().put("sessionId", "workspace-a").put("displayId", 12)
        val merged = ExecutionRequestScope.merge(envelope, JSONObject().put("elementId", "semantic:1"))
        assertEquals("workspace-a", merged.getString("sessionId"))
        assertEquals(12, merged.getInt("displayId"))
        assertEquals("semantic:1", merged.getString("elementId"))
        val attached = ExecutionRequestScope.attach(JSONObject().put("tool", "phone.click"), ExecutionContext("workspace-a", 12))
        assertEquals("phone.click", attached.getString("tool"))
        assertEquals("workspace-a", attached.getString("sessionId"))
        assertEquals(12, attached.getInt("displayId"))
        assertThrows(SessionIdentityException::class.java) {
            ExecutionRequestScope.merge(envelope, JSONObject().put("sessionId", "workspace-b"))
        }
    }

    @Test
    fun observationLookupIsSessionScopedAndCrossSessionActionIsRejected() {
        val sessions = ExecutionSessionStore()
        sessions.registerSynthetic("A", 12, ExecutionBackendKind.SHIZUKU)
        sessions.registerSynthetic("B", 13, ExecutionBackendKind.SHIZUKU)
        val observations = SessionObservationStore(sessions)
        observations.publish("A", 12, "obs-a", "payload-a", 1L)
        observations.publish("B", 13, "obs-b", "payload-b", 2L)

        assertEquals("obs-a", observations.current("A", 12)?.observationId)
        assertEquals("obs-b", observations.current("B", 13)?.observationId)
        assertNotEquals(observations.current("A", 12)?.observationId, observations.current("B", 13)?.observationId)
        assertThrows(SessionIdentityException::class.java) { observations.current("A", 13) }
        assertThrows(SessionIdentityException::class.java) { observations.associate("B", 13, "obs-a") }
        assertThrows(SessionIdentityException::class.java) { sessions.requireSessionDisplay("A", 13) }

        val leaseA = WorkspaceLifecycle("A", 12).apply { transition(WorkspaceState.BACKGROUND_OK) }
        val lifecycleB = WorkspaceLifecycle("B", 13).apply { transition(WorkspaceState.BACKGROUND_OK) }
        assertThrows(IllegalStateException::class.java) {
            lifecycleB.requireMutation(WorkspaceLease("A", 12, leaseA.generation), true, true)
        }
    }

    @Test
    fun apiCapacityAllowsNSessionsWhileProductHotGateStaysOne() {
        val store = ExecutionSessionStore()
        store.registerSynthetic("hidden-a", 12, ExecutionBackendKind.VIRTUAL_DISPLAY)
        store.registerSynthetic("hidden-b", 13, ExecutionBackendKind.VIRTUAL_DISPLAY)
        assertTrue(SessionKernel.list(store).size >= 3)
        assertTrue(SessionKernel.API_SESSION_CAPACITY >= 2)
        assertEquals(1, SessionKernel.PRODUCT_HOT_BACKGROUND_LIMIT)
    }
}
