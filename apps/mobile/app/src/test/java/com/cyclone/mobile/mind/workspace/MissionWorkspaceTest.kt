package com.cyclone.mobile.mind.workspace

import com.cyclone.mobile.mind.MindConversation
import com.cyclone.mobile.mind.MindMessage
import com.cyclone.mobile.mind.MindToolCall
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan 37: the mission workspace's bookkeeping, pure. */
class MissionWorkspaceTest {
    private fun screen(pkg: String, app: String, title: String? = null, vararg texts: String, fields: Map<String, String> = emptyMap(),
                       states: Map<String, String> = emptyMap()) = ScreenFacts(pkg, app, title, texts.toList(), fields, states)

    private val insta = screen("com.instagram.android", "Instagram", "Messages", "lo.06", "Louella")
    private val maps = screen("com.google.android.apps.maps", "Maps", "Directions", "22 min", "Kerkstraat 12")
    private val picker = screen("com.google.android.providers.media.module", "Photos", null, "Recent")
    private val apps = listOf("Instagram" to "com.instagram.android", "Maps" to "com.google.android.apps.maps",
        "Settings" to "com.android.settings", "WhatsApp" to "com.whatsapp")

    @Test
    fun surfacesNeverOpenAStayAndAnotherAppNeedsTwoLooksUnlessTheMoveWasOnPurpose() {
        val ws = MissionWorkspace()
        ws.beginTurn(1, 2, 0, 0)
        assertTrue(ws.observe(insta))
        assertFalse("a photo picker is still in Instagram", ws.observe(picker))
        assertFalse(ws.observe(screen("com.google.android.inputmethod.latin", "Gboard")))
        assertFalse("a Custom Tab opened by a tap stays nested", ws.observe(screen("com.android.chrome", "Chrome", null, "article")))
        ws.beginTurn(2, 5, 0, 0)
        assertFalse("one look at another app is not a stay yet", ws.observe(maps))
        assertTrue(ws.observe(insta).not())
        assertEquals(1, ws.stayCount)
        ws.beginTurn(3, 8, 0, 0)
        assertFalse(ws.observe(maps))
        ws.beginTurn(4, 11, 0, 0)
        assertTrue("two looks in a row open it", ws.observe(maps))
        assertEquals(2, ws.stayCount)
        assertEquals("Maps", ws.currentStay?.app)
        ws.beginTurn(5, 14, 0, 0)
        ws.movingOnPurpose("reply with the ETA")
        assertTrue("open_app switches at once", ws.observe(insta))
        assertEquals("a quick return is marked as coming back", 1, ws.currentStay?.returnOf)
    }

    @Test
    fun aClosedStayIsFoldedWithAJournalBlockAndStaysRecallable() {
        val ws = MissionWorkspace()
        val conversation = MindConversation(listOf(MindMessage.System("sys"), MindMessage.User("goal")))
        ws.attach { conversation.all() }
        fun turn(n: Int, facts: ScreenFacts, screenText: String, purposeful: Boolean = false) {
            ws.beginTurn(n, conversation.size(), 0, 0)
            conversation.add(MindMessage.Assistant("Looking at $n", listOf(MindToolCall("c$n", "screen_read", "{}")), JSONArray().put("opaque")))
            if (purposeful) ws.movingOnPurpose()
            ws.observe(facts)
            conversation.add(MindMessage.Tool("c$n", "screen_read", screenText, "brief $n"))
            conversation.add(MindMessage.User("shot", imageDataUrl = "data:image/png;base64,AA", origin = MindMessage.User.Origin.SCREEN))
            ws.endTurn(conversation)
        }
        turn(1, insta, "Screen: Instagram full text one")
        ws.collect("handle", "lo.06", "Instagram")
        turn(2, insta, "Screen: Instagram full text two")
        val before = conversation.size()
        turn(3, maps, "Screen: Maps full", purposeful = true)
        val all = conversation.all()
        val folded = all.filterIsInstance<MindMessage.Tool>().filter { it.callId in setOf("c1", "c2") }
        assertTrue(folded.all { it.compacted })
        assertEquals("the full text stays for recall", "Screen: Instagram full text one", folded.first().full)
        val first = all.filterIsInstance<MindMessage.Assistant>().first()
        assertNull("provider reasoning of a closed stay is dropped", first.reasoningDetails)
        assertEquals("Looking at 1", first.text)
        assertTrue(all.filterIsInstance<MindMessage.User>().filter { it.text.startsWith("shot") && it.text.contains("removed") }.size >= 2)
        val journal = all.last() as MindMessage.User
        assertTrue(journal.text.startsWith("JOURNAL · stay 1 · Instagram"))
        assertTrue(journal.text.contains("handle = lo.06"))
        assertTrue(journal.text.contains("recall(stay=1)"))
        assertEquals("no message is ever removed", before + 3 + 1, conversation.size())
        assertTrue(ws.recallTurn(1)!!.contains("Screen: Instagram full text one"))
        assertTrue(ws.recallStay(1)!!.contains("JOURNAL · stay 1"))
        assertFalse("the open stay is still in full", all.filterIsInstance<MindMessage.Tool>().first { it.callId == "c3" }.compacted)
    }

    @Test
    fun withinAStayTheWireOnlyGrowsSoTheCachedPrefixHolds() {
        val ws = MissionWorkspace()
        val conversation = MindConversation(listOf(MindMessage.System("sys"), MindMessage.User("goal")))
        ws.attach { conversation.all() }
        var previous: JSONArray? = null
        for (n in 1..6) {
            ws.beginTurn(n, conversation.size(), 0, 0)
            conversation.add(MindMessage.Assistant("t$n", listOf(MindToolCall("c$n", "tap", "{}"))))
            ws.observe(insta.copy(texts = insta.texts + "row $n"))
            conversation.add(MindMessage.Tool("c$n", "tap", "Screen ".repeat(200) + n, "b$n"))
            ws.endTurn(conversation)
            val wire = conversation.toWire(true, ws.liveState())
            val stored = conversation.toWire(true)
            previous?.let { old -> (0 until old.length()).forEach { i -> assertEquals(old.get(i).toString(), stored.get(i).toString()) } }
            previous = stored
            if (ws.liveState() != null) assertEquals("the live state is the last message and never stored", ws.liveState(),
                wire.getJSONObject(wire.length() - 1).getString("content"))
        }
    }

    @Test
    fun aLongStayIsFoldedInBatchesKeepingItsLastTurns() {
        val ws = MissionWorkspace()
        val conversation = MindConversation(listOf(MindMessage.System("sys"), MindMessage.User("goal")))
        for (n in 1..12) {
            ws.beginTurn(n, conversation.size(), 0, 0)
            conversation.add(MindMessage.Assistant("t$n", listOf(MindToolCall("c$n", "scroll", "{}"))))
            ws.observe(insta.copy(texts = listOf("row $n")))
            conversation.add(MindMessage.Tool("c$n", "scroll", "x".repeat(5_000), "b$n"))
            ws.endTurn(conversation)
        }
        val tools = conversation.all().filterIsInstance<MindMessage.Tool>()
        assertTrue(tools.first().compacted)
        assertTrue("the last turns stay in full", tools.takeLast(MissionWorkspace.KEEP_TURNS).none { it.compacted })
        assertTrue(conversation.unfoldedToolChars(0) <= MissionWorkspace.STAY_FOLD_CHARS + 5_000 * MissionWorkspace.KEEP_TURNS)
    }

    @Test
    fun theLiveStateIsAbsentForALightMissionAndHoldsPlanFactsAndWhereOtherwise() {
        val ws = MissionWorkspace()
        ws.beginTurn(1, 2, 60_000, 30 * 60_000)
        ws.observe(insta)
        assertNull("a short single-app mission looks like a classic run", ws.liveState())
        ws.setPlan(listOf(MissionWorkspace.Step("Read the address", "done", "WhatsApp"), MissionWorkspace.Step("Bike time", "doing", "Maps"),
            MissionWorkspace.Step("Reply with the ETA", "todo")))
        ws.collect("ETA", "22 min", "Maps")
        val state = ws.liveState()!!
        assertTrue(state.contains("✓1 Read the address (WhatsApp)"))
        assertTrue(state.contains("→2 Bike time"))
        assertTrue(state.contains("☐3 Reply with the ETA"))
        assertTrue(state.contains("ETA = 22 min (Maps, t1)"))
        assertTrue(state.contains("Where: Instagram"))
        assertTrue(state.contains("the screen is the truth"))
        assertEquals("Step 2 of 3 · Bike time — Tapped Bike", ws.narrate("Tapped Bike"))
    }

    @Test
    fun expectationsAreCheckedConservatively() {
        val chat = screen("com.instagram.android", "Instagram", "lo.06", "lo.06", "Message…")
        val profile = screen("com.instagram.android", "Instagram", "lo.06_official", "lo.06_official", "Follow")
        assertEquals(ExpectCheck.Verdict.HELD, ExpectCheck.check("chat with \"lo.06\" opens", insta, chat, apps).verdict)
        assertEquals(ExpectCheck.Verdict.HELD, ExpectCheck.check("chat with lo.06 opens", insta, chat, apps).verdict)
        assertEquals("lo.06 is not lo.06_official", ExpectCheck.Verdict.MISSED, ExpectCheck.check("chat with lo.06 opens", insta, profile, apps).verdict)
        val missed = ExpectCheck.check("chat with \"Louella ❤️\" opens", insta, profile, apps)
        assertEquals(ExpectCheck.Verdict.MISSED, missed.verdict)
        assertEquals(ExpectCheck.Verdict.MISSED, ExpectCheck.check("WhatsApp opens", insta, chat, apps).verdict)
        assertEquals("a common word like Maps never counts as naming an app", ExpectCheck.Verdict.UNKNOWN, ExpectCheck.check("Maps opens", insta, chat, apps).verdict)
        assertEquals(ExpectCheck.Verdict.UNCHANGED, ExpectCheck.check("the chat opens", insta, insta, apps).verdict)
        assertEquals("fuzzy words are never a miss", ExpectCheck.Verdict.UNKNOWN, ExpectCheck.check("the settings page opens", insta, chat, apps).verdict)
        assertEquals("a permission dialog says nothing yet", ExpectCheck.Verdict.UNKNOWN,
            ExpectCheck.check("Maps opens", insta, screen("com.google.android.permissioncontroller", "Permissions", null, "Allow"), apps).verdict)
        assertEquals(ExpectCheck.Verdict.UNKNOWN, ExpectCheck.check(null, insta, chat, apps).verdict)
        assertEquals(listOf("lo.06", "@mybrand", "wikipedia.org"), ExpectCheck.terms("open lo.06 then @mybrand on wikipedia.org, e.g. 3.5"))
    }

    @Test
    fun surprisesCountAndTwoOnOneStepGiveAdviceNotARule() {
        val ws = MissionWorkspace()
        ws.beginTurn(1, 2, 0, 0)
        ws.observe(insta)
        ws.setPlan(listOf(MissionWorkspace.Step("Open the chat", "doing")))
        ws.beginAction("tap", "chat with \"lo.06\" opens", 1)
        val profile = screen("com.instagram.android", "Instagram", "lo.06_official", "lo.06_official")
        ws.observe(profile)
        assertTrue(ws.screenLines(profile, apps).any { it.startsWith("Check: ✗") })
        assertNull(ws.stepAdvice())
        ws.beginTurn(2, 5, 0, 0)
        ws.beginAction("tap", "chat with \"lo.06\" opens", 1)
        assertTrue(ws.screenLines(profile, apps).any { it.startsWith("Check: ~") })
        assertTrue(ws.stepAdvice()!!.contains("consider another"))
        assertEquals(1, ws.metrics().getJSONObject("checks").getInt("missed"))
        assertEquals(1, ws.metrics().getJSONObject("checks").getInt("unchanged"))
    }

    @Test
    fun whatChangedIsShownInAFewLines() {
        val before = screen("com.android.settings", "Settings", "Wi-Fi", "Networks", states = mapOf("Wi-Fi" to "off"))
        val after = screen("com.android.settings", "Settings", "Wi-Fi", "Networks", "Home_5G", states = mapOf("Wi-Fi" to "on"))
        val lines = ScreenDelta.lines(before, after)
        assertTrue(lines.contains("\"Wi-Fi\" off → on"))
        assertTrue(lines.any { it.startsWith("new: \"Home_5G\"") })
        assertEquals(listOf("nothing on the screen changed"), ScreenDelta.lines(before, before))
        assertTrue("a new app needs no diff", ScreenDelta.lines(before, insta).isEmpty())
    }

    @Test
    fun doneChecksAreLookedForAcrossTheWholeMissionAndNudgeOnlyOnce() {
        val ws = MissionWorkspace()
        ws.beginTurn(1, 2, 0, 0)
        ws.observe(maps)
        ws.setDone(DoneCheck.parse(JSONArray().put(JSONObject().put("kind", "app_shows").put("value", "22 min"))
            .put(JSONObject().put("kind", "sent_to").put("value", "lo.06 on Instagram"))
            .put(JSONObject().put("kind", "other").put("value", "she knows when I arrive"))))
        ws.beginTurn(2, 5, 0, 0)
        ws.observe(insta)
        val note = ws.finishNote("Told Louella I arrive in 22 min")
        assertNotNull("nothing was sent yet", note)
        assertTrue(note!!.contains("sent to lo.06 on Instagram"))
        assertFalse("a screen seen earlier counts", note.contains("22 min\""))
        assertNull("the second finish is always accepted", ws.finishNote("Told Louella"))
        assertEquals(1, ws.metrics().getJSONObject("done").getInt("unverified"))
        val sent = MissionWorkspace()
        sent.setDone(DoneCheck.parse(JSONArray().put(JSONObject().put("kind", "sent_to").put("value", "lo.06 on Instagram"))))
        sent.sent("Instagram", "lo.06")
        assertNull(sent.finishNote("Sent"))
        assertEquals("You asked for lo.06 on Instagram; this is \"lo.06_official\" in Instagram.", sent.approvalNote("lo.06_official", "Instagram"))
        assertNull(sent.approvalNote("lo.06", "Instagram"))
    }

    @Test
    fun theApprovalSaysWhatChangedOnlyWhenTheOwnerNamedARecipient() {
        val open = MissionWorkspace()
        assertNull("a choice the owner left open is never flagged", open.approvalNote("Somebody", "WhatsApp"))
        val named = MissionWorkspace()
        named.setDone(listOf(DoneCheck.Item("sent_to", "Louella on WhatsApp")))
        assertNull(named.approvalNote("Louella ❤️", "WhatsApp"))
        assertEquals("You asked for Louella on WhatsApp; this is \"Lou from work\" in WhatsApp.", named.approvalNote("Lou from work", "WhatsApp"))
        assertTrue(DoneCheck.parse(JSONArray().put(JSONObject().put("value", "x y"))).single().kind == "other")
    }

    @Test
    fun theWorkspaceSurvivesAResume() {
        val ws = MissionWorkspace()
        ws.beginTurn(1, 2, 0, 0)
        ws.observe(insta)
        ws.collect("handle", "lo.06", "Instagram")
        ws.setPlan(listOf(MissionWorkspace.Step("Open chat", "doing", "Instagram", "to reply")))
        ws.setDone(listOf(DoneCheck.Item("sent_to", "lo.06")))
        ws.divert("DM on Instagram", "WhatsApp", "DMs closed")
        ws.beginTurn(2, 5, 0, 0)
        ws.movingOnPurpose("reply")
        ws.observe(maps)
        val back = MissionWorkspace.fromJson(JSONObject(ws.toJson().toString()))!!
        assertEquals(2, back.stayCount)
        assertEquals("Maps", back.currentStay?.app)
        assertEquals(listOf("handle"), back.collected.map { it.key })
        assertEquals("to reply", back.plan.single().why)
        assertEquals(1, back.done.size)
        assertEquals(1, back.diverted.size)
        assertEquals(2, back.turn)
        assertNull(MissionWorkspace.fromJson(JSONObject().put("schema", "other")))
    }
}
