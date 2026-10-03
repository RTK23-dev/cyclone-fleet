package com.cyclone.mobile.ui.v32.search

import java.text.Normalizer
import java.util.Locale

/**
 * R6 smart search (docs/design/redesign/rounds/R6-calm.md): one field that finds anything in Cyclone and shows it
 * by kind. Pure Kotlin: the sources are gathered by [SearchSources] on the phone, and every ranking rule is tested
 * here without one.
 */
enum class SearchCategory(val label: String) {
    SETTINGS("Settings"),
    RUNS("Recent runs"),
    ROUTINES("Routines"),
    SKILLS("Skills"),
    APPS("Apps"),
    PROFILES("Profiles"),
}

/**
 * One thing that can be found. [keywords] are other words for it (for settings: what people type when they look
 * for it, "battery" for Visual quality). [recency] is epoch ms, newer first when scores tie. [target] is what a tap
 * opens: a settings section, a run id, a routine id, a package name or a profile key.
 */
data class SearchItem(
    val category: SearchCategory,
    val target: String,
    val title: String,
    val subtitle: String = "",
    val keywords: List<String> = emptyList(),
    val packageName: String? = null,
    val recency: Long = 0L,
)

data class SearchHit(val item: SearchItem, val score: Int)

data class SearchGroup(val category: SearchCategory, val hits: List<SearchHit>, val more: Int)

object CycloneSearch {
    const val PER_GROUP = 4
    /** Below this a match is too loose to show (a few scattered letters). */
    const val MIN_SCORE = 20

    /** How well [item] answers [query]: 0 when it does not. Case, accents and punctuation are ignored. */
    fun score(query: String, item: SearchItem): Int {
        val q = normalize(query)
        if (q.isEmpty()) return 0
        val title = normalize(item.title)
        val words = title.split(' ').filter(String::isNotEmpty)
        val terms = q.split(' ').filter(String::isNotEmpty)
        var best = when {
            title == q -> 100
            title.startsWith(q) -> 90
            words.any { it.startsWith(q) } -> 75
            title.contains(q) -> 60
            // Every typed word starts a word of the title ("vis qual" → "Visual quality").
            terms.size > 1 && terms.all { t -> words.any { it.startsWith(t) } } -> 70
            else -> 0
        }
        if (best == 0) {
            val subtitle = normalize(item.subtitle)
            best = when {
                item.keywords.any { normalize(it) == q } -> 55
                item.keywords.any { k -> normalize(k).split(' ').any { it.startsWith(q) } } -> 45
                subtitle.split(' ').any { it.startsWith(q) } -> 35
                q.length >= 3 && subtitle.contains(q) -> 30
                // Initials and skipped letters ("mdl" → "Model"), only for short, tight matches.
                q.length >= 3 && subsequence(q, title) -> 22
                else -> 0
            }
        }
        return if (best >= MIN_SCORE) best else 0
    }

    /**
     * The results for [query] over [items], grouped in the category order the owner reads best: the exact kind of
     * thing first (a setting named like the query beats a run that mentions it), then by score, then by recency.
     * [only] keeps one category (the chip row); each group shows [perGroup] and counts the rest as [SearchGroup.more].
     */
    fun search(
        query: String,
        items: List<SearchItem>,
        only: SearchCategory? = null,
        perGroup: Int = PER_GROUP,
    ): List<SearchGroup> {
        val hits = items.asSequence()
            .filter { only == null || it.category == only }
            .map { SearchHit(it, score(query, it)) }
            .filter { it.score > 0 }
            .toList()
        val limit = if (only != null) Int.MAX_VALUE else perGroup
        return hits.groupBy { it.item.category }
            .map { (category, group) ->
                val sorted = group.sortedWith(compareByDescending<SearchHit> { it.score }.thenByDescending { it.item.recency }.thenBy { it.item.title })
                SearchGroup(category, sorted.take(limit), (sorted.size - limit).coerceAtLeast(0))
            }
            .sortedWith(compareByDescending<SearchGroup> { it.hits.first().score }.thenBy { it.category.ordinal })
    }

    /** How many results each category has for [query]: the counts on the chips. */
    fun counts(query: String, items: List<SearchItem>): Map<SearchCategory, Int> =
        items.filter { score(query, it) > 0 }.groupingBy { it.category }.eachCount()

    internal fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    private fun subsequence(query: String, text: String): Boolean {
        val q = query.replace(" ", "")
        var i = 0
        for (c in text) if (i < q.length && c == q[i]) i++
        return i == q.length && text.length <= q.length * 6
    }
}

/**
 * Every settings section, with the words people type when they look for it. The ids are the sections'
 * `onSection` ids in Settings (CycloneSettings426), so a result opens the section itself.
 */
object SettingsIndex {
    val items: List<SearchItem> = listOf(
        entry("Model & API", "AI", "model", "openrouter", "api key", "key", "gpt", "claude", "llm", "provider", "fast mode", "pilot", "decisions"),
        entry("Default intelligence", "AI", "reasoning", "thinking", "effort", "smart", "speed"),
        entry("Phone autonomy", "AI", "permission", "ask often", "independent", "balanced", "control", "approval"),
        entry("User notes", "AI", "memory", "about me", "notes", "personal"),
        entry("Driver mode", "Drive", "car", "driving", "voice button", "drive"),
        entry("Voice", "Drive", "speech", "microphone", "mic", "tts", "speak", "listen", "language"),
        entry("Visual quality", "Appearance", "lite", "full", "auto", "performance", "battery", "glass", "blur", "fast", "slow", "older phone"),
        entry("Working indicator", "Appearance", "trace field", "overlay", "animation", "edge"),
        entry("Set up Cyclone", "Phone", "setup", "cards", "start", "onboarding"),
        entry("Quick setup", "Phone", "root", "shizuku", "adb"),
        entry("Phone control", "Phone", "accessibility", "service", "repair"),
        entry("Notifications", "Phone", "alerts", "results", "notification access"),
        entry("Background work", "Phone", "background", "workspace", "secure"),
        entry("Permissions", "Phone", "battery", "unrestricted", "access"),
        entry("Profile engine", "Profiles", "work profile", "profiles", "clone", "second account"),
        entry("App Maps", "Knowledge", "maps", "mapping", "atlas", "screens"),
        entry("Vault", "Knowledge", "passwords", "logins", "secrets", "slots"),
        entry("Storage", "Knowledge", "data", "space", "local"),
        entry("PC Gateway", "Connections", "pc", "windows", "computer", "glass", "pair"),
        entry("Privacy & safety", "Privacy", "privacy", "safety", "secure", "data"),
        entry("About", "About", "version", "update", "release", "cyclone"),
    )

    private fun entry(section: String, group: String, vararg keywords: String) =
        SearchItem(SearchCategory.SETTINGS, section, if (section == "Profile engine") "Profiles" else section, group, keywords.toList())
}
