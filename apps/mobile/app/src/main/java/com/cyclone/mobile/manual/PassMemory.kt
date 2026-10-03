package com.cyclone.mobile.manual

import com.cyclone.mobile.manual.dictionary.AppDictionary
import com.cyclone.mobile.manual.dictionary.EntryStatus

/**
 * What one mapping pass remembers while it runs, in memory only (plan 36 §5.1, alpha.60).
 *
 * - **Category proof.** When the same category row is seen with a different item selected, the row really switches
 *   views: the categories seen selected are *proven* and pass the organizer's "seen twice" gate in one pass.
 * - **Door words.** The app's words on each door of the screens read, so a walked door and the place it leads to can be
 *   named ("Settings", "Add photos and files").
 * - **Downloaded names.** Names made of the app's words but not one of its strings never reach the dictionary: a proven
 *   one waits in the [ReviewQueue] for the owner's "app word or yours?".
 */
class PassMemory {
    private val selections = HashMap<String, LinkedHashSet<String>>()
    private val doors = HashMap<String, Pair<String?, String>>()

    data class Split(val appStrings: List<SetProposal>, val downloaded: List<SetProposal>)

    fun split(found: ScreenFindings): Split {
        for (strip in found.strips) strip.selected?.let { selections.getOrPut(strip.key) { LinkedHashSet() } += it }
        val marked = found.proposals.map { p ->
            val seen = selections[p.anchor.containerKey]
            if (p.anchor.kind == AnchorKind.VIEW && seen != null && seen.size >= 2 && p.name.text in seen) p.copy(proven = true) else p
        }
        return Split(marked.filter { it.name.proof == ChromeProof.LEXICON }, marked.filter { it.name.proof == ChromeProof.VOCABULARY })
    }

    fun rememberDoor(doorKey: String, label: String?, kind: String) {
        if (doors.size > 2_000) doors.clear()
        doors[doorKey] = label to kind
    }

    fun door(doorKey: String): Pair<String?, String>? = doors[doorKey]
}

/**
 * The owner's "app word or yours?" queue for one app: downloaded names that a probe proved to be a switching category.
 * Held in memory only: nothing here is written to disk until the owner says "app word". "Mine" keeps only a hash.
 */
class ReviewQueue {
    data class Item(val id: String, val hash: String, val proposal: SetProposal, val seenAt: Long)

    private val items = LinkedHashMap<String, Item>()

    fun offer(dict: AppDictionary, proposals: List<SetProposal>, at: Long) {
        for (p in proposals) {
            if (!p.proven || p.name.proof != ChromeProof.VOCABULARY) continue
            val hash = hashOf(p.name.text)
            if (hash in dict.declined) continue
            val known = dict.entries.values.any { e -> e.status != EntryStatus.REJECTED && e.names().any { AppLexicon.normalize(it) == AppLexicon.normalize(p.name.text) } }
            if (known) continue
            val id = "rv:" + hash.take(16)
            if (id !in items && items.size < MAX) items[id] = Item(id, hash, p, at)
        }
    }

    fun list(): List<Item> = items.values.toList()
    fun take(id: String): Item? = items.remove(id)

    companion object {
        const val MAX = 20
        fun hashOf(name: String): String = AppLexicon.hash(AppLexicon.normalize(name))
    }
}
