from __future__ import annotations

import json
import os
from pathlib import Path
import re
import threading
from typing import Any


_GROUP_ID = re.compile(r"^[a-z0-9][a-z0-9_-]{0,47}$")
_NICKNAME_MAX = 40


class FleetWorkspaceStore:
    """Persistent user-owned groups and explicit wall selection."""

    SCHEMA_VERSION = 1

    def __init__(self, path: Path):
        self.path = path
        self._lock = threading.RLock()
        self._groups: dict[str, dict[str, Any]] = {}
        self._selected: list[str] = []
        self._nicknames: dict[str, str] = {}
        self._load()

    def public(self) -> dict[str, Any]:
        with self._lock:
            return {
                "schemaVersion": self.SCHEMA_VERSION,
                "groups": [dict(self._groups[key]) for key in sorted(self._groups)],
                "selectedDeviceIds": list(self._selected),
                "nicknames": dict(self._nicknames),
            }

    def put_group(self, group_id: str, name: str, device_ids: list[str]) -> dict[str, Any]:
        if not _GROUP_ID.fullmatch(group_id):
            raise ValueError("groupId must contain only lowercase letters, numbers, dash, or underscore")
        clean_name = name.strip()
        if not clean_name or len(clean_name) > 80:
            raise ValueError("group name must be 1..80 characters")
        unique = self._unique_ids(device_ids)
        group = {"groupId": group_id, "name": clean_name, "deviceIds": unique}
        with self._lock:
            self._groups[group_id] = group
            self._persist()
        return dict(group)

    def delete_group(self, group_id: str) -> None:
        with self._lock:
            self._groups.pop(group_id, None)
            self._persist()

    def set_selection(self, device_ids: list[str]) -> list[str]:
        unique = self._unique_ids(device_ids)
        with self._lock:
            self._selected = unique
            self._persist()
        return list(unique)

    def nickname_map(self) -> dict[str, str]:
        with self._lock:
            return dict(self._nicknames)

    def set_nickname(self, device_id: str, nickname: str) -> dict[str, Any]:
        device_id = device_id.strip()
        name = " ".join(nickname.split())
        if not device_id or len(device_id) > 128:
            raise ValueError("deviceId is required")
        if len(name) > _NICKNAME_MAX:
            raise ValueError(f"a nickname is at most {_NICKNAME_MAX} characters")
        with self._lock:
            taken = {value.casefold(): key for key, value in self._nicknames.items() if key != device_id}
            if name and name.casefold() in taken:
                raise ValueError("another phone already has that nickname")
            if name:
                self._nicknames[device_id] = name
            else:
                self._nicknames.pop(device_id, None)
            self._persist()
        return {"deviceId": device_id, "nickname": name or None}

    def resolve_nickname(self, name: str) -> str | None:
        key = " ".join(name.split()).casefold()
        with self._lock:
            for device_id, nickname in self._nicknames.items():
                if nickname.casefold() == key:
                    return device_id
        return None

    @staticmethod
    def search(devices: list[dict[str, Any]], query: str = "", *, source: str | None = None, group: dict[str, Any] | None = None) -> list[dict[str, Any]]:
        term = query.strip().casefold()
        group_ids = set(group.get("deviceIds", [])) if group else None
        result = []
        for device in devices:
            if source and str(device.get("source") or "").upper() != source.upper():
                continue
            if group_ids is not None and device.get("deviceId") not in group_ids:
                continue
            haystack = " ".join(str(device.get(key) or "") for key in ("name", "model", "source", "provider", "state")).casefold()
            if term and term not in haystack:
                continue
            result.append(device)
        return result

    def group(self, group_id: str) -> dict[str, Any] | None:
        with self._lock:
            item = self._groups.get(group_id)
            return dict(item) if item else None

    @staticmethod
    def _unique_ids(values: list[str]) -> list[str]:
        if not isinstance(values, list) or len(values) > 32:
            raise ValueError("deviceIds must contain at most 32 explicit devices")
        unique: list[str] = []
        for value in values:
            if not isinstance(value, str) or not value.startswith("dev_") or len(value) > 64:
                raise ValueError("deviceIds contains an invalid Cyclone device ID")
            if value not in unique:
                unique.append(value)
        return unique

    def _load(self) -> None:
        if not self.path.is_file():
            return
        try:
            payload = json.loads(self.path.read_text(encoding="utf-8"))
            if payload.get("schemaVersion") != self.SCHEMA_VERSION:
                return
            for group in payload.get("groups", []):
                if _GROUP_ID.fullmatch(str(group.get("groupId") or "")):
                    self._groups[group["groupId"]] = {
                        "groupId": group["groupId"], "name": str(group.get("name") or group["groupId"])[:80],
                        "deviceIds": self._unique_ids(list(group.get("deviceIds") or [])),
                    }
            self._selected = self._unique_ids(list(payload.get("selectedDeviceIds") or []))
            raw_names = payload.get("nicknames")
            if isinstance(raw_names, dict):
                self._nicknames = {
                    str(device_id): str(nickname)
                    for device_id, nickname in raw_names.items()
                    if isinstance(device_id, str) and isinstance(nickname, str) and nickname.strip()
                }
        except (OSError, ValueError, TypeError, json.JSONDecodeError):
            self._groups = {}
            self._selected = []
            self._nicknames = {}

    def _persist(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.path.with_suffix(self.path.suffix + ".tmp")
        temporary.write_text(json.dumps(self.public(), indent=2, sort_keys=True), encoding="utf-8")
        os.replace(temporary, self.path)
