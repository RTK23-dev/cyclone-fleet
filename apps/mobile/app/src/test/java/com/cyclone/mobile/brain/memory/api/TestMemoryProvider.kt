package com.cyclone.mobile.brain.memory.api

internal class TestMemoryProvider(
    override val providerId: String = "memory.test",
) : MemoryStoreProvider {
    private val records = linkedMapOf<Pair<MemoryScope, String>, MemoryRecord>()
    var mutationCalls: Int = 0
        private set

    override fun query(query: MemoryQuery): List<MemoryRecord> = records.values.filter {
        it.scope == query.scope && it.memoryClass in query.memoryClasses && (query.includeArchived || !it.archived)
    }

    override fun recall(request: MemoryRecallRequest): List<MemoryRecord> {
        val terms = request.terms.map { it.lowercase() }
        return records.values.filter { record ->
            record.scope == request.scope &&
                record.memoryClass in request.memoryClasses &&
                !record.archived &&
                terms.any { term -> record.content.fields.values.any { it.lowercase().contains(term) } }
        }
    }

    override fun get(reference: MemoryRecordRef): MemoryRecord? = records[reference.scope to reference.recordId]

    override fun count(scope: MemoryScope): Int = records.values.count { it.scope == scope }

    override fun findByFingerprint(scope: MemoryScope, fingerprint: String): MemoryRecord? =
        records.values.firstOrNull { it.scope == scope && it.contentFingerprint == fingerprint }

    override fun apply(command: AuthorizedMemoryMutation): MemoryProviderMutationResult {
        mutationCalls += 1
        return when (command) {
            is AuthorizedMemoryMutation.Insert -> {
                val key = command.record.scope to command.record.recordId
                if (key in records) MemoryProviderMutationResult.AlreadyExists
                else {
                    records[key] = command.record
                    MemoryProviderMutationResult.Applied(command.record)
                }
            }

            is AuthorizedMemoryMutation.Replace -> {
                val key = command.record.scope to command.record.recordId
                val current = records[key] ?: return MemoryProviderMutationResult.NotFound
                if (current.recordVersion != command.expectedVersion) {
                    MemoryProviderMutationResult.StaleVersion(current.recordVersion)
                } else {
                    records[key] = command.record
                    MemoryProviderMutationResult.Applied(command.record)
                }
            }

            is AuthorizedMemoryMutation.Remove -> {
                val key = command.reference.scope to command.reference.recordId
                val current = records[key] ?: return MemoryProviderMutationResult.NotFound
                if (current.recordVersion != command.expectedVersion) {
                    MemoryProviderMutationResult.StaleVersion(current.recordVersion)
                } else {
                    records.remove(key)
                    MemoryProviderMutationResult.Applied(null)
                }
            }

            is AuthorizedMemoryMutation.Archive -> {
                val key = command.record.scope to command.record.recordId
                val current = records[key] ?: return MemoryProviderMutationResult.NotFound
                if (current.recordVersion != command.expectedVersion) {
                    MemoryProviderMutationResult.StaleVersion(current.recordVersion)
                } else {
                    records[key] = command.record
                    MemoryProviderMutationResult.Applied(command.record)
                }
            }
        }
    }
}
