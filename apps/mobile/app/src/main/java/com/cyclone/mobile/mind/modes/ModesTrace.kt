package com.cyclone.mobile.mind.modes

import android.content.Context
import android.os.SystemClock

/**
 * Alpha.78: one modes run in Cyclone's run history (Glass → Runs, run diagnostics), with every stage timed: reading the
 * screen, routing, the move itself, a hand-up. A slow quick action then shows exactly where the time went. Holds the
 * owner's request (like any run's goal) and Cyclone's own step lines; never screen text or typed values. Logging never
 * breaks a run: every write is guarded.
 */
internal class ModesTrace(private val context: Context, request: String, voice: Boolean) {
    private val started = SystemClock.elapsedRealtime()
    private val lines = mutableListOf<String>()
    private val id: String? = runCatching {
        com.cyclone.mobile.ai.AgentTraceRuntime.initialize(context)
        com.cyclone.mobile.ai.AgentTraceRuntime.store.startSession(request, if (voice) "modes · voice" else "modes · ask")
    }.getOrNull()

    val steps: List<String> get() = lines.toList()

    fun step(text: String, ok: Boolean? = null) {
        val line = "+${SystemClock.elapsedRealtime() - started} ms · $text"
        lines += line
        id?.let { runCatching { com.cyclone.mobile.ai.AgentTraceRuntime.store.append(it, "MODES", line, code = "modes.step", ok = ok) } }
    }

    fun <T> time(what: String, block: () -> T): T {
        val t = SystemClock.elapsedRealtime()
        val result = block()
        step("$what: ${SystemClock.elapsedRealtime() - t} ms", (result as? InstantOutcome)?.let { it.done || it.cancelled })
        return result
    }

    fun finish(result: ModeResult) {
        val total = SystemClock.elapsedRealtime() - started
        val summary = "${result.mode.name.lowercase()}: " + when {
            result.promoted -> "handed to a mission"
            result.ok -> "done"
            else -> "failed"
        } + " in $total ms"
        lines += summary
        id?.let { runCatching { com.cyclone.mobile.ai.AgentTraceRuntime.store.finishSession(it, if (result.ok) "COMPLETED" else "FAILED", summary, 0) } }
    }
}
