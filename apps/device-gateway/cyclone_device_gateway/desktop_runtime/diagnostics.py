from __future__ import annotations

import json
import os
from pathlib import Path
import re
import subprocess
import threading
import time
from typing import Any, TYPE_CHECKING
import zipfile

if TYPE_CHECKING:
    from .fleet import DeviceFleetManager, DeviceSession

_PACKAGE = "com.cyclone.mobile"
_SAFE_NAME = re.compile(r"[^A-Za-z0-9_.-]+")


class DeviceDiagnosticRecorder:
    """Bounded, read-only Android monitor for a detected Cyclone phone.

    The recorder starts as soon as an authorized USB device enters the fleet. Before pairing it uses
    only low-impact process/state reads plus a Cyclone-PID logcat stream. Expensive crash/package
    snapshots are reserved for a real failure or process death, so observation does not perturb the
    pairing transition it is meant to diagnose.
    """

    POLL_SECONDS = 0.75
    SETTLE_SECONDS = 0.50
    MAX_LOG_BYTES = 2 * 1024 * 1024
    MAX_TIMELINE_BYTES = 2 * 1024 * 1024

    def __init__(self, session: "DeviceSession", runtime_root: Path | None = None):
        self.session = session
        root = runtime_root or Path(os.getenv("CYCLONE_DEVICE_GATEWAY_RUNTIME", ".runtime/device-gateway")).expanduser().resolve()
        stamp = time.strftime("%Y%m%d-%H%M%S", time.localtime())
        safe_device = _safe_component(session.device_id)
        self.path = root / "diagnostics" / f"live-{safe_device}-{stamp}"
        self.timeline_path = self.path / "timeline.jsonl"
        self.logcat_path = self.path / "cyclone-process.logcat.txt"
        self._lock = threading.RLock()
        self._capture_lock = threading.Lock()
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._logcat_process: subprocess.Popen | None = None
        self._logcat_file = None
        self._started_at_ms = 0
        self._last_stage = "not-started"
        self._latest_snapshot: str | None = None
        self._latest_bundle_path: str | None = None
        self._pid: str | None = None
        self._snapshot_counter = 0

    def start(self) -> None:
        with self._lock:
            if self._thread and self._thread.is_alive():
                return
            self.path.mkdir(parents=True, exist_ok=True)
            self._started_at_ms = int(time.time() * 1000)
            self._stop.clear()
            self._thread = threading.Thread(target=self._watch_loop, name=f"cyclone-diag-{self.session.device_id[:8]}", daemon=True)
            self._thread.start()
        self.mark("usb.authorized.monitor_started")
        self.capture_async("usb.authorized.baseline", heavy=False)

    def stop(self) -> None:
        self.mark("usb.monitor_stopping")
        self._stop.set()
        self._stop_logcat()
        thread = self._thread
        if thread and thread.is_alive() and thread is not threading.current_thread():
            thread.join(timeout=2.0)

    def mark(self, stage: str, details: dict[str, Any] | None = None) -> None:
        normalized = _safe_stage(stage)
        with self._lock:
            self._last_stage = normalized
            item: dict[str, Any] = {
                "atEpochMs": int(time.time() * 1000),
                "stage": normalized,
                "pid": self._pid,
            }
            if details:
                item["details"] = _safe_details(details)
            self._append_timeline(item)

    def create_connection_bundle(self, manifest: dict[str, Any]) -> str:
        """Create one bounded, sendable zip with phone, fleet, server and client evidence."""
        self.mark("connection.bundle.requested")
        snapshot_path = self.capture_snapshot("connection.bundle.android", heavy=True)
        now_ms = int(time.time() * 1000)
        stamp = f"{time.strftime('%Y%m%d-%H%M%S', time.localtime(now_ms / 1000))}-{now_ms % 1000:03d}"
        target = self.path / f"cyclone-connection-debug-{stamp}.zip"
        temporary = target.with_suffix(".zip.tmp")
        payload = {
            **manifest,
            "createdAtEpochMs": int(time.time() * 1000),
            "privacy": "No gateway credential, pairing code, clipboard content, typed value, password, OTP, arbitrary shell, root or su output is intentionally included.",
        }
        with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("connection-manifest.json", json.dumps(payload, indent=2, sort_keys=True))
            candidates = [self.timeline_path, self.logcat_path]
            candidates.extend(sorted(self.path.glob("snapshot-*.json")))
            if snapshot_path:
                candidates.append(Path(snapshot_path))
            seen: set[Path] = set()
            for candidate in candidates:
                resolved = candidate.resolve()
                if resolved in seen or not candidate.is_file():
                    continue
                seen.add(resolved)
                archive.write(candidate, arcname=candidate.name)
        temporary.replace(target)
        self.mark("connection.bundle.saved", {"code": "BUNDLE_SAVED"})
        with self._lock:
            self._latest_bundle_path = str(target)
        return str(target)

    def capture_async(self, label: str, *, heavy: bool = False) -> None:
        if self._stop.is_set():
            return
        thread = threading.Thread(
            target=lambda: self.capture_snapshot(label, heavy=heavy),
            name=f"cyclone-diag-snapshot-{self.session.device_id[:8]}",
            daemon=True,
        )
        thread.start()

    def capture_snapshot(self, label: str, *, heavy: bool = True) -> str | None:
        normalized = _safe_stage(label)
        with self._capture_lock:
            try:
                snapshot = self._fixed_snapshot(heavy=heavy)
                with self._lock:
                    self._snapshot_counter += 1
                    name = f"snapshot-{self._snapshot_counter:03d}-{_safe_component(normalized)}.json"
                    target = self.path / name
                    target.write_text(json.dumps(snapshot, indent=2, sort_keys=True), encoding="utf-8")
                    self._latest_snapshot = str(target)
                    self._append_timeline({
                        "atEpochMs": int(time.time() * 1000),
                        "stage": "diagnostics.snapshot",
                        "label": normalized,
                        "kind": "heavy" if heavy else "baseline",
                        "path": str(target),
                        "pid": self._pid,
                    })
                    return str(target)
            except Exception as exc:
                with self._lock:
                    self._append_timeline({
                        "atEpochMs": int(time.time() * 1000),
                        "stage": "diagnostics.snapshot_failed",
                        "label": normalized,
                        "error": f"{exc.__class__.__name__}: {str(exc)[:240]}",
                    })
                return None

    def public_status(self) -> dict[str, Any]:
        thread = self._thread
        with self._lock:
            return {
                "active": bool(thread and thread.is_alive() and not self._stop.is_set()),
                "sessionPath": str(self.path),
                "timelinePath": str(self.timeline_path),
                "latestSnapshotPath": self._latest_snapshot,
                "latestBundlePath": self._latest_bundle_path,
                "lastStage": self._last_stage,
                "appPid": self._pid,
                "startedAtEpochMs": self._started_at_ms or None,
                "rootRequired": False,
                "mode": "ADB_READ_ONLY_PROCESS_MONITOR",
            }

    def _watch_loop(self) -> None:
        last_pid: str | None = None
        while not self._stop.is_set():
            current_pid = self._read_pid()
            if current_pid != last_pid:
                with self._lock:
                    self._pid = current_pid
                self._stop_logcat()
                if current_pid:
                    self.mark("android.cyclone_process_started")
                    self._start_logcat(current_pid)
                    self.capture_async("android.process_started", heavy=False)
                elif last_pid:
                    self.mark("android.cyclone_process_disappeared")
                    self.capture_snapshot("android.process_gone_immediate", heavy=True)
                    if not self._stop.wait(self.SETTLE_SECONDS):
                        self.capture_snapshot("android.process_gone_settled", heavy=True)
                last_pid = current_pid
            self._stop.wait(self.POLL_SECONDS)

    def _read_pid(self) -> str | None:
        try:
            value = self.session.adb.shell("pidof", _PACKAGE, timeout=3).strip().split()
            return value[0] if value else None
        except Exception:
            return None

    def _start_logcat(self, pid: str) -> None:
        handle = None
        try:
            self.path.mkdir(parents=True, exist_ok=True)
            if self.logcat_path.exists() and self.logcat_path.stat().st_size > self.MAX_LOG_BYTES:
                previous = self.logcat_path.with_suffix(self.logcat_path.suffix + ".previous")
                try:
                    previous.unlink(missing_ok=True)
                except Exception:
                    pass
                try:
                    self.logcat_path.replace(previous)
                except Exception:
                    pass
            handle = self.logcat_path.open("ab", buffering=0)
            process = self.session.adb.start_process(
                ["logcat", f"--pid={pid}", "-v", "threadtime", "*:I"],
                stdout=handle,
            )
            with self._lock:
                self._logcat_file = handle
                self._logcat_process = process
            self.mark("android.logcat_attached")
        except Exception as exc:
            if handle is not None:
                try:
                    handle.close()
                except Exception:
                    pass
            self._append_timeline({
                "atEpochMs": int(time.time() * 1000),
                "stage": "android.logcat_attach_failed",
                "error": f"{exc.__class__.__name__}: {str(exc)[:240]}",
            })

    def _stop_logcat(self) -> None:
        with self._lock:
            process = self._logcat_process
            handle = self._logcat_file
            self._logcat_process = None
            self._logcat_file = None
        if process is not None:
            try:
                process.terminate()
                process.wait(timeout=1.0)
            except Exception:
                try:
                    process.kill()
                except Exception:
                    pass
        if handle is not None:
            try:
                handle.close()
            except Exception:
                pass

    def _fixed_snapshot(self, *, heavy: bool) -> dict[str, Any]:
        adb = self.session.adb

        def capture(action) -> str:
            try:
                value = str(action()).replace("\x00", "")
                return value[-64_000:]
            except Exception as exc:
                return f"<unavailable: {exc.__class__.__name__}: {str(exc)[:240]}>"

        snapshot: dict[str, Any] = {
            "capturedAtEpochMs": int(time.time() * 1000),
            "snapshotKind": "heavy" if heavy else "baseline",
            "deviceId": self.session.device_id,
            # What adb reports right now: after a disconnect the fleet's cached "device" would contradict every read below.
            "adbState": capture(lambda: adb.run(["get-state"], timeout=3).strip()),
            "fleetAdbState": self.session.adb_device.state,
            "model": self.session.adb_device.model,
            "product": self.session.adb_device.product,
            "processPid": self._read_pid(),
            "lastMonitorStage": self._last_stage,
            "androidRelease": capture(lambda: adb.shell("getprop", "ro.build.version.release", timeout=3).strip()),
            "androidSdk": capture(lambda: adb.shell("getprop", "ro.build.version.sdk", timeout=3).strip()),
            "bootReason": capture(lambda: adb.shell("getprop", "ro.boot.bootreason", timeout=3).strip()),
            "uptime": capture(lambda: adb.shell("cat", "/proc/uptime", timeout=3).strip()),
            "enabledAccessibilityServices": capture(lambda: adb.shell("settings", "get", "secure", "enabled_accessibility_services", timeout=3).strip()),
            "accessibilityEnabled": capture(lambda: adb.shell("settings", "get", "secure", "accessibility_enabled", timeout=3).strip()),
            "privacy": "Fixed read-only ADB diagnostics only. No root/su, arbitrary shell, pairing code, credential, clipboard content, password, OTP, or typed value is intentionally recorded.",
        }
        if heavy:
            snapshot.update({
                "packageState": capture(lambda: adb.shell("dumpsys", "package", _PACKAGE, timeout=8)),
                "processMemory": capture(lambda: adb.shell("dumpsys", "meminfo", _PACKAGE, timeout=8)),
                "accessibilityState": capture(lambda: adb.shell("dumpsys", "accessibility", timeout=8)),
                "crash": adb.collect_cyclone_crash_diagnostics() if hasattr(adb, "collect_cyclone_crash_diagnostics") else {},
            })
        return snapshot

    def _append_timeline(self, item: dict[str, Any]) -> None:
        try:
            self.path.mkdir(parents=True, exist_ok=True)
            if self.timeline_path.exists() and self.timeline_path.stat().st_size > self.MAX_TIMELINE_BYTES:
                previous = self.timeline_path.with_suffix(".jsonl.previous")
                previous.unlink(missing_ok=True)
                self.timeline_path.replace(previous)
            with self.timeline_path.open("a", encoding="utf-8") as handle:
                handle.write(json.dumps(item, sort_keys=True) + "\n")
        except Exception:
            pass


class FleetDiagnosticSupervisor:
    """Starts a recorder automatically for every authorized USB phone in Cyclone's fleet."""

    SYNC_SECONDS = 0.50

    def __init__(self, fleet: "DeviceFleetManager", runtime_root: Path | None = None):
        self.fleet = fleet
        self.runtime_root = runtime_root
        self._lock = threading.RLock()
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._recorders: dict[str, DeviceDiagnosticRecorder] = {}

    def start(self) -> None:
        with self._lock:
            if self._thread and self._thread.is_alive():
                return
            self._stop.clear()
            self._thread = threading.Thread(target=self._loop, name="cyclone-live-diagnostics", daemon=True)
            self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        thread = self._thread
        if thread and thread.is_alive():
            thread.join(timeout=2.0)
        with self._lock:
            recorders = list(self._recorders.values())
            self._recorders.clear()
        for recorder in recorders:
            recorder.stop()

    def ensure(self, device_id: str) -> DeviceDiagnosticRecorder | None:
        try:
            session = self.fleet.get(device_id)
        except Exception:
            return None
        if session.adb_device.state != "device":
            self._retire(device_id)
            return None
        old: DeviceDiagnosticRecorder | None = None
        with self._lock:
            recorder = self._recorders.get(device_id)
            if recorder is not None and recorder.session is not session:
                old = recorder
                recorder = None
                self._recorders.pop(device_id, None)
            if recorder is None:
                recorder = DeviceDiagnosticRecorder(session, self.runtime_root)
                self._recorders[device_id] = recorder
        if old is not None:
            old.stop()
        recorder.start()
        return recorder

    def mark(
        self,
        device_id: str,
        stage: str,
        *,
        snapshot: bool = False,
        details: dict[str, Any] | None = None,
    ) -> dict[str, Any] | None:
        recorder = self.ensure(device_id)
        if recorder is None:
            return None
        recorder.mark(stage, details)
        if snapshot:
            recorder.capture_async(stage, heavy=True)
        return recorder.public_status()

    def create_connection_bundle(self, device_id: str, manifest: dict[str, Any]) -> str | None:
        recorder = self.ensure(device_id)
        if recorder is None:
            return None
        recent_events = []
        recent = getattr(self.fleet.events, "recent", None)
        if callable(recent):
            try:
                recent_events = recent(256)
            except Exception:
                recent_events = []
        enriched = {**manifest, "recentFleetEvents": recent_events}
        return recorder.create_connection_bundle(enriched)

    def status(self) -> dict[str, Any]:
        with self._lock:
            devices = {device_id: recorder.public_status() for device_id, recorder in self._recorders.items()}
        active = sum(1 for item in devices.values() if item.get("active") is True)
        latest = max(
            (item for item in devices.values() if item.get("startedAtEpochMs")),
            key=lambda item: int(item.get("startedAtEpochMs") or 0),
            default=None,
        )
        return {
            "active": active > 0,
            "activeDeviceCount": active,
            "deviceCount": len(devices),
            "latestSessionPath": latest.get("sessionPath") if latest else None,
            "devices": devices,
            "rootRequired": False,
            "mode": "ADB_READ_ONLY_PROCESS_MONITOR",
        }

    def _loop(self) -> None:
        while not self._stop.is_set():
            try:
                public_devices = self.fleet.list_public()
                seen: set[str] = set()
                for item in public_devices:
                    device_id = str(item.get("deviceId") or item.get("id") or "")
                    if not device_id:
                        continue
                    seen.add(device_id)
                    try:
                        session = self.fleet.get(device_id)
                    except Exception:
                        self._retire(device_id)
                        continue
                    if session.adb_device.state == "device":
                        self.ensure(device_id)
                    else:
                        self._retire(device_id)
                with self._lock:
                    stale = [device_id for device_id in self._recorders if device_id not in seen]
                for device_id in stale:
                    self._retire(device_id)
            except Exception:
                pass
            self._stop.wait(self.SYNC_SECONDS)

    def _retire(self, device_id: str) -> None:
        with self._lock:
            recorder = self._recorders.pop(device_id, None)
        if recorder is not None:
            recorder.stop()


def _safe_component(value: str) -> str:
    return _SAFE_NAME.sub("-", value).strip("-._")[:96] or "unknown"


def _safe_stage(value: str) -> str:
    return _SAFE_NAME.sub("_", value).strip("._-")[:120] or "unknown"


def _safe_details(details: dict[str, Any]) -> dict[str, Any]:
    allowed = {"attempt", "closeCode", "code", "errorClass", "profile", "retryable", "source", "state", "transport"}
    safe: dict[str, Any] = {}
    for key in sorted(allowed.intersection(details)):
        value = details[key]
        if isinstance(value, bool):
            safe[key] = value
        elif isinstance(value, int):
            safe[key] = max(-1, min(value, 1_000_000))
        elif isinstance(value, str):
            safe[key] = _SAFE_NAME.sub("_", value).strip("._-")[:120] or "unknown"
    return safe
