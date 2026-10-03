package com.cyclone.mobile.fastpath

import com.cyclone.mobile.ai.RequestIntent
import com.cyclone.mobile.ai.RequestIntentRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImplicitAppRouterTest {
    private val pixel = listOf(
        InstalledApp("com.google.android.apps.maps", "Maps", 4),
        InstalledApp("com.google.android.calendar", "Calendar", 3),
        InstalledApp("com.android.chrome", "Chrome", 8),
        InstalledApp("com.google.android.gm", "Gmail", 6),
        InstalledApp("com.google.android.apps.messaging", "Messages", 2),
    )

    @Test
    fun hotelNearbyOpensInstalledMaps() {
        val choice = ImplicitAppRouter.resolve("find a hotel close by", pixel)!!
        assertEquals("lodging", choice.job.id)
        assertEquals("com.google.android.apps.maps", choice.packageName)
        assertTrue(choice.uri!!.startsWith("geo:"))
        assertTrue(choice.uri!!.contains("hotels"))
        val landing = choice.landing()
        assertEquals("phone.launch_intent", landing.tool)
        assertEquals("com.google.android.apps.maps", landing.packageName)
    }

    @Test
    fun appointmentOpensInstalledCalendar() {
        val choice = ImplicitAppRouter.resolve("look when I have that appointment", pixel)!!
        assertEquals("calendar", choice.job.id)
        assertEquals("com.google.android.calendar", choice.packageName)
        assertNull(choice.uri)
        assertEquals("phone.open_app", choice.landing().tool)
    }

    @Test
    fun bookingBeatsMapsWhenTheUserClearlyLivesInBooking() {
        val installed = pixel + InstalledApp("com.booking", "Booking.com", 24)
        val choice = ImplicitAppRouter.resolve("find a hotel close by", installed)!!
        assertEquals("com.booking", choice.packageName)
    }

    @Test
    fun chromeIsTheWebFallbackWhenMapsIsMissing() {
        val installed = listOf(InstalledApp("com.android.chrome", "Chrome"))
        val choice = ImplicitAppRouter.resolve("find a hotel close by", installed)!!
        assertEquals("com.android.chrome", choice.packageName)
        assertTrue(choice.uri!!.startsWith("https://www.google.com/maps"))
    }

    @Test
    fun namedAppsAreNeverReplaced() {
        assertNull(ImplicitAppRouter.job("open Facebook"))
        assertNull(ImplicitAppRouter.job("DM Jacob on Instagram that I am late"))
        assertNull(ImplicitAppRouter.resolve("open Maps", pixel))
        assertNull(ImplicitAppRouter.job("open Spotify", pixel + InstalledApp("com.spotify.music", "Spotify")))
    }

    @Test
    fun askCycloneTreatsUnnamedJobsAsPhoneWork() {
        assertEquals(RequestIntent.PHONE_TASK, RequestIntentRouter.route("find a hotel close by").intent)
        assertEquals(RequestIntent.PHONE_TASK, RequestIntentRouter.route("look when I have that appointment").intent)
        assertEquals("Calendar", RequestIntentRouter.route("look when I have that appointment").appHint)
        assertEquals(RequestIntent.CHAT, RequestIntentRouter.route("Write an email to my manager").intent)
        assertEquals(RequestIntent.CHAT, RequestIntentRouter.route("What is a hotel?").intent)
        assertEquals(RequestIntent.CHAT, RequestIntentRouter.route("when was the first meeting of congress").intent)
    }

    @Test
    fun fastPathUsesTheInstalledInventoryForUnnamedJobs() {
        InstalledAppInventory.replace(pixel)
        try {
            val hint = FastPathLanding.resolve("find a hotel close by")
            assertEquals("phone.launch_intent", hint?.tool)
            assertEquals("com.google.android.apps.maps", hint?.packageName)
            assertTrue(hint!!.uri!!.startsWith("geo:"))
            assertEquals("phone.open_app", FastPathLanding.resolve("look when I have that appointment")?.tool)
            assertEquals("com.google.android.calendar", FastPathLanding.resolve("look when I have that appointment")?.packageName)
        } finally {
            InstalledAppInventory.replace(emptyList())
        }
    }
}
