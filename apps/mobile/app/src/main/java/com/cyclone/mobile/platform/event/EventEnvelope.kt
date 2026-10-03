package com.cyclone.mobile.platform.event

import com.cyclone.mobile.platform.module.ModuleId

private val EVENT_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]*")
private val EVENT_TYPE_PATTERN = Regex("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)+")

enum class DataClassification {
    PUBLIC,
    INTERNAL,
    SENSITIVE,
    RESTRICTED,
}

data class RedactionMetadata(
    val classification: DataClassification = DataClassification.INTERNAL,
    val redactedFields: Set<String> = emptySet(),
    val containsSensitiveData: Boolean = false,
    val redactionPolicyVersion: Int = 1,
) {
    init {
        require(redactedFields.none { it.isBlank() }) { "Redacted field paths must not be blank" }
        require(redactionPolicyVersion >= 1) { "Redaction policy version must be at least 1" }
    }
}

/**
 * Stable metadata around a typed event payload. This is a contract, not an event bus or a
 * persistence policy. Consumers must apply the supplied redaction classification at boundaries.
 */
data class EventEnvelope<out T : Any>(
    val eventId: String,
    val eventType: String,
    val schemaVersion: Int,
    val timestampEpochMillis: Long,
    val missionId: String? = null,
    val sessionId: String? = null,
    val moduleId: ModuleId,
    val correlationId: String? = null,
    val payload: T,
    val redaction: RedactionMetadata = RedactionMetadata(),
) {
    init {
        require(EVENT_ID_PATTERN.matches(eventId)) { "Event id is invalid: $eventId" }
        require(EVENT_TYPE_PATTERN.matches(eventType)) { "Event type must be namespaced: $eventType" }
        require(schemaVersion >= 1) { "Event schema version must be at least 1" }
        require(timestampEpochMillis >= 0) { "Event timestamp must be non-negative" }
        require(missionId == null || missionId.isNotBlank()) { "Mission id must not be blank" }
        require(sessionId == null || sessionId.isNotBlank()) { "Session id must not be blank" }
        require(correlationId == null || correlationId.isNotBlank()) { "Correlation id must not be blank" }
    }
}
