package com.cyclone.mobile.mind.map

import com.cyclone.mobile.applearner.ActionRisk
import com.cyclone.mobile.applearner.KnowledgeState
import com.cyclone.mobile.applearner.LearnedAction
import com.cyclone.mobile.applearner.LearnedScreen
import com.cyclone.mobile.applearner.LearnedTransition
import com.cyclone.mobile.applearner.ScreenRecognition
import com.cyclone.mobile.mind.learn.LearnedReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MindMapTest {
    private val pkg = "com.android.settings"

    private class Reader : LearnedReader {
        val screens = mutableListOf<LearnedScreen>()
        val actions = mutableListOf<LearnedAction>()
        val transitions = mutableListOf<LearnedTransition>()
        override fun screens(packageName: String) = screens.filter { it.packageName == packageName }
        override fun actions(packageName: String) = actions.filter { it.packageName == packageName }
        override fun transitions(packageName: String) = transitions.filter { it.packageName == packageName }
    }

    private fun Reader.screen(id: String, title: String) {
        screens += LearnedScreen(id, pkg, id, title, "", ScreenRecognition("key-$id", "", emptyList(), null, emptyList()),
            KnowledgeState.UNDERSTOOD, 0.8)
    }

    private fun Reader.move(from: String, label: String, to: String, ok: Int = 1, seen: Int = ok,
                            risk: ActionRisk = ActionRisk.SAFE, state: KnowledgeState = KnowledgeState.UNDERSTOOD, confidence: Double = 0.7) {
        val actionId = "a-$from-$label"
        actions += LearnedAction(actionId, pkg, from, label.lowercase(), label, listOf("click"),
            """{"resourceId":"id/$label","role":"button"}""", risk, knowledgeState = state)
        transitions += LearnedTransition("t-$from-$label", pkg, from, actionId, to, successfulCount = ok, observedCount = seen, confidence = confidence)
    }

    /** Settings → Display → Screen timeout, Settings → Network, plus a risky and a flaky door that must not be used. */
    private fun settings() = Reader().apply {
        screen("home", "Settings"); screen("display", "Display"); screen("timeout", "Screen timeout"); screen("network", "Network & internet")
        screen("reset", "Reset options")
        move("home", "Display", "display", ok = 3)
        move("display", "Screen timeout", "timeout")
        move("home", "Network & internet", "network")
        move("network", "Reset options", "reset", risk = ActionRisk.CONSEQUENTIAL)
        move("home", "Search", "timeout", ok = 1, seen = 4)
    }

    @Test fun routesUseOnlySafeReliableMovesAndPreferTheOnesThatWorked() {
        val map = MindMap.from(settings(), pkg)!!
        val route = map.route("home", "timeout")!!
        assertEquals(listOf("Display", "Screen timeout"), route.map { it.label })
        assertNull("the risky door is never routed", map.route("network", "reset"))
        assertEquals(emptyList<MapMove>(), map.route("home", "home"))
    }

    @Test fun screensAreFoundByHandleOrNameAndTheCardListsThemWithTheirMoves() {
        val map = MindMap.from(settings(), pkg)!!
        val timeout = map.find("Screen timeout")!!
        assertEquals(timeout, map.find(timeout.handle))
        assertEquals(timeout, map.find("${timeout.handle} Screen timeout"))
        assertEquals(timeout, map.find("timeout"))
        assertNull(map.find("Bluetooth"))
        val card = map.card("Settings", map.locate("key-home"))
        assertTrue(card, card.startsWith("Map of Settings (learned from earlier runs; 5 screens)."))
        assertTrue(card, card.contains("Settings (you are here): “Display” → ${map.find("Display")!!.handle}"))
        assertTrue(card, card.contains("go_to"))
    }

    private class FakePhone(var at: String, val doors: Map<Pair<String, String>, String>) : MapWalkPort {
        val pressed = mutableListOf<String>()
        override fun currentPageKey() = "key-$at"
        override fun press(label: String, role: String?): MapPress {
            val to = doors[at to label] ?: return MapPress.NOT_ON_SCREEN
            pressed += label
            at = to
            return MapPress.DONE
        }
    }

    private class Log : MapFeedback {
        val walked = mutableListOf<String>()
        val diverged = mutableListOf<String>()
        override fun walked(move: MapMove) { walked += move.label }
        override fun diverged(move: MapMove) { diverged += move.label }
    }

    @Test fun theWalkerGoesStraightThereCheckingEveryScreen() {
        val map = MindMap.from(settings(), pkg)!!
        val phone = FakePhone("home", mapOf(("home" to "Display") to "display", ("display" to "Screen timeout") to "timeout"))
        val log = Log()
        val outcome = MapWalker(map, phone, log).walk(map.find("Screen timeout")!!)
        assertEquals(WalkOutcome.Arrived(map.find("Screen timeout")!!, 2), outcome)
        assertEquals(listOf("Display", "Screen timeout"), phone.pressed)
        assertEquals(listOf("Display", "Screen timeout"), log.walked)
    }

    @Test fun theWalkerStopsAtTheFirstSurpriseAndCountsItAgainstTheMove() {
        val map = MindMap.from(settings(), pkg)!!
        // The app changed: "Display" now opens an unknown page.
        val phone = FakePhone("home", mapOf(("home" to "Display") to "somewhere-new"))
        val log = Log()
        val outcome = MapWalker(map, phone, log).walk(map.find("Screen timeout")!!) as WalkOutcome.Diverged
        assertEquals(1, outcome.moves)
        assertNull(outcome.at)
        assertEquals(listOf("Display"), log.diverged)

        val gone = MapWalker(map, FakePhone("home", emptyMap()), log).walk(map.find("Display")!!) as WalkOutcome.Diverged
        assertEquals(0, gone.moves)
        assertTrue(gone.reason, gone.reason.contains("not on Settings"))
    }

    @Test fun landingOnAnotherKnownScreenReplansFromThere() {
        val map = MindMap.from(settings(), pkg)!!
        // "Display" now lands on Network; from Network there is no route to Screen timeout, so it stops honestly.
        val phone = FakePhone("home", mapOf(("home" to "Display") to "network"))
        val outcome = MapWalker(map, phone, Log()).walk(map.find("Screen timeout")!!) as WalkOutcome.Diverged
        assertTrue(outcome.reason, outcome.reason.contains("no known way on from Network"))
        // Already there, or not on the map at all.
        assertEquals(0, MapWalker(map, FakePhone("timeout", emptyMap())).walk(map.find("Screen timeout")!!).moves)
        assertTrue(MapWalker(map, FakePhone("elsewhere", emptyMap())).walk(map.find("Display")!!) is WalkOutcome.NotOnMap)
    }

    @Test fun staleOrUnusedKnowledgeIsNotAMap() {
        val reader = Reader().apply {
            screen("home", "Settings"); screen("display", "Display")
            move("home", "Display", "display", state = KnowledgeState.STALE)
        }
        val map = MindMap.from(reader, pkg)!!
        assertTrue(map.moves.isEmpty())
        assertNull(MindMap.from(Reader(), pkg))
    }

    @Test fun aDivergenceDropsTheCachedMapSoTheBadRouteIsReread() {
        val reader = settings()
        var builds = 0
        val counting = object : LearnedReader by reader {
            override fun screens(packageName: String) = reader.screens(packageName).also { builds++ }
        }
        val maps = MindMaps(counting)
        val first = maps.map(pkg)!!
        assertEquals(first, maps.map(pkg))
        assertEquals(1, builds)
        maps.feedback(pkg).diverged(first.moves.first())
        maps.map(pkg)
        assertEquals(2, builds)
        assertTrue(maps.firstVisit(pkg))
        assertTrue(!maps.firstVisit(pkg))
    }

    @Test fun aConfirmedRouteIsPreferredOverAShorterOneOnlySeenOnATestAccount() {
        val reader = Reader().apply {
            screen("home", "Settings"); screen("display", "Display"); screen("timeout", "Screen timeout")
            move("home", "Display", "display"); move("display", "Screen timeout", "timeout")
            move("home", "Timeout shortcut", "timeout", confidence = 0.5)
        }
        val map = MindMap.from(reader, pkg)!!
        assertEquals(listOf("Display", "Screen timeout"), map.route("home", "timeout")!!.map { it.label })
    }
}
