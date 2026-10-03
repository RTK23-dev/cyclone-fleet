package com.cyclone.mobile.infrastructure.v31

import com.cyclone.mobile.PhoneToolErrorCode
import com.cyclone.mobile.PhoneToolRequest
import com.cyclone.mobile.PhoneToolResult
import com.cyclone.mobile.infrastructure.v3.AuthorizedPhoneActionProposal
import com.cyclone.mobile.infrastructure.v3.CanonicalPhoneExecutorProposalSink
import com.cyclone.mobile.infrastructure.v3.DecisionEvidence
import com.cyclone.mobile.infrastructure.v3.TrustedDecisionEvidenceValidator
import com.cyclone.mobile.policy.PolicyAuthorizationClaimFailure
import com.cyclone.mobile.policy.PolicyAuthorizationClaimResult
import com.cyclone.mobile.policy.PolicyAuthorizationClaimer
import java.util.LinkedHashMap

fun interface V31Clock {
    fun nowEpochMillis(): Long
}

/** The sole production delegate target is PhoneToolExecutor.execute; tests may inject a fake. */
fun interface CanonicalPhoneExecutionDelegate {
    fun execute(request: PhoneToolRequest): PhoneToolResult
}

enum class V31ExecutionState {
    REJECTED,
    SUCCEEDED,
    FAILED,
}

enum class V31VerificationState {
    NOT_RUN,
    PENDING,
    VERIFIED,
    FAILED,
}

/** Secret-free execution/verification status. Request params and typed values are never retained. */
data class V31CanonicalExecutionRecord(
    val handoffId: String,
    val actionId: String,
    val capability: String,
    val executionState: V31ExecutionState,
    val verificationState: V31VerificationState,
    val rejectionCode: String? = null,
    val executorErrorCode: String? = null,
) {
    init {
        require(handoffId.isNotBlank() && actionId.isNotBlank() && capability.isNotBlank())
        require(rejectionCode == null || SAFE_CODE.matches(rejectionCode))
        require(executorErrorCode == null || SAFE_CODE.matches(executorErrorCode))
    }

    private companion object {
        val SAFE_CODE = Regex("[A-Z][A-Z0-9_]{0,95}")
    }
}

/**
 * Fresh semantic evidence authority for the production handoff. Page Awareness/Agent 2 records the
 * newest observation here. A mutation reserves that observation and invalidates it after the
 * canonical executor returns, forcing re-observation before another mutation.
 */
class V31ObservationAuthority(
    private val clock: V31Clock = V31Clock(System::currentTimeMillis),
    private val maxAgeMillis: Long = 15_000L,
) : TrustedDecisionEvidenceValidator {
    private data class CurrentObservation(
        val observationId: String,
        val observedAtEpochMillis: Long,
        var reservedForMutation: Boolean = false,
    )

    private var current: CurrentObservation? = null

    init {
        require(maxAgeMillis in 1_000L..300_000L) { "Observation lifetime is outside the bounded range" }
    }

    @Synchronized
    fun recordCurrent(observationId: String, observedAtEpochMillis: Long = clock.nowEpochMillis()) {
        require(observationId.isNotBlank()) { "Observation id must not be blank" }
        require(observedAtEpochMillis >= 0) { "Observation timestamp must be non-negative" }
        current = CurrentObservation(observationId, observedAtEpochMillis)
    }

    @Synchronized
    fun invalidate() {
        current = null
    }

    @Synchronized
    override fun invalidReason(actionId: String, evidence: DecisionEvidence): String? =
        validateLocked(evidence, reserveForMutation = false)

    @Synchronized
    fun claimForExecution(evidence: DecisionEvidence, mutating: Boolean): String? =
        validateLocked(evidence, reserveForMutation = mutating)

    @Synchronized
    fun currentObservationId(): String? = current?.observationId

    private fun validateLocked(evidence: DecisionEvidence, reserveForMutation: Boolean): String? {
        if (evidence.pageObservationId != evidence.selectorObservationId) return "STALE_SELECTOR"
        val observed = current ?: return "OBSERVATION_REQUIRED"
        if (observed.observationId != evidence.pageObservationId) return "STALE_SELECTOR"
        val now = clock.nowEpochMillis()
        if (observed.observedAtEpochMillis > now || now - observed.observedAtEpochMillis > maxAgeMillis) {
            return "STALE_SELECTOR"
        }
        if (reserveForMutation) {
            if (observed.reservedForMutation) return "STALE_SELECTOR"
            observed.reservedForMutation = true
        }
        return null
    }
}

/**
 * Production policy-authorized bridge into Cyclone's one PhoneToolExecutor path.
 *
 * It contains zero Accessibility/tap/type logic. It claims an authorization that was actually
 * issued by PolicyGovernor, revalidates exact action/capability and observation freshness, then
 * invokes the canonical delegate once. Execution and verification remain distinct states.
 */
class CycloneAuthorizedActionExecutor(
    private val authorizationClaimer: PolicyAuthorizationClaimer,
    private val observationAuthority: V31ObservationAuthority,
    private val canonicalExecutor: CanonicalPhoneExecutionDelegate,
    private val clock: V31Clock = V31Clock(System::currentTimeMillis),
    private val maxRecords: Int = 256,
) : CanonicalPhoneExecutorProposalSink {
    private val records = object : LinkedHashMap<String, V31CanonicalExecutionRecord>(64, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, V31CanonicalExecutionRecord>?,
        ): Boolean = size > maxRecords
    }
    private var sequence = 0L

    init {
        require(maxRecords in 16..2_048)
    }

    override fun propose(action: AuthorizedPhoneActionProposal): String {
        val handoffId = nextHandoffId()
        val authorization = action.authorization
        val request = action.request
        if (authorization.actionId != request.commandId) {
            return reject(handoffId, request, "ACTION_ID_MISMATCH")
        }
        if (authorization.capability != request.tool) {
            return reject(handoffId, request, "CAPABILITY_MISMATCH")
        }
        if (clock.nowEpochMillis() >= authorization.expiresAtEpochMillis) {
            return reject(handoffId, request, "AUTHORIZATION_EXPIRED")
        }

        when (val claim = authorizationClaimer.claimAuthorization(authorization)) {
            PolicyAuthorizationClaimResult.Claimed -> Unit
            is PolicyAuthorizationClaimResult.Rejected -> {
                return reject(handoffId, request, claim.reason.toSafeCode())
            }
        }

        val mutating = request.tool in MUTATING_TOOLS
        observationAuthority.claimForExecution(action.evidence, mutating)?.let { reason ->
            return reject(handoffId, request, reason)
        }

        val result = try {
            canonicalExecutor.execute(request)
        } catch (_: Exception) {
            if (mutating) observationAuthority.invalidate()
            record(
                V31CanonicalExecutionRecord(
                    handoffId = handoffId,
                    actionId = request.commandId,
                    capability = request.tool,
                    executionState = V31ExecutionState.FAILED,
                    verificationState = V31VerificationState.NOT_RUN,
                    executorErrorCode = "CANONICAL_EXECUTOR_EXCEPTION",
                ),
            )
            return handoffId
        }
        if (mutating) observationAuthority.invalidate()
        record(
            V31CanonicalExecutionRecord(
                handoffId = handoffId,
                actionId = request.commandId,
                capability = request.tool,
                executionState = if (result.ok) V31ExecutionState.SUCCEEDED else V31ExecutionState.FAILED,
                verificationState = if (result.ok) V31VerificationState.PENDING else V31VerificationState.NOT_RUN,
                executorErrorCode = result.error?.code?.name,
            ),
        )
        return handoffId
    }

    @Synchronized
    fun recordVerification(handoffId: String, verified: Boolean): Boolean {
        val current = records[handoffId] ?: return false
        if (current.executionState != V31ExecutionState.SUCCEEDED) return false
        records[handoffId] = current.copy(
            verificationState = if (verified) V31VerificationState.VERIFIED else V31VerificationState.FAILED,
        )
        return true
    }

    @Synchronized
    fun executionRecord(handoffId: String): V31CanonicalExecutionRecord? = records[handoffId]?.copy()

    @Synchronized
    fun recentExecutionRecords(): List<V31CanonicalExecutionRecord> = records.values.map { it.copy() }

    @Synchronized
    private fun nextHandoffId(): String {
        sequence += 1
        return "v31.handoff.$sequence"
    }

    private fun reject(handoffId: String, request: PhoneToolRequest, reason: String): String {
        record(
            V31CanonicalExecutionRecord(
                handoffId = handoffId,
                actionId = request.commandId,
                capability = request.tool,
                executionState = V31ExecutionState.REJECTED,
                verificationState = V31VerificationState.NOT_RUN,
                rejectionCode = reason,
            ),
        )
        return handoffId
    }

    @Synchronized
    private fun record(record: V31CanonicalExecutionRecord) {
        records[record.handoffId] = record
    }

    private fun PolicyAuthorizationClaimFailure.toSafeCode(): String = when (this) {
        PolicyAuthorizationClaimFailure.NOT_ISSUED -> "AUTHORIZATION_NOT_ISSUED"
        PolicyAuthorizationClaimFailure.MISMATCH -> "AUTHORIZATION_MISMATCH"
        PolicyAuthorizationClaimFailure.EXPIRED -> "AUTHORIZATION_EXPIRED"
        PolicyAuthorizationClaimFailure.REPLAYED -> "AUTHORIZATION_REPLAY"
    }

    companion object {
        /** Mirrors PhoneToolExecutor's mutation family; this set never performs the operations. */
        val MUTATING_TOOLS: Set<String> = setOf(
            "workspace.register",
            "workspace.switch",
            "phone.workspace_switch",
            "workspace.pause",
            "workspace.release",
            "workspace.arm",
            "workspace.next",

            "phone.click",
            "phone.long_press",
            "phone.tap",
            "phone.type",
            "phone.replace_text",
            "phone.scroll",
            "phone.swipe",
            "phone.back",
            "phone.home",
            "phone.open_app",
            "phone.open_notification",
            "phone.set_clipboard",
            "phone.share",
            "phone.launch_intent",
        )
    }
}
