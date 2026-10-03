package com.cyclone.mobile.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupCardsTest {
    private val all = SetupFlow.cards(35)

    @Test fun backgroundCardOnlyOnAndroid15() {
        assertTrue(SetupCard.BACKGROUND in SetupFlow.cards(35))
        assertFalse(SetupCard.BACKGROUND in SetupFlow.cards(34))
        assertEquals(SetupCard.entries.size - 2, SetupFlow.cards(34).size)
    }

    @Test fun theDriverCardIsPartOfSetupOnlyInDriverMode() {
        assertFalse(SetupCard.DRIVER in SetupFlow.cards(35))
        assertTrue(SetupCard.DRIVER in SetupFlow.cards(35, driving = true))
        // Turning Driver mode on after setup shows its card once, as a new card would.
        val seenBefore = SetupFlow.cards(35).map { it.id }.toSet()
        assertTrue(SetupFlow.shouldOpen(SetupFlow.cards(35, driving = true), SetupFlow.cards(35).toSet(), seenBefore))
    }

    @Test fun startsWithPhoneControl() {
        assertEquals(SetupCard.PHONE_CONTROL, SetupFlow.next(all, emptySet(), emptySet()))
    }

    @Test fun settingsAlreadyOnAreSkipped() {
        val done = setOf(SetupCard.PHONE_CONTROL, SetupCard.OVER_APPS)
        assertEquals(SetupCard.RESULTS, SetupFlow.next(all, done, emptySet()))
        assertEquals(all.size - 2, SetupFlow.left(all, done, emptySet()))
    }

    @Test fun skipMovesOnAndKeepsTheSettingOff() {
        val passed = setOf(SetupCard.PHONE_CONTROL)
        assertEquals(SetupCard.OVER_APPS, SetupFlow.next(all, emptySet(), passed))
        assertTrue(SetupCard.PHONE_CONTROL in SetupFlow.stillOff(all, emptySet()))
    }

    @Test fun finishedWhenEverythingIsOnOrSkipped() {
        assertNull(SetupFlow.next(all, all.toSet(), emptySet()))
        assertNull(SetupFlow.next(all, setOf(SetupCard.PHONE_CONTROL), all.toSet()))
        assertEquals(0, SetupFlow.left(all, all.toSet(), emptySet()))
    }

    @Test fun opensByItselfOnlyForUnseenCardsThatAreOff() {
        assertTrue(SetupFlow.shouldOpen(all, emptySet(), emptySet()))
        // Closed with X or skipped: every card is seen, so it never nags.
        assertFalse(SetupFlow.shouldOpen(all, emptySet(), all.map { it.id }.toSet()))
        // Everything on: nothing to show.
        assertFalse(SetupFlow.shouldOpen(all, all.toSet(), emptySet()))
    }

    @Test fun aCardAddedByAnUpdateOpensAgain() {
        val seenBefore = SetupFlow.cards(34).map { it.id }.toSet()
        assertTrue(SetupFlow.shouldOpen(all, emptySet(), seenBefore))
        assertFalse(SetupFlow.shouldOpen(all, setOf(SetupCard.BACKGROUND), seenBefore))
    }

    @Test fun copyStaysShortAndPlain() {
        val jargon = listOf("accessibility", "adb", "shell", "virtual display", "permission", "api", "root")
        SetupCard.entries.forEach { card ->
            assertTrue("${card.id} title too long", card.title.length <= 32)
            assertTrue("${card.id} why too long", card.why.length <= 130)
            jargon.forEach { word -> assertFalse("${card.id} says '$word'", (card.title + " " + card.why).lowercase().contains(word)) }
        }
        assertEquals(SetupCard.entries.size, SetupCard.entries.map { it.id }.toSet().size)
        assertEquals(SetupCard.CALENDAR, SetupCard.byId("calendar"))
    }

    @Test fun headerAndEndCopy() {
        assertEquals("SET UP CYCLONE · 3 LEFT", SetupCopy.meta(3))
        assertEquals("SET UP CYCLONE · LAST ONE", SetupCopy.meta(1))
        assertEquals("Everything is on. You can change any of it in Settings.", SetupCopy.finished(emptyList()))
        assertTrue(SetupCopy.finished(listOf(SetupCard.CALENDAR, SetupCard.VOICE)).startsWith("Still off: calendar, voice."))
    }
}
