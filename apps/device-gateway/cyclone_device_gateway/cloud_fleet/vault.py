"""The cloud accounts and which of their phones Cyclone keeps connected, with the API keys.

On Windows the whole file is encrypted for the current Windows user (DPAPI), like this PC's trust keys. Elsewhere
(Linux CI, development) it is kept in memory only, so no plaintext key ever lands on disk.
"""
from __future__ import annotations

import copy
import json
import os
import threading
from pathlib import Path
from typing import Any, Callable

SCHEMA = 1


def _dpapi(data: bytes, *, protect: bool) -> bytes:
    from ..desktop_runtime.trust_v33 import _dpapi_transform

    return _dpapi_transform(data, protect=protect)


class CloudVault:
    def __init__(self, path: Path, *, protect: Callable[[bytes], bytes] | None = None,
                 unprotect: Callable[[bytes], bytes] | None = None, persistent: bool | None = None):
        self.path = path
        self.persistent = (os.name == "nt") if persistent is None else persistent
        self._protect = protect or (lambda data: _dpapi(data, protect=True))
        self._unprotect = unprotect or (lambda data: _dpapi(data, protect=False))
        self._lock = threading.RLock()
        self._state: dict[str, Any] = {"schema": SCHEMA, "accounts": {}}
        self.load_error: str | None = None
        self._load()

    @property
    def security_mode(self) -> str:
        return "WINDOWS_DPAPI_CURRENT_USER" if self.persistent else "MEMORY_ONLY"

    def _load(self) -> None:
        if not self.persistent or not self.path.is_file():
            return
        try:
            value = json.loads(self._unprotect(self.path.read_bytes()).decode("utf-8"))
            if isinstance(value, dict) and value.get("schema") == SCHEMA and isinstance(value.get("accounts"), dict):
                self._state = value
        except Exception as exc:  # a file from another Windows user or a damaged one: start empty, say so
            self.load_error = exc.__class__.__name__

    def _save(self) -> None:
        if not self.persistent:
            return
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".tmp")
        tmp.write_bytes(self._protect(json.dumps(self._state, separators=(",", ":")).encode("utf-8")))
        os.replace(tmp, self.path)

    def accounts(self) -> dict[str, dict[str, Any]]:
        with self._lock:
            return copy.deepcopy(self._state["accounts"])

    def account(self, account_id: str) -> dict[str, Any] | None:
        with self._lock:
            value = self._state["accounts"].get(account_id)
            return copy.deepcopy(value) if value else None

    def put(self, account: dict[str, Any]) -> None:
        with self._lock:
            self._state["accounts"][account["id"]] = copy.deepcopy(account)
            self._save()

    def update(self, account_id: str, change: Callable[[dict[str, Any]], None]) -> dict[str, Any] | None:
        with self._lock:
            account = self._state["accounts"].get(account_id)
            if account is None:
                return None
            change(account)
            self._save()
            return copy.deepcopy(account)

    def remove(self, account_id: str) -> bool:
        with self._lock:
            removed = self._state["accounts"].pop(account_id, None) is not None
            if removed:
                self._save()
            return removed
