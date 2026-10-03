"""Alpha.102 Numbers: every phone number Cyclone can receive codes on, in one place.

Where a number's texts arrive (its origin):
- **phone:** a SIM in one of the fleet's phones, or a number the owner confirmed on that phone (Settings → Codes). The
  phone reports it (`numbers.list`); the owner can't add or remove these here, only label, pause and assign them;
- **plugin:** a Cyclone Ports plugin that forwards texts for it (an SMS forwarder on another phone);
- **rental:** a number the owner rents for a longer term from a provider, until a date. Its texts reach Cyclone
  through a plugin, when one is set;
- **other:** a number the owner keeps track of here only.

Rules (code, not a model):
- **The owner manages numbers.** Agents read which number an account uses; nothing here buys, rents or releases one.
- **One number, one account,** and an account has at most one number.
- **Numbers only:** no text, sender or code is ever stored or shown here.
"""
from __future__ import annotations

import re
import secrets
import sqlite3
import threading
import time
from pathlib import Path
from typing import Any, Callable

ORIGINS = ("phone", "plugin", "rental", "other")
NUMBER = re.compile(r"^\+?[0-9]{6,15}$")
SOON_MS = 7 * 86_400_000
PHONE_CACHE_MS = 60_000
MAX_NUMBERS = 500

SCHEMA = """
CREATE TABLE IF NOT EXISTS number (
  id TEXT PRIMARY KEY, number TEXT NOT NULL, key TEXT NOT NULL UNIQUE, label TEXT NOT NULL, origin TEXT NOT NULL,
  origin_ref TEXT NOT NULL, provider TEXT NOT NULL, slot INTEGER, paused INTEGER NOT NULL, account_id TEXT UNIQUE,
  expires_at INTEGER, seen_at INTEGER, notes TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL);
"""


class NumbersError(Exception):
    def __init__(self, message: str, *, code: str = "INVALID_REQUEST") -> None:
        super().__init__(message)
        self.code = code
        self.message = message


def now_ms() -> int:
    return int(time.time() * 1000)


def clean_number(raw: Any) -> str:
    """'+31 6 1234 5678' → '+31612345678'. A number is 6 to 15 digits, with an optional leading +."""
    if not isinstance(raw, str):
        raise NumbersError("A number is text, like +31 6 1234 5678.")
    text = raw.strip()
    digits = re.sub(r"[^0-9]", "", text)
    clean = ("+" if text.startswith("+") else "") + digits
    if not NUMBER.match(clean) or re.search(r"[^0-9+\s().-]", text):
        raise NumbersError("That isn't a phone number: use 6 to 15 digits, like +31 6 1234 5678.")
    return clean


def number_key(number: str) -> str:
    """The same number written two ways (+31 6… and 06…) is one number: its last 9 digits."""
    return re.sub(r"[^0-9]", "", number)[-9:]


def _text(value: Any, limit: int, what: str, *, required: bool = False) -> str:
    if value is None:
        value = ""
    if not isinstance(value, str):
        raise NumbersError(f"{what} is text.")
    text = " ".join(value.split())
    if required and not text:
        raise NumbersError(f"{what} is required.")
    if len(text) > limit:
        raise NumbersError(f"{what} is at most {limit} characters.")
    return text


class NumbersService:
    def __init__(self, path: Path, *, devices: Callable[[], list[dict[str, Any]]],
                 read_phone: Callable[[str], dict[str, Any]], plugins: Callable[[], list[dict[str, Any]]],
                 accounts: Callable[[], list[dict[str, Any]]], clock: Callable[[], int] = now_ms) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        self._db = sqlite3.connect(str(path), check_same_thread=False)
        self._db.row_factory = sqlite3.Row
        self._db.executescript(SCHEMA)
        self._lock = threading.RLock()
        self._devices = devices
        self._read_phone = read_phone
        self._plugins = plugins
        self._accounts = accounts
        self._clock = clock
        # deviceId → (read at, the phone's report or None when it couldn't be read, why not)
        self._phones: dict[str, tuple[int, dict[str, Any] | None, str]] = {}

    def close(self) -> None:
        with self._lock:
            self._db.close()

    # ---- the overview --------------------------------------------------------------------------------------------

    def overview(self, *, refresh: bool = False) -> dict[str, Any]:
        now = self._clock()
        devices = self._safe(self._devices)
        phones = self._read_phones(devices, now, refresh)
        plugins = [p for p in self._safe(self._plugins) if self._serves_codes(p)]
        plugin_state = {p["name"]: p.get("status", "") for p in plugins}
        accounts = {a["id"]: a for a in self._safe(self._accounts) if isinstance(a, dict) and a.get("id")}
        with self._lock:
            rows = self._db.execute("SELECT * FROM number ORDER BY origin, label, number").fetchall()
        names = {str(d.get("deviceId")): str(d.get("name") or d.get("model") or "Phone") for d in devices}
        numbers = [self._public(r, now, phones, plugin_state, accounts, names) for r in rows]
        summary = {
            "total": len(numbers),
            "byOrigin": {o: sum(1 for n in numbers if n["origin"] == o) for o in ORIGINS},
            "ready": sum(1 for n in numbers if n["state"] == "ready"),
            "attention": sum(1 for n in numbers if n["state"] not in ("ready", "paused")),
            "expiringSoon": sum(1 for n in numbers if n["expiresSoon"]),
            "unassigned": sum(1 for n in numbers if n["account"] is None),
        }
        return {
            "summary": summary,
            "numbers": numbers,
            "phones": [self._phone_public(d, phones.get(str(d.get("deviceId")))) for d in devices if d.get("paired")],
            "plugins": [{"name": p["name"], "status": p.get("status", ""), "title": str(p.get("title") or p["name"])[:80]} for p in plugins],
            "accounts": [{"id": a["id"], "service": a.get("service", ""), "handle": a.get("handle", "")} for a in accounts.values()],
            "at": now,
        }

    @staticmethod
    def _safe(fn: Callable[[], list[dict[str, Any]]]) -> list[dict[str, Any]]:
        try:
            value = fn()
        except Exception:  # noqa: BLE001 - one missing source never empties the page
            return []
        return [v for v in value if isinstance(v, dict)] if isinstance(value, list) else []

    @staticmethod
    def _serves_codes(plugin: dict[str, Any]) -> bool:
        return any(isinstance(s, dict) and s.get("port") == "code.in" and s.get("allowed") for s in plugin.get("serves") or [])

    def _read_phones(self, devices: list[dict[str, Any]], now: int, refresh: bool) -> dict[str, tuple[int, dict[str, Any] | None, str]]:
        for device in devices:
            device_id = str(device.get("deviceId") or "")
            if not device_id or not device.get("paired"):
                continue
            if device.get("state") != "ready":
                cached = self._phones.get(device_id)
                self._phones[device_id] = (cached[0], cached[1], "offline") if cached else (now, None, "offline")
                continue
            cached = self._phones.get(device_id)
            if cached and not refresh and now - cached[0] < PHONE_CACHE_MS and cached[2] == "":
                continue
            try:
                report = self._read_phone(device_id)
                self._phones[device_id] = (now, report, "")
                self._sync_phone(device_id, report, now)
            except Exception:  # noqa: BLE001 - an older phone app or a busy phone: show why, keep the last numbers
                self._phones[device_id] = (now, cached[1] if cached else None, "unreadable")
        return self._phones

    def _sync_phone(self, device_id: str, report: dict[str, Any], now: int) -> None:
        """The phone's numbers become rows (origin phone). A number the owner registered elsewhere moves to this phone."""
        with self._lock:
            for row in report.get("numbers") or []:
                number = row["number"]
                key = number_key(number)
                existing = self._db.execute("SELECT * FROM number WHERE key = ?", (key,)).fetchone()
                if existing is None:
                    self._db.execute(
                        "INSERT INTO number(id, number, key, label, origin, origin_ref, provider, slot, paused, account_id, expires_at,"
                        " seen_at, notes, created_at, updated_at) VALUES (?, ?, ?, '', 'phone', ?, '', ?, 0, NULL, NULL, ?, '', ?, ?)",
                        (self._new_id(), number, key, device_id, row.get("slot"), now, now, now))
                else:
                    self._db.execute("UPDATE number SET origin = 'phone', origin_ref = ?, slot = ?, seen_at = ?, updated_at = ?,"
                                     " number = CASE WHEN length(number) < length(?) THEN ? ELSE number END WHERE id = ?",
                                     (device_id, row.get("slot"), now, now, number, number, existing["id"]))
            self._db.commit()

    def _phone_public(self, device: dict[str, Any], read: tuple[int, dict[str, Any] | None, str] | None) -> dict[str, Any]:
        report = read[1] if read else None
        why = read[2] if read else "offline"
        if why == "offline":
            state, hint = "offline", "The phone isn't connected right now."
        elif why == "unreadable" or report is None:
            state, hint = "unreadable", "Update Cyclone on this phone to list its numbers here."
        elif not report.get("canRead"):
            state, hint = "no_permission", "On the phone: Settings → Permissions → Codes → allow Read texts."
        elif not report.get("enabled"):
            state, hint = "off", "On the phone: Settings → Permissions → Codes is switched off."
        elif not report.get("numbers"):
            state, hint = "no_number", "The SIM doesn't tell Android its number. Add it on the phone in Settings → Codes."
        else:
            state, hint = "ready", ""
        return {"deviceId": str(device.get("deviceId")), "name": str(device.get("name") or device.get("model") or "Phone"),
                "state": state, "hint": hint, "numbers": len((report or {}).get("numbers") or [])}

    def _public(self, r: sqlite3.Row, now: int, phones: dict[str, Any], plugin_state: dict[str, str],
                accounts: dict[str, dict[str, Any]], names: dict[str, str]) -> dict[str, Any]:
        origin, ref = r["origin"], r["origin_ref"]
        expires = r["expires_at"]
        expired = expires is not None and expires <= now
        soon = expires is not None and not expired and expires - now <= SOON_MS
        if r["paused"]:
            state, why = "paused", "Paused: agents don't use it."
        elif expired:
            state, why = "expired", "The rental ended."
        elif origin == "phone":
            read = phones.get(ref)
            report = read[1] if read else None
            listed = report is not None and any(number_key(n["number"]) == r["key"] for n in report.get("numbers") or [])
            if not read or read[2] == "offline":
                state, why = "offline", f"{names.get(ref, 'The phone')} isn't connected."
            elif report is None or not listed:
                state, why = "missing", f"{names.get(ref, 'The phone')} no longer lists this number."
            elif not report.get("canRead") or not report.get("enabled"):
                state, why = "codes_off", "Codes from texts are off on the phone."
            else:
                state, why = "ready", ""
        elif origin in ("plugin", "rental") and ref:
            status = plugin_state.get(ref)
            if status is None:
                state, why = "no_source", f"The plugin {ref} isn't added or doesn't forward codes."
            elif status != "active":
                state, why = "source_off", f"The plugin {ref} is {status.replace('_', ' ')}."
            else:
                state, why = "ready", ""
        elif origin == "rental":
            state, why = "no_source", "Pick the plugin that forwards this number's texts."
        else:
            state, why = "manual", "Tracked here only; codes don't arrive automatically."
        account = accounts.get(r["account_id"]) if r["account_id"] else None
        return {
            "id": r["id"], "number": r["number"], "label": r["label"], "origin": origin,
            "source": {"kind": origin, "ref": ref or None, "name": names.get(ref) if origin == "phone" else (ref or None),
                       "provider": r["provider"] or None, "slot": r["slot"]},
            "state": state, "why": why, "paused": bool(r["paused"]),
            "expiresAt": expires, "expiresSoon": soon,
            "account": {"id": account["id"], "service": account.get("service", ""), "handle": account.get("handle", "")} if account else None,
            "seenAt": r["seen_at"], "notes": r["notes"], "createdAt": r["created_at"],
        }

    # ---- the owner's changes ---------------------------------------------------------------------------------------

    def add(self, body: Any) -> dict[str, Any]:
        if not isinstance(body, dict):
            raise NumbersError("Send a JSON object.")
        extra = set(body) - {"number", "label", "origin", "source", "provider", "expiresAt", "notes", "accountId"}
        if extra:
            raise NumbersError(f"Unknown field: {sorted(extra)[0]}.")
        number = clean_number(body.get("number"))
        origin = body.get("origin")
        if origin not in ("plugin", "rental", "other"):
            raise NumbersError("Where do its texts arrive? origin is plugin, rental or other. A phone's own numbers appear by themselves.")
        source = _text(body.get("source"), 64, "The plugin")
        provider = _text(body.get("provider"), 40, "The provider")
        if origin == "plugin" and not source:
            raise NumbersError("Pick the plugin that forwards this number's texts.")
        if origin == "rental" and not provider:
            raise NumbersError("Name the provider you rent this number from.")
        expires = self._expiry(body.get("expiresAt"), origin)
        label = _text(body.get("label"), 60, "The label")
        notes = _text(body.get("notes"), 300, "Notes")
        now = self._clock()
        with self._lock:
            if self._db.execute("SELECT COUNT(*) FROM number").fetchone()[0] >= MAX_NUMBERS:
                raise NumbersError(f"At most {MAX_NUMBERS} numbers.")
            if self._db.execute("SELECT 1 FROM number WHERE key = ?", (number_key(number),)).fetchone():
                raise NumbersError("That number is already here.", code="CONFLICT")
            number_id = self._new_id()
            self._db.execute(
                "INSERT INTO number(id, number, key, label, origin, origin_ref, provider, slot, paused, account_id, expires_at,"
                " seen_at, notes, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, NULL, 0, NULL, ?, NULL, ?, ?, ?)",
                (number_id, number, number_key(number), label, origin, source, provider, expires, notes, now, now))
            self._db.commit()
        if body.get("accountId"):
            self.update(number_id, {"accountId": body["accountId"]})
        return self._one(number_id)

    def update(self, number_id: str, body: Any) -> dict[str, Any]:
        if not isinstance(body, dict):
            raise NumbersError("Send a JSON object.")
        extra = set(body) - {"label", "paused", "accountId", "expiresAt", "source", "provider", "notes"}
        if extra:
            raise NumbersError(f"Unknown field: {sorted(extra)[0]}.")
        with self._lock:
            row = self._row(number_id)
            sets: dict[str, Any] = {}
            if "label" in body:
                sets["label"] = _text(body["label"], 60, "The label")
            if "notes" in body:
                sets["notes"] = _text(body["notes"], 300, "Notes")
            if "paused" in body:
                if not isinstance(body["paused"], bool):
                    raise NumbersError("paused is true or false.")
                sets["paused"] = int(body["paused"])
            if "source" in body or "provider" in body or "expiresAt" in body:
                if row["origin"] == "phone":
                    raise NumbersError("A phone's own number comes from the phone; its source can't change here.")
                if "source" in body:
                    sets["origin_ref"] = _text(body["source"], 64, "The plugin")
                if "provider" in body:
                    sets["provider"] = _text(body["provider"], 40, "The provider")
                if "expiresAt" in body:
                    sets["expires_at"] = self._expiry(body["expiresAt"], row["origin"])
            if "accountId" in body:
                sets["account_id"] = self._assignable(body["accountId"], number_id)
            if sets:
                sets["updated_at"] = self._clock()
                self._db.execute(f"UPDATE number SET {', '.join(f'{k} = ?' for k in sets)} WHERE id = ?", (*sets.values(), number_id))
                self._db.commit()
        return self._one(number_id)

    def remove(self, number_id: str) -> dict[str, Any]:
        with self._lock:
            row = self._row(number_id)
            if row["origin"] == "phone":
                raise NumbersError("A phone's own number is listed by the phone. Remove it there (Settings → Codes), or pause it here.")
            self._db.execute("DELETE FROM number WHERE id = ?", (number_id,))
            self._db.commit()
        return {"id": number_id, "removed": True}

    def number_for_account(self, account_id: str) -> dict[str, Any] | None:
        """For agents and Account Setup: the number an account uses (read only), or None."""
        with self._lock:
            row = self._db.execute("SELECT id FROM number WHERE account_id = ?", (account_id,)).fetchone()
        return self._one(row["id"]) if row else None

    # ---- helpers --------------------------------------------------------------------------------------------------

    def _assignable(self, account_id: Any, number_id: str) -> str | None:
        if account_id is None or account_id == "":
            return None
        if not isinstance(account_id, str):
            raise NumbersError("accountId is an account id or null.")
        if account_id not in {a.get("id") for a in self._safe(self._accounts)}:
            raise NumbersError("No such account.", code="NOT_FOUND")
        taken = self._db.execute("SELECT number FROM number WHERE account_id = ? AND id != ?", (account_id, number_id)).fetchone()
        if taken:
            raise NumbersError(f"That account already uses {taken['number']}. One account, one number.", code="CONFLICT")
        return account_id

    def _expiry(self, value: Any, origin: str) -> int | None:
        if value is None:
            return None
        if origin != "rental":
            raise NumbersError("Only a rented number has an end date.")
        if type(value) is not int or value <= 0:
            raise NumbersError("expiresAt is a time in milliseconds.")
        return value

    def _row(self, number_id: str) -> sqlite3.Row:
        row = self._db.execute("SELECT * FROM number WHERE id = ?", (number_id,)).fetchone() if isinstance(number_id, str) else None
        if row is None:
            raise NumbersError("No such number.", code="NOT_FOUND")
        return row

    def _one(self, number_id: str) -> dict[str, Any]:
        for item in self.overview()["numbers"]:
            if item["id"] == number_id:
                return item
        raise NumbersError("No such number.", code="NOT_FOUND")

    @staticmethod
    def _new_id() -> str:
        return "num_" + secrets.token_hex(6)
