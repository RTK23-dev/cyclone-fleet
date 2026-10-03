package com.cyclone.mobile

import kotlin.math.abs
import kotlin.math.max

class EmptySelectorException(message: String = "empty selector; refusing to match the shallowest node") :
    IllegalArgumentException(message)

object SelectorEngine {
    fun resolve(snapshot: UiSnapshot, selector: ElementSelector, limit: Int = 20): List<SelectorMatch> {
        if (selector.isEmpty()) throw EmptySelectorException()
        val byId = snapshot.nodes.associateBy { it.id }
        val anchor = selector.relativeToText?.let { text ->
            snapshot.nodes.firstOrNull { node ->
                normalize(node.text).contains(normalize(text)) || normalize(node.contentDescription).contains(normalize(text))
            }
        }

        return snapshot.nodes.mapNotNull { node ->
            val reasons = mutableListOf<String>()
            var score = 0.0
            var hardFailure = false

            selector.resourceId?.let {
                if (node.resourceId == it) { score += 3.0; reasons += "resourceId" } else hardFailure = true
            }
            selector.text?.let {
                if (matchesName(node, it)) { score += 2.5; reasons += "text_exact" } else hardFailure = true
            }
            selector.textContains?.let {
                if (normalize(node.text).contains(normalize(it))) { score += 1.8; reasons += "text_contains" } else hardFailure = true
            }
            selector.contentDescription?.let {
                if (normalize(node.contentDescription) == normalize(it)) { score += 2.4; reasons += "description_exact" } else hardFailure = true
            }
            selector.contentDescriptionContains?.let {
                if (normalize(node.contentDescription).contains(normalize(it))) { score += 1.7; reasons += "description_contains" } else hardFailure = true
            }
            selector.className?.let {
                if (node.className == it || node.className.endsWith(it)) { score += 1.2; reasons += "class" } else hardFailure = true
            }
            selector.role?.let {
                if (node.role.equals(it, ignoreCase = true)) { score += 1.2; reasons += "role" } else hardFailure = true
            }
            selector.path?.let {
                if (node.path == it) { score += 3.5; reasons += "path" } else hardFailure = true
            }
            selector.elementId?.let { wanted ->
                val hit = node.id == wanted || node.path == wanted
                if (hit) { score += 4.0; reasons += "elementId" }
            }
            selector.requireClickable?.let { if (node.clickable == it) { score += 0.4; reasons += "clickable" } else hardFailure = true }
            selector.requireEditable?.let { if (node.editable == it) { score += 0.4; reasons += "editable" } else hardFailure = true }
            selector.requireScrollable?.let { if (node.scrollable == it) { score += 0.4; reasons += "scrollable" } else hardFailure = true }

            if (selector.x != null && selector.y != null) {
                if (node.bounds.contains(selector.x, selector.y)) {
                    score += 2.0
                    reasons += "coordinates"
                    score += 1.0 / (1.0 + node.bounds.width * node.bounds.height / 10000.0)
                } else hardFailure = true
            }

            selector.ancestorText?.let { expected ->
                if (hasAncestorText(node, expected, byId)) { score += 1.4; reasons += "ancestor" } else hardFailure = true
            }
            selector.descendantText?.let { expected ->
                if (hasDescendantText(node, expected, byId)) { score += 1.4; reasons += "descendant" } else hardFailure = true
            }

            selector.fuzzyText?.let { expected ->
                val candidate = listOf(node.text, node.contentDescription).filter { it.isNotBlank() }.joinToString(" ")
                val fuzzy = semanticStringScore(expected, candidate)
                if (fuzzy >= selector.minFuzzyScore) {
                    score += fuzzy * 1.5
                    reasons += "fuzzy:${"%.2f".format(fuzzy)}"
                } else hardFailure = true
            }

            if (anchor != null && selector.relativeDirection != null) {
                if (matchesRelative(node, anchor, selector.relativeDirection)) {
                    score += 1.0
                    reasons += "relative:${selector.relativeDirection.name.lowercase()}"
                } else hardFailure = true
            }

            if (hardFailure || score <= 0.0) null
            else {
                SelectorMatch(node, score, reasons)
            }
        }.sortedWith(compareByDescending<SelectorMatch> { it.score }
            .thenBy { it.node.depth }
            .thenBy { it.node.bounds.top }
            .thenBy { it.node.bounds.left })
            .take(limit)
    }

    private fun hasAncestorText(node: UiNodeSnapshot, expected: String, byId: Map<String, UiNodeSnapshot>): Boolean {
        var parentId = node.parentId
        repeat(20) {
            val parent = parentId?.let(byId::get) ?: return false
            if (matchesText(parent, expected)) return true
            parentId = parent.parentId
        }
        return false
    }

    private fun hasDescendantText(node: UiNodeSnapshot, expected: String, byId: Map<String, UiNodeSnapshot>): Boolean {
        val pending = ArrayDeque(node.childIds)
        var visited = 0
        while (pending.isNotEmpty() && visited++ < 1000) {
            val child = byId[pending.removeFirst()] ?: continue
            if (matchesText(child, expected)) return true
            child.childIds.forEach(pending::addLast)
        }
        return false
    }

    private fun matchesText(node: UiNodeSnapshot, expected: String): Boolean {
        val q = normalize(expected)
        return normalize(node.text).contains(q) || normalize(node.contentDescription).contains(q)
    }

    private fun matchesName(node: UiNodeSnapshot, expected: String): Boolean {
        val q = normalize(expected)
        if (q.isBlank()) return false
        return listOf(node.text, node.contentDescription).any { normalize(it) == q }
    }

    private fun matchesRelative(node: UiNodeSnapshot, anchor: UiNodeSnapshot, direction: RelativeDirection): Boolean {
        val nx = node.bounds.centerX
        val ny = node.bounds.centerY
        val ax = anchor.bounds.centerX
        val ay = anchor.bounds.centerY
        return when (direction) {
            RelativeDirection.ABOVE -> ny < ay
            RelativeDirection.BELOW -> ny > ay
            RelativeDirection.LEFT_OF -> nx < ax
            RelativeDirection.RIGHT_OF -> nx > ax
            RelativeDirection.NEAR -> abs(nx - ax) <= max(node.bounds.width, anchor.bounds.width) * 2.5 &&
                abs(ny - ay) <= max(node.bounds.height, anchor.bounds.height) * 3.5
        }
    }

    internal fun semanticStringScore(expected: String, actual: String): Double {
        val a = normalize(expected)
        val b = normalize(actual)
        if (a.isBlank() || b.isBlank()) return 0.0
        if (a == b) return 1.0
        if (b.contains(a) || a.contains(b)) return 0.94

        val aTokens = a.split(' ').filter { it.isNotBlank() }.toSet()
        val bTokens = b.split(' ').filter { it.isNotBlank() }.toSet()
        val tokenUnion = aTokens union bTokens
        val jaccard = if (tokenUnion.isEmpty()) 0.0 else (aTokens intersect bTokens).size.toDouble() / tokenUnion.size

        val distance = levenshtein(a, b)
        val charScore = 1.0 - distance.toDouble() / max(a.length, b.length).coerceAtLeast(1)
        return (jaccard * 0.55 + charScore.coerceAtLeast(0.0) * 0.45).coerceIn(0.0, 1.0)
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val current = IntArray(b.length + 1)
            current[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1, previous[j] + cost)
            }
            previous = current
        }
        return previous[b.length]
    }

    private fun normalize(value: String): String = value
        .lowercase()
        .trim()
        .replace(Regex("\\s+"), " ")
}
