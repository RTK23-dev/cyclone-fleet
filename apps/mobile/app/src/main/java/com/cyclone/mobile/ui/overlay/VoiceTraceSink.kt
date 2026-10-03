package com.cyclone.mobile.ui.overlay

import android.content.Context
import com.cyclone.mobile.ai.AgentTraceRuntime
import com.cyclone.mobile.voice.VoiceRunSink
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Alpha.78: Drive's voice runs in Cyclone's run history, next to every task: Glass → Runs and the run diagnostics show
 * each turn stage by stage with its timings. Writes happen on one background thread, in order, so the voice loop never
 * waits on the database; a failed write is dropped, never a failed turn. The store redacts again on write.
 */
internal class VoiceTraceSink(context: Context) : VoiceRunSink {
    private val app = context.applicationContext
    private val io get() = IO
    private val sessions get() = SESSIONS

    private companion object {
        /** One writer for all voice sessions, in order; Drive restarting never leaves threads behind. */
        val IO = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "cyclone-voice-log").apply { isDaemon = true } }
        /** A voice run's own token → the trace session id, set once the session row exists. */
        val SESSIONS = ConcurrentHashMap<String, String>()
    }

    override fun begin(title: String, engine: String): String {
        val token = UUID.randomUUID().toString()
        io.execute {
            runCatching {
                AgentTraceRuntime.initialize(app)
                sessions[token] = AgentTraceRuntime.store.startSession(title, "voice · $engine")
            }
        }
        return token
    }

    override fun event(runId: String, kind: String, text: String, ok: Boolean?, detail: String?) {
        io.execute {
            val id = sessions[runId] ?: return@execute
            runCatching { AgentTraceRuntime.store.append(id, kind, text, code = "voice", ok = ok, detail = detail) }
        }
    }

    override fun end(runId: String, ok: Boolean, result: String) {
        io.execute {
            val id = sessions.remove(runId) ?: return@execute
            runCatching { AgentTraceRuntime.store.finishSession(id, if (ok) "COMPLETED" else "FAILED", result, 0) }
            runCatching { com.cyclone.mobile.ai.AgentRunDiagnosticV39.ensureCanonical(app, id) }
        }
    }
}
