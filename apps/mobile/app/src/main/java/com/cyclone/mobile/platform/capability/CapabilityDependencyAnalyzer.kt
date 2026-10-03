package com.cyclone.mobile.platform.capability

/** Pure, deterministic cycle analysis over declared capability dependencies. */
internal object CapabilityDependencyAnalyzer {
    fun cycles(graph: Map<CapabilityId, Set<CapabilityId>>): List<CapabilityDependencyCycle> {
        var nextIndex = 0
        val indices = mutableMapOf<CapabilityId, Int>()
        val lowLinks = mutableMapOf<CapabilityId, Int>()
        val stack = ArrayDeque<CapabilityId>()
        val onStack = mutableSetOf<CapabilityId>()
        val components = mutableListOf<List<CapabilityId>>()

        fun visit(node: CapabilityId) {
            indices[node] = nextIndex
            lowLinks[node] = nextIndex
            nextIndex += 1
            stack.addLast(node)
            onStack += node

            graph.getValue(node).sorted().forEach { target ->
                if (target !in indices) {
                    visit(target)
                    lowLinks[node] = minOf(lowLinks.getValue(node), lowLinks.getValue(target))
                } else if (target in onStack) {
                    lowLinks[node] = minOf(lowLinks.getValue(node), indices.getValue(target))
                }
            }

            if (lowLinks.getValue(node) == indices.getValue(node)) {
                val component = mutableListOf<CapabilityId>()
                do {
                    val member = stack.removeLast()
                    onStack -= member
                    component += member
                } while (member != node)
                components += component
            }
        }

        graph.keys.sorted().forEach { node -> if (node !in indices) visit(node) }
        return components
            .filter { component ->
                component.size > 1 || component.single() in graph.getValue(component.single())
            }
            .map { CapabilityDependencyCycle(it.distinct().sorted()) }
            .sorted()
    }
}
