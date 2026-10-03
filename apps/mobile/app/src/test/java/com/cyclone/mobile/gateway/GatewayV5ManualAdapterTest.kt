package com.cyclone.mobile.gateway

import com.cyclone.mobile.manual.Anchor
import com.cyclone.mobile.manual.AnchorKind
import com.cyclone.mobile.manual.ChromeProof
import com.cyclone.mobile.manual.CoreKind
import com.cyclone.mobile.manual.dictionary.AppDictionary
import com.cyclone.mobile.manual.dictionary.DictEntry
import com.cyclone.mobile.manual.dictionary.EntryStatus
import com.cyclone.mobile.manual.dictionary.Organizer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GatewayV5ManualAdapterTest {
    private var dict = AppDictionary("com.example")

    @Before
    fun setUp() {
        val anchor = Anchor(AnchorKind.LIST, "screen:list:0123456789abcdef", "abcdef12", screenTitle = "Messages", searchable = true, searchLabel = "Search")
        val messages = DictEntry("set:messages", CoreKind.CONVERSATION, "Messages", ChromeProof.LEXICON, status = EntryStatus.CONFIRMED, anchors = listOf(anchor), observations = 2)
        val primary = DictEntry("set:primary", CoreKind.CONVERSATION, "Primary", ChromeProof.LEXICON, parentId = "set:messages", status = EntryStatus.CONFIRMED,
            anchors = listOf(anchor.copy(kind = AnchorKind.VIEW, position = 0, siblings = listOf("General"))), observations = 2)
        val general = DictEntry("set:general", CoreKind.CONVERSATION, "General", ChromeProof.LEXICON, parentId = "set:messages", anchors = listOf(anchor.copy(kind = AnchorKind.VIEW, position = 1)), observations = 1)
        dict = AppDictionary("com.example", mapOf(messages.id to messages, primary.id to primary, general.id to general))
        GatewayV5ManualAdapter.load = { _ -> dict }
        GatewayV5ManualAdapter.editor = { _, edit -> Organizer.edit(dict, edit, 1_000).also { dict = it } }
        GatewayV5ManualAdapter.label = { _ -> "Example" }
        GatewayV5ManualAdapter.version = { _ -> "1.0" }
        GatewayV5ManualAdapter.models = { Triple("anthropic/claude-fable-5.1", "Claude Fable 5.1", true) to listOf(Triple("anthropic/claude-fable-5.1", "Claude Fable 5.1", true), Triple("z-ai/glm-5.3-flash", "GLM 5.3 Flash", false)) }
    }

    @After
    fun tearDown() {
        GatewayV5ManualAdapter.load = { _ -> AppDictionary("com.example") }
    }

    private fun call(op: String, args: String = "{}") = GatewayV5ManualAdapter.dispatch(op, JSONObject(args))

    @Test
    fun getShowsTheTreeGatesGlossaryAndHealth() {
        val out = call("dictionary.get", """{"placeId":"package:com.example"}""")
        val entries = out.getJSONArray("entries")
        assertEquals(3, entries.length())
        val general = (0 until entries.length()).map { entries.getJSONObject(it) }.single { it.getString("id") == "set:general" }
        assertEquals("candidate", general.getString("status"))
        assertEquals("Conversation › Messages › General", general.getString("path"))
        assertTrue(general.getJSONArray("failedGates").toString().contains("seen_twice"))
        assertTrue(out.getString("glossary").contains("set:primary = Conversation › Messages › Primary"))
        assertEquals("No decisions yet.", out.getJSONObject("jev").getString("summary"))
    }

    @Test
    fun theOwnerEditsThroughTheOrganizerRules() {
        call("dictionary.edit", """{"placeId":"package:com.example","action":"lock","id":"set:messages"}""")
        assertEquals(EntryStatus.LOCKED, dict.entries.getValue("set:messages").status)
        val refused = runCatching { call("dictionary.edit", """{"placeId":"package:com.example","action":"merge","id":"set:messages","into":"set:primary"}""") }
        assertTrue(refused.exceptionOrNull() is GatewayProtocolException)
        val bad = runCatching { call("dictionary.edit", """{"placeId":"package:com.example","action":"drop_table","id":"set:messages"}""") }
        assertTrue(bad.exceptionOrNull() is GatewayProtocolException)
        val extra = runCatching { call("dictionary.get", """{"placeId":"package:com.example","members":true}""") }
        assertTrue(extra.exceptionOrNull() is GatewayProtocolException)
    }

    @Test
    fun modelsListOffersThePhonesModelsWithoutKeys() {
        val out = call("models.list")
        assertEquals("anthropic/claude-fable-5.1", out.getJSONObject("active").getString("id"))
        assertEquals(2, out.getJSONArray("models").length())
        assertFalse(out.toString().contains("sk-or"))
    }

    @Test
    fun screensDoorsAndTheReviewQueueAreServedAndAnsweredByTheOwner() {
        val room = "screen:list:0123456789abcdef"
        val panel = "screen:unknown:fedcba98765abcde"
        dict = dict.copy(
            screens = mapOf(room to com.cyclone.mobile.manual.dictionary.ScreenCard(room, title = "Messages", category = "Primary", seen = 3),
                panel to com.cyclone.mobile.manual.dictionary.ScreenCard(panel, via = "Add photos and files", panelOf = room, items = listOf("Camera", "Files"), seen = 1)),
            doors = mapOf("edge:" + "a".repeat(64) to com.cyclone.mobile.manual.dictionary.DoorCard("edge:" + "a".repeat(64), room, panel, "Add photos and files", "reveal")),
        )
        val proposal = com.cyclone.mobile.manual.SetProposal(com.cyclone.mobile.manual.ChromeWord("Close friends", ChromeProof.VOCABULARY), CoreKind.PERSON,
            Anchor(AnchorKind.VIEW, room, "abcdef12", screenTitle = "Messages", position = 3, siblings = listOf("Primary")), proven = true)
        val item = com.cyclone.mobile.manual.ReviewQueue.Item("rv:0123456789abcdef", "0123456789abcdef01234567", proposal, 5)
        var answered: Pair<String, Boolean>? = null
        GatewayV5ManualAdapter.review = { _ -> if (answered == null) listOf(item) else emptyList() }
        GatewayV5ManualAdapter.answer = { _, id, appWord -> answered = id to appWord; dict }
        val out = call("dictionary.get", """{"placeId":"package:com.example"}""")
        val screens = out.getJSONArray("screens")
        val messages = (0 until screens.length()).map { screens.getJSONObject(it) }.single { it.getString("roomKey") == room }
        assertEquals("Messages", messages.getString("name"))
        assertTrue(messages.getJSONArray("sets").toString().contains("set:messages"))
        val panelJson = (0 until screens.length()).map { screens.getJSONObject(it) }.single { it.getString("roomKey") == panel }
        assertEquals(room, panelJson.getString("panelOf"))
        assertEquals("reveal", out.getJSONArray("doors").getJSONObject(0).getString("kind"))
        assertEquals("Close friends", out.getJSONArray("review").getJSONObject(0).getString("name"))
        call("dictionary.edit", """{"placeId":"package:com.example","action":"app_word","id":"rv:0123456789abcdef"}""")
        assertEquals("rv:0123456789abcdef" to true, answered)
        val bad = runCatching { call("dictionary.edit", """{"placeId":"package:com.example","action":"mine","id":"set:messages"}""") }
        assertTrue(bad.exceptionOrNull() is GatewayProtocolException)
    }

    @Test
    fun manualGetServesAbilitiesHitsQuizScoresAndMarkdown() {
        val room = "screen:list:0123456789abcdef"
        val panel = "screen:unknown:fedcba98765abcde"
        dict = dict.copy(
            screens = mapOf(
                room to com.cyclone.mobile.manual.dictionary.ScreenCard(room, title = "Messages", category = "Primary", seen = 3, purpose = "Your direct messages.",
                    list = com.cyclone.mobile.manual.dictionary.ListNote("2 texts", "newest_first", emptyList(), true, "Search")),
                panel to com.cyclone.mobile.manual.dictionary.ScreenCard(panel, via = "Add photos and files", panelOf = room, items = listOf("Camera", "Files"), seen = 1)),
            doors = mapOf("edge:" + "a".repeat(64) to com.cyclone.mobile.manual.dictionary.DoorCard("edge:" + "a".repeat(64), room, panel, "Add photos and files", "reveal")),
            quiz = com.cyclone.mobile.manual.dictionary.QuizResult(9, listOf(
                com.cyclone.mobile.manual.dictionary.QuizGoal("see the primary chats", null, 0.2),
                com.cyclone.mobile.manual.dictionary.QuizGoal("attach a file", "ab:0123456789ab", 0.9))),
        )
        val out = call("manual.get", """{"placeId":"package:com.example","query":"attach a file from the camera"}""")
        val abilities = (0 until out.getJSONArray("abilities").length()).map { out.getJSONArray("abilities").getJSONObject(it) }
        val camera = abilities.single { it.getString("name") == "Camera (in Add photos and files)" }
        assertEquals("offer", camera.getString("kind"))
        assertEquals("Camera", camera.getString("pick"))
        assertEquals(camera.getString("id"), out.getJSONArray("hits").getJSONObject(0).getString("id"))
        assertFalse("an offer is never a Tier 0 walk", out.getBoolean("clear"))
        assertEquals(1, out.getJSONObject("quiz").getInt("answered"))
        val scores = out.getJSONObject("scores")
        // A named place, a panel with offers and an ordered list, but the one confirmed category is not proven yet.
        assertEquals(0.75, scores.getDouble("map"), 0.01)
        assertEquals(0.5, scores.getDouble("quiz"), 0.01)
        assertTrue(out.getString("markdown"), out.getString("markdown").contains("- **Messages** — Your direct messages."))
        val dictionary = call("dictionary.get", """{"placeId":"package:com.example"}""")
        assertTrue(dictionary.toString(), dictionary.toString().contains("\"order\":\"newest_first\""))
        val extra = runCatching { call("manual.get", """{"placeId":"package:com.example","members":true}""") }
        assertTrue(extra.exceptionOrNull() is GatewayProtocolException)
    }
}
