package com.cyclone.mobile.direct

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Plan 29 (alpha.45, direct first): the pure part of the no-screen tools. It parses what the model sends, validates it,
 * and says the result in the owner's words. The Android part (DirectActions) only talks to Android's own providers.
 */
object DirectPlan {
    /** A time the model wrote: "2026-10-03T19:00", "2026-10-03 19:00" or a date alone ("2026-10-03") for a whole day. */
    data class When(val start: LocalDateTime, val dateOnly: Boolean)

    fun parse(raw: String?): When? {
        val text = raw?.trim()?.replace(' ', 'T')?.removeSuffix("Z").orEmpty()
        if (text.isEmpty()) return null
        return runCatching { When(LocalDateTime.parse(text), false) }.getOrNull()
            ?: runCatching { When(LocalDate.parse(text).atStartOfDay(), true) }.getOrNull()
    }

    /** A new calendar event, checked: what will be written and when it ends. */
    data class NewEvent(
        val title: String,
        val start: LocalDateTime,
        val end: LocalDateTime,
        val allDay: Boolean,
        val location: String?,
        val notes: String?,
        val reminderMinutes: Int?,
    )

    sealed class Checked<out T> {
        data class Ok<T>(val value: T) : Checked<T>()
        data class Refused(val reason: String) : Checked<Nothing>()
    }

    /**
     * Checks an event the model wants to add. Either an end, a duration (default 60 minutes) or a whole day. Times in the
     * past more than a day, or events longer than two weeks, are refused as likely mistakes.
     */
    fun event(
        title: String?,
        start: String?,
        end: String?,
        durationMinutes: Int?,
        allDay: Boolean,
        location: String?,
        notes: String?,
        reminderMinutes: Int?,
        now: LocalDateTime,
    ): Checked<NewEvent> {
        val name = title?.trim().orEmpty()
        if (name.isBlank()) return Checked.Refused("title is required")
        if (name.length > 200) return Checked.Refused("the title is too long (200 characters at most)")
        val begin = parse(start) ?: return Checked.Refused("start must be a date and time like 2026-10-03T19:00, or a date for a whole day")
        val whole = allDay || begin.dateOnly
        val from = if (whole) begin.start.toLocalDate().atStartOfDay() else begin.start
        val to = when {
            whole -> (parse(end)?.start?.toLocalDate()?.plusDays(1) ?: from.toLocalDate().plusDays(1)).atStartOfDay()
            end != null && end.isNotBlank() -> parse(end)?.start ?: return Checked.Refused("end must look like 2026-10-03T20:00")
            else -> from.plusMinutes((durationMinutes ?: 60).toLong())
        }
        if (!to.isAfter(from)) return Checked.Refused("the event must end after it starts")
        if (Duration.between(from, to) > Duration.ofDays(14)) return Checked.Refused("events longer than two weeks are not added directly")
        if (from.isBefore(now.minusDays(1))) return Checked.Refused("that time is in the past; check the date")
        val reminder = reminderMinutes?.takeIf { it >= 0 }
        if (reminder != null && reminder > 40_320) return Checked.Refused("a reminder can be at most four weeks before")
        return Checked.Ok(NewEvent(name, from, to, whole, location?.trim()?.take(200)?.ifBlank { null },
            notes?.trim()?.take(2_000)?.ifBlank { null }, reminder))
    }

    /** A window to read the calendar in: [from, to], at most 62 days, defaulting to today and the next 7 days. */
    fun window(from: String?, to: String?, now: LocalDateTime): Checked<Pair<LocalDateTime, LocalDateTime>> {
        val start = parse(from)?.start ?: now.toLocalDate().atStartOfDay()
        val stop = parse(to)?.let { if (it.dateOnly) it.start.plusDays(1) else it.start } ?: start.plusDays(8).toLocalDate().atStartOfDay()
        if (!stop.isAfter(start)) return Checked.Refused("to must be after from")
        if (Duration.between(start, stop) > Duration.ofDays(62)) return Checked.Refused("read at most two months at a time")
        return Checked.Ok(start to stop)
    }

    fun millis(time: LocalDateTime, zone: ZoneId = ZoneId.systemDefault()): Long = time.atZone(zone).toInstant().toEpochMilli()

    private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

    /** "Fri 3 Oct 19:00–20:00", "Fri 3 Oct (all day)", "Fri 3 Oct 22:00 – Sat 4 Oct 01:00". */
    fun span(start: LocalDateTime, end: LocalDateTime, allDay: Boolean): String = when {
        allDay && end.toLocalDate().minusDays(1) <= start.toLocalDate() -> "${DAY.format(start)} (all day)"
        allDay -> "${DAY.format(start)} – ${DAY.format(end.minusDays(1))} (all day)"
        start.toLocalDate() == end.toLocalDate() -> "${DAY.format(start)} ${TIME.format(start)}–${TIME.format(end)}"
        else -> "${DAY.format(start)} ${TIME.format(start)} – ${DAY.format(end)} ${TIME.format(end)}"
    }

    /** An alarm the clock app should now hold: the next time [hour]:[minute] comes after [now]. */
    fun nextAlarm(hour: Int, minute: Int, now: LocalDateTime): LocalDateTime {
        val today = now.toLocalDate().atTime(LocalTime.of(hour, minute))
        return if (today.isAfter(now)) today else today.plusDays(1)
    }

    /** Whether the phone's next alarm (from AlarmManager) is the one just asked for, within a minute. */
    fun alarmMatches(nextTriggerMs: Long?, hour: Int, minute: Int, now: LocalDateTime, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        nextTriggerMs ?: return false
        val expected = millis(nextAlarm(hour, minute, now), zone)
        return kotlin.math.abs(nextTriggerMs - expected) < 60_000
    }

    /** Contact search: letters and digits only, 2 to 60 characters. */
    fun contactQuery(raw: String?): String? = raw?.trim()?.take(60)?.takeIf { q -> q.length >= 2 && q.any { it.isLetterOrDigit() } }
}
