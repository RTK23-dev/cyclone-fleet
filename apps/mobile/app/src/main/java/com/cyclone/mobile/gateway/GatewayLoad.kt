package com.cyclone.mobile.gateway

/**
 * Keeps the phone answering while it is stuck on slow work. The socket server runs requests on a few worker threads;
 * when all but one are held by requests that have run for [stuckMs], a new request gets `PHONE_APP_BUSY` at once
 * (with the op that is holding things up) instead of queueing behind them until the PC times out and calls the
 * phone disconnected. Health and status reads always run, so the PC can still tell "busy" from "gone".
 */
internal class GatewayLoad(private val slots: Int, private val stuckMs: Long) {
    data class Busy(val op: String, val forMs: Long)

    private data class Running(val op: String, val startedAtMs: Long)

    private val running = HashMap<Long, Running>()
    private var next = 0L

    /** A ticket for [op], or the reason it may not start now. */
    @Synchronized
    fun enter(op: String, nowMs: Long): Pair<Long?, Busy?> {
        if (op !in ALWAYS && running.size >= (slots - 1).coerceAtLeast(1)) {
            val oldest = running.values.minByOrNull { it.startedAtMs }
            if (oldest != null && nowMs - oldest.startedAtMs >= stuckMs) return null to Busy(oldest.op, nowMs - oldest.startedAtMs)
        }
        val ticket = ++next
        running[ticket] = Running(op, nowMs)
        return ticket to null
    }

    @Synchronized
    fun exit(ticket: Long) {
        running.remove(ticket)
    }

    @Synchronized
    fun inFlight(): Int = running.size

    companion object {
        /** Cheap reads that must answer even when the phone is busy, so health is always observable. */
        val ALWAYS = setOf("bridge.status", "health.report", "session.list", "trust.negotiate", "trust.session.begin", "trust.session.complete")
        const val STUCK_MS = 3_000L
        const val RETRY_AFTER_MS = 1_000L
    }
}
