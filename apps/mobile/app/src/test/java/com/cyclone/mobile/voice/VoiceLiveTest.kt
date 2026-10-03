package com.cyclone.mobile.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan 42 (Live voice): quick commands go straight to the router, succeed silently and keep the mic open. */
class VoiceLiveTest {
    private class Run(var turn: VoiceTurn = VoiceTurn()) {
        val effects = mutableListOf<VoiceEffect>()
        fun on(event: VoiceEvent): List<VoiceEffect> {
            val step = turn.on(event)
            turn = step.turn
            effects += step.effects
            return step.effects
        }
        fun said(text: String): List<VoiceEffect> {
            on(VoiceEvent.Tap)
            on(VoiceEvent.Heard)
            return on(VoiceEvent.Transcript(text))
        }
        val understandCalls get() = effects.count { it is VoiceEffect.Understand }
        val spoken get() = effects.filterIsInstance<VoiceEffect.Say>().map { it.line }
    }

    @Test fun `open my camera goes to the router with no model call and no words`() {
        val r = Run()
        assertEquals(listOf(VoiceEffect.Quick("open my camera")), r.said("open my camera"))
        assertEquals(0, r.understandCalls)
        assertTrue(r.spoken.isEmpty())
        assertTrue(r.turn.quickLive)
    }

    @Test fun `a silent success chimes and keeps listening for eight seconds`() {
        val r = Run()
        r.said("swipe up")
        val effects = r.on(VoiceEvent.QuickDone(ok = true, say = null, promoted = false))
        assertEquals(listOf(VoiceEffect.Play(Earcon.DONE), VoiceEffect.KeepListening(8_000)), effects)
        assertEquals(VoicePhase.LISTENING, r.turn.phase)
        assertTrue(r.turn.keptOpen)
        assertTrue(r.spoken.isEmpty())
    }

    @Test fun `the rest of the sentence in the window is the next command`() {
        val r = Run()
        r.said("open my camera")
        r.on(VoiceEvent.QuickDone(true, null, false))
        r.on(VoiceEvent.Heard)
        assertEquals(listOf(VoiceEffect.Quick("take a selfie")), r.on(VoiceEvent.Transcript("and take a selfie")))
    }

    @Test fun `silence after a quick action closes quietly`() {
        val r = Run()
        r.said("swipe up")
        r.on(VoiceEvent.QuickDone(true, null, false))
        assertEquals(emptyList<VoiceEffect>(), r.on(VoiceEvent.NothingHeard))
        assertEquals(VoicePhase.CLOSED, r.turn.phase)
        assertFalse(r.turn.keptOpen)
    }

    @Test fun `a command handed up is confirmed like a task`() {
        val r = Run()
        r.said("call my mom")
        r.on(VoiceEvent.QuickDone(true, null, promoted = true))
        assertEquals(listOf(VoiceCopy.DEFAULT_ACK), r.spoken)
        assertTrue(r.turn.taskLive)
    }

    @Test fun `a failure is said and closes`() {
        val r = Run()
        r.said("open my camera")
        r.on(VoiceEvent.QuickDone(false, "No camera app on this phone", false))
        assertTrue(r.spoken.single().startsWith("That didn't work"))
        assertFalse(r.turn.keptOpen)
    }

    @Test fun `a real request still gets the understanding model`() {
        val r = Run()
        r.said("message Louella on Instagram that I'm late")
        assertEquals(1, r.understandCalls)
        assertFalse(r.turn.quickLive)
    }

    @Test fun `always mind turns quick commands off`() {
        val r = Run(VoiceTurn(quickCommands = false))
        r.said("swipe up")
        assertEquals(1, r.understandCalls)
    }

    @Test fun `timers keep their spoken confirmation`() {
        val r = Run()
        r.said("set a timer for 5 minutes")
        assertTrue(r.effects.any { it is VoiceEffect.Submit })
        assertFalse(r.turn.quickLive)
    }

    @Test fun `with the window off a success just chimes`() {
        val r = Run(VoiceTurn(keepListeningMs = 0))
        r.said("swipe up")
        assertEquals(listOf(VoiceEffect.Play(Earcon.DONE)), r.on(VoiceEvent.QuickDone(true, null, false)))
        assertEquals(VoicePhase.CLOSED, r.turn.phase)
    }

    @Test fun `continuations drop the joining word or the no`() {
        assertEquals("take a selfie", VoiceQuick.continuation("and take a selfie"))
        assertEquals("open Spotify", VoiceQuick.continuation("no, open Spotify"))
        assertEquals("en dan", VoiceQuick.continuation("en dan"))
        assertEquals("swipe up", VoiceQuick.continuation("swipe up"))
    }

    @Test fun `voice mode keeps its panel and Stop up while a quick command runs`() {
        val r = Run()
        r.said("swipe down")
        assertTrue(r.turn.panelOpen)
        assertTrue(VoiceFace.of(r.turn).stop)
        assertFalse(VoiceFace.of(r.turn).dim)
        r.on(VoiceEvent.Stop)
        assertFalse(r.turn.quickLive)
        assertFalse(r.turn.panelOpen)
    }
}
