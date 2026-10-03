package com.cyclone.mobile.mind.pilot

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Plan 41: the Pilot. The smart model plans the whole run; the rapid model answers a decision board per move and does
 * the low-risk moves itself; mismatches bump the smart model; code decides what is irreversible.
 */
class PilotTest {
    private val home = PilotScreen("Launcher", null, emptyList(), listOf(PilotControl("e1", "Instagram", "app icon")))
    private val chats = PilotScreen("WhatsApp", "Chats", listOf("Chats"), listOf(
        PilotControl("e1", "Search", "button"), PilotControl("e4", "Lou", "list item"), PilotControl("e5", "Mam", "list item")))
    private val chat = PilotScreen("WhatsApp", "Lou", listOf("Lou", "online"), listOf(
        PilotControl("e9", "Message", "text field", editable = true), PilotControl("e10", "Send", "button")))

    /** A phone that moves between screens on taps and records what it was asked to do. */
    private class FakePhone(var screen: PilotScreen, val next: Map<String, PilotScreen> = emptyMap(), val apps: Map<String, PilotScreen> = emptyMap()) : PilotHands {
        val did = mutableListOf<String>()
        var stop = false
        override fun look(withImage: Boolean) = PilotLook(screen, if (withImage) "data:image/png;base64,AA" else null)
        override fun tap(ref: String): PilotMove {
            did += "tap $ref"
            val to = next[ref] ?: return PilotMove(true, false, "Tapped. The screen did not visibly change.")
            screen = to
            return PilotMove(true, true, "Tapped. The screen changed.")
        }
        override fun type(ref: String, text: String): PilotMove { did += "type $ref $text"; return PilotMove(true, true, "Typed.") }
        override fun scroll(down: Boolean): PilotMove { did += "scroll ${if (down) "down" else "up"}"; return PilotMove(true, true, "Scrolled.") }
        override fun back(): PilotMove { did += "back"; return PilotMove(true, true, "Back.") }
        override fun openApp(app: String): PilotMove { did += "open $app"; apps[app]?.let { screen = it }; return PilotMove(true, true, "Opened $app.") }
        override fun pressEnter(fieldLabel: String): PilotMove { did += "enter $fieldLabel"; return PilotMove(true, true, "Enter.") }
        override fun stopped() = stop
    }

    private fun script(vararg answers: PilotAnswer?): Pair<PilotDecider, MutableList<PilotQuestion>> {
        val asked = mutableListOf<PilotQuestion>()
        val queue = ArrayDeque(answers.toList())
        return PilotDecider { q -> asked += q; queue.removeFirstOrNull() } to asked
    }

    private fun advisor(vararg verdicts: PilotVerdict?): Pair<PilotAdvisor, MutableList<PilotReview>> {
        val asked = mutableListOf<PilotReview>()
        val queue = ArrayDeque(verdicts.toList())
        return PilotAdvisor { r -> synchronized(asked) { asked += r }; queue.removeFirstOrNull() } to asked
    }

    private val sure = PilotSettings(sureness = 0.85, images = false, lookahead = false)
    private val steps = listOf(PilotStep("Open the chat with Lou", "the chat with Lou is open"), PilotStep("Type the ETA", text = "On my way, 10 min"))

    @Test fun routineStepsRunFastAndTheRecordSaysWhatHappened() {
        val phone = FakePhone(chats, mapOf("e4" to chat))
        val (decider, asked) = script(PilotAnswer("e4", 0.95), PilotAnswer("step_done", 0.93), PilotAnswer("e9", 0.91), PilotAnswer("step_done", 0.9))
        val outcome = Pilot.run("Send Lou my ETA", steps, phone, decider, sure)
        assertNull(outcome.handBack)
        assertEquals(2, outcome.stepsDone)
        assertEquals(listOf("tap e4", "type e9 On my way, 10 min"), phone.did)
        // Every question carries the goal, the plan around the step and the step, never the screen alone.
        assertTrue(asked.all { Pilot.context(it).contains("Goal: Send Lou my ETA") && Pilot.context(it).contains("Plan:") })
        val report = Pilot.report("fast", sure, steps, outcome)
        assertTrue(report, report.contains("tapped e4 \"Lou\"") && report.contains("All 2 steps done"))
    }

    @Test fun withoutTheSmartModelsSideChannelTheRapidModelsDoubtGoesToTheMind() {
        val phone = FakePhone(chats)
        val (decider, _) = script(PilotAnswer("hand_back", 0.97, "unexpected_screen"))
        val outcome = Pilot.run("Send Lou my ETA", steps, phone, decider, sure)
        assertEquals(PilotHandBack.FAST_MODEL, outcome.handBack!!.by)
        assertEquals("unexpected_screen", outcome.handBack!!.reason)
        assertTrue(phone.did.isEmpty())
        val report = Pilot.report("fast", sure, steps, outcome)
        assertTrue(report, report.contains("The rapid model handed step 1 back to you"))
        assertTrue(report, report.contains("Not done yet: \"Open the chat with Lou\"; \"Type the ETA\""))
    }

    @Test fun theBoardsYesNoAnswersStopTheRapidModel() {
        val offPlan = Pilot.run("g", steps, FakePhone(chats), script(PilotAnswer("e4", 0.99, fitsPlan = false)).first, sure)
        assertEquals("unexpected_screen", offPlan.handBack!!.reason)
        val needsSmart = Pilot.run("g", steps, FakePhone(chats), script(PilotAnswer("e4", 0.99, "step_unclear", needsSmart = true)).first, sure)
        assertEquals("step_unclear", needsSmart.handBack!!.reason)
    }

    @Test fun aMismatchBumpsTheSmartModelWhichPatchesThePlanAndTheRunCarriesOn() {
        val phone = FakePhone(chats, mapOf("e1" to chats.copy(title = "Search"), "e4" to chat))
        val (decider, _) = script(
            PilotAnswer("hand_back", 0.9, "not_on_screen"),   // Lou isn't where the plan said
            PilotAnswer("e4", 0.95), PilotAnswer("step_done", 0.95),
        )
        val (smart, reviews) = advisor(PilotVerdict(PilotVerdict.REVISE, listOf(PilotStep("Tap Lou in the chat list", "the chat with Lou is open")), "Lou is in the list"))
        val outcome = Pilot.run("g", listOf(PilotStep("Search for Lou")), phone, decider, sure, smart)
        assertNull(outcome.handBack)
        assertEquals(1, outcome.bumps)
        assertEquals("Tap Lou in the chat list", outcome.plan.single().action)
        assertTrue(reviews.single().problem!!.contains("not on screen"))
        assertTrue(outcome.lines.any { it.contains("↺ bump: the smart model revised the plan") })
    }

    @Test fun theSmartModelCanTakeTheStepAndBumpsAreLimited() {
        val (decider, _) = script(PilotAnswer("hand_back", 0.9, "step_unclear"))
        val (smart, _) = advisor(PilotVerdict(PilotVerdict.RETURN, note = "the owner must choose which Lou"))
        val outcome = Pilot.run("g", steps, FakePhone(chats), decider, sure, smart)
        assertEquals(PilotHandBack.SMART_MODEL, outcome.handBack!!.by)
        assertTrue(Pilot.report("fast", sure, steps, outcome).contains("the owner must choose which Lou"))

        val doubts = Array(10) { PilotAnswer("hand_back", 0.9, "not_on_screen") }
        val revise = Array(10) { PilotVerdict(PilotVerdict.REVISE, listOf(PilotStep("try again"))) }
        val (again, _) = advisor(*revise)
        val limited = Pilot.run("g", steps, FakePhone(chats), script(*doubts).first, sure, again)
        assertEquals(Pilot.MAX_BUMPS, limited.bumps)
        assertEquals(PilotHandBack.FAST_MODEL, limited.handBack!!.by)
    }

    @Test fun hardProblemsNeverBumpTheyGoStraightToTheMind() {
        val (smart, reviews) = advisor(PilotVerdict(PilotVerdict.REVISE, listOf(PilotStep("x"))))
        val (decider, _) = script(PilotAnswer("hand_back", 0.9, "needs_owner"))
        val outcome = Pilot.run("g", steps, FakePhone(chats), decider, sure, smart)
        assertEquals("needs_owner", outcome.handBack!!.reason)
        assertTrue(reviews.isEmpty())
    }

    @Test fun anIrreversibleMoveTheSmartModelDidntPlanIsConfirmedFirst() {
        val phone = FakePhone(chat)
        // The plan says "send it" but didn't mark it irreversible: the rapid model's pick of Send bumps the smart model.
        val (decider, _) = script(PilotAnswer("e10", 0.97), PilotAnswer("e10", 0.97), PilotAnswer("step_done", 0.95))
        val (smart, reviews) = advisor(PilotVerdict(PilotVerdict.OK))
        val outcome = Pilot.run("g", listOf(PilotStep("Send it")), phone, decider, sure, smart)
        assertNull(outcome.handBack)
        assertTrue(reviews.single().problem!!.contains("looks irreversible (Send)"))
        assertEquals(listOf("tap e10"), phone.did)
        assertTrue(outcome.plan.single().irreversible)
        // Without the side channel, an unplanned irreversible move never happens.
        val alone = Pilot.run("g", listOf(PilotStep("Send it")), FakePhone(chat), script(PilotAnswer("e10", 0.99)).first, sure)
        assertEquals("looks_risky", alone.handBack!!.reason)
        assertTrue(Pilot.irreversible("Send") && Pilot.irreversible("Verwijderen") && Pilot.irreversible("Pay €12") && !Pilot.irreversible("Message"))
    }

    @Test fun aPlannedIrreversibleMoveWaitsForTheParallelReviewThenGoes() {
        val phone = FakePhone(chat)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reviews = mutableListOf<PilotReview>()
        val slow = PilotAdvisor { r -> synchronized(reviews) { reviews += r }; started.countDown(); release.await(5, TimeUnit.SECONDS); PilotVerdict(PilotVerdict.OK) }
        val decider = PilotDecider { q ->
            // The rapid model decides at once; the smart model's review is still running in the background.
            assertTrue(started.await(5, TimeUnit.SECONDS))
            release.countDown()
            if (q.done.isEmpty()) PilotAnswer("e10", 0.97) else PilotAnswer("step_done", 0.95)
        }
        val outcome = Pilot.run("g", listOf(PilotStep("Tap Send", irreversible = true)), phone, decider, sure.copy(lookahead = true), slow)
        assertNull(outcome.handBack)
        assertEquals(listOf("tap e10"), phone.did)
        assertTrue(synchronized(reviews) { reviews.first().problem } == null) // a look-ahead, not a bump
        assertTrue(outcome.lines.any { it.contains("waiting for the smart model's review") })
    }

    @Test fun aLookAheadRevisionArrivingMidRunIsApplied() {
        val instagram = PilotScreen("Instagram", "Home", emptyList(), listOf(PilotControl("e2", "Messenger", "button")))
        val inbox = PilotScreen("Instagram", "Messages", emptyList(), listOf(PilotControl("e7", "Search", "text field", editable = true)))
        val phone = FakePhone(home, next = mapOf("e2" to inbox), apps = mapOf("Instagram" to instagram))
        // The review was for step 1 on, so its steps start there.
        val revised = PilotVerdict(PilotVerdict.REVISE, listOf(PilotStep("Open Instagram", app = "Instagram"),
            PilotStep("Open Direct messages", "the inbox is open"), PilotStep("Search lo.06", text = "lo.06")), "Direct is the Messenger icon")
        val (smart, reviews) = advisor(revised)
        val plan = listOf(PilotStep("Open Instagram", app = "Instagram"), PilotStep("Open Direct"), PilotStep("Search lo.06", text = "lo.06"))
        val decider = PilotDecider { q ->
            when {
                q.step.action == "Open Instagram" && q.screen.app == "Launcher" -> PilotAnswer(Pilot.OPEN_APP, 0.96)
                // The rapid model is sure the app is open; the smart model's review lands meanwhile.
                q.step.action == "Open Instagram" -> { Thread.sleep(100); PilotAnswer(Pilot.STEP_DONE, 0.95) }
                q.screen.app == "Instagram" && q.screen.title == "Home" -> PilotAnswer("e2", 0.93)
                q.step.action.startsWith("Open Direct") -> PilotAnswer(Pilot.STEP_DONE, 0.94)
                q.done.isEmpty() -> PilotAnswer("e7", 0.92)
                else -> PilotAnswer(Pilot.STEP_DONE, 0.92)
            }
        }
        val outcome = Pilot.run("Message lo.06 on Instagram", plan, phone, decider, sure.copy(lookahead = true), smart)
        assertNull(outcome.handBack)
        assertEquals(listOf("open Instagram", "tap e2", "type e7 lo.06"), phone.did)
        assertEquals(1, reviews.size) // entering Instagram started one look-ahead
        assertTrue(outcome.lines.toString(), outcome.lines.any { it.contains("↺ while running: the smart model revised the plan (2 steps left)") })
        assertEquals(listOf("Open Instagram", "Open Direct messages", "Search lo.06"), outcome.plan.map { it.action })
    }

    @Test fun toolMovesUseOnlyThePlansOwnAppLinkAndField() {
        val q = Pilot.question("g", PilotStep("Open Instagram", app = "Instagram"), 0, 1, emptyList(), home, null)
        assertTrue(Pilot.OPEN_APP in q.options && Pilot.OPEN_LINK !in q.options && Pilot.PRESS_ENTER !in q.options)
        val q2 = Pilot.question("g", PilotStep("Search"), 0, 1, emptyList(), home, null, typedInto = "Search")
        assertTrue(Pilot.PRESS_ENTER in q2.options && Pilot.OPEN_APP !in q2.options)
        // Enter in a search field is fine; Enter in a message box could send, so it needs a marked step.
        val search = PilotScreen("Instagram", null, emptyList(), listOf(PilotControl("e7", "Search", "text field", editable = true)))
        val phone = FakePhone(search)
        val (decider, _) = script(PilotAnswer("e7", 0.95), PilotAnswer(Pilot.PRESS_ENTER, 0.95), PilotAnswer(Pilot.STEP_DONE, 0.95))
        assertNull(Pilot.run("g", listOf(PilotStep("Search lo.06", text = "lo.06")), phone, decider, sure).handBack)
        assertEquals(listOf("type e7 lo.06", "enter Search"), phone.did)
        val box = FakePhone(chat)
        val (sender, _) = script(PilotAnswer("e9", 0.95), PilotAnswer(Pilot.PRESS_ENTER, 0.95))
        assertEquals("looks_risky", Pilot.run("g", listOf(PilotStep("Write it", text = "hi")), box, sender, sure).handBack!!.reason)
    }

    @Test fun anUnsureAnswerIsHandedBackBeforeAnyMove() {
        val phone = FakePhone(chats, mapOf("e4" to chat))
        val outcome = Pilot.run("Send Lou my ETA", steps, phone, script(PilotAnswer("e4", 0.6)).first, sure)
        assertEquals("unsure", outcome.handBack!!.reason)
        assertEquals(PilotHandBack.HARNESS, outcome.handBack!!.by)
        assertTrue(phone.did.isEmpty())
    }

    @Test fun noAnswerOrAnAnswerOutsideTheChoicesHandsBack() {
        assertEquals("no_answer", Pilot.run("g", steps, FakePhone(chats), script(null).first, sure).handBack!!.reason)
        assertEquals("invalid", Pilot.run("g", steps, FakePhone(chats), script(PilotAnswer("e77", 0.99)).first, sure).handBack!!.reason)
    }

    @Test fun aMoveThatChangedNothingIsNotRepeated() {
        val phone = FakePhone(chats) // e5 changes nothing
        val outcome = Pilot.run("g", steps, phone, script(PilotAnswer("e5", 0.9), PilotAnswer("e5", 0.9)).first, sure)
        assertEquals("repeat", outcome.handBack!!.reason)
        assertEquals(listOf("tap e5"), phone.did)
    }

    @Test fun aStepHasAMoveBudget() {
        val phone = FakePhone(chats)
        val outcome = Pilot.run("g", steps, phone, script(*Array(10) { PilotAnswer("scroll_down", 0.9) }).first, sure)
        assertEquals("too_many_moves", outcome.handBack!!.reason)
        assertEquals(Pilot.MAX_MOVES_PER_STEP, phone.did.size)
    }

    @Test fun sensitiveScreensAndKeptOffAppsAreNeverTouched() {
        val (decider, asked) = script(PilotAnswer("e4", 0.99))
        val (smart, reviews) = advisor(PilotVerdict(PilotVerdict.OK))
        val outcome = Pilot.run("g", steps, FakePhone(chats.copy(sensitive = true)), decider, sure.copy(lookahead = true), smart)
        assertEquals("sensitive", outcome.handBack!!.reason)
        assertTrue(asked.isEmpty() && reviews.isEmpty())
        assertTrue(Pilot.keepOff("com.ing.mobile", "ING Bankieren"))
        assertTrue(Pilot.keepOff("com.google.android.apps.walletnfcrel", "Google Wallet"))
        assertTrue(Pilot.keepOff("com.revolut.revolut", "Revolut"))
        assertFalse(Pilot.keepOff("com.whatsapp", "WhatsApp"))
        assertFalse(Pilot.keepOff("com.instagram.android", "Instagram"))
    }

    @Test fun secretFieldsAreNeverChoicesAndSecretTextIsNeverTyped() {
        val form = PilotScreen("Shop", null, emptyList(), listOf(PilotControl("e1", "Password", "password field", editable = true, secret = true),
            PilotControl("e2", "Note", "text field", editable = true)))
        val q = Pilot.question("g", PilotStep("fill the note", text = "x"), 0, 1, emptyList(), form, null)
        assertFalse(q.options.containsKey("e1"))
        val outcome = Pilot.run("g", listOf(PilotStep("fill the note", text = "my password: Zomer2024")), FakePhone(form),
            script(PilotAnswer("e2", 0.99)).first, sure)
        assertEquals("secret_text", outcome.handBack!!.reason)
        // Secret-looking steps from the smart model are dropped, too.
        assertNull(PilotWire.parseVerdict("""{"verdict":"revise","steps":[{"do":"type the password","text":"wachtwoord is Zomer2024"}]}"""))
    }

    @Test fun theShortlistKeepsTheStepsControlsOnALongScreen() {
        val many = (1..40).map { PilotControl("e$it", "Item $it", "list item") } + PilotControl("e41", "Lou", "list item")
        val list = Pilot.shortlist(PilotStep("open the chat with Lou"), many)
        assertEquals(Pilot.MAX_CHOICES, list.size)
        assertTrue(list.any { it.ref == "e41" })
    }

    @Test fun imagesAreAskedForOnWeakScreensOrAfterAMoveChangedNothing() {
        var images = 0
        val phone = object : PilotHands by FakePhone(chats) {
            override fun look(withImage: Boolean): PilotLook { if (withImage) images++; return PilotLook(chats, if (withImage) "img" else null) }
        }
        val (decider, asked) = script(PilotAnswer("e5", 0.9), PilotAnswer("hand_back", 0.9, "not_on_screen"))
        Pilot.run("g", steps, phone, decider, PilotSettings(0.85, images = true, lookahead = false))
        assertEquals(1, images)
        assertNull(asked[0].image)
        assertEquals("img", asked[1].image)
    }

    @Test fun theOwnerCanStopThePilot() {
        val phone = FakePhone(chats).apply { stop = true }
        assertEquals("stopped", Pilot.run("g", steps, phone, script().first, sure).handBack!!.reason)
    }

    @Test fun thePlanIsReadFromTheToolArguments() {
        val args = JSONObject("""{"steps":[{"do":"Open Instagram","app":"Instagram"},"Tap Direct",{"do":" "},
            {"do":"Type","text":"hi"},{"do":"Send","risk":"irreversible"},{"do":"Open","link":"javascript:alert(1)"}]}""")
        assertEquals(listOf(PilotStep("Open Instagram", app = "Instagram"), PilotStep("Tap Direct"), PilotStep("Type", text = "hi"),
            PilotStep("Send", irreversible = true), PilotStep("Open")), Pilot.steps(args))
    }

    @Test fun wireFormatsAreReadTolerantly() {
        val q = Pilot.question("g", steps[0], 0, 2, emptyList(), chats, null)
        val body = PilotWire.choiceBody("google/x", q)
        assertTrue(body.toString(), body.toString().contains("\"enum\":[\"e1\",\"e4\",\"e5\",\"step_done\",\"scroll_down\",\"scroll_up\",\"back\",\"wait\",\"hand_back\"]"))
        val completion = """{"choices":[{"message":{"content":"{\"fits_plan\":true,\"needs_smart\":false,\"choice\":\"e4\",\"confidence\":0.92,\"reason\":\"other\"}"}}]}"""
        assertEquals(PilotAnswer("e4", 0.92, "other"), PilotWire.parseChoice(completion))
        assertFalse(PilotWire.parseChoice("""{"choices":[{"message":{"content":"{\"fits_plan\":false,\"choice\":\"e4\",\"confidence\":0.9}"}}]}""")!!.fitsPlan)
        assertNull(PilotWire.parseChoice("""{"choices":[{"message":{"content":"sure, tap Lou"}}]}"""))
        val board = PilotWire.parseDecisions("""{"answers":{"next":{"choice":"hand_back","confidence":0.8},"reason":"step_unclear","needs_smart":"yes","fits_plan":"no"}}""")!!
        assertEquals("hand_back", board.choice)
        assertTrue(board.needsSmart && !board.fitsPlan)
        assertEquals(PilotAnswer("e4", 0.7, null), PilotWire.parseDecisions("""{"decisions":{"next":{"value":"e4","probability":0.7}}}"""))
        assertEquals("e4", PilotWire.parseDecisions("""{"next":"e4"}""")!!.choice)
        assertNull(PilotWire.parseDecisions("""{"nothing":1}"""))
        val decisions = PilotWire.decisionsBody("~typesafe/jev-latest", q)
        assertEquals(listOf("fits_plan", "needs_smart", "next", "reason"), decisions.getJSONObject("questions").keys().asSequence().toList().sorted())
        // The smart model's verdict: fenced or wrapped JSON is read; unknown verdicts are no verdict.
        val verdict = PilotWire.parseVerdict("Here you go:\n```json\n{\"verdict\":\"revise\",\"steps\":[{\"do\":\"Tap Send\",\"risk\":\"irreversible\"}],\"note\":\"n\"}\n```")!!
        assertEquals(PilotVerdict.REVISE, verdict.kind)
        assertTrue(verdict.steps.single().irreversible)
        assertEquals(PilotVerdict.OK, PilotWire.parseVerdict("""{"verdict":"ok"}""")!!.kind)
        assertNull(PilotWire.parseVerdict("""{"verdict":"maybe"}"""))
        val review = PilotReview("Message lo.06", listOf(PilotStep("Open Instagram", app = "Instagram"), PilotStep("Send", irreversible = true)), 1,
            listOf("Step 1: • opened Instagram"), chat, null)
        val text = PilotWire.advisorMessages(review).getJSONObject(1).getString("content")
        assertTrue(text, text.contains("→ 2. Send") && text.contains("[irreversible]") && text.contains("Screen now: WhatsApp"))
    }
}
