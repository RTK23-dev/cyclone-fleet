package com.cyclone.mobile.brain.memory.api

interface MemoryStoreProvider {
    val providerId: String

    fun query(query: MemoryQuery): List<MemoryRecord>
    fun recall(request: MemoryRecallRequest): List<MemoryRecord>
    fun get(reference: MemoryRecordRef): MemoryRecord?
    fun count(scope: MemoryScope): Int
    fun findByFingerprint(scope: MemoryScope, fingerprint: String): MemoryRecord?

    /**
     * The only mutation entry point. Commands are created by CycloneMemoryService after its write
     * gate; providers must not expose a second public mutation API.
     */
    fun apply(command: AuthorizedMemoryMutation): MemoryProviderMutationResult
}

sealed class AuthorizedMemoryMutation protected constructor() {
    class Insert internal constructor(val record: MemoryRecord) : AuthorizedMemoryMutation()

    class Replace internal constructor(
        val record: MemoryRecord,
        val expectedVersion: Int,
    ) : AuthorizedMemoryMutation()

    class Remove internal constructor(
        val reference: MemoryRecordRef,
        val expectedVersion: Int,
    ) : AuthorizedMemoryMutation()

    class Archive internal constructor(
        val record: MemoryRecord,
        val expectedVersion: Int,
    ) : AuthorizedMemoryMutation()
}

sealed class MemoryProviderMutationResult {
    data class Applied(val record: MemoryRecord?) : MemoryProviderMutationResult()
    data class StaleVersion(val actualVersion: Int?) : MemoryProviderMutationResult()
    data object AlreadyExists : MemoryProviderMutationResult()
    data object NotFound : MemoryProviderMutationResult()
    data class Failed(val diagnosticCode: String) : MemoryProviderMutationResult()
}
