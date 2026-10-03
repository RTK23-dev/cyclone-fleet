package com.cyclone.mobile.fastpath

import com.cyclone.mobile.runtime.session.ExecutionSession
import com.cyclone.mobile.runtime.session.SessionContract
import com.cyclone.mobile.runtime.session.SessionIdentityException
import com.cyclone.mobile.runtime.session.SessionPlaneKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NamedWorkspaceFastPathTest {
    @Test
    fun chromeSearchOnNamedVdBindsSessionDisplayAndStaysWithin90sBudget() {
        val clock = Clock()
        val recorder = ActRecorder()
        val result = NamedWorkspaceFastPath.runAcceptance(
            sessionId = NamedWorkspaceFastPath.ACCEPTANCE_SESSION_ID,
            displayId = NamedWorkspaceFastPath.ACCEPTANCE_DISPLAY_ID,
            act = recorder.port(),
            observeFingerprint = recorder.observe,
            sleepMs = clock.sleepMs,
            nowMs = clock.nowMs,
        )
        assertEquals("named-vd", result.sessionId)
        assertNotEquals(ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID, result.sessionId)
        assertEquals(7, result.displayId)
        assertNotEquals(0, result.displayId)
        assertTrue(result.ok)
        assertEquals("session_kernel_vd", result.planeKind)
        assertTrue(result.withinBudget)
        assertTrue(result.elapsedMs <= NamedWorkspaceFastPath.BUDGET_MS)
        assertTrue(result.elapsedMs <= 90_000L)
        assertFalse(result.usedLlm)
        assertFalse(result.usedVision)
        assertTrue(result.secondClickSuppressed)
        assertNull(result.errorClass)
        assertEquals("phone.open_app", result.landingTool)
        assertEquals("com.android.chrome", result.landingPackage)
        assertEquals(3, result.stepsRun)
        assertEquals(listOf("phone.open_app", "phone.type", "phone.click"), recorder.tools)
        recorder.calls.forEach { call ->
            assertEquals("named-vd", call.sessionId)
            assertEquals(7, call.displayId)
            assertEquals("named-vd", call.params.getString("sessionId"))
            assertEquals(7, call.params.getInt("displayId"))
            assertFalse(call.params.has("workspaceId"))
            assertNotEquals(ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID, call.params.getString("sessionId"))
            assertNotEquals(0, call.params.getInt("displayId"))
        }
        assertEquals("com.android.chrome", recorder.calls[0].params.getString("package"))
        assertEquals(NamedWorkspaceFastPath.SEARCH_QUERY, recorder.calls[1].params.getString("value"))
        assertEquals("Search", recorder.calls[2].params.getJSONObject("selector").getString("text"))
        val artifact = result.artifact
        assertEquals("chrome-search-named-vd", artifact.getString("scenario"))
        assertEquals("named-vd", artifact.getString("sessionId"))
        assertEquals(7, artifact.getInt("displayId"))
        assertEquals("session_kernel_vd", artifact.getString("planeKind"))
        assertEquals(NamedWorkspaceFastPath.ACCEPTANCE_GOAL, artifact.getString("goal"))
        assertEquals(NamedWorkspaceFastPath.BUDGET_MS, artifact.getLong("budgetMs"))
        assertEquals(result.elapsedMs, artifact.getLong("elapsedMs"))
        assertTrue(artifact.getBoolean("withinBudget"))
        assertEquals("UNVERIFIED", artifact.getString("physicalPixel8"))
        assertTrue(artifact.getBoolean("secondClickSuppressed"))
        assertEquals("phone.open_app", artifact.getString("landingTool"))
        assertEquals("com.android.chrome", artifact.getString("landingPackage"))
        assertEquals(result.stepsRun, artifact.getInt("stepsRun"))
        val landing = FastPathLanding.resolve(NamedWorkspaceFastPath.ACCEPTANCE_GOAL)
        assertEquals("phone.open_app", landing?.tool)
        assertEquals("com.android.chrome", landing?.packageName)
        val plane = NamedWorkspaceFastPath.requireOwnedVd("named-vd", 7)
        assertEquals(SessionPlaneKind.SESSION_KERNEL_VD, plane.kind)
        assertEquals("session_kernel_vd", plane.wireKind)
        assertEquals(7, plane.displayId)
    }

    @Test
    fun defaultForegroundIsRejected() {
        val recorder = ActRecorder()
        val clock = Clock()
        val ex = assertThrows(SessionIdentityException::class.java) {
            NamedWorkspaceFastPath.requireOwnedVd(
                ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID,
                ExecutionSession.DEFAULT_DISPLAY_ID,
            )
        }
        assertEquals(SessionContract.SESSION_DISPLAY_MISMATCH, ex.errorClass)
        val result = NamedWorkspaceFastPath.runAcceptance(
            sessionId = ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID,
            displayId = 0,
            act = recorder.port(),
            observeFingerprint = recorder.observe,
            sleepMs = clock.sleepMs,
            nowMs = clock.nowMs,
        )
        assertFalse(result.ok)
        assertEquals(SessionContract.SESSION_DISPLAY_MISMATCH, result.errorClass)
        assertEquals(ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID, result.sessionId)
        assertEquals(0, result.displayId)
        assertEquals(0, result.stepsRun)
        assertTrue(recorder.calls.isEmpty())
        assertNotEquals("named-vd", result.sessionId)
        assertNotEquals(7, result.displayId)
    }

    @Test
    fun namedSessionDisplayZeroIsRejected() {
        val recorder = ActRecorder()
        val clock = Clock()
        val ex = assertThrows(SessionIdentityException::class.java) {
            NamedWorkspaceFastPath.requireOwnedVd("named-vd", 0)
        }
        assertEquals(SessionContract.SESSION_DISPLAY_MISMATCH, ex.errorClass)
        val attach = assertThrows(SessionIdentityException::class.java) {
            NamedWorkspaceFastPath.attachIdentity(JSONObject(), "named-vd", 0)
        }
        assertEquals(SessionContract.SESSION_DISPLAY_MISMATCH, attach.errorClass)
        val result = NamedWorkspaceFastPath.runAcceptance(
            sessionId = "named-vd",
            displayId = 0,
            act = recorder.port(),
            observeFingerprint = recorder.observe,
            sleepMs = clock.sleepMs,
            nowMs = clock.nowMs,
        )
        assertFalse(result.ok)
        assertEquals(SessionContract.SESSION_DISPLAY_MISMATCH, result.errorClass)
        assertEquals("named-vd", result.sessionId)
        assertEquals(0, result.displayId)
        assertEquals(0, result.stepsRun)
        assertTrue(recorder.calls.isEmpty())
    }

    @Test
    fun layer2MixIsRejected() {
        val recorder = ActRecorder()
        val mixed = JSONObject()
            .put("sessionId", NamedWorkspaceFastPath.ACCEPTANCE_SESSION_ID)
            .put("displayId", NamedWorkspaceFastPath.ACCEPTANCE_DISPLAY_ID)
            .put("workspaceId", "ws-a")
            .put("workspaceGeneration", 4)
        val classify = assertThrows(SessionIdentityException::class.java) {
            SessionContract.classify(mixed)
        }
        assertEquals(SessionContract.PLANE_MISMATCH, classify.errorClass)
        val owned = assertThrows(SessionIdentityException::class.java) {
            NamedWorkspaceFastPath.requireOwnedVd(mixed)
        }
        assertEquals(SessionContract.PLANE_MISMATCH, owned.errorClass)
        val attach = assertThrows(SessionIdentityException::class.java) {
            NamedWorkspaceFastPath.attachIdentity(
                JSONObject().put("workspaceId", "ws-a").put("workspaceGeneration", 4),
                "named-vd",
                7,
            )
        }
        assertEquals(SessionContract.PLANE_MISMATCH, attach.errorClass)
        assertTrue(recorder.calls.isEmpty())
        val plane = NamedWorkspaceFastPath.requireOwnedVd("named-vd", 7)
        assertEquals(SessionPlaneKind.SESSION_KERNEL_VD, plane.kind)
        assertNull(plane.workspaceId)
    }

    @Test
    fun unchangedDoesNotAuthorizeSecondClick() {
        val clock = Clock()
        val recorder = ActRecorder(changeOnNav = false)
        val result = NamedWorkspaceFastPath.runAcceptance(
            sessionId = "named-vd",
            displayId = 7,
            act = recorder.port(),
            observeFingerprint = recorder.observe,
            sleepMs = clock.sleepMs,
            nowMs = clock.nowMs,
        )
        assertFalse(result.ok)
        assertEquals("UNCHANGED", result.errorClass)
        assertEquals(1, result.stepsRun)
        assertEquals(1, recorder.calls.size)
        assertEquals("phone.open_app", recorder.calls.single().tool)
        assertTrue(result.secondClickSuppressed)
        assertEquals(listOf(300L, 500L, 1_000L), clock.sleeps)
        val settle = FastPathSettleResult(
            changed = false,
            verified = false,
            observations = 3,
            elapsedMs = 1_800L,
            warning = FastPathTimings.UNCHANGED_WARNING,
            afterFingerprint = "same",
        )
        assertFalse(FastPathLoop.allowSecondClickChannel(actionPerformed = true, settle = settle))
        assertTrue(result.elapsedMs <= NamedWorkspaceFastPath.BUDGET_MS)
    }

    @Test
    fun navIsolationDropsLaterNavInSameTurn() {
        val isolated = FastPathNavIsolation.keepPlanned(
            listOf(
                FastPathPlannedAction("phone.click", expectedPageChange = true),
                FastPathPlannedAction("phone.click", expectedPageChange = true),
            ),
        )
        assertEquals(1, isolated.allowed.size)
        assertEquals("phone.click", isolated.allowed.single().tool)
        assertEquals(1, isolated.dropped.size)
        assertTrue(isolated.truncated)
        val turns = NamedWorkspaceFastPath.chromeSearchTurns()
        assertEquals("phone.open_app", turns[0].single().tool)
        assertEquals(listOf("phone.type", "phone.click"), turns[1].map { it.tool })
        turns.forEach { turn ->
            val navs = turn.count { FastPathNavIsolation.isScreenChanging(it.tool, it.expectedPageChange) }
            assertTrue(navs <= 1)
            assertEquals(navs, FastPathNavIsolation.keepPlanned(turn).allowed.count {
                FastPathNavIsolation.isScreenChanging(it.tool, it.expectedPageChange)
            })
        }
    }

    @Test
    fun takeControlUserPausedDoesNotAct() {
        val recorder = ActRecorder()
        val clock = Clock()
        val result = NamedWorkspaceFastPath.runAcceptance(
            sessionId = "named-vd",
            displayId = 7,
            act = recorder.port(),
            observeFingerprint = recorder.observe,
            sleepMs = clock.sleepMs,
            nowMs = clock.nowMs,
            userPaused = true,
        )
        assertFalse(result.ok)
        assertEquals("USER_PAUSED", result.errorClass)
        assertEquals("session_kernel_vd", result.planeKind)
        assertEquals(0, result.stepsRun)
        assertTrue(recorder.calls.isEmpty())
        assertTrue(clock.sleeps.isEmpty())
        assertFalse(result.usedLlm)
        assertFalse(result.usedVision)
    }

    @Test
    fun gateRequiredDoesNotAct() {
        val recorder = ActRecorder()
        val clock = Clock()
        val result = NamedWorkspaceFastPath.runAcceptance(
            sessionId = "named-vd",
            displayId = 7,
            act = recorder.port(),
            observeFingerprint = recorder.observe,
            sleepMs = clock.sleepMs,
            nowMs = clock.nowMs,
            gateRequired = true,
        )
        assertFalse(result.ok)
        assertEquals("GATE_REQUIRED", result.errorClass)
        assertEquals("session_kernel_vd", result.planeKind)
        assertEquals(0, result.stepsRun)
        assertTrue(recorder.calls.isEmpty())
        assertTrue(clock.sleeps.isEmpty())
        assertFalse(result.usedLlm)
        assertFalse(result.usedVision)
    }

    private class Clock {
        var now = 0L
        val sleeps = mutableListOf<Long>()
        val nowMs: () -> Long = { now }
        val sleepMs: (Long) -> Unit = { ms ->
            sleeps += ms
            now += ms
        }
    }

    private class ActRecorder(private val changeOnNav: Boolean = true) {
        data class Call(val tool: String, val params: JSONObject, val sessionId: String, val displayId: Int)

        val calls = mutableListOf<Call>()
        var fingerprint = "before"
        val tools: List<String> get() = calls.map { it.tool }
        val observe: (String, Int) -> String? = { sessionId, displayId ->
            assertEquals("named-vd", sessionId)
            assertEquals(7, displayId)
            fingerprint
        }

        fun port() = NamedWorkspaceActPort { tool, params, sessionId, displayId ->
            calls += Call(tool, JSONObject(params.toString()), sessionId, displayId)
            if (changeOnNav && FastPathNavIsolation.isScreenChanging(tool, expectedPageChange = tool != "phone.type")) {
                fingerprint = "after-${calls.size}"
            }
            NamedWorkspaceActOutcome(ok = true, afterFingerprint = fingerprint)
        }
    }
}
