package com.cyclone.mobile.ui.v32.ask

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class AskCopyTest {
    private val zone = ZoneId.of("Europe/Amsterdam")
    private fun ms(text: String) = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun greetingFollowsTheHour() {
        assertEquals("Good morning", AskCopy.greeting(5))
        assertEquals("Good morning", AskCopy.greeting(11))
        assertEquals("Good afternoon", AskCopy.greeting(12))
        assertEquals("Good afternoon", AskCopy.greeting(17))
        assertEquals("Good evening", AskCopy.greeting(18))
        assertEquals("Good evening", AskCopy.greeting(2))
    }

    @Test
    fun runsGroupByCalendarDay() {
        val now = ms("2026-09-28T09:00:00")
        assertEquals("Today", AskCopy.dayGroup(ms("2026-09-28T00:10:00"), now, zone))
        assertEquals("Yesterday", AskCopy.dayGroup(ms("2026-09-27T23:50:00"), now, zone))
        assertEquals("This week", AskCopy.dayGroup(ms("2026-09-23T12:00:00"), now, zone))
        assertEquals("Earlier", AskCopy.dayGroup(ms("2026-09-20T12:00:00"), now, zone))
        // A clock that moved backwards never files a run in the future.
        assertEquals("Today", AskCopy.dayGroup(ms("2026-09-29T08:00:00"), now, zone))
        assertEquals(listOf("Today", "Yesterday", "This week", "Earlier"), AskCopy.DAY_GROUPS)
    }

    @Test
    fun runLinesSayHowItEndedAndWhen() {
        val now = 10_000_000L
        assertEquals("Done · 2h ago", AskCopy.runLine("COMPLETED", now - 2 * 3_600_000L, now))
        assertEquals("Stopped · just now", AskCopy.runLine("CANCELLED", now - 10_000L, now))
        assertEquals("Waiting for you · 5m ago", AskCopy.runLine("WAITING", now - 5 * 60_000L, now))
        assertEquals("Working", AskCopy.runLine("RUNNING", now - 5 * 60_000L, now))
        assertEquals("Ended · 1d ago", AskCopy.runLine("SOMETHING_NEW", now - 26 * 3_600_000L, now))
        assertEquals(AskCopy.Tone.DONE, AskCopy.tone("COMPLETED"))
        assertEquals(AskCopy.Tone.PROBLEM, AskCopy.tone("FAILED"))
        assertEquals(AskCopy.Tone.PROBLEM, AskCopy.tone("GAVE_UP"))
        assertEquals(AskCopy.Tone.WAITING, AskCopy.tone("PAUSED"))
    }

    @Test
    fun suggestionsOnlyFillTheBar() {
        assertEquals(4, AskCopy.SUGGESTIONS.size)
        AskCopy.SUGGESTIONS.forEach {
            assertTrue(it.title.isNotBlank() && it.detail.isNotBlank() && it.seed.isNotBlank())
            assertTrue(it.icon in setOf("calendar", "messages", "search", "routine"))
        }
    }

    @Test
    fun searchMatchesGoalOrSummary() {
        assertTrue(AskCopy.matches("", "Book a table", ""))
        assertTrue(AskCopy.matches("table", "Book a Table", ""))
        assertTrue(AskCopy.matches(" nora ", "Book", "Booked Nora for four"))
        assertFalse(AskCopy.matches("gym", "Book a table", "Booked Nora"))
    }

    @Test
    fun headerAndLogoWords() {
        assertEquals("Choose a model", AskCopy.modelLabel(null))
        assertEquals("Choose a model", AskCopy.modelLabel("  "))
        assertEquals("Claude Fable 5.1", AskCopy.modelLabel("Claude Fable 5.1"))
        assertEquals("Running on this phone", AskCopy.phoneLine(ready = true, needsRepair = false))
        assertEquals("Repair", AskCopy.phoneValue(ready = false, needsRepair = true))
        assertEquals("Set up", AskCopy.phoneValue(ready = false, needsRepair = false))
    }
}
