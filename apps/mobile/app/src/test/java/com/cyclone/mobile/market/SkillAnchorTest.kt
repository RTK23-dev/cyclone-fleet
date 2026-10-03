package com.cyclone.mobile.market

import com.cyclone.mobile.applearner.ActionRisk
import com.cyclone.mobile.applearner.KnowledgeState
import com.cyclone.mobile.applearner.LearnedAction
import com.cyclone.mobile.applearner.LearnedScreen
import com.cyclone.mobile.applearner.LearnedTransition
import com.cyclone.mobile.applearner.ScreenRecognition
import com.cyclone.mobile.mind.learn.LearnedReader
import com.cyclone.mobile.mind.learn.MissionTrail
import com.cyclone.mobile.mind.learn.TrailScreen
import com.cyclone.mobile.mind.learn.TrailStep
import com.cyclone.mobile.mind.map.MindMap
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Grounded skills (plan 23): a skill knows where it works on the app's map, and whether that still holds. */
class SkillAnchorTest {
    @get:Rule val folder = TemporaryFolder()
    private val clock = CLOCK
    private val launcher = "com.google.android.apps.nexuslauncher"

    private fun screen(pkg: String, key: String, title: String) = TrailScreen("$pkg:$key", pkg, null, title, key, emptyList(), 0)
    private fun step(from: String?, to: String?, control: String? = "c", ok: Boolean = true) = TrailStep("phone.click", from, control, to, ok, 0)

    /** Launcher → Clock (Alarm tab) → Timer tab → a detour to Settings and back → set a 10-minute timer on Timer. */
    private fun timerRun() = MissionTrail("m1",
        listOf(screen(launcher, "home", "Home"), screen(clock, "alarm", "Alarm"), screen(clock, "timer", "Timer"), screen(clock, "settings", "Settings")),
        listOf(
            step("$launcher:home", "$clock:alarm", control = null), // open_app has no control
            step("$clock:alarm", "$clock:timer"),
            step("$clock:timer", "$clock:settings"),
            step("$clock:settings", "$clock:timer"),
            step("$clock:timer", "$clock:timer"), step("$clock:timer", "$clock:timer"), step("$clock:timer", "$clock:timer"),
            step("$clock:timer", "$clock:timer", ok = false),
        ))

    @Test fun aRunsAnchorIsTheAppItWorkedInTheDestinationAndTheWayThereWithoutDetours() {
        val anchor = SkillAnchor.fromTrail(timerRun(), 5)!!
        assertEquals(clock, anchor.packageName)
        assertEquals(listOf("Alarm", "Timer"), anchor.route.map { it.title })
        assertEquals("$clock:timer", anchor.destination.pageKey)
        assertEquals("work at the destination, the detour included, failed step not", 5, anchor.finishSteps)
        assertEquals(anchor, SkillAnchor.fromJson(JSONObject(anchor.toJson().toString())))
    }

    @Test fun aRunThatDidNothingInAnAppHasNoAnchor() {
        assertNull(SkillAnchor.fromTrail(MissionTrail("m", listOf(screen(clock, "alarm", "Alarm")), listOf(step("$clock:alarm", null, ok = false))), 0))
    }

    private class Reader(private val clock: String = CLOCK) : LearnedReader {
        val screens = mutableListOf<LearnedScreen>()
        val actions = mutableListOf<LearnedAction>()
        val transitions = mutableListOf<LearnedTransition>()
        override fun screens(packageName: String) = screens.filter { it.packageName == packageName }
        override fun actions(packageName: String) = actions.filter { it.packageName == packageName }
        override fun transitions(packageName: String) = transitions.filter { it.packageName == packageName }
        fun screen(key: String, title: String) {
            screens += LearnedScreen(key, clock, key, title, "", ScreenRecognition("$clock:$key", "", emptyList(), null, emptyList()), KnowledgeState.UNDERSTOOD, 0.8)
        }
        fun move(from: String, label: String, to: String) {
            actions += LearnedAction("a-$from-$label", clock, from, label.lowercase(), label, listOf("click"), "{}", ActionRisk.SAFE, knowledgeState = KnowledgeState.UNDERSTOOD)
            transitions += LearnedTransition("t-$from-$label", clock, from, "a-$from-$label", to, successfulCount = 1, observedCount = 1, confidence = 0.7)
        }
    }

    @Test fun healthComesFromTheMapAsItIsNow() {
        val anchor = SkillAnchor.fromTrail(timerRun(), 5)!!
        assertEquals(SkillGroundState.NOT_GROUNDED, SkillGrounding.health(null, null).state)
        assertEquals(SkillGroundState.NEEDS_RECHECK, SkillGrounding.health(anchor, null).state)

        val partial = Reader().apply { screen("alarm", "Alarm"); screen("timer", "Timer"); screen("x", "Bedtime"); move("alarm", "Bedtime", "x") }
        assertEquals(SkillGroundState.PARTIAL, SkillGrounding.health(anchor, MindMap.from(partial, clock)).state)

        val known = Reader().apply { screen("alarm", "Alarm"); screen("timer", "Timer"); move("alarm", "Timer", "timer") }
        val health = SkillGrounding.health(anchor, MindMap.from(known, clock))
        assertEquals(SkillGroundState.GROUNDED, health.state)
        assertEquals(1, health.routeMoves)
        assertEquals("1 known move to “Timer”.", health.detail)

        val moved = Reader().apply { screen("alarm", "Alarm"); screen("stopwatch", "Stopwatch"); move("alarm", "Stopwatch", "stopwatch") }
        assertEquals("the destination left the map: re-check", SkillGroundState.NEEDS_RECHECK, SkillGrounding.health(anchor, MindMap.from(moved, clock)).state)
    }

    @Test fun theMindIsToldWhereTheSkillWorksAndToWalkThere() {
        val anchor = SkillAnchor.fromTrail(timerRun(), 5)!!
        val map = MindMap.from(Reader().apply { screen("alarm", "Alarm"); screen("timer", "Timer"); move("alarm", "Timer", "timer") }, clock)
        val card = SkillGrounding.card("Set a 10 minute timer", "Clock", anchor, map)
        val handle = map!!.locate("$clock:timer")!!.handle
        assertTrue(card, card.contains("saved skill “Set a 10 minute timer”"))
        assertTrue(card, card.contains("go_to $handle"))
        assertTrue(card, card.contains("“Alarm” → “Timer”"))
        val lost = SkillGrounding.card("Set a 10 minute timer", "Clock", anchor, null)
        assertTrue(lost, lost.contains("find it yourself"))
    }

    @Test fun ownerSkillsKeepTheirAnchorAndANewerRunReGroundsIt() {
        val skills = OwnerSkills(folder.root.resolve("owner-skills.json"))
        val goal = "Set a 10 minute timer"
        val first = skills.save(goal, listOf(clock), SkillAnchor.fromTrail(timerRun(), 1))
        assertEquals("$clock:timer", skills.anchor(first.id)!!.destination.pageKey)
        val moved = SkillAnchor(clock, listOf(SkillWaypoint("$clock:alarm", "Alarm"), SkillWaypoint("$clock:timers", "Timers")), 2, 9)
        skills.save(goal, emptyList(), moved)
        assertEquals(1, skills.list().size)
        assertEquals("Timers", OwnerSkills(folder.root.resolve("owner-skills.json")).anchor(first.id)!!.destination.title)
        skills.remove(first.id)
        assertNull(skills.anchor(first.id))
    }

    private companion object {
        const val CLOCK = "com.google.android.deskclock"
    }
}
