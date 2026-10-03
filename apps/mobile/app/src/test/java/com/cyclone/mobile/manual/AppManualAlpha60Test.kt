package com.cyclone.mobile.manual

import com.cyclone.mobile.manual.dictionary.AppDictionary
import com.cyclone.mobile.manual.dictionary.DictionaryJson
import com.cyclone.mobile.manual.dictionary.EntryStatus
import com.cyclone.mobile.manual.dictionary.ManualScreens
import com.cyclone.mobile.manual.dictionary.Organizer
import com.cyclone.mobile.manual.dictionary.PassInfo
import com.cyclone.mobile.mapping.crawl.MapperDoorRisk
import com.cyclone.mobile.mapping.crawl.MappingDanger
import com.cyclone.mobile.mapping.crawl.MappingDoorKind
import com.cyclone.mobile.mapping.crawl.MappingIdentity
import com.cyclone.mobile.mapping.crawl.MappingStructuralProjection
import com.cyclone.mobile.mapping.crawl.RawMappingElement
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** alpha.60: reveal doors, one-pass category proof, screen and door names, and the owner's "app word or yours?". */
class AppManualAlpha60Test {
    private val lexicon = AppLexicon.of(listOf(
        "app_name" to "Instagram",
        "direct_title" to "Messages",
        "direct_primary" to "Primary",
        "direct_general" to "General",
        "direct_requests" to "Requests",
        "search_hint" to "Search",
        "settings" to "Settings",
        "attach" to "Add photos and files",
        "camera" to "Camera",
        "files" to "Files",
        "connectors" to "Connectors",
        "help" to "Close friends are people you choose to share with",
    ))
    private val canary = "CANARY-7781 lighthouse"

    // ---- reveal doors ----

    private fun door(id: String, label: String, className: String = "ImageButton", role: String = "imagebutton", iconOnly: Boolean = true, resourceId: String = "") =
        RawMappingElement(elementId = id, label = label, semanticName = label.lowercase(), role = role, resourceId = resourceId,
            className = className, clickable = true, iconOnly = iconOnly)

    private fun kinds(vararg elements: RawMappingElement): Map<String, MappingDoorKind> =
        MappingStructuralProjection.project("obs", "s", 0, "raw", elements.toList()).doors.associate { it.elementId to it.kind }

    @Test
    fun iconButtonsThatOpenPanelsAreRevealDoors() {
        val k = kinds(
            door("plus", "Add photos and files"),
            door("more", "More", resourceId = "com.example:id/overflow_button"),
            door("fab", "", className = "com.google.android.material.floatingactionbutton.FloatingActionButton", role = "button", iconOnly = false),
            door("cart", "Add to cart", className = "Button", role = "button", iconOnly = false),
            door("row", canary, className = "ViewGroup", role = "", iconOnly = false),
        )
        assertEquals(MappingDoorKind.REVEAL, k["plus"])
        assertEquals(MappingDoorKind.REVEAL, k["more"])
        assertEquals(MappingDoorKind.REVEAL, k["fab"])
        assertEquals("a text button is never a revealer", MappingDoorKind.CONTENT_ROW, k["cart"])
        assertTrue(k["row"] != MappingDoorKind.REVEAL)
    }

    @Test
    fun whatARevealOffersIsStillJudgedBeforeAnyTap() {
        fun danger(label: String) = MapperDoorRisk.classify(MapperDoorRisk.Facts(listOf(label), "button"), MappingIdentity.OWN)
        assertEquals(MappingDanger.STATE_CHANGE, danger("Add to cart"))
        assertEquals(MappingDanger.STATE_CHANGE, danger("Buy now"))
        assertEquals(MappingDanger.STATE_CHANGE, danger("Start call"))
        assertNull(danger("Add photos and files"))
    }

    // ---- one-pass proof and the review queue ----

    private fun strip(selected: String, labels: List<String>): List<UiNode> {
        val nodes = ArrayList<UiNode>()
        nodes += UiNode("root", null, className = "FrameLayout", left = 0, top = 0, right = 1080, bottom = 2400)
        nodes += UiNode("title", "root", text = "Messages", className = "TextView", left = 40, top = 100, right = 500, bottom = 180)
        nodes += UiNode("plus", "root", description = "Add photos and files", className = "ImageButton", clickable = true, left = 900, top = 100, right = 1040, bottom = 180)
        nodes += UiNode("tabs", "root", className = "TabLayout", left = 0, top = 300, right = 1080, bottom = 400)
        labels.forEachIndexed { i, label ->
            nodes += UiNode("tab$i", "tabs", text = label, className = "TabView", clickable = true, selected = label == selected, left = i * 270, top = 300, right = i * 270 + 260, bottom = 400)
        }
        nodes += UiNode("list", "root", className = "RecyclerView", scrollable = true, left = 0, top = 420, right = 1080, bottom = 2300)
        listOf(canary, "Sam Jones", "Alex").forEachIndexed { i, name ->
            val top = 500 + i * 200
            nodes += UiNode("row$i", "list", className = "ViewGroup", resourceId = "com.example:id/row_thread", clickable = true, left = 0, top = top, right = 1080, bottom = top + 180)
            nodes += UiNode("rowName$i", "row$i", text = name, className = "TextView", left = 200, top = top + 20, right = 900, bottom = top + 80)
        }
        return nodes
    }

    private val tabs = listOf("Primary", "General", "Requests", "Close friends")
    private val reader = StructureReader(lexicon, "Instagram", allowVocabulary = true)
    private val room = "screen:list:0123456789abcdef"

    @Test
    fun switchingCategoriesProvesThemInOnePass() {
        val memory = PassMemory()
        var dict = AppDictionary("com.example")
        for ((i, selected) in listOf("Primary", "General").withIndex()) {
            val split = memory.split(reader.read(room, strip(selected, tabs)))
            dict = Organizer.record(dict, split.appStrings, PassInfo(1_000L + i, "402.0"))
        }
        val primary = dict.entries.values.single { it.name == "Primary" }
        val general = dict.entries.values.single { it.name == "General" }
        val requests = dict.entries.values.single { it.name == "Requests" }
        assertTrue(primary.proven && general.proven)
        assertFalse("never seen selected: not proven", requests.proven)
        // One observation each is not "seen twice", but a proven category passes that gate.
        val run = Organizer.run(dict, null, PassInfo(5_000, "402.0")).dictionary
        assertEquals(EntryStatus.CONFIRMED, run.entries.getValue(primary.id).status)
        assertEquals(EntryStatus.CONFIRMED, run.entries.getValue(general.id).status)
        assertFalse(DictionaryJson.write(run).toString().contains("Close friends"))
    }

    @Test
    fun aDownloadedNameWaitsInMemoryForTheOwnerAndNeverReachesTheDictionaryUntilThen() {
        val memory = PassMemory()
        val queue = ReviewQueue()
        var dict = AppDictionary("com.example")
        for (selected in listOf("Close friends", "Primary")) {
            val split = memory.split(reader.read(room, strip(selected, tabs)))
            dict = Organizer.record(dict, split.appStrings, PassInfo(1_000, "402.0"))
            queue.offer(dict, split.downloaded, 1_000)
        }
        assertFalse(DictionaryJson.write(dict).toString().contains("Close friends"))
        val item = queue.list().single()
        assertEquals("Close friends", item.proposal.name.text)
        assertTrue(item.id.matches(Regex("^rv:[0-9a-f]{16}$")))
        // "App word": admitted and confirmed by the owner.
        val admitted = Organizer.ownerAdmit(dict, queue.take(item.id)!!.proposal, 2_000)
        val set = admitted.entries.values.single { it.name == "Close friends" }
        assertEquals(EntryStatus.CONFIRMED, set.status)
        assertEquals(ChromeProof.VOCABULARY, set.nameProof)
        assertTrue(admitted.audit.any { it.action == "app_word" && it.by == "owner" })
        // "Mine": only a hash is kept, and it is never offered again.
        val declined = Organizer.decline(dict, item.hash, 3_000)
        assertFalse(DictionaryJson.write(declined).toString().contains("Close friends"))
        val again = ReviewQueue()
        val memory2 = PassMemory()
        for (selected in listOf("Close friends", "Primary")) again.offer(declined, memory2.split(reader.read(room, strip(selected, tabs))).downloaded, 4_000)
        assertTrue(again.list().isEmpty())
    }

    // ---- screen and door names ----

    @Test
    fun placesAreNamedInTheAppsWordsAndPanelsHangOffTheirScreen() {
        var dict = AppDictionary("com.example")
        val found = reader.read(room, strip("Primary", tabs))
        dict = ManualScreens.observed(dict, room, found, 1_000)
        val card = dict.screens.getValue(room)
        assertEquals("Messages", card.name)
        assertEquals("Primary", card.category)
        assertTrue("Add photos and files" in card.items)
        val panel = "screen:unknown:fedcba98765abcde"
        dict = ManualScreens.verified(dict, room, panel, "edge:" + "a".repeat(64), "Add photos and files", "reveal", 2_000)
        val panelCard = dict.screens.getValue(panel)
        assertEquals(room, panelCard.panelOf)
        assertEquals("Add photos and files", panelCard.name)
        // A content door (a person) is never a name: the runtime passes only app strings, and privacy drops the rest.
        val detail = "screen:detail:1111111111111111"
        dict = ManualScreens.verified(dict, room, detail, "edge:" + "b".repeat(64), "sam@example.com", "structural_sample", 3_000)
        assertNull(dict.screens.getValue(detail).name)
        val json = DictionaryJson.write(dict)
        assertFalse(json.toString().contains("CANARY") || json.toString().contains("Sam") || json.toString().contains("sam@"))
        val screens = json.getJSONArray("screens")
        for (i in 0 until screens.length()) assertTrue(screens.getJSONObject(i).keys().asSequence().toSet().minus(DictionaryJson.SCREEN_KEYS).isEmpty())
        val back = DictionaryJson.read(JSONObject(json.toString()))
        assertEquals(room, back.screens.getValue(panel).panelOf)
        assertEquals("Add photos and files", back.doors.getValue("edge:" + "a".repeat(64)).label)
        assertNotNull(back.doors["edge:" + "b".repeat(64)])
        val glossary = Organizer.glossary(back, "Instagram")
        assertTrue(glossary, glossary.contains("“Add photos and files” opens a panel over “Messages”"))
        assertTrue(glossary, glossary.contains("“Messages” (category “Primary” selected)"))
    }
}
