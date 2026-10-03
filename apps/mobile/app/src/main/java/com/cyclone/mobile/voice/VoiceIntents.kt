package com.cyclone.mobile.voice

/**
 * Instant commands (alpha.52, "Drive: faster"): the most common things said in a car, understood on the phone with
 * no model at all, so the confirmation starts as soon as the words are in. Anything that is not clearly one of these
 * goes to the understanding model as before; a match is never a guess.
 *
 * - Timers: "set a timer for 10 minutes", "ten minute timer", "timer 1 hour 30 minutes", "zet een timer van 5 minuten".
 * - Alarms: "set an alarm for 7:30", "wake me up at 6 am", "alarm at half past seven" is left to the model.
 *
 * The goal is phrased the way the understanding model would phrase it, so the Mind runs its direct timer and alarm
 * tools without touching the screen.
 */
object VoiceIntents {

    fun parse(transcript: String): Understanding? {
        val words = VoiceRules.normalize(transcript)
        if (words.isEmpty() || words.size > 12) return null
        val text = words.joinToString(" ")
        return timer(text) ?: alarm(text)
    }

    // ---- timers -----------------------------------------------------------------------------------------------------

    private val TIMER_WORD = Regex("\\b(timer|timers)\\b")
    private val TIMER_OPENERS = setOf("set", "start", "zet", "a", "an", "een", "timer", "for", "van", "voor", "op", "of", "please", "graag",
        "cyclone", "can", "you", "could", "maak")
    private val UNIT = mapOf(
        "second" to 1, "seconds" to 1, "sec" to 1, "secs" to 1, "seconde" to 1, "seconden" to 1,
        "minute" to 60, "minutes" to 60, "min" to 60, "mins" to 60, "minuut" to 60, "minuten" to 60,
        "hour" to 3_600, "hours" to 3_600, "uur" to 3_600, "uren" to 3_600,
    )

    /** Plan 42: a timer command's length in seconds, for Instant mode; null when it isn't one. */
    fun timerSeconds(transcript: String): Int? {
        val words = VoiceRules.normalize(transcript)
        if (words.isEmpty() || words.size > 12) return null
        return seconds(words.joinToString(" "))
    }

    /** Plan 42: an alarm command's time (24 h), for Instant mode; null when it isn't one. */
    fun alarmTime(transcript: String): Pair<Int, Int>? {
        val words = VoiceRules.normalize(transcript)
        if (words.isEmpty() || words.size > 12) return null
        return clock(words.joinToString(" "))?.let { it.first to it.second }
    }

    private fun timer(text: String): Understanding? {
        val seconds = seconds(text) ?: return null
        val span = spoken(seconds)
        return Understanding(VoiceKind.TASK, "Set a timer for $span.", "Setting a ${spokenCompound(seconds)} timer.", "", 1.0)
    }

    private fun seconds(text: String): Int? {
        if (!TIMER_WORD.containsMatchIn(text)) return null
        val tokens = text.split(' ').filterNot { it in setOf("and", "en") }
        var seconds = 0
        var matched = false
        var i = 0
        val rest = mutableListOf<String>()
        while (i < tokens.size) {
            val t = tokens[i]
            // "half an hour", "een half uur"
            if (t == "half" && i + 1 < tokens.size && (tokens[i + 1] == "hour" || tokens[i + 1] == "uur" ||
                    (tokens[i + 1] in setOf("an", "a") && i + 2 < tokens.size && tokens[i + 2] == "hour"))) {
                seconds += 1_800; matched = true
                i += if (tokens[i + 1] == "hour" || tokens[i + 1] == "uur") 2 else 3
                continue
            }
            var n = number(t)
            var step = 1
            // "twenty five minutes": tens then ones.
            val ones = tokens.getOrNull(i + 1)?.let(::number)
            if (n != null && n in 20..90 && n % 10 == 0 && ones != null && ones in 1..9) { n += ones; step = 2 }
            val unit = tokens.getOrNull(i + step)?.let { UNIT[it] }
            // "a minute", "an hour": one unit.
            if (n != null && unit != null) {
                seconds += n * unit; matched = true; i += step + 1; continue
            }
            if (t in setOf("a", "an", "een") && unit != null) {
                seconds += unit; matched = true; i += 2; continue
            }
            // "10 minute timer" (the unit directly before "timer" is singular in English).
            rest += t
            i++
        }
        if (!matched || seconds !in 1..(24 * 3_600)) return null
        // Nothing else may be in the sentence but the command words: "timer for pasta" is the model's job.
        if (rest.any { it !in TIMER_OPENERS }) return null
        return seconds
    }

    private fun spoken(seconds: Int): String {
        val h = seconds / 3_600
        val m = seconds % 3_600 / 60
        val s = seconds % 60
        return listOfNotNull(
            h.takeIf { it > 0 }?.let { "$it hour${if (it == 1) "" else "s"}" },
            m.takeIf { it > 0 }?.let { "$it minute${if (it == 1) "" else "s"}" },
            s.takeIf { it > 0 }?.let { "$it second${if (it == 1) "" else "s"}" },
        ).joinToString(" and ")
    }

    /** "10 minute", "1 hour 30 minute": how a timer is named ("a 10 minute timer"). */
    private fun spokenCompound(seconds: Int): String {
        val h = seconds / 3_600
        val m = seconds % 3_600 / 60
        val s = seconds % 60
        return listOfNotNull(h.takeIf { it > 0 }?.let { "$it hour" }, m.takeIf { it > 0 }?.let { "$it minute" },
            s.takeIf { it > 0 }?.let { "$it second" }).joinToString(" ")
    }

    // ---- alarms -----------------------------------------------------------------------------------------------------

    private val ALARM = Regex("^(?:(?:please |cyclone )?(?:set|zet|make|maak) (?:an |a |een )?)?(?:alarm|wekker)(?: for| at| om| op| voor)? (.+)$")
    private val WAKE = Regex("^(?:please |cyclone )?wake me(?: up)?(?: at| om)? (.+)$")
    private val CLOCK = Regex("^(\\d{1,2})(?: (\\d{1,2}))?(?: (am|pm|a m|p m|uur|o'clock|oclock|in the morning|in the evening|tonight))?$")

    private fun alarm(text: String): Understanding? {
        val (hour, minute, suffix) = clock(text) ?: return null
        val clock = if (suffix.isNotEmpty()) {
            val h12 = (hour % 12).let { if (it == 0) 12 else it }
            "$h12:${minute.toString().padStart(2, '0')}$suffix"
        } else "$hour:${minute.toString().padStart(2, '0')}"
        return Understanding(VoiceKind.TASK, "Set an alarm for $clock.", "Setting an alarm for $clock.", "", 1.0)
    }

    /** The alarm's hour (24 h), minute and the spoken AM/PM suffix, or null. */
    private fun clock(text: String): Triple<Int, Int, String>? {
        val time = (ALARM.find(text) ?: WAKE.find(text))?.groupValues?.get(1)?.trim() ?: return null
        // "7:30" normalizes to "7 30"; number words are accepted for the hour.
        val parts = time.split(' ')
        val first = number(parts[0]) ?: return null
        val normalized = (listOf(first.toString()) + parts.drop(1).map { p -> number(p)?.toString() ?: p }).joinToString(" ")
        val match = CLOCK.find(normalized) ?: return null
        var hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: 0
        val marker = match.groupValues[3]
        if (hour > 23 || minute > 59) return null
        val suffix = when (marker) {
            "am", "a m", "in the morning" -> { if (hour > 12) return null; if (hour == 12) hour = 0; " AM" }
            "pm", "p m", "in the evening", "tonight" -> { if (hour > 12) return null; if (hour < 12) hour += 12; " PM" }
            else -> ""
        }
        return Triple(hour, minute, suffix)
    }

    // ---- numbers ----------------------------------------------------------------------------------------------------

    private val WORDS = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
        "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16,
        "seventeen" to 17, "eighteen" to 18, "nineteen" to 19, "twenty" to 20, "thirty" to 30, "forty" to 40, "forty five" to 45,
        "fifty" to 50, "sixty" to 60, "ninety" to 90,
        "een" to 1, "twee" to 2, "drie" to 3, "vier" to 4, "vijf" to 5, "zes" to 6, "zeven" to 7, "acht" to 8, "negen" to 9,
        "tien" to 10, "elf" to 11, "twaalf" to 12, "dertien" to 13, "veertien" to 14, "vijftien" to 15, "twintig" to 20,
        "dertig" to 30, "veertig" to 40, "vijftig" to 50, "zestig" to 60,
    )

    /** A number said as digits or as a word (English and Dutch basics). "een" counts only as a number here. */
    internal fun number(token: String): Int? = token.toIntOrNull()?.takeIf { it in 0..999 } ?: WORDS[token]
}
