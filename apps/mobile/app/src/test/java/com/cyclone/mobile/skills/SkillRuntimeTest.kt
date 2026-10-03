package com.cyclone.mobile.skills

import com.cyclone.mobile.fastpath.FastPathLoop
import com.cyclone.mobile.fastpath.FastPathSettleResult
import com.cyclone.mobile.runtime.session.ExecutionSession
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillRuntimeTest {
    @Test
    fun learnCompileReplayHitThenMissEscalatesToFastPath() {
        SkillRuntime.initializeForTests(PlaybookHintStore.inMemory())
        val first = settingsHint()
        assertNull(SkillRouteCompiler.compile(first).route)

        val stored = SkillRuntime.recordSuccessfulRun(
            packageName = first.packageName,
            goal = first.goal,
            startPageKey = first.startPageKey,
            sessionId = first.sessionId,
            displayId = first.displayId,
            steps = first.steps,
            nlPlaybook = first.nlPlaybook,
            nowMs = 1_000L,
        )
        assertNotNull(stored)
        assertEquals(1, stored!!.successCount)
        assertNull(SkillRuntime.match(first.packageName, first.goal, first.startPageKey, first.sessionId, first.displayId))

        SkillRuntime.recordSuccessfulRun(
            packageName = first.packageName,
            goal = first.goal,
            startPageKey = first.startPageKey,
            sessionId = first.sessionId,
            displayId = first.displayId,
            steps = first.steps,
            nlPlaybook = first.nlPlaybook,
            nowMs = 2_000L,
        )
        val route = SkillRuntime.match(first.packageName, first.goal, first.startPageKey, first.sessionId, first.displayId)
        assertNotNull(route)
        assertTrue(route!!.id.startsWith(CompiledSkillIds.PREFIX))
        assertEquals(2, route.steps.size)
        assertEquals("phone.click", route.steps[0].tool)

        val acts = mutableListOf<JSONObject>()
        val pages = ArrayDeque(
            listOf(
                settingsPage("settings.home", "Apps"),
                settingsPage("settings.apps", "Battery"),
                settingsPage("settings.battery", "Battery"),
            ),
        )
        var current = pages.removeFirst()
        val hit = SkillRuntime.tryReplay(
            packageName = first.packageName,
            goal = first.goal,
            page = current,
            sessionId = first.sessionId,
            displayId = first.displayId,
            act = PhoneToolPort { _, params, sessionId, displayId ->
                acts += params
                assertEquals(ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID, sessionId)
                assertEquals(0, displayId)
                assertEquals(sessionId, params.getString("sessionId"))
                assertEquals(displayId, params.getInt("displayId"))
                current = pages.removeFirst()
                SkillActOutcome(
                    ok = true,
                    selectorResolved = true,
                    pageChanged = true,
                    fingerprintChanged = true,
                    afterPageKey = current.pageKey,
                    afterPackage = current.packageName,
                )
            },
            observe = { _, _ -> current },
        )
        assertTrue(hit is SkillReplayResult.Hit)
        assertEquals(2, (hit as SkillReplayResult.Hit).stepsRun)
        assertFalse(hit.usedLlm)
        assertFalse(hit.usedVision)
        assertEquals(2, acts.size)

        val miss = SkillRuntime.tryReplay(
            packageName = first.packageName,
            goal = first.goal,
            page = settingsPage("settings.home", "Network"),
            sessionId = first.sessionId,
            displayId = first.displayId,
            act = PhoneToolPort { _, _, _, _ -> error("must not act on selector miss") },
        )
        assertTrue(miss is SkillReplayResult.Miss)
        val missed = miss as SkillReplayResult.Miss
        assertEquals(SkillMissReason.SELECTOR_MISS, missed.reason)
        assertEquals(SkillEscalateTo.FAST_PATH_LLM, missed.escalateTo)
        assertTrue(missed.usedLlm)
        assertFalse(missed.usedVision)
    }

    @Test
    fun unchangedCompiledStepDoesNotAuthorizeSecondClick() {
        SkillRuntime.initializeForTests(PlaybookHintStore.inMemory())
        repeat(2) {
            SkillRuntime.recordSuccessfulRun(
                packageName = "com.android.settings",
                goal = "Open battery settings",
                startPageKey = "settings.home",
                sessionId = ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID,
                displayId = 0,
                steps = settingsHint().steps,
                nowMs = 1_000L + it,
            )
        }
        var calls = 0
        val miss = SkillRuntime.tryReplay(
            packageName = "com.android.settings",
            goal = "Open battery settings",
            page = settingsPage("settings.home", "Apps"),
            sessionId = ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID,
            displayId = 0,
            act = PhoneToolPort { _, _, _, _ ->
                calls += 1
                SkillActOutcome(
                    ok = true,
                    selectorResolved = true,
                    pageChanged = false,
                    fingerprintChanged = false,
                    afterPageKey = "settings.home",
                    afterPackage = "com.android.settings",
                    unchangedWarning = true,
                )
            },
        )
        assertEquals(1, calls)
        assertTrue(miss is SkillReplayResult.Miss)
        assertEquals(SkillMissReason.UNCHANGED, (miss as SkillReplayResult.Miss).reason)
        val settle = FastPathSettleResult(false, false, 3, 1_800L, com.cyclone.mobile.fastpath.FastPathTimings.UNCHANGED_WARNING, "same")
        assertFalse(FastPathLoop.allowSecondClickChannel(true, settle))
    }

    @Test
    fun emptyTreeEscalatesToVisionNotLlm() {
        SkillRuntime.initializeForTests(PlaybookHintStore.inMemory())
        val miss = SkillRuntime.tryReplay(
            packageName = "com.android.settings",
            goal = "Open battery settings",
            page = ReplayPage(
                packageName = "com.android.settings",
                pageKey = "settings.home",
                perceptionMode = "vision_escalate",
                treeUseful = false,
            ),
            sessionId = ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID,
            displayId = 0,
            act = PhoneToolPort { _, _, _, _ -> error("vision miss must not act") },
        )
        assertTrue(miss is SkillReplayResult.Miss)
        assertEquals(SkillEscalateTo.VISION, (miss as SkillReplayResult.Miss).escalateTo)
        assertTrue(miss.usedVision)
    }

    private fun settingsHint(): PlaybookHint = PlaybookHint(
        packageName = "com.android.settings",
        goal = "Open battery settings",
        goalSignature = PlaybookGoal.signature("Open battery settings"),
        startPageKey = "settings.home",
        sessionId = ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID,
        displayId = 0,
        steps = listOf(
            PlaybookHintStep(
                nl = "Then tap Apps",
                tool = "phone.click",
                selector = SemanticSelector(text = "Apps"),
                beforePageKey = "settings.home",
                afterPageKey = "settings.apps",
                expectedPageChange = true,
            ),
            PlaybookHintStep(
                nl = "Then tap Battery",
                tool = "phone.click",
                selector = SemanticSelector(text = "Battery"),
                beforePageKey = "settings.apps",
                afterPageKey = "settings.battery",
                expectedPageChange = true,
            ),
        ),
        nlPlaybook = "When Settings home → Then tap Apps → Then tap Battery",
        successCount = 1,
        source = PlaybookSource.FAST_PATH,
        lastSuccessAtMs = 1_000L,
    )

    private fun settingsPage(pageKey: String, vararg labels: String) = ReplayPage(
        packageName = "com.android.settings",
        pageKey = pageKey,
        perceptionMode = "a11y",
        treeUseful = true,
        controls = labels.map { SemanticSelector(text = it) },
    )
}
