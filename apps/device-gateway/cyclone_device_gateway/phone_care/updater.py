"""One owner-started update per phone: get this PC's phone build, check it, `adb install -r` it, confirm Android now
reports the new version. Never a downgrade (`-d`), never an uninstall, never a file the release manifest doesn't list."""
from __future__ import annotations

import threading
import time
from typing import Any, Callable

from ..adb.client import ADBError
from .apk import ApkError, ApkSource
from .install_errors import InstallOutcome, read_install_output
from .versions import PACKAGE, InstalledApp, compare, parse_dumpsys_package

INSTALL_TIMEOUT_S = 300


def read_installed(adb: Any) -> InstalledApp | None:
    """The installed Cyclone, via a fixed read-only dumpsys (works before pairing and when the app is stopped)."""
    return parse_dumpsys_package(adb.shell("dumpsys", "package", PACKAGE, timeout=8))


class UpdateJobs:
    def __init__(self, apks: ApkSource, clock: Callable[[], float] = time.time, spawn: Callable[[Callable[[], None]], None] | None = None,
                 on_finish: Callable[[str, dict[str, Any]], None] = lambda _device, _job: None):
        self.apks = apks
        self.clock = clock
        self.spawn = spawn or (lambda fn: threading.Thread(target=fn, name="cyclone-phone-update", daemon=True).start())
        self.on_finish = on_finish
        self._lock = threading.Lock()
        self._jobs: dict[str, dict[str, Any]] = {}

    def get(self, device_id: str) -> dict[str, Any] | None:
        with self._lock:
            job = self._jobs.get(device_id)
            return dict(job) if job else None

    def start(self, device_id: str, adb: Any, target_version: str) -> dict[str, Any]:
        with self._lock:
            current = self._jobs.get(device_id)
            if current and current["state"] not in {"done", "failed"}:
                return dict(current)
            job = {"state": "preparing", "target": target_version, "startedAtMs": self._now(), "finishedAtMs": None,
                   "error": None, "from": None}
            self._jobs[device_id] = job
        self.spawn(lambda: self._run(device_id, adb, target_version))
        return dict(job)

    def _now(self) -> int:
        return int(self.clock() * 1000)

    def _set(self, device_id: str, **changes: Any) -> None:
        with self._lock:
            job = self._jobs.get(device_id)
            if job is not None:
                job.update(changes)

    def _fail(self, device_id: str, outcome: InstallOutcome, detail: str | None = None) -> None:
        error = outcome.to_dict()
        if detail:
            error["detail"] = detail[:400]
        self._set(device_id, state="failed", error=error, finishedAtMs=self._now())
        self.on_finish(device_id, self.get(device_id) or {})

    def _run(self, device_id: str, adb: Any, target: str) -> None:
        try:
            before = read_installed(adb)
        except ADBError as exc:
            return self._fail(device_id, read_install_output(str(exc)), str(exc))
        self._set(device_id, **{"from": before.version_name if before else None})
        state = compare(before.version_name if before else None, target)
        if state == "same":
            # Nothing to install: say so, rather than "Updating… / Updated" (alpha 91).
            self._set(device_id, state="done", finishedAtMs=self._now(), alreadyCurrent=True)
            return self.on_finish(device_id, self.get(device_id) or {})
        if state == "newer":
            return self._fail(device_id, read_install_output("INSTALL_FAILED_VERSION_DOWNGRADE"))
        stage_names = {"checking": "preparing", "downloading": "downloading", "verifying": "verifying"}
        try:
            apk = self.apks.ensure(target, progress=lambda stage: self._set(device_id, state=stage_names.get(stage, "preparing")))
        except ApkError as exc:
            return self._fail(device_id, InstallOutcome(False, "DOWNLOAD_FAILED", "Couldn't get the update", str(exc),
                                                        "Check this PC's internet connection, then try again.", True))
        self._set(device_id, state="installing")
        try:
            output = adb.run(["install", "-r", str(apk)], timeout=INSTALL_TIMEOUT_S)
        except ADBError as exc:
            outcome = read_install_output(str(exc))
            if outcome.code.startswith("INSTALL_PARSE_FAILED") or outcome.code == "INSTALL_FAILED_INVALID_APK":
                self.apks.forget(target)
            return self._fail(device_id, outcome, str(exc))
        outcome = read_install_output(str(output))
        if not outcome.ok:
            return self._fail(device_id, outcome, str(output))
        try:
            after = read_installed(adb)
        except ADBError:
            after = None
        if after is not None and compare(after.version_name, target) != "same":
            return self._fail(device_id, InstallOutcome(False, "INSTALL_NOT_APPLIED", "The update didn't take",
                                                        f"Android still reports Cyclone {after.version_name} on the phone.",
                                                        "Restart the phone, then try again.", True))
        self._set(device_id, state="done", finishedAtMs=self._now())
        self.on_finish(device_id, self.get(device_id) or {})
