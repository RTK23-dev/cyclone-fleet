package com.cyclone.mobile.mind.mission

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

enum class OwnerRequestKind { QUESTION, APPROVAL, SECRET, CONTROL, VALUES }

/** One value the owner is asked to type on the check-in card. Never a secret: those go to the Secrets Card. */
data class OwnerField(val label: String, val kind: String = "text", val choices: List<String> = emptyList())

/**
 * A message Cyclone will send once approved, exactly as it will be sent (Drive, plan 32: a send is approved by voice
 * only after this exact text was read back). Only set where the sent text is this text by construction.
 */
data class OwnerSend(val text: String, val recipient: String, val app: String)

/** Something the running mission needs from the owner. Never carries a secret value. */
data class OwnerRequest(
    val id: String,
    val missionId: String,
    val kind: OwnerRequestKind,
    val text: String,
    val choices: List<String> = emptyList(),
    val createdAtMs: Long,
    val fields: List<OwnerField> = emptyList(),
    /** For an approval: its GATE class ("send", "pay", "delete", "grant"), when known. */
    val gate: String? = null,
    /** For a send approval: the exact message. */
    val send: OwnerSend? = null,
)

sealed class OwnerResponse {
    data class Answer(val text: String) : OwnerResponse()
    data object Approve : OwnerResponse()
    data object Decline : OwnerResponse()
    data object Done : OwnerResponse()
    /** The owner will do it by hand; the mission hands the phone over and waits for "I'm done". */
    data object TakeOver : OwnerResponse()
    /** Values typed on the check-in card, keyed by field label. */
    data class Values(val values: Map<String, String>, val remember: Boolean) : OwnerResponse()
}

data class OwnerWait(val response: OwnerResponse?, val waitedMs: Long, val cancelled: Boolean)

/**
 * Where missions wait for their owner. A mission thread posts a request and blocks; the phone UI (notification,
 * request screen, overlay, app) shows [pending] and answers through [respond].
 *
 * Each mission is one train of thought, so it has at most one open request: a new one replaces its previous one.
 * Several missions (plan 26 §6, parallel sessions) may each have one open; the owner sees them one at a time, oldest
 * first ([pending]), and [all] lists them. A mission working behind the owner's screen is named on its requests
 * ([label]) so the owner knows which task asks.
 */
class OwnerInbox(private val clock: () -> Long = System::currentTimeMillis) {
    private class Slot(val request: OwnerRequest) {
        var response: OwnerResponse? = null
    }

    private val lock = Object()
    private val open = LinkedHashMap<String, Slot>()
    private val labels = HashMap<String, String>()
    private val state = MutableStateFlow<OwnerRequest?>(null)
    private val allState = MutableStateFlow<List<OwnerRequest>>(emptyList())

    /** The request the owner sees now: the oldest open one. */
    val pending: StateFlow<OwnerRequest?> = state
    /** Every open request, oldest first. */
    val all: StateFlow<List<OwnerRequest>> = allState

    /** Names [missionId] on its requests ("For “Order the groceries”:"); null stops naming it (it came to the screen). */
    fun label(missionId: String, label: String?) = synchronized(lock) {
        if (label.isNullOrBlank()) labels.remove(missionId) else labels[missionId] = label.take(80)
    }

    fun post(missionId: String, kind: OwnerRequestKind, text: String, choices: List<String> = emptyList(),
             fields: List<OwnerField> = emptyList(), gate: String? = null, send: OwnerSend? = null): OwnerRequest =
        synchronized(lock) {
            val shown = labels[missionId]?.let { "$it $text" } ?: text
            val request = OwnerRequest("req-${UUID.randomUUID()}", missionId, kind, shown.take(600), choices.take(6), clock(), fields.take(MAX_FIELDS),
                gate, send)
            // A mission's new request replaces its previous one; other missions' requests stay open.
            open.entries.removeAll { it.value.request.missionId == missionId }
            open[request.id] = Slot(request)
            publish()
            lock.notifyAll()
            request
        }

    /** Returns false when [requestId] is no longer open (answered, withdrawn or replaced). */
    fun respond(requestId: String, reply: OwnerResponse): Boolean = synchronized(lock) {
        val slot = open[requestId] ?: return false
        if (slot.response != null) return false
        slot.response = reply
        lock.notifyAll()
        true
    }

    /** Blocks until the owner answers, [timeoutMs] passes or [cancelled] turns true; always clears the request. */
    fun await(request: OwnerRequest, timeoutMs: Long, cancelled: () -> Boolean): OwnerWait {
        val started = clock()
        try {
            synchronized(lock) {
                while (true) {
                    val slot = open[request.id] ?: return OwnerWait(null, clock() - started, cancelled = true)
                    slot.response?.let { return OwnerWait(it, clock() - started, cancelled = false) }
                    if (cancelled()) return OwnerWait(null, clock() - started, cancelled = true)
                    val left = timeoutMs - (clock() - started)
                    if (left <= 0) return OwnerWait(null, clock() - started, cancelled = false)
                    lock.wait(minOf(left, POLL_MS))
                }
            }
        } finally {
            withdraw(request.id)
        }
    }

    /** Non-blocking: the owner's reply to the open request, if any; the request stays open. */
    fun poll(requestId: String): OwnerResponse? = synchronized(lock) { open[requestId]?.response }

    /** The open request of [missionId], if it has one. */
    fun openFor(missionId: String): OwnerRequest? = synchronized(lock) { open.values.firstOrNull { it.request.missionId == missionId }?.request }

    fun withdraw(requestId: String) = synchronized(lock) {
        if (open.remove(requestId) != null) {
            publish()
            lock.notifyAll()
        }
    }

    /** Stops what one mission is waiting for (the owner stopped that mission). */
    fun withdrawMission(missionId: String) = synchronized(lock) {
        if (open.entries.removeAll { it.value.request.missionId == missionId }) publish()
        labels.remove(missionId)
        lock.notifyAll()
    }

    /** Stops whatever every mission is waiting for. */
    fun withdrawAll() = synchronized(lock) {
        open.clear()
        publish()
        lock.notifyAll()
    }

    private fun publish() {
        val list = open.values.map { it.request }
        allState.value = list
        state.value = list.firstOrNull()
    }

    private companion object {
        const val POLL_MS = 250L
        const val MAX_FIELDS = 8
    }
}
