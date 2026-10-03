from __future__ import annotations

import hmac
import json
from pathlib import Path
from threading import Lock
from time import time
from typing import Any

from fastapi import HTTPException


SENSITIVE_KEYS = {"token", "auth", "authorization", "password", "api_key", "openrouter_key", "value", "text"}


def verify_bearer(authorization: str | None, expected: str) -> None:
    prefix = "Bearer "
    if not authorization or not authorization.startswith(prefix):
        raise HTTPException(status_code=401, detail="Bearer token required")
    if not hmac.compare_digest(authorization[len(prefix):], expected):
        raise HTTPException(status_code=403, detail="Invalid bearer token")


def redact_params(tool: str | None, params: dict[str, Any] | None) -> dict[str, Any]:
    params = dict(params or {})
    if tool == "phone.type":
        return {"typed_value_redacted": True}
    out: dict[str, Any] = {}
    for key, value in params.items():
        if key.lower() in SENSITIVE_KEYS:
            out[key] = "<redacted>"
        elif isinstance(value, dict):
            out[key] = redact_params(None, value)
        else:
            out[key] = value
    return out


class AuditLog:
    def __init__(self, path: Path):
        self.path = path
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = Lock()

    def write(self, event: dict[str, Any]) -> None:
        record = {"timestamp": time(), **event}
        with self._lock, self.path.open("a", encoding="utf-8") as f:
            f.write(json.dumps(record, separators=(",", ":"), ensure_ascii=False) + "\n")
