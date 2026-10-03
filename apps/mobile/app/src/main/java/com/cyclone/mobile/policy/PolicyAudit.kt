package com.cyclone.mobile.policy

enum class PolicyReason(val explanation: String) {
    AUTHORITY_ACCEPTED("A current, bounded user authority grant matched the proposed action."),
    AUTHORITY_REQUIRED("No current bounded user authority grant matched the proposed action."),
    CURRENT_CONFIRMATION_REQUIRED("This risk category requires a fresh single-use user confirmation."),
    UNTRUSTED_AUTHORITY("Environment or agent-provided data attempted to act as user authority."),
    GRANT_NOT_FOUND("The requested authority grant does not exist."),
    GRANT_REVOKED("The requested authority grant was revoked."),
    GRANT_EXPIRED("The requested authority grant has expired."),
    GRANT_EXHAUSTED("The requested authority grant has no remaining uses."),
    GRANT_SCOPE_MISMATCH("The proposed action is outside the grant's exact scope."),
    GRANT_RISK_MISMATCH("The proposed action risk is outside the grant's allowed risks."),
    DELEGATION_INVALID("The delegation chain is broken, cyclic, or wider than its parent authority."),
}

/**
 * Safe diagnostic record. It intentionally excludes target IDs, attribute values, evidence text,
 * user-entered values and opaque authority references.
 */
data class PolicyAuditRecord(
    val actionId: String,
    val capability: String,
    val risk: ActionRisk,
    val decision: PolicyDecision,
    val reason: PolicyReason,
    val explanation: String,
    val principalKind: PrincipalKind,
    val delegationDepth: Int,
    val packageName: String?,
    val targetType: String?,
    val authorityOrigins: List<AuthorityOrigin>,
    val contextOrigins: List<AuthorityOrigin>,
    val grantReference: String?,
    val evaluatedAtEpochMillis: Long,
)

data class PolicyAuthorization(
    val authorizationId: String,
    val actionId: String,
    val capability: String,
    val grantId: String,
    val expiresAtEpochMillis: Long,
    val singleUse: Boolean,
)

data class PolicyEvaluation(
    val decision: PolicyDecision,
    val authorization: PolicyAuthorization?,
    val audit: PolicyAuditRecord,
) {
    init {
        require((decision == PolicyDecision.ALLOW || decision == PolicyDecision.ALLOW_ONCE) == (authorization != null)) {
            "Only allowed decisions carry an authorization"
        }
    }

    val isAllowed: Boolean get() = authorization != null
}
