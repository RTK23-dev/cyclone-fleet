package com.cyclone.mobile.agent.contract

/** Bounded failure facts for the next model turn. No exception or parameter values are echoed. */
object HarnessFailureCopy {
    fun describe(code: String?): String = when (code) {
        "STALE_OBSERVATION", "STALE_ELEMENT", "FRESH_OBSERVATION_REQUIRED" ->
            "The observation or target is stale. Obtain a fresh observation in this same session before choosing another target."
        "HUMAN_HAS_CONTROL" -> "The human owns input. Wait for an explicit resume, then re-observe and reconcile the current page."
        "PHONE_UNAVAILABLE", "DEVICE_DISCONNECTED", "BACKEND_DISCONNECTED", "BRIDGE_UNAVAILABLE" ->
            "The phone connection is unavailable. No resulting state is verified. Restore the connection and observe before continuing."
        "ACCESSIBILITY_NOT_CONNECTED", "ACCESSIBILITY_UNAVAILABLE" ->
            "Phone control cannot observe the page because Accessibility is disconnected. Wait for access to be restored."
        "POLICY_DENIED", "GATE_REQUIRED", "SECURITY_RESTRICTION" ->
            "The action requires human review or is outside the allowed policy. Do not retry it through another input path."
        "AUTH_REJECTED", "AUTH_REQUIRED", "INVALID_TOKEN", "TRUST_REQUIRED" ->
            "Authorization is missing or expired. Ask the human to restore access; do not request credentials in model context."
        "ASSERTION_FAILED", "VERIFICATION_FAILED", "NO_SEMANTIC_PROGRESS" ->
            "The action was dispatched, but its required after-state was not demonstrated. Re-observe before choosing a different action."
        "APP_NOT_FOUND" ->
            "That app is not installed. Observe the current page, then try another installed app or the website."
        "WORKSPACE_SCOPE_CONFLICT", "MUTATE_LOCK" ->
            "A leftover workspace lease blocked this foreground action. Observe the current session and retry."
        "TARGET_SCOPE_MISMATCH", "TARGET_MISMATCH" ->
            "The workspace target no longer matches. Observe the current session before choosing another action."
        "TIMEOUT" -> "The action timed out; execution may have occurred. Observe before deciding what remains to do."
        else -> "The operation did not establish a verified result. Observe the current page before choosing the next action."
    }
}
