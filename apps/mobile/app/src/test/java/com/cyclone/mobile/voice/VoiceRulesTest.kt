package com.cyclone.mobile.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VoiceRulesTest {
    @Test fun `nothing, filler and stock silence phrases close without a call`() {
        for (text in listOf(null, "", "   ", "...")) assertEquals(text, VoiceRules.Screen.Empty, VoiceRules.screen(text))
        for (text in listOf("uh", "Hmm.", "um, uh", "Thank you.", "Thanks for watching!", "you", "ehm")) {
            assertEquals(text, VoiceRules.Screen.Filler, VoiceRules.screen(text))
        }
    }

    @Test fun `cancel words close with okay and stop is marked`() {
        assertEquals(VoiceRules.Screen.Cancel(stop = false), VoiceRules.screen("Never mind."))
        assertEquals(VoiceRules.Screen.Cancel(stop = false), VoiceRules.screen("uh, cancel that"))
        assertEquals(VoiceRules.Screen.Cancel(stop = false), VoiceRules.screen("Laat maar"))
        assertEquals(VoiceRules.Screen.Cancel(stop = true), VoiceRules.screen("Stop!"))
        assertEquals(VoiceRules.Screen.Cancel(stop = true), VoiceRules.screen("stop it"))
    }

    @Test fun `bare yes and no are answers only while Cyclone asks`() {
        assertEquals(VoiceRules.Screen.Filler, VoiceRules.screen("Yes."))
        assertEquals(VoiceRules.Screen.Filler, VoiceRules.screen("okay"))
        assertEquals(VoiceRules.Screen.Pass("Yes."), VoiceRules.screen("Yes.", answering = true))
        assertEquals(VoiceRules.Screen.Pass("no"), VoiceRules.screen("no", answering = true))
    }

    @Test fun `a request keeps its words without the filler around it`() {
        assertEquals(VoiceRules.Screen.Pass("reply to Louella that I'm fine with it"),
            VoiceRules.screen("uh, reply to Louella that I'm fine with it"))
        assertEquals(VoiceRules.Screen.Pass("set a timer for 10 minutes"), VoiceRules.screen("  set a timer for 10 minutes  um"))
        // "stop" inside a request is not a stop.
        assertTrue(VoiceRules.screen("stop the music in Spotify") is VoiceRules.Screen.Pass)
    }

    @Test fun `a transcript is capped`() {
        val long = "word ".repeat(400)
        val pass = VoiceRules.screen(long) as VoiceRules.Screen.Pass
        assertTrue(pass.text.length <= VoiceRules.MAX_TRANSCRIPT_CHARS)
    }

    @Test fun `wav header describes 16 kHz mono 16 bit`() {
        val samples = shortArrayOf(0, 1000, -1000, 32767)
        val wav = Wav.encode(samples)
        assertEquals(44 + 8, wav.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(36 + 8, b.getInt(4))
        assertEquals(1, b.getShort(20).toInt())
        assertEquals(1, b.getShort(22).toInt())
        assertEquals(16_000, b.getInt(24))
        assertEquals(32_000, b.getInt(28))
        assertEquals(16, b.getShort(34).toInt())
        assertEquals(8, b.getInt(40))
        assertEquals(1000, b.getShort(46).toInt())
        assertEquals(32767, b.getShort(50).toInt())
    }

    @Test fun `trim keeps the speech with a little room`() {
        val samples = ShortArray(16_000 * 3) { it.toShort() }
        val trimmed = Wav.trim(samples, samples.size, startMs = 1_000, endMs = 2_000, padMs = 250)
        assertEquals(16 * 1_500, trimmed.size)
        assertEquals(samples[16 * 750], trimmed[0])
        assertArrayEquals(samples, Wav.trim(samples, samples.size, 0, 3_000, padMs = 500))
    }
}
