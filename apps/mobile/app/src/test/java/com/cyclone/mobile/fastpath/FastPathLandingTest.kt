package com.cyclone.mobile.fastpath

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FastPathLandingTest {
    @Test fun browserFirstWebsiteSkipsRedundantAppLaunch() {
        val hint = FastPathLanding.resolve("open chrome and go to Shopify")
        assertEquals("phone.launch_intent", hint?.tool)
        assertEquals("https://shopify.com", hint?.uri)
    }

    @Test
    fun explicitChromeFacebookGoalKeepsBrowserRouteEvenWithTrailingSignupWork() {
        val hint = FastPathLanding.resolve(
            "open chrome and go to Facebook and try to make an account using my email",
        )
        assertEquals("phone.launch_intent", hint?.tool)
        assertEquals("https://facebook.com", hint?.uri)
        assertNull(hint?.packageName)
        assertTrue(hint!!.reason.contains("Chrome", ignoreCase = true))
    }

    @Test
    fun explicitChromePrefixDoesNotChangeOrdinaryNativeFacebookGoal() {
        val hint = FastPathLanding.resolve("open Facebook and login")
        assertEquals("phone.open_app", hint?.tool)
        assertEquals("com.facebook.katana", hint?.packageName)
    }

    @Test
    fun websiteGoalPrefersLaunchIntentOverIconHunting() {
        val hint = FastPathLanding.resolve("Open ad.nl")
        assertEquals("phone.launch_intent", hint?.tool)
        assertEquals("https://ad.nl", hint?.uri)
        assertTrue(hint!!.reason.contains("icon", ignoreCase = true))
        assertNull(hint.packageName)
    }

    @Test
    fun namedAppPrefersOpenAppAndMarksWorkspaceRouting() {
        val hint = FastPathLanding.resolve("Open Chrome and search for Pixel 8")
        assertEquals("phone.open_app", hint?.tool)
        assertEquals("com.android.chrome", hint?.packageName)
        assertTrue(hint!!.workspaceNamedApp)
        assertTrue(hint.reason.contains("3.9.12"))
    }

    @Test
    fun rawHttpsUrlIsSanitizedWithoutQueryOrFragment() {
        val hint = FastPathLanding.resolve("Go to https://example.com/path?q=secret#frag")
        assertEquals("https://example.com/path", hint?.uri)
        assertTrue(hint!!.toJson().optBoolean("preferBeforeIconHunt"))
    }

    @Test
    fun blankOrAmbiguousGoalsDoNotInventALanding() {
        assertNull(FastPathLanding.resolve(""))
        assertNull(FastPathLanding.resolve("tap the blue button"))
        assertNull(FastPathLanding.sanitizeUri("javascript:alert(1)"))
    }

    @Test
    fun facebookLoginLandsOnInstalledAppNotTheModel() {
        val hint = FastPathLanding.resolve("open Facebook and login")
        assertEquals("phone.open_app", hint?.tool)
        assertEquals("com.facebook.katana", hint?.packageName)
        assertEquals(listOf("com.facebook.katana", "com.facebook.lite"), FastPathLanding.launchCandidates(hint!!.packageName!!))
        assertEquals("https://facebook.com", FastPathLanding.webFallback(hint.packageName!!))
    }

    @Test
    fun destinationCueBeatsAnInstrumentAlias() {
        assertEquals("com.android.chrome", FastPathLanding.namedApp("open Chrome using maps")?.second)
        assertEquals("com.google.android.apps.maps", FastPathLanding.namedApp("directions on maps using Chrome")?.second)
        assertEquals("com.instagram.android", FastPathLanding.namedApp("make a new account on Instagram using my Gmail")?.second)
        assertTrue(FastPathLanding.namedAppHits("make a new account on Instagram using my Gmail").any { it.alias == "gmail" && it.instrumentOnly })
    }

    @Test
    fun namedAppOpenIsALocalPlannerLanding() {
        val agent = sequenceOf(
            java.io.File("src/main/java/com/cyclone/mobile/ai/OpenRouterAdaptiveAgent.kt"),
            java.io.File("apps/mobile/app/src/main/java/com/cyclone/mobile/ai/OpenRouterAdaptiveAgent.kt"),
        ).first { it.isFile }.readText()
        val landing = agent.indexOf("landing?.tool == \"phone.open_app\"")
        assertTrue(landing >= 0)
        assertTrue(agent.contains("webFallback(landing.packageName)"))
        assertTrue(agent.contains("APP_NOT_FOUND"))
        assertTrue(agent.contains("ActionOutcomePolicy.providerBoundary"))
        assertTrue(agent.contains("ActionOutcomePolicy.hardBlocker"))
        assertTrue(agent.contains("phone.wait_for"))
        assertTrue(agent.contains("horizon.plan"))
        assertTrue(agent.contains("taskTier"))
        assertTrue(agent.contains("easy.unopened"))
        assertTrue(agent.contains("tierRule"))
        assertTrue(agent.contains("InstalledAppInventory"))
        assertTrue(agent.contains("geo:"))
        assertTrue(agent.contains("user.md.ask_which"))
        assertTrue(agent.contains("userMd"))
    }

    @Test
    fun settingsAliasResolvesToSystemPackage() {
        assertEquals("com.android.settings", FastPathLanding.namedApp("Open Settings")?.second)
        assertEquals("settings", FastPathLanding.namedApp("Open Settings")?.first)
        assertEquals("com.facebook.katana", FastPathLanding.namedApp("open fb")?.second)
        assertEquals("phone.open_app", FastPathLanding.resolve("open fb")?.tool)
        assertEquals("com.instagram.android", FastPathLanding.namedApp("DM Jacob on insta")?.second)
    }

    @Test
    fun easyOpenUsesAnyInstalledLauncherLabel() {
        val installed = listOf(
            InstalledApp("com.spotify.music", "Spotify"),
            InstalledApp("com.zhiliaoapp.musically", "TikTok"),
            InstalledApp("com.booking", "Booking.com"),
            InstalledApp("org.thoughtcrime.securesms", "Signal"),
            InstalledApp("com.google.android.keep", "Keep"),
        )
        assertEquals("com.spotify.music", FastPathLanding.namedApp("open Spotify", installed)?.second)
        assertEquals("com.zhiliaoapp.musically", FastPathLanding.namedApp("open TikTok", installed)?.second)
        assertEquals("com.booking", FastPathLanding.namedApp("open Booking", installed)?.second)
        assertEquals("org.thoughtcrime.securesms", FastPathLanding.namedApp("open Signal", installed)?.second)
        val hint = FastPathLanding.resolve("open Spotify", installed)
        assertEquals("phone.open_app", hint?.tool)
        assertEquals("com.spotify.music", hint?.packageName)
        assertTrue(hint!!.workspaceNamedApp)
        assertNull(FastPathLanding.namedApp("open Spotify"))
        assertNull(com.cyclone.mobile.agent.contract.NavigationIntent.parse("open Spotify", installed))
        assertEquals("https://ad.nl", FastPathLanding.resolve("Open ad.nl", installed)?.uri)
        assertNull(FastPathLanding.namedApp("my keep is full", installed))
        assertEquals("com.google.android.keep", FastPathLanding.namedApp("open Keep", installed)?.second)
        assertEquals("com.facebook.katana", FastPathLanding.namedApp("open fb", installed)?.second)
        assertEquals("com.spotify.music", FastPathLanding.packageForName("Spotify", installed))
        assertEquals("org.thoughtcrime.securesms", FastPathLanding.packageForName("Signal", installed))
    }
}
