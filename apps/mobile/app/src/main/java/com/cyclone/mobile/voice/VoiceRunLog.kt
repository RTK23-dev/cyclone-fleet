package com.cyclone.mobile.voice

/**
 * Where a voice run's log goes. The phone writes it to Cyclone's run history (the same store as every task, so it shows
 * in Glass → Runs and in the run diagnostics); tests keep it in memory. Voice itself never touches that store.
 */
interface VoiceRunSink {
    /** Opens a run and returns its id. */
    fun begin(title: String, engine: String): String
    fun event(runId: String, kind: String, text: String, ok: Boolean? = null, detail: String? = null)
    fun end(runId: String, ok: Boolean, result: String)

    companion object {
        val NONE = object : VoiceRunSink {
            override fun begin(title: String, engine: String) = ""
            override fun event(runId: String, kind: String, text: String, ok: Boolean?, detail: String?) = Unit
            override fun end(runId: String, ok: Boolean, result: String) = Unit
        }
    }
}

/**
 * One voice run, from the tap to the moment the turn is free again: which ear listened, what was heard, how long each
 * stage took, where the request went, what came back and what Cyclone said. Every line carries "+N ms" since the tap,
 * so a slow run shows exactly which stage was slow.
 *
 * It logs the owner's own words (like the goal of any run) and Cyclone's lines, never audio, and never a value the
 * trace store would not keep (it redacts again on write). Pure; the clock is injectable.
 */
class VoiceRunLog(private val sink: VoiceRunSink, private val clock: () -> Long) {
    private var runId: String? = null
    private var startedAt = 0L
    private var lastAt = 0L
    private var heard: String? = null
    private var outcome: String? = null
    private var failed = false

    val open: Boolean get() = runId != null

    /** A new turn begins (a tap, or the mic reopening by itself after a quick action). Closes one still open. */
    fun begin(trigger: String, engine: String) {
        if (runId != null) end(null)
        startedAt = clock()
        lastAt = startedAt
        heard = null
        outcome = null
        failed = false
        runId = runCatching { sink.begin("Voice: $trigger", engine) }.getOrNull()
        note("LISTEN", "Listening with $engine ($trigger)")
    }

    /** A stage of the run: "+N ms" since the tap and, in detail, the time since the previous stage. */
    fun note(kind: String, text: String, ok: Boolean? = null, detail: String? = null) {
        val id = runId ?: return
        val now = clock()
        val line = "+${now - startedAt} ms · $text"
        val step = "step ${now - lastAt} ms"
        lastAt = now
        runCatching { sink.event(id, kind, line, ok, listOfNotNull(step, detail).joinToString(" · ")) }
    }

    fun heard(text: String, stageMs: Long?) {
        heard = text
        note("HEARD", "Heard: \"${text.take(200)}\"", ok = true, detail = stageMs?.let { "transcription $it ms" })
    }

    fun failed(why: String, detail: String? = null) {
        failed = true
        outcome = why
        note("VOICE_FAIL", why, ok = false, detail = detail)
    }

    fun outcome(text: String, ok: Boolean) {
        outcome = text
        if (!ok) failed = true
        note("OUTCOME", text, ok = ok)
    }

    /** The turn is free again: the run closes with what happened. */
    fun end(result: String?) {
        val id = runId ?: return
        note("END", "Turn ended")
        val summary = result ?: outcome ?: heard?.let { "Heard \"${it.take(120)}\"" } ?: "Nothing heard"
        runCatching { sink.end(id, !failed, "${summary.take(300)} (${clock() - startedAt} ms)") }
        runId = null
    }
}
