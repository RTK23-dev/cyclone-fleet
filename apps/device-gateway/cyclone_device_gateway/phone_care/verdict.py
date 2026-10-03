"""One calm answer per phone for Glass: a status, a headline, one sentence and at most one action. Everything
behind it (versions, exits, freezes, the update's error code) stays under `details` for whoever debugs. Pure."""
from __future__ import annotations

from typing import Any

from . import health as h
from .versions import InstalledApp, compare

RECENT_MS = 24 * 3600 * 1000
JOB_NOTE_MS = 10 * 60 * 1000
# Stops worth interrupting the owner for. Android reclaiming memory from a background app is normal and stays in details.
WARN_KINDS = {"crash", "crash_native", "anr", "initialization_failure", "excessive_resource_usage"}
# A freeze worth a headline: long enough to feel broken, or often enough to be a pattern.
FREEZE_LONG_MS = 2_000
FREEZE_MANY = 3
ACTIVE = {"preparing", "downloading", "verifying", "installing"}
STAGE_TEXT = {
    "preparing": "Checking the phone…",
    "downloading": "Getting the update…",
    "verifying": "Checking the download…",
    "installing": "Installing on the phone. Keep it plugged in.",
}


def _answer(status: str, headline: str, detail: str, action: dict[str, str] | None = None, *, at_ms: int | None = None,
            hint: str | None = None) -> dict[str, Any]:
    return {"status": status, "headline": headline, "detail": detail, "action": action, "atMs": at_ms, "hint": hint}


def compose(*, reachable: bool, pc_version: str, phone: InstalledApp | None, phone_known: bool, job: dict[str, Any] | None,
            history: dict[str, Any], now_ms: int) -> dict[str, Any]:
    state = compare(phone.version_name if phone else None, pc_version) if phone_known else "unknown"
    stop = h.last_stop(history)
    freeze = h.freezes(history, now_ms)
    job_state = (job or {}).get("state")
    job_recent = bool(job) and now_ms - int((job or {}).get("finishedAtMs") or now_ms) <= JOB_NOTE_MS

    if job_state in ACTIVE:
        answer = _answer("working", "Updating the phone", STAGE_TEXT[job_state])
    elif job_state == "failed" and job_recent:
        error = job.get("error") or {}
        again = {"kind": "update", "label": "Try again"} if error.get("retryable", True) else None
        answer = _answer("problem", error.get("title") or "The update didn't finish", error.get("message") or "", again,
                         hint=error.get("action"))
    elif not reachable:
        answer = _answer("offline", "", "")
    elif state == "missing":
        answer = _answer("problem", "Cyclone isn't on this phone", "Install it from this PC. It takes about a minute.",
                         {"kind": "update", "label": "Install Cyclone"})
    elif state == "older":
        answer = _answer("attention", "Update available",
                         f"The phone has Cyclone {phone.version_name}; this PC has {pc_version}.",
                         {"kind": "update", "label": "Update phone"})
    elif state == "newer":
        answer = _answer("attention", "This PC needs an update",
                         f"The phone has a newer Cyclone ({phone.version_name}) than this PC ({pc_version}).",
                         hint="On this PC, type cyclone update.")
    elif job_state == "done" and job_recent and (job or {}).get("alreadyCurrent"):
        answer = _answer("good", "Already up to date", f"Cyclone {pc_version} was already on the phone. Nothing was installed.")
    elif job_state == "done" and job_recent:
        answer = _answer("good", "Updated", f"Cyclone {pc_version} is on the phone.")
    elif stop and stop["kind"] in WARN_KINDS and now_ms - stop["atMs"] <= RECENT_MS:
        answer = _answer("attention", "Cyclone stopped unexpectedly", f"It stopped because {stop['label']}.", at_ms=stop["atMs"])
    elif freeze["count"] >= FREEZE_MANY or freeze["longestMs"] >= FREEZE_LONG_MS:
        times = "once" if freeze["count"] == 1 else f"{freeze['count']} times"
        answer = _answer("attention", f"Cyclone froze {times} today",
                         f"The longest freeze lasted {freeze['longestMs'] / 1000:.1f} s. Cyclone recorded where it happened.",
                         at_ms=freeze["lastAtMs"])
    elif state == "same":
        answer = _answer("good", "Up to date", f"Cyclone {pc_version} on the phone and this PC.")
    else:
        answer = _answer("good", "Connected", "Cyclone on the phone is running.")

    answer["details"] = {
        "versions": {"phone": phone.version_name if phone else None, "phoneCode": phone.version_code if phone else None,
                     "pc": pc_version, "compare": state},
        "update": job,
        "lastStop": stop,
        "freezes": freeze,
        "exits": list(reversed(history.get("exits") or []))[:10],
        "stalls": list(reversed(history.get("stalls") or []))[:10],
        "healthCollectedAtMs": history.get("collectedAtMs"),
        # Alpha 89: how this phone decides requests (JEV, the phone model), for developers.
        "decisions": history.get("decisions"),
    }
    return answer
