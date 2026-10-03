"""Accounts, rebuilt (plan 43 T5 + T6): phones, their apps, the accounts in each app, and the sign-ups Cyclone mapped.

- **Maps come from the phone.** Cyclone Mobile walks an app's sign-up once (a mapping task) and keeps its Sign-up
  Map: pages, field labels and kinds, format hints, choices and the steps only a person can do. The PC keeps a copy
  so Glass can show it while the phone is away. A map is a template: it never holds a value (checked here and on the
  phone).
- **A map becomes a table.** Each field becomes a column, so the next accounts are rows the owner fills in. Passwords
  are never a column: the phone makes them in the vault.
- **Only accounts the owner owns or manages.** A mapping task says whose account it is (mine, company, client), and
  the final control that creates the account always asks the owner on the phone.
"""
from __future__ import annotations

import json
from typing import TYPE_CHECKING, Any

from ..desktop_runtime.models import DesktopRuntimeError
from ..desktop_runtime.v5_contract import SIGNUP_PACKAGE, _secret_name, validate_signup_map
from .center import CommandError
from .pages import _only

if TYPE_CHECKING:  # pragma: no cover
    from .center import CommandCenter

SIGNUP_SCHEMA = """
CREATE TABLE IF NOT EXISTS signup_map (
  device_id TEXT NOT NULL, package TEXT NOT NULL, map TEXT NOT NULL, fetched_at INTEGER NOT NULL, PRIMARY KEY(device_id, package));
CREATE TABLE IF NOT EXISTS signup_table (
  device_id TEXT NOT NULL, package TEXT NOT NULL, table_id TEXT NOT NULL, mapped_at INTEGER NOT NULL, PRIMARY KEY(device_id, package));
CREATE TABLE IF NOT EXISTS signup_column (
  table_id TEXT NOT NULL, prop_id TEXT NOT NULL, field_key TEXT NOT NULL, kind TEXT NOT NULL, PRIMARY KEY(table_id, prop_id));
CREATE TABLE IF NOT EXISTS signup_run (
  task_id TEXT PRIMARY KEY, table_id TEXT NOT NULL, row_id TEXT NOT NULL, device_id TEXT NOT NULL, package TEXT NOT NULL,
  account_id TEXT NOT NULL, vault_item_id TEXT, state TEXT NOT NULL, setup TEXT, paused INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS signup_run_row ON signup_run(table_id, row_id);
"""

RECIPE = "signup_map:"
RUN_RECIPE = "signup_run:"
BASES = {"mine": "the owner's own", "company": "the owner's company's", "client": "a client's (the owner manages it)"}
#: A sign-up field kind → a table property (type, personal). Passwords and photos are never columns.
COLUMN = {
    "text": ("text", False), "first_name": ("text", True), "last_name": ("text", True), "full_name": ("text", True),
    "email": ("email", True), "phone": ("phone_number", True), "username": ("text", False), "birthday": ("date", True),
    "date": ("date", False), "gender": ("select", True), "choice": ("select", False), "checkbox": ("checkbox", False),
    "number": ("number", False),
}
STATUS = (("Draft", "gray", "todo"), ("Ready", "blue", "todo"), ("Paused", "brown", "todo"), ("Queued", "purple", "doing"),
          ("Creating", "yellow", "doing"), ("Needs verification", "orange", "doing"), ("Created", "green", "done"), ("Failed", "red", "done"))
#: An Account Setup run's task state -> the row's status.
ROW_STATUS = {"scheduled": "Queued", "making": "Queued", "waiting_device": "Queued", "running": "Creating", "needs_you": "Needs verification",
              "succeeded": "Created", "failed": "Failed", "cancelled": "Failed"}
OPEN_RUN = ("queued", "running")
CHECK_LABEL = {"email_code": "email code", "sms_code": "SMS code", "captcha": "CAPTCHA", "selfie": "selfie", "id_document": "ID document",
               "phone_call": "phone call", "other": "a person's step"}


def recipe_package(recipe: Any, prefix: str = RECIPE) -> str | None:
    """The app a task maps (or, with [RUN_RECIPE], creates an account in), when it is a sign-up task."""
    if isinstance(recipe, str) and recipe.startswith(prefix) and SIGNUP_PACKAGE.match(recipe[len(prefix):]):
        return recipe[len(prefix):]
    return None


class SignupStore:
    def __init__(self, center: "CommandCenter") -> None:
        self._c = center
        with center._lock:
            center._db.executescript(SIGNUP_SCHEMA)

    # ------------------------------------------------------------------ maps

    def maps(self, device_id: Any) -> dict[str, Any]:
        """The phone's maps, fetched now; the last copy when the phone can't be reached."""
        device = self._device(device_id)
        fresh = True
        try:
            result = self._c._contract.signup_maps(device)
        except (DesktopRuntimeError, AttributeError):
            fresh, result = False, None
        if result is not None:
            with self._c._lock:
                now = self._c._clock()
                self._c._db.execute("DELETE FROM signup_map WHERE device_id = ?", (device,))
                self._c._db.executemany("INSERT INTO signup_map(device_id, package, map, fetched_at) VALUES (?,?,?,?)",
                                        [(device, m["package"], json.dumps(m, ensure_ascii=False), now) for m in result["maps"]])
        with self._c._lock:
            rows = self._c._db.execute("SELECT * FROM signup_map WHERE device_id = ? ORDER BY package", (device,)).fetchall()
            tables = {r["package"]: r["table_id"] for r in self._c._db.execute("SELECT package, table_id FROM signup_table WHERE device_id = ?", (device,))}
            live_tables = {t["id"] for t in self._c.tables.list()}
        maps = []
        for r in rows:
            item = json.loads(r["map"])
            table = tables.get(r["package"])
            maps.append({**item, "fetchedAt": r["fetched_at"], "tableId": table if table in live_tables else None})
        out: dict[str, Any] = {"deviceId": device, "maps": maps, "fresh": fresh}
        if not fresh:
            out["note"] = "The phone couldn't be reached; these are the last maps it sent."
        return out

    def map_signup(self, body: Any, *, actor: str = "owner") -> dict[str, Any]:
        """Starts a mapping task: the phone walks the app's sign-up once, for the owner's first account there."""
        if not isinstance(body, dict):
            raise CommandError("Send {deviceId, package, app, ownerBasis}.")
        _only(body, {"deviceId", "package", "app", "ownerBasis"}, "sign-up mapping")
        device = self._device(body.get("deviceId"))
        package = body.get("package")
        if not isinstance(package, str) or not SIGNUP_PACKAGE.match(package):
            raise CommandError("package is the app's Android package name.")
        app = body.get("app") if isinstance(body.get("app"), str) and body.get("app", "").strip() else package
        app = app.strip()[:80]
        basis = body.get("ownerBasis")
        if basis not in BASES:
            raise CommandError("Say whose account it is: mine, company or client. Cyclone only makes accounts you own or manage.")
        goal = (f"Map the sign-up of {app} ({package}) for Cyclone's Account Setup. The first account is {BASES[basis]}; "
                "ask the owner for its details on the phone. Record every sign-up page, hand any verification to the owner, "
                "and create the account only after the owner approves the final step.")
        return self._c.create_task({"title": f"Map the sign-up of {app}", "goal": goal, "deviceId": device, "recipe": RECIPE + package},
                                   actor=actor)

    def forget(self, body: Any) -> dict[str, Any]:
        if not isinstance(body, dict):
            raise CommandError("Send {deviceId, package}.")
        _only(body, {"deviceId", "package"}, "forget")
        device = self._device(body.get("deviceId"))
        package = body.get("package")
        if not isinstance(package, str) or not SIGNUP_PACKAGE.match(package):
            raise CommandError("package is the app's Android package name.")
        try:
            self._c._contract.signup_forget(device, package)
        except DesktopRuntimeError as exc:
            raise CommandError(f"The phone couldn't forget it now: {exc}") from exc
        with self._c._lock:
            self._c._db.execute("DELETE FROM signup_map WHERE device_id = ? AND package = ?", (device, package))
            self._c._audit("owner", "signup.forget", package, {"device": device})
        return {"forgotten": True}

    # ------------------------------------------------------------------ the sign-up table

    def make_table(self, body: Any) -> dict[str, Any]:
        """Turns a map into a table: one column per field, plus the phone, whose account it is, and the status."""
        if not isinstance(body, dict):
            raise CommandError("Send {deviceId, package}.")
        _only(body, {"deviceId", "package"}, "sign-up table")
        device = self._device(body.get("deviceId"))
        package = body.get("package")
        with self._c._lock:
            row = self._c._db.execute("SELECT map FROM signup_map WHERE device_id = ? AND package = ?", (device, package)).fetchone()
            existing = self._c._db.execute("SELECT table_id FROM signup_table WHERE device_id = ? AND package = ?", (device, package)).fetchone()
        if row is None:
            raise CommandError("This phone has no sign-up map for that app yet. Map the sign-up first.")
        signup = json.loads(row["map"])
        validate_signup_map(signup)
        if existing is not None and any(t["id"] == existing["table_id"] for t in self._c.tables.list()):
            return self._c.tables.get(existing["table_id"])
        t = self._c.tables
        checks = sorted({CHECK_LABEL[p["check"]] for p in signup["pages"] if p["check"]})
        description = (f"Accounts for Cyclone to create with the {signup['app']} sign-up it mapped ({len(signup['pages'])} pages). "
                       "Fill a row, set it to Ready, and press Create accounts. Each account's password is made in your vault, never "
                       "kept here." + (f" Needs a person for: {', '.join(checks)}." if checks else ""))
        table = t.create({"title": f"{signup['app']} sign-ups", "icon": "🪪", "description": description[:1000]})
        tid = table["id"]
        title = next(p for p in table["properties"] if p["type"] == "title")
        status = next(p for p in table["properties"] if p["type"] == "status")
        t.update_property(tid, title["id"], {"name": "Account"})
        t.update_property(tid, status["id"], {"config": {"options": [{"name": n, "color": c, "group": g} for n, c, g in STATUS]}})
        taken = {"account", "status"}
        for page in signup["pages"]:
            if page["check"]:
                continue  # A person's step (a code, a CAPTCHA) is never a column.
            for field in page["fields"]:
                column = COLUMN.get(field["kind"])
                if column is None:
                    continue
                kind, personal = column
                name = field["label"][:70]
                while name.lower() in taken:
                    name = f"{name} ({field['key']})"[:80]
                taken.add(name.lower())
                config: dict[str, Any] = {"personal": True} if personal else {}
                if kind == "select":
                    config["options"] = [{"name": c[:100]} for c in dict.fromkeys(field["choices"])][:100]
                try:
                    made = t.add_property(tid, {"name": name, "type": kind, "config": config})
                except CommandError:
                    continue  # A label the table refuses (it reads like a secret) stays on the phone's map only.
                prop = next(p for p in made["properties"] if p["name"] == name)
                with self._c._lock:
                    self._c._db.execute("INSERT OR REPLACE INTO signup_column(table_id, prop_id, field_key, kind) VALUES (?,?,?,?)",
                                        (tid, prop["id"], field["key"], field["kind"]))
        t.add_property(tid, {"name": "Whose account", "type": "select", "config": {"options": [
            {"name": "Mine", "color": "blue"}, {"name": "Company", "color": "purple"}, {"name": "Client", "color": "orange"}]}})
        t.add_property(tid, {"name": "Phone", "type": "relation", "config": {"target": "sys:phones"}})
        t.add_property(tid, {"name": "Cyclone account", "type": "relation", "config": {"target": "sys:accounts"}})
        t.add_property(tid, {"name": "Progress", "type": "text"})
        t.add_property(tid, {"name": "Notes", "type": "text"})
        with self._c._lock:
            self._c._db.execute("INSERT OR REPLACE INTO signup_table(device_id, package, table_id, mapped_at) VALUES (?,?,?,?)",
                                (device, package, tid, signup["mappedAt"]))
            self._c._audit("owner", "signup.table", package, {"device": device, "table": tid})
        return t.get(tid)

    # ------------------------------------------------------------------ creating accounts (T7)

    def prepare(self, body: Any) -> dict[str, Any]:
        """Step 1 of Create accounts: the Ready rows, each with its Cyclone account (made now, or the one from an earlier
        try) so Glass can put a fresh password for it in the vault. Nothing runs yet."""
        table_id, rows, sheet = self._ready(body)
        out = []
        for row in rows:
            values = self._values(sheet, row)
            earlier = self._c._db.execute("SELECT account_id, vault_item_id FROM signup_run WHERE table_id = ? AND row_id = ? "
                                          "ORDER BY created_at DESC LIMIT 1", (table_id, row["id"])).fetchone()
            account_id = earlier["account_id"] if earlier else None
            if account_id and not self._c._db.execute("SELECT 1 FROM account WHERE id = ?", (account_id,)).fetchone():
                account_id = None
            handle = self._handle(sheet, row, values)
            try:
                if account_id is None:
                    account_id = self._c.create_account({
                        "service": sheet["package"], "handle": handle, "ownerBasis": self._basis(sheet, row), "twofa": "none",
                        "allowedDevices": [self._device_of(sheet, row)], "notes": f"Being created by Cyclone Account Setup ({sheet['title']})."})["id"]
            except CommandError as exc:
                out.append({"rowId": row["id"], "title": handle, "error": str(exc)})
                continue
            vault = earlier["vault_item_id"] if earlier and earlier["account_id"] == account_id else None
            if vault and not self._c._db.execute("SELECT 1 FROM vault_item WHERE id = ? AND account_id = ?", (vault, account_id)).fetchone():
                vault = None
            out.append({"rowId": row["id"], "title": handle, "accountId": account_id, "vaultItemId": vault,
                        "username": values.get("username") or values.get("email") or handle, "deviceId": self._device_of(sheet, row)})
        return {"tableId": table_id, "app": sheet["app"], "package": sheet["package"], "rows": out}

    def create(self, body: Any) -> dict[str, Any]:
        """Step 2: one task per row, on the row's phone, signing up with the row's values and the vault's password. The
        owner's press is the approval of each account's final create; the phone does not ask again."""
        if not isinstance(body, dict):
            raise CommandError("Send {tableId, rows: [{rowId, accountId, vaultItemId}]}.")
        _only(body, {"tableId", "rows"}, "create accounts")
        items = body.get("rows")
        if not isinstance(items, list) or not 1 <= len(items) <= 200:
            raise CommandError("rows is a list of 1..200 {rowId, accountId, vaultItemId}.")
        table_id, ready, sheet = self._ready({"tableId": body.get("tableId")})
        by_id = {r["id"]: r for r in ready}
        started, errors = [], []
        for item in items:
            if not isinstance(item, dict) or set(item) != {"rowId", "accountId", "vaultItemId"}:
                raise CommandError("Each row is {rowId, accountId, vaultItemId}.")
            row = by_id.get(item["rowId"])
            if row is None:
                errors.append({"rowId": item["rowId"], "error": "That row is not Ready any more."})
                continue
            values = self._values(sheet, row)
            handle = self._handle(sheet, row, values)
            device = self._device_of(sheet, row)
            goal = (f"Create a new {sheet['app']} account on this phone ({handle}) with Cyclone's Account Setup: follow the "
                    "sign-up map with the values given. The owner already approved creating it.")
            try:
                task = self._c.create_task({"title": f"Create {sheet['app']} account: {handle}"[:120], "goal": goal, "deviceId": device,
                                            "accountId": item["accountId"], "vaultItemId": item["vaultItemId"],
                                            "recipe": RUN_RECIPE + sheet["package"]})
            except CommandError as exc:
                errors.append({"rowId": row["id"], "error": str(exc)})
                continue
            now = self._c._clock()
            with self._c._lock:
                self._c._db.execute(
                    "INSERT INTO signup_run(task_id, table_id, row_id, device_id, package, account_id, vault_item_id, state, setup, paused,"
                    " created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    (task["id"], table_id, row["id"], device, sheet["package"], item["accountId"], item["vaultItemId"], "queued", None, 0, now, now))
                self._c._audit("owner", "signup.create", task["id"], {"table": table_id, "row": row["id"], "device": device})
            self._set_row(sheet, row["id"], "Queued", "Waiting for the phone.")
            started.append({"rowId": row["id"], "taskId": task["id"]})
        return {"started": started, "errors": errors}

    def stop(self, body: Any, *, pause: bool) -> dict[str, Any]:
        """Pause (the row goes back to Paused; Ready runs it again from the start) or cancel (Failed) a row's run, or all of
        a table's open runs. After the account exists there is nothing left to stop."""
        if not isinstance(body, dict):
            raise CommandError("Send {tableId, rowIds?}.")
        _only(body, {"tableId", "rowIds"}, "pause" if pause else "cancel")
        table_id = body.get("tableId")
        sheet = self._sheet(table_id)
        rows = body.get("rowIds")
        if rows is not None and (not isinstance(rows, list) or not all(isinstance(r, str) for r in rows)):
            raise CommandError("rowIds is a list of row ids.")
        with self._c._lock:
            runs = self._c._db.execute(f"SELECT * FROM signup_run WHERE table_id = ? AND state IN ({','.join('?' * len(OPEN_RUN))})",
                                       (table_id, *OPEN_RUN)).fetchall()
        stopped = []
        for run in runs:
            if rows is not None and run["row_id"] not in rows:
                continue
            with self._c._lock:
                self._c._db.execute("UPDATE signup_run SET paused = ?, state = ?, updated_at = ? WHERE task_id = ?",
                                    (1 if pause else 0, "paused" if pause else "cancelled", self._c._clock(), run["task_id"]))
            try:
                self._c.cancel_task(run["task_id"])
            except CommandError:
                pass  # it finished in between; the next sync shows how
            self._set_row(sheet, run["row_id"], "Paused" if pause else "Failed",
                          "Paused. Set it to Ready to start again from the beginning." if pause else "Cancelled.")
            stopped.append(run["row_id"])
        return {"stopped": stopped}

    def sync(self) -> None:
        """Called by the engine after following runs: each open run's task state and phone progress into its row."""
        with self._c._lock:
            runs = self._c._db.execute(f"SELECT r.*, t.status AS task_status, t.cause AS task_cause FROM signup_run r JOIN task t ON t.id = r.task_id "
                                       f"WHERE r.state IN ({','.join('?' * len(OPEN_RUN))})", OPEN_RUN).fetchall()
            for run in runs:
                try:
                    sheet = self._sheet(run["table_id"])
                except CommandError:
                    self._c._db.execute("UPDATE signup_run SET state = 'failed' WHERE task_id = ?", (run["task_id"],))
                    continue
                status = run["task_status"]
                setup = json.loads(run["setup"]) if run["setup"] else None
                label = ROW_STATUS.get(status, "Queued")
                if status == "running" and setup and setup["state"] == "verification":
                    label = "Needs verification"
                progress = self._progress(status, setup, run["task_cause"])
                if status in ("succeeded", "failed", "cancelled"):
                    created = status == "succeeded" and (setup is None or setup["state"] != "failed")
                    label = "Created" if created else "Failed"
                    self._c._db.execute("UPDATE signup_run SET state = ?, updated_at = ? WHERE task_id = ?",
                                        ("created" if created else "failed", self._c._clock(), run["task_id"]))
                    if created:
                        self._created(sheet, run, setup)
                elif status in ("running", "needs_you") and run["state"] == "queued":
                    self._c._db.execute("UPDATE signup_run SET state = 'running' WHERE task_id = ?", (run["task_id"],))
                self._set_row(sheet, run["row_id"], label, progress)

    def progress(self, task_id: str, setup: dict[str, Any]) -> None:
        """The phone's Account Setup progress for a run (page, a changed page, the handle once made)."""
        with self._c._lock:
            self._c._db.execute("UPDATE signup_run SET setup = ?, updated_at = ? WHERE task_id = ?",
                                (json.dumps(setup), self._c._clock(), task_id))

    def run_args(self, task_id: str) -> dict[str, Any] | None:
        """What the phone needs to start an Account Setup run: the app and the row's current values (never a password)."""
        with self._c._lock:
            run = self._c._db.execute("SELECT * FROM signup_run WHERE task_id = ?", (task_id,)).fetchone()
            if run is None:
                return None
            sheet = self._sheet(run["table_id"])
            row = self._c.tables.get_row(run["table_id"], run["row_id"])
        return {"package": run["package"], "values": self._values(sheet, row)}

    # ------------------------------------------------------------------ T7 helpers

    def _sheet(self, table_id: Any) -> dict[str, Any]:
        """A sign-up table with what Create accounts needs: its app, phone, columns and system properties."""
        if not isinstance(table_id, str):
            raise CommandError("Name the sign-up table by its tableId.")
        with self._c._lock:
            link = self._c._db.execute("SELECT * FROM signup_table WHERE table_id = ?", (table_id,)).fetchone()
            if link is None:
                raise CommandError("That table is not a sign-up table. Make one from an app's sign-up map in Accounts.")
            table = self._c.tables.get(table_id)
            mapped = self._c._db.execute("SELECT map FROM signup_map WHERE device_id = ? AND package = ?", (link["device_id"], link["package"])).fetchone()
            columns = {r["prop_id"]: (r["field_key"], r["kind"]) for r in self._c._db.execute(
                "SELECT prop_id, field_key, kind FROM signup_column WHERE table_id = ?", (table_id,))}
        props = {p["name"]: p for p in table["properties"]}
        status = next(p for p in table["properties"] if p["type"] == "status")
        app = json.loads(mapped["map"])["app"] if mapped else link["package"]
        return {"id": table_id, "title": table["title"], "device": link["device_id"], "package": link["package"], "app": app,
                "columns": columns, "props": table["properties"], "status": status, "byName": props}

    def _ready(self, body: Any) -> tuple[str, list[dict[str, Any]], dict[str, Any]]:
        if not isinstance(body, dict):
            raise CommandError("Send {tableId, rowIds?}.")
        _only(body, {"tableId", "rowIds"}, "create accounts")
        sheet = self._sheet(body.get("tableId"))
        ready = next((o["id"] for o in sheet["status"]["config"].get("options", []) if o["name"] == "Ready"), None)
        wanted = body.get("rowIds")
        rows = [r for r in self._c.tables.all_rows(sheet["id"])
                if ready and r["cells"].get(sheet["status"]["id"]) == ready and (wanted is None or r["id"] in wanted)]
        with self._c._lock:
            busy = {r["row_id"] for r in self._c._db.execute(
                f"SELECT row_id FROM signup_run WHERE table_id = ? AND state IN ({','.join('?' * len(OPEN_RUN))})", (sheet["id"], *OPEN_RUN))}
        return sheet["id"], [r for r in rows if r["id"] not in busy], sheet

    def _values(self, sheet: dict[str, Any], row: dict[str, Any]) -> dict[str, str]:
        """The row's cells as the phone types them, by the map's field keys. Empty cells are left out."""
        out: dict[str, str] = {}
        props = {p["id"]: p for p in sheet["props"]}
        for prop_id, (key, kind) in sheet["columns"].items():
            prop = props.get(prop_id)
            value = row["cells"].get(prop_id)
            if prop is None or value in (None, "", []) or _secret_name(key):
                continue
            if prop["type"] == "select":
                value = next((o["name"] for o in prop["config"].get("options", []) if o["id"] == value), None)
            elif prop["type"] == "date" and isinstance(value, dict):
                value = value.get("start")
            elif prop["type"] == "checkbox":
                value = "yes" if value else "no"
            if value in (None, ""):
                continue
            out[key] = str(value)[:300]
        return out

    def _handle(self, sheet: dict[str, Any], row: dict[str, Any], values: dict[str, str]) -> str:
        title = next((p for p in sheet["props"] if p["type"] == "title"), None)
        name = (row["cells"].get(title["id"]) if title else None) or values.get("username") or values.get("email") or values.get("full_name")
        return (str(name).strip() or f"{sheet['app']} account")[:80]

    def _basis(self, sheet: dict[str, Any], row: dict[str, Any]) -> str:
        prop = sheet["byName"].get("Whose account")
        choice = row["cells"].get(prop["id"]) if prop else None
        name = next((o["name"] for o in (prop or {}).get("config", {}).get("options", []) if o["id"] == choice), "Mine")
        return {"Company": "company", "Client": "client"}.get(name, "mine")

    def _device_of(self, sheet: dict[str, Any], row: dict[str, Any]) -> str:
        prop = sheet["byName"].get("Phone")
        linked = row["cells"].get(prop["id"]) if prop else None
        return linked[0] if isinstance(linked, list) and linked else sheet["device"]

    def _progress(self, status: str, setup: dict[str, Any] | None, cause: str) -> str:
        if status in ("failed", "cancelled"):
            return (setup or {}).get("note") or cause or "It didn't finish."
        if status == "succeeded":
            return (setup or {}).get("note") or "Created."
        if status in ("scheduled", "making", "waiting_device"):
            return cause or "Waiting for the phone."
        if setup is None:
            return "Starting on the phone."
        parts = []
        if setup["page"]:
            parts.append(f"Page {setup['page']} of {setup['pages']}")
        if setup["drift"]:
            parts.append(f"page {setup['drift']} changed since the map; Cyclone worked it out (map it again to refresh)")
        if setup["state"] == "verification":
            parts.append("a person's step: " + (setup["note"] or "check the phone"))
        elif setup["state"] == "code":
            parts.append(setup["note"] or "Waiting for the code on the phone")
        elif setup["note"]:
            parts.append(setup["note"])
        return " · ".join(parts)[:300] or "Creating."

    def _created(self, sheet: dict[str, Any], run: Any, setup: dict[str, Any] | None) -> None:
        handle = (setup or {}).get("handle")
        if handle:
            try:
                self._c.update_account(run["account_id"], {"handle": handle[:80]})
            except CommandError:
                pass  # the name is taken by another account here; the row still links the account
        try:
            self._c.update_account(run["account_id"], {"notes": f"Created by Cyclone Account Setup ({sheet['title']})."})
        except CommandError:
            pass
        link = sheet["byName"].get("Cyclone account")
        if link is not None:
            try:
                self._c.tables.update_row(sheet["id"], run["row_id"], {"cells": {link["id"]: [run["account_id"]]}}, actor="phone")
            except CommandError:
                pass

    def _set_row(self, sheet: dict[str, Any], row_id: str, status: str, progress: str) -> None:
        option = next((o["id"] for o in sheet["status"]["config"].get("options", []) if o["name"] == status), None)
        cells: dict[str, Any] = {}
        if option:
            cells[sheet["status"]["id"]] = option
        note = sheet["byName"].get("Progress")
        if note is not None:
            cells[note["id"]] = progress[:300]
        try:
            row = self._c.tables.get_row(sheet["id"], row_id)
        except CommandError:
            return
        if any(row["cells"].get(k) != v for k, v in cells.items()):
            try:
                self._c.tables.update_row(sheet["id"], row_id, {"cells": cells}, actor="phone")
            except CommandError:
                pass  # the row was deleted or moved to the trash meanwhile

    # ------------------------------------------------------------------ helpers

    def _device(self, device_id: Any) -> str:
        if not isinstance(device_id, str) or not device_id or len(device_id) > 120:
            raise CommandError("Name the phone by its deviceId.")
        return device_id
