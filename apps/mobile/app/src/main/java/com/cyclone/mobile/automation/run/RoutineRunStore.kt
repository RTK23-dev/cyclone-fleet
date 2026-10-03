package com.cyclone.mobile.automation.run

interface RoutineRunStore {
    fun save(run: RoutineRunRecord)
    fun load(runId: RoutineRunId): RoutineRunRecord?
    fun list(limit: Int = 100): List<RoutineRunRecord>
    fun delete(runId: RoutineRunId): Boolean
}

class InMemoryRoutineRunStore(
    private val records: MutableMap<RoutineRunId, RoutineRunRecord> = mutableMapOf(),
    private val maximumRuns: Int = 100,
) : RoutineRunStore {
    init { require(maximumRuns in 1..10_000) }

    @Synchronized
    override fun save(run: RoutineRunRecord) {
        records[run.runId] = run
        trim()
    }

    @Synchronized
    override fun load(runId: RoutineRunId): RoutineRunRecord? = records[runId]

    @Synchronized
    override fun list(limit: Int): List<RoutineRunRecord> = records.values
        .sortedWith(compareByDescending<RoutineRunRecord> { it.updatedAtEpochMillis }.thenBy { it.runId })
        .take(limit.coerceIn(1, maximumRuns))

    @Synchronized
    override fun delete(runId: RoutineRunId): Boolean = records.remove(runId) != null

    private fun trim() {
        records.values
            .sortedWith(compareByDescending<RoutineRunRecord> { it.updatedAtEpochMillis }.thenBy { it.runId })
            .drop(maximumRuns)
            .forEach { records.remove(it.runId) }
    }
}
