from __future__ import annotations

import json
from pathlib import Path
import sqlite3
import time
import uuid
from typing import Any


class StateStore:
    def __init__(self, path: Path):
        path.parent.mkdir(parents=True, exist_ok=True)
        self.path = path
        self.db = sqlite3.connect(path, check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.db.executescript("""
        PRAGMA journal_mode=WAL;
        CREATE TABLE IF NOT EXISTS device_sessions (
          id TEXT PRIMARY KEY, ts REAL NOT NULL, serial TEXT, status_json TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS observations (
          id TEXT PRIMARY KEY, ts REAL NOT NULL, source TEXT NOT NULL, page_key TEXT,
          package TEXT, activity TEXT, semantic_json TEXT NOT NULL, raw_json TEXT NOT NULL,
          uia_json TEXT, screenshot_json TEXT
        );
        CREATE TABLE IF NOT EXISTS actions (
          id TEXT PRIMARY KEY, ts REAL NOT NULL, request_id TEXT, tool TEXT NOT NULL,
          params_json TEXT NOT NULL, result_json TEXT NOT NULL, duration_ms INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS transitions (
          id TEXT PRIMARY KEY, ts REAL NOT NULL, before_observation_id TEXT, action_id TEXT,
          after_observation_id TEXT, before_page TEXT, after_page TEXT, success INTEGER,
          latency_ms INTEGER, verification TEXT, backend TEXT, error_class TEXT
        );
        """)
        self.db.commit()

    @staticmethod
    def _j(value: Any) -> str:
        return json.dumps(value, separators=(",", ":"), ensure_ascii=False)

    def add_device_session(self, status: dict) -> str:
        sid = str(uuid.uuid4())
        self.db.execute("INSERT INTO device_sessions VALUES (?,?,?,?)", (sid, time.time(), status.get("serial"), self._j(status)))
        self.db.commit(); return sid

    def add_observation(self, semantic: dict, *, uia: dict | None = None, screenshot: dict | None = None, raw: dict | None = None) -> str:
        oid = str(uuid.uuid4())
        page = semantic.get("page_key") or semantic.get("pageKey")
        package = semantic.get("package")
        activity = semantic.get("activity")
        self.db.execute("INSERT INTO observations VALUES (?,?,?,?,?,?,?,?,?,?)", (
            oid, time.time(), "CYCLONE_ACCESSIBILITY", page, package, activity,
            self._j(semantic), self._j(raw or semantic), self._j(uia) if uia else None, self._j(screenshot) if screenshot else None))
        self.db.commit(); return oid

    def current_observation(self) -> dict | None:
        row = self.db.execute("SELECT * FROM observations ORDER BY ts DESC LIMIT 1").fetchone()
        return self._observation(row) if row else None

    def get_observation(self, oid: str) -> dict | None:
        row = self.db.execute("SELECT * FROM observations WHERE id=?", (oid,)).fetchone()
        return self._observation(row) if row else None

    def _observation(self, row: sqlite3.Row) -> dict:
        return {"id": row["id"], "timestamp": row["ts"], "source": row["source"], "page_key": row["page_key"],
                "package": row["package"], "activity": row["activity"], "semantic": json.loads(row["semantic_json"]),
                "raw": json.loads(row["raw_json"]), "uiautomator": json.loads(row["uia_json"]) if row["uia_json"] else None,
                "screenshot": json.loads(row["screenshot_json"]) if row["screenshot_json"] else None}

    def page_history(self, limit: int = 50) -> list[dict]:
        rows = self.db.execute("SELECT id,ts,page_key,package,activity FROM observations ORDER BY ts DESC LIMIT ?", (limit,)).fetchall()
        return [dict(r) for r in rows]

    def add_action(self, request_id: str, tool: str, params: dict, result: dict, duration_ms: int) -> str:
        aid = str(uuid.uuid4())
        self.db.execute("INSERT INTO actions VALUES (?,?,?,?,?,?,?)", (aid, time.time(), request_id, tool, self._j(params), self._j(result), duration_ms)); self.db.commit(); return aid

    def add_transition(self, *, before_id: str, action_id: str, after_id: str, before_page: str | None, after_page: str | None, success: bool, latency_ms: int, verification: str, backend: str, error_class: str | None = None) -> str:
        tid = str(uuid.uuid4())
        self.db.execute("INSERT INTO transitions VALUES (?,?,?,?,?,?,?,?,?,?,?,?)", (tid, time.time(), before_id, action_id, after_id, before_page, after_page, int(success), latency_ms, verification, backend, error_class)); self.db.commit(); return tid

    def transition_history(self, limit: int = 50) -> list[dict]:
        rows = self.db.execute("SELECT * FROM transitions ORDER BY ts DESC LIMIT ?", (limit,)).fetchall()
        return [dict(r) for r in rows]

    def recent_actions(self, limit: int = 50) -> list[dict]:
        rows = self.db.execute("SELECT * FROM actions ORDER BY ts DESC LIMIT ?", (limit,)).fetchall()
        return [dict(r) for r in rows]
