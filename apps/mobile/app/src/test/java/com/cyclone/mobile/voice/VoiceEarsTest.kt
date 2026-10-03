package com.cyclone.mobile.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Alpha.78: an ear that fails hands over in the same turn, once, and a broken recognizer never traps the session. */
class VoiceEarsTest {
    @Test fun `the recording listens first unless the owner chose on-device recognition`() {
        assertEquals(VoiceEars.Ear.RECORDER, VoiceEars().start(onDeviceSetting = false))
        assertEquals(VoiceEars.Ear.RECOGNIZER, VoiceEars().start(onDeviceSetting = true))
    }

    @Test fun `a deaf recording hands to the recognizer in the same turn and for the session`() {
        val ears = VoiceEars()
        ears.start(false)
        assertEquals(VoiceEars.Ear.RECOGNIZER, ears.recorderDeaf())
        assertEquals(VoiceEars.Ear.RECOGNIZER, ears.start(false))
    }

    @Test fun `a broken recognizer hands back to the recording, so taps never loop on the same failure`() {
        val ears = VoiceEars()
        ears.start(false)
        ears.recorderDeaf()
        // The recognizer fails at once (the alpha.77 "I didn't catch that" loop): the recording tries this turn...
        assertEquals(VoiceEars.Ear.RECORDER, ears.start(false).let { ears.recognizerBroken() })
        // ...and is the ear on the next tap.
        assertEquals(VoiceEars.Ear.RECORDER, ears.start(false))
    }

    @Test fun `each ear gets one try per turn`() {
        val ears = VoiceEars()
        ears.start(false)
        ears.recorderDeaf()
        assertNull(ears.recognizerBroken())
    }
}
