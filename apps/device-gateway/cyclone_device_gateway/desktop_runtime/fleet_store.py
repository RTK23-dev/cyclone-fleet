"""SQLite mission store for the Fleet Orchestrator.

Replaces the single JSON file with a proper indexed store:
  - atomic writes (WAL journal, INSERT OR REPLACE)
  - index on created_at for fast recent/paginated queries
  - retention policy: records older than RETENTION_DAYS are removed, and the
    oldest are removed beyond MAX_MISSIONS (default: 5,000 missions, 90 days);
    a mission still open in the Command Center is never removed
  - age cleanup uses the caller's clock, never deletes a mission the caller
    still marks open, and skips cleanup if that clock jumps backwards
  - migrates from the legacy missions.json on first open
  - thread-safe (one SQLite connection per store, protected by RLock)

Schema is self-contained; it never touches the Command Center's DB file.
"""
from __future__ import annotations

import json
import sqlite3
import threading
import time
from pathlib import Path
from typing import Any, Callable

# --------------------------------------------------------------------------- defaults
MAX_MISSIONS: int = 5_000        # hard upper bound regardless of age
RETENTION_DAYS: int = 90         # records older than this are candidates for removal


_DDL = """\
PRAGMA journal_mode=WAL;
PRAGMA synchronous=NORMAL;
CREATE TABLE IF NOT EXISTS fleet_mission (
    mission_id  TEXT PRIMARY KEY,
    command     TEXT NOT NULL DEFAULT '',
    created_at  INTEGER NOT NULL,
    notes_json  TEXT NOT NULL DEFAULT '[]',
    rows_json   TEXT NOT NULL DEFAULT '[]'
);
CREATE INDEX IF NOT EXISTS idx_fleet_mission_created
    ON fleet_mission (created_at DESC, mission_id DESC);
CREATE TABLE IF NOT EXISTS fleet_control (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS fleet_task (
    task_id    TEXT PRIMARY KEY,
    mission_id TEXT NOT NULL
);
"""


def _row_to_dict(row: sqlite3.Row) -> dict[str, Any]:
    return {
        "missionId":   row["mission_id"],
        "command":     row["command"],
        "createdAt":   row["created_at"],
        "notes":       json.loads(row["notes_json"]),
        "assignments": json.loads(row["rows_json"]),
    }


def _encode_cursor(mission: dict[str, Any]) -> str:
    return f"{int(mission.get('createdAt') or 0)}|{mission['missionId']}"


def _decode_cursor(cursor: str) -> tuple[int, str] | None:
    created, sep, mission_id = cursor.partition("|")
    if not sep or not mission_id or not created.lstrip("-").isdigit():
        return None
    return int(created), mission_id


class FleetStore:
    """Thread-safe SQLite store for fleet missions.

    Usage::

        store = FleetStore(Path("fleet/fleet.db"), legacy_json=Path("fleet/missions.json"), clock=clock)
        store.save(mission_dict)
        missions, next_cursor = store.page(limit=50)
        mission  = store.get("flt_abc123")
        store.trim(protect_ids=open_ids)
    """

    def __init__(
        self,
        db_path: Path,
        *,
        legacy_json: Path | None = None,
        max_missions: int = MAX_MISSIONS,
        retention_days: int = RETENTION_DAYS,
        clock: Callable[[], int] | None = None,
    ) -> None:
        self._path = db_path
        self._max = max(1, int(max_missions))
        self._retention_ms = max(0, int(retention_days)) * 86_400_000
        self._clock = clock or (lambda: int(time.time() * 1000))
        self._lock = threading.RLock()
        db_path.parent.mkdir(parents=True, exist_ok=True)
        self._db = sqlite3.connect(str(db_path), check_same_thread=False)
        self._db.row_factory = sqlite3.Row
        self._db.executescript(_DDL)
        self._db.commit()
        if legacy_json is not None:
            self._migrate(legacy_json)

    # ------------------------------------------------------------------ public API

    def save(self, mission: dict[str, Any]) -> None:
        """Upsert one mission (atomic — uses INSERT OR REPLACE)."""
        with self._lock:
            self._db.execute(
                "INSERT OR REPLACE INTO fleet_mission"
                " (mission_id, command, created_at, notes_json, rows_json) VALUES (?,?,?,?,?)",
                (
                    str(mission["missionId"]),
                    str(mission.get("command") or "")[:2000],
                    int(mission.get("createdAt") or 0),
                    json.dumps(list(mission.get("notes") or [])),
                    json.dumps(list(mission.get("assignments") or [])),
                ),
            )
            self._db.commit()

    def control(self, key: str, default: str = "") -> str:
        with self._lock:
            row = self._db.execute("SELECT value FROM fleet_control WHERE key = ?", (key,)).fetchone()
        return str(row["value"]) if row else default

    def set_control(self, key: str, value: str) -> None:
        with self._lock:
            self._db.execute("INSERT OR REPLACE INTO fleet_control(key, value) VALUES (?, ?)", (key, value))
            self._db.commit()

    def bind_task(self, task_id: str, mission_id: str) -> None:
        if not task_id or not mission_id:
            return
        with self._lock:
            self._db.execute(
                "INSERT OR REPLACE INTO fleet_task(task_id, mission_id) VALUES (?, ?)",
                (task_id, mission_id),
            )
            self._db.commit()

    def mission_of_task(self, task_id: str) -> str:
        with self._lock:
            row = self._db.execute("SELECT mission_id FROM fleet_task WHERE task_id = ?", (task_id,)).fetchone()
        return str(row["mission_id"]) if row else ""

    def get(self, mission_id: str) -> dict[str, Any] | None:
        """Return one mission dict or None."""
        with self._lock:
            row = self._db.execute(
                "SELECT * FROM fleet_mission WHERE mission_id = ?", (mission_id,)
            ).fetchone()
        return _row_to_dict(row) if row else None

    def recent(self, limit: int = 50, *, cursor: str | None = None) -> list[dict[str, Any]]:
        """Most-recent missions first, up to *limit*. No silent cap — the caller chooses the page size."""
        items, _next = self.page(limit, cursor=cursor)
        return items

    def page(self, limit: int = 50, *, cursor: str | None = None) -> tuple[list[dict[str, Any]], str | None]:
        """One page, newest first. `cursor` is the previous page's nextCursor. Returns (items, nextCursor)."""
        limit = max(1, int(limit))
        where = ""
        args: list[Any] = []
        decoded = _decode_cursor(cursor) if cursor else None
        if decoded:
            created, mission_id = decoded
            where = "WHERE created_at < ? OR (created_at = ? AND mission_id < ?)"
            args = [created, created, mission_id]
        with self._lock:
            rows = self._db.execute(
                f"SELECT * FROM fleet_mission {where} ORDER BY created_at DESC, mission_id DESC LIMIT ?",
                (*args, limit + 1),
            ).fetchall()
        more = len(rows) > limit
        rows = rows[:limit]
        items = [_row_to_dict(r) for r in rows]
        next_cursor = _encode_cursor(items[-1]) if more and items else None
        return items, next_cursor

    def all_ids(self) -> list[str]:
        """All mission IDs (for in-memory overlay rebuilds)."""
        with self._lock:
            rows = self._db.execute("SELECT mission_id FROM fleet_mission").fetchall()
        return [r["mission_id"] for r in rows]

    def delete(self, mission_id: str) -> None:
        with self._lock:
            self._delete_ids([mission_id])

    def count(self) -> int:
        with self._lock:
            return self._db.execute("SELECT COUNT(*) FROM fleet_mission").fetchone()[0]

    def newest_created_at(self) -> int | None:
        with self._lock:
            row = self._db.execute("SELECT MAX(created_at) FROM fleet_mission").fetchone()
        value = row[0] if row else None
        return int(value) if value is not None else None

    def trim(self, *, protect_ids: set[str] | None = None) -> int:
        """Remove old or excess missions. Returns number of rows deleted.

        Age is measured with the injected clock, not the wall clock. A mission in
        `protect_ids` (still open in the Command Center) is never deleted. If the
        clock is earlier than the newest stored created_at, cleanup is skipped -
        a backwards jump must not wipe the table. Deletes run in chunks so a large
        protect set never hits SQLite's bound-variable limit, and a deleted mission
        takes its task bindings with it.
        """
        now_ms = int(self._clock())
        protect = set(protect_ids or ())
        with self._lock:
            newest = self._db.execute("SELECT MAX(created_at) FROM fleet_mission").fetchone()[0]
            if newest is not None and now_ms < int(newest):
                return 0
            doomed: list[str] = []
            if self._retention_ms > 0:
                cutoff = now_ms - self._retention_ms
                doomed += [r[0] for r in self._db.execute("SELECT mission_id FROM fleet_mission WHERE created_at < ?", (cutoff,))
                           if r[0] not in protect]
            gone = set(doomed)
            total = self._db.execute("SELECT COUNT(*) FROM fleet_mission").fetchone()[0] - len(gone)
            if total > self._max:
                excess = total - self._max
                for (mission_id,) in self._db.execute("SELECT mission_id FROM fleet_mission ORDER BY created_at ASC, mission_id ASC"):
                    if excess <= 0:
                        break
                    if mission_id in protect or mission_id in gone:
                        continue
                    doomed.append(mission_id)
                    gone.add(mission_id)
                    excess -= 1
            self._delete_ids(doomed)
        return len(doomed)

    def missions_for_tasks(self, task_ids: list[str]) -> list[str]:
        found: list[str] = []
        with self._lock:
            for i in range(0, len(task_ids), 500):
                chunk = task_ids[i:i + 500]
                if not chunk:
                    continue
                marks = ",".join("?" for _ in chunk)
                found += [r[0] for r in self._db.execute(
                    f"SELECT DISTINCT mission_id FROM fleet_task WHERE task_id IN ({marks})", chunk,
                )]
        return found

    def delete_empty(self) -> int:
        """Remove missions with no assignments. A real mission always has one; an empty one is a ghost left by a crash."""
        with self._lock:
            ids = [r[0] for r in self._db.execute("SELECT mission_id FROM fleet_mission WHERE rows_json = '[]'")]
            self._delete_ids(ids)
        return len(ids)

    def _delete_ids(self, ids: list[str]) -> None:
        for i in range(0, len(ids), 500):
            chunk = ids[i:i + 500]
            marks = ",".join("?" for _ in chunk)
            self._db.execute(f"DELETE FROM fleet_mission WHERE mission_id IN ({marks})", chunk)
            self._db.execute(f"DELETE FROM fleet_task WHERE mission_id IN ({marks})", chunk)
        if ids:
            self._db.commit()

    def close(self) -> None:
        with self._lock:
            self._db.close()

    # ------------------------------------------------------------------ migration

    def _migrate(self, legacy_json: Path) -> None:
        """Import missions from the old JSON file on first open (idempotent)."""
        if not legacy_json.is_file():
            return
        try:
            payload = json.loads(legacy_json.read_text(encoding="utf-8"))
        except (OSError, ValueError, TypeError):
            return
        missions = payload.get("missions") if isinstance(payload, dict) else None
        if not isinstance(missions, list):
            return
        count = 0
        with self._lock:
            for m in missions:
                if (
                    isinstance(m, dict)
                    and isinstance(m.get("missionId"), str)
                    and isinstance(m.get("assignments"), list)
                ):
                    existing = self._db.execute(
                        "SELECT 1 FROM fleet_mission WHERE mission_id = ?", (m["missionId"],)
                    ).fetchone()
                    if not existing:
                        self._db.execute(
                            "INSERT INTO fleet_mission"
                            " (mission_id, command, created_at, notes_json, rows_json) VALUES (?,?,?,?,?)",
                            (
                                m["missionId"],
                                str(m.get("command") or "")[:2000],
                                int(m.get("createdAt") or 0),
                                json.dumps(list(m.get("notes") or [])),
                                json.dumps(list(m.get("assignments") or [])),
                            ),
                        )
                        count += 1
            if count:
                self._db.commit()
        # Rename the old file so we don't re-import on subsequent starts.
        if count > 0:
            try:
                legacy_json.rename(legacy_json.with_suffix(".json.migrated"))
            except OSError:
                pass

