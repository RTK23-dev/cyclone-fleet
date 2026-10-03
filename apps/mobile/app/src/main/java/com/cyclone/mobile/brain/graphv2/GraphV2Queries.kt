package com.cyclone.mobile.brain.graphv2

class GraphV2Queries(private val store: TemporalGraphStore) {
    fun pagesThatCanReach(target: GraphNodeId, maxDepth: Int = 16): List<ReachablePage> {
        require(maxDepth >= 1) { "Maximum depth must be positive" }
        val page = store.node(target) as? PageNode ?: return emptyList()
        val navigationTypes = setOf(GraphEdgeType.NAVIGATES_TO, GraphEdgeType.OPENS, GraphEdgeType.SUBMITS)
        val reverse = store.currentEdges().filter { it.key.type in navigationTypes }
            .groupBy { it.key.to }
        val distance = linkedMapOf(page.id to 0)
        val queue = ArrayDeque<GraphNodeId>().apply { add(page.id) }
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val nextDistance = distance.getValue(current) + 1
            if (nextDistance > maxDepth) continue
            reverse[current].orEmpty().sortedBy { it.key.from }.forEach { edge ->
                if (store.node(edge.key.from) is PageNode && edge.key.from !in distance) {
                    distance[edge.key.from] = nextDistance
                    queue.add(edge.key.from)
                }
            }
        }
        return distance.entries.asSequence()
            .filter { it.key != target }
            .mapNotNull { (id, hops) -> (store.node(id) as? PageNode)?.let { ReachablePage(it, hops) } }
            .sortedWith(compareBy(ReachablePage::distance, { it.page.id.value }))
            .toList()
    }

    fun validSelectorsHere(pageId: GraphNodeId): List<SelectorNode> {
        val current = store.currentEdges()
        val elements = current.asSequence()
            .filter { it.key.from == pageId && it.key.type in setOf(GraphEdgeType.CONTAINS, GraphEdgeType.SCROLL_REVEALS) }
            .map { it.key.to }
            .filter { store.node(it) is ElementNode }
            .toSet()
        return current.asSequence()
            .filter { it.key.type == GraphEdgeType.SELECTOR_MATCHES && it.key.to in elements }
            .mapNotNull { store.node(it.key.from) as? SelectorNode }
            .distinctBy { it.id }
            .sortedBy { it.id }
            .toList()
    }

    fun routinesDependingOn(nodeId: GraphNodeId): List<RoutineNode> =
        store.currentEdges().asSequence()
            .filter {
                (it.key.type == GraphEdgeType.REQUIRES && it.key.to == nodeId) ||
                    (it.key.type == GraphEdgeType.USED_BY_ROUTINE && it.key.from == nodeId)
            }
            .mapNotNull { edge ->
                val routineId = if (edge.key.type == GraphEdgeType.REQUIRES) edge.key.from else edge.key.to
                store.node(routineId) as? RoutineNode
            }
            .distinctBy { it.id }
            .sortedBy { it.id }
            .toList()

    fun changesAfterAppUpdate(
        packageName: String,
        currentVersionName: String? = null,
        currentVersionCode: Long? = null,
    ): List<AppUpdateChange> {
        val currentIdentity = currentVersionCode?.toString() ?: currentVersionName.orEmpty()
        require(currentIdentity.isNotBlank()) { "Current app version must be identified" }
        return store.currentEdges(includeStale = true).asSequence()
            .filter { it.evidence.appVersion?.packageName == packageName }
            .filter { it.evidence.appVersion?.stableIdentity != currentIdentity }
            .map { AppUpdateChange(it, currentIdentity) }
            .sortedWith(compareBy({ it.edge.key }, { it.edge.evidence.appVersion?.stableIdentity }))
            .toList()
    }

    fun blastRadius(changedNode: GraphNodeId, maxDepth: Int = 16): List<BlastRadiusEntry> {
        require(maxDepth >= 1) { "Maximum depth must be positive" }
        if (store.node(changedNode) == null) return emptyList()
        val edges = store.currentEdges()
        val visited = linkedMapOf(changedNode to 0)
        val queue = ArrayDeque<GraphNodeId>().apply { add(changedNode) }
        val impacts = mutableListOf<BlastRadiusEntry>()
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val nextDistance = visited.getValue(current) + 1
            if (nextDistance > maxDepth) continue
            impactNeighbors(current, edges).forEach { (next, via) ->
                if (next !in visited) {
                    visited[next] = nextDistance
                    queue.add(next)
                    store.node(next)?.let { impacts += BlastRadiusEntry(it, nextDistance, via) }
                }
            }
        }
        return impacts.sortedWith(compareBy(BlastRadiusEntry::distance, { it.node.id.value }, { it.via.name }))
    }

    private fun impactNeighbors(
        node: GraphNodeId,
        edges: List<TemporalKnowledgeEdge>,
    ): List<Pair<GraphNodeId, GraphEdgeType>> {
        val forwardTypes = setOf(
            GraphEdgeType.CONTAINS,
            GraphEdgeType.SCROLL_REVEALS,
            GraphEdgeType.USED_BY_ROUTINE,
            GraphEdgeType.SUPERSEDES,
        )
        return buildList {
            edges.forEach { edge ->
                if (edge.key.to == node && edge.key.type != GraphEdgeType.SUPERSEDES) {
                    add(edge.key.from to edge.key.type)
                }
                if (edge.key.from == node && edge.key.type in forwardTypes) {
                    add(edge.key.to to edge.key.type)
                }
            }
        }.distinct().sortedWith(compareBy({ it.first.value }, { it.second.name }))
    }
}
