package com.cyclone.mobile.market

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MarketplaceTest {
    @get:Rule val folder = TemporaryFolder()
    private val submitted = mutableListOf<String>()
    private var busy = false
    private var human = false
    private var overlay = true
    private lateinit var store: MarketInstalls

    @Before fun seams() {
        store = MarketInstalls(folder.root.resolve("installed.json")) { 1_000L }
        Marketplace.busy = { busy }
        Marketplace.humanHasControl = { human }
        Marketplace.overlayReady = { overlay }
        Marketplace.submit = { submitted += it }
    }

    @After fun reset() = submitted.clear()

    @Test fun everyFirstPartyListingPassesTheRules() {
        MarketCatalog.LISTINGS.forEach(MarketRules::validate)
        assertEquals(MarketCatalog.LISTINGS.size, Marketplace.catalog().size)
        assertEquals(MarketCatalog.LISTINGS.size, MarketCatalog.LISTINGS.map { it.id }.toSet().size)
        assertTrue("the store has a featured shelf", MarketCatalog.LISTINGS.count { it.featured } >= 4)
        assertTrue("sending discloses that it asks first", MarketCatalog.byId("cyclone.send-whatsapp")!!.asksFirst.isNotEmpty())
    }

    @Test fun brokenListingsAreRejected() {
        val good = MarketCatalog.byId("cyclone.focus-timer")!!
        listOf(
            good.copy(id = "Bad Id"),
            good.copy(goal = "Set a timer for {hours} hours"),
            good.copy(goal = "Turn on Do Not Disturb"),
            good.copy(goal = "Log in with my password and set a timer for {minutes} minutes"),
            good.copy(inputs = listOf(MarketInput("minutes", "Your PIN code"))),
            good.copy(apps = listOf("not a package")),
            good.copy(does = emptyList()),
        ).forEach { listing -> assertTrue(listing.toString(), runCatching { MarketRules.validate(listing) }.isFailure) }
    }

    @Test fun goalsAreFilledFromSavedValuesDefaultsAndChecks() {
        val timer = MarketCatalog.byId("cyclone.focus-timer")!!
        assertEquals("Set a timer for 25 minutes and turn on Do Not Disturb.", MarketRules.fill(timer, emptyMap()))
        assertEquals("Set a timer for 10 minutes and turn on Do Not Disturb.", MarketRules.fill(timer, mapOf("minutes" to " 10 ")))
        assertTrue(runCatching { MarketRules.fill(timer, mapOf("minutes" to "ten")) }.isFailure)
        val directions = MarketCatalog.byId("cyclone.directions")!!
        assertTrue(runCatching { MarketRules.fill(directions, mapOf("mode" to "rocket", "place" to "Utrecht")) }.isFailure)
        assertTrue("required inputs must be given", runCatching { MarketRules.fill(directions, mapOf("mode" to "walking")) }.isFailure)
        val send = MarketCatalog.byId("cyclone.send-whatsapp")!!
        assertTrue("secrets never go into a recipe",
            runCatching { MarketRules.fill(send, mapOf("person" to "Sam", "message" to "my password is hunter2")) }.isFailure)
        assertEquals(mapOf("person" to "Sam"), MarketRules.cleanInputs(send, mapOf("person" to "Sam", "extra" to "x")))
    }

    @Test fun suggestionsExplainThemselvesAndSkipWhatIsAdded() {
        val apps = mapOf("com.whatsapp" to "WhatsApp", "com.google.android.deskclock" to "Clock")
        val suggestions = MarketRules.suggestions(MarketCatalog.LISTINGS, apps, setOf("cyclone.focus-timer"))
        assertTrue(suggestions.any { it.first.id == "cyclone.whatsapp-catch-up" && it.second == "Because you use WhatsApp" })
        assertTrue(suggestions.none { it.first.id == "cyclone.focus-timer" })
        assertTrue(suggestions.none { it.first.id == "cyclone.inbox-brief" })
    }

    @Test fun addRunAndRemoveOnlyWhenThePhoneIsFree() {
        assertEquals("NOT_ADDED", Marketplace.run(store, "cyclone.focus-timer", emptyMap())!!.code)
        Marketplace.add(store, "cyclone.focus-timer", mapOf("minutes" to "15", "shell" to "id"), "phone")
        assertEquals(mapOf("minutes" to "15"), store.get("cyclone.focus-timer")!!.inputs)

        busy = true
        assertEquals("ASK_BUSY", Marketplace.run(store, "cyclone.focus-timer", emptyMap())!!.code)
        busy = false; human = true
        assertEquals("HUMAN_HAS_CONTROL", Marketplace.run(store, "cyclone.focus-timer", emptyMap())!!.code)
        human = false; overlay = false
        assertEquals("OVERLAY_UNAVAILABLE", Marketplace.run(store, "cyclone.focus-timer", emptyMap())!!.code)
        overlay = true
        assertTrue("nothing ran while the phone was not free", submitted.isEmpty())

        assertNull(Marketplace.run(store, "cyclone.focus-timer", emptyMap()))
        assertEquals(listOf("Set a timer for 15 minutes and turn on Do Not Disturb."), submitted)
        assertEquals(1, store.get("cyclone.focus-timer")!!.runs)
        assertEquals("INVALID_REQUEST", Marketplace.run(store, "cyclone.focus-timer", mapOf("minutes" to "abc"))!!.code)

        assertTrue(store.remove("cyclone.focus-timer"))
        assertFalse(store.remove("cyclone.focus-timer"))
        assertTrue(runCatching { Marketplace.add(store, "cyclone.unknown", emptyMap(), "phone") }.isFailure)
    }

    @Test fun theInstalledFileSurvivesARestartAndBadFilesAreEmpty() {
        Marketplace.add(store, "cyclone.inbox-brief", mapOf("count" to "3"), "pc")
        val again = MarketInstalls(folder.root.resolve("installed.json"))
        assertEquals("pc", again.get("cyclone.inbox-brief")!!.source)
        folder.root.resolve("broken.json").writeText("{nope")
        assertTrue(MarketInstalls(folder.root.resolve("broken.json")).list().isEmpty())
    }
}
