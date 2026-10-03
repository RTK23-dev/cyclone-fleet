"""Fixes the connection links a PC can safely fix on its own, and the ones the owner asks for with one click (alpha 88).

Automatic, without showing anything on the phone:
- Cyclone's process is gone (crash, Android closed it): wake it with an explicit broadcast to Cyclone's own receiver,
  which only adb or the system may send (it is guarded by android.permission.DUMP). At most once every 30 s and
  five times per USB session, so a phone that keeps dying is reported, not hammered.

On the owner's click (fixed commands only, never caller-chosen):
- start_app: the same wake, then Cyclone's launcher if the process still isn't there (older phone builds);
- open_accessibility: opens Android's Accessibility settings on the phone, for the owner to turn Cyclone on;
- open_cyclone: opens Cyclone on the phone (PC Gateway lives in its settings).
Nothing here grants a permission, changes a setting or touches trust. (Putting Cyclone's own Accessibility back after
Android removed it is the separate, narrowly fixed accessibility_keeper, alpha 91.)
"""
from __future__ import annotations

import threading
import time
from typing import Any, Callable

from ..adb.device import CYCLONE_PACKAGE
from .models import DesktopRuntimeError, DeviceFleetState, RuntimeErrorCode

WAKE = ("am", "broadcast", "-a", "com.cyclone.mobile.action.WAKE_GATEWAY", "-n", f"{CYCLONE_PACKAGE}/.gateway.GatewayWakeReceiver")
OPEN_APP = ("am", "start", "-n", f"{CYCLONE_PACKAGE}/.MainActivity")
OPEN_ACCESSIBILITY = ("am", "start", "-a", "android.settings.ACCESSIBILITY_SETTINGS")
ACTIONS = {"start_app", "open_accessibility", "open_cyclone"}

LOOP_S = 5.0
WAKE_EVERY_S = 30.0
MAX_WAKES_PER_USB = 5


class ConnectionMedic:
    def __init__(self, fleet: Any, diagnostics: Any = None, *, clock: Callable[[], float] = time.monotonic,
                 sleep: Callable[[float], None] = time.sleep, keeper: Any = None):
        self.fleet = fleet
        self.keeper = keeper
        self.diagnostics = diagnostics
        self.clock = clock
        self.sleep = sleep
        self._wakes: dict[str, tuple[str, int, float]] = {}
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None

    def start(self) -> None:
        if self._thread and self._thread.is_alive():
            return
        self._stop.clear()
        self._thread = threading.Thread(target=self._loop, name="cyclone-connection-medic", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()

    def _loop(self) -> None:
        while not self._stop.wait(LOOP_S):
            try:
                for item in self.fleet.list_public():
                    device_id = str(item.get("deviceId") or "")
                    if device_id:
                        self.check(device_id)
            except Exception:
                pass

    @staticmethod
    def _usable(session: Any) -> bool:
        return (str(getattr(session.adb_device, "state", "") or "") == "device"
                and session.state not in {DeviceFleetState.DISCONNECTED, DeviceFleetState.UNAUTHORIZED}
                and "not installed" not in str(getattr(session, "bridge_last_error", "") or "").lower())

    def _running(self, session: Any) -> bool | None:
        try:
            return bool(session.adb.shell("pidof", CYCLONE_PACKAGE, timeout=3).strip())
        except Exception:
            return None

    def check(self, device_id: str) -> None:
        """One look at one phone: is Cyclone's process there, and if not, wake it (bounded)."""
        try:
            session = self.fleet.get(device_id)
        except DesktopRuntimeError:
            return
        if not self._usable(session):
            return
        if self.keeper is not None:
            accessibility = getattr(session, "accessibility_connected", None)
            if accessibility is True:
                self.keeper.seen_on(device_id)
            elif accessibility is False and self.keeper.check(device_id, session) == "restored":
                session.accessibility_connected = None  # the next status says whether it took
        # A phone answering its heartbeat is running; only look closer when it doesn't, or before trust.
        if session.bridge_ok is True and session.credential:
            session.app_running = True
            return
        running = self._running(session)
        if running is None:
            return
        session.app_running = running
        if running:
            return
        usb = str(getattr(session, "usb_session_id", "") or "")
        last_usb, count, last_at = self._wakes.get(device_id, ("", 0, -WAKE_EVERY_S))
        if last_usb != usb:
            count, last_at = 0, -WAKE_EVERY_S
        now = self.clock()
        if count >= MAX_WAKES_PER_USB or now - last_at < WAKE_EVERY_S:
            return
        self._wakes[device_id] = (usb, count + 1, now)
        self._wake(session)
        self._mark(device_id, "connection.medic.wake", count + 1)

    def _wake(self, session: Any) -> None:
        try:
            session.adb.shell(*WAKE, timeout=8)
        except Exception:
            pass

    def fix(self, device_id: str, action: str) -> dict[str, Any]:
        if action not in ACTIONS:
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "Unknown fix.")
        session = self.fleet.get(device_id)
        if not self._usable(session):
            raise DesktopRuntimeError(RuntimeErrorCode.DEVICE_NOT_READY, "Plug the phone in and allow USB debugging first.", retryable=True)
        try:
            if action == "start_app":
                self._wake(session)
                self.sleep(1.5)
                if not self._running(session):
                    session.adb.shell(*OPEN_APP, timeout=8)
                session.app_running = self._running(session)
            elif action == "open_accessibility":
                # Alpha 93: Repair first puts Cyclone's Accessibility back itself; the list opens only when it can't.
                repaired = self.keeper.repair_now(device_id, session) if self.keeper is not None else None
                if repaired in {"restored", "already_on"}:
                    session.accessibility_connected = None  # unknown until the phone reports again
                    self._mark(device_id, "connection.fix.open_accessibility", 1)
                    return {"deviceId": device_id, "action": action, "ok": True, "repaired": True}
                session.adb.shell(*OPEN_ACCESSIBILITY, timeout=8)
            else:
                session.adb.shell(*OPEN_APP, timeout=8)
        except DesktopRuntimeError:
            raise
        except Exception as exc:
            raise DesktopRuntimeError(RuntimeErrorCode.DEVICE_DISCONNECTED, "The phone didn't answer over USB. Check the cable and try again.",
                                      retryable=True) from exc
        self._mark(device_id, f"connection.fix.{action}", 1)
        return {"deviceId": device_id, "action": action, "ok": True}

    def _mark(self, device_id: str, stage: str, attempt: int) -> None:
        if self.diagnostics is None:
            return
        try:
            self.diagnostics.mark(device_id, stage, details={"attempt": attempt, "code": "CONNECTION_MEDIC"})
        except Exception:
            pass
