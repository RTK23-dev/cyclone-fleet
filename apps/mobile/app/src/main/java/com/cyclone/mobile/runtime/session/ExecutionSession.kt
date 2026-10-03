package com.cyclone.mobile.runtime.session

enum class ExecutionBackendKind {
    FOREGROUND_ACCESSIBILITY,
    SHIZUKU,
    ROOT,
    VIRTUAL_DISPLAY,
}

enum class InputOwner {
    HUMAN,
    CYCLONE,
    UNOWNED,
}

data class ExecutionSession(
    val sessionId: String,
    val displayId: Int,
    val targetPackage: String? = null,
    val backend: ExecutionBackendKind = ExecutionBackendKind.FOREGROUND_ACCESSIBILITY,
    val inputOwner: InputOwner = InputOwner.HUMAN,
    val executable: Boolean,
    val createdAtEpochMs: Long,
) {
    init {
        require(sessionId.isNotBlank()) { "sessionId required" }
        require(displayId >= 0) { "displayId must be non-negative" }
        require(createdAtEpochMs >= 0L) { "createdAtEpochMs must be non-negative" }

        if (sessionId == DEFAULT_FOREGROUND_SESSION_ID) {
            require(displayId == DEFAULT_DISPLAY_ID) { "default foreground session must use display 0" }
            require(backend == ExecutionBackendKind.FOREGROUND_ACCESSIBILITY) {
                "default foreground session must use foreground Accessibility"
            }
            require(executable) { "default foreground session must remain executable" }
        }

        if (executable && sessionId != DEFAULT_FOREGROUND_SESSION_ID) {
            require(displayId > 0 && backend == ExecutionBackendKind.SHIZUKU) {
                "Background execution requires an owned nonzero Shizuku display"
            }
        }
    }

    val isDefaultForeground: Boolean
        get() = sessionId == DEFAULT_FOREGROUND_SESSION_ID && displayId == DEFAULT_DISPLAY_ID

    companion object {
        const val DEFAULT_FOREGROUND_SESSION_ID = "default-foreground"
        const val DEFAULT_DISPLAY_ID = 0

        fun defaultForeground(nowEpochMs: Long = 0L): ExecutionSession = ExecutionSession(
            sessionId = DEFAULT_FOREGROUND_SESSION_ID,
            displayId = DEFAULT_DISPLAY_ID,
            backend = ExecutionBackendKind.FOREGROUND_ACCESSIBILITY,
            inputOwner = InputOwner.HUMAN,
            executable = true,
            createdAtEpochMs = nowEpochMs,
        )
    }
}

data class ExecutionContext(
    val sessionId: String = ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID,
    val displayId: Int = ExecutionSession.DEFAULT_DISPLAY_ID,
    val observationId: String? = null,
    val frameId: Long? = null,
) {
    init {
        require(sessionId.isNotBlank()) { "sessionId required" }
        require(displayId >= 0) { "displayId must be non-negative" }
        require(observationId == null || observationId.isNotBlank()) { "observationId must be non-blank when present" }
        require(frameId == null || frameId > 0L) { "frameId must be positive when present" }
    }

    companion object {
        val DEFAULT = ExecutionContext()

        fun from(session: ExecutionSession, observationId: String? = null, frameId: Long? = null) = ExecutionContext(
            sessionId = session.sessionId,
            displayId = session.displayId,
            observationId = observationId,
            frameId = frameId,
        )
    }
}
