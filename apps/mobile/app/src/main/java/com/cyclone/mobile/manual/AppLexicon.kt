package com.cyclone.mobile.manual

import android.content.Context
import com.cyclone.mobile.brain.graphv2.AtlasPrivacy
import java.security.MessageDigest
import java.util.Locale

/** How a word was proven to be the app's own (plan 36 §4). */
enum class ChromeProof(val wire: String) {
    /** The text is one of the strings shipped inside the app (its string resources). */
    LEXICON("lexicon"),
    /**
     * Every word of the text occurs in the app's shipped strings, but the whole text does not: typical for menus the app
     * downloads. Such a name is only admitted after two days of sightings and the organizer's question.
     */
    VOCABULARY("vocabulary");

    companion object {
        fun fromWire(value: String?): ChromeProof? = entries.firstOrNull { it.wire == value }
    }
}

/** A text proven to be the app's own words, with the resource key that shipped it (when there is one). */
data class ChromeWord(val text: String, val proof: ChromeProof, val resKey: String? = null)

/**
 * The app's own dictionary: every string shipped in one version of an app, in the phone's language, kept only as
 * hashes of their normalised form (plus the words they contain, also hashed). A text on screen is the app's own words
 * when its normalised form is in the lexicon; a person's name, a chat title or a message never is.
 *
 * Templates with a number ("%1$d followers") match a label whose digits differ ("1,234 followers"); the kept word is the
 * template without its number ("followers"). Only digits are ever replaced, so a template like "%1$s's story" can
 * never turn "Sam's story" into chrome.
 */
class AppLexicon private constructor(
    private val strings: Map<String, String>,
    private val templates: Map<String, Pair<String, String>>,
    private val words: Set<String>,
) {
    val size: Int get() = strings.size

    fun chrome(raw: String): ChromeWord? {
        val text = raw.trim().replace(Regex("\\s+"), " ")
        if (text.isEmpty() || text.length > MAX_LABEL) return null
        if (AtlasPrivacy.structuralLabel(text, "").isBlank()) return null
        val key = normalize(text)
        if (key.isEmpty()) return null
        strings[hash(key)]?.let { return ChromeWord(display(text), ChromeProof.LEXICON, it) }
        if (DIGITS.containsMatchIn(key)) {
            templates[hash(key.replace(DIGITS, "#"))]?.let { (resKey, _) ->
                // The kept word is the label without its number: "1,234 Followers" → "Followers".
                val shown = display(text.replace(DIGITS, " ").replace(Regex("\\s+"), " ").trim())
                return shown.takeIf { it.isNotBlank() }?.let { ChromeWord(it, ChromeProof.LEXICON, resKey) }
            }
        }
        val tokens = tokens(key)
        if (tokens.isEmpty() || tokens.size > MAX_WORDS || tokens.any { it.any(Char::isDigit) }) return null
        if (tokens.all { hash(it) in words }) return ChromeWord(display(text), ChromeProof.VOCABULARY)
        return null
    }

    companion object {
        const val MAX_LABEL = 60
        const val MAX_WORDS = 6
        private val DIGITS = Regex("\\d[\\d.,\\s]*\\d|\\d")
        private val PLACEHOLDER = Regex("%(\\d+\\$)?[-#+ 0,(]*\\d*(\\.\\d+)?[sdfi]|\\{\\d+}")

        fun normalize(raw: String): String = raw.lowercase(Locale.ROOT)
            .replace('’', '\'').replace('‘', '\'').replace('“', '"').replace('”', '"')
            .replace("…", "").replace("...", "")
            .replace(Regex("\\s+"), " ")
            .trim().trimEnd('.', ':', '!', '?', ' ')

        fun tokens(normalized: String): List<String> = normalized.split(Regex("[^\\p{L}\\p{N}']+")).filter { it.isNotBlank() }

        fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).take(12).joinToString("") { "%02x".format(it) }

        private fun display(text: String): String = text.trim().trimEnd('.', ':', '…').take(MAX_LABEL)

        /** Builds a lexicon from (resource key, text) pairs. Pure: tests pass their own strings. */
        fun of(entries: List<Pair<String, String>>): AppLexicon {
            val strings = HashMap<String, String>()
            val templates = HashMap<String, Pair<String, String>>()
            val words = HashSet<String>()
            for ((resKey, raw) in entries) {
                val text = raw.trim()
                if (text.isEmpty() || text.length > 400) continue
                if (PLACEHOLDER.containsMatchIn(text)) {
                    // A label only ever matches a template through its digits (see [chrome]), so "%1$s's story" can
                    // never make a name chrome: "Sam's story" has no digits to replace.
                    val stripped = PLACEHOLDER.replace(text, "").replace(Regex("\\s+"), " ").trim()
                    tokens(normalize(stripped)).forEach { words += hash(it) }
                    val key = normalize(PLACEHOLDER.replace(text, "#"))
                    templates.putIfAbsent(hash(key), resKey to display(stripped).trim())
                    continue
                }
                val key = normalize(text)
                if (key.isEmpty()) continue
                strings.putIfAbsent(hash(key), resKey)
                if (text.length <= 120) tokens(key).forEach { words += hash(it) }
            }
            return AppLexicon(strings, templates, words)
        }

        val EMPTY: AppLexicon = of(emptyList())
    }
}

/**
 * Reads an installed app's string resources. Android lets any app read another visible app's resources; the strings
 * are the app's own UI text, identical for every user. They are hashed into an [AppLexicon] and never stored as text.
 */
object AndroidLexicon {
    private const val MAX_STRINGS = 60_000
    private const val MISSES_BEFORE_END = 512

    fun build(context: Context, packageName: String): AppLexicon = runCatching {
        val pm = context.packageManager
        val res = pm.getResourcesForApplication(packageName)
        val entries = ArrayList<Pair<String, String>>()
        val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString() }.getOrNull()
        label?.let { entries += "app_label" to it }
        for (packageId in listOf(0x7f, 0x7e)) {
            val stringType = (1..0x40).firstOrNull { type ->
                runCatching { res.getResourceTypeName((packageId shl 24) or (type shl 16)) == "string" }.getOrDefault(false)
            } ?: continue
            var misses = 0
            var entry = 0
            while (misses < MISSES_BEFORE_END && entry <= 0xffff && entries.size < MAX_STRINGS) {
                val id = (packageId shl 24) or (stringType shl 16) or entry
                val text = runCatching { res.getString(id) }.getOrNull()
                if (text == null) misses++ else {
                    misses = 0
                    val key = runCatching { res.getResourceEntryName(id) }.getOrNull()?.takeIf { it.length in 2..80 } ?: "0x%08x".format(id)
                    entries += key to text
                }
                entry++
            }
        }
        AppLexicon.of(entries)
    }.getOrDefault(AppLexicon.EMPTY)
}
