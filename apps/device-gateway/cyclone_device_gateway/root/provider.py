from __future__ import annotations

from dataclasses import dataclass
from hashlib import sha256
from pathlib import Path
from time import time
from typing import Any

from ..adb.client import ADBClient


class RootUnavailable(RuntimeError):
    pass


@dataclass
class InputTrace:
    started_at: float
    process: Any
    path: Path
    file_handle: Any


class RootProvider:
    """Strict root telemetry surface. There is intentionally no generic su method."""
    def __init__(self, adb: ADBClient, trace_dir: Path | None = None):
        self.adb = adb
        self.trace_dir = trace_dir or Path(".runtime/device-gateway/input-traces")
        self.trace: InputTrace | None = None

    def available(self) -> bool:
        try:
            return "uid=0" in self.adb.shell("su", "-c", "id", timeout=5)
        except Exception:
            return False

    def _require(self) -> None:
        if not self.available():
            raise RootUnavailable("Root telemetry unavailable")

    def input_devices(self) -> str:
        self._require(); return self.adb.shell("su", "-c", "cat /proc/bus/input/devices")

    def input_trace_start(self) -> dict:
        self._require()
        if self.trace:
            raise RuntimeError("Input trace already active")
        self.trace_dir.mkdir(parents=True, exist_ok=True)
        started = time()
        path = self.trace_dir / f"getevent-{int(started * 1000)}.log"
        fh = path.open("wb")
        proc = self.adb.start_process(["shell", "su", "-c", "getevent -lt"], stdout=fh)
        self.trace = InputTrace(started, proc, path, fh)
        return {"active": True, "started_at": started, "path": str(path)}

    def input_trace_stop(self) -> dict:
        if not self.trace:
            return {"active": False}
        trace, self.trace = self.trace, None
        trace.process.terminate()
        try:
            trace.process.wait(timeout=2)
        except Exception:
            trace.process.kill(); trace.process.wait()
        trace.file_handle.close()
        data = trace.path.read_bytes() if trace.path.exists() else b""
        return {"active": False, "started_at": trace.started_at, "stopped_at": time(), "path": str(trace.path),
                "bytes": len(data), "sha256": sha256(data).hexdigest(), "raw_events_local_only": True}

    def dumpsys_window(self) -> str:
        self._require(); return self.adb.shell("su", "-c", "dumpsys window windows")

    def dumpsys_input(self) -> str:
        self._require(); return self.adb.shell("su", "-c", "dumpsys input")

    def filtered_logcat(self) -> str:
        self._require(); return self.adb.shell("su", "-c", "logcat -d -t 300 ActivityTaskManager:I AndroidRuntime:E Cyclone:I *:S")

    def process_info(self) -> str:
        self._require(); return self.adb.shell("su", "-c", "ps -A -o USER,PID,PPID,NAME,ARGS")
