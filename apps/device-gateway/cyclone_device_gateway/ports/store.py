"""Port Hub state: plugins with their pinned manifests, and a metadata-only activity log, in one SQLite file. Plugin
keys live apart from the database, sealed with DPAPI for this Windows user (a 0600 file elsewhere, for development).

The activity log never holds a body, a code, a token, a key or an artifact URL: only who, which port, when, the HTTP
status and how long it took.
"""
from __future__ import annotations

import json
import os
import sqlite3
import threading
import time
from pathlib import Path
from typing import Any, Callable

from ..command.connections import GrantStore

SCHEMA = """
CREATE TABLE IF NOT EXISTS plugin (
  name TEXT PRIMARY KEY, endpoint TEXT NOT NULL UNIQUE, manifest TEXT NOT NULL, pin_hash TEXT NOT NULL,
  pending TEXT, consent TEXT NOT NULL, paused INTEGER NOT NULL DEFAULT 0, kid TEXT NOT NULL,
  checks TEXT, checked_at INTEGER, health TEXT NOT NULL DEFAULT 'unknown', health_detail TEXT NOT NULL DEFAULT '',
  failures INTEGER NOT NULL DEFAULT 0, seen_at INTEGER, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS activity (
  id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, plugin TEXT NOT NULL, kind TEXT NOT NULL,
  port TEXT, run_id TEXT, status INTEGER, ok INTEGER NOT NULL, latency_ms INTEGER, detail TEXT NOT NULL DEFAULT '');
CREATE INDEX IF NOT EXISTS activity_plugin ON activity(plugin, at);
CREATE TABLE IF NOT EXISTS wait (
  await_id TEXT PRIMARY KEY, run_id TEXT NOT NULL, port TEXT NOT NULL, way TEXT NOT NULL, plugin TEXT NOT NULL,
  request TEXT NOT NULL, timeout_at INTEGER NOT NULL, state TEXT NOT NULL, result TEXT, delivery_id TEXT,
  created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS wait_run ON wait(run_id);
CREATE TABLE IF NOT EXISTS binding (
  scope TEXT NOT NULL, port TEXT NOT NULL, plugins TEXT NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(scope, port));
CREATE TABLE IF NOT EXISTS starter_settings (
  name TEXT PRIMARY KEY, settings TEXT NOT NULL, updated_at INTEGER NOT NULL);
"""
ACTIVITY_KEEP = 5_000
JSON_FIELDS = ("manifest", "pending", "consent", "checks")


def now_ms() -> int:
    return int(time.time() * 1000)


class PortStore:
    def __init__(self, root: Path, *, protect: Callable[[bytes], bytes] | None = None,
                 unprotect: Callable[[bytes], bytes] | None = None) -> None:
        root.mkdir(parents=True, exist_ok=True)
        self._db = sqlite3.connect(str(root / "ports.db"), check_same_thread=False)
        self._db.row_factory = sqlite3.Row
        self._db.executescript(SCHEMA)
        self._lock = threading.RLock()
        if protect is None and os.name != "nt":
            # Development machines: a file only this user can read. Windows uses DPAPI (GrantStore's default).
            protect = unprotect = lambda data: data  # noqa: E731
        self.keys = GrantStore(root / "plugin-keys.dpapi", protect=protect, unprotect=unprotect)
        # Port tokens of open waits (run 3), sealed like the keys, so a restart can re-send the same wait.
        self.tokens = GrantStore(root / "wait-tokens.dpapi", protect=protect, unprotect=unprotect)

    @property
    def keys_persistent(self) -> bool:
        return self.keys.persistent

    # ---- plugins -----------------------------------------------------------------------------------------------------

    def _row(self, row: sqlite3.Row | None) -> dict[str, Any] | None:
        if row is None:
            return None
        out = dict(row)
        for key in JSON_FIELDS:
            out[key] = json.loads(out[key]) if out.get(key) else None
        return out

    def plugins(self) -> list[dict[str, Any]]:
        with self._lock:
            rows = self._db.execute("SELECT * FROM plugin ORDER BY created_at").fetchall()
        return [self._row(r) for r in rows]  # type: ignore[misc]

    def plugin(self, name: str) -> dict[str, Any] | None:
        with self._lock:
            return self._row(self._db.execute("SELECT * FROM plugin WHERE name = ?", (name,)).fetchone())

    def by_endpoint(self, endpoint: str) -> dict[str, Any] | None:
        with self._lock:
            return self._row(self._db.execute("SELECT * FROM plugin WHERE endpoint = ?", (endpoint,)).fetchone())

    def insert(self, record: dict[str, Any]) -> None:
        stamp = now_ms()
        with self._lock, self._db:
            self._db.execute(
                "INSERT INTO plugin (name, endpoint, manifest, pin_hash, consent, kid, created_at, updated_at) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                (record["name"], record["endpoint"], json.dumps(record["manifest"]), record["pin_hash"],
                 json.dumps(record["consent"]), record["kid"], stamp, stamp))

    def update(self, name: str, **fields: Any) -> None:
        if not fields:
            return
        values = [json.dumps(v) if k in JSON_FIELDS and v is not None else v for k, v in fields.items()]
        sets = ", ".join(f"{k} = ?" for k in fields)
        with self._lock, self._db:
            self._db.execute(f"UPDATE plugin SET {sets}, updated_at = ? WHERE name = ?", (*values, now_ms(), name))

    def delete(self, name: str) -> None:
        with self._lock, self._db:
            self._db.execute("DELETE FROM plugin WHERE name = ?", (name,))
        self._drop_key(name)

    # ---- keys --------------------------------------------------------------------------------------------------------

    def put_key(self, name: str, kid: str, secret: str) -> None:
        self.keys.put(name, {"kid": kid, "secret": secret})

    def key(self, name: str) -> str | None:
        """``"k<N>.<secret>"`` for signing, or None when it was lost (memory-only store after a restart)."""
        grant = self.keys.get(name) or {}
        return f"{grant['kid']}.{grant['secret']}" if grant.get("kid") and grant.get("secret") else None

    def _drop_key(self, name: str) -> None:
        self.keys.drop(name)

    def starter_settings(self, name: str) -> dict[str, Any] | None:
        with self._lock:
            row = self._db.execute("SELECT settings FROM starter_settings WHERE name=?", (name,)).fetchone()
        return json.loads(row[0]) if row else None

    def save_starter_settings(self, name: str, settings: dict[str, Any]) -> None:
        with self._lock, self._db:
            self._db.execute("INSERT OR REPLACE INTO starter_settings VALUES(?,?,?)",
                             (name, json.dumps(settings), now_ms()))

    # ---- bindings (plan 48 run 2) -------------------------------------------------------------------------------------

    def bindings(self, scope: str | None = None) -> dict[tuple[str, str], list[str]]:
        """{(scope, port): [plugin names]}. A missing pair means automatic."""
        query = "SELECT scope, port, plugins FROM binding" + (" WHERE scope = ?" if scope else "")
        with self._lock:
            rows = self._db.execute(query, (scope,) if scope else ()).fetchall()
        return {(r["scope"], r["port"]): json.loads(r["plugins"]) for r in rows}

    def set_binding(self, scope: str, port: str, plugins: list[str] | None) -> None:
        with self._lock, self._db:
            if plugins is None:
                self._db.execute("DELETE FROM binding WHERE scope = ? AND port = ?", (scope, port))
            else:
                self._db.execute(
                    "INSERT INTO binding (scope, port, plugins, updated_at) VALUES (?, ?, ?, ?) "
                    "ON CONFLICT(scope, port) DO UPDATE SET plugins = excluded.plugins, updated_at = excluded.updated_at",
                    (scope, port, json.dumps(plugins), now_ms()))

    def forget_plugin_bindings(self, name: str) -> None:
        """A removed plugin leaves every choice it was in. A choice it alone made goes back to automatic."""
        for (scope, port), plugins in self.bindings().items():
            if name in plugins:
                rest = [p for p in plugins if p != name]
                self.set_binding(scope, port, rest if rest else None)

    # ---- waits (run 3) -------------------------------------------------------------------------------------------------
    # A wait's request (run, port, match, timeout) and its outcome as metadata only: never a code, value, link or file.

    def put_wait(self, record: dict[str, Any], token: str) -> None:
        stamp = now_ms()
        with self._lock, self._db:
            self._db.execute(
                "INSERT INTO wait (await_id, run_id, port, way, plugin, request, timeout_at, state, created_at, updated_at) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, 'waiting', ?, ?)",
                (record["awaitId"], record["runId"], record["port"], record["way"], record["plugin"],
                 json.dumps(record["request"]), record["timeoutAt"], stamp, stamp))
        self.tokens.put(record["awaitId"], {"token": token})

    def wait(self, await_id: str) -> dict[str, Any] | None:
        with self._lock:
            row = self._db.execute("SELECT * FROM wait WHERE await_id = ?", (await_id,)).fetchone()
        return self._wait_row(row)

    def waits(self, *, state: str | None = None, run_id: str | None = None, limit: int = 200) -> list[dict[str, Any]]:
        clauses, args = [], []
        if state:
            clauses.append("state = ?")
            args.append(state)
        if run_id:
            clauses.append("run_id = ?")
            args.append(run_id)
        query = "SELECT * FROM wait" + (" WHERE " + " AND ".join(clauses) if clauses else "") + " ORDER BY created_at DESC LIMIT ?"
        with self._lock:
            rows = self._db.execute(query, (*args, limit)).fetchall()
        return [self._wait_row(r) for r in rows]  # type: ignore[misc]

    def _wait_row(self, row: sqlite3.Row | None) -> dict[str, Any] | None:
        if row is None:
            return None
        return {"awaitId": row["await_id"], "runId": row["run_id"], "port": row["port"], "way": row["way"],
                "plugin": row["plugin"], "request": json.loads(row["request"]), "timeoutAt": row["timeout_at"],
                "state": row["state"], "result": json.loads(row["result"]) if row["result"] else None,
                "deliveryId": row["delivery_id"], "createdAt": row["created_at"], "updatedAt": row["updated_at"]}

    def finish_wait(self, await_id: str, state: str, result: dict[str, Any] | None = None,
                    delivery_id: str | None = None, *, only_if_waiting: bool = True) -> bool:
        """Moves a wait out of 'waiting'. False when it already left (a race lost to another delivery or timeout)."""
        with self._lock, self._db:
            cursor = self._db.execute(
                "UPDATE wait SET state = ?, result = ?, delivery_id = ?, updated_at = ? WHERE await_id = ?"
                + (" AND state = 'waiting'" if only_if_waiting else ""),
                (state, json.dumps(result) if result is not None else None, delivery_id, now_ms(), await_id))
            changed = cursor.rowcount > 0
        if changed:
            self.tokens.drop(await_id)
        return changed

    def wait_token(self, await_id: str) -> str | None:
        return (self.tokens.get(await_id) or {}).get("token")

    # ---- activity ----------------------------------------------------------------------------------------------------

    def log(self, plugin: str, kind: str, *, ok: bool, port: str | None = None, run_id: str | None = None,
            status: int | None = None, latency_ms: int | None = None, detail: str = "") -> None:
        with self._lock, self._db:
            self._db.execute(
                "INSERT INTO activity (at, plugin, kind, port, run_id, status, ok, latency_ms, detail) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (now_ms(), plugin, kind, port, run_id, status, 1 if ok else 0, latency_ms, detail[:200]))
            self._db.execute("DELETE FROM activity WHERE id <= (SELECT MAX(id) FROM activity) - ?", (ACTIVITY_KEEP,))

    def activity(self, limit: int = 50, plugin: str | None = None, *, port: str | None = None,
                 run_id: str | None = None, ok: bool | None = None, kinds: tuple[str, ...] | None = None) -> list[dict[str, Any]]:
        clauses, args = [], []
        for column, value in (("plugin", plugin), ("port", port), ("run_id", run_id)):
            if value:
                clauses.append(f"{column} = ?")
                args.append(value)
        if ok is not None:
            clauses.append("ok = ?")
            args.append(1 if ok else 0)
        if kinds:
            clauses.append(f"kind IN ({','.join('?' for _ in kinds)})")
            args.extend(kinds)
        query = "SELECT * FROM activity" + (" WHERE " + " AND ".join(clauses) if clauses else "") + " ORDER BY id DESC LIMIT ?"
        with self._lock:
            rows = self._db.execute(query, (*args, limit)).fetchall()
        return [{"id": r["id"], "at": r["at"], "plugin": r["plugin"], "kind": r["kind"], "port": r["port"],
                 "runId": r["run_id"], "status": r["status"], "ok": bool(r["ok"]), "latencyMs": r["latency_ms"],
                 "detail": r["detail"]} for r in rows]

    def runs(self, limit: int = 30) -> list[dict[str, Any]]:
        """Runs that used a port, newest first, from the activity log."""
        with self._lock:
            rows = self._db.execute(
                "SELECT run_id, MIN(at) AS first, MAX(at) AS last, COUNT(*) AS n, "
                "SUM(CASE WHEN ok = 0 THEN 1 ELSE 0 END) AS bad FROM activity WHERE run_id IS NOT NULL "
                "AND kind IN ('emit', 'await', 'deliver', 'timeout', 'cancel', 'run') GROUP BY run_id ORDER BY last DESC LIMIT ?",
                (limit,)).fetchall()
        return [{"runId": r["run_id"], "firstAt": r["first"], "lastAt": r["last"], "messages": r["n"],
                 "failures": int(r["bad"] or 0)} for r in rows]

    def counts_since(self, since_ms: int) -> dict[str, int]:
        with self._lock:
            row = self._db.execute(
                "SELECT COUNT(*) AS n, SUM(CASE WHEN ok = 0 THEN 1 ELSE 0 END) AS bad FROM activity "
                "WHERE at >= ? AND kind IN ('emit', 'test', 'deliver')", (since_ms,)).fetchone()
        return {"messages": int(row["n"] or 0), "failures": int(row["bad"] or 0)}

    def close(self) -> None:
        with self._lock:
            self._db.close()
