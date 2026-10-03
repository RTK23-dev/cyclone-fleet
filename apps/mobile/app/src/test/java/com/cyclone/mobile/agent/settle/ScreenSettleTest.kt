package com.cyclone.mobile.agent.settle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenSettleTest {
    /** A scripted screen: each capture returns the next frame; null throws like OBSERVATION_CHANGED_DURING_CAPTURE. */
    private class Script(vararg frames: Frame?) {
        private val queue = frames.toMutableList()
        var now = 0L
        var captures = 0
        fun capture(): Frame {
            captures++
            val frame = if (queue.size > 1) queue.removeAt(0) else queue.firstOrNull()
            return frame ?: throw IllegalStateException("OBSERVATION_CHANGED_DURING_CAPTURE")
        }
    }

    private data class Frame(val pkg: String, val fp: String, val actionable: Int = 5, val loading: Boolean = false)

    private fun sample(frame: Frame) = SettleSample(frame.pkg, frame.fp, frame.actionable, frame.loading)

    private fun run(script: Script, before: String = "home", budget: SettleBudget = SettleBudget(),
                    requireStable: Boolean = true, target: (Frame) -> Boolean) =
        SettleController.run(before, budget, script::capture, ::sample, target, requireStable,
            nowMs = { script.now }, sleepMs = { script.now += it })

    private val clock = "com.google.android.deskclock"

    @Test fun alpha22ClockLaunchIsVerifiedInsteadOfLost() {
        // alpha.22: the first capture during the launch animation threw and the after-state was reported missing.
        val script = Script(null, null, Frame(clock, "splash", actionable = 0, loading = true),
            Frame(clock, "alarms"), Frame(clock, "alarms"))
        val outcome = run(script) { it.pkg == clock }
        assertEquals(SettleState.READY, outcome.state)
        assertEquals(2, outcome.captureErrors)
        assertEquals("alarms", outcome.value!!.fp)
        assertTrue("well inside the fast window", outcome.waitedMs < 1_800)
    }

    @Test fun aSlowSplashExtendsTheWaitOnlyWhileItIsLoading() {
        val frames = arrayOfNulls<Frame>(20).mapIndexed { i, _ -> Frame(clock, "splash", actionable = 0, loading = true) } +
            listOf(Frame(clock, "home"), Frame(clock, "home"))
        val outcome = run(Script(*frames.toTypedArray())) { it.pkg == clock }
        assertEquals(SettleState.READY, outcome.state)
        assertTrue(outcome.extended)
        assertTrue(outcome.waitedMs > 1_800)
    }

    @Test fun aSpinnerThatNeverEndsStopsAtTheCeiling() {
        val outcome = run(Script(Frame(clock, "spin", loading = true))) { it.pkg == clock }
        assertEquals(SettleState.LOADING, outcome.state)
        val budget = SettleBudget()
        assertTrue(outcome.waitedMs in (budget.fastMs + budget.extendedMs)..(budget.fastMs + budget.extendedMs + 500))
    }

    @Test fun aDeadScreenStopsEarlyAndIsReportedUnchanged() {
        val outcome = run(Script(Frame("launcher", "home")), before = "home") { false }
        assertEquals(SettleState.UNCHANGED, outcome.state)
        assertFalse(outcome.extended)
        assertTrue(outcome.waitedMs <= 1_800 + 150)
    }

    @Test fun aTapIsJudgedOnItsFirstChangedFrame() {
        val script = Script(Frame("app", "next"))
        val outcome = run(script, before = "page", requireStable = false) { it.fp != "page" }
        assertEquals(SettleState.READY, outcome.state)
        assertEquals(1, script.captures)
        assertEquals(0L, outcome.waitedMs)
    }

    @Test fun capturesThatKeepFailingEndWithoutAValueAtTheCeiling() {
        val outcome = run(Script(null)) { true }
        assertEquals(SettleState.MOVING, outcome.state)
        assertEquals(null, outcome.value)
        assertEquals(outcome.captures, outcome.captureErrors)
    }

    @Test fun cancellationWins() {
        val script = Script(null)
        val outcome = SettleController.run("home", SettleBudget(), script::capture, ::sample, { true },
            nowMs = { script.now }, sleepMs = { script.now += it }, cancelled = { true })
        assertEquals("cancelled", outcome.reason)
        assertEquals(1, script.captures)
    }

    @Test fun loadingEvidenceComesFromRolesIdsAndShortStatusText() {
        fun ev(vararg e: Triple<String, String, String>, actionable: Int = 3, launch: Boolean = false) =
            ScreenStateClassifier.loadingEvidence("com.example", e.toList(), actionable, launch)
        assertTrue(ev(Triple("progress", "", "")))
        assertTrue(ev(Triple("generic", "com.example:id/loading_spinner", "")))
        assertTrue(ev(Triple("text", "", "Loading…")))
        assertTrue(ev(Triple("text", "", "Even geduld")))
        assertFalse(ev(Triple("text", "", "Loading screens explained: a long article title about apps")))
        assertFalse(ev(Triple("button", "", "Alarms")))
        assertTrue("splash on launch", ev(actionable = 0, launch = true))
        assertFalse("empty page after a tap is a real page", ev(actionable = 0, launch = false))
        assertFalse(ScreenStateClassifier.loadingEvidence("com.cyclone.mobile", emptyList(), 0, true))
    }

    @Test fun waitTelemetryHasNoScreenContent() {
        val outcome = run(Script(null, Frame(clock, "a"), Frame(clock, "a"))) { true }
        val json = outcome.toJson().toString()
        assertFalse(json.contains(clock))
        assertTrue(json.contains("\"state\":\"ready\""))
    }
}

class SettleBudgetsTest {
    @Test fun learnedBudgetsGrowWithSlowAppsAndAreCapped() {
        SettleBudgets.reset()
        val base = SettleBudget()
        assertEquals("no evidence yet: default window", base, SettleBudgets.budgetFor("com.slow", base))
        repeat(5) { SettleBudgets.record("com.slow", 9_000) }
        val learned = SettleBudgets.budgetFor("com.slow", base)
        assertEquals(13_500L, learned.fastMs + learned.extendedMs)
        repeat(20) { SettleBudgets.record("com.slower", 30_000) }
        val capped = SettleBudgets.budgetFor("com.slower", base)
        assertEquals(15_000L, capped.fastMs + capped.extendedMs)
        repeat(5) { SettleBudgets.record("com.fast", 400) }
        assertEquals("a fast app never shrinks the default window", base, SettleBudgets.budgetFor("com.fast", base))
        SettleBudgets.forget("com.slow")
        assertEquals(base, SettleBudgets.budgetFor("com.slow", base))
    }
}
