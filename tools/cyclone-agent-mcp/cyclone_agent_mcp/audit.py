from __future__ import annotations

import json
import os
import time
from pathlib import Path
from typing import Any


class SafeAuditLog:
    """Optional metadata-only audit. Arguments, goals, typed text and credentials are never written."""

    def __init__(self, path: str | None = None):
        self.path = Path(path or os.getenv("CYCLONE_AGENT_MCP_AUDIT", "")).expanduser() if (path or os.getenv("CYCLONE_AGENT_MCP_AUDIT")) else None

    def record(self, tool: str, *, ok: bool, elapsed_ms: int, error_code: str | None = None) -> None:
        if self.path is None:
            return
        self.path.parent.mkdir(parents=True, exist_ok=True)
        record: dict[str, Any] = {
            "timestamp_ms": int(time.time() * 1000),
            "tool": tool,
            "ok": ok,
            "elapsed_ms": max(0, elapsed_ms),
        }
        if error_code:
            record["error_code"] = error_code[:80]
        with self.path.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(record, separators=(",", ":")) + "\n")
