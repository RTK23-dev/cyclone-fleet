package com.cyclone.mobile.mind

import com.cyclone.mobile.mind.SettingGoals.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingGoalsTest {
    @Test
    fun `the lab's settings goals are read as targets`() {
        assertEquals(listOf(SettingGoals.Target(Key.ROTATION, on = true)), SettingGoals.parse("Turn on auto-rotate"))
        assertEquals(listOf(SettingGoals.Target(Key.ROTATION, on = false)), SettingGoals.parse("Lock the screen rotation (turn auto-rotate off)"))
        assertEquals(listOf(SettingGoals.Target(Key.TIMEOUT_MS, ms = 120_000)), SettingGoals.parse("Set the screen timeout to 2 minutes"))
        assertEquals(listOf(SettingGoals.Target(Key.DARK, on = true)), SettingGoals.parse("Turn on the dark theme"))
        assertEquals(listOf(SettingGoals.Target(Key.DND, on = true)), SettingGoals.parse("Turn on Do Not Disturb"))
        assertEquals(listOf(SettingGoals.Target(Key.TOUCH_VIBRATION, on = false)), SettingGoals.parse("Turn off vibration for touch feedback"))
        assertEquals(listOf(SettingGoals.Target(Key.ADAPTIVE_BRIGHTNESS, on = false)), SettingGoals.parse("Turn off adaptive brightness"))
        assertEquals(listOf(SettingGoals.Target(Key.DARK, on = true)), SettingGoals.parse("zet de donkere modus aan"))
        assertEquals(2, SettingGoals.parse("Turn on the dark theme and set the screen timeout to 2 minutes")!!.size)
    }

    @Test
    fun `anything else in the goal means no check at all, so a task is never ended early`() {
        assertNull(SettingGoals.parse("Turn on the dark theme and tell me the battery percentage"))
        assertNull(SettingGoals.parse("Make the font size bigger"))
        assertNull(SettingGoals.parse("Open settings"))
        assertNull(SettingGoals.parse("turn the dark theme"))
        assertNull(SettingGoals.parse("Set the screen timeout"))
        assertNull(SettingGoals.parse(""))
    }

    @Test
    fun `the goal is met only when every target holds and every value could be read`() {
        val targets = SettingGoals.parse("Turn on the dark theme and set the screen timeout to 2 minutes")!!
        val good = SettingGoals.Reading(mapOf(Key.DARK to true, Key.TIMEOUT_MS to 120_000L))
        assertEquals("the dark theme is on, the screen timeout is 2 minutes", SettingGoals.met(targets, good))
        assertNull(SettingGoals.met(targets, SettingGoals.Reading(mapOf(Key.DARK to true, Key.TIMEOUT_MS to 30_000L))))
        assertNull(SettingGoals.met(targets, SettingGoals.Reading(mapOf(Key.DARK to true, Key.TIMEOUT_MS to null))))
    }
}
