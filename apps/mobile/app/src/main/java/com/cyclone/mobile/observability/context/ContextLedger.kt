package com.cyclone.mobile.observability.context

import com.cyclone.mobile.observability.events.ContextDecisionEvent
import com.cyclone.mobile.observability.events.ContextEventFactory
import com.cyclone.mobile.observability.events.ContextEventRequest
import com.cyclone.mobile.observability.events.ContextPayloadBudget
import com.cyclone.mobile.observability.events.DecisionStage
import com.cyclone.mobile.platform.event.EventEnvelope

data class ContextLedgerRetention(
    val maxEvents: Int = 1_000,
    val maxDecisions: Int = 100,
    val maxEventsPerDecision: Int = 32,
    val maxEstimatedBytes: Int = 2_000_000,
) {
    init {
        require(maxEvents > 0 && maxDecisions > 0 && maxEventsPerDecision > 0)
        require(maxEstimatedBytes >= 1_024) { "Ledger byte budget must be at least 1024" }
    }
}

data class ContextLedgerQuery(
    val correlationId: String? = null,
    val missionId: String? = null,
    val stages: Set<DecisionStage> = emptySet(),
    val fromEpochMillisInclusive: Long = 0,
    val toEpochMillisInclusive: Long = Long.MAX_VALUE,
    val limit: Int = 100,
) {
    init {
        require(fromEpochMillisInclusive >= 0 && toEpochMillisInclusive >= fromEpochMillisInclusive)
        require(limit > 0)
    }
}

data class AppendOutcome(
    val envelope: EventEnvelope<ContextDecisionEvent>,
    val replayed: Boolean,
    val evictedEventCount: Int,
)

/** Persistence is snapshot-based so retention and writes are committed atomically by an adapter. */
interface ContextLedgerPersistence {
    fun load(): List<EventEnvelope<ContextDecisionEvent>>
    fun save(events: List<EventEnvelope<ContextDecisionEvent>>)
}

class InMemoryContextLedgerPersistence(
    initialEvents: List<EventEnvelope<ContextDecisionEvent>> = emptyList(),
) : ContextLedgerPersistence {
    private var snapshot = initialEvents.toList()

    @Synchronized
    override fun load(): List<EventEnvelope<ContextDecisionEvent>> = snapshot.toList()

    @Synchronized
    override fun save(events: List<EventEnvelope<ContextDecisionEvent>>) {
        snapshot = events.toList()
    }
}

class ContextLedger(
    private val persistence: ContextLedgerPersistence,
    private val retention: ContextLedgerRetention = ContextLedgerRetention(),
    private val payloadBudget: ContextPayloadBudget = ContextPayloadBudget(),
) {
    private var events: List<EventEnvelope<ContextDecisionEvent>>

    init {
        val loaded = persistence.load()
        events = normalizeLoaded(loaded)
        if (events != loaded) persistence.save(events)
    }

    @Synchronized
    fun append(request: ContextEventRequest): AppendOutcome {
        val envelope = ContextEventFactory.create(request, payloadBudget)
        val replay = events.firstOrNull { it.eventId == envelope.eventId }
        if (replay != null) {
            require(replay == envelope) { "Event id ${envelope.eventId} was reused with different content" }
            return AppendOutcome(replay, replayed = true, evictedEventCount = 0)
        }
        require(estimatedBytes(envelope) <= retention.maxEstimatedBytes) {
            "A single event exceeds the ledger retention byte budget"
        }
        val candidate = retain(events + envelope)
        require(candidate.any { it.eventId == envelope.eventId }) {
            "New event could not fit within ledger retention bounds"
        }
        val evicted = events.size + 1 - candidate.size
        persistence.save(candidate)
        events = candidate
        return AppendOutcome(envelope, replayed = false, evictedEventCount = evicted)
    }

    @Synchronized
    fun query(query: ContextLedgerQuery = ContextLedgerQuery()): List<EventEnvelope<ContextDecisionEvent>> =
        events.asSequence()
            .filter { query.correlationId == null || it.correlationId == query.correlationId }
            .filter { query.missionId == null || it.missionId == query.missionId }
            .filter { query.stages.isEmpty() || it.payload.stage in query.stages }
            .filter { it.timestampEpochMillis in query.fromEpochMillisInclusive..query.toEpochMillisInclusive }
            .toList()
            .takeLast(query.limit)

    @Synchronized
    fun diagnostic(decisionId: String): ContextDecisionDiagnostic? =
        ContextDiagnosticBuilder.build(events.filter { it.correlationId == decisionId })

    @Synchronized
    fun compactDiagnostics(limit: Int = 20): List<ContextDecisionDiagnostic> {
        require(limit > 0)
        return events.groupBy { it.correlationId ?: it.payload.decisionId }
            .values
            .mapNotNull(ContextDiagnosticBuilder::build)
            .sortedWith(compareBy<ContextDecisionDiagnostic>({ it.lastEventEpochMillis }, { it.decisionId }))
            .takeLast(limit)
    }

    private fun normalizeLoaded(loaded: List<EventEnvelope<ContextDecisionEvent>>): List<EventEnvelope<ContextDecisionEvent>> {
        val seen = mutableSetOf<String>()
        loaded.forEach {
            require(it.moduleId == ContextEventFactory.MODULE_ID) { "Persistence contains a foreign module event" }
            require(it.schemaVersion == ContextEventFactory.SCHEMA_VERSION) { "Unsupported context event schema" }
            require(it.correlationId == it.payload.decisionId) { "Context event correlation mismatch" }
            require(seen.add(it.eventId)) { "Persistence contains duplicate event ids" }
        }
        return retain(loaded)
    }

    private fun retain(input: List<EventEnvelope<ContextDecisionEvent>>): List<EventEnvelope<ContextDecisionEvent>> {
        val ordered = input.sortedWith(EVENT_ORDER)
        val newestDecisions = ordered.groupBy { it.correlationId ?: it.payload.decisionId }
            .entries
            .sortedWith(compareBy<Map.Entry<String, List<EventEnvelope<ContextDecisionEvent>>>>(
                { it.value.maxOf(EventEnvelope<ContextDecisionEvent>::timestampEpochMillis) },
                { it.key },
            ).reversed())
            .take(retention.maxDecisions)
            .map { it.key }
            .toSet()

        val perDecisionCount = mutableMapOf<String, Int>()
        var bytes = 0
        val keptNewestFirst = mutableListOf<EventEnvelope<ContextDecisionEvent>>()
        ordered.asReversed().forEach { event ->
            val decision = event.correlationId ?: event.payload.decisionId
            if (decision !in newestDecisions || keptNewestFirst.size >= retention.maxEvents) return@forEach
            val count = perDecisionCount.getOrDefault(decision, 0)
            val eventBytes = estimatedBytes(event)
            if (count < retention.maxEventsPerDecision && bytes + eventBytes <= retention.maxEstimatedBytes) {
                keptNewestFirst += event
                perDecisionCount[decision] = count + 1
                bytes += eventBytes
            }
        }
        return keptNewestFirst.sortedWith(EVENT_ORDER)
    }

    private fun estimatedBytes(event: EventEnvelope<ContextDecisionEvent>): Int {
        val payload = event.payload
        return 256 +
            event.eventId.length + event.eventType.length + (event.missionId?.length ?: 0) +
            (event.sessionId?.length ?: 0) + payload.decisionId.length +
            payload.contextSources.sumOf { source ->
                64 + source.evidenceRefs.sumOf { it.toString().length }
            } +
            payload.knowledgeRefs.sumOf { it.toString().length } +
            (payload.proposedAction?.parameterNames?.sumOf(String::length) ?: 0)
    }

    private companion object {
        val EVENT_ORDER = compareBy<EventEnvelope<ContextDecisionEvent>>(
            EventEnvelope<ContextDecisionEvent>::timestampEpochMillis,
            EventEnvelope<ContextDecisionEvent>::eventId,
        )
    }
}
