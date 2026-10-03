package com.cyclone.mobile.mind.learn

import com.cyclone.mobile.applearner.ActionRisk
import com.cyclone.mobile.applearner.KnowledgeState
import com.cyclone.mobile.applearner.LearnedAction
import com.cyclone.mobile.applearner.LearnedApp
import com.cyclone.mobile.applearner.LearnedScreen
import com.cyclone.mobile.applearner.LearnedTransition
import com.cyclone.mobile.applearner.PageContext
import com.cyclone.mobile.applearner.PageControl
import com.cyclone.mobile.mind.mission.Mission
import com.cyclone.mobile.mind.mission.MissionStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MissionLearnerTest {
    private val calc = "com.google.android.calculator"

    private fun control(label: String, id: String, role: String = "button", editable: Boolean = false, text: String = label) = PageControl(
        key = "k-$id", label = label, semanticName = label.lowercase(), role = role,
        selector = JSONObject().put("resourceId", "$calc:id/$id").put("text", text).put("role", role).apply { if (editable) put("editable", true) else put("clickable", true) },
        androidActions = listOf("click"), risk = ActionRisk.SAFE, confidence = 0.8,
    )

    private fun page(key: String, pkg: String, title: String, controls: List<PageControl>) =
        PageContext("$pkg:Main:$key", pkg, "Main", title, key, "content-$key", controls, 1, 0, 0)

    private val launcher = page("home", "com.google.android.apps.nexuslauncher", "Home", listOf(control("Calculator", "calc_icon")))
    private val keypad = page("keypad", calc, "Calculator",
        (0..9).map { control("$it", "digit_$it") } + listOf(control("plus", "op_add"), control("equals", "eq"), control("History", "history")))
    private val history = page("history", calc, "History", listOf(control("Navigate up", "up")))

    private fun recorded(): MissionTrail {
        var now = 0L
        val recorder = MindTrailRecorder("m1") { now++ }
        recorder.screen(launcher)
        recorder.screen(page("overlay", "com.cyclone.mobile", "Cyclone", listOf(control("Stop", "stop"))))
        recorder.step("open_app", launcher, null, null, keypad, ok = true)
        recorder.screen(keypad)
        recorder.step("tap", keypad, "7", "button", keypad, ok = true)
        recorder.step("tap", keypad, "plus", "button", keypad, ok = true)
        recorder.step("tap", keypad, "History", "button", history, ok = true)
        recorder.screen(history)
        recorder.step("tap", history, "Navigate up", "button", history, ok = false)
        return recorder.snapshot()
    }

    @Test fun theTrailKeepsEveryScreenAndEveryControlButNotCyclonesOwnOverlay() {
        val trail = recorded()
        assertEquals(listOf("home", "keypad", "history"), trail.screens.map { it.structuralKey })
        assertEquals(13, trail.screens.first { it.structuralKey == "keypad" }.controls.size)
        assertEquals(5, trail.steps.size)
        assertEquals("k-history", trail.steps[3].controlKey)
        assertEquals(trail.toJson().toString(), MissionTrail.fromJson(JSONObject(trail.toJson().toString()))!!.toJson().toString())
    }

    @Test fun contentAndTypedTextNeverReachTheTrail() {
        val recorder = MindTrailRecorder("m2") { 0 }
        recorder.screen(page("chats", "com.whatsapp", "louella@example.com", listOf(
            control("Search", "search", role = "edit_text", editable = true, text = "Louella van Dijk"),
            control("Louella: see you tomorrow at the station, bring the tickets please", "row1"),
            control("mail jan@example.com", "row2"),
            control("New chat", "fab"),
        )))
        val screen = recorder.snapshot().screens.single()
        assertEquals("Screen", screen.title)
        assertEquals(listOf("search", "New chat"), screen.controls.map { it.label })
        val json = recorder.snapshot().toJson().toString()
        assertFalse(json.contains("Louella"))
        assertFalse(json.contains("example.com"))
    }

    private class Sink : LearnSink, LearnedReader {
        override fun screens(packageName: String) = screens.values.filter { it.packageName == packageName }
        override fun actions(packageName: String) = actions.values.filter { it.packageName == packageName }
        override fun transitions(packageName: String) = transitions.filter { it.packageName == packageName }
        val apps = mutableMapOf<String, LearnedApp>()
        val screens = mutableMapOf<String, LearnedScreen>()
        val actions = mutableMapOf<String, LearnedAction>()
        val transitions = mutableListOf<LearnedTransition>()
        override fun app(packageName: String) = apps[packageName]
        override fun screenIdFor(packageName: String, pageKey: String) =
            screens.values.firstOrNull { it.packageName == packageName && it.recognition.semanticFingerprint == pageKey }?.id
        override fun putApp(app: LearnedApp) { apps[app.packageName] = app }
        override fun putScreen(screen: LearnedScreen) { screens[screen.id] = screen }
        override fun putAction(action: LearnedAction): String { actions[action.id] = action; return action.id }
        override fun putTransition(transition: LearnedTransition) { transitions += transition }
    }

    @Test fun learnRemembersEveryButtonAndOnlyTheMovesThatWorked() {
        val sink = Sink()
        val report = MissionLearner(sink) { 1_000 }.learn(recorded()) { if (it == calc) "Calculator" else "Launcher" }

        val calcActions = sink.actions.values.filter { it.packageName == calc }
        assertEquals("every key of the keypad is known, not only the ones pressed", 14, calcActions.size)
        assertEquals(KnowledgeState.UNDERSTOOD, calcActions.first { it.label == "7" }.knowledgeState)
        assertEquals(KnowledgeState.DISCOVERED, calcActions.first { it.label == "9" }.knowledgeState)
        assertTrue(calcActions.first { it.label == "9" }.selectorJson.contains("digit_9"))
        assertEquals(1, calcActions.first { it.label == "Navigate up" }.failureCount)

        assertEquals("only keypad → History is a move; the failed step and cross-app launch are not", 1, sink.transitions.size)
        val move = sink.transitions.single()
        assertEquals(sink.screens.values.first { it.title == "Calculator" }.id, move.fromScreenId)
        assertEquals(sink.screens.values.first { it.title == "History" }.id, move.toScreenId)

        assertEquals(1, report.failedSteps)
        assertEquals("Learned 3 screens, 15 controls and 1 move in Launcher, Calculator.", report.sentence())
        assertEquals(report, LearnReport.fromJson(JSONObject(report.toJson().toString())))
    }

    @Test fun theNextRunIsToldWhatWasLearnedAboutTheScreenItIsOn() {
        val sink = Sink()
        val hints = LearnedHints(sink)
        assertNull("nothing learned yet, no advice", hints.forScreen(calc, keypad.pageKey))
        MissionLearner(sink) { 1 }.learn(recorded()) { "Calculator" }
        val advice = LearnedHints(sink).forScreen(calc, keypad.pageKey)!!
        assertTrue(advice, advice.contains("Cyclone knows this screen (13 controls)"))
        assertTrue(advice, advice.contains("“History” → History"))
        assertTrue(advice, advice.contains("Other learned screens in this app: History"))
        assertTrue(advice, advice.contains("This is advice"))
        assertNull("an unknown screen gets no advice", LearnedHints(sink).forScreen(calc, "$calc:Main:settings"))
    }

    @Test fun learningASecondRunMergesIntoTheSameScreens() {
        val sink = Sink()
        MissionLearner(sink) { 1 }.learn(recorded()) { "App" }
        val screensBefore = sink.screens.keys.toSet()
        val second = MissionLearner(sink) { 2 }.learn(recorded()) { "App" }
        assertEquals(screensBefore, sink.screens.keys.toSet())
        assertEquals(0, second.newScreens)
    }

    @Test fun aMissionRemembersWhatWasLearnedFromIt() {
        val report = LearnReport(listOf(AppLearned(calc, "Calculator", 1, 1, 14, 0)), 0, 5)
        val mission = Mission("m1", "open calculator", MissionStatus.COMPLETED, 1, 2, "x", "X", learned = report)
        assertEquals(report, Mission.fromJson(mission.toJson()).learned)
        assertNull(Mission.fromJson(mission.copy(learned = null).toJson()).learned)
    }
}
