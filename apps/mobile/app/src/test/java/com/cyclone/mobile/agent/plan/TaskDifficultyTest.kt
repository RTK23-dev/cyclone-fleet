package com.cyclone.mobile.agent.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskDifficultyTest {
    @Test
    fun easyIsNamedOpenOrSimpleWebsite() {
        assertEquals(TaskDifficultyTier.EASY, TaskDifficulty.classify("open Facebook"))
        assertEquals(TaskDifficultyTier.EASY, TaskDifficulty.classify("open fb"))
        assertEquals(TaskDifficultyTier.EASY, TaskDifficulty.classify("open google maps"))
        assertEquals(TaskDifficultyTier.EASY, TaskDifficulty.classify("open shopify.com"))
        assertTrue(TaskDifficulty.isNamedAppOpenOnly("open Facebook"))
        assertFalse(TaskDifficulty.isNamedAppOpenOnly("open Facebook and login"))
        assertFalse(TaskDifficulty.isNamedAppOpenOnly("DM Jacob on Instagram that I am late"))
        assertFalse(com.cyclone.mobile.agent.contract.GoalContractCompiler.isSimpleWebNavigation("open Facebook"))
        assertEquals(1, TaskDifficulty.namedAppCount("open google maps"))
        assertEquals(1, TaskDifficulty.namedAppCount("open facebook then fb"))
    }

    @Test
    fun mediumIsOneAppWithInSceneWork() {
        assertEquals(TaskDifficultyTier.MEDIUM, TaskDifficulty.classify("open Facebook and login"))
        assertEquals(TaskDifficultyTier.MEDIUM, TaskDifficulty.classify("DM Jacob on Instagram that I am late"))
        assertEquals(TaskDifficultyTier.MEDIUM, TaskDifficulty.classify("Open Chrome and search for Pixel 8"))
        assertEquals(TaskDifficultyTier.MEDIUM, TaskDifficulty.classify("Open Settings, then Picture-in-picture"))
        assertEquals(TaskDifficultyTier.MEDIUM, TaskDifficulty.classify("open WhatsApp then send a message to Jacob"))
        assertEquals(TaskDifficultyTier.MEDIUM, TaskDifficulty.classify("find a hotel close by"))
        assertEquals(TaskDifficultyTier.MEDIUM, TaskDifficulty.classify("look when I have that appointment"))
    }

    @Test
    fun hardIsTwoDestinationsAndUsesALocalPlan() {
        assertEquals(TaskDifficultyTier.HARD, TaskDifficulty.classify("open Gmail then send this to WhatsApp"))
        assertEquals(TaskDifficultyTier.HARD, TaskDifficulty.classify("open Facebook then share the post on Instagram"))
        assertEquals(TaskDifficultyTier.HARD, TaskDifficulty.classify("go to shopify.com then email the receipt in Gmail"))
        val assessment = TaskDifficulty.assess("open Gmail then send this to WhatsApp")
        assertEquals(2, assessment.destinationCount)
        assertTrue(assessment.localHardPlan)
        val plan = TaskDifficulty.hardWaypoints("open Gmail then send this to WhatsApp")!!
        assertEquals(2, plan.count { it.kind == WaypointKind.OPEN_APP })
        assertTrue(plan.any { it.packageName == "com.google.android.gm" })
        assertTrue(plan.any { it.packageName == "com.whatsapp" })
    }

    @Test
    fun instrumentAliasIsNotASecondDestination() {
        val assessment = TaskDifficulty.assess("make a new account on Instagram using my Gmail")
        assertEquals(1, assessment.destinationCount)
        assertEquals(TaskDifficultyTier.MEDIUM, assessment.tier)
        assertEquals(setOf("com.instagram.android"), assessment.packages)
        assertEquals("com.instagram.android", com.cyclone.mobile.fastpath.FastPathLanding.namedApp("make a new account on Instagram using my Gmail")?.second)
    }

    @Test
    fun chromePlusAWebsiteIsOneDestination() {
        val assessment = TaskDifficulty.assess("open chrome and go to shopify.com")
        assertEquals(1, assessment.destinationCount)
        assertEquals(TaskDifficultyTier.EASY, assessment.tier)
    }

    @Test
    fun chromeThenFacebookLoginIsWebsiteNotNativeApp() {
        val assessment = TaskDifficulty.assess(
            "Open Gmail and find my current logged-in email, then go to Chrome and log in to Facebook using that email.",
        )
        assertEquals(TaskDifficultyTier.HARD, assessment.tier)
        assertEquals(2, assessment.destinationCount)
        assertEquals("com.google.android.gm", assessment.destinations[0].value)
        assertEquals("host", assessment.destinations[1].kind)
        assertTrue(assessment.destinations[1].value.contains("facebook"))
        assertFalse(assessment.packages.contains("com.facebook.katana"))
        assertFalse(assessment.packages.contains("com.android.chrome"))
    }

    @Test
    fun easyOpenUsesInstalledLauncherInventory() {
        com.cyclone.mobile.fastpath.InstalledAppInventory.replace(
            listOf(
                com.cyclone.mobile.fastpath.InstalledApp("com.spotify.music", "Spotify"),
                com.cyclone.mobile.fastpath.InstalledApp("com.zhiliaoapp.musically", "TikTok"),
            ),
        )
        try {
            assertEquals(TaskDifficultyTier.EASY, TaskDifficulty.classify("open Spotify"))
            assertEquals(TaskDifficultyTier.EASY, TaskDifficulty.classify("open my TikTok"))
            assertTrue(TaskDifficulty.isNamedAppOpenOnly("open Spotify"))
            assertEquals(TaskDifficultyTier.MEDIUM, TaskDifficulty.classify("open Spotify and play a song"))
            assertEquals(TaskDifficultyTier.HARD, TaskDifficulty.classify("open Spotify then TikTok"))
            assertEquals(1, TaskDifficulty.namedAppCount("open Spotify"))
            assertEquals(2, TaskDifficulty.namedAppCount("open Spotify then TikTok"))
        } finally {
            com.cyclone.mobile.fastpath.InstalledAppInventory.replace(emptyList())
        }
    }
}
