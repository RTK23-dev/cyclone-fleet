package com.cyclone.mobile.gateway

import android.content.Context
import android.content.ContextWrapper
import com.cyclone.mobile.ai.vision.live.LiveVisionRuntime
import com.cyclone.mobile.runtime.session.ExecutionBackendKind
import com.cyclone.mobile.runtime.session.ExecutionSession
import com.cyclone.mobile.runtime.session.ExecutionSessionStore
import com.cyclone.mobile.runtime.session.SessionIdentityException
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class GatewaySessionAdapterTest {
    private val context = object : ContextWrapper(null) {
        override fun getPackageName(): String = "com.cyclone.mobile"
        override fun getApplicationContext(): Context = this
    }

    private lateinit var baselineIds: Set<String>

    @Before
    fun captureBaseline() {
        baselineIds = LiveVisionRuntime.sessions.snapshot().map { it.sessionId }.toSet()
    }

    @After
    fun restoreSessions() {
        LiveVisionRuntime.sessions.snapshot()
            .filter { it.sessionId !in baselineIds }
            .forEach { session -> runCatching { LiveVisionRuntime.sessions.remove(session.sessionId) } }
    }

    @Test
    fun listIncludesDefaultForegroundExecutableDisplayZero() {
        val listed = GatewaySessionAdapter.list(context)
        assertEquals("cyclone.one.session.v1", listed.getString("protocol"))
        val sessions = listed.getJSONArray("sessions")
        val foreground = (0 until sessions.length())
            .map { sessions.getJSONObject(it) }
            .first { it.getString("sessionId") == ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID }
        assertEquals(0, foreground.getInt("displayId"))
        assertTrue(foreground.getBoolean("executable"))
        assertEquals("FOREGROUND", foreground.getString("state"))
        assertEquals("FOREGROUND_ACCESSIBILITY", foreground.getString("backend"))
        assertEquals("com.cyclone.mobile", foreground.getString("appPackage"))
    }

    @Test
    fun listIncludesRegisteredWorkspaceSessions() {
        LiveVisionRuntime.sessions.registerOwned("workspace-adapter-list", 12, "com.example.app")
        val listed = GatewaySessionAdapter.list(context)
        val sessions = listed.getJSONArray("sessions")
        val ids = (0 until sessions.length()).map { sessions.getJSONObject(it).getString("sessionId") }
        assertTrue(ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID in ids)
        assertTrue("workspace-adapter-list" in ids)
        val workspace = (0 until sessions.length())
            .map { sessions.getJSONObject(it) }
            .first { it.getString("sessionId") == "workspace-adapter-list" }
        assertEquals(12, workspace.getInt("displayId"))
        assertTrue(workspace.getBoolean("executable"))
        assertEquals("com.example.app", workspace.getString("targetPackage"))
        assertEquals("SHIZUKU", workspace.getString("backend"))
    }

    @Test
    fun startWithoutIsolatedDisplayFailsClosedAndDoesNotSubstituteDisplayZero() {
        val before = LiveVisionRuntime.sessions.snapshot().map { it.sessionId }.toSet()
        try {
            GatewaySessionAdapter.start(context, JSONObject().put("package", "com.android.settings"))
            fail("session.start must fail closed without an isolated Shizuku display")
        } catch (error: GatewayProtocolException) {
            assertTrue(
                "expected BACKGROUND_MODE_UNAVAILABLE or similar, got ${error.code}: ${error.message}",
                error.code in setOf("BACKGROUND_MODE_UNAVAILABLE", "CAPABILITY_UNAVAILABLE"),
            )
        }
        val after = LiveVisionRuntime.sessions.snapshot()
        assertEquals(before, after.map { it.sessionId }.toSet())
        assertFalse(after.any { !it.isDefaultForeground && it.displayId == 0 })
        assertFalse(after.any { it.sessionId != ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID && it.displayId == 0 })
    }

    @Test
    fun startRequiresPackage() {
        try {
            GatewaySessionAdapter.start(context, JSONObject())
            fail("package is required")
        } catch (error: GatewayProtocolException) {
            assertEquals("INVALID_REQUEST", error.code)
        }
    }

    @Test
    fun requireBackgroundRefusesDefaultForegroundLifecycle() {
        val args = JSONObject().put("sessionId", ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID)
        for (op in listOf("pause", "resume", "handoff", "stop")) {
            try {
                when (op) {
                    "pause" -> GatewaySessionAdapter.pause(context, args)
                    "resume" -> GatewaySessionAdapter.resume(context, args)
                    "handoff" -> GatewaySessionAdapter.handoff(context, args)
                    "stop" -> GatewaySessionAdapter.stop(args)
                }
                fail("$op must refuse default-foreground")
            } catch (error: GatewayProtocolException) {
                assertEquals("PROTOCOL_MISMATCH", error.code)
                assertTrue(error.message.contains("default foreground"))
            }
        }
    }

    @Test
    fun namedWorkspaceDisplayZeroIsRejected() {
        try {
            LiveVisionRuntime.sessions.registerOwned("workspace-display-zero", 0, "com.example.app")
            fail("owned sessions must not map onto display 0")
        } catch (_: SessionIdentityException) {
        }
        try {
            GatewaySessionAdapter.snapshot(
                context,
                JSONObject().put("sessionId", "workspace-missing").put("displayId", 0),
            )
            fail("named workspace snapshot must not accept display 0")
        } catch (error: GatewayProtocolException) {
            assertEquals("PROTOCOL_MISMATCH", error.code)
        }
        try {
            GatewaySessionAdapter.snapshot(
                context,
                JSONObject().put("session_id", "workspace-missing").put("display_id", 0),
            )
            fail("named workspace snapshot must not accept snake_case display 0")
        } catch (error: GatewayProtocolException) {
            assertEquals("PROTOCOL_MISMATCH", error.code)
        }
        try {
            ExecutionSessionStore().registerOwned("workspace-store-zero", 0, "com.example.app")
            fail("store must reject named workspace display 0")
        } catch (_: SessionIdentityException) {
        }
    }

    @Test
    fun listOmitsNamedDisplayZeroSyntheticSessions() {
        LiveVisionRuntime.sessions.registerSynthetic(
            "shared-context",
            0,
            ExecutionBackendKind.VIRTUAL_DISPLAY,
        )
        val listed = GatewaySessionAdapter.list(context)
        val sessions = listed.getJSONArray("sessions")
        val ids = (0 until sessions.length()).map { sessions.getJSONObject(it).getString("sessionId") }
        assertTrue(ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID in ids)
        assertFalse("shared-context" in ids)
        assertFalse(
            (0 until sessions.length()).map { sessions.getJSONObject(it) }.any {
                it.getString("sessionId") != ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID &&
                    it.getInt("displayId") == 0
            },
        )
    }

    @Test
    fun snapshotOfNonExecutableWorkspaceFailsClosedWithoutForegroundSubstitution() {
        LiveVisionRuntime.sessions.registerSynthetic(
            "workspace-synthetic",
            14,
            ExecutionBackendKind.VIRTUAL_DISPLAY,
            "com.example.app",
        )
        try {
            GatewaySessionAdapter.snapshot(
                context,
                JSONObject().put("sessionId", "workspace-synthetic").put("displayId", 14),
            )
            fail("synthetic metadata must not yield an exact-session frame")
        } catch (error: GatewayProtocolException) {
            assertEquals("BACKGROUND_MODE_UNAVAILABLE", error.code)
        }
    }

    @Test
    fun statusReturnsRegisteredWorkspaceWithoutDisplayZeroSubstitution() {
        LiveVisionRuntime.sessions.registerOwned("workspace-adapter-status", 15, "com.example.app")
        val status = GatewaySessionAdapter.status(
            context,
            JSONObject().put("sessionId", "workspace-adapter-status"),
        )
        assertEquals("workspace-adapter-status", status.getString("sessionId"))
        assertEquals(15, status.getInt("displayId"))
        assertTrue(status.getBoolean("executable"))
        assertFalse(status.optBoolean("foregroundSubstitution", false))
    }

    @Test
    fun sessionOperationsAreRegisteredAndMutationsStayTrustedOnly() {
        val ops = listOf(
            "session.list",
            "session.start",
            "session.status",
            "session.pause",
            "session.continue",
            "session.resume",
            "session.handoff",
            "session.stop",
            "session.snapshot",
        )
        ops.forEach { GatewayProtocol.requireKnownOperation(it) }
        assertTrue("session.list" in GatewayProtocol.legacyReadOnlyOperations)
        assertTrue("session.list" in GatewayProtocol.operations)
        for (mutating in ops.filter { it != "session.list" }) {
            assertFalse(mutating in GatewayProtocol.legacyReadOnlyOperations)
            assertFalse(mutating in GatewayProtocol.unauthenticatedOperations)
        }
        assertFalse("session.start" in GatewayProtocol.unauthenticatedOperations)
    }
}
