"""Sealed delivery (plan 33, C2): the owner's browser seals one vault secret to one phone's device key, for one task.
This module keeps the phones' public keys (and whether the owner trusts them), and the sealed leases, as opaque bytes.

What the gateway can see: which task, phone, vault item (by id), slot and app or site a lease is for, and when it
expires; all of that is also bound inside the envelope, so the phone checks it again. What it cannot see or do: the
secret. It has no private key, and the envelope opens only inside the target phone's Android Keystore.

A lease is single-use: ready -> delivered (sent with the task) -> used | failed | unused | expired | rejected, or
revoked/replaced before delivery. The phone keeps its own used-lease list, so a replayed envelope is refused there too.
"""
from __future__ import annotations

import base64
import json
import re
from typing import TYPE_CHECKING, Any

if TYPE_CHECKING:  # pragma: no cover
    from .center import CommandCenter

from ..desktop_runtime.models import DesktopRuntimeError
from .center import CommandError

DELIVERY_SCHEMA = """
CREATE TABLE IF NOT EXISTS device_key (
  device_id TEXT PRIMARY KEY, public_key TEXT NOT NULL, fingerprint TEXT NOT NULL, strongbox INTEGER NOT NULL,
  fetched_at INTEGER NOT NULL, trusted_at INTEGER);
CREATE TABLE IF NOT EXISTS lease (
  id TEXT PRIMARY KEY, task_id TEXT NOT NULL, device_id TEXT NOT NULL, vault_item_id TEXT NOT NULL, slot TEXT NOT NULL,
  place TEXT NOT NULL, fingerprint TEXT NOT NULL, enc TEXT NOT NULL, ct TEXT NOT NULL, aad TEXT NOT NULL,
  expires_at INTEGER NOT NULL, state TEXT NOT NULL, run_id TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS lease_task ON lease(task_id);
"""

LEASE_ID = re.compile(r"^ls_[0-9a-f]{11}[A-Za-z0-9_-]{8,24}$")
B64 = re.compile(r"^[A-Za-z0-9+/]+={0,2}$")
FINGERPRINT = re.compile(r"^(?:[0-9A-F]{4} ){7}[0-9A-F]{4}$")
SLOTS = ("password", "otp")
MAX_LEASE_MS = 24 * 60 * 60_000
#: Pre-authorised leases (C3 milestone): a routine's future run may be prepared up to 8 days ahead; its lease still ends
#: 30 minutes after that run is due.
MAX_AHEAD_MS = 8 * 24 * 60 * 60_000
RUN_WINDOW_MS = 30 * 60_000
FINAL = ("used", "failed", "unused", "expired", "rejected", "revoked", "replaced")


_PACKAGE_ROOTS = {"com", "org", "net", "io", "app", "co", "de", "nl", "fr", "uk", "me", "tv", "dev", "ai", "us", "ch", "be",
                  "se", "no", "dk", "fi", "it", "es", "pl", "br", "in", "jp", "kr", "cn", "ru", "au", "ca", "eu", "info", "biz"}


def place_for(service: str) -> str:
    """The app or site a lease may be filled on. An Android package (com.example.app: a known top-level name first, or
    capitals/underscores) is an app; anything else (example.com, shop.example.co.uk) is a website over https."""
    parts = service.split(".")
    if "_" in service or service != service.lower() or (parts[0] in _PACKAGE_ROOTS and len(parts) >= 2):
        return f"package:{service}"
    return f"chrome:https://{service}"


class DeliveryStore:
    def __init__(self, center: "CommandCenter") -> None:
        self._c = center
        with center._lock:
            center._db.executescript(DELIVERY_SCHEMA)

    # ------------------------------------------------------------------ phones and keys

    def phones(self) -> list[dict[str, Any]]:
        with self._c._lock:
            keys = {r["device_id"]: r for r in self._c._db.execute("SELECT * FROM device_key")}
            try:
                listed = self._c._devices()
            except Exception:  # noqa: BLE001 - the key list still shows without discovery
                listed = []
            seen: set[str] = set()
            out = []
            for d in listed:
                device_id = str(d.get("deviceId") or "")
                if not device_id:
                    continue
                seen.add(device_id)
                out.append(self._phone_public(device_id, str(d.get("name") or device_id), bool(d.get("paired")) and d.get("state") == "ready", keys.get(device_id)))
            for device_id, row in keys.items():
                if device_id not in seen:
                    out.append(self._phone_public(device_id, device_id, False, row))
            return out

    @staticmethod
    def _phone_public(device_id: str, name: str, ready: bool, row: Any) -> dict[str, Any]:
        return {
            "deviceId": device_id, "name": name, "ready": ready,
            "key": None if row is None else {
                "publicKey": row["public_key"], "fingerprint": row["fingerprint"], "strongBox": bool(row["strongbox"]),
                "fetchedAt": row["fetched_at"], "trusted": row["trusted_at"] is not None, "trustedAt": row["trusted_at"],
            },
        }

    def fetch_key(self, device_id: str) -> dict[str, Any]:
        """Ask the phone for its device key. A key that changed is no longer trusted, and its ready leases are revoked."""
        try:
            key = self._c._contract.cc_key(device_id)
        except DesktopRuntimeError as exc:
            raise CommandError(f"The phone did not give its key ({exc.code}). Update Cyclone on the phone and keep it connected.") from exc
        with self._c._lock:
            now = self._c._clock()
            old = self._c._db.execute("SELECT * FROM device_key WHERE device_id = ?", (device_id,)).fetchone()
            changed = old is not None and old["public_key"] != key["publicKey"]
            trusted = None if (old is None or changed) else old["trusted_at"]
            self._c._db.execute(
                "INSERT OR REPLACE INTO device_key(device_id, public_key, fingerprint, strongbox, fetched_at, trusted_at) VALUES (?,?,?,?,?,?)",
                (device_id, key["publicKey"], key["fingerprint"], 1 if key["strongBox"] else 0, now, trusted))
            if changed:
                self._revoke_device(device_id, "Its device key changed.")
            self._c._audit("owner", "phone.key", device_id, {"fingerprint": key["fingerprint"], "changed": changed, "strongBox": key["strongBox"]})
            return self._phone_public(device_id, device_id, True, self._c._db.execute("SELECT * FROM device_key WHERE device_id = ?", (device_id,)).fetchone())

    def trust(self, device_id: str, body: Any) -> dict[str, Any]:
        """The owner compared the fingerprint on both screens. They send back the fingerprint they saw."""
        if not isinstance(body, dict) or set(body) != {"fingerprint"} or not isinstance(body["fingerprint"], str):
            raise CommandError("Send the fingerprint you compared.")
        with self._c._lock:
            row = self._c._db.execute("SELECT * FROM device_key WHERE device_id = ?", (device_id,)).fetchone()
            if row is None:
                raise CommandError("Get the phone's key first.")
            if body["fingerprint"].strip().upper() != row["fingerprint"]:
                raise CommandError("That fingerprint is not this phone's key. Do not trust it.")
            self._c._db.execute("UPDATE device_key SET trusted_at = ? WHERE device_id = ?", (self._c._clock(), device_id))
            self._c._audit("owner", "phone.trust", device_id, {"fingerprint": row["fingerprint"]})
            return self._phone_public(device_id, device_id, True, self._c._db.execute("SELECT * FROM device_key WHERE device_id = ?", (device_id,)).fetchone())

    def untrust(self, device_id: str) -> dict[str, Any]:
        with self._c._lock:
            self._c._db.execute("UPDATE device_key SET trusted_at = NULL WHERE device_id = ?", (device_id,))
            self._revoke_device(device_id, "The phone is no longer trusted.")
            self._c._audit("owner", "phone.untrust", device_id)
            return {"deviceId": device_id, "trusted": False}

    def trusted_key(self, device_id: str) -> Any:
        return self._c._db.execute("SELECT * FROM device_key WHERE device_id = ? AND trusted_at IS NOT NULL", (device_id,)).fetchone()

    def _revoke_device(self, device_id: str, why: str) -> None:
        n = self._c._db.execute("UPDATE lease SET state = 'revoked', updated_at = ? WHERE device_id = ? AND state = 'ready'",
                                (self._c._clock(), device_id)).rowcount
        if n:
            self._c._audit("engine", "lease.revoke", device_id, {"count": n, "why": why})

    # ------------------------------------------------------------------ leases

    def pending(self) -> list[dict[str, Any]]:
        """Tasks that need a sealed secret and have no ready one: what Glass should seal next (all metadata)."""
        with self._c._lock:
            now = self._c._clock()
            self._expire(now)
            rows = self._c._db.execute(
                "SELECT t.*, a.service, a.handle FROM task t JOIN account a ON a.id = t.account_id WHERE t.vault_item_id IS NOT NULL"
                " AND t.status IN ('scheduled','waiting_device') ORDER BY COALESCE(t.due_at, t.created_at)").fetchall()
            out = []
            for t in rows:
                if self.ready_leases(t["id"]):
                    continue
                key = self.trusted_key(t["device_id"]) if t["device_id"] else None
                out.append({
                    "taskId": t["id"], "title": t["title"], "deviceId": t["device_id"], "vaultItemId": t["vault_item_id"],
                    "accountId": t["account_id"], "handle": t["handle"], "place": place_for(t["service"]),
                    "dueAt": t["due_at"], "deviceKey": None if key is None else {"publicKey": key["public_key"], "fingerprint": key["fingerprint"]},
                    "ahead": False, "routineId": t["routine_id"],
                })
            # Pre-authorised leases: a routine's next runs, each by the task id it will have.
            for s in self._c._db.execute(
                    "SELECT s.*, r.title, r.vault_item_id, r.account_id, a.service, a.handle FROM routine_slot s JOIN routine r ON r.id = s.routine_id"
                    " JOIN account a ON a.id = r.account_id WHERE r.vault_item_id IS NOT NULL AND r.paused = 0 AND s.due_at > ? ORDER BY s.due_at", (now,)).fetchall():
                if self.ready_leases(s["task_id"]):
                    continue
                key = self.trusted_key(s["device_id"])
                out.append({
                    "taskId": s["task_id"], "title": s["title"], "deviceId": s["device_id"], "vaultItemId": s["vault_item_id"],
                    "accountId": s["account_id"], "handle": s["handle"], "place": place_for(s["service"]), "dueAt": s["due_at"],
                    "deviceKey": None if key is None else {"publicKey": key["public_key"], "fingerprint": key["fingerprint"]},
                    "ahead": True, "routineId": s["routine_id"],
                })
            return out

    def ready_leases(self, task_id: str) -> list[Any]:
        now = self._c._clock()
        return self._c._db.execute("SELECT * FROM lease WHERE task_id = ? AND state = 'ready' AND expires_at > ?", (task_id, now + 60_000)).fetchall()

    def submit(self, task_id: str, body: Any) -> dict[str, Any]:
        """Glass sealed the secret(s) for [task_id]. Every bound field is checked against the task; the bytes stay opaque."""
        if not isinstance(body, dict) or set(body) != {"envelopes"} or not isinstance(body["envelopes"], list) or not 1 <= len(body["envelopes"]) <= 2:
            raise CommandError("Send {envelopes: [...]} (one or two).")
        with self._c._lock:
            task = self._c._db.execute(
                "SELECT t.*, a.service FROM task t JOIN account a ON a.id = t.account_id WHERE t.id = ?", (task_id,)).fetchone()
            ahead = None
            if task is None:
                # A routine's future run (pre-authorised lease): the slot holds the task id that run will have.
                ahead = self._c._db.execute(
                    "SELECT s.task_id AS id, s.device_id, s.due_at, r.vault_item_id, 'scheduled' AS status, a.service FROM routine_slot s"
                    " JOIN routine r ON r.id = s.routine_id JOIN account a ON a.id = r.account_id WHERE s.task_id = ? AND r.paused = 0",
                    (task_id,)).fetchone()
                task = ahead
            if task is None or task["vault_item_id"] is None:
                raise CommandError("That task does not use a vault secret.")
            if task["status"] not in ("scheduled", "waiting_device", "making"):
                raise CommandError("That task has already started or finished.")
            key = self.trusted_key(task["device_id"])
            if key is None:
                raise CommandError("Trust the phone's key first (Command Center -> Vault -> Phones).")
            now = self._c._clock()
            place = place_for(task["service"])
            slots: set[str] = set()
            checked = []
            for envelope in body["envelopes"]:
                if not isinstance(envelope, dict) or set(envelope) != {"leaseId", "slot", "enc", "ct", "aad"}:
                    raise CommandError("An envelope is {leaseId, slot, enc, ct, aad}.")
                lease_id, slot, enc, ct, aad = (envelope[k] for k in ("leaseId", "slot", "enc", "ct", "aad"))
                if not isinstance(lease_id, str) or not LEASE_ID.match(lease_id) or slot not in SLOTS or slot in slots:
                    raise CommandError("Bad lease id or slot.")
                if abs(int(lease_id[3:14], 16) - now) > 10 * 60_000:
                    raise CommandError("A lease id carries the time it was made; this one is stale.")
                for label, value, size in (("enc", enc, 65), ("ct", ct, None)):
                    if not isinstance(value, str) or not B64.match(value):
                        raise CommandError(f"{label} must be base64.")
                    raw = base64.b64decode(value)
                    if (size and len(raw) != size) or (not size and not 17 <= len(raw) <= 8192):
                        raise CommandError(f"{label} has the wrong size.")
                try:
                    bound = json.loads(aad) if isinstance(aad, str) and len(aad) <= 1000 else None
                except ValueError:
                    bound = None
                expected = {"deviceKey": key["fingerprint"], "leaseId": lease_id, "place": place, "slot": slot, "taskId": task_id}
                if not isinstance(bound, dict) or set(bound) != set(expected) | {"expiresAt"} or any(bound[k] != v for k, v in expected.items()):
                    raise CommandError("The sealed data is not bound to this task, phone, app and slot.")
                expires = bound["expiresAt"]
                if ahead is not None:
                    if ahead["due_at"] > now + MAX_AHEAD_MS or type(expires) is not int or not ahead["due_at"] < expires <= ahead["due_at"] + RUN_WINDOW_MS:
                        raise CommandError("A prepared lease ends within 30 minutes after its run is due, at most 8 days ahead.")
                elif type(expires) is not int or not now + 60_000 < expires <= now + MAX_LEASE_MS:
                    raise CommandError("A lease lasts from one minute to 24 hours.")
                if self._c._db.execute("SELECT 1 FROM lease WHERE id = ?", (lease_id,)).fetchone():
                    raise CommandError("That lease id was already used.")
                slots.add(slot)
                checked.append((lease_id, slot, enc, ct, aad, expires))
            if "password" not in slots and "otp" not in slots:
                raise CommandError("Nothing to deliver.")
            replaced = self._c._db.execute("UPDATE lease SET state = 'replaced', updated_at = ? WHERE task_id = ? AND state = 'ready'",
                                           (now, task_id)).rowcount
            for lease_id, slot, enc, ct, aad, expires in checked:
                self._c._db.execute(
                    "INSERT INTO lease(id, task_id, device_id, vault_item_id, slot, place, fingerprint, enc, ct, aad, expires_at, state, run_id, created_at, updated_at)"
                    " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    (lease_id, task_id, task["device_id"], task["vault_item_id"], slot, place, key["fingerprint"], enc, ct, aad, expires, "ready", None, now, now))
                self._c._audit("owner", "lease.create", lease_id, {"task": task_id, "device": task["device_id"], "slot": slot, "expires": expires})
            # A task waiting for its secret may start at the next tick; its "unlock the vault" request is answered.
            self._c._db.execute("UPDATE task SET next_try_at = ? WHERE id = ?", (now, task_id))
            self._c._db.execute("UPDATE approval SET state = 'withdrawn' WHERE task_id = ? AND kind = 'login' AND state = 'open'", (task_id,))
            return {"taskId": task_id, "leases": [self._lease_public(r) for r in self._c._db.execute("SELECT * FROM lease WHERE task_id = ? ORDER BY created_at", (task_id,))], "replaced": replaced}

    def revoke(self, lease_id: str) -> dict[str, Any]:
        with self._c._lock:
            n = self._c._db.execute("UPDATE lease SET state = 'revoked', updated_at = ? WHERE id = ? AND state = 'ready'", (self._c._clock(), lease_id)).rowcount
            if not n:
                raise CommandError("Only a lease that has not been sent can be revoked; stop the task to end one in use.")
            self._c._audit("owner", "lease.revoke", lease_id)
            return {"id": lease_id, "state": "revoked"}

    def leases(self, limit: int = 200) -> list[dict[str, Any]]:
        with self._c._lock:
            self._expire(self._c._clock())
            return [self._lease_public(r) for r in self._c._db.execute("SELECT * FROM lease ORDER BY created_at DESC LIMIT ?", (max(1, min(limit, 1000)),))]

    @staticmethod
    def _lease_public(r: Any) -> dict[str, Any]:
        return {"id": r["id"], "taskId": r["task_id"], "deviceId": r["device_id"], "vaultItemId": r["vault_item_id"], "slot": r["slot"],
                "place": r["place"], "fingerprint": r["fingerprint"], "expiresAt": r["expires_at"], "state": r["state"],
                "runId": r["run_id"], "createdAt": r["created_at"], "updatedAt": r["updated_at"]}

    def _expire(self, now: int) -> None:
        for r in self._c._db.execute("SELECT id FROM lease WHERE state = 'ready' AND expires_at <= ?", (now,)).fetchall():
            self._c._db.execute("UPDATE lease SET state = 'expired', updated_at = ? WHERE id = ?", (now, r["id"]))
            self._c._audit("engine", "lease.expired", r["id"])

    # ------------------------------------------------------------------ the job loop's side

    def envelopes_for(self, task_id: str) -> list[dict[str, Any]]:
        return [{"leaseId": r["id"], "slot": r["slot"], "enc": r["enc"], "ct": r["ct"], "aad": r["aad"]} for r in self.ready_leases(task_id)]

    def mark_delivered(self, task_id: str, run_id: str, lease_ids: list[str]) -> None:
        now = self._c._clock()
        for lease_id in lease_ids:
            self._c._db.execute("UPDATE lease SET state = 'delivered', run_id = ?, updated_at = ? WHERE id = ? AND state = 'ready'", (run_id, now, lease_id))
            self._c._audit("engine", "lease.delivered", lease_id, {"task": task_id, "run": run_id})

    def mark_rejected(self, task_id: str) -> None:
        now = self._c._clock()
        for r in self.ready_leases(task_id):
            self._c._db.execute("UPDATE lease SET state = 'rejected', updated_at = ? WHERE id = ?", (now, r["id"]))
            self._c._audit("phone", "lease.rejected", r["id"], {"task": task_id})

    def report(self, run_id: str, outcomes: list[dict[str, Any]]) -> None:
        now = self._c._clock()
        for item in outcomes:
            state = item.get("state")
            if state not in ("used", "failed", "unused", "expired"):
                continue
            n = self._c._db.execute("UPDATE lease SET state = ?, updated_at = ? WHERE id = ? AND run_id = ? AND state = 'delivered'",
                                    (state, now, item.get("leaseId"), run_id)).rowcount
            if n:
                self._c._audit("phone", f"lease.{state}", str(item.get("leaseId")), {"run": run_id})

    def finish_run(self, run_id: str) -> None:
        """A run ended: a lease the phone never reported on is counted unused (the phone wiped it with the mission)."""
        self._c._db.execute("UPDATE lease SET state = 'unused', updated_at = ? WHERE run_id = ? AND state = 'delivered'", (self._c._clock(), run_id))
