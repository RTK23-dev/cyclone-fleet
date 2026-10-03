package com.cyclone.mobile.manual

import com.cyclone.mobile.manual.dictionary.AbilityStat
import com.cyclone.mobile.manual.dictionary.AppDictionary
import com.cyclone.mobile.manual.dictionary.DictEntry
import com.cyclone.mobile.manual.dictionary.DictionaryJson
import com.cyclone.mobile.manual.dictionary.DoorCard
import com.cyclone.mobile.manual.dictionary.EntryStatus
import com.cyclone.mobile.manual.dictionary.ListNote
import com.cyclone.mobile.manual.dictionary.ManualScreens
import com.cyclone.mobile.manual.dictionary.ScreenCard
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** alpha.64: list order, abilities, the ability index, the checked walk, the describer and the self-quiz. */
class AppManualAlpha64Test {
    private val today = LocalDate.of(2026, 9, 28) // a Monday
    private val canary = "CANARY-7781 lighthouse"

    // ---- list order ----

    @Test
    fun listOrderComesFromRowShapesOnly() {
        assertEquals(ListOrder.NEWEST_FIRST, ListOrder.of(listOf(
            listOf("Sam", "hey", "2m"), listOf("Alex", "ok", "1h"), listOf("Kim", "see you", "Yesterday"), listOf("Lou", "…", "Fri"), listOf("Bo", "…", "12 Aug"),
        ), today))
        assertEquals(ListOrder.NEWEST_FIRST, ListOrder.of(listOf(listOf("A", "14:32"), listOf("B", "09:10"), listOf("C", "Sun")), today))
        assertEquals(ListOrder.A_Z, ListOrder.of(listOf(listOf("Alex"), listOf("Bo"), listOf("bram"), listOf("Kim"), listOf("Lou")), today))
        assertNull("mixed ages: no clear order", ListOrder.of(listOf(listOf("A", "2h"), listOf("B", "5m"), listOf("C", "3d")), today))
        assertNull(ListOrder.of(listOf(listOf("Zed"), listOf("Alex"), listOf("Kim"), listOf("Bo")), today))
        assertEquals(2 * 1_440.0 + 720, ListOrder.age("Sat", today)!!, 0.1)
    }

    @Test
    fun theReaderKeepsTheOrderButNeverTheRows() {
        val lexicon = AppLexicon.of(listOf("t" to "Chats", "s" to "Search chats"))
        val nodes = ArrayList<UiNode>()
        nodes += UiNode("root", null, className = "FrameLayout", left = 0, top = 0, right = 1080, bottom = 2400)
        nodes += UiNode("title", "root", text = "Chats", className = "TextView", left = 40, top = 100, right = 500, bottom = 180)
        nodes += UiNode("search", "root", description = "Search chats", className = "EditText", editable = true, clickable = true, left = 40, top = 200, right = 1040, bottom = 280)
        nodes += UiNode("list", "root", className = "RecyclerView", scrollable = true, left = 0, top = 300, right = 1080, bottom = 2300)
        listOf(canary to "3m", "Sam Jones" to "2h", "Alex" to "Yesterday", "Kim" to "Thu").forEachIndexed { i, (name, age) ->
            val top = 320 + i * 200
            nodes += UiNode("row$i", "list", className = "ViewGroup", clickable = true, left = 0, top = top, right = 1080, bottom = top + 180)
            nodes += UiNode("name$i", "row$i", text = name, className = "TextView", left = 200, top = top + 20, right = 800, bottom = top + 80)
            nodes += UiNode("age$i", "row$i", text = age, className = "TextView", left = 900, top = top + 20, right = 1060, bottom = top + 80)
        }
        val found = StructureReader(lexicon, "Chatty", today = { today }).read(ROOM_CHATS, nodes)
        assertEquals(ListOrder.NEWEST_FIRST, found.list!!.order)
        assertTrue(found.list!!.searchable)
        assertEquals("Search chats", found.list!!.searchLabel)
        val dict = ManualScreens.observed(AppDictionary("com.example"), ROOM_CHATS, found, 1_000, "com.example:MainActivity:" + "a".repeat(28))
        val card = dict.screens.getValue(ROOM_CHATS)
        assertEquals("newest_first", card.list!!.order)
        assertEquals(1, card.pageKeys.size)
        val json = DictionaryJson.write(dict).toString()
        assertFalse(json.contains("CANARY") || json.contains("Sam") || json.contains("Yesterday"))
    }

    // ---- abilities and the index ----

    private fun manual(): AppDictionary {
        val screens = listOf(
            ScreenCard(ROOM_CHAT, title = "Chat", items = listOf("Add photos and files", "Messages", "Settings"), seen = 5, pageKeys = listOf(KEY_CHAT)),
            ScreenCard(ROOM_PANEL, via = "Add photos and files", panelOf = ROOM_CHAT, items = listOf("Camera", "Files", "Connectors"), seen = 2),
            ScreenCard(ROOM_MESSAGES, title = "Messages", category = "Primary", items = listOf("New message"), seen = 3,
                list = ListNote("2 texts · image", "newest_first", emptyList(), true, "Search")),
            ScreenCard(ROOM_SETTINGS, title = "Settings", items = listOf("Dark mode", "Delete account"), seen = 1),
        ).associateBy { it.roomKey }
        val doors = listOf(
            DoorCard(EDGE_PANEL, ROOM_CHAT, ROOM_PANEL, "Add photos and files", "reveal", 10),
            DoorCard(EDGE_MESSAGES, ROOM_CHAT, ROOM_MESSAGES, "Messages", "tab", 10),
            DoorCard(EDGE_SETTINGS, ROOM_CHAT, ROOM_SETTINGS, "Settings", "settings", 10),
        ).associateBy { it.edgeId }
        fun view(name: String, pos: Int) = DictEntry("set:${name.lowercase()}", CoreKind.CONVERSATION, name, ChromeProof.LEXICON, status = EntryStatus.CONFIRMED,
            anchors = listOf(Anchor(AnchorKind.VIEW, ROOM_MESSAGES, "abc123", "Messages", pos)), proven = true)
        val entries = listOf(view("Primary", 0), view("Requests", 1),
            DictEntry("set:messages", CoreKind.CONVERSATION, "Messages", ChromeProof.LEXICON, status = EntryStatus.CONFIRMED,
                anchors = listOf(Anchor(AnchorKind.LIST, ROOM_MESSAGES, "def456", "Messages", searchable = true, searchLabel = "Search", order = "newest_first"))),
        ).associateBy { it.id }
        return AppDictionary("com.example", entries = entries, screens = screens, doors = doors)
    }

    @Test
    fun abilitiesComeFromPlacesDoorsAndSetsWithStableIds() {
        val abilities = Abilities.derive(manual())
        val byName = abilities.associateBy { it.name }
        val connectors = byName.getValue("Connectors (in Add photos and files)")
        assertEquals(AbilityKind.OFFER, connectors.kind)
        assertEquals(listOf("Chat", "Add photos and files", "Connectors"), connectors.path)
        assertEquals("Connectors", connectors.pick)
        val requests = byName.getValue("Requests on Messages")
        assertEquals(AbilityKind.SWITCH, requests.kind)
        assertEquals("Requests", requests.tap)
        assertTrue(requests.navigationOnly)
        assertEquals("asks", byName.getValue("Delete account (on Settings)").effect)
        val find = abilities.single { it.kind == AbilityKind.FIND }
        assertTrue(find.note!!, find.note!!.contains("newest first"))
        // Stable: the same manual gives the same ids; a walk record raises confidence and marks it walked.
        assertEquals(abilities.map { it.id }, Abilities.derive(manual()).map { it.id })
        val walked = Abilities.derive(manual().copy(abilityStats = mapOf(requests.id to AbilityStat(2, 0, 5))))
            .single { it.id == requests.id }
        assertEquals("walked", walked.provenance)
        assertTrue(walked.confidence > requests.confidence)
    }

    @Test
    fun theIndexFindsTheRightAbilityForPlainGoals() {
        val index = AbilityIndex(Abilities.derive(manual()))
        val connector = index.search("Add a connector to this chat")
        assertEquals("Connectors (in Add photos and files)", connector.first().ability.name)
        val requests = index.search("see message requests")
        assertEquals("Requests on Messages", requests.first().ability.name)
        assertTrue("one clear navigation match: Tier 0", AbilityIndex.clear(requests))
        assertFalse("an offer is a choice, never Tier 0", AbilityIndex.clear(connector))
        assertEquals("Open Settings", index.search("open preferences").first().ability.name)
        assertTrue(index.search("order a pizza").all { it.score < AbilityIndex.ANSWER_SCORE })
    }

    // ---- the checked walk ----

    /** A tiny phone: each room shows its words; pressing a door's words moves along it. */
    private class FakePhone(val dict: AppDictionary, var room: String, val detour: Map<String, String> = emptyMap()) : ManualWalkPort {
        val pressed = ArrayList<String>()
        override fun here(): ManualHere {
            val card = dict.screens[room]
            val words = listOfNotNull(card?.title) + card?.items.orEmpty() + (if (room == ROOM_MESSAGES) listOf("Primary", "Requests") else emptyList())
            return ManualHere("com.example", card?.pageKeys?.firstOrNull(), words.toSet())
        }
        override fun press(label: String): ManualPress {
            pressed += label
            detour[label]?.let { room = it; return ManualPress.DONE }
            val door = dict.doors.values.firstOrNull { it.from == room && it.label == label }
            if (door != null) { room = door.to; return ManualPress.DONE }
            val here = here().words
            return if (label in here) ManualPress.DONE else ManualPress.NOT_ON_SCREEN
        }
    }

    @Test
    fun aWalkChecksEveryStepAndLeavesThePickToTheMind() {
        val dict = manual()
        val abilities = Abilities.derive(dict).associateBy { it.name }
        val phone = FakePhone(dict, ROOM_CHAT)
        val switch = ManualNavigator(dict, phone).walk(abilities.getValue("Requests on Messages"))
        assertTrue(switch is ManualWalk.Arrived)
        assertEquals(listOf("Messages", "Requests"), phone.pressed)

        val offerPhone = FakePhone(dict, ROOM_CHAT)
        val offer = ManualNavigator(dict, offerPhone).walk(abilities.getValue("Connectors (in Add photos and files)"))
        assertEquals("Connectors", (offer as ManualWalk.Arrived).pick)
        assertEquals("the pick is never tapped by the walk", listOf("Add photos and files"), offerPhone.pressed)

        // A door that leads somewhere the manual does not know stops the walk at once.
        val lost = FakePhone(dict, ROOM_CHAT, detour = mapOf("Messages" to "screen:detail:9999999999999999"))
        val stopped = ManualNavigator(dict, lost).walk(abilities.getValue("Requests on Messages"))
        assertTrue(stopped is ManualWalk.Stopped)
        assertEquals(1, stopped.moves)

        // Where am I: the page key first, else the place whose title is on screen.
        assertEquals(ROOM_CHAT, ManualNavigator.locate(dict, ManualHere("com.example", KEY_CHAT, emptySet())))
        assertEquals(ROOM_SETTINGS, ManualNavigator.locate(dict, ManualHere("com.example", null, setOf("Settings", "Dark mode"))))
        assertTrue(ManualNavigator(dict, FakePhone(dict, ROOM_CHAT)).walk(abilities.getValue("Open Settings").copy(place = "screen:list:0000000000000000")) is ManualWalk.NoRoute)
    }

    // ---- the describer and the self-quiz ----

    @Test
    fun theDescriberWritesPurposesPhrasingsAndAQuizThatTheManualAnswers() {
        val dict = manual()
        val abilities = Abilities.derive(dict)
        val question = ManualDescriber.question(dict, "Chatty", abilities)!!
        assertFalse(question.user.contains("CANARY"))
        val chat = question.screens.entries.first { it.value == ROOM_CHAT }.key
        val requests = question.abilities.entries.first { e -> abilities.first { it.id == e.value }.name == "Requests on Messages" }.key
        val reply = JSONObject()
            .put("screens", org.json.JSONArray()
                .put(JSONObject().put("s", chat).put("purpose", "Talk with the assistant and attach things."))
                .put(JSONObject().put("s", "s99").put("purpose", "unknown handle"))
                .put(JSONObject().put("s", "s2").put("purpose", "Mail me at sam@example.com")))
            .put("abilities", org.json.JSONArray().put(JSONObject().put("a", requests).put("say", org.json.JSONArray().put("open my message requests").put("call 0612345678"))))
            .put("quiz", org.json.JSONArray().put("see message requests").put("turn on dark mode").put("order a pizza"))
        val answer = ManualDescriber.parse("```json\n$reply\n```", question)!!
        assertEquals(1, answer.purposes.size)
        assertEquals(listOf("open my message requests"), answer.phrasings.values.single())
        val next = ManualDescriber.apply(dict, answer, 9_000)
        assertEquals("Talk with the assistant and attach things.", next.screens.getValue(ROOM_CHAT).purpose)
        val quiz = next.quiz!!
        assertEquals(3, quiz.goals.size)
        assertEquals(2, quiz.answered)
        assertEquals(listOf("order a pizza"), quiz.gaps)
        assertEquals(listOf("order", "pizza"), SelfQuiz.focusWords(quiz))
        // Stored with fixed keys and read back the same.
        val json = DictionaryJson.write(next)
        val back = DictionaryJson.read(JSONObject(json.toString()))
        assertEquals(quiz.answered, back.quiz!!.answered)
        assertEquals(next.phrasings, back.phrasings)
        assertEquals("newest_first", back.entries.getValue("set:messages").anchors.single().order)
        val screens = json.getJSONArray("screens")
        for (i in 0 until screens.length()) assertTrue(screens.getJSONObject(i).keys().asSequence().toSet().minus(DictionaryJson.SCREEN_KEYS).isEmpty())
        assertFalse(json.toString().contains("sam@") || json.toString().contains("0612345678"))
    }

    @Test
    fun theManualReadsAsMarkdownAndExcerpts() {
        val dict = manual()
        val abilities = Abilities.derive(dict)
        val md = ManualRenderer.markdown(dict, "Chatty", "4.2", abilities)
        assertTrue(md, md.contains("## Screens") && md.contains("## Abilities"))
        assertTrue(md, md.contains("**Add photos and files** — a panel over **Chat**: Camera · Files · Connectors"))
        assertTrue(md, md.contains("Categories: **Primary** · **Requests**"))
        val hits = AbilityIndex(abilities).search("see message requests", 3)
        val excerpt = ManualRenderer.excerpt("Chatty", hits.mapIndexed { i, h -> "a${i + 1}" to h }, AbilityIndex.clear(hits))
        assertNotNull(excerpt)
        assertTrue(excerpt!!, excerpt.contains("a1 Requests on Messages → Chat › Messages › Requests") && excerpt.contains("fits clearly"))
    }

    companion object {
        const val ROOM_CHAT = "screen:home:0123456789abcdef"
        const val ROOM_PANEL = "screen:unknown:1111111111111111"
        const val ROOM_MESSAGES = "screen:list:2222222222222222"
        const val ROOM_SETTINGS = "screen:settings:3333333333333333"
        const val ROOM_CHATS = "screen:list:4444444444444444"
        const val EDGE_PANEL = "edge:aaaaaaaaaaaaaaaa"
        const val EDGE_MESSAGES = "edge:bbbbbbbbbbbbbbbb"
        const val EDGE_SETTINGS = "edge:cccccccccccccccc"
        val KEY_CHAT = "com.example:MainActivity:" + "c".repeat(28)
    }
}
