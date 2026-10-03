package com.cyclone.mobile.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceFaceTest {
    @Test fun `idle is the calm button, no panel, no dim`() {
        val face = VoiceFace.of(VoiceTurn())
        assertEquals(OrbMotion.CALM, face.motion)
        assertFalse(face.panel)
        assertFalse(face.dim)
        assertFalse(face.stop)
        assertEquals("Ready", face.stateDescription)
    }

    @Test fun `listening dims, opens the panel and says so`() {
        val face = VoiceFace.of(VoiceTurn().on(VoiceEvent.Tap).turn)
        assertEquals(OrbMotion.LISTEN, face.motion)
        assertTrue(face.panel && face.dim && face.stop)
        assertEquals(VoiceCopy.CAPTION_LISTENING, face.cyclone)
        assertEquals("Listening", face.status)
    }

    @Test fun `thinking swirls and working collapses to the ring`() {
        var turn = VoiceTurn().on(VoiceEvent.Tap).turn.on(VoiceEvent.Heard).turn
        assertEquals(OrbMotion.SWIRL, VoiceFace.of(turn).motion)
        turn = turn.on(VoiceEvent.Transcript("set a timer")).turn
            .on(VoiceEvent.Understood(Understanding(VoiceKind.TASK, "Set a timer.", "Setting a timer.", "", 1.0))).turn
        assertEquals(OrbMotion.SPEAK, VoiceFace.of(turn).motion)
        assertEquals("set a timer", VoiceFace.of(turn).you)
        assertEquals("Setting a timer.", VoiceFace.of(turn).cyclone)
        turn = turn.on(VoiceEvent.SpeechEnded).turn
        val working = VoiceFace.of(turn)
        assertEquals(OrbMotion.WORK, working.motion)
        assertFalse(working.panel)
        assertTrue(working.stop)
        assertEquals("", working.cyclone)
    }

    @Test fun `a question glows warm and offers not now`() {
        var turn = VoiceTurn(taskLive = true, phase = VoicePhase.WORKING)
        turn = turn.on(VoiceEvent.MomentOpened(VoiceMoment("q", VoiceMoment.Kind.QUESTION, "Which one?"))).turn
        val face = VoiceFace.of(turn)
        assertTrue(face.warm && face.notNow && face.panel)
        assertEquals("Needs you", face.status)
    }

    @Test fun `models come from the live list, preferences first, the owner's pick wins`() {
        val live = listOf(VoiceModel("x/other", "Other"), VoiceModel("openai/gpt-4o-mini-tts-2025-12-15", "Mini TTS"),
            VoiceModel("hexgrad/kokoro-82m", "Kokoro"))
        assertEquals("openai/gpt-4o-mini-tts-2025-12-15", VoiceModels.pick(live, VoiceModels.PREFERRED_TTS))
        assertEquals("hexgrad/kokoro-82m", VoiceModels.pick(live, VoiceModels.PREFERRED_TTS, chosen = "hexgrad/kokoro-82m"))
        // A pick that is no longer listed falls back; nothing unseen is ever used.
        assertEquals("openai/gpt-4o-mini-tts-2025-12-15", VoiceModels.pick(live, VoiceModels.PREFERRED_TTS, chosen = "gone/model"))
        assertEquals("x/other", VoiceModels.pick(listOf(VoiceModel("x/other", "Other")), VoiceModels.PREFERRED_TTS))
        assertNull(VoiceModels.pick(emptyList(), VoiceModels.PREFERRED_TTS))
    }

    @Test fun `the live list is parsed with prices and voices`() {
        val models = VoiceModels.parse("""{"data":[{"id":"openai/whisper-large-v3-turbo","name":"Whisper","pricing":{"prompt":"0.0000001"}},
            {"id":"bad id"},{"id":"google/tts","name":"TTS","voices":["Kore","Puck"]}]}""")
        assertEquals(listOf("openai/whisper-large-v3-turbo", "google/tts"), models.map { it.id })
        assertEquals(listOf("Kore", "Puck"), models[1].voices)
        assertEquals(emptyList<VoiceModel>(), VoiceModels.parse("not json"))
    }

    @Test fun `voices fall back to the family and the owner's voice wins`() {
        val gemini = VoiceModel("google/gemini-3.8-flash-lite-tts", "Gemini TTS")
        assertEquals("Kore", VoiceModels.voice(gemini, null))
        assertEquals("Puck", VoiceModels.voice(gemini, "Puck"))
        assertEquals("Kore", VoiceModels.voice(gemini, "not-a-voice"))
        assertNull(VoiceModels.voice(VoiceModel("unknown/tts", "?"), null))
        assertEquals(listOf("eve", "ara", "rex", "sal", "leo"), VoiceModels.voices(VoiceModel("x-ai/grok-voice-tts-1.0", "Grok Voice")))
        assertEquals("ara", VoiceModels.voice(VoiceModel("x-ai/grok-voice-tts-1.0", "Grok Voice"), "ara"))
    }

    @Test fun `on-device recognition needs no speech-to-text model`() {
        val choice = VoiceModels.choose(listOf(VoiceModel("openai/whisper-large-v3-turbo", "W")), emptyList(), emptyList(),
            DriverSettings(onDeviceStt = true))
        assertNull(choice.stt)
    }

    @Test fun `pcm rate comes from the content type`() {
        assertEquals(24_000, VoiceModels.pcmRate(null))
        assertEquals(16_000, VoiceModels.pcmRate("audio/pcm; rate=16000"))
        assertEquals(24_000, VoiceModels.pcmRate("audio/pcm;rate=999999"))
    }

    @Test fun `timings keep percentiles against the targets`() {
        var t = VoiceTimings()
        assertNull(t.p50)
        listOf(1_500L, 1_800L, 2_100L, 2_600L, 3_400L).forEach { t = t.record(600, it, "openrouter") }
        assertEquals(2_100L, t.p50)
        assertEquals(3_400L, t.p90)
        repeat(30) { t = t.record(500, 1_000, "cache") }
        assertEquals(VoiceTimings.KEEP, t.confirms.size)
        assertEquals("0.7 s", VoiceTimings.seconds(700))
    }

    @Test fun `earcons are short, soft and click free`() {
        for (earcon in Earcon.entries) {
            val pcm = Earcons.pcm(earcon)
            val ms = pcm.size * 1000 / Earcons.RATE
            assertTrue("$earcon is $ms ms", ms in 100..600)
            assertTrue("$earcon is too loud", pcm.maxOf { kotlin.math.abs(it.toInt()) } < 12_000)
            // Starts and ends near silence: no click.
            assertTrue(kotlin.math.abs(pcm.first().toInt()) < 200)
            assertTrue(kotlin.math.abs(pcm.last().toInt()) < 200)
        }
    }

    @Test fun `speech is resampled for the transcriber`() {
        val input = ShortArray(24_000) { (it % 100).toShort() }
        val out = Wav.resample(input, 24_000, 16_000)
        assertEquals(16_000, out.size)
        assertEquals(input[0], out[0])
        val bytes = byteArrayOf(0x10, 0x00, 0xff.toByte(), 0x7f)
        assertEquals(listOf<Short>(16, 32767), Wav.samples(bytes).toList())
    }

    @Test fun `a dropped button stays where it was let go, wholly on screen`() {
        val spot = ButtonSpot.at(540f, 1200f, 1080f, 2400f)
        assertEquals(0.5f, spot.xFraction, 0.0001f)
        assertEquals(0.5f, spot.yFraction, 0.0001f)
        assertEquals(540 - 50 to 1200 - 50, spot.corner(1080, 2400, 100, 16))
        // Clear of the very top and bottom, and never off the sides.
        assertEquals(0.06f, ButtonSpot.at(100f, 0f, 1080f, 2400f).yFraction, 0.0001f)
        assertEquals(0.94f, ButtonSpot.at(100f, 2400f, 1080f, 2400f).yFraction, 0.0001f)
        assertEquals(0f, ButtonSpot.at(-50f, 500f, 1080f, 2400f).xFraction, 0f)
        assertEquals(16 to 0, ButtonSpot(0f, 0f).corner(1080, 2400, 100, 16))
        assertEquals(1080 - 100 - 16 to 2400 - 100, ButtonSpot(1f, 1f).corner(1080, 2400, 100, 16))
        // The default is the right edge, where older phones kept it; a rotation keeps the same fraction of the screen.
        assertEquals(1080 - 100 - 16, ButtonSpot().corner(1080, 2400, 100, 16).first)
        val landscape = spot.corner(2400, 1080, 100, 16)
        assertEquals(1200 - 50 to 540 - 50, landscape)
        // A screen smaller than the button still gives a place on it.
        assertEquals(0 to 0, ButtonSpot(0.5f, 0.5f).corner(80, 80, 100, 16))
    }

    @Test fun `listening says so once, in the large caption`() {
        val listening = VoiceFace.of(VoiceTurn().on(VoiceEvent.Tap).turn)
        assertEquals("Listening", listening.status)
        assertEquals(VoiceCopy.CAPTION_LISTENING, listening.cyclone)
        assertFalse(listening.showStatus)
        assertTrue(VoiceFace.repeats("Listening…", "Listening"))
        assertTrue(VoiceFace.repeats("Needs you: pay €24.90?", "Needs you"))
        assertFalse(VoiceFace.repeats("One moment…", "Thinking"))
        assertFalse(VoiceFace.repeats("Working on it", "Work"))
        assertFalse(VoiceFace.repeats("", "Listening"))
        // A different word stays: "Thinking" over "One moment…".
        val thinking = listening.copy(status = "Thinking", cyclone = VoiceCopy.CAPTION_THINKING)
        assertTrue(thinking.showStatus)
    }
}
