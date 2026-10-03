package com.cyclone.mobile.codes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Alpha.102: `numbers.list` gives the PC this phone's numbers only, one row per number. */
class NumbersReportTest {
    @Test fun simsComeFirstAndTheSameNumberTwiceIsOneRow() {
        val json = NumbersReport.build(true, true, listOf(1 to "+31 6 1234 5678"), listOf("06-1234-5678", "+44 7700 900123", "12"))
        val rows = json.getJSONArray("numbers")
        assertEquals(2, rows.length())
        assertEquals("+31612345678", rows.getJSONObject(0).getString("number"))
        assertEquals("sim", rows.getJSONObject(0).getString("source"))
        assertEquals(1, rows.getJSONObject(0).getInt("slot"))
        assertEquals("confirmed", rows.getJSONObject(1).getString("source"))
        assertEquals(setOf("enabled", "canRead", "numbers"), json.keys().asSequence().toSet())
        assertEquals(setOf("number", "source", "slot"), rows.getJSONObject(1).keys().asSequence().toSet())
    }

    @Test fun offAndUnreadableAreReportedAsTheyAre() {
        val json = NumbersReport.build(false, false, emptyList(), emptyList())
        assertFalse(json.getBoolean("enabled"))
        assertFalse(json.getBoolean("canRead"))
        assertEquals(0, json.getJSONArray("numbers").length())
    }

    @Test fun atMostEightNumbers() {
        val many = (0 until 12).map { "+3161234500$it".take(13) + it }
        assertEquals(NumbersReport.MAX, NumbersReport.build(true, true, emptyList(), many).getJSONArray("numbers").length())
    }
}
