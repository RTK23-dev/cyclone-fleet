package com.cyclone.mobile.manual

import kotlin.math.ln

/**
 * Finds the abilities that fit a plain-language goal (plan 36 §8), on the phone, with no network call. A goal's words
 * are weighted by how rare they are among the app's abilities; an ability's score is the share of that weight it
 * covers (0–1), so "see message requests" scores 1.0 on "Requests on Messages" and about half on "Open Messages".
 *
 * [clear] is the Tier 0 rule: one ability well ahead of the rest, whose walk is navigation only.
 */
class AbilityIndex(val abilities: List<Ability>) {
    data class Hit(val ability: Ability, val score: Double)

    private val docs: List<Set<String>> = abilities.map { a ->
        terms(listOfNotNull(a.name, a.placeName, a.pick, a.tap, a.note?.takeIf { a.kind == AbilityKind.FIND }) + a.say + a.path).toSet()
    }
    private val names: List<Set<String>> = abilities.map { terms(listOf(it.name)).toSet() }
    private val df: Map<String, Int> = docs.flatten().groupingBy { it }.eachCount()
    private val n = abilities.size

    private fun idf(term: String): Double = ln(1.0 + (n + 1.0) / (1.0 + (df[term] ?: 0)))

    fun search(goal: String, limit: Int = 5): List<Hit> {
        val query = terms(listOf(goal)).distinct()
        if (query.isEmpty() || abilities.isEmpty()) return emptyList()
        val total = query.sumOf(::idf)
        return abilities.indices.mapNotNull { i ->
            val matched = query.filter { it in docs[i] }
            if (matched.isEmpty()) return@mapNotNull null
            val cover = matched.sumOf(::idf) / total
            // Small tie-breakers: words in the name, then how much the ability is trusted.
            val inName = query.count { it in names[i] }.toDouble() / query.size
            val score = (cover + 0.04 * inName + 0.02 * abilities[i].confidence).coerceAtMost(1.0)
            Hit(abilities[i], score)
        }.sortedWith(compareByDescending<Hit> { it.score }.thenBy { it.ability.path.size }.thenBy { it.ability.id }).take(limit)
    }

    companion object {
        const val CLEAR_SCORE = 0.8
        const val CLEAR_MARGIN = 0.3
        /** A goal counts as answered by the manual (the self-quiz) at this score. */
        const val ANSWER_SCORE = 0.6

        /** Tier 0: one ability well ahead, and its walk is navigation only. */
        fun clear(hits: List<Hit>): Boolean {
            val top = hits.firstOrNull() ?: return false
            val second = hits.getOrNull(1)?.score ?: 0.0
            return top.score >= CLEAR_SCORE && top.score - second >= CLEAR_MARGIN && top.ability.navigationOnly
        }

        private val STOP = setOf("a", "an", "the", "to", "of", "in", "on", "my", "me", "i", "and", "or", "for", "with", "from", "at",
            "this", "that", "it", "is", "are", "please", "can", "you", "how", "do", "what", "where", "which", "want", "would", "like",
            "some", "someone", "something", "all", "one", "there", "here", "app", "into", "by", "be", "get", "let", "us", "your", "our")

        /** Common verbs folded to one word, so "see", "show" and "view" all match "open". Generic, never per app. */
        private val SAME = mapOf(
            "see" to "open", "show" to "open", "view" to "open", "go" to "open", "visit" to "open", "check" to "open", "look" to "open",
            "display" to "open", "navigate" to "open", "launch" to "open",
            "search" to "find", "locate" to "find", "lookup" to "find",
            "attach" to "add", "insert" to "add", "upload" to "add", "include" to "add",
            "create" to "new", "start" to "new", "make" to "new", "compose" to "new", "write" to "new",
            "preference" to "setting", "option" to "setting", "config" to "setting", "configure" to "setting",
            "latest" to "newest", "recent" to "newest", "last" to "newest",
            "remove" to "delete", "erase" to "delete",
            "photos" to "photo", "picture" to "photo", "pic" to "photo", "image" to "photo",
        )

        fun terms(texts: List<String>): List<String> = texts.flatMap { text ->
            text.lowercase().replace("’", "'").split(Regex("[^\\p{L}\\p{N}']+"))
                .map { it.removeSuffix("'s").trim('\'') }
                .filter { it.length > 1 && it !in STOP }
                .map { SAME[it] ?: stem(it) }
                .map { SAME[it] ?: it }
        }

        private fun stem(word: String): String = when {
            word.length > 4 && word.endsWith("ies") -> word.dropLast(3) + "y"
            word.length > 5 && word.endsWith("ing") -> word.dropLast(3)
            word.length > 4 && word.endsWith("ed") -> word.dropLast(2)
            word.length > 3 && word.endsWith("s") && !word.endsWith("ss") -> word.dropLast(1)
            else -> word
        }
    }
}
