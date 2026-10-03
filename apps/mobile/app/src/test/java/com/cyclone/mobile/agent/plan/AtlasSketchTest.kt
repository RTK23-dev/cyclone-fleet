package com.cyclone.mobile.agent.plan

import com.cyclone.mobile.brain.graphv2.AtlasStore
import com.cyclone.mobile.mapping.crawl.MappingDanger
import com.cyclone.mobile.mapping.crawl.MappingDoorKind
import com.cyclone.mobile.mapping.crawl.VerifiedStructure
import com.cyclone.mobile.mapping.run.AtlasStoreMappingPort
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AtlasSketchTest {
    private val dir: File = Files.createTempDirectory("atlas-sketch").toFile()
    private val store = AtlasStore(File(dir, "atlas.json"))

    private val home = "screen:unknown:aaaaaaaaaaaaaaaa"
    private val menu = "screen:menu:bbbbbbbbbbbbbbbb"
    private val settings = "screen:settings:cccccccccccccccc"

    @After
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun mapGmail() {
        val port = AtlasStoreMappingPort(store, GMAIL, "Gmail", clock = { 1_000L })
        port.recordVerified(GMAIL, "mapping", VerifiedStructure(home, menu, "door:menu:1111111111111111", MappingDoorKind.MENU, "fp-home", "fp-menu"))
        port.recordVerified(GMAIL, "mapping", VerifiedStructure(menu, settings, "door:settings:2222222222222222", MappingDoorKind.SETTINGS, "fp-menu", "fp-settings"))
        port.markDanger(GMAIL, "mapping", settings, "door:menu:3333333333333333", MappingDanger.DELETE)
    }

    @Test
    fun namedPlaceGetsRoomsDoorsYouAreHereAndARoute() {
        mapGmail()

        val sketch = AtlasSketch.build(store, "open gmail and find the settings", setOf(home))

        assertNotNull(sketch)
        val place = sketch!!.json.getJSONArray("places").getJSONObject(0)
        assertEquals("Gmail", place.getString("place"))
        assertEquals("mapping-pass", place.getString("source"))
        assertEquals(3, place.getInt("roomsKnown"))
        val rooms = place.getJSONArray("rooms")
        assertEquals(3, rooms.length())
        val here = place.getString("youAreHere")
        val route = place.getJSONArray("suggestedRoute")
        assertEquals(3, route.length())
        assertEquals(here, route.getString(0))
        val destination = (0 until rooms.length()).map { rooms.getJSONObject(it) }
            .first { it.getString("id") == route.getString(2) }
        assertEquals("Settings", destination.getString("room"))
        assertEquals("delete-account", destination.getString("danger"))
        assertTrue(sketch.json.getString("rule").contains("Look at the live screen every step"))
    }

    @Test
    fun sketchCarriesNoIdsSelectorsOrValues() {
        mapGmail()
        val text = AtlasSketch.build(store, "open gmail and find the settings", setOf(home))!!.json.toString()
        assertFalse(text.contains("sha256"))
        assertFalse(text.contains("screen:"))
        assertFalse(text.contains("door:"))
        assertFalse(text.contains("@"))
        assertFalse(text.contains("fp-"))
    }

    @Test
    fun unknownPlacesGiveNoSketch() {
        assertNull(AtlasSketch.build(store, "open gmail and find the settings"))
        mapGmail()
        assertNull(AtlasSketch.build(store, "turn on the flashlight"))
    }

    @Test
    fun youAreHereIsOmittedWhenTheScreenIsNotAKnownRoom() {
        mapGmail()
        val place = AtlasSketch.build(store, "open gmail and find the settings", setOf("screen:list:dddddddddddddddd"))!!
            .json.getJSONArray("places").getJSONObject(0)
        assertTrue(place.isNull("youAreHere"))
    }

    @Test
    fun stageLineNamesTheMapUsed() {
        mapGmail()
        val sketch = AtlasSketch.build(store, "open gmail and find the settings")!!
        assertEquals("Using the Gmail map · 3 rooms known", AtlasSketch.stageLine(sketch.summaries))
    }

    @Test
    fun theSketchCannotExecuteAnything() {
        val source = sequenceOf(
            File("src/main/java/com/cyclone/mobile/agent/plan/AtlasSketch.kt"),
            File("apps/mobile/app/src/main/java/com/cyclone/mobile/agent/plan/AtlasSketch.kt"),
        ).first { it.isFile }.readText()
        assertFalse(source.contains("PhoneToolExecutor"))
        assertFalse(source.contains("PhoneToolRequest"))
        assertFalse(source.contains("selectorKey"))
    }

    private companion object {
        const val GMAIL = "package:com.google.android.gm"
    }
}
