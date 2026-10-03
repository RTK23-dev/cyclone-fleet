package com.cyclone.mobile.agent.nav

import com.cyclone.mobile.brain.graphv2.GraphNodeId
import com.cyclone.mobile.brain.graphv2.TemporalKnowledgeEdge

/** Shared breadth-first routing. The caller selects eligible edges; ties prefer stronger evidence. */
object AtlasRoutes {
    fun shortest(entry: GraphNodeId, doors: List<TemporalKnowledgeEdge>): Map<GraphNodeId, List<TemporalKnowledgeEdge>> {
        val out = doors.groupBy { it.key.from }
        val routes = linkedMapOf(entry to emptyList<TemporalKnowledgeEdge>())
        val queue = ArrayDeque(listOf(entry))
        while (queue.isNotEmpty()) {
            val room = queue.removeFirst()
            out[room].orEmpty().sortedWith(compareByDescending<TemporalKnowledgeEdge> { it.evidence.confidence }
                .thenBy { it.key.to.value }.thenBy { it.key.type.name }).forEach { door ->
                if (door.key.to !in routes) {
                    routes[door.key.to] = routes.getValue(room) + door
                    queue.addLast(door.key.to)
                }
            }
        }
        return routes
    }
}
