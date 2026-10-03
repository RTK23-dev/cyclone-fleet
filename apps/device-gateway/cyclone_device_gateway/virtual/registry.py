from __future__ import annotations

import json
import os
from pathlib import Path
import threading
from typing import Iterable

from .models import VirtualDeviceConfig, VirtualInstance, VirtualInstanceState


class VirtualDeviceRegistry:
    SCHEMA_VERSION = 1

    def __init__(self, path: Path):
        self.path = path
        self._lock = threading.RLock()
        self._instances: dict[str, VirtualInstance] = {}
        self._load()

    def list(self, provider: str | None = None) -> list[VirtualInstance]:
        with self._lock:
            values = list(self._instances.values())
        if provider:
            values = [item for item in values if item.provider == provider]
        return sorted(values, key=lambda item: item.instance_id)

    def get(self, instance_id: str) -> VirtualInstance:
        with self._lock:
            item = self._instances.get(instance_id)
        if item is None:
            raise KeyError(instance_id)
        return item

    def save(self, instance: VirtualInstance) -> None:
        with self._lock:
            self._instances[instance.instance_id] = instance
            self._persist()

    def remove(self, instance_id: str) -> None:
        with self._lock:
            self._instances.pop(instance_id, None)
            self._persist()

    def metadata_for_serial(self, serial: str) -> dict[str, str] | None:
        normalized = serial.removeprefix("emulator-")
        with self._lock:
            for item in self._instances.values():
                endpoint = item.adb_endpoint or ""
                if serial == endpoint or (item.console_port is not None and normalized == str(item.console_port)):
                    return {"source": "VIRTUAL", "provider": item.provider, "instanceId": item.instance_id}
        return None

    def allocated_console_ports(self) -> set[int]:
        with self._lock:
            return {item.console_port for item in self._instances.values() if item.console_port is not None}

    def _load(self) -> None:
        if not self.path.is_file():
            return
        try:
            payload = json.loads(self.path.read_text(encoding="utf-8"))
            if payload.get("schemaVersion") != self.SCHEMA_VERSION:
                return
            for raw in payload.get("instances", []):
                config = VirtualDeviceConfig(**raw["config"])
                item = VirtualInstance(
                    instance_id=raw["instanceId"], provider=raw["provider"],
                    cyclone_device_id=raw["cycloneDeviceId"], name=raw["name"], config=config,
                    state=VirtualInstanceState(raw["state"]), data_path=raw.get("dataPath", ""),
                    adb_endpoint=raw.get("adbEndpoint"), console_port=raw.get("consolePort"),
                    created_at_ms=int(raw.get("createdAtEpochMs") or 0),
                    last_started_at_ms=raw.get("lastStartedAtEpochMs"), last_error=raw.get("lastError"),
                    pid=None,
                )
                # A process never survives registry reconstruction as a trusted RUNNING claim.
                if item.state in {VirtualInstanceState.RUNNING, VirtualInstanceState.STARTING}:
                    item.state = VirtualInstanceState.STOPPED
                self._instances[item.instance_id] = item
        except (OSError, ValueError, TypeError, KeyError, json.JSONDecodeError):
            self._instances = {}

    def _persist(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        payload = {"schemaVersion": self.SCHEMA_VERSION, "instances": [item.public() for item in self.list()]}
        temporary = self.path.with_suffix(self.path.suffix + ".tmp")
        temporary.write_text(json.dumps(payload, indent=2, sort_keys=True), encoding="utf-8")
        os.replace(temporary, self.path)
