package com.cyclone.mobile.mind.modes

import java.util.UUID

/**
 * Alpha 91: what became of each request sent through `ask.start` (Glass, the MCP, the Lab's router tests). Each one
 * gets an id; the router writes down which lane took it (instant, answer, ignore, flash, mind), who decided and how
 * fast; the run writes down when it finished and how. `ask.status {requestId}` reads it back, so a client can measure
 * a request and tell it apart from the task before it. Pure, bounded, in memory only: never the request text in a
 * status (the client sent it), never kept across a restart.
 */
object AskLedger {
    const val KEEP = 20
    /** A request is matched to its route only if routing starts this soon after it was sent. */
    const val MATCH_WINDOW_MS = 60_000L

    data class Entry(
        val requestId: String,
        val text: String,
        val startedAtMs: Long,
        val lane: String? = null,
        val decider: String? = null,
        val why: String? = null,
        val decideMs: Long = 0,
        val routedAtMs: Long? = null,
        val finishedAtMs: Long? = null,
        val ok: Boolean? = null,
        val say: String? = null,
        val cancelled: Boolean = false,
    ) {
        /** waiting (not routed yet), running, done, failed or cancelled. */
        val state: String get() = when {
            cancelled -> "cancelled"
            finishedAtMs != null -> if (ok == false) "failed" else "done"
            routedAtMs != null -> "running"
            else -> "waiting"
        }
    }

    private val lock = Any()
    private val entries = ArrayList<Entry>()

    fun begin(text: String, nowMs: Long = System.currentTimeMillis()): String = synchronized(lock) {
        val id = "req-" + UUID.randomUUID().toString().take(12)
        entries += Entry(id, text.trim(), nowMs)
        while (entries.size > KEEP) entries.removeAt(0)
        id
    }

    /** The router decided: [lane] is the mode's name, [decider] who settled it. */
    fun routed(text: String, lane: String, decider: String?, why: String?, decideMs: Long, nowMs: Long = System.currentTimeMillis()) =
        update(text, nowMs, MATCH_WINDOW_MS) { it.routedAtMs == null }?.let { entry ->
            replace(entry.copy(lane = lane, decider = decider, why = why?.take(160), decideMs = decideMs, routedAtMs = nowMs))
        }

    /**
     * The request's run ended. A request that went on as a Flash or Mind mission ends here as "handed over"; the
     * mission itself is then read from the task snapshot.
     */
    fun finished(text: String, lane: String, ok: Boolean, say: String?, nowMs: Long = System.currentTimeMillis()) =
        update(text, nowMs, Long.MAX_VALUE) { it.finishedAtMs == null && !it.cancelled }?.let { entry ->
            replace(entry.copy(lane = entry.lane ?: lane, routedAtMs = entry.routedAtMs ?: nowMs, finishedAtMs = nowMs, ok = ok, say = say?.take(240)))
        }

    /** The run went up to a Flash or Mind mission: the lane says so, and the request keeps running as that mission. */
    fun handed(text: String, lane: String, nowMs: Long = System.currentTimeMillis()) =
        update(text, nowMs, Long.MAX_VALUE) { it.finishedAtMs == null && !it.cancelled }?.let { entry ->
            replace(entry.copy(lane = lane, routedAtMs = entry.routedAtMs ?: nowMs))
        }

    fun cancel(requestId: String, nowMs: Long = System.currentTimeMillis()): Entry? = synchronized(lock) {
        val entry = entries.lastOrNull { it.requestId == requestId } ?: return null
        if (entry.finishedAtMs != null || entry.cancelled) return entry
        entry.copy(cancelled = true, finishedAtMs = nowMs).also(::replace)
    }

    fun get(requestId: String): Entry? = synchronized(lock) { entries.lastOrNull { it.requestId == requestId } }

    fun latest(): Entry? = synchronized(lock) { entries.lastOrNull() }

    fun clear() = synchronized(lock) { entries.clear() }

    private fun update(text: String, nowMs: Long, windowMs: Long, open: (Entry) -> Boolean): Entry? = synchronized(lock) {
        val wanted = text.trim()
        entries.lastOrNull { it.text == wanted && open(it) && nowMs - it.startedAtMs <= windowMs }
    }

    private fun replace(entry: Entry) = synchronized(lock) {
        val index = entries.indexOfLast { it.requestId == entry.requestId }
        if (index >= 0) entries[index] = entry
    }
}
