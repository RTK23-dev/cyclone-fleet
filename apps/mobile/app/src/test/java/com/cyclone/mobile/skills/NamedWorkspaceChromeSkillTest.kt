package com.cyclone.mobile.skills

import com.cyclone.mobile.runtime.session.ExecutionSession
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NamedWorkspaceChromeSkillTest {
    @Test
    fun compileChromeSearchOnNamedVd() {
        val route = NamedWorkspaceChromeSkill.compile()
        assertEquals(NamedWorkspaceChromeSkill.ACCEPTANCE_SESSION_ID, route.sessionId)
        assertEquals("named-vd", route.sessionId)
        assertEquals(NamedWorkspaceChromeSkill.ACCEPTANCE_DISPLAY_ID, route.displayId)
        assertEquals(7, route.displayId)
        assertTrue(route.isWorkspace)
        assertEquals(NamedWorkspaceChromeSkill.PACKAGE, route.packageName)
        assertEquals("com.android.chrome", route.packageName)
        assertTrue(route.steps.size >= 2)
        assertEquals("PhoneToolExecutor", route.toJson().getString("mutationEngine"))
        assertTrue(route.id.startsWith(CompiledSkillIds.PREFIX))
        assertTrue(route.id.startsWith("compiled-skill"))
        assertEquals(NamedWorkspaceChromeSkill.GOAL, route.goal)
        assertTrue(route.nlPlaybook.contains(NamedWorkspaceChromeSkill.SEARCH_QUERY))
        assertFalse(route.sessionId == ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID)
        assertTrue(route.displayId > 0)
        route.steps.forEach { step ->
            assertTrue(PlaybookSafety.toolAllowed(step.tool))
            assertTrue(step.selector.isSemantic)
            assertTrue(PlaybookSafety.paramsSafe(step.params))
            assertFalse(step.selector.toJson().has("x"))
            assertTrue(step.selector.toJson().optBoolean("coordinateFree"))
        }
        assertEquals("phone.open_app", route.steps[0].tool)
        assertEquals("phone.click", route.steps[1].tool)
        assertEquals(route.steps.first().beforePageKey, route.startPageKey)
        assertEquals(NamedWorkspaceChromeSkill.START_PAGE_KEY, route.steps[0].afterPageKey)
        assertEquals(NamedWorkspaceChromeSkill.START_PAGE_KEY, route.steps[1].beforePageKey)
        assertEquals(NamedWorkspaceChromeSkill.RESULTS_PAGE_KEY, route.steps[1].afterPageKey)
    }

    @Test
    fun replayHitOnMatchingSessionDisplay() {
        SkillRuntime.initializeForTests(PlaybookHintStore.inMemory())
        val hint = NamedWorkspaceChromeSkill.playbook(successCount = 1)
        repeat(2) { index ->
            val stored = SkillRuntime.recordSuccessfulRun(
                packageName = hint.packageName,
                goal = hint.goal,
                startPageKey = hint.startPageKey,
                sessionId = hint.sessionId,
                displayId = hint.displayId,
                steps = hint.steps,
                nlPlaybook = hint.nlPlaybook,
                nowMs = 1_000L + index,
            )
            assertNotNull(stored)
        }
        val route = SkillRuntime.match(
            hint.packageName,
            hint.goal,
            hint.startPageKey,
            NamedWorkspaceChromeSkill.ACCEPTANCE_SESSION_ID,
            NamedWorkspaceChromeSkill.ACCEPTANCE_DISPLAY_ID,
        )
        assertNotNull(route)

        val act = RecordingPhoneToolPort(
            successOutcome(NamedWorkspaceChromeSkill.START_PAGE_KEY),
            successOutcome(NamedWorkspaceChromeSkill.RESULTS_PAGE_KEY),
        )
        val observe = ScriptedPageObservePort(
            page(
                NamedWorkspaceChromeSkill.START_PAGE_KEY,
                SemanticSelector(text = "Search or type web address"),
            ),
            page(NamedWorkspaceChromeSkill.RESULTS_PAGE_KEY),
        )
        val hit = SkillRuntime.tryReplay(
            packageName = hint.packageName,
            goal = hint.goal,
            page = page(hint.startPageKey, SemanticSelector(packageName = NamedWorkspaceChromeSkill.PACKAGE)),
            sessionId = NamedWorkspaceChromeSkill.ACCEPTANCE_SESSION_ID,
            displayId = NamedWorkspaceChromeSkill.ACCEPTANCE_DISPLAY_ID,
            act = act,
            observe = observe,
        )
        assertTrue(hit is SkillReplayResult.Hit)
        val result = hit as SkillReplayResult.Hit
        assertEquals(2, result.stepsRun)
        assertEquals(NamedWorkspaceChromeSkill.ACCEPTANCE_SESSION_ID, result.sessionId)
        assertEquals(NamedWorkspaceChromeSkill.ACCEPTANCE_DISPLAY_ID, result.displayId)
        assertFalse(result.usedLlm)
        assertFalse(result.usedVision)
        assertEquals(2, act.actCount)
        assertEquals(listOf("phone.open_app", "phone.click"), act.tools)
        act.params.forEach { params ->
            assertIdentity(params, NamedWorkspaceChromeSkill.ACCEPTANCE_SESSION_ID, NamedWorkspaceChromeSkill.ACCEPTANCE_DISPLAY_ID)
        }
        assertEquals("named-vd", act.sessionIds[0])
        assertEquals(7, act.displayIds[0])
        assertEquals("named-vd", act.sessionIds[1])
        assertEquals(7, act.displayIds[1])
    }

    @Test
    fun missCrossSessionEscalatesToFastPathLlm() {
        val route = NamedWorkspaceChromeSkill.compile()
        val act = RecordingPhoneToolPort()
        val result = CompiledSkillReplay.replay(
            route = route,
            page = page(route.startPageKey, SemanticSelector(packageName = NamedWorkspaceChromeSkill.PACKAGE)),
            sessionId = "other-vd",
            displayId = NamedWorkspaceChromeSkill.ACCEPTANCE_DISPLAY_ID,
            act = act,
        )
        val miss = result as SkillReplayResult.Miss
        assertEquals(SkillMissReason.CROSS_SESSION, miss.reason)
        assertEquals(SkillEscalateTo.FAST_PATH_LLM, miss.escalateTo)
        assertEquals(0, miss.stepsRun)
        assertEquals(0, act.actCount)
        assertTrue(result.usedLlm)
        assertFalse(result.usedVision)
    }

    @Test
    fun missDisplayMismatchEscalates() {
        val route = NamedWorkspaceChromeSkill.compile()
        val act = RecordingPhoneToolPort()
        val result = CompiledSkillReplay.replay(
            route = route,
            page = page(route.startPageKey, SemanticSelector(packageName = NamedWorkspaceChromeSkill.PACKAGE)),
            sessionId = NamedWorkspaceChromeSkill.ACCEPTANCE_SESSION_ID,
            displayId = 9,
            act = act,
        )
        val miss = result as SkillReplayResult.Miss
        assertEquals(SkillMissReason.DISPLAY_MISMATCH, miss.reason)
        assertEquals(SkillEscalateTo.FAST_PATH_LLM, miss.escalateTo)
        assertEquals(0, miss.stepsRun)
        assertEquals(0, act.actCount)
        assertTrue(result.usedLlm)
        assertFalse(result.usedVision)
    }

    @Test
    fun missDisplayZeroDoesNotInject() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            NamedWorkspaceChromeSkill.playbook(displayId = 0)
        }
        assertTrue(error.message!!.contains("display 0"))
        assertThrows(IllegalArgumentException::class.java) {
            NamedWorkspaceChromeSkill.compile(displayId = 0)
        }

        val route = NamedWorkspaceChromeSkill.compile()
        val act = RecordingPhoneToolPort()
        val result = CompiledSkillReplay.replay(
            route = route,
            page = page(route.startPageKey, SemanticSelector(packageName = NamedWorkspaceChromeSkill.PACKAGE)),
            sessionId = NamedWorkspaceChromeSkill.ACCEPTANCE_SESSION_ID,
            displayId = ExecutionSession.DEFAULT_DISPLAY_ID,
            act = act,
        )
        val miss = result as SkillReplayResult.Miss
        assertEquals(SkillMissReason.DISPLAY_ZERO_WORKSPACE, miss.reason)
        assertEquals(SkillEscalateTo.FAST_PATH_LLM, miss.escalateTo)
        assertEquals(0, miss.stepsRun)
        assertEquals(0, act.actCount)
        assertTrue(route.isWorkspace)
        assertTrue(route.displayId > 0)
    }

    @Test
    fun missSelectorEscalatesToFastPathLlm() {
        val route = NamedWorkspaceChromeSkill.compile()
        val act = RecordingPhoneToolPort()
        val result = CompiledSkillReplay.replay(
            route = route,
            page = page(route.startPageKey, SemanticSelector(text = "Gmail")),
            sessionId = NamedWorkspaceChromeSkill.ACCEPTANCE_SESSION_ID,
            displayId = NamedWorkspaceChromeSkill.ACCEPTANCE_DISPLAY_ID,
            act = act,
        )
        val miss = result as SkillReplayResult.Miss
        assertEquals(SkillMissReason.SELECTOR_MISS, miss.reason)
        assertEquals(SkillEscalateTo.FAST_PATH_LLM, miss.escalateTo)
        assertEquals(0, miss.stepsRun)
        assertEquals(0, act.actCount)
        assertTrue(result.usedLlm)
        assertFalse(result.usedVision)
    }

    @Test
    fun missGateRequiredDoesNotContinueCompiledPath() {
        val route = NamedWorkspaceChromeSkill.compile()
        val act = RecordingPhoneToolPort(
            SkillActOutcome(
                ok = false,
                selectorResolved = true,
                pageChanged = false,
                fingerprintChanged = false,
                afterPageKey = route.startPageKey,
                afterPackage = NamedWorkspaceChromeSkill.PACKAGE,
                gateRequired = true,
            ),
        )
        val result = CompiledSkillReplay.replay(
            route = route,
            page = page(route.startPageKey, SemanticSelector(packageName = NamedWorkspaceChromeSkill.PACKAGE)),
            sessionId = NamedWorkspaceChromeSkill.ACCEPTANCE_SESSION_ID,
            displayId = NamedWorkspaceChromeSkill.ACCEPTANCE_DISPLAY_ID,
            act = act,
        )
        val miss = result as SkillReplayResult.Miss
        assertEquals(SkillMissReason.GATE_REQUIRED, miss.reason)
        assertEquals(SkillEscalateTo.FAST_PATH_LLM, miss.escalateTo)
        assertEquals(1, act.actCount)
        assertEquals(1, miss.stepsRun)
        assertTrue(result.usedLlm)
        assertFalse(result.usedVision)
        assertTrue(route.steps.size > miss.stepsRun)
    }

    @Test
    fun defaultForegroundPlaybookDoesNotMatchNamedVd() {
        val foreground = NamedWorkspaceChromeSkill.compile(
            sessionId = ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID,
            displayId = ExecutionSession.DEFAULT_DISPLAY_ID,
        )
        assertFalse(foreground.isWorkspace)
        assertEquals(ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID, foreground.sessionId)
        assertEquals(0, foreground.displayId)

        val matched = SkillRouteCompiler.match(
            routes = listOf(foreground),
            packageName = NamedWorkspaceChromeSkill.PACKAGE,
            goal = NamedWorkspaceChromeSkill.GOAL,
            startPageKey = foreground.startPageKey,
            sessionId = NamedWorkspaceChromeSkill.ACCEPTANCE_SESSION_ID,
            displayId = NamedWorkspaceChromeSkill.ACCEPTANCE_DISPLAY_ID,
        )
        assertNull(matched)

        val act = RecordingPhoneToolPort()
        val miss = CompiledSkillReplay.tryReplay(
            routes = listOf(foreground),
            packageName = NamedWorkspaceChromeSkill.PACKAGE,
            goal = NamedWorkspaceChromeSkill.GOAL,
            page = page(foreground.startPageKey, SemanticSelector(packageName = NamedWorkspaceChromeSkill.PACKAGE)),
            sessionId = NamedWorkspaceChromeSkill.ACCEPTANCE_SESSION_ID,
            displayId = NamedWorkspaceChromeSkill.ACCEPTANCE_DISPLAY_ID,
            act = act,
        ) as SkillReplayResult.Miss
        assertEquals(SkillMissReason.NO_MATCH, miss.reason)
        assertEquals(SkillEscalateTo.FAST_PATH_LLM, miss.escalateTo)
        assertEquals(0, miss.stepsRun)
        assertEquals(0, act.actCount)
    }

    private class RecordingPhoneToolPort(
        vararg outcomes: SkillActOutcome,
    ) : PhoneToolPort {
        private val remaining = ArrayDeque(outcomes.toList())
        val tools = mutableListOf<String>()
        val params = mutableListOf<JSONObject>()
        val sessionIds = mutableListOf<String>()
        val displayIds = mutableListOf<Int>()
        val actCount: Int get() = tools.size

        override fun act(tool: String, params: JSONObject, sessionId: String, displayId: Int): SkillActOutcome {
            tools += tool
            this.params += JSONObject(params.toString())
            sessionIds += sessionId
            displayIds += displayId
            check(remaining.isNotEmpty()) { "unexpected extra act: $tool" }
            return remaining.removeFirst()
        }
    }

    private class ScriptedPageObservePort(
        vararg pages: ReplayPage,
    ) : PageObservePort {
        private val remaining = ArrayDeque(pages.toList())

        override fun observe(sessionId: String, displayId: Int): ReplayPage {
            assertEquals(NamedWorkspaceChromeSkill.ACCEPTANCE_SESSION_ID, sessionId)
            assertEquals(NamedWorkspaceChromeSkill.ACCEPTANCE_DISPLAY_ID, displayId)
            check(remaining.isNotEmpty()) { "unexpected extra observe" }
            return remaining.removeFirst()
        }
    }

    private fun page(pageKey: String, vararg controls: SemanticSelector) = ReplayPage(
        packageName = NamedWorkspaceChromeSkill.PACKAGE,
        pageKey = pageKey,
        perceptionMode = "a11y",
        treeUseful = true,
        fingerprint = "fp-$pageKey",
        controls = controls.toList(),
    )

    private fun successOutcome(afterPageKey: String) = SkillActOutcome(
        ok = true,
        selectorResolved = true,
        pageChanged = true,
        fingerprintChanged = true,
        afterPageKey = afterPageKey,
        afterPackage = NamedWorkspaceChromeSkill.PACKAGE,
        unchangedWarning = false,
    )

    private fun assertIdentity(params: JSONObject, sessionId: String, displayId: Int) {
        assertTrue(params.has("sessionId"))
        assertTrue(params.has("displayId"))
        assertEquals(sessionId, params.getString("sessionId"))
        assertEquals(displayId, params.getInt("displayId"))
    }
}
