package com.cyclone.mobile.brain.graphv2

interface TemporalGraphStore {
    fun registerNode(node: GraphNode)
    fun node(id: GraphNodeId): GraphNode?
    fun nodes(): List<GraphNode>
    fun record(edge: TemporalKnowledgeEdge): EdgeRecordResult
    fun history(key: GraphEdgeKey): List<TemporalKnowledgeEdge>
    fun currentEdges(includeStale: Boolean = false): List<TemporalKnowledgeEdge>
}

class InMemoryTemporalGraphStore : TemporalGraphStore {
    private val nodesById = linkedMapOf<GraphNodeId, GraphNode>()
    private val recordsByKey = linkedMapOf<GraphEdgeKey, MutableList<TemporalKnowledgeEdge>>()

    @Synchronized
    override fun registerNode(node: GraphNode) {
        val existing = nodesById[node.id]
        require(existing == null || existing == node) { "Conflicting node definition for ${node.id}" }
        nodesById[node.id] = node
    }

    @Synchronized
    override fun node(id: GraphNodeId): GraphNode? = nodesById[id]

    @Synchronized
    override fun nodes(): List<GraphNode> = nodesById.values.sortedBy { it.id }

    @Synchronized
    override fun record(edge: TemporalKnowledgeEdge): EdgeRecordResult {
        val from = nodesById[edge.key.from]
            ?: return EdgeRecordResult.Rejected(EdgeRejectionReason.UNKNOWN_NODE)
        val to = nodesById[edge.key.to]
            ?: return EdgeRecordResult.Rejected(EdgeRejectionReason.UNKNOWN_NODE)
        if (!GraphV2Schema.accepts(edge.key.type, from.type, to.type)) {
            return EdgeRecordResult.Rejected(EdgeRejectionReason.INVALID_RELATION)
        }
        val existing = recordsByKey[edge.key].orEmpty()
        val sameEvidence = existing.firstOrNull {
            it.evidence.source.evidenceId == edge.evidence.source.evidenceId
        }
        if (sameEvidence != null) {
            return if (sameEvidence == edge) EdgeRecordResult.AlreadyRecorded(sameEvidence)
            else EdgeRecordResult.Rejected(EdgeRejectionReason.DUPLICATE_EVIDENCE_CONFLICT)
        }
        if (!edge.evidence.source.kind.deterministic && existing.none { it.evidence.source.kind.deterministic }) {
            return EdgeRecordResult.Rejected(EdgeRejectionReason.INFERENCE_CANNOT_CREATE_STRUCTURE)
        }
        if (edge.evidence.verificationState == GraphVerificationState.VERIFIED &&
            !edge.evidence.source.kind.deterministic
        ) {
            return EdgeRecordResult.Rejected(EdgeRejectionReason.VERIFICATION_REQUIRES_DETERMINISTIC_EVIDENCE)
        }
        if (edge.evidence.verificationScope == GraphVerificationScope.PHYSICAL_DEVICE &&
            !edge.evidence.source.physicalDeviceEvidence
        ) {
            return EdgeRecordResult.Rejected(EdgeRejectionReason.PHYSICAL_VERIFICATION_NOT_PROVEN)
        }
        if (edge.evidence.verificationState == GraphVerificationState.VERIFIED &&
            (edge.evidence.successCount == 0 || edge.evidence.lastSucceededAtEpochMillis == null)
        ) {
            return EdgeRecordResult.Rejected(EdgeRejectionReason.VERIFIED_WITHOUT_SUCCESS)
        }
        recordsByKey.getOrPut(edge.key) { mutableListOf() }.add(edge)
        return EdgeRecordResult.Recorded(edge)
    }

    @Synchronized
    override fun history(key: GraphEdgeKey): List<TemporalKnowledgeEdge> =
        recordsByKey[key].orEmpty().sortedWith(edgeHistoryOrder)

    @Synchronized
    override fun currentEdges(includeStale: Boolean): List<TemporalKnowledgeEdge> =
        recordsByKey.values.mapNotNull { it.maxWithOrNull(edgeCurrentOrder) }
            .filter { includeStale || it.evidence.staleness == GraphStaleness.CURRENT }
            .sortedWith(compareBy({ it.key }, { it.evidence.source.evidenceId }))

    private val edgeHistoryOrder = compareBy<TemporalKnowledgeEdge>(
        { it.evidence.observedAtEpochMillis },
        { it.evidence.source.evidenceId },
    )
    private val edgeCurrentOrder = compareBy<TemporalKnowledgeEdge>(
        { it.evidence.observedAtEpochMillis },
        { verificationRank(it.evidence.verificationState) },
        { it.evidence.source.evidenceId },
    )

    private fun verificationRank(state: GraphVerificationState): Int = when (state) {
        GraphVerificationState.REJECTED -> 0
        GraphVerificationState.UNVERIFIED -> 1
        GraphVerificationState.OBSERVED -> 2
        GraphVerificationState.VERIFIED -> 3
    }
}

object GraphV2Schema {
    fun accepts(edge: GraphEdgeType, from: GraphNodeType, to: GraphNodeType): Boolean = when (edge) {
        GraphEdgeType.CONTAINS -> when (from) {
            GraphNodeType.APP -> to in setOf(GraphNodeType.ACTIVITY, GraphNodeType.PAGE)
            GraphNodeType.ACTIVITY -> to == GraphNodeType.PAGE
            GraphNodeType.PAGE -> to in setOf(GraphNodeType.ELEMENT, GraphNodeType.TRANSITION)
            else -> false
        }
        GraphEdgeType.NAVIGATES_TO -> from == GraphNodeType.PAGE && to == GraphNodeType.PAGE
        GraphEdgeType.OPENS, GraphEdgeType.SUBMITS ->
            from in setOf(GraphNodeType.PAGE, GraphNodeType.ELEMENT, GraphNodeType.TRANSITION) &&
                to == GraphNodeType.PAGE
        GraphEdgeType.REQUIRES ->
            from in setOf(GraphNodeType.TRANSITION, GraphNodeType.ROUTINE, GraphNodeType.CAPABILITY) &&
                to in setOf(GraphNodeType.PAGE, GraphNodeType.SELECTOR, GraphNodeType.CAPABILITY)
        GraphEdgeType.SCROLL_REVEALS -> from == GraphNodeType.PAGE && to == GraphNodeType.ELEMENT
        GraphEdgeType.SELECTOR_MATCHES -> from == GraphNodeType.SELECTOR && to == GraphNodeType.ELEMENT
        GraphEdgeType.RECOVERED_BY -> from != GraphNodeType.APP && to != GraphNodeType.APP
        GraphEdgeType.USED_BY_ROUTINE -> from != GraphNodeType.ROUTINE && to == GraphNodeType.ROUTINE
        GraphEdgeType.SUPERSEDES -> from == to
    }
}
