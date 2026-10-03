package com.cyclone.mobile.brain.people

import com.cyclone.mobile.agent.nav.*
import com.cyclone.mobile.agent.nav.MultiAppScenarioTest.Companion.page
import com.cyclone.mobile.applearner.ActionRisk
import com.cyclone.mobile.applearner.PageControl
import com.cyclone.mobile.brain.graphv2.AtlasPersona
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PeopleMemoryTest {
    private val directory = Files.createTempDirectory("people-test").toFile()
    private val file = File(directory, "people.json")
    private val memory = PeopleMemory(file)
    private val ledger = TaskLedger(0)
    private val clause = ClauseCompiler.compile("find the DM of Louella on Facebook") { false }.single()
    private val screen = NavigationScreen("chrome:https://facebook.com", "screen:thread:aaaaaaaaaaaaaaaa",
        page("com.android.chrome", "Louella").copy(controls = listOf(PageControl("message", "Message", "Message", "textbox",
            JSONObject().put("editable", true), emptyList(), ActionRisk.SAFE))), "live-1", 1000)

    @After fun clean() { directory.deleteRecursively() }

    @Test fun onlyProvedLiveThreadIsPersistedAndReadByPersonClauses() {
        assertEquals("SEARCH_PERSON", memory.contextFor(clause)!!.getString("capability"))
        assertTrue(memory.observeOpenedThread(clause, screen, ledger))
        val reloaded = PeopleMemory(file)
        assertEquals("Louella", reloaded.find("louella", "chrome:https://www.facebook.com").single().displayName)
        assertEquals("OPEN_DM", reloaded.contextFor(clause)!!.getString("capability"))
        assertTrue(reloaded.find("Louella", "chrome:https://instagram.com").isEmpty())
        assertNull(reloaded.contextFor(clause.copy(capability = NavCapability.OPEN_PLACE)))
    }

    @Test fun dummySearchResultsStaleObservationAndSecretsNeverWrite() {
        assertFalse(memory.observeOpenedThread(clause, screen.copy(persona = AtlasPersona.MAPPING), ledger))
        assertFalse(memory.observeOpenedThread(clause, screen.copy(page = page("com.android.chrome", "Search results", "Louella")), ledger))
        assertFalse(memory.observeOpenedThread(clause, screen.copy(observationId = ""), ledger))
        assertFalse(memory.observeOpenedThread(clause.copy(target = "password=hidden"), screen, ledger))
        assertFalse(file.exists())
    }
}
