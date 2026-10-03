package com.cyclone.mobile.mapping.run

import com.cyclone.mobile.applearner.ActionRisk
import com.cyclone.mobile.applearner.KnowledgeState
import com.cyclone.mobile.applearner.LearnedAction
import com.cyclone.mobile.applearner.LearnedApp
import com.cyclone.mobile.applearner.LearnedScreen
import com.cyclone.mobile.applearner.LearnedTransition
import com.cyclone.mobile.applearner.PageContext
import com.cyclone.mobile.applearner.PageControl
import com.cyclone.mobile.mapping.crawl.MappingIdentity
import com.cyclone.mobile.mind.learn.LearnSink
import com.cyclone.mobile.mind.learn.LearnedReader
import com.cyclone.mobile.mind.map.MindMap
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** One map: what a mapping pass walks becomes knowledge that runs route on (plan 23). */
class MappingTrailTapTest {
    private val pkg = "com.android.settings"

    private fun control(label: String, id: String) = PageControl(
        key = "k-$id", label = label, semanticName = label.lowercase(), role = "button",
        selector = JSONObject().put("resourceId", "$pkg:id/$id").put("text", label).put("clickable", true),
        androidActions = listOf("click"), risk = ActionRisk.SAFE, confidence = 0.8,
    )

    private fun page(key: String, title: String, vararg controls: PageControl) =
        PageContext("$pkg:Main:$key", pkg, "Main", title, key, "content-$key", controls.toList(), 1, 0, 0)

    private val home = page("home", "Settings", control("Display", "display"), control("Network & internet", "network"))
    private val display = page("display", "Display", control("Screen timeout", "timeout"), control("Navigate up", "up"))
    private val timeout = page("timeout", "Screen timeout", control("Navigate up", "up"))

    private fun labels(page: PageContext) = page.controls.associate { "el-${it.key}" to (it.label to it.role) }

    /** Home → Display → Screen timeout, back, back, and a press that failed. */
    private fun walked(): MappingTrailTap {
        val tap = MappingTrailTap("job-1") { 0 }
        tap.observed("o1", home, labels(home))
        tap.pressed("o1", "el-k-display", ok = true)
        tap.observed("o2", display, labels(display))
        tap.pressed("o2", "el-k-timeout", ok = true)
        tap.observed("o3", timeout, labels(timeout))
        tap.navigated()
        tap.observed("o4", display, labels(display))
        tap.navigated()
        tap.observed("o5", home, labels(home))
        tap.pressed("o5", "el-k-network", ok = false)
        tap.observed("o6", home, labels(home))
        return tap
    }

    private class Store : LearnSink, LearnedReader {
        val apps = mutableMapOf<String, LearnedApp>()
        val screens = mutableMapOf<String, LearnedScreen>()
        val actions = mutableMapOf<String, LearnedAction>()
        val transitions = mutableListOf<LearnedTransition>()
        override fun screens(packageName: String) = screens.values.filter { it.packageName == packageName }
        override fun actions(packageName: String) = actions.values.filter { it.packageName == packageName }
        override fun transitions(packageName: String) = transitions.filter { it.packageName == packageName }
        override fun app(packageName: String) = apps[packageName]
        override fun screenIdFor(packageName: String, pageKey: String) =
            screens.values.firstOrNull { it.packageName == packageName && it.recognition.semanticFingerprint == pageKey }?.id
        override fun putApp(app: LearnedApp) { apps[app.packageName] = app }
        override fun putScreen(screen: LearnedScreen) { screens[screen.id] = screen }
        override fun putAction(action: LearnedAction): String { actions[action.id] = action; return action.id }
        override fun putTransition(transition: LearnedTransition) { transitions += transition }
    }

    @Test fun thePassIsRecordedAsScreensAndTheDoorsThatWorkedNotBackOrFailedPresses() {
        val trail = walked().snapshot()
        assertEquals(listOf("home", "display", "timeout"), trail.screens.map { it.structuralKey })
        val moves = trail.steps.filter { it.ok }.map { it.fromPageKey!!.substringAfterLast(':') + "→" + it.toPageKey!!.substringAfterLast(':') }
        assertEquals(listOf("home→display", "display→timeout"), moves)
        assertEquals("a press that failed is not a pending move", 2, walked().doorPresses)
    }

    @Test fun aMappingPassOnTheOwnersAccountBecomesARouteRunsCanWalk() {
        val store = Store()
        val learning = MappingLearning(store) { 1_000 }
        val report = learning.learnOnce(walked().snapshot(), MappingIdentity.OWN) { "Settings" }!!
        assertEquals(2, report.transitions)
        assertSame("learned once, even when asked again", report, learning.learnOnce(walked().snapshot(), MappingIdentity.OWN) { "Settings" })
        assertEquals(2, store.transitions.size)

        val map = MindMap.from(store, pkg)!!
        val from = map.locate(home.pageKey)!!
        val to = map.locate(timeout.pageKey)!!
        assertEquals(listOf("Display", "Screen timeout"), map.route(from.id, to.id)!!.map { it.label })
        assertTrue(store.transitions.all { it.confidence >= MindMap.CONFIRMED_CONFIDENCE })
    }

    @Test fun aTestAccountPassIsLearnedLessTrustedAndTheMapPrefersConfirmedMoves() {
        val store = Store()
        MappingLearning(store) { 1 }.learnOnce(walked().snapshot(), MappingIdentity.TEST) { "Settings" }
        assertTrue(store.transitions.all { it.confidence < MindMap.CONFIRMED_CONFIDENCE && it.knowledgeState == KnowledgeState.DISCOVERED })
        val map = MindMap.from(store, pkg)!!
        assertNotNull("still routable: the walker checks every screen", map.route(map.locate(home.pageKey)!!.id, map.locate(timeout.pageKey)!!.id))
        assertTrue(map.moves.all { it.confidence < MindMap.CONFIRMED_CONFIDENCE })
    }

    @Test fun anEmptyPassTeachesNothing() {
        assertNull(MappingLearning(Store()).learnOnce(MappingTrailTap("j").snapshot(), MappingIdentity.OWN) { "x" })
    }

    @Test fun contentLabelsNeverReachTheTrail() {
        val tap = MappingTrailTap("j") { 0 }
        val inbox = page("inbox", "Inbox", control("Louella: see you tomorrow at the station, bring the tickets please", "row"), control("Compose", "fab"))
        tap.observed("o1", inbox, labels(inbox))
        val json = tap.snapshot().toJson().toString()
        assertFalse(json.contains("Louella"))
        assertTrue(json.contains("Compose"))
    }
}
