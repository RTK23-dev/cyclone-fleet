package com.cyclone.mobile.manual

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * How a list is ordered, worked out from the *shapes* of what its rows show (plan 36 §6.3). Ages ("2m", "3h",
 * "Yesterday", "Mon", "12 Sep") and first letters are read here and thrown away: only the conclusion is kept.
 */
enum class ListOrder(val wire: String, val words: String) {
    NEWEST_FIRST("newest_first", "newest first"),
    OLDEST_FIRST("oldest_first", "oldest first"),
    A_Z("a_z", "A–Z");

    companion object {
        fun fromWire(value: String?): ListOrder? = entries.firstOrNull { it.wire == value }

        /** At least this many rows must show an age (or a letter) before an order is concluded. */
        const val MIN_ROWS = 3

        /**
         * The order of a list whose rows show [rows] (each row's texts, top to bottom), or null when there is no clear
         * signal. The texts are not kept.
         */
        fun of(rows: List<List<String>>, today: LocalDate = LocalDate.now()): ListOrder? {
            val ages = rows.mapNotNull { texts -> texts.firstNotNullOfOrNull { age(it, today) } }
            if (ages.size >= MIN_ROWS && ages.size * 2 >= rows.size) {
                val steps = ages.zipWithNext()
                if (steps.all { (a, b) -> b >= a } && steps.any { (a, b) -> b > a }) return NEWEST_FIRST
                if (steps.all { (a, b) -> b <= a } && steps.any { (a, b) -> b < a }) return OLDEST_FIRST
                return null
            }
            val letters = rows.mapNotNull { texts -> texts.firstOrNull { it.isNotBlank() }?.let(::firstLetter) }
            if (letters.size >= MIN_ROWS + 1 && letters.size * 2 >= rows.size && letters.distinct().size >= 2 &&
                letters.zipWithNext().all { (a, b) -> b >= a }) return A_Z
            return null
        }

        private fun firstLetter(text: String): Char? = text.trim().firstOrNull()?.takeIf { it.isLetter() }?.lowercaseChar()

        private val RELATIVE = Regex("^(\\d{1,3})\\s?(s|sec|secs|m|min|mins|h|hr|hrs|u|d|w|wk|wks|y|mo)( ago)?$", RegexOption.IGNORE_CASE)
        private val CLOCK = Regex("^(\\d{1,2})[:.](\\d{2})\\s?(am|pm)?$", RegexOption.IGNORE_CASE)
        private val NOW = Regex("^(now|just now|nu|jetzt|maintenant|ahora)$", RegexOption.IGNORE_CASE)
        private val YESTERDAY = Regex("^(yesterday|gisteren|gestern|hier|ayer|ieri)$", RegexOption.IGNORE_CASE)
        private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
        private val DAY_MONTH = Regex("^(\\d{1,2})\\s([a-z]{3})[a-z]*\\.?(\\s\\d{4})?$", RegexOption.IGNORE_CASE)
        private val MONTH_DAY = Regex("^([a-z]{3})[a-z]*\\.?\\s(\\d{1,2})(,?\\s\\d{4})?$", RegexOption.IGNORE_CASE)
        private val NUMERIC = Regex("^(\\d{1,2})[/.-](\\d{1,2})([/.-](\\d{2,4}))?$")
        private val ISO = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$")

        /** Minutes ago shown by [text], when it is an age shape; null for anything else. */
        fun age(text: String, today: LocalDate = LocalDate.now()): Double? {
            val t = text.trim().lowercase().removeSuffix("·").trim()
            if (t.isEmpty() || t.length > 16) return null
            if (NOW.matches(t)) return 0.0
            RELATIVE.matchEntire(t)?.let { m ->
                val n = m.groupValues[1].toDouble()
                return n * when (m.groupValues[2].lowercase()) {
                    "s", "sec", "secs" -> 1.0 / 60
                    "m", "min", "mins" -> 1.0
                    "h", "hr", "hrs", "u" -> 60.0
                    "d" -> 1_440.0
                    "w", "wk", "wks" -> 10_080.0
                    "mo" -> 43_200.0
                    else -> 525_600.0
                }
            }
            CLOCK.matchEntire(t)?.let { m ->
                var hour = m.groupValues[1].toInt()
                val minute = m.groupValues[2].toInt()
                if (hour > 23 || minute > 59) return null
                when (m.groupValues[3].lowercase()) { "pm" -> if (hour < 12) hour += 12; "am" -> if (hour == 12) hour = 0 }
                return (1_440 - (hour * 60 + minute)).toDouble()
            }
            if (YESTERDAY.matches(t)) return 1_440.0 + 720
            weekday(t)?.let { day ->
                val back = ((today.dayOfWeek.value - day.value) + 7) % 7
                return (if (back == 0) 7 else back) * 1_440.0 + 720
            }
            return date(t, today)?.let { d -> ChronoUnit.DAYS.between(d, today).takeIf { it >= 0 }?.let { it * 1_440.0 + 720 } }
        }

        private fun weekday(t: String): DayOfWeek? {
            // Full names and unambiguous short forms only: "Sam", "Mar" or "Do" could be a name or a word in a row.
            val words = mapOf(
                DayOfWeek.MONDAY to listOf("mon", "monday", "maandag", "montag", "lundi", "lunes"),
                DayOfWeek.TUESDAY to listOf("tue", "tues", "tuesday", "dinsdag", "dienstag", "mardi", "martes"),
                DayOfWeek.WEDNESDAY to listOf("wed", "wednesday", "woensdag", "mittwoch", "mercredi", "miércoles"),
                DayOfWeek.THURSDAY to listOf("thu", "thur", "thurs", "thursday", "donderdag", "donnerstag", "jeudi", "jueves"),
                DayOfWeek.FRIDAY to listOf("fri", "friday", "vrijdag", "freitag", "vendredi", "viernes"),
                DayOfWeek.SATURDAY to listOf("sat", "saturday", "zaterdag", "samstag", "samedi", "sábado"),
                DayOfWeek.SUNDAY to listOf("sun", "sunday", "zondag", "sonntag", "dimanche", "domingo"),
            )
            val clean = t.removeSuffix(".")
            return words.entries.firstOrNull { clean in it.value }?.key
        }

        private fun date(t: String, today: LocalDate): LocalDate? = runCatching {
            ISO.matchEntire(t)?.let { m -> return LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }
            DAY_MONTH.matchEntire(t)?.let { m ->
                val month = MONTHS.indexOf(m.groupValues[2].lowercase()).takeIf { it >= 0 } ?: return null
                return fit(m.groupValues[3].trim().toIntOrNull(), month + 1, m.groupValues[1].toInt(), today)
            }
            MONTH_DAY.matchEntire(t)?.let { m ->
                val month = MONTHS.indexOf(m.groupValues[1].lowercase()).takeIf { it >= 0 } ?: return null
                return fit(m.groupValues[3].trim(',', ' ').toIntOrNull(), month + 1, m.groupValues[2].toInt(), today)
            }
            NUMERIC.matchEntire(t)?.let { m ->
                // Day first (most of the world); a year of two digits is this century.
                val year = m.groupValues[4].toIntOrNull()?.let { if (it < 100) 2000 + it else it }
                return fit(year, m.groupValues[2].toInt(), m.groupValues[1].toInt(), today)
            }
            null
        }.getOrNull()

        /** A date without a year is the most recent one not after today. */
        private fun fit(year: Int?, month: Int, day: Int, today: LocalDate): LocalDate? {
            if (year != null) return LocalDate.of(year, month, day)
            val guess = LocalDate.of(today.year, month, day)
            return if (guess.isAfter(today)) guess.minusYears(1) else guess
        }
    }
}
