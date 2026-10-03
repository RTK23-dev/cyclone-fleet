package com.cyclone.mobile.policy

import java.security.MessageDigest

fun interface PolicyClock {
    fun nowEpochMillis(): Long
}

interface PolicyGovernor {
    fun issueGrant(grant: AuthorityGrant): AuthorityGrantSnapshot
    fun revokeGrant(grantId: String): GrantRevocationResult
    fun inspectGrant(grantId: String): AuthorityGrantSnapshot?
    fun evaluate(request: PolicyRequest): PolicyEvaluation
}

enum class PolicyAuthorizationClaimFailure {
    NOT_ISSUED,
    MISMATCH,
    EXPIRED,
    REPLAYED,
}

sealed interface PolicyAuthorizationClaimResult {
    data object Claimed : PolicyAuthorizationClaimResult
    data class Rejected(val reason: PolicyAuthorizationClaimFailure) : PolicyAuthorizationClaimResult
}

/**
 * Production executor handoff seam. A policy evaluation issues an authorization, but only an
 * exact, atomically claimed authorization may cross into the canonical phone executor.
 */
fun interface PolicyAuthorizationClaimer {
    fun claimAuthorization(authorization: PolicyAuthorization): PolicyAuthorizationClaimResult
}

/**
 * Thread-safe, fail-closed Layer-0 authority engine. Grant evaluation and use consumption happen in
 * one monitor. Issued executor authorizations are separately claimable exactly once so a captured
 * handoff cannot be replayed or forged by reconstructing its public fields.
 */
class InMemoryPolicyGovernor(
    private val clock: PolicyClock = PolicyClock(System::currentTimeMillis),
) : PolicyGovernor, PolicyAuthorizationClaimer {
    private data class StoredGrant(
        val grant: AuthorityGrant,
        var usesConsumed: Int = 0,
        var revoked: Boolean = false,
    )

    private val grants = linkedMapOf<String, StoredGrant>()
    private val issuedAuthorizations = linkedMapOf<String, PolicyAuthorization>()
    private val claimedAuthorizations = linkedSetOf<String>()

    @Synchronized
    override fun issueGrant(grant: AuthorityGrant): AuthorityGrantSnapshot {
        require(grant.grantId !in grants) { "Grant already exists: ${grant.grantId}" }
        require(grant.expiresAtEpochMillis > clock.nowEpochMillis()) { "Cannot issue an already expired grant" }
        val stored = StoredGrant(grant)
        grants[grant.grantId] = stored
        return stored.snapshot()
    }

    @Synchronized
    override fun revokeGrant(grantId: String): GrantRevocationResult {
        val stored = grants[grantId] ?: return GrantRevocationResult.NOT_FOUND
        if (stored.revoked) return GrantRevocationResult.ALREADY_REVOKED
        stored.revoked = true
        return GrantRevocationResult.REVOKED
    }

    @Synchronized
    override fun inspectGrant(grantId: String): AuthorityGrantSnapshot? = grants[grantId]?.snapshot()

    @Synchronized
    override fun evaluate(request: PolicyRequest): PolicyEvaluation {
        val evaluatedAt = clock.nowEpochMillis()
        pruneAuthorizations(evaluatedAt)
        if (request.authorityClaims.any { !it.origin.canIssueGrant }) {
            return blocked(request, PolicyDecision.DENY, PolicyReason.UNTRUSTED_AUTHORITY, evaluatedAt)
        }

        val selected = if (request.grantId != null) {
            grants[request.grantId]
                ?: return blocked(request, PolicyDecision.DENY, PolicyReason.GRANT_NOT_FOUND, evaluatedAt)
        } else {
            grants.values
                .asSequence()
                .filter { candidateCouldApply(it, request, evaluatedAt) }
                .sortedBy { it.grant.grantId }
                .firstOrNull()
                ?: return blocked(request, PolicyDecision.ASK, PolicyReason.AUTHORITY_REQUIRED, evaluatedAt)
        }

        if (selected.revoked) {
            return blocked(request, PolicyDecision.DENY, PolicyReason.GRANT_REVOKED, evaluatedAt, selected.grant)
        }
        if (evaluatedAt >= selected.grant.expiresAtEpochMillis) {
            return blocked(request, PolicyDecision.ASK, PolicyReason.GRANT_EXPIRED, evaluatedAt, selected.grant)
        }
        if (selected.usesConsumed >= selected.grant.maximumUses) {
            return blocked(request, PolicyDecision.DENY, PolicyReason.GRANT_EXHAUSTED, evaluatedAt, selected.grant)
        }
        if (!delegationAllows(selected.grant, request)) {
            return blocked(request, PolicyDecision.DENY, PolicyReason.DELEGATION_INVALID, evaluatedAt, selected.grant)
        }
        if (!selected.grant.scope.contains(request)) {
            return blocked(request, PolicyDecision.DENY, PolicyReason.GRANT_SCOPE_MISMATCH, evaluatedAt, selected.grant)
        }
        if (request.risk !in selected.grant.allowedRisks) {
            return blocked(request, PolicyDecision.DENY, PolicyReason.GRANT_RISK_MISMATCH, evaluatedAt, selected.grant)
        }
        if (request.risk.requiresCurrentConfirmation() &&
            selected.grant.authority.origin != AuthorityOrigin.CURRENT_CONFIRMATION
        ) {
            return blocked(
                request,
                PolicyDecision.ASK,
                PolicyReason.CURRENT_CONFIRMATION_REQUIRED,
                evaluatedAt,
                selected.grant,
            )
        }

        selected.usesConsumed += 1
        val singleUse = selected.grant.maximumUses == 1
        val decision = if (singleUse) PolicyDecision.ALLOW_ONCE else PolicyDecision.ALLOW
        val authorization = PolicyAuthorization(
            authorizationId = "${selected.grant.grantId}:${selected.usesConsumed}:${request.actionId}",
            actionId = request.actionId,
            capability = request.capability,
            grantId = selected.grant.grantId,
            expiresAtEpochMillis = selected.grant.expiresAtEpochMillis,
            singleUse = singleUse,
        )
        issuedAuthorizations[authorization.authorizationId] = authorization
        trimAuthorizationHistory()
        return PolicyEvaluation(
            decision = decision,
            authorization = authorization,
            audit = audit(request, decision, PolicyReason.AUTHORITY_ACCEPTED, evaluatedAt, selected.grant),
        )
    }

    @Synchronized
    override fun claimAuthorization(authorization: PolicyAuthorization): PolicyAuthorizationClaimResult {
        val now = clock.nowEpochMillis()
        pruneAuthorizations(now)
        if (authorization.authorizationId in claimedAuthorizations) {
            return PolicyAuthorizationClaimResult.Rejected(PolicyAuthorizationClaimFailure.REPLAYED)
        }
        val issued = issuedAuthorizations[authorization.authorizationId]
            ?: return PolicyAuthorizationClaimResult.Rejected(PolicyAuthorizationClaimFailure.NOT_ISSUED)
        if (issued != authorization) {
            return PolicyAuthorizationClaimResult.Rejected(PolicyAuthorizationClaimFailure.MISMATCH)
        }
        if (now >= issued.expiresAtEpochMillis) {
            issuedAuthorizations.remove(authorization.authorizationId)
            return PolicyAuthorizationClaimResult.Rejected(PolicyAuthorizationClaimFailure.EXPIRED)
        }
        issuedAuthorizations.remove(authorization.authorizationId)
        claimedAuthorizations += authorization.authorizationId
        trimAuthorizationHistory()
        return PolicyAuthorizationClaimResult.Claimed
    }

    private fun candidateCouldApply(stored: StoredGrant, request: PolicyRequest, now: Long): Boolean =
        !stored.revoked &&
            now < stored.grant.expiresAtEpochMillis &&
            stored.usesConsumed < stored.grant.maximumUses &&
            request.risk in stored.grant.allowedRisks &&
            stored.grant.scope.contains(request) &&
            delegationAllows(stored.grant, request)

    private fun delegationAllows(grant: AuthorityGrant, request: PolicyRequest): Boolean {
        val acting = request.principal.acting
        val links = request.principal.delegation.links
        if (acting == grant.subject) return links.isEmpty()
        if (links.isEmpty()) return false

        var expectedDelegator = grant.subject
        var parentScope = grant.scope
        val seen = linkedSetOf(grant.subject)
        links.forEach { link ->
            if (link.delegator != expectedDelegator) return false
            if (!seen.add(link.delegate)) return false
            if (!parentScope.contains(link.scope)) return false
            if (!link.scope.contains(request)) return false
            expectedDelegator = link.delegate
            parentScope = link.scope
        }
        return expectedDelegator == acting
    }

    private fun blocked(
        request: PolicyRequest,
        decision: PolicyDecision,
        reason: PolicyReason,
        evaluatedAt: Long,
        grant: AuthorityGrant? = null,
    ): PolicyEvaluation = PolicyEvaluation(
        decision = decision,
        authorization = null,
        audit = audit(request, decision, reason, evaluatedAt, grant),
    )

    private fun audit(
        request: PolicyRequest,
        decision: PolicyDecision,
        reason: PolicyReason,
        evaluatedAt: Long,
        grant: AuthorityGrant?,
    ): PolicyAuditRecord = PolicyAuditRecord(
        actionId = request.actionId,
        capability = request.capability,
        risk = request.risk,
        decision = decision,
        reason = reason,
        explanation = reason.explanation,
        principalKind = request.principal.acting.kind,
        delegationDepth = request.principal.delegation.links.size,
        packageName = request.target?.packageName,
        targetType = request.target?.targetType,
        authorityOrigins = request.authorityClaims.map { it.origin }.distinct().sortedBy { it.name },
        contextOrigins = request.contextEvidence.map { it.origin }.distinct().sortedBy { it.name },
        grantReference = grant?.grantId?.let(::safeReference),
        evaluatedAtEpochMillis = evaluatedAt,
    )

    private fun pruneAuthorizations(now: Long) {
        issuedAuthorizations.entries.removeAll { (_, authorization) -> now >= authorization.expiresAtEpochMillis }
        trimAuthorizationHistory()
    }

    private fun trimAuthorizationHistory() {
        while (issuedAuthorizations.size > MAX_AUTHORIZATION_HISTORY) {
            val key = issuedAuthorizations.keys.firstOrNull() ?: break
            issuedAuthorizations.remove(key)
        }
        while (claimedAuthorizations.size > MAX_AUTHORIZATION_HISTORY) {
            val iterator = claimedAuthorizations.iterator()
            if (!iterator.hasNext()) break
            iterator.next()
            iterator.remove()
        }
    }

    private fun safeReference(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return "sha256:" + digest.take(6).joinToString("") { "%02x".format(it) }
    }

    private fun StoredGrant.snapshot() = AuthorityGrantSnapshot(grant, usesConsumed, revoked)

    private fun ActionRisk.requiresCurrentConfirmation(): Boolean = when (this) {
        ActionRisk.AUTHENTICATION,
        ActionRisk.FINANCIAL,
        ActionRisk.DESTRUCTIVE,
        ActionRisk.SECURITY_CRITICAL,
        -> true

        ActionRisk.ROUTINE,
        ActionRisk.PRIVACY_SENSITIVE,
        ActionRisk.EXTERNAL_COMMUNICATION,
        -> false
    }

    private companion object {
        const val MAX_AUTHORIZATION_HISTORY = 2_048
    }
}

/**
 * Small integration seam that makes the authority check precede a caller-supplied operation. It
 * contains no phone behavior and cannot bypass PhoneToolExecutor.
 */
class PolicyGuard(private val governor: PolicyGovernor) {
    fun <T> authorizeThen(request: PolicyRequest, operation: (PolicyAuthorization) -> T): GuardedPolicyResult<T> {
        val evaluation = governor.evaluate(request)
        val authorization = evaluation.authorization
            ?: return GuardedPolicyResult.Blocked(evaluation)
        return GuardedPolicyResult.Executed(evaluation, operation(authorization))
    }
}

sealed class GuardedPolicyResult<out T> {
    abstract val evaluation: PolicyEvaluation

    data class Blocked(override val evaluation: PolicyEvaluation) : GuardedPolicyResult<Nothing>()

    data class Executed<T>(
        override val evaluation: PolicyEvaluation,
        val value: T,
    ) : GuardedPolicyResult<T>()
}
