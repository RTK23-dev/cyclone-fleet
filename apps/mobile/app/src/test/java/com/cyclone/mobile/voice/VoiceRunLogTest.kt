package com.cyclone.mobile.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Alpha.78: every voice run is logged stage by stage with its time since the tap. */
class VoiceRunLogTest {
    private class Memory : VoiceRunSink {
        val runs = mutableListOf<Pair<String, String>>()
        val events = mutableListOf<Triple<String, String, String?>>()
        val ends = mutableListOf<Pair<Boolean, String>>()
        override fun begin(title: String, engine: String): String { runs += title to engine; return "run${runs.size}" }
        override fun event(runId: String, kind: String, text: String, ok: Boolean?, detail: String?) { events += Triple(kind, text, detail) }
        override fun end(runId: String, ok: Boolean, result: String) { ends += ok to result }
    }

    @Test fun `a quick command is logged with its timings`() {
        var now = 0L
        val sink = Memory()
        val log = VoiceRunLog(sink) { now }
        log.begin("a tap", "Cyclone's recording")
        now = 900; log.note("CLIP", "Speech ended")
        now = 1_400; log.heard("swipe down", 500)
        now = 1_410; log.note("QUICK", "Quick command")
        now = 2_300; log.outcome("Done", true)
        log.end(null)
        assertEquals("Voice: a tap" to "Cyclone's recording", sink.runs.single())
        assertTrue(sink.events.any { it.first == "HEARD" && it.second == "+1400 ms · Heard: \"swipe down\"" })
        assertTrue(sink.events.first { it.first == "HEARD" }.third!!.contains("transcription 500 ms"))
        assertEquals(true to "Done (2300 ms)", sink.ends.single())
        assertFalse(log.open)
    }

    @Test fun `a failure makes the run failed and a new tap closes an open run`() {
        val sink = Memory()
        val log = VoiceRunLog(sink) { 0 }
        log.begin("a tap", "Android's recognizer")
        log.failed("Neither ear could listen: not_heard", "Android on-device error 5; standard error 5")
        log.begin("a tap", "Cyclone's recording")
        assertEquals(false, sink.ends.single().first)
        assertTrue(sink.events.any { it.third?.contains("error 5") == true })
    }

    @Test fun `nothing is logged outside a run and a broken sink never breaks the turn`() {
        val log = VoiceRunLog(object : VoiceRunSink {
            override fun begin(title: String, engine: String): String = error("disk full")
            override fun event(runId: String, kind: String, text: String, ok: Boolean?, detail: String?) = error("x")
            override fun end(runId: String, ok: Boolean, result: String) = error("x")
        }) { 0 }
        log.note("X", "outside a run")
        log.begin("a tap", "ear")
        log.note("X", "inside")
        log.end(null)
    }
}
