package com.cyclone.mobile

/**
 * Pre-dispatch / execution-scope failures must keep a stable code.
 * Dumping them into CAPABILITY_UNAVAILABLE made leftover Layer-2 leases look like
 * "this app cannot be launched" and killed the task.
 *
 * Token order is specific-before-generic: MUTATE_LOCK messages mention
 * workspaceGeneration, and GATE messages mention "human review".
 */
object PhoneToolScopeErrors {
    fun code(error: Exception): PhoneToolErrorCode {
        val reason = error.message.orEmpty().uppercase()
        return when {
            "MUTATE_LOCK" in reason || "LEASE" in reason || "QUEUE_EMPTY" in reason ->
                PhoneToolErrorCode.WORKSPACE_SCOPE_CONFLICT
            "TARGET_MISMATCH" in reason -> PhoneToolErrorCode.TARGET_SCOPE_MISMATCH
            "POLICY" in reason || "GATE" in reason -> PhoneToolErrorCode.POLICY_DENIED
            "HUMAN_HAS_CONTROL" in reason || "OWNS DEVICE INPUT" in reason || "OWNS INPUT" in reason ->
                PhoneToolErrorCode.HUMAN_HAS_CONTROL
            "STALE" in reason || "EXPIRED" in reason || "FRESH_OBSERVATION" in reason ->
                PhoneToolErrorCode.FRESH_OBSERVATION_REQUIRED
            "MISMATCH" in reason -> PhoneToolErrorCode.INVALID_REQUEST
            else -> PhoneToolErrorCode.ACTION_FAILED
        }
    }

    fun message(error: Exception): String {
        val token = error.message?.substringBefore(':')?.trim()?.take(80).orEmpty()
        return if (token.isNotBlank()) {
            "Execution scope unavailable ($token); observe the current session again."
        } else {
            "Execution scope unavailable; observe the current session again."
        }
    }
}
