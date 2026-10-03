"""Routine schedules for the Command Center (plan 33, C0). Two small, checkable kinds instead of full RRULE:

- ``{"kind": "daily", "time": "07:30", "days": [1, 2, 3, 4, 5]}``: at a local time on ISO weekdays (1 = Monday);
- ``{"kind": "every", "minutes": 120}``: every N minutes (15 minutes to 7 days), counted from the last run.

Missed runs are never replayed: the next run is always in the future.
"""
from __future__ import annotations

import re
from datetime import datetime, timedelta
from typing import Any

TIME = re.compile(r"^([01][0-9]|2[0-3]):([0-5][0-9])$")
MIN_EVERY = 15
MAX_EVERY = 7 * 24 * 60


class ScheduleError(ValueError):
    pass


def parse(raw: Any) -> dict[str, Any]:
    """A validated copy of [raw], or ScheduleError."""
    if not isinstance(raw, dict):
        raise ScheduleError("A schedule is an object.")
    kind = raw.get("kind")
    if kind == "daily":
        if set(raw) != {"kind", "time", "days"}:
            raise ScheduleError("A daily schedule has kind, time and days.")
        time = raw.get("time")
        if not isinstance(time, str) or not TIME.match(time):
            raise ScheduleError("time is HH:MM, 24-hour.")
        days = raw.get("days")
        if not isinstance(days, list) or not days or not all(type(d) is int and 1 <= d <= 7 for d in days):
            raise ScheduleError("days are ISO weekdays 1 (Monday) to 7 (Sunday).")
        return {"kind": "daily", "time": time, "days": sorted(set(days))}
    if kind == "every":
        if set(raw) != {"kind", "minutes"}:
            raise ScheduleError("An every schedule has kind and minutes.")
        minutes = raw.get("minutes")
        if type(minutes) is not int or not MIN_EVERY <= minutes <= MAX_EVERY:
            raise ScheduleError(f"minutes is {MIN_EVERY} to {MAX_EVERY}.")
        return {"kind": "every", "minutes": minutes}
    raise ScheduleError("kind is daily or every.")


def next_after(schedule: dict[str, Any], after: datetime) -> datetime:
    """The first run strictly after [after] (an aware local datetime)."""
    if schedule["kind"] == "every":
        return after + timedelta(minutes=schedule["minutes"])
    hour, minute = (int(part) for part in schedule["time"].split(":"))
    days = set(schedule["days"])
    for offset in range(0, 8):
        day = after + timedelta(days=offset)
        candidate = day.replace(hour=hour, minute=minute, second=0, microsecond=0)
        if candidate > after and candidate.isoweekday() in days:
            return candidate
    raise ScheduleError("No run in the next week.")  # unreachable with at least one day


def describe(schedule: dict[str, Any]) -> str:
    if schedule["kind"] == "every":
        minutes = schedule["minutes"]
        if minutes % 1440 == 0:
            return f"Every {minutes // 1440} day(s)"
        if minutes % 60 == 0:
            return f"Every {minutes // 60} hour(s)"
        return f"Every {minutes} minutes"
    names = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]
    days = schedule["days"]
    label = "Every day" if len(days) == 7 else "Weekdays" if days == [1, 2, 3, 4, 5] else ", ".join(names[d - 1] for d in days)
    return f"{label} at {schedule['time']}"
