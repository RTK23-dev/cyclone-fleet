package com.cyclone.mobile.brain.graphv2

private val GRAPH_ID_PATTERN = Regex("[a-zA-Z0-9][a-zA-Z0-9._:/=_-]{0,255}")

@JvmInline
value class GraphNodeId(val value: String) : Comparable<GraphNodeId> {
    init {
        require(GRAPH_ID_PATTERN.matches(value)) { "Invalid graph node id: $value" }
    }

    override fun compareTo(other: GraphNodeId): Int = value.compareTo(other.value)
    override fun toString(): String = value
}

enum class GraphNodeType {
    APP,
    ACTIVITY,
    PAGE,
    ELEMENT,
    SELECTOR,
    TRANSITION,
    ROUTINE,
    CAPABILITY,
}

sealed interface GraphNode {
    val id: GraphNodeId
    val type: GraphNodeType
    val displayName: String
}

data class AppNode(
    override val id: GraphNodeId,
    val packageName: String,
    override val displayName: String,
) : GraphNode {
    override val type = GraphNodeType.APP
}

data class ActivityNode(
    override val id: GraphNodeId,
    val packageName: String,
    val className: String,
    override val displayName: String = className,
) : GraphNode {
    override val type = GraphNodeType.ACTIVITY
}

data class PageNode(
    override val id: GraphNodeId,
    val packageName: String,
    val identity: String,
    override val displayName: String,
) : GraphNode {
    override val type = GraphNodeType.PAGE
}

data class ElementNode(
    override val id: GraphNodeId,
    val semanticName: String,
    override val displayName: String,
) : GraphNode {
    override val type = GraphNodeType.ELEMENT
}

/** Selector data is a stable sanitized key. Raw editable values must never be stored here. */
data class SelectorNode(
    override val id: GraphNodeId,
    val selectorKey: String,
    override val displayName: String,
) : GraphNode {
    override val type = GraphNodeType.SELECTOR
}

data class TransitionNode(
    override val id: GraphNodeId,
    val actionName: String,
    override val displayName: String,
) : GraphNode {
    override val type = GraphNodeType.TRANSITION
}

data class RoutineNode(
    override val id: GraphNodeId,
    val routineId: String,
    override val displayName: String,
) : GraphNode {
    override val type = GraphNodeType.ROUTINE
}

data class CapabilityNode(
    override val id: GraphNodeId,
    val capabilityId: String,
    override val displayName: String = capabilityId,
) : GraphNode {
    override val type = GraphNodeType.CAPABILITY
}

enum class GraphEdgeType {
    CONTAINS,
    NAVIGATES_TO,
    OPENS,
    SUBMITS,
    REQUIRES,
    SCROLL_REVEALS,
    SELECTOR_MATCHES,
    RECOVERED_BY,
    USED_BY_ROUTINE,
    SUPERSEDES,
}

data class GraphEdgeKey(
    val from: GraphNodeId,
    val type: GraphEdgeType,
    val to: GraphNodeId,
) : Comparable<GraphEdgeKey> {
    override fun compareTo(other: GraphEdgeKey): Int =
        compareValuesBy(this, other, { it.from.value }, { it.type.name }, { it.to.value })
}

enum class GraphEvidenceKind(val deterministic: Boolean) {
    ACCESSIBILITY_OBSERVATION(true),
    ACTION_AFTER_STATE(true),
    FOLLOW_ME_DEMONSTRATION(true),
    ROUTINE_TEACHING(true),
    LEGACY_GRAPH_IMPORT(true),
    USER_CORRECTION(true),
    CI_FIXTURE(true),
    MODEL_INFERENCE(false),
}

data class GraphEvidenceSource(
    val kind: GraphEvidenceKind,
    val evidenceId: String,
    val producer: String,
    val physicalDeviceEvidence: Boolean = false,
) {
    init {
        require(evidenceId.isNotBlank()) { "Evidence id must not be blank" }
        require(producer.isNotBlank()) { "Evidence producer must not be blank" }
        require(!physicalDeviceEvidence || kind.deterministic) {
            "Physical-device evidence must come from a deterministic source"
        }
        require(!physicalDeviceEvidence || kind != GraphEvidenceKind.CI_FIXTURE) {
            "CI fixtures cannot claim physical-device evidence"
        }
    }
}

enum class GraphVerificationState {
    UNVERIFIED,
    OBSERVED,
    VERIFIED,
    REJECTED,
}

enum class GraphVerificationScope {
    NONE,
    CI_CONTRACT,
    LOCAL_DEVICE,
    PHYSICAL_DEVICE,
}

enum class GraphStaleness {
    CURRENT,
    SUSPECT,
    STALE,
    SUPERSEDED,
}

data class AppVersionEvidence(
    val packageName: String,
    val versionName: String? = null,
    val versionCode: Long? = null,
) {
    init {
        require(packageName.isNotBlank()) { "App version package must not be blank" }
        require(versionCode == null || versionCode >= 0L) { "App version code must be non-negative" }
    }

    val stableIdentity: String get() = versionCode?.toString() ?: versionName.orEmpty()
}

data class TemporalEdgeEvidence(
    val source: GraphEvidenceSource,
    val confidence: Double,
    val observedAtEpochMillis: Long,
    val lastSucceededAtEpochMillis: Long?,
    val lastFailedAtEpochMillis: Long?,
    val successCount: Int,
    val failureCount: Int,
    val appVersion: AppVersionEvidence?,
    val verificationState: GraphVerificationState,
    val verificationScope: GraphVerificationScope,
    val staleness: GraphStaleness,
) {
    init {
        require(confidence in 0.0..1.0) { "Confidence must be between zero and one" }
        require(observedAtEpochMillis >= 0L) { "Observed time must be non-negative" }
        require(successCount >= 0 && failureCount >= 0) { "Evidence counts must be non-negative" }
        require(lastSucceededAtEpochMillis == null || lastSucceededAtEpochMillis >= 0L)
        require(lastFailedAtEpochMillis == null || lastFailedAtEpochMillis >= 0L)
        require(lastSucceededAtEpochMillis == null || lastSucceededAtEpochMillis <= observedAtEpochMillis)
        require(lastFailedAtEpochMillis == null || lastFailedAtEpochMillis <= observedAtEpochMillis)
        require(verificationState == GraphVerificationState.VERIFIED || verificationScope == GraphVerificationScope.NONE) {
            "Only verified evidence may carry a verification scope"
        }
        require(verificationState != GraphVerificationState.VERIFIED || verificationScope != GraphVerificationScope.NONE) {
            "Verified evidence requires an explicit scope"
        }
    }
}

data class TemporalKnowledgeEdge(
    val key: GraphEdgeKey,
    val evidence: TemporalEdgeEvidence,
)

enum class EdgeRejectionReason {
    UNKNOWN_NODE,
    INVALID_RELATION,
    DUPLICATE_EVIDENCE_CONFLICT,
    INFERENCE_CANNOT_CREATE_STRUCTURE,
    VERIFICATION_REQUIRES_DETERMINISTIC_EVIDENCE,
    PHYSICAL_VERIFICATION_NOT_PROVEN,
    VERIFIED_WITHOUT_SUCCESS,
}

sealed interface EdgeRecordResult {
    data class Recorded(val edge: TemporalKnowledgeEdge) : EdgeRecordResult
    data class AlreadyRecorded(val edge: TemporalKnowledgeEdge) : EdgeRecordResult
    data class Rejected(val reason: EdgeRejectionReason) : EdgeRecordResult
}

data class ReachablePage(
    val page: PageNode,
    val distance: Int,
)

data class BlastRadiusEntry(
    val node: GraphNode,
    val distance: Int,
    val via: GraphEdgeType,
)

data class AppUpdateChange(
    val edge: TemporalKnowledgeEdge,
    val currentVersionIdentity: String,
)
