package com.cyclone.mobile.brain.memory.api

private val MEMORY_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]*")

enum class MemoryScopeKind {
    SESSION,
    APP,
    ROUTINE,
    WORKSPACE_DEVICE,
    USER_APPROVED_GLOBAL,
}

data class MemoryScope(
    val kind: MemoryScopeKind,
    val scopeId: String,
) {
    init {
        require(MEMORY_ID_PATTERN.matches(scopeId)) { "Memory scope id is invalid: $scopeId" }
    }
}

enum class MemoryClass {
    RUNTIME_HINT,
    DOCUMENT_REFERENCE,
    STRUCTURAL_KNOWLEDGE,
}

enum class MemorySensitivity {
    PUBLIC,
    INTERNAL,
    SENSITIVE,
    RESTRICTED,
}

enum class MemoryVerificationState {
    UNVERIFIED,
    OBSERVED,
    VERIFIED,
    STALE,
}

enum class MemorySourceKind {
    USER,
    CYCLONE_RUNTIME,
    APP_LEARNER,
    AUTOMATION,
    AI_PROPOSAL,
    IMPORT,
    GATEWAY,
}

data class MemoryActor(
    val actorId: String,
    val sourceKind: MemorySourceKind,
) {
    init {
        require(MEMORY_ID_PATTERN.matches(actorId)) { "Memory actor id is invalid: $actorId" }
    }
}

data class MemoryProvenance(
    val sourceSystem: String,
    val evidenceReferences: Set<String>,
    val observedAtEpochMillis: Long,
) {
    init {
        require(MEMORY_ID_PATTERN.matches(sourceSystem)) { "Provenance source is invalid: $sourceSystem" }
        require(evidenceReferences.isNotEmpty()) { "Memory provenance needs at least one evidence reference" }
        require(evidenceReferences.all(MEMORY_ID_PATTERN::matches)) { "Evidence references must be opaque identifiers" }
        require(observedAtEpochMillis >= 0) { "Provenance time must be non-negative" }
    }
}

data class MemoryContent(val fields: Map<String, String>) {
    init {
        require(fields.isNotEmpty()) { "Memory content must not be empty" }
        require(fields.keys.none { it.isBlank() }) { "Memory field names must not be blank" }
    }

    fun estimatedUtf8Bytes(): Int = fields.entries.sumOf {
        it.key.toByteArray(Charsets.UTF_8).size + it.value.toByteArray(Charsets.UTF_8).size
    }
}

data class MemoryDraft(
    val recordId: String,
    val schemaVersion: Int,
    val source: MemoryActor,
    val provenance: MemoryProvenance,
    val confidence: Double,
    val verificationState: MemoryVerificationState,
    val scope: MemoryScope,
    val sensitivity: MemorySensitivity,
    val memoryClass: MemoryClass,
    val content: MemoryContent,
) {
    init {
        require(MEMORY_ID_PATTERN.matches(recordId)) { "Memory record id is invalid: $recordId" }
        require(schemaVersion >= 1) { "Memory schema version must be at least 1" }
        require(confidence in 0.0..1.0) { "Memory confidence must be between 0 and 1" }
    }
}

data class MemoryRecord(
    val recordId: String,
    val schemaVersion: Int,
    val recordVersion: Int,
    val source: MemoryActor,
    val provenance: MemoryProvenance,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val confidence: Double,
    val verificationState: MemoryVerificationState,
    val scope: MemoryScope,
    val sensitivity: MemorySensitivity,
    val memoryClass: MemoryClass,
    val content: MemoryContent,
    val contentFingerprint: String,
    val archived: Boolean = false,
) {
    init {
        require(MEMORY_ID_PATTERN.matches(recordId)) { "Memory record id is invalid: $recordId" }
        require(schemaVersion >= 1) { "Memory schema version must be at least 1" }
        require(recordVersion >= 1) { "Memory record version must be at least 1" }
        require(createdAtEpochMillis >= 0 && updatedAtEpochMillis >= createdAtEpochMillis) {
            "Memory timestamps are invalid"
        }
        require(confidence in 0.0..1.0) { "Memory confidence must be between 0 and 1" }
        require(contentFingerprint.matches(Regex("sha256:[0-9a-f]{64}"))) { "Memory fingerprint is invalid" }
    }
}

data class MemoryRecordRef(
    val recordId: String,
    val scope: MemoryScope,
) {
    init {
        require(MEMORY_ID_PATTERN.matches(recordId)) { "Memory record id is invalid: $recordId" }
    }
}

data class MemoryQuery(
    val scope: MemoryScope,
    val memoryClasses: Set<MemoryClass> = MemoryClass.entries.toSet(),
    val includeArchived: Boolean = false,
    val limit: Int = 20,
) {
    init {
        require(memoryClasses.isNotEmpty()) { "Memory query needs at least one class" }
        require(limit >= 1) { "Memory query limit must be positive" }
    }
}

data class MemoryRecallRequest(
    val scope: MemoryScope,
    val terms: Set<String>,
    val memoryClasses: Set<MemoryClass> = MemoryClass.entries.toSet(),
    val limit: Int = 8,
) {
    init {
        require(terms.isNotEmpty() && terms.none { it.isBlank() }) { "Recall terms must not be empty" }
        require(memoryClasses.isNotEmpty()) { "Memory recall needs at least one class" }
        require(limit >= 1) { "Memory recall limit must be positive" }
    }
}

data class MemoryBudgetPolicy(
    val maxRecordBytes: Int = 32_768,
    val maxRecordsPerScope: Int = 2_000,
    val maxQueryResults: Int = 100,
    val maxPendingProposals: Int = 100,
    val proposalLifetimeMillis: Long = 300_000,
    val supportedSchemaVersions: Set<Int> = setOf(1),
) {
    init {
        require(maxRecordBytes >= 1) { "Record byte budget must be positive" }
        require(maxRecordsPerScope >= 1) { "Scope record budget must be positive" }
        require(maxQueryResults >= 1) { "Query result budget must be positive" }
        require(maxPendingProposals >= 1) { "Pending proposal budget must be positive" }
        require(proposalLifetimeMillis >= 1) { "Proposal lifetime must be positive" }
        require(supportedSchemaVersions.isNotEmpty() && supportedSchemaVersions.all { it >= 1 }) {
            "Supported memory schema versions are invalid"
        }
    }
}
