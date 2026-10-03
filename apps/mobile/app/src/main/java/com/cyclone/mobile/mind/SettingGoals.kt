package com.cyclone.mobile.mind

/**
 * Alpha 92: a success check for goals that are only phone settings ("turn on auto-rotate", "set the screen timeout to
 * 2 minutes and turn on dark mode"). The phone can read those settings itself, so once they all hold, the Mind is told
 * the goal is met and finishes, instead of working on for dozens of turns (one stress-test run met the goal and then
 * spent 45 turns and 707k tokens).
 *
 * Strict on purpose: the check applies only when **every** clause of the goal is a recognised setting change. Any
 * other words ("…and tell me the battery") and there is no check at all, so it can never end a task early. Pure.
 */
object SettingGoals {
    enum class Key { ROTATION, DARK, DND, TIMEOUT_MS, TOUCH_VIBRATION, ADAPTIVE_BRIGHTNESS }

    data class Target(val key: Key, val on: Boolean? = null, val ms: Long? = null) {
        fun describe(): String = when (key) {
            Key.ROTATION -> "auto-rotate is ${onOff()}"
            Key.DARK -> "the dark theme is ${onOff()}"
            Key.DND -> "Do Not Disturb is ${onOff()}"
            Key.TIMEOUT_MS -> "the screen timeout is ${(ms ?: 0) / 60_000} minute${if ((ms ?: 0) == 60_000L) "" else "s"}"
            Key.TOUCH_VIBRATION -> "touch vibration is ${onOff()}"
            Key.ADAPTIVE_BRIGHTNESS -> "adaptive brightness is ${onOff()}"
        }

        private fun onOff() = if (on == true) "on" else "off"
    }

    /** What the phone reports right now; null for a value it couldn't read. */
    data class Reading(val values: Map<Key, Any?>)

    private val SPLIT = Regex("(?i)\\s*(?:,|;|\\band then\\b|\\bthen\\b|\\band\\b|\\ben\\b|\\ben dan\\b|\\bdaarna\\b)\\s*")
    private val FILLER = Regex("(?i)\\b(please|pls|alsjeblieft|aub|for me|voor mij|the|my|de|het|mijn|phone'?s?|telefoon)\\b")
    private val ON = Regex("(?i)\\b(turn on|switch on|enable|activate|zet aan|aanzetten|inschakelen|aan)\\b|\\bon\\b")
    private val OFF = Regex("(?i)\\b(turn off|switch off|disable|deactivate|zet uit|uitzetten|uitschakelen|uit|lock)\\b|\\boff\\b")

    /** The targets of [goal], or null when any part of it is not a recognised setting change. */
    fun parse(goal: String): List<Target>? {
        val clauses = goal.trim().trimEnd('.', '!').split(SPLIT).map { it.trim() }.filter { it.isNotBlank() }
        if (clauses.isEmpty() || clauses.size > 4) return null
        val targets = clauses.map { clause(it) ?: return null }
        return targets.distinctBy { it.key }.takeIf { it.size == targets.size }
    }

    private fun clause(raw: String): Target? {
        val text = FILLER.replace(raw.lowercase(), " ").replace(Regex("\\s+"), " ").trim()
        val on = ON.containsMatchIn(text)
        val off = OFF.containsMatchIn(text)
        fun state(): Boolean? = when {
            on && !off -> true
            off && !on -> false
            else -> null
        }
        return when {
            Regex("\\b(auto-?rotat\\w*|automatisch draaien|screen rotation|schermrotatie|rotation)\\b").containsMatchIn(text) ->
                state()?.let { Target(Key.ROTATION, on = it) }
            Regex("\\b(dark (theme|mode)|donkere? (thema|modus)|night mode)\\b").containsMatchIn(text) ->
                state()?.let { Target(Key.DARK, on = it) }
            Regex("\\b(do not disturb|dnd|niet storen)\\b").containsMatchIn(text) -> state()?.let { Target(Key.DND, on = it) }
            Regex("\\b(adaptive brightness|aanpasbare helderheid|auto brightness)\\b").containsMatchIn(text) ->
                state()?.let { Target(Key.ADAPTIVE_BRIGHTNESS, on = it) }
            Regex("\\b(vibration for touch|touch vibration|touch feedback|haptic|aanraakfeedback|trillen bij aanraken)\\b").containsMatchIn(text) ->
                state()?.let { Target(Key.TOUCH_VIBRATION, on = it) }
            Regex("\\b(screen timeout|scherm time-?out|time-?out)\\b").containsMatchIn(text) -> timeout(text)
            else -> null
        }
    }

    private fun timeout(text: String): Target? {
        val match = Regex("(\\d{1,2})\\s*(seconds?|secs?|seconden|minutes?|mins?|minuten|minuut)\\b").find(text) ?: return null
        val n = match.groupValues[1].toLong()
        val seconds = match.groupValues[2].startsWith("sec")
        val ms = if (seconds) n * 1_000 else n * 60_000
        return Target(Key.TIMEOUT_MS, ms = ms)
    }

    /** "auto-rotate is on" when every target holds, null when one doesn't or can't be read. */
    fun met(targets: List<Target>, reading: Reading): String? {
        for (target in targets) {
            val value = reading.values[target.key] ?: return null
            val ok = when (target.key) {
                Key.TIMEOUT_MS -> (value as? Number)?.toLong() == target.ms
                else -> (value as? Boolean) == target.on
            }
            if (!ok) return null
        }
        return targets.joinToString(", ") { it.describe() }
    }
}
