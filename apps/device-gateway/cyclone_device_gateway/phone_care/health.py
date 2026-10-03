"""The phone's health report (`health.report`), kept per phone so a stop is still known after the phone reconnects.

The phone reports Android's own exit record (why Cyclone's last processes ended; the main thread's stack when a
freeze was killed) and the freezes its watchdog caught. Only fixed fields are kept: kinds, times, sizes and code
frame names. Nothing the owner typed or saw is in it.
"""
from __future__ import annotations

import json
import re
import threading
from pathlib import Path
from typing import Any

MAX_EXITS = 20
MAX_STALLS = 50
MAX_FRAMES = 24
_FRAME = re.compile(r"^[A-Za-z0-9_$.<>:-]{1,200}$")
_KIND = re.compile(r"^[a-z_]{1,40}$")
_SAFE_ID = re.compile(r"[^A-Za-z0-9_.-]+")

# What each Android exit kind means, in the owner's words ("Cyclone stopped because …").
EXIT_LABELS = {
    "anr": "it stopped responding and Android closed it",
    "crash": "it crashed",
    "crash_native": "it crashed",
    "low_memory": "Android needed the memory for other apps",
    "excessive_resource_usage": "it used too much battery or processing",
    "initialization_failure": "it couldn't start",
    "signaled": "the system stopped it",
    "dependency_died": "a part it depends on stopped",
    "user_requested": "it was closed from the phone",
    "user_stopped": "it was force-stopped",
    "package_updated": "it was updated",
    "package_state_change": "the app was changed",
    "exit_self": "it closed itself",
    "permission_change": "a permission changed",
    "freezer": "Android paused it in the background",
    "other": "of a system reason",
    "unknown": "of an unknown reason",
}


def _frames(value: Any) -> list[str]:
    if not isinstance(value, list):
        return []
    return [str(f) for f in value if isinstance(f, str) and _FRAME.match(f)][:MAX_FRAMES]


def _int(value: Any, default: int = 0) -> int:
    return int(value) if isinstance(value, (int, float)) and not isinstance(value, bool) else default


def clean_report(raw: Any) -> dict[str, Any]:
    """Only the fields Cyclone knows, with bounded sizes; anything else from the phone is dropped."""
    report = raw if isinstance(raw, dict) else {}
    app = report.get("app") if isinstance(report.get("app"), dict) else {}
    exits = []
    for item in report.get("exits") or []:
        if not isinstance(item, dict) or _int(item.get("atMs")) <= 0:
            continue
        kind = str(item.get("kind") or "unknown")
        kind = kind if _KIND.match(kind) else "unknown"
        description = item.get("description")
        exits.append({
            "atMs": _int(item.get("atMs")), "kind": kind, "reason": _int(item.get("reason")),
            "status": _int(item.get("status")), "importance": _int(item.get("importance")),
            "pssKb": _int(item.get("pssKb")), "rssKb": _int(item.get("rssKb")),
            "description": str(description)[:160] if isinstance(description, str) and description else None,
            "unexpected": item.get("unexpected") is True, "mainThread": _frames(item.get("mainThread")),
        })
    stalls = []
    for item in report.get("stalls") or []:
        if not isinstance(item, dict) or _int(item.get("startedAtMs")) <= 0:
            continue
        suspect = item.get("suspect")
        stalls.append({
            "startedAtMs": _int(item.get("startedAtMs")), "durationMs": _int(item.get("durationMs")),
            "suspect": suspect if isinstance(suspect, str) and _FRAME.match(suspect) else None,
            "frames": _frames(item.get("frames")), "samples": _int(item.get("samples")),
        })
    version = app.get("versionName")
    return {
        "app": {
            "versionName": version if isinstance(version, str) and len(version) <= 64 else None,
            "versionCode": _int(app.get("versionCode")) or None,
            "uptimeMs": _int(app.get("uptimeMs")) or None,
            "processStartedAtMs": _int(app.get("processStartedAtMs")) or None,
        },
        "exits": exits,
        "stalls": stalls,
        "decisions": clean_decisions(report.get("decisions")),
    }


_INTENT = re.compile(r"^[a-z_]{1,40}$")


def _share(value: Any) -> float | None:
    return round(float(value), 3) if isinstance(value, (int, float)) and not isinstance(value, bool) and 0 <= value <= 1 else None


def _ms(block: Any) -> dict[str, Any]:
    block = block if isinstance(block, dict) else {}
    return {"p50": _int(block.get("p50")) or None, "p95": _int(block.get("p95")) or None, "count": _int(block.get("count"))}


def clean_decisions(raw: Any) -> dict[str, Any] | None:
    """Alpha 89: how the phone decides requests. Counts, shares and times only; anything else is dropped."""
    if not isinstance(raw, dict):
        return None
    by = raw.get("byDecider") if isinstance(raw.get("byDecider"), dict) else {}
    actions = []
    for item in raw.get("actions") or []:
        if isinstance(item, dict) and isinstance(item.get("intent"), str) and _INTENT.match(item["intent"]):
            actions.append({"intent": item["intent"], "samples": _int(item.get("samples")), "agreement": _share(item.get("agreement")),
                            "phoneDecisions": _int(item.get("phoneDecisions")), "phoneFailures": _int(item.get("phoneFailures")),
                            "earned": item.get("earned") is True})
    text = lambda key: str(raw.get(key))[:40] if isinstance(raw.get(key), str) else None
    return {
        "provider": text("provider"), "phoneModel": text("phoneModel"), "speed": text("speed"),
        "lessons": _int(raw.get("lessons")), "lastDay": _int(raw.get("lastDay")),
        "byDecider": {k: _int(v) for k, v in by.items() if isinstance(k, str) and _INTENT.match(k)},
        "onPhoneShare": _share(raw.get("onPhoneShare")),
        "decisionsMs": _ms(raw.get("decisionsMs")), "phoneModelMs": _ms(raw.get("phoneModelMs")),
        "instantVerified": _share(raw.get("instantVerified")), "instantChecked": _int(raw.get("instantChecked")),
        "shadowAgreement": _share(raw.get("shadowAgreement")), "shadowChecked": _int(raw.get("shadowChecked")),
        "teaching": _int(raw.get("teaching")),
        "earned": [e for e in raw.get("earned") or [] if isinstance(e, str) and _INTENT.match(e)][:40],
        "actions": actions[:40],
    }


def merge(history: dict[str, Any], report: dict[str, Any], collected_at_ms: int) -> dict[str, Any]:
    """Adds a fresh report to the kept history: exits and freezes by their time, newest kept."""
    exits = {(e["atMs"], e["reason"]): e for e in history.get("exits", [])}
    exits.update({(e["atMs"], e["reason"]): e for e in report["exits"]})
    stalls = {s["startedAtMs"]: s for s in history.get("stalls", [])}
    stalls.update({s["startedAtMs"]: s for s in report["stalls"]})
    return {
        "app": report["app"],
        "decisions": report.get("decisions") or history.get("decisions"),
        "collectedAtMs": collected_at_ms,
        "exits": sorted(exits.values(), key=lambda e: e["atMs"])[-MAX_EXITS:],
        "stalls": sorted(stalls.values(), key=lambda s: s["startedAtMs"])[-MAX_STALLS:],
    }


def last_stop(history: dict[str, Any]) -> dict[str, Any] | None:
    """The newest time Android ended Cyclone, with its meaning."""
    exits = history.get("exits") or []
    if not exits:
        return None
    newest = max(exits, key=lambda e: e["atMs"])
    return {**newest, "label": EXIT_LABELS.get(newest["kind"], EXIT_LABELS["unknown"])}


def freezes(history: dict[str, Any], now_ms: int, window_ms: int = 24 * 3600 * 1000) -> dict[str, Any]:
    recent = [s for s in history.get("stalls") or [] if now_ms - s["startedAtMs"] <= window_ms]
    if not recent:
        return {"count": 0, "longestMs": 0, "lastAtMs": None, "suspect": None}
    longest = max(recent, key=lambda s: s["durationMs"])
    return {"count": len(recent), "longestMs": longest["durationMs"], "lastAtMs": max(s["startedAtMs"] for s in recent),
            "suspect": longest.get("suspect")}


class HealthStore:
    """One JSON file per phone under `root`, so what the phone reported survives reconnects and PC restarts."""

    def __init__(self, root: Path):
        self.root = root
        self._lock = threading.Lock()

    def _path(self, device_id: str) -> Path:
        return self.root / f"{_SAFE_ID.sub('-', device_id).strip('-.')[:96] or 'phone'}.json"

    def get(self, device_id: str) -> dict[str, Any]:
        try:
            value = json.loads(self._path(device_id).read_text(encoding="utf-8"))
            return value if isinstance(value, dict) else {}
        except (OSError, ValueError):
            return {}

    def add(self, device_id: str, report: dict[str, Any], collected_at_ms: int) -> dict[str, Any]:
        with self._lock:
            merged = merge(self.get(device_id), report, collected_at_ms)
            path = self._path(device_id)
            path.parent.mkdir(parents=True, exist_ok=True)
            temp = path.with_suffix(".tmp")
            temp.write_text(json.dumps(merged, indent=1, sort_keys=True), encoding="utf-8")
            temp.replace(path)
            return merged
