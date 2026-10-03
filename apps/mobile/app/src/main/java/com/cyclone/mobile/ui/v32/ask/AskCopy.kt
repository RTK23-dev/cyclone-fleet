package com.cyclone.mobile.ui.v32.ask

/**
 * The AI screen's words (R3). Pure, so the greeting, the suggestions and the run lines are tested without a device.
 * Suggestions only fill the Ask bar: the owner reads and sends them, so nothing starts from a chip.
 */
object AskCopy {
    const val TITLE = "Ask Cyclone"
    const val QUESTION = "What can I do for you?"
    const val SUGGESTIONS_LABEL = "Suggestions"
    const val RUNS_LABEL = "Recent runs"
    const val SEE_ALL = "See all"
    const val NO_MODEL = "Choose a model"
    const val SEARCH_RUNS = "Search runs"
    const val NO_RUNS = "Your runs will show up here."
    const val NO_MATCH = "No runs match."

    /** One suggestion chip: [icon] names a Material icon in [AskHome], [seed] is what lands in the Ask bar. */
    data class Suggestion(val icon: String, val title: String, val detail: String, val seed: String)

    val SUGGESTIONS = listOf(
        Suggestion("calendar", "Plan my day", "Calendar and to-dos", "Plan my day"),
        Suggestion("messages", "Catch me up", "Messages I missed", "Catch me up on my messages"),
        Suggestion("search", "Research", "Sources and a summary", "Research "),
        Suggestion("routine", "New routine", "Automate something", "Create a routine"),
    )

    fun greeting(hour: Int): String = when (hour) {
        in 5..11 -> "Good morning"
        in 12..17 -> "Good afternoon"
        else -> "Good evening"
    }

    /** Where a run sits in the menu: "Today", "Yesterday", "This week" or "Earlier" (calendar days, local time). */
    fun dayGroup(atMs: Long, nowMs: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String {
        val day = java.time.Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate()
        val today = java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val days = java.time.temporal.ChronoUnit.DAYS.between(day, today)
        return when {
            days <= 0L -> "Today"
            days == 1L -> "Yesterday"
            days < 7L -> "This week"
            else -> "Earlier"
        }
    }

    val DAY_GROUPS = listOf("Today", "Yesterday", "This week", "Earlier")

    /** "just now", "5m ago", "2h ago", "3d ago". */
    fun age(elapsedMs: Long): String {
        val minutes = elapsedMs.coerceAtLeast(0L) / 60_000L
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "${minutes}m ago"
            minutes < 24 * 60 -> "${minutes / 60}h ago"
            else -> "${minutes / (24 * 60)}d ago"
        }
    }

    /** How a run ended, in one or two words. [status] is `MissionStatus.name`. */
    fun runStatus(status: String): String = when (status) {
        "RUNNING" -> "Working"
        "WAITING" -> "Waiting for you"
        "COMPLETED" -> "Done"
        "GAVE_UP" -> "Not possible"
        "FAILED" -> "Didn't finish"
        "CANCELLED" -> "Stopped"
        "PAUSED" -> "Paused"
        "INTERRUPTED" -> "Interrupted"
        else -> "Ended"
    }

    /** "Done · 2h ago" */
    fun runLine(status: String, updatedAtMs: Long, nowMs: Long): String =
        if (status == "RUNNING") runStatus(status) else "${runStatus(status)} · ${age(nowMs - updatedAtMs)}"

    /** The tone of a run's tile: done, needs the owner, or went wrong. */
    enum class Tone { DONE, WAITING, PROBLEM }

    fun tone(status: String): Tone = when (status) {
        "COMPLETED" -> Tone.DONE
        "GAVE_UP", "FAILED" -> Tone.PROBLEM
        else -> Tone.WAITING
    }

    /** Case-insensitive match on the goal and the summary; a blank query keeps everything. */
    fun matches(query: String, goal: String, summary: String): Boolean {
        val q = query.trim()
        return q.isEmpty() || goal.contains(q, ignoreCase = true) || summary.contains(q, ignoreCase = true)
    }

    /** The header's model pill: the model's name, or a nudge when none is chosen. */
    fun modelLabel(label: String?): String = label?.trim()?.takeIf { it.isNotEmpty() } ?: NO_MODEL

    /** The logo panel's status line, from the phone-control snapshot. */
    fun phoneLine(ready: Boolean, needsRepair: Boolean): String = when {
        ready -> "Running on this phone"
        needsRepair -> "Phone control needs a repair"
        else -> "Finish setting up phone control"
    }

    fun phoneValue(ready: Boolean, needsRepair: Boolean): String = when {
        ready -> "Ready"
        needsRepair -> "Repair"
        else -> "Set up"
    }
}
