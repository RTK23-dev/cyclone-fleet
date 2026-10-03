package com.cyclone.mobile.mind.workspace

import org.json.JSONArray
import org.json.JSONObject

/**
 * Plan 37 §5: the definition of done. The model may give it with its first plan (`plan_update(done=…)`); it is
 * looked for across the whole mission (every screen seen, the collected facts, the approved sends, the summary), never
 * only on the final screen. It nudges once and never traps: a second finish is always accepted, as unverified.
 */
object DoneCheck {
    val KINDS = setOf("app_shows", "sent_to", "setting", "answer", "other")

    data class Item(val kind: String, val value: String) {
        fun toJson(): JSONObject = JSONObject().put("kind", kind).put("value", value)
        val label: String get() = when (kind) {
            "sent_to" -> "sent to $value"
            "answer" -> "answer: $value"
            else -> value
        }
    }

    /** A message the owner approved and Cyclone sent: the app and the chat it went to. */
    data class Sent(val app: String, val chat: String)

    data class Evidence(val seen: String, val collected: List<String>, val sends: List<Sent>, val summary: String)

    fun parse(raw: JSONArray?): List<Item> = raw?.let { array ->
        (0 until array.length()).mapNotNull { i ->
            val row = array.optJSONObject(i)
            // A check the model wrote without a known kind is kept as plain words: shown, never blocking.
            val kind = row?.optString("kind")?.trim()?.lowercase()?.takeIf { it in KINDS } ?: "other"
            val value = (row?.optString("value") ?: array.optString(i)).replace(Regex("\\s+"), " ").trim().take(160)
            if (value.length < 2) null else Item(kind, value)
        }.take(5)
    }.orEmpty()

    fun fromJson(raw: JSONArray?): List<Item> = parse(raw)

    /** For each item: true (found), false (a checkable item nowhere in the mission), null (not checkable). */
    fun verify(items: List<Item>, evidence: Evidence): List<Pair<Item, Boolean?>> = items.map { item ->
        item to when (item.kind) {
            "other" -> null
            "sent_to" -> evidence.sends.isNotEmpty() && evidence.sends.any { sent -> names("${sent.chat}\n${sent.app}", recipient(item.value)) }
            "answer" -> covers("${evidence.summary}\n${evidence.collected.joinToString("\n")}", item.value)
            else -> covers("${evidence.seen}\n${evidence.collected.joinToString("\n")}", item.value)
        }
    }

    /**
     * Whether [text] (a chat title) is the recipient [value] names. Handles and quoted names must match as whole tokens
     * (lo.06 is not lo.06_official); plain names fall back to [covers].
     */
    fun names(text: String, value: String): Boolean {
        val terms = ExpectCheck.terms(value)
        return if (terms.isNotEmpty()) terms.all { ExpectCheck.contains(text, it) } else covers(text, value)
    }

    /** "Louella on WhatsApp" → "Louella": the app part is checked by the send's own app. */
    fun recipient(value: String): String = value.replace(Regex("(?i)\\s+(on|in|via|through)\\s+.+$"), "").trim().ifBlank { value }

    private val STOP = setOf("the", "a", "an", "is", "on", "in", "of", "to", "and", "or", "at", "it", "be", "with", "for", "shows", "show",
        "open", "opened", "screen", "page", "de", "het", "een", "van", "en")

    /** True when [text] contains [value] or at least 80% of its significant words (quoted parts must all be there). */
    fun covers(text: String, value: String): Boolean {
        val hay = text.lowercase()
        val wanted = value.lowercase().trim()
        if (wanted.isBlank()) return true
        if (wanted in hay) return true
        val quoted = ExpectCheck.terms(value)
        if (quoted.isNotEmpty() && quoted.any { !ExpectCheck.contains(hay, it) }) return false
        val words = wanted.split(Regex("[^\\p{L}\\p{N}@._:]+")).map { it.trim('.', ':') }.filter { it.length >= 2 && it !in STOP }
        if (words.isEmpty()) return false
        val found = words.count { it in hay }
        return found * 10 >= words.size * 8
    }
}
