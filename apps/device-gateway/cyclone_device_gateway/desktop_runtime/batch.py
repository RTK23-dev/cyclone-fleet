from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field
import re
import secrets
import threading
import time
from typing import Any, Callable

from ..backends.base import DeviceBackend


ALLOWED_BATCH_OPERATIONS = frozenset({"home", "back", "open_app", "screenshot", "recover"})
_PACKAGE = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+$")


@dataclass
class BatchTask:
    batch_id: str
    operation: str
    device_ids: tuple[str, ...]
    status: str = "RUNNING"
    created_at_ms: int = field(default_factory=lambda: int(time.time() * 1000))
    completed_at_ms: int | None = None
    results: list[dict[str, Any]] = field(default_factory=list)
    cancelled: threading.Event = field(default_factory=threading.Event, repr=False)

    def public(self) -> dict[str, Any]:
        results = list(self.results)
        return {
            "batchId": self.batch_id, "operation": self.operation, "status": self.status,
            "deviceIds": list(self.device_ids), "createdAtEpochMs": self.created_at_ms,
            "completedAtEpochMs": self.completed_at_ms, "results": results,
            "summary": {
                "requested": len(self.device_ids), "completed": len(results),
                "succeeded": sum(1 for item in results if item.get("ok") is True),
                "failed": sum(1 for item in results if item.get("ok") is False),
            },
        }


class FleetBatchService:
    def __init__(self, backend_factory: Callable[[str], DeviceBackend], *, max_workers: int = 8):
        self.backend_factory = backend_factory
        self.max_workers = max(1, min(int(max_workers), 8))
        self._lock = threading.RLock()
        self._tasks: dict[str, BatchTask] = {}

    def submit(self, device_ids: list[str], operation: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
        unique = tuple(dict.fromkeys(device_ids))
        if not unique or len(unique) != len(device_ids) or len(unique) > 32:
            raise ValueError("deviceIds must be a non-empty unique list of at most 32 explicit targets")
        if operation not in ALLOWED_BATCH_OPERATIONS:
            raise ValueError("Unsupported typed batch operation")
        params = dict(params or {})
        if operation == "open_app" and not _PACKAGE.fullmatch(str(params.get("package") or "")):
            raise ValueError("open_app requires a valid Android package name")
        if operation != "open_app" and params:
            raise ValueError("This batch operation accepts no parameters")
        task = BatchTask(f"batch_{secrets.token_hex(8)}", operation, unique)
        with self._lock:
            self._tasks[task.batch_id] = task
        threading.Thread(target=self._run, args=(task, params), name=f"cyclone-{task.batch_id}", daemon=True).start()
        return task.public()

    def get(self, batch_id: str) -> dict[str, Any]:
        with self._lock:
            task = self._tasks.get(batch_id)
        if task is None:
            raise KeyError(batch_id)
        return task.public()

    def cancel(self, batch_id: str) -> dict[str, Any]:
        with self._lock:
            task = self._tasks.get(batch_id)
        if task is None:
            raise KeyError(batch_id)
        task.cancelled.set()
        if task.status == "RUNNING":
            task.status = "CANCELLING"
        return task.public()

    def _run(self, task: BatchTask, params: dict[str, Any]) -> None:
        with ThreadPoolExecutor(max_workers=min(self.max_workers, len(task.device_ids)), thread_name_prefix="cyclone-batch") as pool:
            futures = {}
            for device_id in task.device_ids:
                if task.cancelled.is_set():
                    break
                futures[pool.submit(self._execute_one, device_id, task.operation, params)] = device_id
            for future in as_completed(futures):
                device_id = futures[future]
                if task.cancelled.is_set() and future.cancel():
                    task.results.append({"deviceId": device_id, "ok": False, "cancelled": True})
                    continue
                try:
                    task.results.append(future.result())
                except Exception as exc:
                    task.results.append({
                        "deviceId": device_id, "ok": False, "transportOk": False,
                        "executionOk": False, "verificationOk": False,
                        "error": {"code": getattr(exc, "code", exc.__class__.__name__), "message": str(exc)[:240]},
                    })
        task.status = "CANCELLED" if task.cancelled.is_set() else "COMPLETED"
        task.completed_at_ms = int(time.time() * 1000)

    def _execute_one(self, device_id: str, operation: str, params: dict[str, Any]) -> dict[str, Any]:
        backend = self.backend_factory(device_id)
        if operation == "recover":
            value = backend.recover()
            return {"deviceId": device_id, "ok": True, "transportOk": True, "executionOk": True, "verificationOk": True, "result": value}
        if operation == "screenshot":
            value = backend.screenshot(profile="thumbnail")
            available = not isinstance(value.get("screenshot"), dict) or value["screenshot"].get("available") is True
            return {
                "deviceId": device_id,
                "ok": available,
                "transportOk": available,
                "executionOk": available,
                "verificationOk": available,
                "result": value,
            }
        capability_id = {"home": "phone.home", "back": "phone.back", "open_app": "phone.open_app"}[operation]
        action_params = {"package": params["package"]} if operation == "open_app" else {}
        value = backend.act(capability_id, action_params, goal=f"Fleet batch: {operation}")
        ok = bool(value.get("success"))
        return {
            "deviceId": device_id, "ok": ok,
            "transportOk": bool(value.get("transport_ok", value.get("transportOk", ok))),
            "executionOk": bool(value.get("execution_ok", value.get("executionOk", ok))),
            "verificationOk": bool(value.get("verification_ok", value.get("verificationOk", ok))),
            "result": {key: value.get(key) for key in ("request_id", "verification", "error_class") if key in value},
        }
