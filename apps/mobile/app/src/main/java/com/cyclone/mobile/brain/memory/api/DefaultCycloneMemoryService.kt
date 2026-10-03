package com.cyclone.mobile.brain.memory.api

import com.cyclone.mobile.brain.memory.audit.MemoryAuditDecision
import com.cyclone.mobile.brain.memory.audit.MemoryAuditEntry
import com.cyclone.mobile.brain.memory.audit.MemoryAuditJournal
import com.cyclone.mobile.brain.memory.audit.MemoryAuditQuery
import java.security.MessageDigest

fun interface MemoryClock {
    fun nowEpochMillis(): Long
}

class DefaultCycloneMemoryService(
    private val provider: MemoryStoreProvider,
    private val policyGate: MemoryWritePolicyGate,
    private val approvalVerifier: MemoryApprovalVerifier,
    private val auditJournal: MemoryAuditJournal,
    private val redactor: MemoryRedactor = DefaultMemoryRedactor(),
    private val budgets: MemoryBudgetPolicy = MemoryBudgetPolicy(),
    private val clock: MemoryClock = MemoryClock(System::currentTimeMillis),
) : CycloneMemoryService {
    private data class PendingProposal(
        val record: MemoryRecord,
        val policyRequest: MemoryPolicyRequest,
        val approvalRequiredAtProposal: Boolean,
        val redactedFieldCount: Int,
        val expiresAtEpochMillis: Long,
    )

    private val pending = linkedMapOf<String, PendingProposal>()
    private val completedMutationIds = linkedSetOf<String>()
    private var auditSequence = 0L

    init {
        require(provider.providerId.matches(Regex("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)+"))) {
            "Memory provider id must be namespaced"
        }
    }

    @Synchronized
    override fun query(query: MemoryQuery): List<MemoryRecord> = provider.query(
        query.copy(limit = query.limit.coerceAtMost(budgets.maxQueryResults)),
    ).asSequence()
        .filter { it.scope == query.scope }
        .filter { it.memoryClass in query.memoryClasses }
        .filter { query.includeArchived || !it.archived }
        .sortedWith(memoryOrder())
        .take(query.limit.coerceAtMost(budgets.maxQueryResults))
        .toList()

    @Synchronized
    override fun recall(request: MemoryRecallRequest): List<MemoryRecord> = provider.recall(
        request.copy(limit = request.limit.coerceAtMost(budgets.maxQueryResults)),
    ).asSequence()
        .filter { it.scope == request.scope && !it.archived }
        .filter { it.memoryClass in request.memoryClasses }
        .sortedWith(memoryOrder())
        .take(request.limit.coerceAtMost(budgets.maxQueryResults))
        .toList()

    @Synchronized
    override fun proposeWrite(request: MemoryWriteProposalRequest): MemoryWriteProposalResult {
        val now = clock.nowEpochMillis()
        expirePending(now)
        if (!validMutationId(request.proposalId)) {
            return proposalResult(request.proposalId, MemoryProposalStatus.INVALID, "INVALID_PROPOSAL_ID")
        }
        if (request.proposalId in pending || request.proposalId in completedMutationIds) {
            audit(request.proposalId, MemoryMutationKind.CREATE, request.draft.source, MemoryAuditDecision.REPLAY_REJECTED,
                "PROPOSAL_REPLAY", request.draft.scope, request.draft.recordId, null, 0, now)
            return proposalResult(request.proposalId, MemoryProposalStatus.INVALID, "PROPOSAL_REPLAY")
        }
        validateDraft(request.draft, now)?.let { reason ->
            audit(request.proposalId, MemoryMutationKind.CREATE, request.draft.source, MemoryAuditDecision.DENIED,
                reason, request.draft.scope, request.draft.recordId, null, 0, now)
            val status = if (reason.endsWith("BUDGET")) MemoryProposalStatus.BUDGET_EXCEEDED else MemoryProposalStatus.INVALID
            return proposalResult(request.proposalId, status, reason)
        }
        val redaction = redactor.redact(request.draft)
        if (redaction is MemoryRedactionResult.Rejected) {
            audit(request.proposalId, MemoryMutationKind.CREATE, request.draft.source, MemoryAuditDecision.DENIED,
                redaction.reasonCode, request.draft.scope, request.draft.recordId, null, 0, now)
            return proposalResult(request.proposalId, MemoryProposalStatus.DENIED, redaction.reasonCode)
        }
        redaction as MemoryRedactionResult.Safe
        if (redaction.content.estimatedUtf8Bytes() > budgets.maxRecordBytes) {
            audit(request.proposalId, MemoryMutationKind.CREATE, request.draft.source, MemoryAuditDecision.DENIED,
                "SAFE_CONTENT_BUDGET", request.draft.scope, request.draft.recordId, null,
                redaction.redactedFieldNames.size, now)
            return proposalResult(request.proposalId, MemoryProposalStatus.BUDGET_EXCEEDED, "SAFE_CONTENT_BUDGET")
        }
        val policyRequest = policyRequest(
            request.proposalId,
            MemoryMutationKind.CREATE,
            request.draft,
            redaction.content,
            redaction.redactedFieldNames.size,
        )
        val policy = evaluatePolicy(policyRequest)
        if (policy.decision == MemoryPolicyDecision.DENY) {
            audit(request.proposalId, MemoryMutationKind.CREATE, request.draft.source, MemoryAuditDecision.DENIED,
                policy.reasonCode, request.draft.scope, request.draft.recordId, null,
                redaction.redactedFieldNames.size, now)
            return proposalResult(request.proposalId, MemoryProposalStatus.DENIED, policy.reasonCode)
        }

        val fingerprint = fingerprint(request.draft, redaction.content)
        provider.findByFingerprint(request.draft.scope, fingerprint)?.let { duplicate ->
            audit(request.proposalId, MemoryMutationKind.CREATE, request.draft.source, MemoryAuditDecision.DUPLICATE,
                "CONTENT_DUPLICATE", request.draft.scope, request.draft.recordId, duplicate.recordVersion,
                redaction.redactedFieldNames.size, now)
            return MemoryWriteProposalResult(
                request.proposalId,
                MemoryProposalStatus.DUPLICATE,
                "CONTENT_DUPLICATE",
                duplicateRecord = duplicate,
            )
        }
        provider.get(MemoryRecordRef(request.draft.recordId, request.draft.scope))?.let { existing ->
            audit(request.proposalId, MemoryMutationKind.CREATE, request.draft.source, MemoryAuditDecision.DENIED,
                "RECORD_ID_EXISTS", request.draft.scope, request.draft.recordId, existing.recordVersion,
                redaction.redactedFieldNames.size, now)
            return proposalResult(request.proposalId, MemoryProposalStatus.INVALID, "RECORD_ID_EXISTS")
        }
        if (provider.count(request.draft.scope) >= budgets.maxRecordsPerScope) {
            audit(request.proposalId, MemoryMutationKind.CREATE, request.draft.source, MemoryAuditDecision.DENIED,
                "SCOPE_RECORD_BUDGET", request.draft.scope, request.draft.recordId, null,
                redaction.redactedFieldNames.size, now)
            return proposalResult(request.proposalId, MemoryProposalStatus.BUDGET_EXCEEDED, "SCOPE_RECORD_BUDGET")
        }
        if (pending.size >= budgets.maxPendingProposals) {
            audit(request.proposalId, MemoryMutationKind.CREATE, request.draft.source, MemoryAuditDecision.DENIED,
                "PENDING_PROPOSAL_BUDGET", request.draft.scope, request.draft.recordId, null,
                redaction.redactedFieldNames.size, now)
            return proposalResult(request.proposalId, MemoryProposalStatus.BUDGET_EXCEEDED, "PENDING_PROPOSAL_BUDGET")
        }

        val record = recordFrom(request.draft, redaction.content, fingerprint, now)
        val expiresAt = now + budgets.proposalLifetimeMillis
        pending[request.proposalId] = PendingProposal(
            record,
            policyRequest,
            policy.decision == MemoryPolicyDecision.REQUIRE_APPROVAL,
            redaction.redactedFieldNames.size,
            expiresAt,
        )
        val status = if (policy.decision == MemoryPolicyDecision.REQUIRE_APPROVAL) {
            MemoryProposalStatus.APPROVAL_REQUIRED
        } else {
            MemoryProposalStatus.READY
        }
        val auditDecision = if (status == MemoryProposalStatus.APPROVAL_REQUIRED) {
            MemoryAuditDecision.APPROVAL_REQUIRED
        } else {
            MemoryAuditDecision.PROPOSED
        }
        audit(request.proposalId, MemoryMutationKind.CREATE, request.draft.source, auditDecision,
            policy.reasonCode, request.draft.scope, request.draft.recordId, 1,
            redaction.redactedFieldNames.size, now)
        return MemoryWriteProposalResult(request.proposalId, status, policy.reasonCode, expiresAtEpochMillis = expiresAt)
    }

    @Synchronized
    override fun commitApprovedWrite(
        proposalId: String,
        approval: MemoryMutationApproval?,
    ): MemoryMutationResult {
        val now = clock.nowEpochMillis()
        if (proposalId in completedMutationIds) {
            return replay(proposalId, MemoryMutationKind.CREATE, null, null, now)
        }
        val proposal = pending[proposalId]
            ?: return MemoryMutationResult(proposalId, MemoryMutationStatus.NOT_FOUND, "PROPOSAL_NOT_FOUND")
        if (now >= proposal.expiresAtEpochMillis) {
            pending.remove(proposalId)
            audit(proposalId, MemoryMutationKind.CREATE, proposal.record.source, MemoryAuditDecision.DENIED,
                "PROPOSAL_EXPIRED", proposal.record.scope, proposal.record.recordId, null,
                proposal.redactedFieldCount, now)
            return MemoryMutationResult(proposalId, MemoryMutationStatus.DENIED, "PROPOSAL_EXPIRED")
        }
        val currentPolicy = evaluatePolicy(proposal.policyRequest)
        if (currentPolicy.decision == MemoryPolicyDecision.DENY) {
            audit(proposalId, MemoryMutationKind.CREATE, proposal.record.source, MemoryAuditDecision.DENIED,
                currentPolicy.reasonCode, proposal.record.scope, proposal.record.recordId, null,
                proposal.redactedFieldCount, now)
            return MemoryMutationResult(proposalId, MemoryMutationStatus.DENIED, currentPolicy.reasonCode)
        }
        if ((proposal.approvalRequiredAtProposal || currentPolicy.decision == MemoryPolicyDecision.REQUIRE_APPROVAL) &&
            !validApproval(approval, proposal.policyRequest, now)
        ) {
            audit(proposalId, MemoryMutationKind.CREATE, proposal.record.source, MemoryAuditDecision.DENIED,
                "APPROVAL_REQUIRED", proposal.record.scope, proposal.record.recordId, null,
                proposal.redactedFieldCount, now)
            return MemoryMutationResult(proposalId, MemoryMutationStatus.DENIED, "APPROVAL_REQUIRED")
        }
        return applyProviderMutation(
            proposalId,
            MemoryMutationKind.CREATE,
            proposal.record.source,
            proposal.record.scope,
            proposal.record.recordId,
            proposal.redactedFieldCount,
            AuthorizedMemoryMutation.Insert(proposal.record),
            proposal.record.recordVersion,
            now,
        ).also { result ->
            if (result.status == MemoryMutationStatus.COMMITTED) {
                pending.remove(proposalId)
                completedMutationIds += proposalId
            }
        }
    }

    @Synchronized
    override fun replace(request: MemoryReplaceRequest, approval: MemoryMutationApproval?): MemoryMutationResult {
        val now = clock.nowEpochMillis()
        if (request.mutationId in completedMutationIds) {
            return replay(request.mutationId, MemoryMutationKind.REPLACE, request.draft.source, request.draft, now)
        }
        if (!validMutationId(request.mutationId)) {
            return MemoryMutationResult(request.mutationId, MemoryMutationStatus.INVALID, "INVALID_MUTATION_ID")
        }
        validateDraft(request.draft, now)?.let {
            audit(request.mutationId, MemoryMutationKind.REPLACE, request.draft.source, MemoryAuditDecision.DENIED,
                it, request.draft.scope, request.draft.recordId, null, 0, now)
            return MemoryMutationResult(request.mutationId, MemoryMutationStatus.INVALID, it)
        }
        val reference = MemoryRecordRef(request.draft.recordId, request.draft.scope)
        val existing = provider.get(reference)
            ?: return notFound(request.mutationId, MemoryMutationKind.REPLACE, request.draft.source, reference, now)
        if (existing.recordVersion != request.expectedVersion) {
            return stale(request.mutationId, MemoryMutationKind.REPLACE, request.draft.source, existing, now)
        }
        val redaction = redactor.redact(request.draft)
        if (redaction is MemoryRedactionResult.Rejected) {
            audit(request.mutationId, MemoryMutationKind.REPLACE, request.draft.source, MemoryAuditDecision.DENIED,
                redaction.reasonCode, request.draft.scope, request.draft.recordId, existing.recordVersion, 0, now)
            return MemoryMutationResult(request.mutationId, MemoryMutationStatus.DENIED, redaction.reasonCode)
        }
        redaction as MemoryRedactionResult.Safe
        if (redaction.content.estimatedUtf8Bytes() > budgets.maxRecordBytes) {
            audit(request.mutationId, MemoryMutationKind.REPLACE, request.draft.source, MemoryAuditDecision.DENIED,
                "SAFE_CONTENT_BUDGET", request.draft.scope, request.draft.recordId, existing.recordVersion,
                redaction.redactedFieldNames.size, now)
            return MemoryMutationResult(request.mutationId, MemoryMutationStatus.BUDGET_EXCEEDED, "SAFE_CONTENT_BUDGET")
        }
        val policyRequest = policyRequest(
            request.mutationId,
            MemoryMutationKind.REPLACE,
            request.draft,
            redaction.content,
            redaction.redactedFieldNames.size,
        )
        policyDenied(policyRequest, approval, now)?.let { reason ->
            audit(request.mutationId, MemoryMutationKind.REPLACE, request.draft.source, MemoryAuditDecision.DENIED,
                reason, request.draft.scope, request.draft.recordId, existing.recordVersion,
                redaction.redactedFieldNames.size, now)
            return MemoryMutationResult(request.mutationId, MemoryMutationStatus.DENIED, reason)
        }
        val fingerprint = fingerprint(request.draft, redaction.content)
        if (fingerprint == existing.contentFingerprint) {
            audit(request.mutationId, MemoryMutationKind.REPLACE, request.draft.source, MemoryAuditDecision.DUPLICATE,
                "UNCHANGED_CONTENT", request.draft.scope, request.draft.recordId, existing.recordVersion,
                redaction.redactedFieldNames.size, now)
            return MemoryMutationResult(request.mutationId, MemoryMutationStatus.DUPLICATE, "UNCHANGED_CONTENT", existing)
        }
        provider.findByFingerprint(request.draft.scope, fingerprint)?.takeIf { it.recordId != existing.recordId }?.let { duplicate ->
            audit(request.mutationId, MemoryMutationKind.REPLACE, request.draft.source, MemoryAuditDecision.DUPLICATE,
                "CONTENT_DUPLICATE", request.draft.scope, request.draft.recordId, duplicate.recordVersion,
                redaction.redactedFieldNames.size, now)
            return MemoryMutationResult(request.mutationId, MemoryMutationStatus.DUPLICATE, "CONTENT_DUPLICATE", duplicate)
        }
        val updated = recordFrom(
            request.draft,
            redaction.content,
            fingerprint,
            now,
            createdAt = existing.createdAtEpochMillis,
            version = existing.recordVersion + 1,
        )
        return applyProviderMutation(
            request.mutationId,
            MemoryMutationKind.REPLACE,
            request.draft.source,
            request.draft.scope,
            request.draft.recordId,
            redaction.redactedFieldNames.size,
            AuthorizedMemoryMutation.Replace(updated, request.expectedVersion),
            updated.recordVersion,
            now,
        ).also { if (it.status == MemoryMutationStatus.COMMITTED) completedMutationIds += request.mutationId }
    }

    @Synchronized
    override fun remove(
        request: MemoryRecordMutationRequest,
        approval: MemoryMutationApproval?,
    ): MemoryMutationResult = mutateExisting(request, approval, MemoryMutationKind.REMOVE)

    @Synchronized
    override fun archive(
        request: MemoryRecordMutationRequest,
        approval: MemoryMutationApproval?,
    ): MemoryMutationResult = mutateExisting(request, approval, MemoryMutationKind.ARCHIVE)

    @Synchronized
    override fun inspectAudit(query: MemoryAuditQuery): List<MemoryAuditEntry> = auditJournal.inspect(
        query.copy(limit = query.limit.coerceAtMost(budgets.maxQueryResults)),
    )

    private fun mutateExisting(
        request: MemoryRecordMutationRequest,
        approval: MemoryMutationApproval?,
        kind: MemoryMutationKind,
    ): MemoryMutationResult {
        val now = clock.nowEpochMillis()
        if (request.mutationId in completedMutationIds) {
            return replay(request.mutationId, kind, request.actor, null, now, request.reference)
        }
        if (!validMutationId(request.mutationId)) {
            return MemoryMutationResult(request.mutationId, MemoryMutationStatus.INVALID, "INVALID_MUTATION_ID")
        }
        val existing = provider.get(request.reference)
            ?: return notFound(request.mutationId, kind, request.actor, request.reference, now)
        if (existing.recordVersion != request.expectedVersion) {
            return stale(request.mutationId, kind, request.actor, existing, now)
        }
        val policyRequest = MemoryPolicyRequest(
            request.mutationId,
            kind,
            request.actor,
            existing.scope,
            existing.memoryClass,
            existing.sensitivity,
            existing.recordId,
            existing.schemaVersion,
            safeContentBytes = 0,
            redactedFieldCount = 0,
        )
        policyDenied(policyRequest, approval, now)?.let { reason ->
            audit(request.mutationId, kind, request.actor, MemoryAuditDecision.DENIED, reason,
                existing.scope, existing.recordId, existing.recordVersion, 0, now)
            return MemoryMutationResult(request.mutationId, MemoryMutationStatus.DENIED, reason)
        }
        if (kind == MemoryMutationKind.ARCHIVE && existing.archived) {
            audit(request.mutationId, kind, request.actor, MemoryAuditDecision.DUPLICATE, "ALREADY_ARCHIVED",
                existing.scope, existing.recordId, existing.recordVersion, 0, now)
            return MemoryMutationResult(
                request.mutationId,
                MemoryMutationStatus.DUPLICATE,
                "ALREADY_ARCHIVED",
                existing,
            )
        }
        val command = if (kind == MemoryMutationKind.REMOVE) {
            AuthorizedMemoryMutation.Remove(request.reference, request.expectedVersion)
        } else {
            AuthorizedMemoryMutation.Archive(
                existing.copy(
                    recordVersion = existing.recordVersion + 1,
                    updatedAtEpochMillis = now,
                    archived = true,
                ),
                request.expectedVersion,
            )
        }
        return applyProviderMutation(
            request.mutationId,
            kind,
            request.actor,
            existing.scope,
            existing.recordId,
            0,
            command,
            if (kind == MemoryMutationKind.ARCHIVE) existing.recordVersion + 1 else existing.recordVersion,
            now,
        ).also { if (it.status == MemoryMutationStatus.COMMITTED) completedMutationIds += request.mutationId }
    }

    private fun policyDenied(
        request: MemoryPolicyRequest,
        approval: MemoryMutationApproval?,
        now: Long,
    ): String? {
        val policy = evaluatePolicy(request)
        if (policy.decision == MemoryPolicyDecision.DENY) return policy.reasonCode
        if (policy.decision == MemoryPolicyDecision.REQUIRE_APPROVAL && !validApproval(approval, request, now)) {
            return "APPROVAL_REQUIRED"
        }
        return null
    }

    private fun validApproval(
        approval: MemoryMutationApproval?,
        request: MemoryPolicyRequest,
        now: Long,
    ): Boolean = approval != null &&
        approval.mutationId == request.mutationId &&
        approval.approvedAtEpochMillis <= now &&
        now < approval.expiresAtEpochMillis &&
        runCatching { approvalVerifier.isValid(approval, request) }.getOrDefault(false)

    private fun applyProviderMutation(
        mutationId: String,
        kind: MemoryMutationKind,
        actor: MemoryActor,
        scope: MemoryScope,
        recordId: String,
        redactedFieldCount: Int,
        command: AuthorizedMemoryMutation,
        auditRecordVersion: Int,
        now: Long,
    ): MemoryMutationResult {
        val providerResult = runCatching { provider.apply(command) }
            .getOrElse { MemoryProviderMutationResult.Failed("PROVIDER_EXCEPTION") }
        val result = when (providerResult) {
            is MemoryProviderMutationResult.Applied ->
                MemoryMutationResult(mutationId, MemoryMutationStatus.COMMITTED, "COMMITTED", providerResult.record)

            is MemoryProviderMutationResult.StaleVersion ->
                MemoryMutationResult(mutationId, MemoryMutationStatus.STALE_VERSION, "STALE_VERSION")

            MemoryProviderMutationResult.AlreadyExists ->
                MemoryMutationResult(mutationId, MemoryMutationStatus.DUPLICATE, "PROVIDER_ALREADY_EXISTS")

            MemoryProviderMutationResult.NotFound ->
                MemoryMutationResult(mutationId, MemoryMutationStatus.NOT_FOUND, "PROVIDER_NOT_FOUND")

            is MemoryProviderMutationResult.Failed ->
                MemoryMutationResult(
                    mutationId,
                    MemoryMutationStatus.PROVIDER_FAILED,
                    providerResult.diagnosticCode.takeIf { it.matches(Regex("[A-Z][A-Z0-9_]*")) }
                        ?: "PROVIDER_FAILURE",
                )
        }
        val auditDecision = if (result.status == MemoryMutationStatus.COMMITTED) {
            MemoryAuditDecision.COMMITTED
        } else {
            MemoryAuditDecision.FAILED
        }
        audit(mutationId, kind, actor, auditDecision, result.reasonCode, scope, recordId,
            result.record?.recordVersion ?: auditRecordVersion, redactedFieldCount, now)
        return result
    }

    private fun validateDraft(draft: MemoryDraft, now: Long): String? = when {
        draft.schemaVersion !in budgets.supportedSchemaVersions -> "UNSUPPORTED_SCHEMA"
        draft.provenance.observedAtEpochMillis > now -> "FUTURE_PROVENANCE"
        draft.content.estimatedUtf8Bytes() > budgets.maxRecordBytes -> "RAW_CONTENT_BUDGET"
        else -> null
    }

    private fun policyRequest(
        mutationId: String,
        kind: MemoryMutationKind,
        draft: MemoryDraft,
        safeContent: MemoryContent,
        redactedFieldCount: Int,
    ) = MemoryPolicyRequest(
        mutationId,
        kind,
        draft.source,
        draft.scope,
        draft.memoryClass,
        draft.sensitivity,
        draft.recordId,
        draft.schemaVersion,
        safeContent.estimatedUtf8Bytes(),
        redactedFieldCount,
    )

    private fun evaluatePolicy(request: MemoryPolicyRequest): MemoryPolicyResult = runCatching {
        policyGate.evaluate(request)
    }.getOrElse { MemoryPolicyResult(MemoryPolicyDecision.DENY, "POLICY_GATE_FAILURE") }

    private fun recordFrom(
        draft: MemoryDraft,
        safeContent: MemoryContent,
        fingerprint: String,
        now: Long,
        createdAt: Long = now,
        version: Int = 1,
    ) = MemoryRecord(
        draft.recordId,
        draft.schemaVersion,
        version,
        draft.source,
        draft.provenance,
        createdAt,
        now,
        draft.confidence,
        draft.verificationState,
        draft.scope,
        draft.sensitivity,
        draft.memoryClass,
        safeContent,
        fingerprint,
    )

    private fun fingerprint(draft: MemoryDraft, content: MemoryContent): String {
        val canonical = buildString {
            append(draft.schemaVersion).append('|')
            append(draft.scope.kind.name).append(':').append(draft.scope.scopeId).append('|')
            append(draft.memoryClass.name).append('|')
            content.fields.toSortedMap().forEach { (key, value) ->
                append(key.length).append(':').append(key).append('=')
                append(value.length).append(':').append(value).append(';')
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return "sha256:" + digest.joinToString("") { "%02x".format(it) }
    }

    private fun memoryOrder() = compareBy<MemoryRecord> { it.verificationState == MemoryVerificationState.STALE }
        .thenByDescending { it.provenance.observedAtEpochMillis }
        .thenByDescending { verificationRank(it.verificationState) }
        .thenByDescending { it.updatedAtEpochMillis }
        .thenByDescending { it.confidence }
        .thenBy { it.recordId }

    private fun verificationRank(state: MemoryVerificationState): Int = when (state) {
        MemoryVerificationState.VERIFIED -> 3
        MemoryVerificationState.OBSERVED -> 2
        MemoryVerificationState.UNVERIFIED -> 1
        MemoryVerificationState.STALE -> 0
    }

    private fun validMutationId(value: String): Boolean = value.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:-]*"))

    private fun proposalResult(id: String, status: MemoryProposalStatus, reason: String) =
        MemoryWriteProposalResult(id, status, reason)

    private fun expirePending(now: Long) {
        pending.entries.removeAll { now >= it.value.expiresAtEpochMillis }
    }

    private fun notFound(
        mutationId: String,
        kind: MemoryMutationKind,
        actor: MemoryActor,
        reference: MemoryRecordRef,
        now: Long,
    ): MemoryMutationResult {
        audit(mutationId, kind, actor, MemoryAuditDecision.FAILED, "RECORD_NOT_FOUND",
            reference.scope, reference.recordId, null, 0, now)
        return MemoryMutationResult(mutationId, MemoryMutationStatus.NOT_FOUND, "RECORD_NOT_FOUND")
    }

    private fun stale(
        mutationId: String,
        kind: MemoryMutationKind,
        actor: MemoryActor,
        record: MemoryRecord,
        now: Long,
    ): MemoryMutationResult {
        audit(mutationId, kind, actor, MemoryAuditDecision.FAILED, "STALE_VERSION",
            record.scope, record.recordId, record.recordVersion, 0, now)
        return MemoryMutationResult(mutationId, MemoryMutationStatus.STALE_VERSION, "STALE_VERSION", record)
    }

    private fun replay(
        mutationId: String,
        kind: MemoryMutationKind,
        actor: MemoryActor?,
        draft: MemoryDraft?,
        now: Long,
        reference: MemoryRecordRef? = null,
    ): MemoryMutationResult {
        val safeActor = actor ?: draft?.source ?: MemoryActor("cyclone.memory", MemorySourceKind.CYCLONE_RUNTIME)
        val safeScope = reference?.scope ?: draft?.scope ?: MemoryScope(MemoryScopeKind.WORKSPACE_DEVICE, "device")
        val recordId = reference?.recordId ?: draft?.recordId ?: "unknown"
        audit(mutationId, kind, safeActor, MemoryAuditDecision.REPLAY_REJECTED, "MUTATION_REPLAY",
            safeScope, recordId, null, 0, now)
        return MemoryMutationResult(mutationId, MemoryMutationStatus.REPLAYED, "MUTATION_REPLAY")
    }

    private fun audit(
        mutationId: String,
        kind: MemoryMutationKind,
        actor: MemoryActor,
        decision: MemoryAuditDecision,
        reason: String,
        scope: MemoryScope,
        recordId: String,
        recordVersion: Int?,
        redactedFieldCount: Int,
        now: Long,
    ) {
        auditSequence += 1
        auditJournal.append(
            MemoryAuditEntry(
                auditSequence,
                now,
                mutationId,
                kind,
                safeActorReference(actor.actorId),
                actor.sourceKind,
                decision,
                reason,
                provider.providerId,
                scope,
                recordId,
                recordVersion,
                redactedFieldCount,
            ),
        )
    }

    private fun safeActorReference(actorId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(actorId.toByteArray(Charsets.UTF_8))
        return "sha256:" + digest.take(6).joinToString("") { "%02x".format(it) }
    }
}
