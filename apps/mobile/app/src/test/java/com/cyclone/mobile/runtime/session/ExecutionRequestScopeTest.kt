package com.cyclone.mobile.runtime.session

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ExecutionRequestScopeTest {
    @Test fun legacyAndExplicitForegroundStillResolve() {
        assertEquals(ExecutionContext.DEFAULT, ExecutionRequestScope.requireForeground(JSONObject()))
        assertEquals(ExecutionContext.DEFAULT, ExecutionRequestScope.requireForeground(JSONObject()
            .put("sessionId", "default-foreground").put("displayId", 0)))
        assertEquals(ExecutionContext.DEFAULT, ExecutionRequestScope.bind(JSONObject()))
        assertEquals(ExecutionContext.DEFAULT, ExecutionRequestScope.read(JSONObject()))
    }

    @Test fun bindAllowsWorkspaceIdentityWithNonzeroDisplay() {
        val params = JSONObject().put("sessionId", "workspace-a").put("displayId", 8).put("x", 15)
        assertEquals(ExecutionContext("workspace-a", 8), ExecutionRequestScope.bind(params))
        assertEquals(ExecutionContext("workspace-a", 8), ExecutionRequestScope.read(params))
    }

    @Test fun bindAndReadRejectSilentDisplayZeroAndForegroundMismatch() {
        val rejected = listOf(
            "{\"sessionId\":\"B\"}",
            "{\"sessionId\":\"B\",\"displayId\":0}",
            "{\"displayId\":8}",
            "{\"sessionId\":\"default-foreground\",\"displayId\":8}",
            "{\"executionContext\":{\"sessionId\":\"B\"}}",
            "{\"executionContext\":{\"sessionId\":\"B\",\"displayId\":0}}",
            "{\"sessionId\":\"default-foreground\",\"executionContext\":{\"displayId\":8}}",
        )
        rejected.forEach { json ->
            assertThrows(SessionIdentityException::class.java) { ExecutionRequestScope.bind(JSONObject(json)) }
            assertThrows(SessionIdentityException::class.java) { ExecutionRequestScope.read(JSONObject(json)) }
        }
    }

    @Test fun malformedOrBackgroundIdentityCannotEnterLegacyDispatch() {
        val requests = listOf(
            "{\"sessionId\":\"B\"}", "{\"sessionId\":\"B\",\"displayId\":0}",
            "{\"sessionId\":\"B\",\"displayId\":8}", "{\"displayId\":8}",
            "{\"sessionId\":\"\"}", "{\"sessionId\":null}", "{\"sessionId\":42}",
            "{\"displayId\":\"0\"}", "{\"displayId\":0.5}", "{\"displayId\":-1}",
            "{\"displayId\":4294967296}", "{\"executionContext\":null}",
            "{\"executionContext\":{\"sessionId\":\"B\",\"displayId\":8}}",
            "{\"sessionId\":\"default-foreground\",\"executionContext\":{\"sessionId\":\"B\",\"displayId\":8}}",
        )
        var dispatched = 0
        requests.forEach { json ->
            try { ExecutionRequestScope.requireForeground(JSONObject(json)); dispatched++ }
            catch (_: SessionIdentityException) { }
        }
        assertEquals("Invalid scopes must never reach even read-only foreground side effects", 0, dispatched)
    }

    @Test fun requireForegroundStillRejectsWorkspaceEvenWhenBindWouldAllowIt() {
        val workspace = JSONObject().put("sessionId", "B").put("displayId", 8)
        assertEquals(ExecutionContext("B", 8), ExecutionRequestScope.bind(workspace))
        assertThrows(SessionIdentityException::class.java) { ExecutionRequestScope.requireForeground(workspace) }
    }

    @Test fun envelopeIdentitySurvivesNormalizationAndConflictsAreRejected() {
        val envelope = JSONObject().put("sessionId", "B").put("displayId", 8)
        val scoped = ExecutionRequestScope.merge(envelope, JSONObject().put("x", 15))
        assertEquals(ExecutionContext("B", 8), ExecutionRequestScope.read(scoped))
        assertEquals(15, scoped.getInt("x"))
        assertThrows(SessionIdentityException::class.java) {
            ExecutionRequestScope.merge(envelope, JSONObject().put("sessionId", "default-foreground"))
        }
    }

    @Test fun attachWritesIdentityWithoutDroppingOtherKeys() {
        val attached = ExecutionRequestScope.attach(JSONObject().put("x", 15).put("elementId", "semantic:1"), ExecutionContext("B", 8))
        assertEquals("B", attached.getString("sessionId"))
        assertEquals(8, attached.getInt("displayId"))
        assertEquals(15, attached.getInt("x"))
        assertEquals("semantic:1", attached.getString("elementId"))
        assertThrows(SessionIdentityException::class.java) {
            ExecutionRequestScope.attach(JSONObject().put("sessionId", "A").put("displayId", 7), ExecutionContext("B", 8))
        }
    }
}
