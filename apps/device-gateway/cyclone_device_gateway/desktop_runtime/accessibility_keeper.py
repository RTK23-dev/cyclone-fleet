"""Puts Cyclone's Accessibility back after Android took it away (alpha 91).

When Cyclone is force-stopped (or crashes and is stopped), Android removes it from the enabled accessibility services,
and until someone flips the switch again the phone is dead to Cyclone. This PC already has the owner's USB-debugging
trust, so it can restore exactly that one setting, under fixed limits:

- only on a phone where this PC saw Cyclone's Accessibility **on** before (it restores the owner's choice, never makes it);
- only Cyclone's own service is added; every other service in the list is kept as it was;
- at most three times an hour per phone, each one recorded in the diagnostics;
- off with ``CYCLONE_AUTO_REPAIR_ACCESSIBILITY=0``.

It never turns Accessibility on for a phone where the owner never did, and it never touches another setting.

Alpha 93: the owner's own **Repair** press (Glass, ``connection/fix open_accessibility``) is that choice made now, so
:meth:`AccessibilityKeeper.repair_now` puts Cyclone back on that phone without the "seen on before" condition, at most
``MAX_OWNER_REPAIRS_PER_HOUR`` times an hour; the medic opens the Accessibility list instead when it can't.
"""
from __future__ import annotations

import json
import os
import re
import threading
import time
from pathlib import Path
from typing import Any, Callable

from ..adb.device import CYCLONE_PACKAGE

SERVICE = f"{CYCLONE_PACKAGE}/.CycloneAccessibilityService"
SERVICE_FULL = f"{CYCLONE_PACKAGE}/{CYCLONE_PACKAGE}.CycloneAccessibilityService"
KEY = "enabled_accessibility_services"
COMPONENT = re.compile(r"^[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+$")
MAX_PER_HOUR = 3
MAX_OWNER_REPAIRS_PER_HOUR = 6
CHECK_EVERY_S = 10.0


def enabled() -> bool:
    return os.getenv("CYCLONE_AUTO_REPAIR_ACCESSIBILITY", "1").strip() != "0"


def has_cyclone(value: str) -> bool:
    return any(part.strip() in {SERVICE, SERVICE_FULL} for part in value.split(":"))


def restored(value: str) -> str:
    """The list with Cyclone added, keeping every well-formed service already there (and nothing else)."""
    parts = [p.strip() for p in (value or "").strip().split(":") if p.strip() and p.strip() != "null"]
    kept = [p for p in parts if COMPONENT.match(p) and p not in {SERVICE, SERVICE_FULL}]
    return ":".join(kept + [SERVICE])


class AccessibilityKeeper:
    def __init__(self, path: Path, diagnostics: Any = None, *, clock: Callable[[], float] = time.time):
        self.path = path
        self.diagnostics = diagnostics
        self.clock = clock
        self._lock = threading.Lock()
        self._seen: dict[str, bool] = self._load()
        self._repairs: dict[str, list[float]] = {}
        self._checked: dict[str, float] = {}
        self._owner_repairs: dict[str, list[float]] = {}

    def _load(self) -> dict[str, bool]:
        try:
            value = json.loads(self.path.read_text(encoding="utf-8"))
            return {str(k): True for k, v in (value.get("seenOn") or {}).items() if v is True}
        except (OSError, ValueError, AttributeError):
            return {}

    def _save(self) -> None:
        try:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            self.path.write_text(json.dumps({"seenOn": self._seen}), encoding="utf-8")
        except OSError:
            pass

    def seen_on(self, device_id: str) -> None:
        """The phone reports Cyclone's Accessibility on: remember that the owner turned it on here."""
        with self._lock:
            if not self._seen.get(device_id):
                self._seen[device_id] = True
                self._save()

    def repair_now(self, device_id: str, session: Any) -> str | None:
        """The owner pressed Repair: put Cyclone back now. "restored", "already_on", or None (not possible, or the hourly
        limit), in which case the caller opens the Accessibility list for the owner."""
        now = self.clock()
        with self._lock:
            recent = [t for t in self._owner_repairs.get(device_id, []) if now - t < 3600]
            self._owner_repairs[device_id] = recent
            if len(recent) >= MAX_OWNER_REPAIRS_PER_HOUR:
                return None
        try:
            value = str(session.adb.shell("settings", "get", "secure", KEY, timeout=5)).strip()
            if has_cyclone(value):
                return "already_on"
            session.adb.shell("settings", "put", "secure", KEY, f"'{restored(value)}'", timeout=5)
            session.adb.shell("settings", "put", "secure", "accessibility_enabled", "1", timeout=5)
            after = str(session.adb.shell("settings", "get", "secure", KEY, timeout=5)).strip()
        except Exception:
            return None
        if not has_cyclone(after):
            return None
        with self._lock:
            self._owner_repairs[device_id] = recent + [now]
        self.seen_on(device_id)
        if self.diagnostics is not None:
            try:
                self.diagnostics.mark(device_id, "connection.fix.accessibility_restored",
                                      details={"attempt": len(recent) + 1, "code": "ACCESSIBILITY_RESTORED_BY_OWNER"})
            except Exception:
                pass
        return "restored"

    def check(self, device_id: str, session: Any) -> str | None:
        """One look at a phone whose Accessibility is off. Returns "restored" when it put Cyclone back."""
        now = self.clock()
        with self._lock:
            if now - self._checked.get(device_id, 0.0) < CHECK_EVERY_S:
                return None
            self._checked[device_id] = now
            if not enabled() or not self._seen.get(device_id):
                return None
            recent = [t for t in self._repairs.get(device_id, []) if now - t < 3600]
            if len(recent) >= MAX_PER_HOUR:
                self._repairs[device_id] = recent
                return None
        try:
            value = str(session.adb.shell("settings", "get", "secure", KEY, timeout=5)).strip()
            if has_cyclone(value):
                return None
            session.adb.shell("settings", "put", "secure", KEY, f"'{restored(value)}'", timeout=5)
            session.adb.shell("settings", "put", "secure", "accessibility_enabled", "1", timeout=5)
        except Exception:
            return None
        with self._lock:
            self._repairs[device_id] = recent + [now]
        if self.diagnostics is not None:
            try:
                self.diagnostics.mark(device_id, "connection.medic.accessibility_restored",
                                      details={"attempt": len(recent) + 1, "code": "ACCESSIBILITY_RESTORED"})
            except Exception:
                pass
        return "restored"
