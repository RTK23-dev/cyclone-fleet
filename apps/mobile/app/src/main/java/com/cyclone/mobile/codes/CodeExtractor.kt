package com.cyclone.mobile.codes

/**
 * Plan 49: the one-time code in a text message, or null. Pure; it never keeps the text.
 *
 * Rules, in order:
 * 1. Google's `G-123456` style;
 * 2. a 4–8 character code next to a code word ("code", "verification", "verificatiecode", "OTP"…), also written as
 *    "123 456" or "123-456";
 * 3. a text with exactly one standalone 4–8 digit number that is not an amount, a date, a time, a year or a phone
 *    number.
 *
 * Two different codes next to code words is ambiguous: null, so Cyclone never guesses.
 */
object CodeExtractor {
    private const val WORDS = "code|codes|verification|verify|verifizierung|otp|passcode|pin|one[- ]time|login|log-in|sign[- ]in|" +
        "confirmation|security|verificatiecode|bevestigingscode|inlogcode|beveiligingscode|controlecode|sicherheitscode|bestätigungscode|" +
        "código|codigo|code de|kod|kode"
    private val WORD = Regex("(?i)(?<![\\p{L}])($WORDS)(?![\\p{L}])")
    private val GOOGLE = Regex("(?<![A-Za-z0-9])G-(\\d{4,8})(?!\\d)")
    /** A code token: 4–8 digits, digits split once by a space or dash (3+3, 4+4), or 4–8 capitals and digits with a digit. */
    private val TOKEN = Regex("(?<![\\p{L}\\d.,:/€$£-])(\\d{3,4}[ -]\\d{3,4}|\\d{4,8}|(?=[A-Z0-9]*\\d)(?=[A-Z0-9]*[A-Z])[A-Z0-9]{4,8})(?![\\p{L}\\d]|[.,:/]\\d|\\s?(?:€|eur|usd|\\$|%))", RegexOption.IGNORE_CASE)
    private val MONEY_BEFORE = Regex("(?i)(€|\\$|£|eur|usd|gbp)\\s?$")
    private const val NEAR = 40

    fun extract(body: String): String? {
        val text = body.replace(' ', ' ').take(1_000)
        GOOGLE.findAll(text).map { it.groupValues[1] }.distinct().toList().let { if (it.size == 1) return it.single() }
        val tokens = TOKEN.findAll(text).mapNotNull { match ->
            val raw = match.groupValues[1]
            val before = text.substring(0, match.range.first)
            if (MONEY_BEFORE.containsMatchIn(before.takeLast(5))) return@mapNotNull null
            if (raw.any { it.isLetter() } && raw != raw.uppercase()) return@mapNotNull null
            val joined = raw.replace(" ", "").replace("-", "")
            if (joined.length !in 4..8) return@mapNotNull null
            // A split pair must be two halves of one code (3+3 or 4+4).
            if (raw.contains(' ') || raw.contains('-')) {
                val parts = raw.split(' ', '-')
                if (parts.size != 2 || parts[0].length != parts[1].length || parts.any { p -> !p.all(Char::isDigit) }) return@mapNotNull null
            }
            Token(joined, match.range.first, match.range.last, raw.any { it.isLetter() })
        }.toList()
        if (tokens.isEmpty()) return null
        val words = WORD.findAll(text).map { it.range }.toList()
        val near = tokens.filter { t -> words.any { w -> distance(w, t) <= NEAR } }
        near.map { it.code }.distinct().let { codes ->
            if (codes.size == 1) return codes.single()
            if (codes.size > 1) {
                // A numeric code beats a letters-and-digits word next to it ("Your Instagram code is 123456"), and a
                // year never counts next to a real code. Two real codes left: no guess.
                val numeric = near.filter { !it.letters }.ifEmpty { near }.map { it.code }.distinct()
                val real = numeric.filterNot { YEAR.matches(it) }.ifEmpty { numeric }
                return real.singleOrNull()
            }
        }
        // No code word: only a lone plain number counts, and never a year.
        val plain = tokens.filter { !it.letters && !YEAR.matches(it.code) }.map { it.code }.distinct()
        return plain.singleOrNull()?.takeIf { tokens.size == 1 }
    }

    private val YEAR = Regex("(19|20)\\d\\d")

    private class Token(val code: String, val first: Int, val last: Int, val letters: Boolean)

    private fun distance(word: IntRange, token: Token): Int = when {
        word.last < token.first -> token.first - word.last
        token.last < word.first -> word.first - token.last
        else -> 0
    }
}
