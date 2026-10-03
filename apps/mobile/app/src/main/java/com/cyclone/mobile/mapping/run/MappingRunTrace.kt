package com.cyclone.mobile.mapping.run

import android.content.Context
import com.cyclone.mobile.ai.AgentTraceRuntime
import com.cyclone.mobile.mapping.session.MappingJob
import com.cyclone.mobile.mapping.session.MappingSessionState

/** One line of a mapping run's trace, in the same shape the Ask agent writes (so RunInsight reads both). */
data class MappingTraceLine(
    val kind: String,
    val text: String,
    val code: String,
    val ok: Boolean?,
    val detail: String?,
)

/**
 * Mapping passes are runs too (V5 plan 11): every pass is recorded in the run trace, so Glass Runs lists it and the
 * run inspector explains how it ended (stopped by you, login wall, you took the phone, finished). Structural only:
 * door kinds, room purpose words and structural room keys; never labels or screen text.
 */
object MappingRunTrace {
    const val MODEL = "cyclone-mapper"

    /** Translate one driver event. Door steps are decision turns (`TOOL_REQUESTED` with a `mapper:` action). */
    fun lines(event: MappingDriverEvent, placeId: String, appVersion: String?): List<MappingTraceLine> {
        val place = listOfNotNull("place=$placeId", appVersion?.let { "appv=$it" }).joinToString(" · ")
        val kind = event.detail.substringBefore(':')
        val word = event.detail.substringAfter(':', "")
        return when (event.kind) {
            "progress" -> listOf(
                MappingTraceLine("TOOL_REQUESTED", "Opening a ${door(kind)} door", "tool.requested", null, "action=mapper:$kind · $place"),
                MappingTraceLine("VERIFY", "Reached a ${room(word)}", "verify.progress", true, event.roomKey?.let { "roomAfter=$it" }),
            )
            "no_progress" -> listOf(
                MappingTraceLine("TOOL_REQUESTED", "Trying a ${door(kind)} door", "tool.requested", null, "action=mapper:$kind · $place"),
                MappingTraceLine("VERIFICATION", "The door led nowhere new (${word.replace('_', ' ')})", "mapping.no_progress", false, null),
            )
            "room_exhausted" -> listOf(MappingTraceLine("PLAN", "Every safe door of this ${room(event.detail)} is known", "mapping.room_done", true, event.roomKey?.let { "room=$it" }))
            "navigate" -> listOf(
                MappingTraceLine("TOOL_REQUESTED", if (kind == "back") "Going back" else "Opening the app at its first screen", "tool.requested", null, "action=mapper:navigate-$kind · $place"),
                MappingTraceLine("ANDROID_EXECUTION", if (word == "ok") "Done on the phone" else "Didn't happen ($word)", "executor.${if (word == "ok") "ok" else "failed"}", word == "ok", null),
            )
            "paused" -> when (event.detail) {
                "secret", "needs_secret", "secret_required" -> listOf(MappingTraceLine("GATE_SUSPEND", "Mapping paused at a login wall. Waiting for the Secrets Card on the phone.", "gate.need_secret", null, null))
                "human_control", "human" -> listOf(MappingTraceLine("ACTION_REJECTED", "Paused: you have the phone", "HUMAN_HAS_CONTROL", false, null))
                else -> listOf(MappingTraceLine("GATE_SUSPEND", "Mapping paused (${event.detail.replace('_', ' ')})", "mapping.paused", null, null))
            }
            "partial" -> listOf(MappingTraceLine("PLAN", "Stopped with part of the app mapped (${event.detail.replace('_', ' ')})", "mapping.partial", true, null))
            "failed", "error" -> listOf(MappingTraceLine("HARD_BLOCKER", "Mapping could not continue: ${event.detail.replace('_', ' ')}", "mapping.failed", false, null))
            "complete" -> listOf(MappingTraceLine("DONE", "Mapping finished: ${event.detail.substringAfter(':')} (${event.detail.substringBefore(':').replace('_', ' ')})", "task.finish", true, null))
            else -> emptyList()
        }
    }

    /** Trace session status for a parked or finished job. */
    fun status(job: MappingJob?): String = when (job?.state) {
        MappingSessionState.COMPLETED -> "COMPLETED"
        MappingSessionState.STOPPED -> "CANCELLED"
        MappingSessionState.FAILED, null -> "FAILED"
        MappingSessionState.RUNNING -> "RUNNING"
        MappingSessionState.PAUSED, MappingSessionState.NEEDS_SECRET, MappingSessionState.HUMAN_CONTROL -> "SUSPENDED"
    }

    private fun door(kind: String): String = kind.replace('_', ' ').ifBlank { "screen" }
    private fun room(word: String): String = "${word.replace('_', ' ').ifBlank { "new" }} screen"

    /** The Android sink: one trace session per mapping job. No overlay, no model. */
    class Session(private val context: Context, label: String, private val placeId: String, private val appVersion: String?) {
        val id: String
        private var decisions = 0

        init {
            AgentTraceRuntime.initialize(context)
            id = AgentTraceRuntime.store.startSession("Map $label", MODEL)
            AgentTraceRuntime.event(context, id, "START", "Mapping $label on its own", code = "task.start", ok = true)
        }

        fun record(event: MappingDriverEvent) {
            lines(event, placeId, appVersion).forEach { line ->
                if (line.kind == "TOOL_REQUESTED") decisions++
                AgentTraceRuntime.event(context, id, line.kind, line.text, code = line.code, ok = line.ok, detail = line.detail)
            }
        }

        fun park(job: MappingJob?) {
            when (val status = status(job)) {
                "RUNNING" -> Unit
                "SUSPENDED" -> AgentTraceRuntime.store.setLiveStatus(id, "SUSPENDED", "Mapping paused")
                else -> {
                    if (status == "CANCELLED") AgentTraceRuntime.event(context, id, "CANCELLED", "Stopped by you", code = "Request stopped by you.")
                    AgentTraceRuntime.finish(context, id, status, if (status == "COMPLETED") "The app is mapped." else "Mapping ended: ${job?.state?.wireValue ?: "unknown"}", decisions)
                }
            }
        }

        fun resumed() {
            AgentTraceRuntime.event(context, id, "GATE_RESUME", "Mapping resumed", code = "gate.resume", ok = true)
        }
    }
}
