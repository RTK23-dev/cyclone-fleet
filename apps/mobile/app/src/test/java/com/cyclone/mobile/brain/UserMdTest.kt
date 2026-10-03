package com.cyclone.mobile.brain

import com.cyclone.mobile.fastpath.FastPathLanding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserMdTest {
    @Test
    fun extractKnowsJacobOnInstagram() {
        val extracted = UserMdDocument.extract("DM Jacob on Instagram that I am late")
        assertEquals("Jacob", extracted.people.single().name)
        assertEquals(listOf("Instagram"), extracted.people.single().apps)
    }

    @Test
    fun twoAppsBecomeAnAskNotAGuess() {
        val doc = UserMdDocument.empty()
            .upsertPerson("Jacob", listOf("Instagram"))
            .upsertPerson("Jacob", listOf("WhatsApp"))
        val slice = doc.slice("message Jacob")
        assertEquals(listOf("Jacob"), slice.askWhich)
        assertTrue(slice.text.contains("Ask which app"))
        assertNull(slice.preferredApp)
        assertTrue(slice.text.length < 400)
    }

    @Test
    fun oneAppIsEnoughToLand() {
        val doc = UserMdDocument.empty().upsertPerson("Jacob", listOf("Instagram"))
        val slice = doc.slice("message Jacob")
        assertTrue(slice.askWhich.isEmpty())
        assertEquals("Instagram", slice.preferredApp)
        UserMdRuntime.resetForTest(true, doc)
        try {
            assertEquals("com.instagram.android", UserMdRuntime.landing("message Jacob")?.packageName)
            assertEquals("phone.open_app", FastPathLanding.resolve("message Jacob")?.tool)
        } finally {
            UserMdRuntime.resetForTest()
        }
    }

    @Test
    fun namedAppStillWins() {
        val doc = UserMdDocument.empty().upsertPerson("Jacob", listOf("Instagram", "WhatsApp"))
        UserMdRuntime.resetForTest(true, doc)
        try {
            assertNull(UserMdRuntime.landing("message Jacob on WhatsApp"))
            assertEquals("com.whatsapp", FastPathLanding.resolve("message Jacob on WhatsApp")?.packageName)
        } finally {
            UserMdRuntime.resetForTest()
        }
    }

    @Test
    fun meSectionIsNotSentUnlessSomethingMatched() {
        val doc = UserMdDocument(me = "I live in Amsterdam and work nights.")
        assertFalse(doc.slice("open Chrome").useful)
        val withPerson = doc.upsertPerson("Jacob", listOf("Instagram"))
        val slice = withPerson.slice("message Jacob")
        assertTrue(slice.text.startsWith("I live in Amsterdam"))
        assertTrue(slice.text.contains("Jacob"))
    }

    @Test
    fun parseRoundTripKeepsUserMe() {
        val raw = """
            # Me
            Call me Alex.

            # People
            - Jacob: Instagram, WhatsApp

            # Apps
            - hotels: Maps

            # Cues
            - If someone is on more than one app, ask which one.
        """.trimIndent()
        val parsed = UserMdDocument.parse(raw)
        assertEquals("Call me Alex.", parsed.me)
        assertEquals("Jacob", parsed.people.single().name)
        assertEquals(listOf("Instagram", "WhatsApp"), parsed.people.single().apps)
        assertEquals("Maps", parsed.apps.single().second)
        assertTrue(parsed.render().contains("# Me"))
        assertTrue(parsed.render().contains("Call me Alex."))
    }
}
