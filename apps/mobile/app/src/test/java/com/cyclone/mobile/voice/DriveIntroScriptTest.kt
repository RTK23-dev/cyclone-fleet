package com.cyclone.mobile.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveIntroScriptTest {
    private fun at(t: Long, still: Boolean = false) = DriveIntroScript.frame(t, still)

    @Test fun `three scenes then the close, each with its own caption`() {
        assertEquals(listOf(0, 1, 2, 3), listOf(0L, 3_000L, 6_000L, 9_000L).map { at(it).scene })
        assertEquals("Tap the orb and talk", at(1_000).title)
        assertEquals("It answers in one line", at(4_000).title)
        assertEquals("Anything risky waits", at(7_000).title)
        assertEquals("Drive safe", at(9_300).title)
        assertTrue(at(9_300).done)
        assertFalse(at(8_900).done)
        assertTrue(at(7_000).detail.contains("Paying, deleting, passwords"))
        assertTrue(at(9_300).detail.contains("Stop is always one tap"))
        assertEquals("Drive safe. Stop is always one tap. Keep your eyes on the road.", at(9_300).spoken)
    }

    @Test fun `the bubble grows into the orb, a tap lands, and the orb listens`() {
        assertEquals(0.34f, at(0).orbScale, 0.001f)
        assertEquals(1f, at(700).orbScale, 0.001f)
        assertTrue(at(400).orbScale in 0.6f..1.2f)
        assertEquals(0f, at(800).ripple, 0f)
        assertEquals(0.5f, at(1_200).ripple, 0.001f)
        assertEquals(0f, at(1_600).ripple, 0f)
        assertEquals(OrbMotion.CALM, at(500).motion)
        val listening = at(2_000)
        assertEquals(OrbMotion.LISTEN, listening.motion)
        assertEquals(PillIcon.YOU, listening.pillIcon)
        assertTrue(listening.pill.contains("Louella"))
        assertTrue((1_100L..2_900L step 37).all { at(it).level in 0f..1f })
    }

    @Test fun `it thinks, answers in one line, then says it is done`() {
        assertEquals(OrbMotion.SWIRL, at(3_300).motion)
        assertEquals(OrbMotion.SPEAK, at(4_000).motion)
        assertEquals(PillIcon.CYCLONE, at(4_000).pillIcon)
        assertEquals("Sent to Louella", at(5_000).pill)
        assertEquals(PillIcon.DONE, at(5_000).pillIcon)
        assertFalse(at(5_000).warm)
    }

    @Test fun `a risky step turns warm and waits while the car slows to a stop`() {
        val waiting = at(6_300)
        assertTrue(waiting.warm)
        assertEquals(PillIcon.WAIT, waiting.pillIcon)
        assertEquals(1f, waiting.roadSpeed, 0f)
        assertEquals(0f, at(7_500).roadSpeed, 0f)
        assertEquals(PillIcon.WAIT, at(7_500).pillIcon)
        assertEquals(PillIcon.READY, at(7_800).pillIcon)
        assertTrue(at(7_800).pill.startsWith("Stopped"))
    }

    @Test fun `the road never goes backwards and stays parked after stopping`() {
        var last = -1f
        for (t in 0L..9_500L step 5) {
            val travel = DriveIntroScript.travel(t)
            assertTrue("travel went backwards at $t", travel >= last)
            last = travel
        }
        assertEquals(DriveIntroScript.travel(7_400), DriveIntroScript.travel(9_000), 0f)
        assertTrue(DriveIntroScript.travel(7_400) > DriveIntroScript.travel(6_400))
        assertEquals(0f, at(9_400).roadSpeed, 0f)
    }

    @Test fun `a tap moves to the next scene and the close stays put`() {
        assertEquals(3_000L, DriveIntroScript.next(100))
        assertEquals(6_000L, DriveIntroScript.next(3_000))
        assertEquals(9_000L, DriveIntroScript.next(8_000))
        assertEquals(9_000L, DriveIntroScript.next(9_000))
        assertEquals(DriveIntroScript.LAST_MS, DriveIntroScript.next(DriveIntroScript.LAST_MS + 1_000))
    }

    @Test fun `captions fade in and out, and the close fades in after the last scene`() {
        assertEquals(0f, at(0).captionAlpha, 0f)
        assertEquals(1f, at(1_000).captionAlpha, 0f)
        assertEquals(0f, at(2_999).captionAlpha, 0.02f)
        assertEquals(0f, at(DriveIntroScript.END_MS).captionAlpha, 0f)
        assertEquals(1f, at(DriveIntroScript.LAST_MS).captionAlpha, 0f)
    }

    @Test fun `with animations off every scene is shown whole and still`() {
        for (scene in 0 until DriveIntroScript.SCENES) {
            val frame = at(DriveIntroScript.settled(scene), still = true)
            assertEquals(scene, frame.scene)
            assertEquals(1f, frame.captionAlpha, 0f)
            assertEquals(1f, frame.orbScale, 0.0001f)
            assertEquals(0f, frame.ripple, 0f)
            assertEquals(0f, frame.level, 0f)
            assertEquals(1f, frame.pillAlpha, 0f)
            assertEquals(0f, frame.pillLift, 0f)
            assertTrue(frame.pill.isNotEmpty())
        }
        assertEquals(PillIcon.DONE, at(DriveIntroScript.settled(1), still = true).pillIcon)
        assertEquals(PillIcon.READY, at(DriveIntroScript.settled(2), still = true).pillIcon)
        assertEquals(1f, at(DriveIntroScript.END_MS, still = true).captionAlpha, 0f)
        assertTrue(DriveIntroScript.STILL_SCENE_MS > DriveIntroScript.SCENE_MS)
    }
}
