package com.cyclone.mobile.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskAppTrailTest {
    @Test fun theTrailKeepsDistinctAppsInOrderWithTheCurrentOneLast() {
        val id = "mission-trail-" + System.nanoTime()
        TaskAppTrail.record(id, "com.google.android.calendar")
        TaskAppTrail.record(id, "com.whatsapp")
        TaskAppTrail.record(id, "com.whatsapp")
        assertEquals(listOf("com.google.android.calendar", "com.whatsapp"), TaskAppTrail.of(id))
        TaskAppTrail.record(id, "com.google.android.calendar")
        assertEquals(listOf("com.whatsapp", "com.google.android.calendar"), TaskAppTrail.of(id))
    }

    @Test fun cycloneItselfAndBlanksAreNotApps() {
        val id = "mission-trail-" + System.nanoTime()
        TaskAppTrail.record(id, "com.cyclone.mobile")
        TaskAppTrail.record(id, "")
        TaskAppTrail.record(id, null)
        assertEquals(emptyList<String>(), TaskAppTrail.of(id))
    }

    @Test fun atMostThreeAppsAreShown() {
        val id = "mission-trail-" + System.nanoTime()
        listOf("a.one", "b.two", "c.three", "d.four").forEach { TaskAppTrail.record(id, it) }
        assertEquals(listOf("b.two", "c.three", "d.four"), TaskAppTrail.of(id))
    }
}
