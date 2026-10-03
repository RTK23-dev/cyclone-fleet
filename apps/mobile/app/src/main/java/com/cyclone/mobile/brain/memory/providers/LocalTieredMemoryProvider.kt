package com.cyclone.mobile.brain.memory.providers

import com.cyclone.mobile.brain.memory.api.AuthorizedMemoryMutation
import com.cyclone.mobile.brain.memory.api.MemoryClass
import com.cyclone.mobile.brain.memory.api.MemoryProviderMutationResult
import com.cyclone.mobile.brain.memory.api.MemoryQuery
import com.cyclone.mobile.brain.memory.api.MemoryRecallRequest
import com.cyclone.mobile.brain.memory.api.MemoryRecord
import com.cyclone.mobile.brain.memory.api.MemoryRecordRef
import com.cyclone.mobile.brain.memory.api.MemoryScope
import com.cyclone.mobile.brain.memory.api.MemorySensitivity
import com.cyclone.mobile.brain.memory.api.MemoryStoreProvider
import com.cyclone.mobile.brain.memory.tiered.AppGraphProjectionPolicy
import com.cyclone.mobile.brain.memory.tiered.DeterministicTierSelector
import com.cyclone.mobile.brain.memory.tiered.MemoryTier
import com.cyclone.mobile.brain.memory.tiered.TieredFreshnessPolicy
import com.cyclone.mobile.brain.memory.tiered.TieredMemoryBudgets
import com.cyclone.mobile.brain.memory.tiered.TieredMemoryRanker
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

fun interface TieredMemoryClock {
    fun nowEpochMillis(): Long
}

enum class LocalMemoryLoadState {
    EMPTY,
    READY,
    CORRUPT,
}

data class LocalTieredMemoryDiagnostics(
    val loadState: LocalMemoryLoadState,
    val loadFailureCode: String?,
    val documentMirrorFailureCode: String?,
    val missionHotRecords: Int,
    val knowledgeDocumentRecords: Int,
    val structuralRecords: Int,
)

/**
 * Local-first provider behind CycloneMemoryService. It has no producer-facing write helpers: all
 * mutations arrive through the frozen AuthorizedMemoryMutation seam.
 */
class LocalTieredMemoryProvider(
    private val root: Path,
    private val budgets: TieredMemoryBudgets = TieredMemoryBudgets(),
    freshness: TieredFreshnessPolicy = TieredFreshnessPolicy(),
    private val clock: TieredMemoryClock = TieredMemoryClock(System::currentTimeMillis),
) : MemoryStoreProvider {
    override val providerId: String = "memory.tiered.local"

    private data class RecordKey(val scope: MemoryScope, val recordId: String)

    private val ranker = TieredMemoryRanker(freshness)
    private val storePath = root.resolve("tiered-memory-v1.bin")
    private val documentMirrorPath = root.resolve("knowledge-documents.md")
    private val records = linkedMapOf<RecordKey, MemoryRecord>()
    private var loadState = LocalMemoryLoadState.EMPTY
    private var loadFailureCode: String? = null
    private var documentMirrorFailureCode: String? = null

    init {
        load()
    }

    @Synchronized
    override fun query(query: MemoryQuery): List<MemoryRecord> = records.values.asSequence()
        .filter { it.scope == query.scope }
        .filter { it.memoryClass in query.memoryClasses }
        .filter { query.includeArchived || !it.archived }
        .map { ranker.surfaceStaleness(it, clock.nowEpochMillis()) }
        .sortedWith(ranker.recallOrder(clock.nowEpochMillis()))
        .take(query.limit)
        .toList()

    @Synchronized
    override fun recall(request: MemoryRecallRequest): List<MemoryRecord> {
        // Including runtime hints means normal mission recall: documents/structural records require
        // a separate explicit request that excludes RUNTIME_HINT.
        val effectiveClasses = if (MemoryClass.RUNTIME_HINT in request.memoryClasses) {
            setOf(MemoryClass.RUNTIME_HINT)
        } else {
            request.memoryClasses
        }
        val terms = request.terms.map { it.lowercase() }
        val now = clock.nowEpochMillis()
        return records.values.asSequence()
            .filter { it.scope == request.scope && !it.archived }
            .filter { it.memoryClass in effectiveClasses }
            .filter { record ->
                terms.any { term ->
                    record.content.fields.any { (key, value) ->
                        key.lowercase().contains(term) || value.lowercase().contains(term)
                    }
                }
            }
            .map { ranker.surfaceStaleness(it, now) }
            .sortedWith(ranker.recallOrder(now))
            .take(request.limit)
            .toList()
    }

    @Synchronized
    override fun get(reference: MemoryRecordRef): MemoryRecord? =
        records[RecordKey(reference.scope, reference.recordId)]?.let {
            ranker.surfaceStaleness(it, clock.nowEpochMillis())
        }

    @Synchronized
    override fun count(scope: MemoryScope): Int = records.values.count { it.scope == scope }

    @Synchronized
    override fun findByFingerprint(scope: MemoryScope, fingerprint: String): MemoryRecord? =
        records.values
            .filter { it.scope == scope && it.contentFingerprint == fingerprint }
            .minByOrNull { it.recordId }
            ?.let { ranker.surfaceStaleness(it, clock.nowEpochMillis()) }

    @Synchronized
    override fun apply(command: AuthorizedMemoryMutation): MemoryProviderMutationResult {
        if (loadState == LocalMemoryLoadState.CORRUPT) {
            return MemoryProviderMutationResult.Failed("LOCAL_STORE_CORRUPT")
        }
        val candidate = LinkedHashMap(records)
        val resultRecord = when (command) {
            is AuthorizedMemoryMutation.Insert -> {
                val record = command.record
                val key = RecordKey(record.scope, record.recordId)
                if (key in candidate) return MemoryProviderMutationResult.AlreadyExists
                if (candidate.values.any {
                        it.scope == record.scope && it.contentFingerprint == record.contentFingerprint
                    }
                ) {
                    return MemoryProviderMutationResult.AlreadyExists
                }
                candidate[key] = record
                record
            }

            is AuthorizedMemoryMutation.Replace -> {
                val record = command.record
                val key = RecordKey(record.scope, record.recordId)
                val current = candidate[key] ?: return MemoryProviderMutationResult.NotFound
                if (current.recordVersion != command.expectedVersion) {
                    return MemoryProviderMutationResult.StaleVersion(current.recordVersion)
                }
                if (candidate.values.any {
                        it.scope == record.scope && it.recordId != record.recordId &&
                            it.contentFingerprint == record.contentFingerprint
                    }
                ) {
                    return MemoryProviderMutationResult.AlreadyExists
                }
                candidate[key] = record
                record
            }

            is AuthorizedMemoryMutation.Remove -> {
                val key = RecordKey(command.reference.scope, command.reference.recordId)
                val current = candidate[key] ?: return MemoryProviderMutationResult.NotFound
                if (current.recordVersion != command.expectedVersion) {
                    return MemoryProviderMutationResult.StaleVersion(current.recordVersion)
                }
                candidate.remove(key)
                null
            }

            is AuthorizedMemoryMutation.Archive -> {
                val record = command.record
                val key = RecordKey(record.scope, record.recordId)
                val current = candidate[key] ?: return MemoryProviderMutationResult.NotFound
                if (current.recordVersion != command.expectedVersion) {
                    return MemoryProviderMutationResult.StaleVersion(current.recordVersion)
                }
                candidate[key] = record
                record
            }
        }

        resultRecord?.let { AppGraphProjectionPolicy.validate(it) }?.let {
            return MemoryProviderMutationResult.Failed(it)
        }
        val budgeted = enforceBudgets(candidate, resultRecord)
        if (budgeted is BudgetOutcome.Rejected) {
            return MemoryProviderMutationResult.Failed(budgeted.reasonCode)
        }
        budgeted as BudgetOutcome.Accepted
        try {
            LocalTieredMemoryCodec.writeAtomically(storePath, budgeted.records.values)
        } catch (_: Exception) {
            return MemoryProviderMutationResult.Failed("LOCAL_PERSISTENCE_FAILED")
        }
        records.clear()
        records.putAll(budgeted.records)
        loadState = if (records.isEmpty()) LocalMemoryLoadState.EMPTY else LocalMemoryLoadState.READY
        refreshDocumentMirror()
        return MemoryProviderMutationResult.Applied(resultRecord)
    }

    @Synchronized
    fun diagnostics(): LocalTieredMemoryDiagnostics = LocalTieredMemoryDiagnostics(
        loadState,
        loadFailureCode,
        documentMirrorFailureCode,
        records.values.count { DeterministicTierSelector.select(it) == MemoryTier.MISSION_HOT },
        records.values.count { DeterministicTierSelector.select(it) == MemoryTier.KNOWLEDGE_DOCUMENTS },
        records.values.count { DeterministicTierSelector.select(it) == MemoryTier.STRUCTURAL_DURABLE },
    )

    private sealed class BudgetOutcome {
        data class Accepted(val records: LinkedHashMap<RecordKey, MemoryRecord>) : BudgetOutcome()
        data class Rejected(val reasonCode: String) : BudgetOutcome()
    }

    private fun enforceBudgets(
        candidate: LinkedHashMap<RecordKey, MemoryRecord>,
        changedRecord: MemoryRecord?,
    ): BudgetOutcome {
        val grouped = candidate.values.groupBy { DeterministicTierSelector.select(it) to it.scope }
        grouped.forEach { (tierAndScope, scopedRecords) ->
            val (tier, scope) = tierAndScope
            val budget = budgets.forTier(tier)
            if (scopedRecords.any { it.content.estimatedUtf8Bytes() > budget.maxSingleRecordBytes }) {
                return BudgetOutcome.Rejected("${tier.name}_RECORD_BUDGET")
            }
            if (tier != MemoryTier.MISSION_HOT) {
                if (scopedRecords.size > budget.maxRecordsPerScope ||
                    scopedRecords.sumOf { it.content.estimatedUtf8Bytes() } > budget.maxContentBytesPerScope
                ) {
                    return BudgetOutcome.Rejected("${tier.name}_SCOPE_BUDGET")
                }
                return@forEach
            }

            val now = clock.nowEpochMillis()
            val selected = linkedSetOf<RecordKey>()
            var bytes = 0
            scopedRecords.sortedWith(ranker.evictionOrder(now)).forEach { record ->
                val recordBytes = record.content.estimatedUtf8Bytes()
                if (selected.size < budget.maxRecordsPerScope && bytes + recordBytes <= budget.maxContentBytesPerScope) {
                    selected += RecordKey(record.scope, record.recordId)
                    bytes += recordBytes
                }
            }
            val changedKey = changedRecord?.let { RecordKey(it.scope, it.recordId) }
            if (changedRecord != null && changedRecord.scope == scope &&
                DeterministicTierSelector.select(changedRecord) == MemoryTier.MISSION_HOT &&
                changedKey !in selected
            ) {
                return BudgetOutcome.Rejected("MISSION_HOT_EVICTION_REJECTED")
            }
            scopedRecords.forEach { record ->
                val key = RecordKey(record.scope, record.recordId)
                if (key !in selected) candidate.remove(key)
            }
        }
        return BudgetOutcome.Accepted(candidate)
    }

    private fun load() {
        if (!Files.exists(storePath)) {
            loadState = LocalMemoryLoadState.EMPTY
            return
        }
        runCatching { LocalTieredMemoryCodec.read(storePath) }
            .onSuccess { loaded ->
                loaded.forEach { record ->
                    val key = RecordKey(record.scope, record.recordId)
                    require(key !in records) { "Duplicate local memory record key" }
                    require(AppGraphProjectionPolicy.validate(record) == null) { "Unsafe App Graph projection" }
                    records[key] = record
                }
                loadState = if (records.isEmpty()) LocalMemoryLoadState.EMPTY else LocalMemoryLoadState.READY
                refreshDocumentMirror()
            }
            .onFailure {
                records.clear()
                loadState = LocalMemoryLoadState.CORRUPT
                loadFailureCode = "LOCAL_STORE_LOAD_FAILED"
            }
    }

    private fun refreshDocumentMirror() {
        val documents = records.values
            .filter { DeterministicTierSelector.select(it) == MemoryTier.KNOWLEDGE_DOCUMENTS && !it.archived }
            .sortedWith(compareBy<MemoryRecord>({ it.scope.kind.name }, { it.scope.scopeId }, { it.recordId }))
        val text = buildString {
            appendLine("# Cyclone Knowledge Documents")
            appendLine()
            appendLine("Local, policy-filtered document memory. Runtime recall does not inject this file by default.")
            documents.forEach { record ->
                appendLine()
                appendLine("## ${record.recordId}")
                appendLine("- Scope: `${record.scope.kind}:${record.scope.scopeId}`")
                appendLine("- Version: ${record.recordVersion}")
                appendLine("- Verification: ${record.verificationState}")
                if (record.sensitivity == MemorySensitivity.SENSITIVE) {
                    appendLine("- Content: `[SENSITIVE SAFE FIELDS OMITTED FROM MIRROR]`")
                } else {
                    record.content.fields.toSortedMap().forEach { (key, value) ->
                        appendLine("- $key: ${value.replace('\n', ' ').take(4_000)}")
                    }
                }
            }
            appendLine()
        }
        documentMirrorFailureCode = runCatching {
            Files.createDirectories(root)
            val temporary = documentMirrorPath.resolveSibling("${documentMirrorPath.fileName}.staging")
            Files.write(temporary, text.toByteArray(Charsets.UTF_8))
            try {
                Files.move(
                    temporary,
                    documentMirrorPath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, documentMirrorPath, StandardCopyOption.REPLACE_EXISTING)
            }
        }.fold(onSuccess = { null }, onFailure = { "DOCUMENT_MIRROR_WRITE_FAILED" })
    }
}
