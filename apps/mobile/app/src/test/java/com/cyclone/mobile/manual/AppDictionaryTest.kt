package com.cyclone.mobile.manual

import com.cyclone.mobile.manual.dictionary.AppDictionary
import com.cyclone.mobile.manual.dictionary.Choice
import com.cyclone.mobile.manual.dictionary.DictionaryJson
import com.cyclone.mobile.manual.dictionary.EntryStatus
import com.cyclone.mobile.manual.dictionary.Organizer
import com.cyclone.mobile.manual.dictionary.OrganizerDecision
import com.cyclone.mobile.manual.dictionary.OrganizerJudge
import com.cyclone.mobile.manual.dictionary.OrganizerPrompt
import com.cyclone.mobile.manual.dictionary.OrganizerQuestion
import com.cyclone.mobile.manual.dictionary.PassInfo
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDictionaryTest {
    /** Strings an Instagram-like app ships. Nothing here is a person. */
    private val lexicon = AppLexicon.of(listOf(
        "app_name" to "Instagram",
        "direct_title" to "Messages",
        "direct_primary" to "Primary",
        "direct_general" to "General",
        "direct_requests" to "Requests",
        "search_hint" to "Search",
        "followers_count" to "%1\$s followers",
        "following_count" to "%1\$s following",
        "remove_follower" to "Remove",
        "story_of" to "%1\$s's story",
        "close_friends_title" to "Close friends",
        "close_friends_help" to "Only close friends can see stories you share with this list",
        "today" to "Today",
    ))

    private val canary = "CANARY-7781 lighthouse"
    private val person = "Sam Jones"

    @Test
    fun theLexiconKeepsOnlyTheAppsOwnWords() {
        assertEquals(ChromeProof.LEXICON, lexicon.chrome("Messages")?.proof)
        assertEquals("direct_primary", lexicon.chrome("Primary")?.resKey)
        // A number next to a label is dropped; the label survives with the label's own casing.
        assertEquals("Followers", lexicon.chrome("1,234 Followers")?.text)
        // Names never become chrome, not even through a template that has a name slot.
        assertNull(lexicon.chrome(person))
        assertNull(lexicon.chrome("Sam's story"))
        assertNull(lexicon.chrome(canary))
        // Every word shipped, the whole phrase not: a downloaded menu name, which needs more proof.
        assertEquals(ChromeProof.VOCABULARY, lexicon.chrome("Close friends list")?.proof)
    }

    @Test
    fun coreKindsComeFromGenericHints() {
        assertEquals(CoreKind.PERSON, CoreKind.guess(listOf("Followers", "row_user_container")))
        assertEquals(CoreKind.CONVERSATION, CoreKind.guess(listOf("Messages", "row_thread")))
        assertEquals(CoreKind.OTHER, CoreKind.guess(listOf("Primary")))
    }

    private fun messagesScreen(selected: String = "Primary"): List<UiNode> {
        val nodes = ArrayList<UiNode>()
        nodes += UiNode("root", null, className = "FrameLayout", left = 0, top = 0, right = 1080, bottom = 2400)
        nodes += UiNode("title", "root", text = "Messages", className = "TextView", left = 40, top = 100, right = 500, bottom = 180)
        nodes += UiNode("search", "root", text = "Search", resourceId = "com.example:id/search_edit", className = "EditText", editable = true, clickable = true, left = 40, top = 200, right = 1040, bottom = 280)
        nodes += UiNode("tabs", "root", className = "TabLayout", left = 0, top = 300, right = 1080, bottom = 400)
        listOf("Primary", "General", "Requests").forEachIndexed { i, label ->
            nodes += UiNode("tab$i", "tabs", className = "TabView", clickable = true, selected = label == selected, left = i * 360, top = 300, right = i * 360 + 350, bottom = 400)
            nodes += UiNode("tabText$i", "tab$i", text = label, className = "TextView", left = i * 360 + 20, top = 320, right = i * 360 + 300, bottom = 380)
        }
        nodes += UiNode("list", "root", className = "RecyclerView", resourceId = "com.example:id/inbox_list", scrollable = true, left = 0, top = 420, right = 1080, bottom = 2300)
        nodes += UiNode("header", "list", text = "Today", className = "TextView", left = 0, top = 420, right = 1080, bottom = 480)
        listOf(person, canary, "Alex", "Robin").forEachIndexed { i, name ->
            val top = 500 + i * 200
            nodes += UiNode("row$i", "list", className = "ViewGroup", resourceId = "com.example:id/row_thread", clickable = true, left = 0, top = top, right = 1080, bottom = top + 180)
            nodes += UiNode("rowName$i", "row$i", text = name, className = "TextView", left = 200, top = top + 20, right = 900, bottom = top + 80)
            nodes += UiNode("rowPreview$i", "row$i", text = "See you at 7 $canary", className = "TextView", left = 200, top = top + 90, right = 900, bottom = top + 150)
            nodes += UiNode("rowAvatar$i", "row$i", className = "ImageView", left = 20, top = top + 20, right = 180, bottom = top + 160)
        }
        return nodes
    }

    private fun followersScreen(): List<UiNode> {
        val nodes = ArrayList<UiNode>()
        nodes += UiNode("root", null, className = "FrameLayout", left = 0, top = 0, right = 1080, bottom = 2400)
        nodes += UiNode("title", "root", text = person, className = "TextView", left = 40, top = 100, right = 500, bottom = 180)
        nodes += UiNode("tabs", "root", className = "TabLayout", left = 0, top = 200, right = 1080, bottom = 300)
        listOf("1,234 followers", "321 following").forEachIndexed { i, label ->
            nodes += UiNode("tab$i", "tabs", text = label, className = "TabView", clickable = true, selected = i == 0, left = i * 540, top = 200, right = i * 540 + 530, bottom = 300)
        }
        nodes += UiNode("list", "root", className = "RecyclerView", scrollable = true, left = 0, top = 320, right = 1080, bottom = 2300)
        listOf(person, canary, "Alex").forEachIndexed { i, name ->
            val top = 320 + i * 200
            nodes += UiNode("row$i", "list", className = "ViewGroup", resourceId = "com.example:id/follow_list_user", clickable = true, left = 0, top = top, right = 1080, bottom = top + 180)
            nodes += UiNode("rowName$i", "row$i", text = name, className = "TextView", left = 200, top = top + 20, right = 700, bottom = top + 80)
            nodes += UiNode("rowButton$i", "row$i", text = "Remove", className = "Button", clickable = true, left = 800, top = top + 40, right = 1040, bottom = top + 140)
        }
        return nodes
    }

    private val reader = StructureReader(lexicon, appLabel = "Instagram")

    @Test
    fun theReaderFindsCategoriesAndListsButNeverTheRows() {
        val found = reader.read("screen:list:0123456789abcdef", messagesScreen())
        assertEquals("Messages", found.title?.text)
        val names = found.proposals.map { it.name.text }
        assertEquals(listOf("Messages", "Primary", "General", "Requests"), names)
        val messages = found.proposals.first()
        assertEquals(CoreKind.CONVERSATION, messages.kindGuess)
        assertEquals(AnchorKind.LIST, messages.anchor.kind)
        assertEquals(listOf("Today"), messages.anchor.groups)
        assertTrue(messages.anchor.searchable)
        assertEquals("Search", messages.anchor.searchLabel)
        val primary = found.proposals[1]
        assertEquals("Messages", primary.parentName?.text)
        assertEquals(AnchorKind.VIEW, primary.anchor.kind)
        assertNotNull("the selected category owns the list", primary.secondAnchor)
        assertEquals(listOf("General", "Requests"), primary.anchor.siblings)
        assertNull(found.proposals[2].secondAnchor)
        assertNoContent(found.proposals.toString())
    }

    @Test
    fun followersAreTellApartByTheirPlaceAndMarkerNotByNames() {
        val found = reader.read("screen:list:fedcba98765abcde", followersScreen())
        assertNull("a person's name as a title is never kept", found.title)
        val followers = found.proposals.first { it.name.text == "followers" || it.name.text == "Followers" }
        assertEquals(CoreKind.PERSON, followers.kindGuess)
        assertEquals(listOf("Remove"), followers.markers.map { it.text })
        assertEquals("2 texts · button", followers.secondAnchor?.rowShape)
        assertTrue(found.proposals.any { it.name.text.equals("following", true) })
        assertNoContent(found.proposals.toString())
    }

    private fun assertNoContent(text: String) {
        assertFalse(text, text.contains("CANARY"))
        assertFalse(text, text.contains("lighthouse"))
        assertFalse(text, text.contains("Sam"))
        assertFalse(text, text.contains("Alex"))
        assertFalse(text, text.contains("See you"))
        assertFalse(text, text.contains("1,234") || text.contains("321"))
    }

    private val day = 24L * 60 * 60 * 1000

    private fun pass(dict: AppDictionary, nodes: List<UiNode>, at: Long, room: String = "screen:list:0123456789abcdef"): AppDictionary =
        Organizer.record(dict, reader.read(room, nodes).proposals, PassInfo(at, "402.0", setOf(room)))

    @Test
    fun theGatesConfirmClearSetsParentsFirstAndNeverDuplicate() {
        var dict = AppDictionary("com.example")
        dict = pass(dict, messagesScreen(), 1_000)
        assertEquals(4, dict.entries.size)
        // Seen once: nothing is confirmed yet.
        var result = Organizer.run(dict, null, PassInfo(2_000, "402.0"))
        assertTrue(result.confirmed.isEmpty())
        dict = pass(result.dictionary, messagesScreen(selected = "General"), 3_000)
        assertEquals("a second sighting adds to the same sets", 4, dict.entries.size)
        result = Organizer.run(dict, null, PassInfo(4_000, "402.0"))
        dict = result.dictionary
        val messages = dict.entries.values.single { it.name == "Messages" }
        assertEquals(EntryStatus.CONFIRMED, messages.status)
        assertEquals(CoreKind.CONVERSATION, messages.kind)
        // Primary / General / Requests have no kind of their own until they sit under Messages: parents first.
        val general = dict.entries.values.single { it.name == "General" }
        assertEquals(messages.id, general.parentId)
        assertEquals(EntryStatus.CONFIRMED, general.status)
        assertEquals(CoreKind.CONVERSATION, general.kind)
        assertEquals("Conversation › Messages › General", dict.path(general))
        val json = DictionaryJson.write(dict).toString()
        assertNoContent(json)
        val glossary = Organizer.glossary(dict, "Instagram")
        assertTrue(glossary, glossary.contains("${general.id} = Conversation › Messages › General (category on “Messages”"))
        assertTrue(glossary, glossary.contains("find one: “Search”"))
    }

    @Test
    fun aUserMadeLabelIsNeverProposed() {
        // "Close friends list" and "General Robin" are made of the app's words but are not its strings: a user's folder
        // or chat could look the same, so production never proposes them (not even as a waiting candidate).
        val nodes = messagesScreen().map {
            when (it.text) { "Messages" -> it.copy(text = "Close friends list"); "General" -> it.copy(text = "General Robin"); else -> it }
        }
        val found = reader.read("screen:list:0123456789abcdef", nodes)
        assertNull(found.title)
        assertFalse(found.proposals.any { it.name.proof == ChromeProof.VOCABULARY })
        assertFalse(found.proposals.toString().contains("Close friends list") || found.proposals.toString().contains("Robin"))
    }

    @Test
    fun aDownloadedMenuNameNeedsTwoDaysAndTheQuestion() {
        val strip = messagesScreen().map { if (it.text == "Requests") it.copy(text = "Close friends list") else it }
        val vocab = StructureReader(lexicon, appLabel = "Instagram", allowVocabulary = true)
        fun pass(dict: AppDictionary, nodes: List<UiNode>, at: Long) =
            Organizer.record(dict, vocab.read("screen:list:0123456789abcdef", nodes).proposals, PassInfo(at, "402.0"))
        val nodes = strip
        var dict = pass(AppDictionary("com.example"), nodes, 1_000)
        dict = pass(dict, nodes, 2_000)
        val sameDay = Organizer.run(dict, null, PassInfo(3_000, "402.0"))
        assertTrue(sameDay.questions.none { it.name == "Close friends list" })
        dict = pass(sameDay.dictionary, nodes, 1_000 + day)
        val asked = mutableListOf<OrganizerQuestion>()
        val judge = object : OrganizerJudge {
            override val label = "test model"
            override fun decide(questions: List<OrganizerQuestion>): List<OrganizerDecision> {
                asked += questions
                return questions.map { OrganizerDecision(it.key, Choice.NEW, kind = CoreKind.PERSON) }
            }
        }
        val result = Organizer.run(dict, judge, PassInfo(2 * day, "402.0"))
        val list = result.dictionary.entries.values.single { it.name == "Close friends list" }
        assertTrue(asked.any { it.entryId == list.id })
        assertEquals(EntryStatus.CONFIRMED, list.status)
        assertTrue(result.dictionary.audit.any { it.action == "confirmed" && it.entryId == list.id && it.by == "test model" })
    }

    @Test
    fun answersOutsideTheAllowedChoicesAreIgnored() {
        val q = OrganizerQuestion("q1", "set:x", "X", CoreKind.OTHER, "kind unknown", emptyList(), emptyList(), null,
            listOf(OrganizerQuestion.Nearby("set:messages", "Conversation › Messages")))
        val parsed = OrganizerPrompt.parse("""```json
            {"decisions":[{"key":"q1","choice":"same_as","target":"set:invented"},{"key":"q9","choice":"new","kind":"person"},
            {"key":"q1","choice":"subset_of","target":"set:messages"}]}```""", listOf(q))
        assertEquals(listOf(OrganizerDecision("q1", Choice.SUBSET_OF, targetId = "set:messages")), parsed)
        assertTrue(OrganizerPrompt.parse("""{"decisions":[{"key":"q1","choice":"new"}]}""", listOf(q)).isEmpty())
        assertTrue(OrganizerPrompt.parse("no json", listOf(q)).isEmpty())
        // The prompt carries only app words, kinds and ids.
        val user = OrganizerPrompt.user("Instagram", listOf(q))
        assertTrue(JSONObject(user).getJSONArray("questions").length() == 1)
        // JEV's pick must be one of the offered choices.
        assertEquals("subset_of:set:messages" to 0.9,
            OrganizerPrompt.jevParse("""{"answers":{"decision":{"choice":"subset_of:set:messages","confidence":0.9}}}""", q))
        assertNull(OrganizerPrompt.jevParse("""{"answers":{"decision":"same_as:set:elsewhere"}}""", q))
    }

    @Test
    fun theOwnerCanMergeUndoLockAndMoveWithinTheRules() {
        var dict = pass(AppDictionary("com.example"), messagesScreen(), 1_000)
        dict = pass(dict, messagesScreen(), 2_000)
        dict = Organizer.run(dict, null, PassInfo(3_000, "402.0")).dictionary
        val messages = dict.entries.values.single { it.name == "Messages" }
        val primary = dict.entries.values.single { it.name == "Primary" }
        val general = dict.entries.values.single { it.name == "General" }
        dict = Organizer.edit(dict, Organizer.OwnerEdit.Merge(general.id, primary.id), 4_000)
        assertEquals(primary.id, dict.resolve(general.id)?.id)
        assertTrue("General" in dict.entries.getValue(primary.id).aliases)
        dict = Organizer.edit(dict, Organizer.OwnerEdit.Unmerge(general.id), 5_000)
        assertEquals(general.id, dict.resolve(general.id)?.id)
        dict = Organizer.edit(dict, Organizer.OwnerEdit.Lock(messages.id), 6_000)
        dict = Organizer.edit(dict, Organizer.OwnerEdit.Rename(messages.id, "DMs"), 7_000)
        assertEquals("DMs", dict.entries.getValue(messages.id).shownName)
        assertThrows { Organizer.edit(dict, Organizer.OwnerEdit.Move(messages.id, primary.id), 8_000) }
        assertThrows { Organizer.edit(dict, Organizer.OwnerEdit.Rename(messages.id, "a name with far too many words in it"), 8_000) }
        assertThrows { Organizer.edit(dict, Organizer.OwnerEdit.Merge(messages.id, primary.id), 8_000) }
        // A locked set keeps its name when a candidate is folded into it.
        val question = Organizer.questionFor(dict, dict.entries.getValue(primary.id), "q1", "test")
        assertTrue(question.nearby.any { it.id == messages.id })
    }

    @Test
    fun storedJsonHasOnlyAllowedFieldsAndRoundTrips() {
        var dict = pass(AppDictionary("com.example"), followersScreen(), 1_000, "screen:list:fedcba98765abcde")
        dict = pass(dict, followersScreen(), 2_000, "screen:list:fedcba98765abcde")
        dict = Organizer.run(dict, null, PassInfo(3_000, "402.0")).dictionary
        val json = DictionaryJson.write(dict)
        assertNoContent(json.toString())
        val entries = json.getJSONArray("entries")
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            assertTrue(entry.keys().asSequence().toSet().minus(DictionaryJson.ENTRY_KEYS).isEmpty())
            val anchors = entry.getJSONArray("anchors")
            for (j in 0 until anchors.length()) assertTrue(anchors.getJSONObject(j).keys().asSequence().toSet().minus(DictionaryJson.ANCHOR_KEYS).isEmpty())
        }
        val back = DictionaryJson.read(JSONObject(json.toString()))
        assertEquals(dict.entries.keys, back.entries.keys)
        val followers = back.active().single { it.name.equals("Followers", true) }
        assertEquals(CoreKind.PERSON, followers.kind)
        assertEquals(listOf("Remove"), followers.markers)
        // A captured value smuggled into a stored file is dropped on the way in.
        val tampered = JSONObject(json.toString())
        tampered.getJSONArray("entries").getJSONObject(0).put("aliases", JSONArray(listOf("sam@example.com", "Tabs")))
        assertFalse(DictionaryJson.read(tampered).entries.values.flatMap { it.aliases }.contains("sam@example.com"))
    }

    @Test
    fun healthReportsWhatNeedsALook() {
        var dict = pass(AppDictionary("com.example"), messagesScreen(), 1_000)
        dict = pass(dict, messagesScreen(), 2_000)
        dict = Organizer.run(dict, null, PassInfo(3_000, "402.0")).dictionary
        val health = Organizer.health(dict, 3_000 + 40 * day, "403.0")
        assertTrue(health.notSeenInVersion.isNotEmpty())
        assertTrue(health.orphans.isEmpty())
        // Missing from its own screen on two passes retires a set; it still resolves.
        val messages = dict.entries.values.single { it.name == "Messages" }
        val room = messages.anchors.first().roomKey
        dict = Organizer.run(dict, null, PassInfo(10_000, "402.0", setOf(room))).dictionary
        dict = Organizer.run(dict, null, PassInfo(20_000, "402.0", setOf(room))).dictionary
        assertEquals(EntryStatus.RETIRED, dict.entries.getValue(messages.id).status)
    }

    private fun assertThrows(block: () -> Unit) {
        val failed = runCatching(block).isFailure
        assertTrue("expected a refusal", failed)
    }
}
