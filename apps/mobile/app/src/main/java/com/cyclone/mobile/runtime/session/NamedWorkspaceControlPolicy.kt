package com.cyclone.mobile.runtime.session

/**
 * Fail-closed mutate / identity policy for named-VD Fast Path and compiled skills.
 * GATE, Take control, and Continue stay product-owned: this object does not rewrite overlay copy
 * or move a named session onto default-foreground / display 0.
 */
object NamedWorkspaceControlPolicy {
    const val TAKE_CONTROL = "Take control"
    const val CONTINUE = "Continue with Cyclone"
    const val RESUME = "Resume"
    const val GATE_COPY = "Cyclone needs you to confirm before finishing this."
    const val USER_PAUSED = "USER_PAUSED"
    const val GATE_REQUIRED = "GATE_REQUIRED"

    data class Decision(
        val allowMutate: Boolean,
        val errorClass: String? = null,
        val detail: String? = null,
    )

    /** Fail-closed mutate gate for named VD Fast Path / compiled skills. */
    fun mayMutate(
        sessionId: String,
        displayId: Int,
        userPaused: Boolean,
        gateRequired: Boolean,
    ): Decision {
        if (!isNamedWorkspace(sessionId, displayId)) {
            return Decision(
                allowMutate = false,
                errorClass = SessionContract.SESSION_DISPLAY_MISMATCH,
                detail = "Named-VD Fast Path requires sessionId != default-foreground and displayId > 0",
            )
        }
        if (userPaused) {
            return Decision(
                allowMutate = false,
                errorClass = USER_PAUSED,
                detail = "Take control paused Cyclone; do not act",
            )
        }
        if (gateRequired) {
            return Decision(
                allowMutate = false,
                errorClass = GATE_REQUIRED,
                detail = "GATE_CONFIRM is required; never synthesize approval / pcAutoApprove",
            )
        }
        return Decision(allowMutate = true)
    }

    /**
     * Continue / resume must keep the named VD identity.
     * Never rewrite to default-foreground / display 0.
     */
    fun continueIdentity(sessionId: String, displayId: Int): ExecutionContext {
        if (!isNamedWorkspace(sessionId, displayId)) {
            throw SessionIdentityException(
                "Continue / resume must keep the named VD identity; never rewrite to default-foreground / display 0",
                SessionContract.SESSION_DISPLAY_MISMATCH,
            )
        }
        return ExecutionContext(sessionId, displayId)
    }

    /**
     * Human Take control may move the Android task to display 0, but Cyclone must not
     * keep injecting on display 0 for that named session.
     */
    fun takeControlRelinquishInject(sessionId: String, displayId: Int): Boolean {
        // displayId is the named workspace display, which may later be 0 after human take-over.
        // Cyclone still relinquishes inject for that named session.
        return isNamedSession(sessionId) && displayId >= ExecutionSession.DEFAULT_DISPLAY_ID
    }

    private fun isNamedSession(sessionId: String): Boolean =
        sessionId.isNotBlank() && sessionId != ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID

    private fun isNamedWorkspace(sessionId: String, displayId: Int): Boolean =
        isNamedSession(sessionId) && displayId > 0
}
