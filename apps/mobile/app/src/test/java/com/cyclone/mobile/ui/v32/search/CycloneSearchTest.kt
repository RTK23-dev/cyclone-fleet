package com.cyclone.mobile.ui.v32.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CycloneSearchTest {
    private val run = SearchItem(SearchCategory.RUNS, "r1", "Order a pizza on Thuisbezorgd", "Done · Today", recency = 3)
    private val oldRun = SearchItem(SearchCategory.RUNS, "r0", "Order groceries", "Done · Monday", recency = 1)
    private val routine = SearchItem(SearchCategory.ROUTINES, "t1", "Morning briefing", "Every day at 07:30")
    private val app = SearchItem(SearchCategory.APPS, "com.whatsapp", "WhatsApp", packageName = "com.whatsapp")
    private val skill = SearchItem(SearchCategory.SKILLS, "s1", "Open chats in WhatsApp", "12 successful uses", packageName = "com.whatsapp")
    private val all = SettingsIndex.items + listOf(run, oldRun, routine, app, skill)

    @Test fun `a setting is found by its own words and by what people type`() {
        assertEquals("Visual quality", CycloneSearch.search("visual", all).first().hits.first().item.title)
        val battery = CycloneSearch.search("battery", all).first { it.category == SearchCategory.SETTINGS }.hits.map { it.item.title }
        assertTrue(battery.containsAll(listOf("Visual quality", "Permissions")))
        assertEquals("Model & API", CycloneSearch.search("openrouter", all).first().hits.first().item.title)
        assertEquals("Model & API", CycloneSearch.search("api key", all).first().hits.first().item.title)
    }

    @Test fun `results are grouped by kind and the best kind comes first`() {
        val groups = CycloneSearch.search("whatsapp", all)
        assertEquals(SearchCategory.APPS, groups.first().category)
        assertTrue(groups.any { it.category == SearchCategory.SKILLS })
    }

    @Test fun `case, accents and punctuation do not matter`() {
        assertTrue(CycloneSearch.score("WHATSAPP", app) > 0)
        assertTrue(CycloneSearch.score("privacy safety", SettingsIndex.items.first { it.title == "Privacy & safety" }) > 0)
        assertTrue(CycloneSearch.score("cafe", SearchItem(SearchCategory.RUNS, "x", "Book Café Noir")) > 0)
    }

    @Test fun `word starts and several words match`() {
        assertTrue(CycloneSearch.score("vis qual", SettingsIndex.items.first { it.title == "Visual quality" }) >= 70)
        assertTrue(CycloneSearch.score("pizza", run) >= 75)
    }

    @Test fun `ties go to the newer run`() {
        val hits = CycloneSearch.search("order", all).first { it.category == SearchCategory.RUNS }.hits
        assertEquals(listOf("r1", "r0"), hits.map { it.item.target })
    }

    @Test fun `loose letters do not match`() {
        assertEquals(0, CycloneSearch.score("zq", app))
        assertEquals(0, CycloneSearch.score("x", routine))
        assertTrue(CycloneSearch.search("", all).isEmpty())
    }

    @Test fun `each group shows four and counts the rest, a chip shows all of one kind`() {
        val runs = (1..9).map { SearchItem(SearchCategory.RUNS, "r$it", "Send report $it", recency = it.toLong()) }
        val group = CycloneSearch.search("report", runs).single()
        assertEquals(4, group.hits.size)
        assertEquals(5, group.more)
        assertEquals(9, CycloneSearch.search("report", runs, only = SearchCategory.RUNS).single().hits.size)
        assertEquals(mapOf(SearchCategory.RUNS to 9), CycloneSearch.counts("report", runs))
    }

    @Test fun `every settings entry opens a real section`() {
        assertEquals(21, SettingsIndex.items.size)
        assertTrue(SettingsIndex.items.all { it.category == SearchCategory.SETTINGS && it.target.isNotBlank() && it.keywords.isNotEmpty() })
    }
}
