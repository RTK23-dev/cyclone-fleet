package com.cyclone.mobile.mind.workspace

/**
 * Plan 37 §4: after an action that said what it expects, the harness compares the new screen with it, without a
 * model call. Conservative by design: MISSED only when it is sure (another app is in front, or a quoted word or handle
 * is absent while the screen changed); anything fuzzy is UNKNOWN and shows nothing, so a check never pushes the model
 * off a correct path.
 */
object ExpectCheck {
    enum class Verdict { HELD, MISSED, UNCHANGED, UNKNOWN }

    data class Result(val verdict: Verdict, val line: String?) {
        companion object { val NONE = Result(Verdict.UNKNOWN, null) }
    }

    /** App labels too common as plain words to count as naming an app ("the settings page", "a new message"). */
    private val COMMON = setOf("settings", "phone", "messages", "message", "clock", "camera", "files", "maps", "contacts", "calendar",
        "gallery", "photos", "music", "email", "mail", "calculator", "notes", "weather", "news", "store", "home", "browser", "search",
        "chat", "chats", "video", "videos", "wallet", "health", "fitness", "radio", "recorder", "downloads", "drive", "keep", "duo", "meet",
        "one", "play", "tv", "books", "games", "shop", "shopping", "translate", "assistant", "lens", "files by google")
    private val QUOTED = Regex("[\"“”'‘’]([^\"“”‘’']{2,60})[\"“”'‘’]")
    private val HANDLE = Regex("(?<![\\p{L}\\p{N}])(@[\\p{L}\\p{N}._]{2,30}|[\\p{L}\\p{N}]+(?:[._][\\p{L}\\p{N}]+)+)")

    /**
     * [apps] are the installed apps as (label, package). [before] is the screen the action started from (null when
     * unknown), [after] the screen now.
     */
    fun check(expect: String?, before: ScreenFacts?, after: ScreenFacts, apps: List<Pair<String, String>>): Result {
        val wanted = expect?.trim().orEmpty()
        if (wanted.length < 3) return Result.NONE
        if (before != null && before.signature == after.signature) {
            return Result(Verdict.UNCHANGED, "~ nothing changed; the action may not have landed")
        }
        // A permission dialog, share sheet or keyboard in front says nothing about the expectation yet.
        if (Surfaces.transient(after.packageName)) return Result.NONE
        val lower = wanted.lowercase()
        val named = apps.filter { (label, _) ->
            val l = label.trim().lowercase()
            l.length >= 3 && l !in COMMON && Regex("(?<![\\p{L}\\p{N}])${Regex.escape(l)}(?![\\p{L}\\p{N}])").containsMatchIn(lower)
        }
        val appOk = named.isEmpty() || named.any { it.second == after.packageName } ||
            after.app.isNotBlank() && named.any { it.first.equals(after.app, ignoreCase = true) }
        if (!appOk) {
            val names = named.joinToString(" or ") { it.first }
            return Result(Verdict.MISSED, "✗ expected $names, but ${after.app.ifBlank { after.packageName }} is in front")
        }
        val terms = terms(wanted)
        if (terms.isNotEmpty()) {
            val missing = terms.filter { !contains(after.haystack, it) }
            if (missing.isEmpty()) return Result(Verdict.HELD, "✓ ${terms.joinToString(", ") { "\"$it\"" }} on screen")
            return Result(Verdict.MISSED, "✗ expected ${missing.joinToString(", ") { "\"$it\"" }}; not on this screen" +
                (after.title?.takeIf { it.isNotBlank() }?.let { " (it shows \"${it.take(50)}\")" } ?: "") +
                ". It may be further down or still loading.")
        }
        if (named.isNotEmpty()) return Result(Verdict.HELD, "✓ ${after.app.ifBlank { named.first().first }} is in front")
        return Result.NONE
    }

    /**
     * Whole-token match: "lo.06" is on a screen that shows lo.06, not on one that only shows lo.06_official. The same
     * rule decides whether a chat is the recipient the owner named.
     */
    fun contains(haystack: String, term: String): Boolean {
        val t = term.trim().lowercase()
        if (t.isEmpty()) return true
        return Regex("(?<![\\p{L}\\p{N}._@])${Regex.escape(t)}(?![\\p{L}\\p{N}_@]|\\.[\\p{L}\\p{N}])").containsMatchIn(haystack.lowercase())
    }

    /** Quoted words and handles (lo.06, @mybrand, wikipedia.org): the parts of an expectation that can be checked exactly. */
    fun terms(expect: String): List<String> {
        val quoted = QUOTED.findAll(expect).map { it.groupValues[1].trim() }.filter { it.length >= 2 }.toList()
        val rest = QUOTED.replace(expect, " ")
        val handles = HANDLE.findAll(rest).map { it.value.trimEnd('.', '_') }
            .filter { h -> h.length >= 3 && h.any(Char::isLetterOrDigit) && !h.matches(Regex("\\d+([.,]\\d+)*")) && !h.equals("e.g", true) && !h.equals("i.e", true) }
            .toList()
        return (quoted + handles).distinct().take(4)
    }
}
