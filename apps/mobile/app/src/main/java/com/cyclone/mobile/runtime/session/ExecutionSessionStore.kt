package com.cyclone.mobile.runtime.session

class SessionIdentityException(
    message: String,
    val errorClass: String = "SESSION_DISPLAY_MISMATCH",
) : IllegalArgumentException(message)

class ExecutionSessionStore {
    private val lock = Any()
    private val sessions = linkedMapOf<String, ExecutionSession>()

    init {
        val default = ExecutionSession.defaultForeground()
        sessions[default.sessionId] = default
    }

    fun defaultForeground(): ExecutionSession = synchronized(lock) {
        sessions.getValue(ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID)
    }

    fun lookup(sessionId: String?): ExecutionSession = synchronized(lock) {
        resolveLocked(sessionId)
    }

    fun requireSessionDisplay(sessionId: String?, displayId: Int? = null): ExecutionSession = synchronized(lock) {
        val session = resolveLocked(sessionId)
        val resolvedDisplayId = displayId ?: session.displayId
        if (resolvedDisplayId != session.displayId) {
            throw SessionIdentityException(
                "display/session mismatch: session ${session.sessionId} owns display ${session.displayId}, not $resolvedDisplayId",
            )
        }
        session
    }

    fun registerSynthetic(
        sessionId: String,
        displayId: Int,
        backend: ExecutionBackendKind,
        targetPackage: String? = null,
        inputOwner: InputOwner = InputOwner.CYCLONE,
        nowEpochMs: Long = 0L,
    ): ExecutionSession = synchronized(lock) {
        require(sessionId.isNotBlank()) { "sessionId required" }
        if (sessionId == ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID) {
            throw SessionIdentityException("cannot replace default foreground session")
        }
        if (sessions.containsKey(sessionId)) {
            throw SessionIdentityException("duplicate session identity: $sessionId")
        }
        if (displayId < 0) {
            throw SessionIdentityException("invalid displayId: $displayId")
        }

        val session = ExecutionSession(
            sessionId = sessionId,
            displayId = displayId,
            targetPackage = targetPackage,
            backend = backend,
            inputOwner = inputOwner,
            executable = false,
            createdAtEpochMs = nowEpochMs,
        )
        sessions[sessionId] = session
        session
    }

    internal fun registerOwned(sessionId: String, displayId: Int, targetPackage: String): ExecutionSession = synchronized(lock) {
        if (sessionId.isBlank() || sessionId == ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID) {
            throw SessionIdentityException("owned session requires a unique named identity")
        }
        if (displayId <= 0) {
            throw SessionIdentityException("owned session requires a unique nonzero displayId")
        }
        if (sessionId in sessions || sessions.values.any { it.displayId == displayId }) {
            throw SessionIdentityException("owned session requires a unique nonzero displayId")
        }
        ExecutionSession(sessionId, displayId, targetPackage, ExecutionBackendKind.SHIZUKU,
            InputOwner.CYCLONE, true, System.currentTimeMillis()).also { sessions[sessionId] = it }
    }

    internal fun setOwner(sessionId: String, owner: InputOwner): ExecutionSession = synchronized(lock) {
        val old = resolveLocked(sessionId)
        require(!old.isDefaultForeground)
        old.copy(inputOwner = owner).also { sessions[sessionId] = it }
    }

    fun remove(sessionId: String): ExecutionSession? = synchronized(lock) {
        if (sessionId == ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID) {
            throw SessionIdentityException("cannot remove default foreground session")
        }
        sessions.remove(sessionId)
    }

    fun snapshot(): List<ExecutionSession> = synchronized(lock) { sessions.values.toList() }

    private fun resolveLocked(sessionId: String?): ExecutionSession {
        val resolvedId = sessionId?.takeIf { it.isNotBlank() }
            ?: ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID
        return sessions[resolvedId] ?: throw SessionIdentityException("unknown session $resolvedId")
    }
}
