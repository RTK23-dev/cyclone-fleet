package com.cyclone.mobile.policy

data class AuthorityGrant(
    val grantId: String,
    val subject: PrincipalRef,
    val authority: AuthorityClaim,
    val scope: ActionScope,
    val allowedRisks: Set<ActionRisk>,
    val issuedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val maximumUses: Int,
) {
    init {
        require(grantId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:-]*"))) { "Grant id is invalid" }
        require(authority.origin.canIssueGrant) { "${authority.origin} cannot issue authority" }
        require(allowedRisks.isNotEmpty()) { "A grant must enumerate at least one allowed risk" }
        require(issuedAtEpochMillis >= 0) { "Grant issue time must be non-negative" }
        require(expiresAtEpochMillis > issuedAtEpochMillis) { "A grant must have a finite positive lifetime" }
        require(maximumUses in 1..1_000) { "Grant use bound must be between 1 and 1000" }

        when (authority.origin) {
            AuthorityOrigin.CURRENT_CONFIRMATION ->
                require(maximumUses == 1) { "Current confirmation is always single-use" }

            AuthorityOrigin.DIRECT_USER_MISSION ->
                require(scope.missionId != null) { "Direct mission authority must be mission-scoped" }

            AuthorityOrigin.STANDING_USER_RULE ->
                require(scope.isBoundedStandingScope()) { "Standing user authority must have a target boundary" }

            else -> error("Untrusted authority cannot create a grant")
        }
    }
}

data class AuthorityGrantSnapshot(
    val grant: AuthorityGrant,
    val usesConsumed: Int,
    val revoked: Boolean,
) {
    val remainingUses: Int get() = (grant.maximumUses - usesConsumed).coerceAtLeast(0)
}

enum class GrantRevocationResult {
    REVOKED,
    ALREADY_REVOKED,
    NOT_FOUND,
}
