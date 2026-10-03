package com.cyclone.mobile.runtime.background

import java.util.UUID

/** A local user's one-shot consent, bound to the exact page and grounded action. */
data class WorkspaceConfirmation(
    val token: String = UUID.randomUUID().toString(),
    val action: String,
    val nodeId: String,
    val fingerprint: String,
    val kind: String,
) {
    val button get() = when (kind) {
        "send" -> "Send"
        "pay" -> "Confirm payment"
        "delete" -> "Delete"
        "grant" -> "Allow"
        else -> "Confirm"
    }
    val explanation get() = when (kind) {
        "send" -> "Review the recipient and prepared message in the live page before sending."
        "pay" -> "Review the total and payment details in the live page before confirming."
        "delete" -> "Review the selected content in the live page before deleting."
        "grant" -> "Review the requested access in the live page before allowing it."
        else -> "Review the prepared page before confirming."
    }
}
internal class WorkspaceConsent {
    var pending: WorkspaceConfirmation? = null; private set
    private var approved: WorkspaceConfirmation? = null
    private var expiresAt = 0L
    fun request(confirmation: WorkspaceConfirmation) { pending = confirmation; approved = null }
    fun approve(token: String, now: Long): Boolean {
        val challenge = pending?.takeIf { it.token == token } ?: return false
        approved = challenge; pending = null; expiresAt = now + 60_000
        return true
    }
    fun consume(action: String, nodeId: String, fingerprint: String, kind: String, now: Long): Boolean {
        val challenge = approved ?: return false
        approved = null // Any attempted use spends the grant, even when stale or mismatched.
        return now < expiresAt && challenge.action == action && challenge.nodeId == nodeId &&
            challenge.fingerprint == fingerprint && challenge.kind == kind
    }
    fun clear() { pending = null; approved = null }
}
