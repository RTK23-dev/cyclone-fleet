"""The Command Center vault (plan 33, C1): a zero-knowledge store. It keeps ciphertext only.

Glass encrypts everything in the browser before it reaches this module:
- the vault key (VK) is random, wrapped once with a key derived from the owner's passphrase (PBKDF2-SHA256) and once
  with a random recovery key, both with AES-256-GCM;
- every item has its own random key, wrapped with the VK, and its fields are encrypted with that key, AES-256-GCM,
  with the item id, kind and version bound in as associated data.

This module checks shapes and sizes, keeps versions monotonic and writes the audit chain. It never receives, holds or
logs a passphrase, a key or an item's plaintext, and it cannot decrypt anything. Only the kind of an item and the
account it belongs to are plain metadata.
"""
from __future__ import annotations

import base64
import json
import re
from typing import TYPE_CHECKING, Any

if TYPE_CHECKING:  # pragma: no cover
    from .center import CommandCenter

from .center import ID, CommandError

VAULT_SCHEMA = """
CREATE TABLE IF NOT EXISTS vault_meta (
  id INTEGER PRIMARY KEY CHECK (id = 1), format INTEGER NOT NULL, kdf TEXT NOT NULL, salt TEXT NOT NULL,
  wrapped_vk TEXT NOT NULL, recovery_wrapped_vk TEXT NOT NULL, key_version INTEGER NOT NULL,
  created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS vault_item (
  id TEXT PRIMARY KEY, account_id TEXT, kind TEXT NOT NULL, iv TEXT NOT NULL, ciphertext TEXT NOT NULL,
  wrapped_item_key TEXT NOT NULL, version INTEGER NOT NULL, created_by TEXT NOT NULL, created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS vault_item_account ON vault_item(account_id);
"""

FORMAT = 1
KINDS = ("login", "totp", "recovery_codes", "note")
PROVENANCE = ("owner", "import", "restore")
ITEM_ID = re.compile(r"^vi_[A-Za-z0-9_-]{16,40}$")
B64 = re.compile(r"^[A-Za-z0-9+/]+={0,2}$")
MIN_ITERATIONS = 600_000
MAX_ITERATIONS = 10_000_000
MAX_ITEMS = 5_000
MAX_CIPHERTEXT = 64 * 1024
CLIENT_AUDIT = ("unlock", "unlock_failed", "unlock_recovery", "reveal", "copy", "export", "import", "lock")


def _b64(value: Any, label: str, *, exact: int | None = None, max_bytes: int = 256) -> str:
    if not isinstance(value, str) or not value or len(value) > (max_bytes * 4) // 3 + 4 or not B64.match(value):
        raise CommandError(f"{label} must be base64.")
    try:
        raw = base64.b64decode(value, validate=True)
    except ValueError as exc:
        raise CommandError(f"{label} must be base64.") from exc
    if exact is not None and len(raw) != exact:
        raise CommandError(f"{label} must be {exact} bytes.")
    if len(raw) > max_bytes:
        raise CommandError(f"{label} is too large.")
    return value


def _sealed(value: Any, label: str, *, max_bytes: int = 256) -> str:
    """An AES-GCM box as the browser sends it: {iv, ct}. Stored as compact JSON."""
    if not isinstance(value, dict) or set(value) != {"iv", "ct"}:
        raise CommandError(f"{label} is {{iv, ct}}.")
    iv = _b64(value["iv"], f"{label}.iv", exact=12)
    ct = _b64(value["ct"], f"{label}.ct", max_bytes=max_bytes)
    if len(base64.b64decode(ct)) < 17:
        raise CommandError(f"{label}.ct is too short to be sealed.")
    return json.dumps({"iv": iv, "ct": ct}, separators=(",", ":"))


def _kdf(value: Any) -> str:
    if not isinstance(value, dict) or set(value) != {"name", "hash", "iterations"}:
        raise CommandError("kdf is {name, hash, iterations}.")
    if value["name"] != "PBKDF2" or value["hash"] != "SHA-256":
        raise CommandError("kdf is PBKDF2 with SHA-256.")
    iterations = value["iterations"]
    if type(iterations) is not int or not MIN_ITERATIONS <= iterations <= MAX_ITERATIONS:
        raise CommandError(f"kdf iterations are {MIN_ITERATIONS} or more.")
    return json.dumps({"name": "PBKDF2", "hash": "SHA-256", "iterations": iterations}, separators=(",", ":"))


def _only(body: Any, allowed: set[str], required: set[str] | None = None) -> dict[str, Any]:
    if not isinstance(body, dict):
        raise CommandError("Send a JSON object.")
    extra = set(body) - allowed
    if extra:
        raise CommandError(f"Unknown field: {sorted(extra)[0]}. The vault takes ciphertext only.")
    missing = (required if required is not None else allowed) - set(body)
    if missing:
        raise CommandError(f"Missing field: {sorted(missing)[0]}.")
    return body


class VaultStore:
    def __init__(self, center: "CommandCenter") -> None:
        self._c = center
        with center._lock:
            center._db.executescript(VAULT_SCHEMA)

    # ------------------------------------------------------------------ read

    def get(self) -> dict[str, Any]:
        with self._c._lock:
            meta = self._c._db.execute("SELECT * FROM vault_meta WHERE id = 1").fetchone()
            if meta is None:
                return {"exists": False, "format": FORMAT, "minIterations": MIN_ITERATIONS}
            items = self._c._db.execute("SELECT * FROM vault_item ORDER BY created_at").fetchall()
            return {
                "exists": True,
                "format": meta["format"],
                "minIterations": MIN_ITERATIONS,
                "meta": {
                    "kdf": json.loads(meta["kdf"]), "salt": meta["salt"], "wrappedVk": json.loads(meta["wrapped_vk"]),
                    "recoveryWrappedVk": json.loads(meta["recovery_wrapped_vk"]), "keyVersion": meta["key_version"],
                    "createdAt": meta["created_at"], "updatedAt": meta["updated_at"],
                },
                "items": [self._item_public(r) for r in items],
            }

    @staticmethod
    def _item_public(r: Any) -> dict[str, Any]:
        return {
            "id": r["id"], "accountId": r["account_id"], "kind": r["kind"], "iv": r["iv"], "ct": r["ciphertext"],
            "wrappedKey": json.loads(r["wrapped_item_key"]), "version": r["version"], "createdBy": r["created_by"],
            "createdAt": r["created_at"], "updatedAt": r["updated_at"],
        }

    def counts_by_account(self) -> dict[str, int]:
        rows = self._c._db.execute("SELECT account_id, COUNT(*) n FROM vault_item WHERE account_id IS NOT NULL GROUP BY account_id")
        return {r["account_id"]: r["n"] for r in rows}

    # ------------------------------------------------------------------ keys

    def init(self, body: Any) -> dict[str, Any]:
        body = _only(body, {"kdf", "salt", "wrappedVk", "recoveryWrappedVk"})
        kdf = _kdf(body["kdf"])
        salt = _b64(body["salt"], "salt", exact=16)
        wrapped = _sealed(body["wrappedVk"], "wrappedVk", max_bytes=64)
        recovery = _sealed(body["recoveryWrappedVk"], "recoveryWrappedVk", max_bytes=64)
        with self._c._lock:
            if self._c._db.execute("SELECT 1 FROM vault_meta").fetchone():
                raise CommandError("A vault already exists on this PC.")
            now = self._c._clock()
            self._c._db.execute(
                "INSERT INTO vault_meta(id, format, kdf, salt, wrapped_vk, recovery_wrapped_vk, key_version, created_at, updated_at)"
                " VALUES (1,?,?,?,?,?,?,?,?)", (FORMAT, kdf, salt, wrapped, recovery, 1, now, now))
            self._c._audit("owner", "vault.create", "vault", {"kdf": json.loads(kdf)})
            return self.get()

    def rewrap(self, body: Any) -> dict[str, Any]:
        """A new passphrase (or new recovery key): the same VK, wrapped again. Items are untouched."""
        body = _only(body, {"kdf", "salt", "wrappedVk", "recoveryWrappedVk", "keyVersion"}, {"keyVersion"})
        with self._c._lock:
            meta = self._c._db.execute("SELECT * FROM vault_meta WHERE id = 1").fetchone()
            if meta is None:
                raise CommandError("There is no vault yet.")
            if body["keyVersion"] != meta["key_version"]:
                raise CommandError("The vault changed in another window. Reload and try again.")
            sets: dict[str, Any] = {}
            if "wrappedVk" in body:
                if "kdf" not in body or "salt" not in body:
                    raise CommandError("A new passphrase needs its kdf and salt.")
                sets.update(kdf=_kdf(body["kdf"]), salt=_b64(body["salt"], "salt", exact=16),
                            wrapped_vk=_sealed(body["wrappedVk"], "wrappedVk", max_bytes=64))
            elif "kdf" in body or "salt" in body:
                raise CommandError("kdf and salt come with wrappedVk.")
            if "recoveryWrappedVk" in body:
                sets["recovery_wrapped_vk"] = _sealed(body["recoveryWrappedVk"], "recoveryWrappedVk", max_bytes=64)
            if not sets:
                raise CommandError("Nothing to change.")
            columns = ", ".join(f"{k} = ?" for k in sets)
            self._c._db.execute(f"UPDATE vault_meta SET {columns}, key_version = key_version + 1, updated_at = ? WHERE id = 1",
                                (*sets.values(), self._c._clock()))
            self._c._audit("owner", "vault.rewrap", "vault", {"passphrase": "wrappedVk" in body, "recovery": "recoveryWrappedVk" in body})
            return self.get()

    # ------------------------------------------------------------------ items

    def put_item(self, body: Any, *, created_by: str = "owner") -> dict[str, Any]:
        body = _only(body, {"id", "accountId", "kind", "iv", "ct", "wrappedKey", "version"})
        item_id = body["id"]
        if not isinstance(item_id, str) or not ITEM_ID.match(item_id):
            raise CommandError("id is vi_ plus 16..40 letters, digits, dash or underscore (made in the browser).")
        kind = body["kind"]
        if kind not in KINDS:
            raise CommandError(f"kind is one of {', '.join(KINDS)}.")
        account = body["accountId"]
        if account is not None and (not isinstance(account, str) or not ID.match(account)):
            raise CommandError("accountId is malformed.")
        iv = _b64(body["iv"], "iv", exact=12)
        ct = _b64(body["ct"], "ct", max_bytes=MAX_CIPHERTEXT)
        wrapped = _sealed(body["wrappedKey"], "wrappedKey", max_bytes=64)
        version = body["version"]
        if type(version) is not int or version < 1:
            raise CommandError("version starts at 1 and goes up by one per change.")
        if created_by not in PROVENANCE:
            raise CommandError("Unknown provenance.")
        with self._c._lock:
            if self._c._db.execute("SELECT 1 FROM vault_meta").fetchone() is None:
                raise CommandError("Create the vault first.")
            if account is not None:
                self._c.get_account(account)
            now = self._c._clock()
            current = self._c._db.execute("SELECT * FROM vault_item WHERE id = ?", (item_id,)).fetchone()
            if current is None:
                if version != 1:
                    raise CommandError("A new item starts at version 1.")
                if self._c._db.execute("SELECT COUNT(*) FROM vault_item").fetchone()[0] >= MAX_ITEMS:
                    raise CommandError(f"The vault holds up to {MAX_ITEMS} items.")
                self._c._db.execute(
                    "INSERT INTO vault_item(id, account_id, kind, iv, ciphertext, wrapped_item_key, version, created_by, created_at, updated_at)"
                    " VALUES (?,?,?,?,?,?,?,?,?,?)", (item_id, account, kind, iv, ct, wrapped, 1, created_by, now, now))
                self._c._audit(created_by, "vault.item.create", item_id, {"kind": kind, "account": account})
            else:
                if version != current["version"] + 1:
                    raise CommandError("The item changed in another window. Reload and try again.")
                if kind != current["kind"]:
                    raise CommandError("An item keeps its kind.")
                self._c._db.execute(
                    "UPDATE vault_item SET account_id = ?, iv = ?, ciphertext = ?, wrapped_item_key = ?, version = ?, updated_at = ? WHERE id = ?",
                    (account, iv, ct, wrapped, version, now, item_id))
                self._c._audit("owner", "vault.item.update", item_id, {"kind": kind, "account": account, "version": version})
            return self._item_public(self._c._db.execute("SELECT * FROM vault_item WHERE id = ?", (item_id,)).fetchone())

    def delete_item(self, item_id: str) -> dict[str, Any]:
        with self._c._lock:
            if self._c._db.execute("DELETE FROM vault_item WHERE id = ?", (item_id,)).rowcount == 0:
                raise CommandError("No such vault item.")
            self._c._audit("owner", "vault.item.delete", item_id)
            return {"id": item_id, "deleted": True}

    # ------------------------------------------------------------------ backup, restore, reset

    def restore(self, body: Any) -> dict[str, Any]:
        """Put an encrypted backup (Glass's download of GET /v1/cc/vault) into an empty vault. Still ciphertext only."""
        body = _only(body, {"backup"})
        backup = body["backup"]
        if not isinstance(backup, dict) or backup.get("exists") is not True or backup.get("format") != FORMAT:
            raise CommandError("That file is not a Cyclone vault backup.")
        meta = backup.get("meta")
        items = backup.get("items")
        if not isinstance(meta, dict) or not isinstance(items, list) or len(items) > MAX_ITEMS:
            raise CommandError("That backup is damaged.")
        with self._c._lock:
            if self._c._db.execute("SELECT 1 FROM vault_meta").fetchone():
                raise CommandError("This PC already has a vault. A backup restores only into an empty vault.")
            known = {r["id"] for r in self._c._db.execute("SELECT id FROM account")}
            try:
                self._c._db.execute("BEGIN")
                self.init({k: meta.get(k) for k in ("kdf", "salt", "wrappedVk", "recoveryWrappedVk")})
                for item in items:
                    if not isinstance(item, dict):
                        raise CommandError("That backup is damaged.")
                    fields = {k: item.get(k) for k in ("id", "accountId", "kind", "iv", "ct", "wrappedKey")}
                    # Accounts do not travel in a vault backup: an item whose account is not on this PC is unlinked.
                    if fields["accountId"] not in known:
                        fields["accountId"] = None
                    version = item.get("version")
                    self.put_item({**fields, "version": 1}, created_by="restore")
                    if type(version) is int and version > 1:
                        # The AAD binds the version the browser encrypted with, so it is kept as it was.
                        self._c._db.execute("UPDATE vault_item SET version = ? WHERE id = ?", (version, fields["id"]))
                self._c._db.execute("COMMIT")
            except Exception:
                self._c._db.execute("ROLLBACK")
                raise
            self._c._audit("owner", "vault.restore", "vault", {"items": len(items)})
            return self.get()

    def reset(self, body: Any) -> dict[str, Any]:
        body = _only(body, {"confirm"})
        if body["confirm"] != "DELETE VAULT":
            raise CommandError('Type DELETE VAULT to delete the vault and every item in it.')
        with self._c._lock:
            n = self._c._db.execute("SELECT COUNT(*) FROM vault_item").fetchone()[0]
            self._c._db.execute("DELETE FROM vault_item")
            self._c._db.execute("DELETE FROM vault_meta")
            self._c._audit("owner", "vault.reset", "vault", {"items": n})
            return self.get()

    def client_audit(self, body: Any) -> dict[str, Any]:
        """What only the browser knows happened (an unlock, a reveal, a copy). Names and ids only."""
        body = _only(body, {"action", "itemId"}, {"action"})
        action = body["action"]
        if action not in CLIENT_AUDIT:
            raise CommandError(f"action is one of {', '.join(CLIENT_AUDIT)}.")
        item = body.get("itemId")
        if item is not None and (not isinstance(item, str) or not ITEM_ID.match(item)):
            raise CommandError("itemId is malformed.")
        with self._c._lock:
            self._c._audit("owner", f"vault.{action}", item or "vault")
            return {"recorded": True}
