package com.cyclone.mobile.skills

import com.cyclone.mobile.runtime.session.ExecutionSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillRouteCompilerTest {
    @Test
    fun oneSuccessfulFastPathRunDoesNotCompile() {
        val store = PlaybookHintStore.inMemory()
        val recorded = store.recordSuccess(hint(successCount = 1, lastSuccessAtMs = 1_000L))
        assertNotNull(recorded)
        assertEquals(1, recorded!!.successCount)

        val result = SkillRouteCompiler.compile(recorded)
        assertNull(result.route)
        assertFalse(result.compiled)
        assertTrue(result.rejected!!.contains("need"))
    }

    @Test
    fun twoIdenticalSuccessfulRunsCompileIntoPhoneToolExecutorRoute() {
        val store = PlaybookHintStore.inMemory()
        store.recordSuccess(hint(lastSuccessAtMs = 1_000L))
        val recorded = store.recordSuccess(hint(lastSuccessAtMs = 2_000L))
        assertNotNull(recorded)
        assertEquals(2, recorded!!.successCount)

        val result = SkillRouteCompiler.compile(recorded)
        val route = result.route
        assertNotNull(route)
        assertTrue(result.compiled)
        assertNull(result.rejected)
        assertEquals(ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID, route!!.sessionId)
        assertEquals(0, route.displayId)
        assertTrue(route.id.startsWith("compiled-skill."))
        assertTrue(route.id.startsWith(CompiledSkillIds.PREFIX))
        assertEquals("PhoneToolExecutor", route.toJson().getString("mutationEngine"))
        assertEquals(2, route.steps.size)
        assertEquals(2, route.compiledFromSuccesses)
        route.steps.forEach { step ->
            assertEquals("phone.click", step.tool)
            assertTrue(step.selector.isSemantic)
            val params = step.toPhoneParams()
            assertTrue(params.has("selector"))
            assertTrue(params.getJSONObject("selector").optBoolean("coordinateFree"))
        }
    }

    @Test
    fun userOverrideCompilesAfterSingleMergeEvenWhenSuccessCountWasOne() {
        val store = PlaybookHintStore.inMemory()
        val first = store.recordSuccess(hint(successCount = 1, lastSuccessAtMs = 1_000L))
        assertNotNull(first)
        assertEquals(1, first!!.successCount)
        assertFalse(first.userOverride)

        val overridden = store.mergeUserOverride(hint(successCount = 1, lastSuccessAtMs = 2_000L))
        assertNotNull(overridden)
        assertTrue(overridden!!.userOverride)
        assertEquals(PlaybookSource.USER_OVERRIDE, overridden.source)
        assertTrue(overridden.successCount >= SkillRouteCompiler.MIN_SUCCESSES)

        val result = SkillRouteCompiler.compile(overridden)
        assertNotNull(result.route)
        assertTrue(result.compiled)
        assertEquals(overridden.successCount, result.route!!.compiledFromSuccesses)
    }

    @Test
    fun workspacePlaybookKeepsSessionAndDisplayBindingAndRejectsDisplayZero() {
        val store = PlaybookHintStore.inMemory()
        val workspace = hint(sessionId = "workspace-a", displayId = 8, lastSuccessAtMs = 1_000L)
        store.recordSuccess(workspace)
        val recorded = store.recordSuccess(workspace.copy(lastSuccessAtMs = 2_000L))
        assertNotNull(recorded)

        val result = SkillRouteCompiler.compile(recorded!!)
        assertNotNull(result.route)
        assertEquals("workspace-a", result.route!!.sessionId)
        assertEquals(8, result.route.displayId)
        assertTrue(result.route.isWorkspace)

        val error = assertThrows(IllegalArgumentException::class.java) {
            hint(sessionId = "workspace-a", displayId = 0)
        }
        assertTrue(error.message!!.contains("display 0"))
    }

    @Test
    fun consequentialPayOrDeleteGoalIsNotCompiled() {
        for (goal in listOf("pay the electricity bill", "delete old photos")) {
            val result = SkillRouteCompiler.compile(hint(goal = goal, successCount = 2))
            assertNull(goal, result.route)
            assertFalse(result.compiled)
            assertTrue(result.rejected!!.contains("approval-sensitive") || result.rejected.contains("goal"))
        }
    }

    @Test
    fun unsafeShareToolIsNotCompiled() {
        val steps = listOf(
            clickSteps().first(),
            PlaybookHintStep(
                nl = "share the page",
                tool = "phone.share",
                selector = SemanticSelector(text = "Share"),
                beforePageKey = "settings.apps",
                afterPageKey = "settings.share",
                expectedPageChange = true,
            ),
        )
        val result = SkillRouteCompiler.compile(hint(steps = steps, successCount = 2))
        assertNull(result.route)
        assertFalse(result.compiled)
        assertTrue(result.rejected!!.contains("unsafe tool"))
        assertTrue(result.rejected.contains("phone.share"))
    }

    @Test
    fun matchReturnsCompiledRouteForSameBindingAndNullOnDisplayMismatch() {
        val store = PlaybookHintStore.inMemory()
        store.recordSuccess(hint(lastSuccessAtMs = 1_000L))
        val recorded = store.recordSuccess(hint(lastSuccessAtMs = 2_000L))
        val route = SkillRouteCompiler.compile(recorded!!).route!!

        val hit = SkillRouteCompiler.match(
            routes = listOf(route),
            packageName = "com.android.settings",
            goal = "Open battery settings",
            startPageKey = "settings.home",
            sessionId = ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID,
            displayId = 0,
        )
        assertNotNull(hit)
        assertEquals(route.id, hit!!.id)
        assertEquals(route.sessionId, hit.sessionId)
        assertEquals(route.displayId, hit.displayId)

        val miss = SkillRouteCompiler.match(
            routes = listOf(route),
            packageName = "com.android.settings",
            goal = "Open battery settings",
            startPageKey = "settings.home",
            sessionId = ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID,
            displayId = 8,
        )
        assertNull(miss)
    }
}

private fun clickSteps(): List<PlaybookHintStep> = listOf(
    PlaybookHintStep(
        nl = "tap Apps",
        tool = "phone.click",
        selector = SemanticSelector(text = "Apps", resourceId = "com.android.settings:id/apps"),
        beforePageKey = "settings.home",
        afterPageKey = "settings.apps",
        expectedPageChange = true,
    ),
    PlaybookHintStep(
        nl = "tap Battery",
        tool = "phone.click",
        selector = SemanticSelector(text = "Battery"),
        beforePageKey = "settings.apps",
        afterPageKey = "settings.battery",
        expectedPageChange = true,
    ),
)

private fun hint(
    goal: String = "Open battery settings",
    sessionId: String = ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID,
    displayId: Int = ExecutionSession.DEFAULT_DISPLAY_ID,
    packageName: String = "com.android.settings",
    startPageKey: String = "settings.home",
    steps: List<PlaybookHintStep> = clickSteps(),
    successCount: Int = 1,
    lastSuccessAtMs: Long = 1_000L,
): PlaybookHint = PlaybookHint(
    packageName = packageName,
    goal = goal,
    goalSignature = PlaybookGoal.signature(goal),
    startPageKey = startPageKey,
    sessionId = sessionId,
    displayId = displayId,
    steps = steps,
    nlPlaybook = PlaybookGoal.nlPlaybook(steps),
    successCount = successCount,
    source = PlaybookSource.FAST_PATH,
    lastSuccessAtMs = lastSuccessAtMs,
)
