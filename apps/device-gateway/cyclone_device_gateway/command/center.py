"""The Command Center's local core (plan 33, C0): accounts, tasks, routines, results and one Approvals inbox, in one
SQLite file on the owner's PC, with a small durable job loop that assigns tasks to phones.

Rules this module keeps:
- **No secret values.** Accounts are metadata only (service, handle, owner basis, 2FA method). Every text the owner
  types is checked for secret-shaped content and refused; the vault is a later release (C1).
- **Phones do the work.** A task becomes an ordinary Mind mission on one phone through the typed ``cc.*`` contract;
  the phone's GATE, Owner Moments and Secrets Card apply as always. "Done" is what the phone reports.
- **One phone per account at a time** (the account lock). A phone takes up to three Command Center tasks at once
  (plan 26 §6: one in front, up to two behind it on background screens); the phone decides whether it can take the
  next one, and a phone with fewer running tasks is preferred.
- **Approvals stay human.** The inbox shows a phone's open Owner Moment; only the owner's answer from Glass reaches
  the phone, for that exact request id. Nothing here approves by itself, and nothing times out into an approval.
- **No replay.** A routine that missed its time (the PC was off) runs next time; a task is never started twice
  (idempotency keys), and a task is not retried after its mission started.
- **Audit.** Every change is appended to a hash chain.
"""
from __future__ import annotations

import hashlib
import json
import re
import secrets
import sqlite3
import threading
import time
from datetime import datetime
from pathlib import Path
from typing import Any, Callable, Protocol

from ..desktop_runtime.models import DesktopRuntimeError
from ..desktop_runtime.v5_contract import INLINE_SECRET, _secret_name
from . import schedule as schedules
from . import steps as chain

SERVICE = re.compile(r"^(?:[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+|[a-z0-9-]+(?:\.[a-z0-9-]+)+)$")
HANDLE = re.compile(r"^[^\s].{0,79}$")
DEVICE_ID = re.compile(r"^[A-Za-z0-9._:-]{1,120}$")
ID = re.compile(r"^[a-z]{1,4}_[A-Za-z0-9_-]{6,40}$")
OWNER_BASIS = ("mine", "company", "client")
TWOFA = ("none", "totp", "passkey", "sms", "email", "app")
TASK_STATES = ("scheduled", "making", "waiting_device", "running", "needs_you", "succeeded", "failed", "cancelled")
OPEN_TASK_STATES = ("scheduled", "making", "waiting_device", "running", "needs_you")
FINISHED = {"completed": "succeeded", "gave_up": "failed", "failed": "failed", "cancelled": "cancelled",
            "paused": "failed", "interrupted": "failed"}
#: Phone refusals that mean "not now", so the task waits for a phone instead of failing.
WAIT_CODES = {"ASK_BUSY", "HUMAN_HAS_CONTROL", "OVERLAY_UNAVAILABLE", "DEVICE_DISCONNECTED", "PAIRING_REQUIRED",
              "DEVICE_NOT_FOUND", "AUTH_REJECTED"}
WAIT_LIMIT_MS = 6 * 60 * 60_000
# Plan 26 §6: tasks one phone may run at the same time (one in front, up to two behind). The phone refuses what it
# cannot take (ASK_BUSY), and the task waits for the next tick as before.
PHONE_TASKS = 3
UNREACHABLE_LIMIT_MS = 30 * 60_000
MISSED_GRACE_MS = 10 * 60_000
MAX_GOAL = 1_800
#: Plan 33 (C3): a routine may seal its next runs' passwords ahead (pre-authorised leases), at most this many.
MAX_PREAUTH = 7
PREAUTH_WINDOW_MS = 30 * 60_000
MEDIA_CHUNK = 256 * 1024


class CommandError(ValueError):
    """A refused request; the message is for the owner."""


class CommandContract(Protocol):
    def cc_start(self, device_id: str, goal: str) -> dict[str, Any]: ...
    def cc_status(self, device_id: str, mission_id: str) -> dict[str, Any]: ...
    def cc_answer(self, device_id: str, mission_id: str, action: str, *, request_id: str | None = None,
                  text: str | None = None, values: dict[str, str] | None = None) -> dict[str, Any]: ...
    def cc_media(self, device_id: str, task_id: str, *, name: str, mime: str, size: int, sha256: str, offset: int,
                 data: bytes) -> dict[str, Any]: ...


def _now_ms() -> int:
    return int(time.time() * 1000)


def _id(prefix: str) -> str:
    return f"{prefix}_{secrets.token_urlsafe(12)}"


def _clean_text(value: Any, label: str, limit: int, *, required: bool = True) -> str:
    if value is None and not required:
        return ""
    if not isinstance(value, str):
        raise CommandError(f"{label} must be text.")
    text = value.strip()
    if required and not text:
        raise CommandError(f"{label} is required.")
    if len(text) > limit:
        raise CommandError(f"{label} is at most {limit} characters.")
    if INLINE_SECRET.search(text):
        raise CommandError(f"{label} looks like it holds a secret. Passwords and codes never go here; the phone asks for them.")
    return text


def _only(body: dict[str, Any], allowed: set[str]) -> None:
    extra = set(body) - allowed
    if extra:
        raise CommandError(f"Unknown field: {sorted(extra)[0]}.")
    for key in body:
        if _secret_name(key):
            raise CommandError("Secret-bearing fields are refused.")


SCHEMA = """
CREATE TABLE IF NOT EXISTS account (
  id TEXT PRIMARY KEY, service TEXT NOT NULL, handle TEXT NOT NULL, owner_basis TEXT NOT NULL, twofa TEXT NOT NULL,
  allowed_devices TEXT NOT NULL, status TEXT NOT NULL, notes TEXT NOT NULL, created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL, UNIQUE(service, handle));
CREATE TABLE IF NOT EXISTS routine (
  id TEXT PRIMARY KEY, title TEXT NOT NULL, goal TEXT NOT NULL, devices TEXT NOT NULL, account_id TEXT,
  schedule TEXT NOT NULL, paused INTEGER NOT NULL, next_run_at INTEGER, last_run_at INTEGER, created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS task (
  id TEXT PRIMARY KEY, title TEXT NOT NULL, goal TEXT NOT NULL, device_id TEXT, account_id TEXT, recipe TEXT,
  routine_id TEXT, due_at INTEGER, status TEXT NOT NULL, cause TEXT NOT NULL, idempotency_key TEXT UNIQUE,
  next_try_at INTEGER NOT NULL, waiting_since INTEGER, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS run (
  id TEXT PRIMARY KEY, task_id TEXT NOT NULL, device_id TEXT NOT NULL, mission_id TEXT NOT NULL, status TEXT NOT NULL,
  cause TEXT NOT NULL, summary TEXT NOT NULL, turns INTEGER NOT NULL, working_ms INTEGER NOT NULL, cost_usd REAL NOT NULL,
  started_at INTEGER NOT NULL, ended_at INTEGER, last_seen_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS approval (
  id TEXT PRIMARY KEY, run_id TEXT NOT NULL, task_id TEXT NOT NULL, device_id TEXT NOT NULL, mission_id TEXT NOT NULL,
  request_id TEXT NOT NULL, kind TEXT NOT NULL, text TEXT NOT NULL, gate TEXT, send TEXT, choices TEXT NOT NULL,
  fields TEXT NOT NULL, approvable_here INTEGER NOT NULL, state TEXT NOT NULL, answer TEXT, created_at INTEGER NOT NULL,
  answered_at INTEGER, UNIQUE(run_id, request_id));
CREATE TABLE IF NOT EXISTS audit (
  seq INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, actor TEXT NOT NULL, action TEXT NOT NULL,
  object TEXT NOT NULL, detail TEXT NOT NULL, prev_hash TEXT NOT NULL, hash TEXT NOT NULL);
CREATE INDEX IF NOT EXISTS task_status ON task(status);
CREATE INDEX IF NOT EXISTS run_task ON run(task_id);
CREATE INDEX IF NOT EXISTS approval_state ON approval(state);
"""


class CommandCenter:
    def __init__(self, path: Path, contract: CommandContract, devices: Callable[[], list[dict[str, Any]]], *,
                 clock: Callable[[], int] = _now_ms, local_now: Callable[[], datetime] | None = None,
                 tick_seconds: float = 5.0, connections: dict[str, Any] | None = None,
                 ai: dict[str, Any] | None = None) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        self._db = sqlite3.connect(str(path), check_same_thread=False, isolation_level=None)
        self._db.row_factory = sqlite3.Row
        self._db.execute("PRAGMA journal_mode=WAL")
        self._db.executescript(SCHEMA)
        self._lock = threading.RLock()
        self._contract = contract
        self._devices = devices
        self._clock = clock
        self._local_now = local_now or (lambda: datetime.fromtimestamp(self._clock() / 1000).astimezone())
        self._tick_seconds = tick_seconds
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        # Plan 33 (C1): the zero-knowledge vault shares this file, lock and audit chain; it stores ciphertext only.
        from .vault import VaultStore
        self.vault = VaultStore(self)
        # Plan 33 (C2): sealed delivery. Tasks may name a vault item; its secret travels sealed to one trusted phone.
        if "vault_item_id" not in {r["name"] for r in self._db.execute("PRAGMA table_info(task)")}:
            self._db.execute("ALTER TABLE task ADD COLUMN vault_item_id TEXT")
        from .delivery import DeliveryStore
        self.delivery = DeliveryStore(self)
        # Plan 33 (C3): connections (MCP servers such as Higgsfield), their calls and the files they make.
        columns = {r["name"] for r in self._db.execute("PRAGMA table_info(task)")}
        for column in ("make", "artifact_id", "media"):
            if column not in columns:
                self._db.execute(f"ALTER TABLE task ADD COLUMN {column} TEXT")
        if "step_at" not in columns:
            # Plan 34 M3: the next step of a task's chain. A C3 task that already made its file is past its one step.
            self._db.execute("ALTER TABLE task ADD COLUMN step_at INTEGER NOT NULL DEFAULT 0")
            self._db.execute("UPDATE task SET step_at = 1 WHERE make IS NOT NULL AND artifact_id IS NOT NULL")
        columns = {r["name"] for r in self._db.execute("PRAGMA table_info(routine)")}
        for column, kind in (("make", "TEXT"), ("vault_item_id", "TEXT"), ("preauth", "INTEGER NOT NULL DEFAULT 0")):
            if column not in columns:
                self._db.execute(f"ALTER TABLE routine ADD COLUMN {column} {kind}")
        self._db.execute("CREATE TABLE IF NOT EXISTS routine_slot (routine_id TEXT NOT NULL, due_at INTEGER NOT NULL, device_id TEXT NOT NULL,"
                         " task_id TEXT NOT NULL UNIQUE, created_at INTEGER NOT NULL, PRIMARY KEY(routine_id, due_at, device_id))")
        from .connections import ConnectionStore
        self.connections = ConnectionStore(self, path.parent, **(connections or {}))
        # Plan 33 (C5): pages, the workspace's Notion-like documents with live references.
        from .pages import PageStore
        self.pages = PageStore(self)
        # Plan 43 (T1): tables, the owner's own databases with typed properties and saved views.
        from .tables import TableStore
        self.tables = TableStore(self)
        # Plan 43 (T3): action buttons in tables.
        from .buttons import ButtonStore
        self.buttons = ButtonStore(self)
        # Plan 43 (T5 + T6): accounts by phone and app, and the sign-ups the phone mapped.
        from .signup import SignupStore
        self.signup = SignupStore(self)
        # Plan 33 §7 (C4, moved forward): the AI project manager, on the owner's OpenRouter key.
        from .ai import AiStore
        self.ai = AiStore(self, **(ai or {}))
        self._task_listener = None
        self._wake = threading.Event()
        self._tick_done = threading.Condition()
        self._ticks = 0
        self._last_tick_ms = 0

    def set_task_listener(self, listener) -> None:
        self._task_listener = listener

    # ---------------------------------------------------------------- lifecycle

    def start(self) -> None:
        if self._thread and self._thread.is_alive():
            return
        self._stop.clear()
        self.connections.mrz.start()
        self._thread = threading.Thread(target=self._loop, name="cyclone-command-center", daemon=True)
        self._thread.start()

    def request_tick(self) -> None:
        self._wake.set()

    def flush_tick(self, timeout: float = 5.0) -> None:
        if not (self._thread and self._thread.is_alive()):
            self.tick()
            return
        with self._tick_done:
            target = self._ticks + 1
            self._wake.set()
            self._tick_done.wait_for(lambda: self._ticks >= target, timeout)

    def stop(self) -> None:
        self._stop.set()
        self._wake.set()
        if self._thread:
            self._thread.join(timeout=5)
        self.connections.close()
        with self._lock:
            self._db.close()

    def _loop(self) -> None:
        while not self._stop.is_set():
            self._wake.wait(self._tick_seconds)
            self._wake.clear()
            if self._stop.is_set():
                break
            try:
                self.tick()
            except Exception:  # noqa: BLE001 - the loop must survive one bad tick; the next one retries
                pass
            self._last_tick_ms = self._clock()
            with self._tick_done:
                self._ticks += 1
                self._tick_done.notify_all()

    # ---------------------------------------------------------------- audit

    def _audit(self, actor: str, action: str, obj: str, detail: dict[str, Any] | None = None) -> None:
        at = self._clock()
        body = json.dumps(detail or {}, sort_keys=True, separators=(",", ":"))
        row = self._db.execute("SELECT hash FROM audit ORDER BY seq DESC LIMIT 1").fetchone()
        prev = row["hash"] if row else "0" * 64
        digest = hashlib.sha256(f"{prev}\n{at}\n{actor}\n{action}\n{obj}\n{body}".encode()).hexdigest()
        self._db.execute("INSERT INTO audit(at, actor, action, object, detail, prev_hash, hash) VALUES (?,?,?,?,?,?,?)",
                         (at, actor, action, obj, body, prev, digest))

    def audit(self, limit: int = 200) -> dict[str, Any]:
        with self._lock:
            rows = self._db.execute("SELECT * FROM audit ORDER BY seq DESC LIMIT ?", (max(1, min(limit, 1000)),)).fetchall()
            return {"entries": [dict(r) for r in rows], "intact": self.verify_audit()}

    def verify_audit(self) -> bool:
        prev = "0" * 64
        for r in self._db.execute("SELECT * FROM audit ORDER BY seq"):
            digest = hashlib.sha256(f"{prev}\n{r['at']}\n{r['actor']}\n{r['action']}\n{r['object']}\n{r['detail']}".encode()).hexdigest()
            if r["prev_hash"] != prev or r["hash"] != digest:
                return False
            prev = r["hash"]
        return True

    # ---------------------------------------------------------------- accounts

    def _account_fields(self, body: dict[str, Any], *, partial: bool) -> dict[str, Any]:
        _only(body, {"service", "handle", "ownerBasis", "twofa", "allowedDevices", "status", "notes"})
        out: dict[str, Any] = {}
        if not partial or "service" in body:
            service = body.get("service")
            if not isinstance(service, str) or not SERVICE.match(service.strip()) or len(service) > 160:
                raise CommandError("service is an app package (com.instagram.android) or a web domain (example.com).")
            out["service"] = service.strip()
        if not partial or "handle" in body:
            handle = _clean_text(body.get("handle"), "handle", 80)
            if not HANDLE.match(handle):
                raise CommandError("handle is the account's name or address, up to 80 characters.")
            out["handle"] = handle
        if not partial or "ownerBasis" in body:
            basis = body.get("ownerBasis")
            if basis not in OWNER_BASIS:
                raise CommandError("ownerBasis is mine, company or client: whose account this is and why you may use it.")
            out["owner_basis"] = basis
        if not partial or "twofa" in body:
            twofa = body.get("twofa", "none")
            if twofa not in TWOFA:
                raise CommandError(f"twofa is one of {', '.join(TWOFA)}.")
            out["twofa"] = twofa
        if not partial or "allowedDevices" in body:
            devices = body.get("allowedDevices", [])
            if not isinstance(devices, list) or len(devices) > 200 or not all(isinstance(d, str) and DEVICE_ID.match(d) for d in devices):
                raise CommandError("allowedDevices is a list of phone ids (empty = any phone).")
            out["allowed_devices"] = json.dumps(sorted(set(devices)))
        if not partial or "status" in body:
            status = body.get("status", "active")
            if status not in ("active", "paused"):
                raise CommandError("status is active or paused.")
            out["status"] = status
        if not partial or "notes" in body:
            out["notes"] = _clean_text(body.get("notes", ""), "notes", 500, required=False)
        return out

    def list_accounts(self) -> list[dict[str, Any]]:
        with self._lock:
            rows = self._db.execute("SELECT * FROM account ORDER BY service, handle").fetchall()
            return [self._account_public(r) for r in rows]

    def create_account(self, body: dict[str, Any]) -> dict[str, Any]:
        fields = self._account_fields(body, partial=False)
        with self._lock:
            now = self._clock()
            account_id = _id("acc")
            try:
                self._db.execute(
                    "INSERT INTO account(id, service, handle, owner_basis, twofa, allowed_devices, status, notes, created_at, updated_at)"
                    " VALUES (?,?,?,?,?,?,?,?,?,?)",
                    (account_id, fields["service"], fields["handle"], fields["owner_basis"], fields["twofa"],
                     fields["allowed_devices"], fields["status"], fields["notes"], now, now))
            except sqlite3.IntegrityError as exc:
                raise CommandError("That account is already in the Command Center.") from exc
            self._audit("owner", "account.create", account_id, {"service": fields["service"], "basis": fields["owner_basis"]})
            return self.get_account(account_id)

    def update_account(self, account_id: str, body: dict[str, Any]) -> dict[str, Any]:
        fields = self._account_fields(body, partial=True)
        with self._lock:
            self.get_account(account_id)
            if fields:
                sets = ", ".join(f"{k} = ?" for k in fields)
                try:
                    self._db.execute(f"UPDATE account SET {sets}, updated_at = ? WHERE id = ?",
                                     (*fields.values(), self._clock(), account_id))
                except sqlite3.IntegrityError as exc:
                    raise CommandError("That account is already in the Command Center.") from exc
                self._audit("owner", "account.update", account_id, {"fields": sorted(fields)})
            return self.get_account(account_id)

    def delete_account(self, account_id: str) -> dict[str, Any]:
        with self._lock:
            self.get_account(account_id)
            busy = self._db.execute(
                f"SELECT COUNT(*) FROM task WHERE account_id = ? AND status IN ({','.join('?' * len(OPEN_TASK_STATES))})",
                (account_id, *OPEN_TASK_STATES)).fetchone()[0]
            routines = self._db.execute("SELECT COUNT(*) FROM routine WHERE account_id = ?", (account_id,)).fetchone()[0]
            if busy or routines:
                raise CommandError("Open tasks or routines still use this account. Cancel or change them first.")
            if self._db.execute("SELECT COUNT(*) FROM vault_item WHERE account_id = ?", (account_id,)).fetchone()[0]:
                raise CommandError("Vault items belong to this account. Move or delete them first.")
            self._db.execute("DELETE FROM account WHERE id = ?", (account_id,))
            self._audit("owner", "account.delete", account_id)
            return {"id": account_id, "deleted": True}

    def get_account(self, account_id: str) -> dict[str, Any]:
        row = self._db.execute("SELECT * FROM account WHERE id = ?", (account_id,)).fetchone()
        if row is None:
            raise CommandError("No such account.")
        return self._account_public(row)

    def _account_public(self, r: sqlite3.Row) -> dict[str, Any]:
        last = self._db.execute(
            "SELECT t.status, r.ended_at FROM task t LEFT JOIN run r ON r.task_id = t.id WHERE t.account_id = ?"
            " AND t.status IN ('succeeded','failed') ORDER BY t.updated_at DESC LIMIT 1", (r["id"],)).fetchone()
        return {
            "id": r["id"], "service": r["service"], "handle": r["handle"], "ownerBasis": r["owner_basis"],
            "twofa": r["twofa"], "allowedDevices": json.loads(r["allowed_devices"]), "status": r["status"],
            "notes": r["notes"], "createdAt": r["created_at"], "updatedAt": r["updated_at"],
            "lastOutcome": last["status"] if last else None,
            "locked": self._account_locked(r["id"]),
            "vaultItems": self._db.execute("SELECT COUNT(*) FROM vault_item WHERE account_id = ?", (r["id"],)).fetchone()[0],
        }

    def _account_locked(self, account_id: str) -> bool:
        return self._db.execute(
            "SELECT 1 FROM task WHERE account_id = ? AND status IN ('running','needs_you') LIMIT 1", (account_id,)).fetchone() is not None

    # ---------------------------------------------------------------- tasks

    def _target(self, body: dict[str, Any]) -> tuple[str | None, str | None]:
        device = body.get("deviceId")
        if device is not None and (not isinstance(device, str) or not DEVICE_ID.match(device)):
            raise CommandError("deviceId is a phone id, or leave it out for any ready phone.")
        account = body.get("accountId")
        if account is not None:
            if not isinstance(account, str) or not ID.match(account):
                raise CommandError("accountId is malformed.")
            acc = self.get_account(account)
            if device and acc["allowedDevices"] and device not in acc["allowedDevices"]:
                raise CommandError("That phone is not allowed to use this account.")
        return device, account

    def create_task(self, body: dict[str, Any], *, actor: str = "owner") -> dict[str, Any]:
        _only(body, {"title", "goal", "deviceId", "accountId", "recipe", "dueAt", "requestId", "vaultItemId", "make", "steps", "then"})
        make = self._plan_spec(body)
        if make and not body.get("goal") and json.loads(make)["then"] == "keep":
            plan = json.loads(make)
            body = {**body, "goal": f"Make with {plan['steps'][0]['tool']}" if len(plan["steps"]) == 1 else f"Run {len(plan['steps'])} connection steps"}
        title = _clean_text(body.get("title") or str(body.get("goal", ""))[:80], "title", 80)
        goal = _clean_text(body.get("goal"), "goal", MAX_GOAL)
        self._check_goal(goal, make)
        recipe = _clean_text(body.get("recipe"), "recipe", 64, required=False) or None
        due = body.get("dueAt")
        if due is not None and (type(due) is not int or due < 0):
            raise CommandError("dueAt is a time in epoch milliseconds, or leave it out to start now.")
        key = body.get("requestId")
        if key is not None and (not isinstance(key, str) or not re.match(r"^[A-Za-z0-9_-]{8,80}$", key)):
            raise CommandError("requestId is 8..80 letters, digits, dash or underscore.")
        with self._lock:
            device, account = self._target(body)
            if key:
                existing = self._db.execute("SELECT id FROM task WHERE idempotency_key = ?", (f"req:{key}",)).fetchone()
                if existing:
                    return self.get_task(existing["id"])
            item = self._vault_item_for(body.get("vaultItemId"), device, account)
            created = self._insert_task(title, goal, device, account, recipe, None, due, f"req:{key}" if key else None, actor,
                                        vault_item=item, make=make)
            return created

    def _plan_spec(self, body: dict[str, Any]) -> str | None:
        """Plan 33 (C3) and plan 34 (M3): up to five connection steps before the phone (or instead of it). ``make`` is
        the one-step C3 form; ``steps`` + ``then`` the chain. Stored as {steps, then}."""
        make, raw_steps, then = body.get("make"), body.get("steps"), body.get("then")
        if make is None and raw_steps is None:
            if then is not None:
                raise CommandError("then needs steps.")
            return None
        if make is not None and raw_steps is not None:
            raise CommandError("Send make or steps, not both.")
        if make is not None:
            if not isinstance(make, dict) or not set(make) <= {"connectionId", "tool", "arguments", "pollTool", "then"}:
                raise CommandError("make is {connectionId, tool, arguments, pollTool?, then}.")
            if then is not None:
                raise CommandError("make carries its own then.")
            raw_steps, then = [{k: v for k, v in make.items() if k != "then"}], make.get("then", "post")
        if not isinstance(raw_steps, list) or not 1 <= len(raw_steps) <= chain.MAX_STEPS:
            raise CommandError(f"steps is a list of 1..{chain.MAX_STEPS} connection calls.")
        if then is None:
            then = "post"
        if then not in chain.THEN:
            raise CommandError("then is post (a phone posts the file), keep (only keep what came back) or phone (a phone uses the results).")
        steps = [self._step_spec(raw, i + 1, len(raw_steps) == 1 and make is not None) for i, raw in enumerate(raw_steps)]
        return json.dumps({"steps": steps, "then": then}, sort_keys=True)

    def _step_spec(self, raw: Any, number: int, legacy: bool) -> dict[str, Any]:
        label = "make" if legacy else f"Step {number}"
        if not isinstance(raw, dict) or not set(raw) <= {"connectionId", "tool", "arguments", "pollTool"}:
            raise CommandError(f"{label} is {{connectionId, tool, arguments, pollTool?}}.")
        connection = raw.get("connectionId")
        if not isinstance(connection, str) or not re.match(r"^con_[A-Za-z0-9_-]{6,40}$", connection):
            raise CommandError(f"{label}: connectionId is malformed.")
        row = self.connections._row(connection)
        allowed = set(json.loads(row["allowed"]))
        tool, poll = raw.get("tool"), raw.get("pollTool")
        for name in (tool, poll):
            if name is not None and (not isinstance(name, str) or name not in allowed):
                raise CommandError(f"{row['name']}: allow the tool {name} in Connections first.")
        if tool is None:
            raise CommandError(f"{label}: tool is required.")
        arguments = raw.get("arguments", {})
        if not isinstance(arguments, dict) or len(json.dumps(arguments)) > 8_000:
            raise CommandError(f"{label}: arguments are an object of at most 8 KB.")
        for key, value in arguments.items():
            if _secret_name(str(key)) or (isinstance(value, str) and INLINE_SECRET.search(value)):
                raise CommandError(f"{label}: the arguments look like they hold a secret.")
            if not isinstance(value, (str, int, float, bool)) or (isinstance(value, str) and len(value) > 4_000):
                raise CommandError(f"{label}: arguments are plain values (text up to 4000 characters, numbers, true/false).")
        try:
            chain.check_arguments(arguments, number)
        except chain.StepError as exc:
            raise CommandError(str(exc)) from exc
        return {"connectionId": connection, "tool": tool, "arguments": arguments, "pollTool": poll}

    @staticmethod
    def _check_goal(goal: str, make: str | None) -> None:
        """``{stepN…}`` in a goal needs that step, and a phone that gets the results."""
        try:
            refs = chain.references(goal)
            if refs:
                plan = json.loads(make) if make else None
                if not plan or plan["then"] == "keep":
                    raise CommandError("{step…} in the goal needs steps whose results go to a phone (then: phone or post).")
                chain.check_goal(goal, len(plan["steps"]))
        except chain.StepError as exc:
            raise CommandError(str(exc)) from exc

    def _vault_item_for(self, item_id: Any, device: str | None, account: str | None) -> str | None:
        """A task may use one vault item's secret (C2): its own account's login or authenticator, on one trusted phone."""
        if item_id is None:
            return None
        if not isinstance(item_id, str) or not re.match(r"^vi_[A-Za-z0-9_-]{16,40}$", item_id):
            raise CommandError("vaultItemId is malformed.")
        if not account or not device:
            raise CommandError("A task that uses a vault password needs its account and one phone.")
        row = self._db.execute("SELECT account_id, kind FROM vault_item WHERE id = ?", (item_id,)).fetchone()
        if row is None or row["account_id"] != account or row["kind"] not in ("login", "totp"):
            raise CommandError("That vault item is not a login or authenticator of this account.")
        if self.delivery.trusted_key(device) is None:
            raise CommandError("Trust that phone's key first (Command Center -> Vault -> Phones).")
        return item_id

    def _insert_task(self, title: str, goal: str, device: str | None, account: str | None, recipe: str | None,
                     routine: str | None, due: int | None, key: str | None, actor: str, *, task_id: str | None = None,
                     vault_item: str | None = None, make: str | None = None) -> dict[str, Any]:
        now = self._clock()
        task_id = task_id or _id("tsk")
        self._db.execute(
            "INSERT INTO task(id, title, goal, device_id, account_id, recipe, routine_id, due_at, status, cause, idempotency_key,"
            " next_try_at, waiting_since, created_at, updated_at, vault_item_id, make) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (task_id, title, goal, device, account, recipe, routine, due, "scheduled", "", key, due or now, None, now, now, vault_item, make))
        self._audit(actor, "task.create", task_id, {"device": device, "account": account, "routine": routine,
                                                    "vault": bool(vault_item), "make": [st["tool"] for st in chain.plan_of(make)["steps"]] if make else None})
        return self.get_task(task_id)

    def cancel_task(self, task_id: str) -> dict[str, Any]:
        with self._lock:
            task = self.get_task(task_id)
            if task["status"] not in OPEN_TASK_STATES:
                raise CommandError("That task has already finished.")
            run = self._open_run(task_id)
            if run is not None:
                try:
                    self._contract.cc_answer(run["device_id"], run["mission_id"], "stop")
                except DesktopRuntimeError:
                    pass  # the phone may be offline; the task is cancelled here and the phone's own Stop still works
                self._finish_run(run, "cancelled", "Cancelled from the Command Center.")
            self._db.execute("UPDATE lease SET state = 'revoked', updated_at = ? WHERE task_id = ? AND state = 'ready'", (self._clock(), task_id))
            self._db.execute("UPDATE tool_call SET state = 'declined', summary = 'The task was cancelled.', finished_at = ? WHERE task_id = ? AND state = 'waiting'",
                             (self._clock(), task_id))
            self._withdraw_gateway_approvals(task_id)
            self._set_task(task_id, "cancelled", "Cancelled from the Command Center.")
            self._audit("owner", "task.cancel", task_id)
            return self.get_task(task_id)

    def list_tasks(self, *, status: str | None = None, limit: int = 200) -> list[dict[str, Any]]:
        with self._lock:
            if status == "open":
                rows = self._db.execute(
                    f"SELECT * FROM task WHERE status IN ({','.join('?' * len(OPEN_TASK_STATES))}) ORDER BY created_at DESC, rowid DESC LIMIT ?",
                    (*OPEN_TASK_STATES, limit)).fetchall()
            elif status == "done":
                rows = self._db.execute(
                    "SELECT * FROM task WHERE status IN ('succeeded','failed','cancelled') ORDER BY updated_at DESC LIMIT ?",
                    (limit,)).fetchall()
            else:
                rows = self._db.execute("SELECT * FROM task ORDER BY created_at DESC LIMIT ?", (limit,)).fetchall()
            return [self._task_public(r) for r in rows]

    def get_task(self, task_id: str) -> dict[str, Any]:
        row = self._db.execute("SELECT * FROM task WHERE id = ?", (task_id,)).fetchone()
        if row is None:
            raise CommandError("No such task.")
        return self._task_public(row)

    def _task_public(self, r: sqlite3.Row) -> dict[str, Any]:
        run = self._db.execute("SELECT * FROM run WHERE task_id = ? ORDER BY started_at DESC LIMIT 1", (r["id"],)).fetchone()
        return {
            "id": r["id"], "title": r["title"], "goal": r["goal"], "deviceId": r["device_id"], "accountId": r["account_id"],
            "recipe": r["recipe"], "routineId": r["routine_id"], "dueAt": r["due_at"], "status": r["status"],
            "cause": r["cause"], "createdAt": r["created_at"], "updatedAt": r["updated_at"],
            "run": self._run_public(run) if run else None,
            "vaultItemId": r["vault_item_id"],
            "make": chain.public(chain.plan_of(r["make"])),
            "stepAt": r["step_at"],
            "artifact": self._artifact_of(r["artifact_id"]),
            "call": self.connections.call_of_task(r["id"]) if r["make"] else None,
            "calls": self.connections.calls_of_task(r["id"]) if r["make"] else [],
            "media": json.loads(r["media"]) if r["media"] else None,
            "leases": [{"id": l["id"], "slot": l["slot"], "state": l["state"], "expiresAt": l["expires_at"]}
                       for l in self._db.execute("SELECT id, slot, state, expires_at FROM lease WHERE task_id = ? ORDER BY created_at DESC LIMIT 4", (r["id"],))],
        }

    def _artifact_of(self, artifact_id: str | None) -> dict[str, Any] | None:
        if not artifact_id:
            return None
        row = self._db.execute("SELECT * FROM artifact WHERE id = ?", (artifact_id,)).fetchone()
        return self.connections._artifact_public(row) if row else None

    def _withdraw_gateway_approvals(self, task_id: str) -> None:
        """Approvals the gateway itself raised for a task (a spend OK, a login) end with it."""
        self._db.execute("UPDATE approval SET state = 'withdrawn' WHERE task_id = ? AND run_id = '' AND state = 'open'", (task_id,))

    @staticmethod
    def _run_public(r: sqlite3.Row) -> dict[str, Any]:
        return {
            "id": r["id"], "deviceId": r["device_id"], "missionId": r["mission_id"], "status": r["status"],
            "cause": r["cause"], "summary": r["summary"], "turns": r["turns"], "workingMs": r["working_ms"],
            "costUsd": r["cost_usd"], "startedAt": r["started_at"], "endedAt": r["ended_at"],
        }

    def results(self, limit: int = 200) -> list[dict[str, Any]]:
        with self._lock:
            rows = self._db.execute(
                "SELECT r.*, t.title, t.account_id, t.routine_id FROM run r JOIN task t ON t.id = r.task_id"
                " ORDER BY r.started_at DESC LIMIT ?", (max(1, min(limit, 1000)),)).fetchall()
            out = []
            for r in rows:
                item = self._run_public(r)
                item.update({"taskId": r["task_id"], "title": r["title"], "accountId": r["account_id"], "routineId": r["routine_id"]})
                out.append(item)
            return out

    def _set_task(self, task_id: str, status: str, cause: str = "", **extra: Any) -> None:
        sets = ", ".join(f"{k} = ?" for k in extra)
        self._db.execute(f"UPDATE task SET status = ?, cause = ?, updated_at = ?{', ' + sets if sets else ''} WHERE id = ?",
                         (status, cause[:300], self._clock(), *extra.values(), task_id))
        listener = getattr(self, "_task_listener", None)
        if listener is not None:
            device_id = str(extra.get("device_id") or "")
            if not device_id:
                row = self._db.execute("SELECT device_id FROM task WHERE id = ?", (task_id,)).fetchone()
                device_id = str(row["device_id"] or "") if row else ""
            try:
                listener({"taskId": task_id, "status": status, "deviceId": device_id})
            except Exception:
                return

    def _open_run(self, task_id: str) -> sqlite3.Row | None:
        return self._db.execute("SELECT * FROM run WHERE task_id = ? AND ended_at IS NULL", (task_id,)).fetchone()

    # ---------------------------------------------------------------- routines

    def _routine_fields(self, body: dict[str, Any], *, partial: bool) -> dict[str, Any]:
        _only(body, {"title", "goal", "deviceIds", "accountId", "schedule", "paused", "make", "steps", "then", "vaultItemId", "preauth"})
        out: dict[str, Any] = {}
        if not partial or "title" in body:
            out["title"] = _clean_text(body.get("title"), "title", 80)
        if not partial or "goal" in body:
            out["goal"] = _clean_text(body.get("goal"), "goal", MAX_GOAL)
        if not partial or "deviceIds" in body:
            devices = body.get("deviceIds", [])
            if not isinstance(devices, list) or len(devices) > 50 or not all(isinstance(d, str) and DEVICE_ID.match(d) for d in devices):
                raise CommandError("deviceIds is a list of phones (one task each), or empty for any ready phone.")
            out["devices"] = json.dumps(sorted(set(devices)))
        if not partial or "accountId" in body:
            account = body.get("accountId")
            if account is not None:
                acc = self.get_account(account) if isinstance(account, str) and ID.match(account) else None
                if acc is None:
                    raise CommandError("accountId is malformed.")
                devices = json.loads(out.get("devices", "[]"))
                if acc["allowedDevices"] and any(d not in acc["allowedDevices"] for d in devices):
                    raise CommandError("One of those phones is not allowed to use this account.")
                if len(devices) > 1:
                    raise CommandError("One account runs on one phone at a time; pick one phone, or none.")
            out["account_id"] = account
        if not partial or "schedule" in body:
            try:
                out["schedule"] = json.dumps(schedules.parse(body.get("schedule")))
            except schedules.ScheduleError as exc:
                raise CommandError(str(exc)) from exc
        if "paused" in body:
            if not isinstance(body["paused"], bool):
                raise CommandError("paused is true or false.")
            out["paused"] = 1 if body["paused"] else 0
        if not partial or {"make", "steps", "then"} & set(body):
            out["make"] = self._plan_spec(body)
        if not partial or "vaultItemId" in body:
            item = body.get("vaultItemId")
            if item is not None:
                devices = json.loads(out.get("devices", "[]"))
                self._vault_item_for(item, devices[0] if len(devices) == 1 else None, out.get("account_id") if "account_id" in out else body.get("accountId"))
            out["vault_item_id"] = item
        if not partial or "preauth" in body:
            preauth = body.get("preauth", 0)
            if type(preauth) is not int or not 0 <= preauth <= MAX_PREAUTH:
                raise CommandError(f"preauth is how many runs to prepare the password for ahead, 0..{MAX_PREAUTH}.")
            out["preauth"] = preauth
        return out

    def _next_run(self, schedule_json: str, after_ms: int | None = None) -> int:
        schedule = json.loads(schedule_json)
        local = self._local_now()
        after = local if after_ms is None else datetime.fromtimestamp(after_ms / 1000, local.tzinfo)
        return int(schedules.next_after(schedule, after).timestamp() * 1000)

    def create_routine(self, body: dict[str, Any], *, actor: str = "owner") -> dict[str, Any]:
        fields = self._routine_fields(body, partial=False)
        self._check_goal(fields["goal"], fields["make"])
        with self._lock:
            now = self._clock()
            routine_id = _id("rtn")
            paused = fields.get("paused", 0)
            if fields["preauth"] and not fields["vault_item_id"]:
                raise CommandError("Preparing runs ahead needs a vault login.")
            self._db.execute(
                "INSERT INTO routine(id, title, goal, devices, account_id, schedule, paused, next_run_at, last_run_at, created_at, updated_at,"
                " make, vault_item_id, preauth) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                (routine_id, fields["title"], fields["goal"], fields["devices"], fields["account_id"], fields["schedule"],
                 paused, self._next_run(fields["schedule"]), None, now, now, fields["make"], fields["vault_item_id"], fields["preauth"]))
            self._audit(actor, "routine.create", routine_id, {"schedule": json.loads(fields["schedule"]), "vault": bool(fields["vault_item_id"]),
                                                              "preauth": fields["preauth"], "make": [st["tool"] for st in chain.plan_of(fields["make"])["steps"]] if fields["make"] else None})
            self._plan_slots(self._db.execute("SELECT * FROM routine WHERE id = ?", (routine_id,)).fetchone())
            return self.get_routine(routine_id)

    def update_routine(self, routine_id: str, body: dict[str, Any]) -> dict[str, Any]:
        with self._lock:
            current = self.get_routine(routine_id)
            merged = {"deviceIds": current["deviceIds"], **body} if ("accountId" in body or "vaultItemId" in body) and "deviceIds" not in body else body
            if "vaultItemId" in merged and "accountId" not in merged:
                merged = {**merged, "accountId": current["accountId"]}
            fields = self._routine_fields(merged, partial=True)
            if "goal" in fields or "make" in fields:
                current_plan = chain.plan_of(self._db.execute("SELECT make FROM routine WHERE id = ?", (routine_id,)).fetchone()["make"])
                self._check_goal(fields.get("goal", current["goal"]), fields["make"] if "make" in fields else (json.dumps(current_plan) if current_plan else None))
            if "schedule" in fields or fields.get("paused") == 0:
                fields["next_run_at"] = self._next_run(fields.get("schedule") or json.dumps(current["schedule"]))
            if fields:
                sets = ", ".join(f"{k} = ?" for k in fields)
                self._db.execute(f"UPDATE routine SET {sets}, updated_at = ? WHERE id = ?", (*fields.values(), self._clock(), routine_id))
                self._audit("owner", "routine.update", routine_id, {"fields": sorted(fields)})
                row = self._db.execute("SELECT * FROM routine WHERE id = ?", (routine_id,)).fetchone()
                if row["preauth"] and not row["vault_item_id"]:
                    self._db.execute("UPDATE routine SET preauth = 0 WHERE id = ?", (routine_id,))
                if set(fields) & {"schedule", "devices", "account_id", "vault_item_id", "preauth", "paused", "next_run_at"}:
                    self._clear_slots(routine_id, "The routine changed.")
                    self._plan_slots(self._db.execute("SELECT * FROM routine WHERE id = ?", (routine_id,)).fetchone())
            return self.get_routine(routine_id)

    def delete_routine(self, routine_id: str) -> dict[str, Any]:
        with self._lock:
            self.get_routine(routine_id)
            self._clear_slots(routine_id, "The routine was deleted.")
            self._db.execute("DELETE FROM routine WHERE id = ?", (routine_id,))
            self._audit("owner", "routine.delete", routine_id)
            return {"id": routine_id, "deleted": True}

    def run_routine_now(self, routine_id: str) -> dict[str, Any]:
        with self._lock:
            routine = self._db.execute("SELECT * FROM routine WHERE id = ?", (routine_id,)).fetchone()
            if routine is None:
                raise CommandError("No such routine.")
            created = self._spawn(routine, f"now:{self._clock()}", actor="owner")
            return {"routine": self.get_routine(routine_id), "tasks": created}

    def pause_all(self, paused: bool) -> dict[str, Any]:
        with self._lock:
            self._db.execute("UPDATE routine SET paused = ?, updated_at = ?", (1 if paused else 0, self._clock()))
            if not paused:
                for r in self._db.execute("SELECT id, schedule FROM routine").fetchall():
                    self._db.execute("UPDATE routine SET next_run_at = ? WHERE id = ?", (self._next_run(r["schedule"]), r["id"]))
            self._audit("owner", "routine.pause_all" if paused else "routine.resume_all", "routine:*")
            return {"routines": self.list_routines()}

    def list_routines(self) -> list[dict[str, Any]]:
        with self._lock:
            return [self._routine_public(r) for r in self._db.execute("SELECT * FROM routine ORDER BY title").fetchall()]

    def get_routine(self, routine_id: str) -> dict[str, Any]:
        row = self._db.execute("SELECT * FROM routine WHERE id = ?", (routine_id,)).fetchone()
        if row is None:
            raise CommandError("No such routine.")
        return self._routine_public(row)

    def _routine_public(self, r: sqlite3.Row) -> dict[str, Any]:
        schedule = json.loads(r["schedule"])
        counts = self._db.execute(
            "SELECT SUM(status = 'succeeded') ok, SUM(status = 'failed') bad FROM task WHERE routine_id = ?", (r["id"],)).fetchone()
        return {
            "id": r["id"], "title": r["title"], "goal": r["goal"], "deviceIds": json.loads(r["devices"]),
            "accountId": r["account_id"], "schedule": schedule, "scheduleLabel": schedules.describe(schedule),
            "paused": bool(r["paused"]), "nextRunAt": None if r["paused"] else r["next_run_at"], "lastRunAt": r["last_run_at"],
            "succeeded": counts["ok"] or 0, "failed": counts["bad"] or 0,
            "make": chain.public(chain.plan_of(r["make"])), "vaultItemId": r["vault_item_id"], "preauth": r["preauth"],
            "prepared": [{"dueAt": slot["due_at"], "taskId": slot["task_id"], "deviceId": slot["device_id"],
                          "ready": bool(self.delivery.ready_leases(slot["task_id"]))}
                         for slot in self._db.execute("SELECT * FROM routine_slot WHERE routine_id = ? ORDER BY due_at", (r["id"],))],
        }

    # ---------------------------------------------------------------- pre-authorised leases (C3 milestone)

    def _plan_slots(self, routine: sqlite3.Row) -> None:
        """Reserve task ids for a routine's next runs, so the owner's browser can seal each run's password ahead.
        Each lease is bound to its run's own task id and expires 30 minutes after the run is due; the phone accepts it
        for that task only, so it cannot be used for another run or earlier by another task."""
        devices = json.loads(routine["devices"])
        if routine["paused"] or not routine["preauth"] or not routine["vault_item_id"] or len(devices) != 1:
            return
        due, times = routine["next_run_at"], []
        schedule = json.loads(routine["schedule"])
        while due is not None and len(times) < routine["preauth"]:
            times.append(due)
            due = int(schedules.next_after(schedule, datetime.fromtimestamp(due / 1000, self._local_now().tzinfo)).timestamp() * 1000)
        now = self._clock()
        for slot in self._db.execute("SELECT * FROM routine_slot WHERE routine_id = ?", (routine["id"],)).fetchall():
            if slot["due_at"] not in times or slot["device_id"] != devices[0]:
                self._drop_slot(slot, "Its run is no longer scheduled." if slot["due_at"] > now else "Its run has passed.")
        for due_at in times:
            if not self._db.execute("SELECT 1 FROM routine_slot WHERE routine_id = ? AND due_at = ? AND device_id = ?",
                                    (routine["id"], due_at, devices[0])).fetchone():
                self._db.execute("INSERT INTO routine_slot(routine_id, due_at, device_id, task_id, created_at) VALUES (?,?,?,?,?)",
                                 (routine["id"], due_at, devices[0], _id("tsk"), now))

    def _drop_slot(self, slot: sqlite3.Row, why: str) -> None:
        self._db.execute("DELETE FROM routine_slot WHERE task_id = ?", (slot["task_id"],))
        if not self._db.execute("SELECT 1 FROM task WHERE id = ?", (slot["task_id"],)).fetchone():
            n = self._db.execute("UPDATE lease SET state = 'revoked', updated_at = ? WHERE task_id = ? AND state = 'ready'",
                                 (self._clock(), slot["task_id"])).rowcount
            if n:
                self._audit("engine", "lease.revoke", slot["task_id"], {"count": n, "why": why})

    def _clear_slots(self, routine_id: str, why: str) -> None:
        for slot in self._db.execute("SELECT * FROM routine_slot WHERE routine_id = ?", (routine_id,)).fetchall():
            self._drop_slot(slot, why)

    def _spawn(self, routine: sqlite3.Row, slot: str, *, actor: str) -> list[dict[str, Any]]:
        devices = json.loads(routine["devices"]) or [None]
        created = []
        for device in devices:
            key = f"rtn:{routine['id']}:{slot}:{device or 'any'}"
            if self._db.execute("SELECT 1 FROM task WHERE idempotency_key = ?", (key,)).fetchone():
                continue
            reserved = None
            if slot.isdigit() and device:
                row = self._db.execute("SELECT task_id FROM routine_slot WHERE routine_id = ? AND due_at = ? AND device_id = ?",
                                       (routine["id"], int(slot), device)).fetchone()
                if row:
                    reserved = row["task_id"]
                    self._db.execute("DELETE FROM routine_slot WHERE task_id = ?", (reserved,))
            vault_item = routine["vault_item_id"] if device and len(devices) == 1 else None
            created.append(self._insert_task(routine["title"], routine["goal"], device, routine["account_id"], None,
                                             routine["id"], None, key, actor, task_id=reserved, vault_item=vault_item, make=routine["make"]))
        self._db.execute("UPDATE routine SET last_run_at = ? WHERE id = ?", (self._clock(), routine["id"]))
        return created

    # ---------------------------------------------------------------- approvals

    def list_approvals(self, *, state: str = "open") -> list[dict[str, Any]]:
        with self._lock:
            if state == "all":
                rows = self._db.execute("SELECT a.*, COALESCE(t.title, 'Connection call') AS title FROM approval a LEFT JOIN task t ON t.id = a.task_id"
                                        " ORDER BY a.created_at DESC LIMIT 300").fetchall()
            else:
                rows = self._db.execute(
                    "SELECT a.*, COALESCE(t.title, 'Connection call') AS title FROM approval a LEFT JOIN task t ON t.id = a.task_id"
                    " WHERE a.state = ? ORDER BY a.created_at", (state,)).fetchall()
            return [self._approval_public(r) for r in rows]

    @staticmethod
    def _approval_public(r: sqlite3.Row) -> dict[str, Any]:
        return {
            "id": r["id"], "taskId": r["task_id"], "runId": r["run_id"], "title": r["title"], "deviceId": r["device_id"],
            "kind": r["kind"], "text": r["text"], "gate": r["gate"], "send": json.loads(r["send"]) if r["send"] else None,
            "choices": json.loads(r["choices"]), "fields": json.loads(r["fields"]), "approvableHere": bool(r["approvable_here"]),
            "answerHere": r["kind"] in ("question", "values", "approval", "spend"),
            "state": r["state"], "answer": r["answer"], "createdAt": r["created_at"], "answeredAt": r["answered_at"],
        }

    def answer(self, approval_id: str, body: dict[str, Any]) -> dict[str, Any]:
        """The owner's answer from Glass. Only a person reaches this route; the coordinator (C4) never will."""
        _only(body, {"action", "text", "values"})
        action = body.get("action")
        if action not in ("approve", "decline", "reply", "fill"):
            raise CommandError("action is approve, decline, reply or fill.")
        with self._lock:
            row = self._db.execute("SELECT * FROM approval WHERE id = ?", (approval_id,)).fetchone()
            if row is None:
                raise CommandError("No such approval.")
            if row["state"] != "open":
                raise CommandError("That was already answered or withdrawn.")
            if row["kind"] == "spend":
                # Plan 33 (C3): the owner's OK for a connection call (it may use credits). Only approve or decline.
                if action not in ("approve", "decline"):
                    raise CommandError("A connection call is approved or declined.")
                result = self.connections.answer(row, action)
                self._db.execute("UPDATE approval SET state = 'answered', answer = ?, answered_at = ? WHERE id = ?", (action, self._clock(), approval_id))
                self._audit("owner", f"approval.{action}", approval_id, {"kind": "spend", "call": row["request_id"]})
                return {"approval": self._approval_public(self._db.execute(
                    "SELECT a.*, COALESCE(t.title, 'Connection call') AS title FROM approval a LEFT JOIN task t ON t.id = a.task_id WHERE a.id = ?",
                    (approval_id,)).fetchone()), **result}
            if row["kind"] == "login":
                raise CommandError("Unlock the vault in Glass (Command Center -> Vault); this clears itself when the password is sent.")
            if row["kind"] not in ("question", "values", "approval"):
                raise CommandError("Secure input and taking over happen on the phone.")
            if action == "approve" and (row["kind"] != "approval" or not row["approvable_here"]):
                raise CommandError("Approve this one on the phone.")
            text = _clean_text(body.get("text"), "answer", 500) if action == "reply" else None
            values = None
            if action == "fill":
                raw = body.get("values")
                if not isinstance(raw, dict):
                    raise CommandError("values are the details to fill in.")
                values = {k: _clean_text(v, k if isinstance(k, str) else "value", 300) for k, v in raw.items()}
                if any(_secret_name(k) for k in values):
                    raise CommandError("Secrets are typed on the phone, never sent from the PC.")
            try:
                result = self._contract.cc_answer(row["device_id"], row["mission_id"], action, request_id=row["request_id"],
                                                  text=text, values=values)
            except DesktopRuntimeError as exc:
                if str(exc.code) in ("MOMENT_CHANGED", "RUN_NOT_FOUND"):
                    self._db.execute("UPDATE approval SET state = 'withdrawn' WHERE id = ?", (approval_id,))
                    raise CommandError("The phone is not waiting for that any more.") from exc
                raise
            if result.get("handled"):
                self._db.execute("UPDATE approval SET state = 'answered', answer = ?, answered_at = ? WHERE id = ?",
                                 (action, self._clock(), approval_id))
                self._audit("owner", f"approval.{action}", approval_id, {"task": row["task_id"], "kind": row["kind"], "gate": row["gate"]})
            return {"approval": self._approval_public(self._db.execute(
                "SELECT a.*, COALESCE(t.title, 'Connection call') AS title FROM approval a LEFT JOIN task t ON t.id = a.task_id WHERE a.id = ?",
                (approval_id,)).fetchone()),
                "handled": bool(result.get("handled")), "detail": str(result.get("detail", ""))[:200]}

    # ---------------------------------------------------------------- overview

    def overview(self) -> dict[str, Any]:
        with self._lock:
            def count(sql: str, *args: Any) -> int:
                return int(self._db.execute(sql, args).fetchone()[0] or 0)
            day = self._clock() - 24 * 60 * 60_000
            return {
                "accounts": count("SELECT COUNT(*) FROM account"),
                "openTasks": count(f"SELECT COUNT(*) FROM task WHERE status IN ({','.join('?' * len(OPEN_TASK_STATES))})", *OPEN_TASK_STATES),
                "running": count("SELECT COUNT(*) FROM task WHERE status IN ('running','needs_you')"),
                "routines": count("SELECT COUNT(*) FROM routine"),
                "routinesPaused": count("SELECT COUNT(*) FROM routine WHERE paused = 1"),
                "approvals": count("SELECT COUNT(*) FROM approval WHERE state = 'open'"),
                "succeeded24h": count("SELECT COUNT(*) FROM run WHERE status = 'succeeded' AND started_at >= ?", day),
                "failed24h": count("SELECT COUNT(*) FROM run WHERE status = 'failed' AND started_at >= ?", day),
            }

    # ---------------------------------------------------------------- the job loop

    def tick(self) -> None:
        self.connections.local.sweep()
        with self._lock:
            self._fire_routines()
            ready = self._ready_devices()
            self._follow_runs()
            self._dispatch(ready)
            self.signup.sync()
            self.buttons.sync()

    def _fire_routines(self) -> None:
        now = self._clock()
        for routine in self._db.execute("SELECT * FROM routine WHERE paused = 0 AND next_run_at <= ?", (now,)).fetchall():
            due = routine["next_run_at"]
            if now - due <= MISSED_GRACE_MS:
                self._spawn(routine, str(due), actor="routine")
            else:
                self._audit("routine", "routine.missed", routine["id"], {"due": due})
            self._db.execute("UPDATE routine SET next_run_at = ? WHERE id = ?", (self._next_run(routine["schedule"], max(now, due)), routine["id"]))
        for routine in self._db.execute("SELECT * FROM routine WHERE preauth > 0 OR id IN (SELECT routine_id FROM routine_slot)").fetchall():
            if routine["paused"] or not routine["preauth"]:
                self._clear_slots(routine["id"], "The routine is paused.")
            else:
                self._plan_slots(routine)

    def _ready_devices(self) -> dict[str, dict[str, Any]]:
        try:
            listed = self._devices()
        except Exception:  # noqa: BLE001 - discovery trouble means no phone is ready this tick
            return {}
        return {str(d.get("deviceId")): d for d in listed if d.get("paired") and d.get("state") == "ready" and d.get("deviceId")}

    def _dispatch(self, ready: dict[str, dict[str, Any]]) -> None:
        now = self._clock()
        running: dict[str, int] = {}
        for r in self._db.execute("SELECT device_id FROM run WHERE ended_at IS NULL"):
            running[r["device_id"]] = running.get(r["device_id"], 0) + 1
        # A phone that said it cannot take more this tick is not asked again until the next one.
        full: set[str] = set()
        tasks = self._db.execute(
            "SELECT * FROM task WHERE status IN ('scheduled','waiting_device') AND next_try_at <= ? ORDER BY COALESCE(due_at, created_at)",
            (now,)).fetchall()
        for task in tasks:
            if task["waiting_since"] and now - task["waiting_since"] > WAIT_LIMIT_MS:
                self._set_task(task["id"], "failed", "No phone could take it for 6 hours.")
                self._withdraw_gateway_approvals(task["id"])
                self._audit("engine", "task.expired", task["id"])
                continue
            make = chain.plan_of(task["make"])
            if make and task["step_at"] < len(make["steps"]):
                self._start_make(task, make)
                continue
            if make and make["then"] == "post":
                media = json.loads(task["media"]) if task["media"] else None
                if media and media.get("state") == "sending":
                    continue
            account = None
            if task["account_id"]:
                row = self._db.execute("SELECT * FROM account WHERE id = ?", (task["account_id"],)).fetchone()
                if row is None:
                    self._set_task(task["id"], "failed", "Its account was removed.")
                    continue
                account = self._account_public(row)
                if account["status"] != "active":
                    self._wait(task, "Its account is paused.")
                    continue
                if account["locked"]:
                    self._wait(task, "Another phone is using this account.")
                    continue
            candidates = [task["device_id"]] if task["device_id"] else sorted(ready)
            if account and account["allowedDevices"]:
                candidates = [d for d in candidates if d in account["allowedDevices"]]
            candidates = [d for d in candidates if d in ready and d not in full and running.get(d, 0) < PHONE_TASKS]
            if not candidates:
                self._wait(task, "Waiting for a ready phone." if not task["device_id"] else "Waiting for the phone to be ready.")
                continue
            # An idle phone first; a busy one only takes a task behind its current one.
            least = min(running.get(d, 0) for d in candidates)
            device = self._prefer([d for d in candidates if running.get(d, 0) == least], task["account_id"])
            if make and make["then"] == "post":
                media = json.loads(task["media"]) if task["media"] else None
                if not media or media.get("deviceId") != device or media.get("state") != "sent":
                    self._push_media(task, device)
                    continue
            goal = task["goal"]
            if make and make["then"] in ("phone", "post"):
                try:
                    goal = self._goal_with_results(task, make, goal)
                except chain.StepError as exc:
                    self._set_task(task["id"], "failed", str(exc))
                    self._audit("engine", "task.refused", task["id"], {"why": "step result"})
                    continue
            if account:
                goal = f"{goal}\n\nUse the account {account['handle']} ({account['service']})."
            sealed: list[dict[str, Any]] = []
            if task["vault_item_id"]:
                sealed = self.delivery.envelopes_for(task["id"])
                if not sealed:
                    self._wait(task, "Waiting for the vault: unlock it in Glass so it can send the password to this phone.")
                    self._login_approval(task)
                    continue
                self._withdraw_gateway_approvals(task["id"])
                slots = {e["slot"] for e in sealed}
                goal += "\n\nThe owner sent this phone the account's " + (
                    "password and authenticator code" if slots == {"password", "otp"} else "authenticator code" if slots == {"otp"} else "password"
                ) + " for this task only. On its field, use vault_fill (what=password" + (", what=one_time_code for the code" if "otp" in slots else "") + "); you never see the value."
            publish = bool(make and make["then"] == "post")
            if publish:
                media = json.loads(task["media"])
                goal += (f"\n\nThe file to post is already on this phone: {media['name']} (the newest item in the gallery, folder {media['folder']})."
                         " Post that file. The final Share or Post needs the owner's OK; ask for it and wait.")
            if len(goal) > chain.PHONE_GOAL:
                self._set_task(task["id"], "failed", f"With the step results the task is {len(goal)} characters; a phone takes {chain.PHONE_GOAL}."
                               " Name just the fields it needs, like {step1.name}.")
                self._audit("engine", "task.refused", task["id"], {"why": "goal too long"})
                continue
            extra: dict[str, Any] = {}
            if sealed or publish:
                extra["task_id"] = task["id"]
            if sealed:
                extra["sealed"] = sealed
            if publish:
                extra["publish"] = True
            from .signup import RUN_RECIPE, recipe_package
            mapped = recipe_package(task["recipe"])
            if mapped and not extra:
                extra["signup_map"] = mapped
            if recipe_package(task["recipe"], RUN_RECIPE):
                # Plan 43 T7: an Account Setup run; the phone gets the row's current values (never a password).
                run_args = self.signup.run_args(task["id"])
                if run_args is None:
                    self._set_task(task["id"], "failed", "This account's row is gone from its sign-up table.")
                    continue
                extra["signup_run"] = run_args
                extra.setdefault("task_id", task["id"])
            try:
                ack = self._contract.cc_start(device, goal, **extra)
            except DesktopRuntimeError as exc:
                code = str(exc.code)
                if code in WAIT_CODES:
                    if code == "ASK_BUSY":
                        full.add(device)
                    self._wait(task, _wait_reason(code))
                    continue
                if code == "SEALED_REJECTED":
                    self.delivery.mark_rejected(task["id"])
                    self._set_task(task["id"], "failed", "The phone refused the sealed password (another phone's key, expired or already used).")
                    self._audit("engine", "task.refused", task["id"], {"device": device, "code": code})
                    continue
                self._set_task(task["id"], "failed", f"The phone refused the task ({code}).")
                self._audit("engine", "task.refused", task["id"], {"device": device, "code": code})
                continue
            run_id = _id("run")
            self._db.execute(
                "INSERT INTO run(id, task_id, device_id, mission_id, status, cause, summary, turns, working_ms, cost_usd, started_at, ended_at, last_seen_at)"
                " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                (run_id, task["id"], device, ack["missionId"], "running", "", "", 0, 0, 0.0, now, None, now))
            self._set_task(task["id"], "running", "", waiting_since=None)
            if sealed:
                self.delivery.mark_delivered(task["id"], run_id, [e["leaseId"] for e in sealed])
            self._audit("engine", "task.start", task["id"], {"device": device, "run": run_id})
            running[device] = running.get(device, 0) + 1

    # ---------------------------------------------------------------- C3: make with a connection, then post from a phone

    def _start_make(self, task: sqlite3.Row, make: dict[str, Any]) -> None:
        """Start the task's next connection step, its arguments filled from the earlier steps' results."""
        index = task["step_at"]
        total = len(make["steps"])
        calls = self.connections.calls_of_task(task["id"])
        current = next((c for c in calls if c["step"] == index), None)
        if current and current["state"] in ("waiting", "running"):
            return
        step = make["steps"][index]
        label = f"Step {index + 1} of {total}: " if total > 1 else ""
        try:
            arguments = chain.fill(step["arguments"], {c["step"] + 1: c["result"] for c in calls if c["state"] == "done" and c["step"] < index})
        except chain.StepError as exc:
            self._set_task(task["id"], "failed", f"{label}{exc}")
            self._audit("engine", "task.refused", task["id"], {"why": "step reference", "step": index})
            return
        try:
            call = self.connections.call(step["connectionId"], step["tool"], arguments, task_id=task["id"],
                                         actor="routine" if task["routine_id"] else "engine", poll_tool=step.get("pollTool"), step=index)
        except CommandError as exc:
            message = str(exc)
            if "already waiting" in message or "already running" in message:
                self._wait(task, message)
                return
            self._set_task(task["id"], "failed", f"{label}{message}")
            self._audit("engine", "task.refused", task["id"], {"why": "connection", "step": index})
            return
        current = self._db.execute("SELECT status, step_at FROM task WHERE id = ?", (task["id"],)).fetchone()
        still = self.connections.get_call(call["id"])["state"] in ("waiting", "running")
        # The call may already have finished (and moved the task on) before this line runs.
        if still and current and current["step_at"] == index and current["status"] in ("scheduled", "waiting_device"):
            doing = "Making the file." if total == 1 and make["then"] == "post" else f"Calling {step['tool']}."
            self._set_task(task["id"], "making", label + ("Waiting for your OK to use the connection." if call["state"] == "waiting" else doing),
                           waiting_since=None)

    def _make_finished(self, task_id: str) -> None:
        """A task's connection call ended (called by the connection store, under this lock): go on to the next step,
        or post, keep, or hand the results to a phone."""
        task = self._db.execute("SELECT * FROM task WHERE id = ?", (task_id,)).fetchone()
        if task is None or task["status"] not in OPEN_TASK_STATES:
            return
        call = self.connections.call_of_task(task_id)
        make = chain.plan_of(task["make"])
        total = len(make["steps"])
        self._withdraw_gateway_approvals(task_id)
        label = f"Step {call['step'] + 1} of {total}: " if call and total > 1 else ""
        if call is None or call["state"] != "done":
            why = {"declined": "You declined the connection call.", "refused": "The connection refused the call."}.get(call["state"] if call else "", "")
            doing = "Making the file failed" if total == 1 and make["then"] == "post" else "The call failed"
            self._set_task(task_id, "failed", label + (why or f"{doing}: {(call or {}).get('summary', '')}")[:300])
            return
        if call["step"] + 1 < total:
            self._set_task(task_id, "scheduled", f"Step {call['step'] + 1} of {total} done.", step_at=call["step"] + 1, next_try_at=self._clock())
            self._audit("engine", "task.step", task_id, {"step": call["step"], "call": call["id"]})
            return
        made = [a for c in reversed(self.connections.calls_of_task(task_id)) for a in c["artifacts"]]
        if make["then"] == "post":
            media = [a for a in made if (self._db.execute("SELECT mime FROM artifact WHERE id = ?", (a,)).fetchone() or {"mime": ""})["mime"].startswith(("video/", "image/", "audio/"))]
            if not media:
                self._set_task(task_id, "failed", label + "No video, image or audio came back to post. " + call["summary"][:200], step_at=total)
                return
            self._set_task(task_id, "scheduled", "Made the file; sending it to a phone.", step_at=total, artifact_id=media[0], next_try_at=self._clock())
            self._audit("engine", "task.made", task_id, {"artifact": media[0]})
            return
        if make["then"] == "keep":
            if made:
                self._set_task(task_id, "succeeded", "Made and kept the file." if total == 1 else f"Ran {total} steps and kept the file.", step_at=total, artifact_id=made[0])
            else:
                self._set_task(task_id, "succeeded", "Done; what came back is kept with the call." if total == 1 else f"Ran {total} steps; the results are kept.",
                               step_at=total)
            self._audit("engine", "task.made", task_id, {"artifacts": made[:4]})
            return
        self._set_task(task_id, "scheduled", "The steps are done; a phone gets their results next.", step_at=total,
                       artifact_id=made[0] if made else None, next_try_at=self._clock())
        self._audit("engine", "task.steps_done", task_id, {"steps": total})

    def _goal_with_results(self, task: sqlite3.Row, make: dict[str, Any], goal: str) -> str:
        """The phone's goal with the steps' results as quoted data (plan 34 §5: outside content, never instructions)."""
        results = {c["step"] + 1: c["result"] for c in self.connections.calls_of_task(task["id"]) if c["state"] == "done"}
        if make["then"] == "post" and not chain.references(goal):
            return goal
        return chain.quote_for_phone(goal, results, last=len(make["steps"]))

    def _push_media(self, task: sqlite3.Row, device: str) -> None:
        """Send the made file to [device]'s gallery in chunks, off the job loop. The task goes on once it is there."""
        artifact, path = self.connections.artifact(task["artifact_id"])
        self._db.execute("UPDATE task SET media = ?, cause = ? WHERE id = ?",
                         (json.dumps({"deviceId": device, "state": "sending", "name": artifact["name"]}), "Sending the file to the phone.", task["id"]))
        task_id = task["id"]

        def push() -> None:
            outcome: dict[str, Any] = {"deviceId": device, "state": "failed", "name": artifact["name"]}
            try:
                with open(path, "rb") as handle:
                    offset = 0
                    result: dict[str, Any] = {}
                    while offset < artifact["size"] or offset == 0:
                        chunk = handle.read(MEDIA_CHUNK)
                        result = self._contract.cc_media(device, task_id, name=artifact["name"], mime=artifact["mime"], size=artifact["size"],
                                                         sha256=artifact["sha256"], offset=offset, data=chunk)
                        offset += len(chunk)
                        if not chunk:
                            break
                if result.get("done"):
                    outcome = {"deviceId": device, "state": "sent", "name": result.get("name") or artifact["name"],
                               "folder": result.get("folder") or "Cyclone"}
            except (DesktopRuntimeError, OSError) as exc:
                outcome["why"] = str(getattr(exc, "code", "")) or exc.__class__.__name__
            with self._lock:
                self._db.execute("UPDATE task SET media = ?, next_try_at = ?, cause = ? WHERE id = ?",
                                 (json.dumps(outcome), self._clock() + (0 if outcome["state"] == "sent" else 30_000),
                                  "The file is on the phone." if outcome["state"] == "sent" else "Sending the file to the phone failed; trying again.", task_id))
                self._audit("engine", f"media.{outcome['state']}", task_id, {"device": device, "artifact": artifact["id"]})

        self.connections._spawn(push)

    def _login_approval(self, task: sqlite3.Row) -> None:
        """A run that needs the vault and has no sealed password: ask the owner to unlock it (never answered by itself)."""
        if self._db.execute("SELECT 1 FROM approval WHERE task_id = ? AND kind = 'login' AND state = 'open'", (task["id"],)).fetchone():
            return
        account = self._db.execute("SELECT handle, service FROM account WHERE id = ?", (task["account_id"],)).fetchone()
        approval_id = _id("apv")
        self._db.execute(
            "INSERT INTO approval(id, run_id, task_id, device_id, mission_id, request_id, kind, text, gate, send, choices, fields,"
            " approvable_here, state, answer, created_at, answered_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (approval_id, "", task["id"], task["device_id"] or "", "", f"login:{task['id']}", "login",
             f"Unlock the vault in Glass to send the password for {account['handle'] if account else 'this account'} to the phone.",
             None, None, "[]", "[]", 0, "open", None, self._clock(), None))
        self._audit("engine", "approval.open", approval_id, {"kind": "login", "task": task["id"]})

    def _prefer(self, candidates: list[str], account_id: str | None) -> str:
        if account_id:
            row = self._db.execute(
                "SELECT r.device_id FROM run r JOIN task t ON t.id = r.task_id WHERE t.account_id = ? AND r.status = 'succeeded'"
                " ORDER BY r.ended_at DESC LIMIT 1", (account_id,)).fetchone()
            if row and row["device_id"] in candidates:
                return row["device_id"]
        return candidates[0]

    def _wait(self, task: sqlite3.Row, reason: str) -> None:
        now = self._clock()
        self._set_task(task["id"], "waiting_device", reason, next_try_at=now + 30_000, waiting_since=task["waiting_since"] or now)

    def _follow_runs(self) -> None:
        now = self._clock()
        for run in self._db.execute("SELECT * FROM run WHERE ended_at IS NULL").fetchall():
            try:
                status = self._contract.cc_status(run["device_id"], run["mission_id"])
            except DesktopRuntimeError as exc:
                if str(exc.code) == "RUN_NOT_FOUND":
                    self._finish_run(run, "failed", "The phone no longer knows this mission.")
                elif now - run["last_seen_at"] > UNREACHABLE_LIMIT_MS:
                    self._finish_run(run, "failed", "The phone was unreachable for 30 minutes.")
                continue
            self._db.execute("UPDATE run SET turns = ?, working_ms = ?, cost_usd = ?, summary = ?, last_seen_at = ? WHERE id = ?",
                             (status["turns"], status["workingMs"], float(status["costUsd"]), status["summary"], now, run["id"]))
            if status.get("setup"):
                self.signup.progress(run["task_id"], status["setup"])
            if status.get("leases"):
                self.delivery.report(run["id"], status["leases"])
            moment = status.get("moment")
            self._sync_approvals(run, moment)
            if status["live"]:
                self._set_task(run["task_id"], "needs_you" if moment else "running")
                continue
            outcome = FINISHED.get(status["status"])
            if outcome is None:  # not live but not finished: the phone is between states; look again next tick
                continue
            cause = {"gave_up": "Cyclone gave up.", "failed": "The mission failed.", "cancelled": "Stopped.",
                     "paused": "Its working time ran out.", "interrupted": "Cyclone was stopped or restarted on the phone."
                     }.get(status["status"], "")
            self._finish_run(self._db.execute("SELECT * FROM run WHERE id = ?", (run["id"],)).fetchone(), outcome, cause)

    def _sync_approvals(self, run: sqlite3.Row, moment: dict[str, Any] | None) -> None:
        request_id = moment.get("requestId") if moment else None
        self._db.execute("UPDATE approval SET state = 'withdrawn' WHERE run_id = ? AND state = 'open' AND request_id IS NOT ?",
                         (run["id"], request_id))
        if not moment or not request_id:
            return
        if self._db.execute("SELECT 1 FROM approval WHERE run_id = ? AND request_id = ?", (run["id"], request_id)).fetchone():
            return
        approval_id = _id("apv")
        self._db.execute(
            "INSERT INTO approval(id, run_id, task_id, device_id, mission_id, request_id, kind, text, gate, send, choices, fields,"
            " approvable_here, state, answer, created_at, answered_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (approval_id, run["id"], run["task_id"], run["device_id"], run["mission_id"], request_id, moment["kind"],
             moment["text"], moment.get("gate"), json.dumps(moment["send"]) if moment.get("send") else None,
             json.dumps(moment.get("choices", [])), json.dumps(moment.get("fields", [])),
             1 if moment.get("approvableHere") else 0, "open", None, self._clock(), None))
        self._audit("phone", "approval.open", approval_id, {"task": run["task_id"], "kind": moment["kind"], "gate": moment.get("gate")})

    def _finish_run(self, run: sqlite3.Row, outcome: str, cause: str) -> None:
        now = self._clock()
        self._db.execute("UPDATE run SET status = ?, cause = ?, ended_at = ? WHERE id = ?", (outcome, cause[:300], now, run["id"]))
        self._db.execute("UPDATE approval SET state = 'withdrawn' WHERE run_id = ? AND state = 'open'", (run["id"],))
        self.delivery.finish_run(run["id"])
        task = self._db.execute("SELECT status FROM task WHERE id = ?", (run["task_id"],)).fetchone()
        if task and task["status"] in OPEN_TASK_STATES:
            self._set_task(run["task_id"], outcome, cause)
        self._withdraw_gateway_approvals(run["task_id"])
        self._audit("engine", f"run.{outcome}", run["id"], {"task": run["task_id"]})


def _wait_reason(code: str) -> str:
    return {
        "ASK_BUSY": "The phone is busy with another task.",
        "HUMAN_HAS_CONTROL": "You have control of the phone.",
        "OVERLAY_UNAVAILABLE": "Cyclone's phone control is off on the phone.",
        "PAIRING_REQUIRED": "The phone is not paired.",
    }.get(code, "The phone is not reachable.")
