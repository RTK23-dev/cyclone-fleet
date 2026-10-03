package com.cyclone.mobile.agent.contract

import java.util.Calendar
import java.util.TimeZone

/**
 * Common phone intents that deserve a typed goal, a deterministic route and a live proof: alarms and timers.
 * Parsing is conservative; anything it does not understand stays with the model and the generic contract.
 */
object PhoneIntents {
    data class Alarm(val hour: Int, val minute: Int, val relativeMinutes: Int?) {
        val hhmm: String get() = "%02d:%02d".format(hour, minute)
        /** What the user sees before the action: "Alarm at 23:09". */
        val summary: String get() = "Alarm at $hhmm"
    }

    data class Timer(val seconds: Long) {
        val summary: String get() = when {
            seconds % 3600 == 0L -> "${seconds / 3600} h timer"
            seconds % 60 == 0L -> "${seconds / 60} min timer"
            else -> "$seconds s timer"
        }
    }

    private val ALARM_WORD = Regex("(?i)\\b(alarm|alarms|wekker|wake me)\\b")
    private val TIMER_WORD = Regex("(?i)\\b(timer|countdown|kookwekker)\\b")
    private const val UNIT = "(seconds?|secs?|s|minutes?|mins?|min|m|hours?|hrs?|hr|h|uur|minuten|minuut|seconden)"
    private val RELATIVE = Regex("(?i)\\b(?:for|in|over|within)\\s+(\\d{1,4})\\s*$UNIT\\b")
    private val ABSOLUTE = Regex(
        "(?i)\\b(?:at|for|om|to)\\s+(\\d{1,2})(?:[:.](\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?)?(?![\\d:.])(?!\\s*$UNIT\\b)",
    )
    private val TIMER_LENGTH = listOf(
        Regex("(?i)\\btimer\\b[^\\d]{0,20}?(\\d{1,4})\\s*[- ]?$UNIT\\b"),
        Regex("(?i)\\b(\\d{1,4})\\s*[- ]?$UNIT\\s+(?:timer|countdown)\\b"),
    )

    /** One resolution per sentence for a while, so "in 5 minutes" does not drift between checks of the same run. */
    private val resolved = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Alarm>>()
    private const val RESOLUTION_TTL_MS = 15 * 60_000L

    fun alarm(goal: String, nowMs: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): Alarm? {
        if (!ALARM_WORD.containsMatchIn(goal) || TIMER_WORD.containsMatchIn(goal)) return null
        val key = goal.trim().lowercase()
        resolved[key]?.let { (at, alarm) -> if (nowMs - at in 0..RESOLUTION_TTL_MS) return alarm }
        val alarm = parseAlarm(goal, nowMs, zone) ?: return null
        resolved[key] = nowMs to alarm
        return alarm
    }

    internal fun parseAlarm(goal: String, nowMs: Long, zone: TimeZone): Alarm? {
        RELATIVE.find(goal)?.let { match ->
            val amount = match.groupValues[1].toIntOrNull() ?: return null
            val minutes = when (unitKind(match.groupValues[2])) {
                'h' -> amount * 60
                'm' -> amount
                else -> return null // an alarm is minute-precise; "in 30 seconds" is a timer, not an alarm
            }
            if (minutes !in 1..24 * 60) return null
            val calendar = Calendar.getInstance(zone).apply { timeInMillis = nowMs + minutes * 60_000L }
            return Alarm(calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), minutes)
        }
        ABSOLUTE.find(goal)?.let { match ->
            var hour = match.groupValues[1].toIntOrNull() ?: return null
            val minute = match.groupValues[2].takeIf { it.isNotBlank() }?.toIntOrNull() ?: 0
            val meridiem = match.groupValues[3].lowercase().replace(".", "")
            if (meridiem.isNotBlank()) {
                if (hour !in 1..12) return null
                hour = when {
                    meridiem == "am" && hour == 12 -> 0
                    meridiem == "pm" && hour != 12 -> hour + 12
                    else -> hour
                }
            }
            if (hour !in 0..23 || minute !in 0..59) return null
            return Alarm(hour, minute, null)
        }
        return null
    }

    fun timer(goal: String): Timer? {
        if (!TIMER_WORD.containsMatchIn(goal)) return null
        val match = TIMER_LENGTH.firstNotNullOfOrNull { it.find(goal) }
            ?: RELATIVE.find(goal)
            ?: return null
        val amount = match.groupValues[1].toLongOrNull() ?: return null
        val seconds = amount * when (unitKind(match.groupValues[2])) {
            'h' -> 3600
            'm' -> 60
            else -> 1
        }
        return seconds.takeIf { it in 1..86_400 }?.let(::Timer)
    }

    private fun unitKind(unit: String): Char {
        val u = unit.lowercase()
        return when {
            u.startsWith("h") || u == "uur" -> 'h'
            u.startsWith("m") -> 'm'
            else -> 's'
        }
    }

    // --- live proof ------------------------------------------------------------------------------------------

    /** One visible row or control: its label and, when it is a switch/checkbox, whether it is on. */
    data class Row(val label: String, val checked: Boolean?)

    private val ENABLED = Regex("(?i)\\b(enabled|is on|switched on|aan|ingeschakeld)\\b")
    private val DISABLED = Regex("(?i)\\b(disabled|is off|switched off|uit|uitgeschakeld)\\b")
    private val CLOCK_PACKAGE = Regex("(?i)clock|alarm")

    fun isClockApp(packageName: String): Boolean = CLOCK_PACKAGE.containsMatchIn(packageName)

    /** An enabled alarm at the requested time is on screen in a clock app. Opening Clock alone never passes. */
    fun alarmVisible(packageName: String, rows: List<Row>, alarm: Alarm): Boolean {
        if (!isClockApp(packageName)) return false
        val times = timePatterns(alarm)
        val matching = rows.filter { row -> times.any { it.containsMatchIn(row.label) } }
        return matching.any { row ->
            !DISABLED.containsMatchIn(row.label) && (ENABLED.containsMatchIn(row.label) || row.checked == true)
        }
    }

    private fun timePatterns(alarm: Alarm): List<Regex> {
        val h24 = alarm.hour
        val h12 = if (h24 % 12 == 0) 12 else h24 % 12
        val mm = "%02d".format(alarm.minute)
        val meridiem = if (h24 < 12) "a\\.?\\s?m\\.?" else "p\\.?\\s?m\\.?"
        val other = if (h24 < 12) "p\\.?\\s?m\\.?" else "a\\.?\\s?m\\.?"
        return listOf(
            // 24-hour display: 09:05 / 9:05 / 23:09, not followed by am/pm
            Regex("(?i)(?<![\\d:])0?$h24:$mm(?![\\d:])(?!\\s*(?:a\\.?\\s?m|p\\.?\\s?m))"),
            // 12-hour display with the right meridiem, or none printed next to the time. With no meridiem, a padded
            // hour ("09:00") is a 24-hour display, so it is 9 AM and never 9 PM.
            Regex("(?i)(?<![\\d:])0?$h12:$mm\\s*$meridiem"),
            Regex("(?i)(?<![\\d:])$h12:$mm(?![\\d:])(?!\\s*$other)"),
        ).distinctBy { it.pattern }
    }

    /** A timer of the requested length is counting down: pause/stop visible and a remaining time just below it. */
    fun timerRunning(labels: List<String>, requestedSeconds: Long): Boolean {
        val running = labels.any { it.trim().lowercase() in RUNNING_CONTROLS }
        if (!running) return false
        val slack = maxOf(60L, requestedSeconds / 5)
        val remaining = labels.flatMap { countdowns(it) }.distinct()
        return remaining.any { it in maxOf(1L, requestedSeconds - slack)..requestedSeconds }
    }

    private val RUNNING_CONTROLS = setOf("pause", "pause timer", "stop", "stop timer", "pauzeren", "stoppen")
    private val HMS = Regex("(?<![0-9:])(\\d{1,2}):([0-5]\\d):([0-5]\\d)(?![0-9:])")
    private val MS = Regex("(?<![0-9:])(\\d{1,3}):([0-5]\\d)(?![0-9:])")

    fun countdowns(label: String): List<Long> {
        val hours = HMS.findAll(label).map { m ->
            m.groupValues[1].toLong() * 3600 + m.groupValues[2].toLong() * 60 + m.groupValues[3].toLong()
        }.toList()
        if (hours.isNotEmpty()) return hours
        return MS.findAll(label).map { m -> m.groupValues[1].toLong() * 60 + m.groupValues[2].toLong() }.toList()
    }
}

/**
 * The model's own "done" summary must not merely describe navigation when the goal asked for an action.
 * "Clock is open on its Alarms screen" is not "alarm set".
 */
object CompletionClaimAudit {
    private val ACTION_GOAL = Regex(
        "(?i)\\b(set|create|make|add|send|turn\\s+(?:on|off)|enable|disable|book|schedule|save|delete|remove|pay|buy|" +
            "sign\\s*up|register|change|rename|start|stop|play|post|reply|write|install|update|call|share)\\b",
    )
    private val NAVIGATION_ONLY = Regex(
        "(?i)\\b(is|are)\\s+(?:now\\s+)?(?:open|opened|visible|shown|displayed|showing)\\b|\\bopened\\b|\\bnavigated\\b|" +
            "\\bon\\s+(?:its|the)\\s+[\\w\\s]{0,30}\\b(?:screen|page|tab|view)\\b|\\blanded\\b",
    )
    private val OUTCOME = Regex(
        "(?i)\\b(set|created|added|sent|saved|scheduled|booked|enabled|disabled|turned|started|stopped|deleted|removed|" +
            "paid|bought|registered|changed|renamed|playing|posted|replied|installed|updated|called|shared|running|ready|found|is\\s+[\\w.@-]+@)\\b",
    )

    fun isActionGoal(goal: String): Boolean = ACTION_GOAL.containsMatchIn(goal.replace(Regex("(?i)\\bopen\\b"), ""))

    fun navigationOnly(goal: String, claim: String?): Boolean {
        val text = claim.orEmpty()
        if (text.isBlank() || !isActionGoal(goal)) return false
        return NAVIGATION_ONLY.containsMatchIn(text) && !OUTCOME.containsMatchIn(text)
    }
}
