package com.cyclone.mobile.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Plan 29: the pure part of the no-screen tools. */
class DirectPlanTest {
    private val now = LocalDateTime.of(2026, 9, 27, 10, 0)

    private fun ok(checked: DirectPlan.Checked<DirectPlan.NewEvent>) = (checked as DirectPlan.Checked.Ok).value
    private fun refused(checked: DirectPlan.Checked<*>) = (checked as DirectPlan.Checked.Refused).reason

    @Test fun timesTheModelWritesAreRead() {
        assertEquals(LocalDateTime.of(2026, 10, 3, 19, 0), DirectPlan.parse("2026-10-03T19:00")!!.start)
        assertEquals(LocalDateTime.of(2026, 10, 3, 19, 0), DirectPlan.parse("2026-10-03 19:00")!!.start)
        assertEquals(LocalDateTime.of(2026, 10, 3, 19, 0, 30), DirectPlan.parse("2026-10-03T19:00:30Z")!!.start)
        assertTrue(DirectPlan.parse("2026-10-03")!!.dateOnly)
        assertNull(DirectPlan.parse("friday at seven"))
        assertNull(DirectPlan.parse(""))
    }

    @Test fun anEventGetsAnEndAndIsChecked() {
        val dinner = ok(DirectPlan.event("Dinner with Sam", "2026-10-03T19:00", null, null, false, " Luigi's ", "", 30, now))
        assertEquals(LocalDateTime.of(2026, 10, 3, 20, 0), dinner.end)
        assertEquals("Luigi's", dinner.location)
        assertNull(dinner.notes)
        assertEquals(30, dinner.reminderMinutes)
        assertEquals(LocalDateTime.of(2026, 10, 3, 19, 45),
            ok(DirectPlan.event("Call", "2026-10-03T19:00", null, 45, false, null, null, null, now)).end)
        assertEquals(LocalDateTime.of(2026, 10, 3, 21, 30),
            ok(DirectPlan.event("Film", "2026-10-03T19:00", "2026-10-03T21:30", null, false, null, null, null, now)).end)
        val holiday = ok(DirectPlan.event("Holiday", "2026-10-05", "2026-10-07", null, false, null, null, null, now))
        assertTrue(holiday.allDay)
        assertEquals(LocalDateTime.of(2026, 10, 8, 0, 0), holiday.end)
    }

    @Test fun likelyMistakesAreRefusedWithAReason() {
        assertTrue(refused(DirectPlan.event(" ", "2026-10-03T19:00", null, null, false, null, null, null, now)).contains("title"))
        assertTrue(refused(DirectPlan.event("X", "soon", null, null, false, null, null, null, now)).contains("start"))
        assertTrue(refused(DirectPlan.event("X", "2026-10-03T19:00", "2026-10-03T18:00", null, false, null, null, null, now)).contains("end after"))
        assertTrue(refused(DirectPlan.event("X", "2026-01-03T19:00", null, null, false, null, null, null, now)).contains("past"))
        assertTrue(refused(DirectPlan.event("X", "2026-10-03", "2026-11-30", null, false, null, null, null, now)).contains("two weeks"))
    }

    @Test fun calendarWindowsDefaultToTheComingWeek() {
        val (from, to) = (DirectPlan.window(null, null, now) as DirectPlan.Checked.Ok).value
        assertEquals(LocalDateTime.of(2026, 9, 27, 0, 0), from)
        assertEquals(LocalDateTime.of(2026, 10, 5, 0, 0), to)
        // A date as the end means through that whole day.
        assertEquals(LocalDateTime.of(2026, 10, 4, 0, 0),
            (DirectPlan.window("2026-10-01", "2026-10-03", now) as DirectPlan.Checked.Ok).value.second)
        assertTrue(DirectPlan.window("2026-10-01", "2027-01-01", now) is DirectPlan.Checked.Refused)
    }

    @Test fun eventsAreSaidTheWayPeopleSayThem() {
        assertEquals("Sat 3 Oct 19:00–20:00", DirectPlan.span(LocalDateTime.of(2026, 10, 3, 19, 0), LocalDateTime.of(2026, 10, 3, 20, 0), false))
        assertEquals("Sat 3 Oct (all day)", DirectPlan.span(LocalDateTime.of(2026, 10, 3, 0, 0), LocalDateTime.of(2026, 10, 4, 0, 0), true))
        assertEquals("Mon 5 Oct – Wed 7 Oct (all day)", DirectPlan.span(LocalDateTime.of(2026, 10, 5, 0, 0), LocalDateTime.of(2026, 10, 8, 0, 0), true))
    }

    @Test fun anAlarmIsProvenByAndroidsNextAlarm() {
        assertEquals(LocalDateTime.of(2026, 9, 28, 6, 30), DirectPlan.nextAlarm(6, 30, now))
        assertEquals(LocalDateTime.of(2026, 9, 27, 18, 0), DirectPlan.nextAlarm(18, 0, now))
        val next = DirectPlan.millis(LocalDateTime.of(2026, 9, 28, 6, 30), ZoneOffset.UTC)
        assertTrue(DirectPlan.alarmMatches(next, 6, 30, now, ZoneOffset.UTC))
        assertFalse(DirectPlan.alarmMatches(next, 7, 0, now, ZoneOffset.UTC))
        assertFalse(DirectPlan.alarmMatches(null, 6, 30, now, ZoneOffset.UTC))
    }

    @Test fun contactSearchesAreBounded() {
        assertEquals("Sam", DirectPlan.contactQuery(" Sam "))
        assertNull(DirectPlan.contactQuery("a"))
        assertNull(DirectPlan.contactQuery("%%"))
        assertEquals(60, DirectPlan.contactQuery("x".repeat(100))!!.length)
    }
}
