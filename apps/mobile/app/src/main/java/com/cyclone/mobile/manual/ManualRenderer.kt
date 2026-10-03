package com.cyclone.mobile.manual

import com.cyclone.mobile.manual.dictionary.AppDictionary
import com.cyclone.mobile.manual.dictionary.Organizer

/**
 * The manual as text (plan 36 §3.1): compact Markdown any AI can read, and short excerpts for the Mind. Built from the
 * dictionary and its abilities only, so it holds the app's own words and structure, never content.
 */
object ManualRenderer {
    const val MAX_ABILITY_LINES = 60

    fun markdown(dict: AppDictionary, appLabel: String, version: String?, abilities: List<Ability>, mappedOn: String? = null): String = buildString {
        append("# $appLabel (${dict.packageName})")
        version?.let { append(" · $it") }
        mappedOn?.let { append(" · mapped $it") }
        append(" · look only\n")
        val glossary = Organizer.glossary(dict, appLabel)
        if (glossary.isNotBlank()) append("\n").append(glossary).append("\n")

        val screens = dict.screens.values.filter { it.name != null }.sortedWith(compareBy({ it.isPanel }, { -it.seen }, { it.name }))
        if (screens.isNotEmpty()) {
            append("\n## Screens\n")
            for (card in screens) {
                val name = card.name!!
                if (card.isPanel) {
                    val over = card.panelOf?.let { dict.screens[it]?.name }
                    append("- **$name** — a panel").append(over?.let { " over **$it**" } ?: "")
                    if (card.items.isNotEmpty()) append(": ").append(card.items.joinToString(" · "))
                    append("\n")
                    continue
                }
                append("- **$name**")
                card.purpose?.let { append(" — ").append(it) }
                append("\n")
                val categories = dict.active().filter { e -> e.anchors.any { it.kind == AnchorKind.VIEW && it.roomKey == card.roomKey } }
                    .sortedBy { e -> e.anchors.firstOrNull { it.roomKey == card.roomKey }?.position ?: 0 }
                if (categories.isNotEmpty()) append("  - Categories: ").append(categories.joinToString(" · ") { "**${it.shownName}**" }).append("\n")
                card.list?.let { list ->
                    append("  - List: rows of ${list.shape}")
                    Abilities.howToFind(list.searchable, list.searchLabel, list.order, list.groups)?.let { append("; ").append(it) }
                    append(".\n")
                }
                if (card.items.isNotEmpty()) append("  - Buttons: ").append(card.items.take(8).joinToString(" · ")).append("\n")
            }
        }
        if (abilities.isNotEmpty()) {
            append("\n## Abilities\n")
            for (a in abilities.sortedWith(compareByDescending<Ability> { it.confidence }.thenBy { it.name }).take(MAX_ABILITY_LINES)) append(line(a)).append("\n")
            if (abilities.size > MAX_ABILITY_LINES) append("- … ${abilities.size - MAX_ABILITY_LINES} more\n")
        }
        dict.quiz?.let { q ->
            append("\n## Self-quiz\n")
            append("Answers ${q.answered} of ${q.goals.size} goals")
            if (q.gaps.isNotEmpty()) append(" · still to explore: ").append(q.gaps.take(6).joinToString("; "))
            append("\n")
        }
    }

    /** One ability as a manual line: "- Open a chat → Chats › Search [walked · 0.9]". */
    fun line(a: Ability): String {
        val tags = listOfNotNull(a.provenance, "%.2f".format(a.confidence),
            when (a.effect) { "asks" -> "changes ask you"; "choose" -> "you choose"; else -> null })
        return "- ${a.name} → ${a.pathText.ifBlank { a.placeName ?: "?" }}" + (a.note?.let { " ($it)" } ?: "") + " [${tags.joinToString(" · ")}]"
    }

    /**
     * What the Mind is shown for its goal (plan 36 §8): a few manual lines instead of the whole map, each with a handle
     * `go_to` takes.
     */
    fun excerpt(appLabel: String, hits: List<Pair<String, AbilityIndex.Hit>>, clear: Boolean): String? {
        if (hits.isEmpty()) return null
        return buildString {
            append("Manual of $appLabel: things you can do that fit the goal (go_to with ability=<handle> walks the safe part, checking every step):")
            for ((handle, hit) in hits) append("\n  $handle ").append(line(hit.ability).removePrefix("- ")).append(" · fit ").append("%.2f".format(hit.score))
            if (clear) append("\n  ${hits.first().first} fits clearly: go_to it first.")
        }
    }
}
