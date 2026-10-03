package com.cyclone.mobile.agent.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class PhoneIntentsTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private fun at(hour: Int, minute: Int, second: Int = 0) = Calendar.getInstance(utc).apply {
        clear(); set(2026, 8, 24, hour, minute, second)
    }.timeInMillis

    @Test fun relativeAlarmsResolveFromNow() {
        val alarm = PhoneIntents.parseAlarm("open clock and set an alarm for 5 minutes", at(23, 4, 29), utc)!!
        assertEquals("23:09", alarm.hhmm)
        assertEquals(5, alarm.relativeMinutes)
        assertEquals("00:30", PhoneIntents.parseAlarm("alarm in 1 hour", at(23, 30), utc)!!.hhmm)
        assertEquals("08:05", PhoneIntents.parseAlarm("zet een wekker over 5 minuten", at(8, 0), utc)!!.hhmm)
        assertNull("an alarm is minute precise", PhoneIntents.parseAlarm("alarm in 30 seconds", at(8, 0), utc))
    }

    @Test fun absoluteAlarmsUnderstandClockFormats() {
        assertEquals("07:00", PhoneIntents.parseAlarm("set an alarm at 7", at(1, 0), utc)!!.hhmm)
        assertEquals("07:30", PhoneIntents.parseAlarm("alarm for 7:30", at(1, 0), utc)!!.hhmm)
        assertEquals("19:15", PhoneIntents.parseAlarm("set an alarm at 7:15 pm", at(1, 0), utc)!!.hhmm)
        assertEquals("00:00", PhoneIntents.parseAlarm("alarm at 12 am", at(1, 0), utc)!!.hhmm)
        assertEquals("06:45", PhoneIntents.parseAlarm("wekker om 6.45", at(1, 0), utc)!!.hhmm)
        assertNull(PhoneIntents.parseAlarm("set an alarm", at(1, 0), utc))
    }

    @Test fun oneSentenceResolvesOnceSoTheTargetDoesNotDrift() {
        val goal = "set an alarm in 10 minutes (drift test)"
        val first = PhoneIntents.alarm(goal, at(10, 0), utc)!!
        assertEquals(first, PhoneIntents.alarm(goal, at(10, 3), utc))
    }

    @Test fun timersInEveryCommonShape() {
        assertEquals(300L, PhoneIntents.timer("set a timer for 5 minutes")!!.seconds)
        assertEquals(300L, PhoneIntents.timer("start a 5 minute timer")!!.seconds)
        assertEquals(300L, PhoneIntents.timer("5-minute timer please")!!.seconds)
        assertEquals(3600L, PhoneIntents.timer("timer of 1 hour")!!.seconds)
        assertEquals(90L, PhoneIntents.timer("set a timer for 90 seconds")!!.seconds)
        assertNull(PhoneIntents.timer("open the timer"))
        assertNull("alarm sentences are not timers", PhoneIntents.alarm("set a timer for 5 minutes", at(1, 0), utc))
    }

    @Test fun alarmProofNeedsAnEnabledRowAtTheTime() {
        val alarm = PhoneIntents.Alarm(23, 9, 5)
        fun rows(vararg labels: String) = labels.map { PhoneIntents.Row(it, null) }
        val clock = "com.google.android.deskclock"
        assertTrue(PhoneIntents.alarmVisible(clock, rows("Alarm Today 23:09 Alarm is currently enabled."), alarm))
        assertTrue(PhoneIntents.alarmVisible(clock, rows("11:09 PM, Alarm is currently enabled."), alarm))
        assertFalse(PhoneIntents.alarmVisible(clock, rows("11:09 AM, Alarm is currently enabled."), alarm))
        assertFalse(PhoneIntents.alarmVisible(clock, rows("Alarm 23:09 Alarm is currently disabled."), alarm))
        assertFalse(PhoneIntents.alarmVisible(clock, rows("Alarm 23:19 Alarm is currently enabled."), alarm))
        assertFalse(PhoneIntents.alarmVisible(clock, rows("Alarm 3:09 Alarm is currently enabled."), alarm))
        assertTrue("a checked switch on the row counts", PhoneIntents.alarmVisible("com.sec.android.app.clockpackage",
            listOf(PhoneIntents.Row("23:09", true)), alarm))
        assertFalse(PhoneIntents.alarmVisible("com.android.chrome", rows("Alarm 23:09 Alarm is currently enabled."), alarm))
    }

    @Test fun timerProofNeedsARunningCountdownNearTheRequest() {
        assertTrue(PhoneIntents.timerRunning(listOf("4:59", "Pause"), 300))
        assertTrue(PhoneIntents.timerRunning(listOf("0:59:10", "Stop"), 3600))
        assertFalse(PhoneIntents.timerRunning(listOf("5:00", "Start"), 300))
        assertFalse(PhoneIntents.timerRunning(listOf("2:10", "Pause"), 300))
    }

    @Test fun navigationClaimsAreNotOutcomes() {
        val goal = "open clock and set an alarm for 5 minutes"
        assertTrue(CompletionClaimAudit.navigationOnly(goal, "Clock is open on its Alarms screen."))
        assertFalse(CompletionClaimAudit.navigationOnly(goal, "Alarm set for 23:09 and enabled."))
        assertFalse("open-only goals may report navigation", CompletionClaimAudit.navigationOnly("open clock", "Clock is open."))
        assertFalse(CompletionClaimAudit.navigationOnly("which gmail am I logged in with", "Gmail is open; the account is jane@gmail.com"))
    }

    @Test fun aPaddedHourIsA24HourTimeSoNineAmIsNeverNinePm() {
        val clock = "com.google.android.deskclock"
        fun visible(label: String, hour: Int) =
            PhoneIntents.alarmVisible(clock, listOf(PhoneIntents.Row("Alarm $label Alarm is currently enabled.", null)), PhoneIntents.Alarm(hour, 0, null))
        assertFalse("09:00 is 9 AM", visible("Sunday 09:00", 21))
        assertTrue(visible("9:00 PM", 21))
        assertTrue(visible("21:00", 21))
        assertTrue(visible("Sunday 09:00", 9))
        assertTrue(visible("9:00 AM", 9))
    }
}
