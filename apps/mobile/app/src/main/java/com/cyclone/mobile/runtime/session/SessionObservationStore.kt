package com.cyclone.mobile.runtime.session

data class SessionObservationKey(
    val sessionId: String,
    val displayId: Int,
)

data class SessionObservationEnvelope(
    val sessionId: String,
    val displayId: Int,
    val observationId: String,
    val generation: Long,
    val payload: Any?,
    val timestampEpochMs: Long,
)

class SessionObservationStore(
    private val sessions: ExecutionSessionStore = ExecutionSessionStore(),
) {
    private val lock = Any()
    private val current = mutableMapOf<SessionObservationKey, SessionObservationEnvelope>()
    private val generations = mutableMapOf<String, Long>()

    fun publish(
        sessionId: String?,
        displayId: Int? = null,
        observationId: String,
        payload: Any?,
        timestampEpochMs: Long,
    ): SessionObservationEnvelope = synchronized(lock) {
        require(observationId.isNotBlank()) { "observationId required" }
        require(timestampEpochMs >= 0L) { "timestampEpochMs must be non-negative" }
        val session = sessions.requireSessionDisplay(sessionId, displayId)
        val key = SessionObservationKey(session.sessionId, session.displayId)
        val next = (generations[session.sessionId] ?: 0L) + 1L
        generations[session.sessionId] = next
        val envelope = SessionObservationEnvelope(
            sessionId = session.sessionId,
            displayId = session.displayId,
            observationId = observationId,
            generation = next,
            payload = payload,
            timestampEpochMs = timestampEpochMs,
        )
        current[key] = envelope
        envelope
    }

    fun current(sessionId: String?, displayId: Int? = null): SessionObservationEnvelope? = synchronized(lock) {
        val session = sessions.requireSessionDisplay(sessionId, displayId)
        current[SessionObservationKey(session.sessionId, session.displayId)]
    }

    fun currentOrLegacy(sessionId: String?): SessionObservationEnvelope? = current(sessionId)

    fun associate(sessionId: String?, displayId: Int? = null, observationId: String) = synchronized(lock) {
        require(observationId.isNotBlank()) { "observationId required" }
        val session = sessions.requireSessionDisplay(sessionId, displayId)
        val key = SessionObservationKey(session.sessionId, session.displayId)
        val existing = current[key] ?: throw SessionIdentityException("no observation for session ${session.sessionId}")
        if (existing.observationId != observationId) {
            throw SessionIdentityException(
                "foreign observation/session mismatch: requested $observationId, current ${existing.observationId}",
            )
        }
    }

    fun clear(sessionId: String?, displayId: Int? = null) = synchronized(lock) {
        val session = sessions.requireSessionDisplay(sessionId, displayId)
        current.remove(SessionObservationKey(session.sessionId, session.displayId))
        Unit
    }
}
