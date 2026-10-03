package com.cyclone.mobile.brain.v31

import com.cyclone.mobile.brain.memory.api.CycloneMemoryService
import com.cyclone.mobile.brain.memory.api.DefaultMemoryRedactor
import com.cyclone.mobile.brain.memory.api.MemoryActor
import com.cyclone.mobile.brain.memory.api.MemoryClass
import com.cyclone.mobile.brain.memory.api.MemoryContent
import com.cyclone.mobile.brain.memory.api.MemoryDraft
import com.cyclone.mobile.brain.memory.api.MemoryMutationStatus
import com.cyclone.mobile.brain.memory.api.MemoryProposalStatus
import com.cyclone.mobile.brain.memory.api.MemoryProvenance
import com.cyclone.mobile.brain.memory.api.MemoryRecallRequest
import com.cyclone.mobile.brain.memory.api.MemoryRecord
import com.cyclone.mobile.brain.memory.api.MemoryRedactionResult
import com.cyclone.mobile.brain.memory.api.MemoryScope
import com.cyclone.mobile.brain.memory.api.MemoryScopeKind
import com.cyclone.mobile.brain.memory.api.MemorySensitivity
import com.cyclone.mobile.brain.memory.api.MemorySourceKind
import com.cyclone.mobile.brain.memory.api.MemoryVerificationState
import com.cyclone.mobile.brain.memory.api.MemoryWriteProposalRequest
import java.security.MessageDigest

enum class V31MemoryProducer(val sourceKind: MemorySourceKind) {
    BRAIN(MemorySourceKind.CYCLONE_RUNTIME),
    APP_LEARNER(MemorySourceKind.APP_LEARNER),
    TEACH(MemorySourceKind.APP_LEARNER),
    AUTOMATION(MemorySourceKind.AUTOMATION),
    AI(MemorySourceKind.AI_PROPOSAL),
}

data class V31StructuredMemoryWrite(
    val producer: V31MemoryProducer,
    val appPackage: String,
    val routineId: String? = null,
    val evidenceId: String,
    val observedAtEpochMillis: Long,
    val confidence: Double,
    val verificationState: MemoryVerificationState,
    val verifiedRuntimeEvidence: Boolean,
    val memoryClass: MemoryClass,
    val sensitivity: MemorySensitivity = MemorySensitivity.INTERNAL,
    val fields: Map<String, String>,
) {
    init {
        require(appPackage.isNotBlank() && evidenceId.isNotBlank())
        require(observedAtEpochMillis >= 0)
        require(confidence in 0.0..1.0)
        require(fields.isNotEmpty())
    }
}

sealed interface V31PreparedMemory {
    data class Ready(val proposal: MemoryWriteProposalRequest) : V31PreparedMemory
    data class Rejected(val reasonCode: String) : V31PreparedMemory
}

sealed interface V31MemoryWriteOutcome {
    data class Committed(val recordId: String) : V31MemoryWriteOutcome
    data object Duplicate : V31MemoryWriteOutcome
    data object ApprovalRequired : V31MemoryWriteOutcome
    data class Rejected(val reasonCode: String) : V31MemoryWriteOutcome
}

data class V31LegacyBrainFact(
    val id: String,
    val safeSummary: String,
    val confidence: Double,
    val stale: Boolean,
)

data class V31LegacyRecallRequest(
    val appPackage: String,
    val pageKey: String?,
    val terms: Set<String>,
    val limit: Int,
)

fun interface V31LegacyBrainReader {
    fun recall(request: V31LegacyRecallRequest): List<V31LegacyBrainFact>
}

enum class V31KnowledgeOrigin { CURRENT_OBSERVATION, MEMORY_SERVICE, LEGACY_BRAIN }

data class V31KnowledgeItem(
    val id: String,
    val summary: String,
    val origin: V31KnowledgeOrigin,
    val confidence: Double,
    val stale: Boolean,
    val observedAtEpochMillis: Long,
)

/** Current phone evidence always ranks ahead of historical Memory/Brain records. */
object V31KnowledgeRanking {
    fun merge(
        currentObservation: List<V31KnowledgeItem>,
        memory: List<V31KnowledgeItem>,
        legacy: List<V31KnowledgeItem>,
        limit: Int,
    ): List<V31KnowledgeItem> {
        require(limit > 0)
        fun originRank(origin: V31KnowledgeOrigin) = when (origin) {
            V31KnowledgeOrigin.CURRENT_OBSERVATION -> 0
            V31KnowledgeOrigin.MEMORY_SERVICE -> 1
            V31KnowledgeOrigin.LEGACY_BRAIN -> 2
        }
        return (currentObservation + memory + legacy)
            .distinctBy { it.id }
            .sortedWith(
                compareBy<V31KnowledgeItem>({ originRank(it.origin) }, { it.stale })
                    .thenByDescending { it.observedAtEpochMillis }
                    .thenByDescending { it.confidence },
            )
            .take(limit)
    }
}

object V31MemorySanitizer {
    private val sensitiveKey = Regex("(?i)(password|passcode|pin|otp|token|secret|api[_-]?key|credential|typed[_-]?(text|value)|cvv|card[_-]?number)")
    private val suspiciousValue = listOf(
        Regex("(?i)\\b(bearer|password|passcode|otp|token|secret|api[_-]?key)\\s*[:=]\\s*\\S+"),
        Regex("\\bsk-[A-Za-z0-9_-]{8,}\\b"),
        Regex("(?i)\\b(otp|verification code)\\D{0,8}\\d{4,8}\\b"),
    )

    fun prepare(write: V31StructuredMemoryWrite): V31PreparedMemory {
        val safeFields = write.fields.filterNot { (key, value) ->
            sensitiveKey.containsMatchIn(key) || suspiciousValue.any { it.containsMatchIn(value) }
        }
        if (safeFields.isEmpty()) return V31PreparedMemory.Rejected("NO_SAFE_CONTENT")

        val verification = if (
            write.verificationState == MemoryVerificationState.VERIFIED && !write.verifiedRuntimeEvidence
        ) MemoryVerificationState.OBSERVED else write.verificationState
        val scope = MemoryScope(
            if (write.routineId == null) MemoryScopeKind.APP else MemoryScopeKind.ROUTINE,
            write.routineId ?: write.appPackage,
        )
        val digestSeed = buildString {
            append(write.producer.name)
            append('|').append(write.appPackage)
            append('|').append(write.routineId.orEmpty())
            append('|').append(write.evidenceId)
            safeFields.toSortedMap().forEach { (key, value) -> append('|').append(key).append('=').append(value) }
        }
        val recordId = "v31:${digest(digestSeed).take(40)}"
        val evidenceRef = "ev:${digest(write.evidenceId).take(40)}"
        val draft = MemoryDraft(
            recordId = recordId,
            schemaVersion = 1,
            source = MemoryActor("v31.${write.producer.name.lowercase()}", write.producer.sourceKind),
            provenance = MemoryProvenance(
                sourceSystem = "v31.${write.producer.name.lowercase()}",
                evidenceReferences = setOf(evidenceRef),
                observedAtEpochMillis = write.observedAtEpochMillis,
            ),
            confidence = write.confidence,
            verificationState = verification,
            scope = scope,
            sensitivity = write.sensitivity,
            memoryClass = write.memoryClass,
            content = MemoryContent(safeFields),
        )
        return when (val redaction = DefaultMemoryRedactor().redact(draft)) {
            is MemoryRedactionResult.Rejected -> V31PreparedMemory.Rejected(redaction.reasonCode)
            is MemoryRedactionResult.Safe -> V31PreparedMemory.Ready(
                MemoryWriteProposalRequest(
                    proposalId = "proposal:${digest(digestSeed).take(40)}",
                    draft = draft.copy(content = redaction.content),
                ),
            )
        }
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

class V31MemoryBridge(
    private val memory: CycloneMemoryService,
    private val legacyReader: V31LegacyBrainReader = V31LegacyBrainReader { emptyList() },
) {
    fun write(write: V31StructuredMemoryWrite): V31MemoryWriteOutcome {
        val prepared = V31MemorySanitizer.prepare(write)
        if (prepared is V31PreparedMemory.Rejected) return V31MemoryWriteOutcome.Rejected(prepared.reasonCode)
        val request = (prepared as V31PreparedMemory.Ready).proposal
        val proposal = memory.proposeWrite(request)
        return when (proposal.status) {
            MemoryProposalStatus.READY -> {
                val mutation = memory.commitApprovedWrite(proposal.proposalId)
                when (mutation.status) {
                    MemoryMutationStatus.COMMITTED, MemoryMutationStatus.REPLAYED ->
                        V31MemoryWriteOutcome.Committed(mutation.record?.recordId ?: request.draft.recordId)
                    MemoryMutationStatus.DUPLICATE -> V31MemoryWriteOutcome.Duplicate
                    else -> V31MemoryWriteOutcome.Rejected(mutation.reasonCode)
                }
            }
            MemoryProposalStatus.DUPLICATE -> V31MemoryWriteOutcome.Duplicate
            MemoryProposalStatus.APPROVAL_REQUIRED -> V31MemoryWriteOutcome.ApprovalRequired
            else -> V31MemoryWriteOutcome.Rejected(proposal.reasonCode)
        }
    }

    fun recall(
        appPackage: String,
        pageKey: String?,
        terms: Set<String>,
        currentObservation: List<V31KnowledgeItem>,
        limit: Int = 12,
    ): List<V31KnowledgeItem> {
        require(appPackage.isNotBlank() && limit > 0)
        val effectiveTerms = terms.filter(String::isNotBlank).toSet().ifEmpty { setOf(pageKey ?: appPackage) }
        val records = memory.recall(
            MemoryRecallRequest(
                scope = MemoryScope(MemoryScopeKind.APP, appPackage),
                terms = effectiveTerms,
                limit = limit,
            ),
        ).map(::asKnowledge)
        val legacy = legacyReader.recall(V31LegacyRecallRequest(appPackage, pageKey, effectiveTerms, limit))
            .map {
                V31KnowledgeItem(
                    id = "legacy:${it.id}",
                    summary = it.safeSummary,
                    origin = V31KnowledgeOrigin.LEGACY_BRAIN,
                    confidence = it.confidence,
                    stale = it.stale,
                    observedAtEpochMillis = 0,
                )
            }
        return V31KnowledgeRanking.merge(currentObservation, records, legacy, limit)
    }

    private fun asKnowledge(record: MemoryRecord): V31KnowledgeItem = V31KnowledgeItem(
        id = "memory:${record.recordId}",
        summary = record.content.fields.toSortedMap().entries.joinToString(" · ") { (key, value) -> "$key: $value" },
        origin = V31KnowledgeOrigin.MEMORY_SERVICE,
        confidence = record.confidence,
        stale = record.archived || record.verificationState == MemoryVerificationState.STALE,
        observedAtEpochMillis = record.provenance.observedAtEpochMillis,
    )
}
