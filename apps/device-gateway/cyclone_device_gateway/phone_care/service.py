"""Phone care for every phone in the fleet: the update jobs, the health collector and one verdict per phone."""
from __future__ import annotations

import json
import threading
import time
from pathlib import Path
from typing import Any, Callable

from ..adb.client import ADBError
from ..cyclone_bridge.client import BridgeError, BridgeOperationError
from ..desktop_runtime.models import DesktopRuntimeError, DeviceFleetState, RuntimeErrorCode
from ..terminal.release import installed_version, version_key
from . import health as h
from .apk import ApkSource
from .updater import UpdateJobs, read_installed
from .verdict import compose

COLLECT_EVERY_S = 60.0
LOOP_S = 15.0
VERSION_TTL_S = 20.0
HEALTH_TIMEOUT_S = 5.0


class PhoneCareService:
    def __init__(self, fleet: Any, root: Path, *, diagnostics: Any = None, pc_version: Callable[[], str] = installed_version,
                 clock: Callable[[], float] = time.time, apks: ApkSource | None = None, jobs: UpdateJobs | None = None):
        self.fleet = fleet
        self.diagnostics = diagnostics
        self.pc_version = pc_version
        self.clock = clock
        self.store = h.HealthStore(root / "devices")
        self.jobs = jobs or UpdateJobs(apks or ApkSource(root / "apk"), clock=clock, on_finish=self._update_finished)
        self._versions: dict[str, tuple[float, Any, bool]] = {}
        self._collected: dict[str, float] = {}
        self._unsupported: set[str] = set()
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._lock = threading.Lock()

    # Lifecycle ----------------------------------------------------------------------------------------------

    def start(self) -> None:
        if self._thread and self._thread.is_alive():
            return
        self._stop.clear()
        self._thread = threading.Thread(target=self._loop, name="cyclone-phone-care", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()

    def _loop(self) -> None:
        while not self._stop.wait(LOOP_S):
            try:
                for item in self.fleet.list_public():
                    device_id = str(item.get("deviceId") or "")
                    if device_id and self.clock() - self._collected.get(device_id, 0) >= COLLECT_EVERY_S:
                        self.collect(device_id)
            except Exception:
                pass

    # Health -------------------------------------------------------------------------------------------------

    def collect(self, device_id: str) -> dict[str, Any] | None:
        """Asks a connected, trusted phone for its health report and keeps it. None when the phone can't answer."""
        try:
            session = self.fleet.get(device_id)
        except DesktopRuntimeError:
            return None
        if session.state != DeviceFleetState.READY or not session.credential:
            return None
        version = session.mobile_version or ""
        if version in self._unsupported:
            return None
        with self._lock:
            self._collected[device_id] = self.clock()
        try:
            bridge = session.bridge()
            bridge.timeout = HEALTH_TIMEOUT_S
            raw = bridge.request("health.report", {})
        except BridgeOperationError as exc:
            if exc.code in {"UNKNOWN_OPERATION", "UNSUPPORTED_OPERATION", "PROTOCOL_MISMATCH"}:
                self._unsupported.add(version)
            return None
        except (BridgeError, OSError):
            return None
        report = h.clean_report(raw)
        merged = self.store.add(device_id, report, int(self.clock() * 1000))
        self._note_in_diagnostics(device_id, report)
        return merged

    def _note_in_diagnostics(self, device_id: str, report: dict[str, Any]) -> None:
        """Puts the report next to the USB session's timeline, so a debug bundle carries why the app stopped."""
        if self.diagnostics is None:
            return
        try:
            recorder = self.diagnostics.ensure(device_id)
            if recorder is None:
                return
            (recorder.path / "phone-health.json").write_text(json.dumps(report, indent=1, sort_keys=True), encoding="utf-8")
            unexpected = sum(1 for e in report["exits"] if e["unexpected"])
            recorder.mark("phone.health.collected", {"code": "HEALTH_REPORT", "attempt": len(report["stalls"]), "state": f"exits_{unexpected}"})
        except Exception:
            pass

    # Versions -----------------------------------------------------------------------------------------------

    def _phone_version(self, device_id: str, session: Any) -> tuple[Any, bool]:
        cached = self._versions.get(device_id)
        if cached and self.clock() - cached[0] < VERSION_TTL_S:
            return cached[1], cached[2]
        try:
            installed, known = read_installed(session.adb), True
        except (ADBError, OSError):
            installed, known = None, False
        self._versions[device_id] = (self.clock(), installed, known)
        return installed, known

    # Verdict and update -------------------------------------------------------------------------------------

    def care(self, device_id: str) -> dict[str, Any]:
        session = self.fleet.get(device_id)
        reachable = session.state not in {DeviceFleetState.DISCONNECTED, DeviceFleetState.UNAUTHORIZED}
        phone, known = self._phone_version(device_id, session) if reachable else (None, False)
        if reachable and self.clock() - self._collected.get(device_id, 0) >= COLLECT_EVERY_S:
            # Never make Glass wait on the phone: the fresh report shows on the next look.
            self._collected[device_id] = self.clock()
            threading.Thread(target=self.collect, args=(device_id,), name="cyclone-phone-health", daemon=True).start()
        answer = compose(reachable=reachable, pc_version=self.pc_version(), phone=phone, phone_known=known,
                         job=self.jobs.get(device_id), history=self.store.get(device_id), now_ms=int(self.clock() * 1000))
        answer["deviceId"] = device_id
        if self.diagnostics is not None:
            try:
                status = self.diagnostics.status().get("devices", {}).get(device_id) or {}
                answer["details"]["diagnosticsPath"] = status.get("sessionPath")
            except Exception:
                pass
        return answer

    def start_update(self, device_id: str) -> dict[str, Any]:
        session = self.fleet.get(device_id)
        if session.state in {DeviceFleetState.DISCONNECTED, DeviceFleetState.UNAUTHORIZED}:
            raise DesktopRuntimeError(RuntimeErrorCode.DEVICE_NOT_READY, "Plug the phone in and allow USB debugging first.", retryable=True)
        target = self.pc_version()
        if version_key(target) is None or target == "0.0.0":
            raise DesktopRuntimeError(RuntimeErrorCode.CAPABILITY_UNAVAILABLE, "This PC's Cyclone version is unknown, so it can't pick a phone update.")
        self._versions.pop(device_id, None)
        self.jobs.start(device_id, session.adb, target)
        return self.care(device_id)

    def _update_finished(self, device_id: str, job: dict[str, Any]) -> None:
        self._versions.pop(device_id, None)
        if self.diagnostics is not None:
            error = job.get("error") or {}
            try:
                self.diagnostics.mark(device_id, "phone.update." + str(job.get("state") or "unknown"),
                                      details={"code": error.get("code") or "UPDATED", "retryable": bool(error.get("retryable"))})
            except Exception:
                pass
