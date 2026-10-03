package com.cyclone.mobile.skills

import com.cyclone.mobile.fastpath.FastPathLoop
import com.cyclone.mobile.fastpath.FastPathTimings
import com.cyclone.mobile.runtime.session.ExecutionSession
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompiledSkillReplayTest {
    @Test
    fun rejectedExecutorCannotCompleteRoutineEvenWhenFingerprintsChanged() {
        val act = RecordingPhoneToolPort(successOutcome("settings.apps").copy(ok = false))
        val result = CompiledSkillReplay.replay(compileSettings(),
            page("settings.home", SemanticSelector(text = "Apps")),
            FOREGROUND_SESSION, FOREGROUND_DISPLAY, act)
        assertTrue(result is SkillReplayResult.Miss)
        assertEquals(1, act.actCount)
    }

    @Test
    fun hitReplaysCompiledTwoStepSettingsRouteWithoutLlmOrVision() {
        val route = compileSettings()
        val act = RecordingPhoneToolPort(
            successOutcome("settings.apps"),
            successOutcome("settings.battery"),
        )
        val observe = ScriptedPageObservePort(
            page("settings.apps", SemanticSelector(text = "Battery")),
            page("settings.battery"),
        )

        val result = CompiledSkillReplay.replay(
            route = route,
            page = page("settings.home", SemanticSelector(text = "Apps")),
            sessionId = FOREGROUND_SESSION,
            displayId = FOREGROUND_DISPLAY,
            act = act,
            observe = observe,
        )

        assertTrue(result is SkillReplayResult.Hit)
        val hit = result as SkillReplayResult.Hit
        assertEquals(route.id, hit.routeId)
        assertEquals(2, hit.stepsRun)
        assertEquals(route.nlPlaybook, hit.nlPlaybook)
        assertEquals(FOREGROUND_SESSION, hit.sessionId)
        assertEquals(FOREGROUND_DISPLAY, hit.displayId)
        assertFalse(result.usedLlm)
        assertFalse(result.usedVision)
        assertEquals(2, act.actCount)
        assertEquals(listOf("phone.click", "phone.click"), act.tools)
        assertIdentity(act.params[0], FOREGROUND_SESSION, FOREGROUND_DISPLAY)
        assertIdentity(act.params[1], FOREGROUND_SESSION, FOREGROUND_DISPLAY)
        assertEquals("Apps", act.params[0].getJSONObject("selector").getString("text"))
        assertEquals("Battery", act.params[1].getJSONObject("selector").getString("text"))
    }

    @Test
    fun missSelectorDoesNotClickWhenFirstSelectorIsAbsent() {
        val route = compileSettings()
        val act = RecordingPhoneToolPort()

        val result = CompiledSkillReplay.replay(
            route = route,
            page = page("settings.home", SemanticSelector(text = "Network")),
            sessionId = FOREGROUND_SESSION,
            displayId = FOREGROUND_DISPLAY,
            act = act,
        )

        val miss = result as SkillReplayResult.Miss
        assertEquals(SkillMissReason.SELECTOR_MISS, miss.reason)
        assertEquals(SkillEscalateTo.FAST_PATH_LLM, miss.escalateTo)
        assertEquals(0, miss.stepsRun)
        assertEquals(route.id, miss.routeId)
        assertTrue(result.usedLlm)
        assertFalse(result.usedVision)
        assertEquals(0, act.actCount)
    }

    @Test
    fun missUnchangedDoesNotExecuteSecondStepOrAuthorizeSecondClick() {
        val route = compileSettings()
        val act = RecordingPhoneToolPort(unchangedOutcome("settings.home"))
        val settle = FastPathLoop.settle(
            beforeFingerprint = "fp-settings.home",
            sleepMs = {},
            observeFingerprint = { "fp-settings.home" },
            nowMs = { 0L },
        )

        val result = CompiledSkillReplay.replay(
            route = route,
            page = page("settings.home", SemanticSelector(text = "Apps")),
            sessionId = FOREGROUND_SESSION,
            displayId = FOREGROUND_DISPLAY,
            act = act,
        )

        val miss = result as SkillReplayResult.Miss
        assertEquals(SkillMissReason.UNCHANGED, miss.reason)
        assertEquals(SkillEscalateTo.FAST_PATH_LLM, miss.escalateTo)
        assertEquals(1, miss.stepsRun)
        assertEquals(route.id, miss.routeId)
        assertTrue(result.usedLlm)
        assertFalse(result.usedVision)
        assertEquals(1, act.actCount)
        assertEquals(false, settle.changed)
        assertFalse(settle.verified)
        assertEquals(FastPathTimings.UNCHANGED_WARNING, settle.warning)
        assertFalse(FastPathLoop.allowSecondClickChannel(actionPerformed = true, settle = settle))
    }

    @Test
    fun missCrossSessionDoesNotAct() {
        val route = compileSettings(sessionId = WORKSPACE_A, displayId = WORKSPACE_DISPLAY)
        val act = RecordingPhoneToolPort()

        val result = CompiledSkillReplay.replay(
            route = route,
            page = page("settings.home", SemanticSelector(text = "Apps")),
            sessionId = WORKSPACE_B,
            displayId = WORKSPACE_DISPLAY,
            act = act,
        )

        val miss = result as SkillReplayResult.Miss
        assertEquals(SkillMissReason.CROSS_SESSION, miss.reason)
        assertEquals(SkillEscalateTo.FAST_PATH_LLM, miss.escalateTo)
        assertEquals(0, miss.stepsRun)
        assertEquals(route.id, miss.routeId)
        assertEquals(0, act.actCount)
    }

    @Test
    fun missDisplayZeroWorkspaceDoesNotAct() {
        val route = compileSettings(sessionId = WORKSPACE_A, displayId = WORKSPACE_DISPLAY)
        val act = RecordingPhoneToolPort()

        val result = CompiledSkillReplay.replay(
            route = route,
            page = page("settings.home", SemanticSelector(text = "Apps")),
            sessionId = WORKSPACE_A,
            displayId = ExecutionSession.DEFAULT_DISPLAY_ID,
            act = act,
        )

        val miss = result as SkillReplayResult.Miss
        assertEquals(SkillMissReason.DISPLAY_ZERO_WORKSPACE, miss.reason)
        assertEquals(SkillEscalateTo.FAST_PATH_LLM, miss.escalateTo)
        assertEquals(0, miss.stepsRun)
        assertEquals(route.id, miss.routeId)
        assertEquals(0, act.actCount)
    }

    @Test
    fun visionEscalateDoesNotActWhenTreeIsUnusable() {
        val route = compileSettings()
        val pages = listOf(
            page("settings.home", SemanticSelector(text = "Apps"), perceptionMode = "vision_escalate"),
            page("settings.home", SemanticSelector(text = "Apps"), treeUseful = false),
        )
        val expectedReasons = listOf(SkillMissReason.VISION_ESCALATE, SkillMissReason.EMPTY_TREE)

        pages.zip(expectedReasons).forEach { (page, expectedReason) ->
            val act = RecordingPhoneToolPort()
            val result = CompiledSkillReplay.replay(
                route = route,
                page = page,
                sessionId = FOREGROUND_SESSION,
                displayId = FOREGROUND_DISPLAY,
                act = act,
            )
            val miss = result as SkillReplayResult.Miss
            assertEquals(expectedReason, miss.reason)
            assertEquals(SkillEscalateTo.VISION, miss.escalateTo)
            assertTrue(result.usedVision)
            assertFalse(result.usedLlm)
            assertEquals(0, miss.stepsRun)
            assertEquals(0, act.actCount)
        }
    }

    @Test
    fun gateRequiredMissDoesNotContinueTheCompiledPath() {
        val route = compileSettings()
        val act = RecordingPhoneToolPort(
            SkillActOutcome(
                ok = false,
                selectorResolved = true,
                pageChanged = false,
                fingerprintChanged = false,
                afterPageKey = "settings.home",
                afterPackage = SETTINGS_PACKAGE,
                gateRequired = true,
            ),
        )

        val result = CompiledSkillReplay.replay(
            route = route,
            page = page("settings.home", SemanticSelector(text = "Apps")),
            sessionId = FOREGROUND_SESSION,
            displayId = FOREGROUND_DISPLAY,
            act = act,
        )

        val miss = result as SkillReplayResult.Miss
        assertEquals(SkillMissReason.GATE_REQUIRED, miss.reason)
        assertEquals(SkillEscalateTo.FAST_PATH_LLM, miss.escalateTo)
        assertEquals(1, act.actCount)
        assertEquals(1, miss.stepsRun)
    }

    @Test
    fun tryReplayWithNoMatchingRouteEscalatesToFastPathLlm() {
        val act = RecordingPhoneToolPort()
        val result = CompiledSkillReplay.tryReplay(
            routes = listOf(compileSettings()),
            packageName = SETTINGS_PACKAGE,
            goal = "open wifi settings",
            page = page("settings.home", SemanticSelector(text = "Apps")),
            sessionId = FOREGROUND_SESSION,
            displayId = FOREGROUND_DISPLAY,
            act = act,
        )

        val miss = result as SkillReplayResult.Miss
        assertEquals(SkillMissReason.NO_MATCH, miss.reason)
        assertEquals(SkillEscalateTo.FAST_PATH_LLM, miss.escalateTo)
        assertTrue(result.usedLlm)
        assertFalse(result.usedVision)
        assertEquals(0, miss.stepsRun)
        assertEquals(0, act.actCount)
    }

    private class RecordingPhoneToolPort(
        vararg outcomes: SkillActOutcome,
    ) : PhoneToolPort {
        private val remaining = ArrayDeque(outcomes.toList())
        val tools = mutableListOf<String>()
        val params = mutableListOf<JSONObject>()
        val actCount: Int get() = tools.size

        override fun act(tool: String, params: JSONObject, sessionId: String, displayId: Int): SkillActOutcome {
            tools += tool
            this.params += JSONObject(params.toString())
            check(remaining.isNotEmpty()) { "unexpected extra act: $tool" }
            return remaining.removeFirst()
        }
    }

    private class ScriptedPageObservePort(
        vararg pages: ReplayPage,
    ) : PageObservePort {
        private val remaining = ArrayDeque(pages.toList())

        override fun observe(sessionId: String, displayId: Int): ReplayPage {
            check(remaining.isNotEmpty()) { "unexpected extra observe" }
            return remaining.removeFirst()
        }
    }

    companion object {
        private const val SETTINGS_PACKAGE = "com.android.settings"
        private const val WORKSPACE_A = "workspace-a"
        private const val WORKSPACE_B = "workspace-b"
        private const val WORKSPACE_DISPLAY = 8
        private const val FOREGROUND_SESSION = ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID
        private const val FOREGROUND_DISPLAY = ExecutionSession.DEFAULT_DISPLAY_ID

        private fun compileSettings(
            sessionId: String = FOREGROUND_SESSION,
            displayId: Int = FOREGROUND_DISPLAY,
        ): CompiledSkillRoute {
            val steps = listOf(
                PlaybookHintStep(
                    nl = "Open Apps",
                    tool = "phone.click",
                    selector = SemanticSelector(text = "Apps"),
                    beforePageKey = "settings.home",
                    afterPageKey = "settings.apps",
                    expectedPageChange = true,
                ),
                PlaybookHintStep(
                    nl = "Open Battery",
                    tool = "phone.click",
                    selector = SemanticSelector(text = "Battery"),
                    beforePageKey = "settings.apps",
                    afterPageKey = "settings.battery",
                    expectedPageChange = true,
                ),
            )
            val compiled = SkillRouteCompiler.compile(
                PlaybookHint(
                    packageName = SETTINGS_PACKAGE,
                    goal = "Open battery settings",
                    goalSignature = PlaybookGoal.signature("Open battery settings"),
                    startPageKey = "settings.home",
                    sessionId = sessionId,
                    displayId = displayId,
                    steps = steps,
                    nlPlaybook = PlaybookGoal.nlPlaybook(steps),
                    successCount = SkillRouteCompiler.MIN_SUCCESSES,
                    source = PlaybookSource.FAST_PATH,
                    lastSuccessAtMs = 1_000L,
                ),
            )
            return requireNotNull(compiled.route) { compiled.rejected ?: "compile failed" }
        }

        private fun page(
            pageKey: String,
            vararg controls: SemanticSelector,
            perceptionMode: String = "a11y",
            treeUseful: Boolean = true,
        ) = ReplayPage(
            packageName = SETTINGS_PACKAGE,
            pageKey = pageKey,
            perceptionMode = perceptionMode,
            treeUseful = treeUseful,
            fingerprint = "fp-$pageKey",
            controls = controls.toList(),
        )

        private fun successOutcome(afterPageKey: String) = SkillActOutcome(
            ok = true,
            selectorResolved = true,
            pageChanged = true,
            fingerprintChanged = true,
            afterPageKey = afterPageKey,
            afterPackage = SETTINGS_PACKAGE,
            unchangedWarning = false,
        )

        private fun unchangedOutcome(afterPageKey: String) = SkillActOutcome(
            ok = true,
            selectorResolved = true,
            pageChanged = false,
            fingerprintChanged = false,
            afterPageKey = afterPageKey,
            afterPackage = SETTINGS_PACKAGE,
            unchangedWarning = true,
        )

        private fun assertIdentity(params: JSONObject, sessionId: String, displayId: Int) {
            assertTrue(params.has("sessionId"))
            assertTrue(params.has("displayId"))
            assertEquals(sessionId, params.getString("sessionId"))
            assertEquals(displayId, params.getInt("displayId"))
        }
    }
}
