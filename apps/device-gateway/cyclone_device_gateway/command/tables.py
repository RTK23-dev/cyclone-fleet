"""Tables (plan 43, T1): the Command Center's Notion-like databases. The owner builds tables in Glass with typed
properties, fills rows, and looks at the same rows through several saved views (table, board). Every row is also a
page: it has its own blocks under the properties.

Rules:
- **Typed cells only.** Every cell is checked against its property's type; unknown fields are refused. Glass renders
  cells with DOM APIs, so nothing in a table is ever markup or code.
- **No secrets.** Text that looks like a password, code or key is refused, and no property may be a password: the
  vault keeps those (plan 33 §4). A property marked personal is kept out of exports and agent reads unless asked for.
- **Views are computed here.** Filters, sorts and groups run on the PC; Glass only draws what comes back.
- **Every change is kept.** A row's history records who changed which property from what to what, and undo reverts
  the last change. Deleting a row or a table moves it to the trash first.
"""
from __future__ import annotations

import json
import math
import re
import secrets
from datetime import date
from typing import TYPE_CHECKING, Any, Callable

from ..desktop_runtime.v5_contract import FORBIDDEN_SECRET_TOKENS, INLINE_SECRET

if TYPE_CHECKING:  # pragma: no cover
    from .center import CommandCenter

from . import formula as fx
from .center import CommandError
from .pages import ICON, _only, plain_text, validate_blocks

TABLES_SCHEMA = """
CREATE TABLE IF NOT EXISTS cc_table (
  id TEXT PRIMARY KEY, title TEXT NOT NULL, icon TEXT NOT NULL, description TEXT NOT NULL, version INTEGER NOT NULL,
  created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, archived_at INTEGER);
CREATE TABLE IF NOT EXISTS cc_property (
  id TEXT PRIMARY KEY, table_id TEXT NOT NULL, name TEXT NOT NULL, type TEXT NOT NULL, config TEXT NOT NULL, position REAL NOT NULL);
CREATE TABLE IF NOT EXISTS cc_row (
  id TEXT PRIMARY KEY, table_id TEXT NOT NULL, cells TEXT NOT NULL, blocks TEXT NOT NULL, plain TEXT NOT NULL, position REAL NOT NULL,
  version INTEGER NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, archived_at INTEGER);
CREATE TABLE IF NOT EXISTS cc_view (
  id TEXT PRIMARY KEY, table_id TEXT NOT NULL, name TEXT NOT NULL, layout TEXT NOT NULL, config TEXT NOT NULL, position REAL NOT NULL);
CREATE TABLE IF NOT EXISTS cc_row_history (
  seq INTEGER PRIMARY KEY AUTOINCREMENT, row_id TEXT NOT NULL, at INTEGER NOT NULL, actor TEXT NOT NULL, change TEXT NOT NULL);
CREATE INDEX IF NOT EXISTS cc_property_table ON cc_property(table_id);
CREATE INDEX IF NOT EXISTS cc_row_table ON cc_row(table_id);
CREATE INDEX IF NOT EXISTS cc_view_table ON cc_view(table_id);
CREATE INDEX IF NOT EXISTS cc_row_history_row ON cc_row_history(row_id);
"""

TABLE_ID = re.compile(r"^tb_[A-Za-z0-9_-]{8,40}$")
PROP_ID = re.compile(r"^pr_[A-Za-z0-9_-]{6,40}$")
ROW_ID = re.compile(r"^rw_[A-Za-z0-9_-]{8,40}$")
VIEW_ID = re.compile(r"^vw_[A-Za-z0-9_-]{6,40}$")
OPTION_ID = re.compile(r"^op_[A-Za-z0-9_-]{4,40}$")
DAY = re.compile(r"^\d{4}-\d{2}-\d{2}(T\d{2}:\d{2})?$")
EMAIL = re.compile(r"^[^\s@<>]{1,64}@[^\s@<>]{1,190}\.[^\s@<>]{2,24}$")
URL = re.compile(r"^https?://[^\s<>\"]{1,1990}$")
PHONE = re.compile(r"^\+?[0-9 ()./-]{3,30}$")

#: Property types. Plan 43 T3 adds buttons (buttons.py); files and profiles come later.
TYPES = ("title", "text", "number", "currency", "percent", "date", "select", "multi_select", "status", "checkbox",
         "email", "url", "phone_number", "created_time", "edited_time", "relation", "rollup", "formula", "button")
COMPUTED = ("created_time", "edited_time", "rollup", "formula", "button")
#: Plan 43 T2: types whose shape can't change after they are made (delete and add them again instead).
LINKED = ("relation", "rollup", "formula", "button")
#: Cyclone's own records a relation can point at: the Command Center's accounts, routines, tasks, and the phones.
SYSTEM_TARGETS = {"sys:accounts": "Accounts", "sys:routines": "Routines", "sys:tasks": "Tasks", "sys:phones": "Phones"}
LINK_ID = re.compile(r"^[A-Za-z0-9._:-]{1,120}$")
MAX_LINKS = 100
ROLLUP_FNS = ("count", "count_values", "sum", "average", "min", "max", "earliest", "latest", "show", "percent_checked")
COLORS = ("default", "gray", "brown", "orange", "yellow", "green", "blue", "purple", "pink", "red")
STATUS_GROUPS = ("todo", "doing", "done")
CURRENCIES = ("EUR", "USD", "GBP", "PLN", "CHF", "SEK", "NOK", "DKK")
LAYOUTS = ("table", "board", "timeline", "calendar", "gallery", "list")
DATED_LAYOUTS = ("timeline", "calendar")
TEXT_OPS = ("contains", "not_contains", "is", "is_not", "starts_with", "empty", "not_empty")
NUMBER_OPS = ("eq", "ne", "gt", "lt", "gte", "lte", "empty", "not_empty")
CHOICE_OPS = ("is", "is_not", "empty", "not_empty")
MULTI_OPS = ("contains", "not_contains", "empty", "not_empty")
DATE_OPS = ("is", "before", "after", "on_or_before", "on_or_after", "empty", "not_empty")
CHECK_OPS = ("is",)
FORMULA_OPS = ("contains", "is", "eq", "gt", "lt", "gte", "lte", "empty", "not_empty")
OPS_FOR: dict[str, tuple[str, ...]] = {
    "title": TEXT_OPS, "text": TEXT_OPS, "email": TEXT_OPS, "url": TEXT_OPS, "phone_number": TEXT_OPS,
    "number": NUMBER_OPS, "currency": NUMBER_OPS, "percent": NUMBER_OPS,
    "select": CHOICE_OPS, "status": CHOICE_OPS, "multi_select": MULTI_OPS, "checkbox": CHECK_OPS,
    "date": DATE_OPS, "created_time": DATE_OPS, "edited_time": DATE_OPS,
    "relation": MULTI_OPS, "formula": FORMULA_OPS, "rollup": NUMBER_OPS, "button": (),
}
GROUPABLE = ("select", "status", "checkbox", "multi_select")

MAX_TABLES = 200
MAX_PROPERTIES = 60
MAX_ROWS = 10_000
MAX_VIEWS = 30
MAX_OPTIONS = 100
MAX_TEXT = 2_000
MAX_TITLE = 300
MAX_FILTERS = 20
MAX_HISTORY = 200
DEFAULT_STATUS = (("Not started", "gray", "todo"), ("In progress", "blue", "doing"), ("Done", "green", "done"))


class TableConflict(CommandError):
    """A save made on an older version of a row."""


def _new(prefix: str, n: int = 10) -> str:
    return f"{prefix}{secrets.token_urlsafe(n)}"


def _text(value: Any, label: str, limit: int) -> str:
    if not isinstance(value, str):
        raise CommandError(f"{label} must be text.")
    if len(value) > limit:
        raise CommandError(f"{label} is at most {limit} characters.")
    if any(ord(c) < 32 and c not in "\n\t" for c in value):
        raise CommandError(f"{label} has control characters.")
    if INLINE_SECRET.search(value):
        raise CommandError("That looks like a password, code or key. Tables never keep secrets; the vault does.")
    return value


def _name(value: Any, label: str = "A property's name") -> str:
    name = _text(value, label, 80).strip()
    if not name:
        raise CommandError(f"{label} is required.")
    words = re.sub(r"[^a-z]", " ", name.lower()).split()
    if any(w in FORBIDDEN_SECRET_TOKENS for w in words) or "".join(words) in FORBIDDEN_SECRET_TOKENS:
        raise CommandError("Passwords, codes and keys never live in a table. Keep them in the vault; a later build links vault items to rows.")
    return name


def _icon(value: Any) -> str:
    if not isinstance(value, str) or not ICON.match(value):
        raise CommandError("The icon is one emoji.")
    return value


def _date_value(value: Any) -> dict[str, str] | None:
    if value is None:
        return None
    if isinstance(value, str):
        value = {"start": value}
    if not isinstance(value, dict):
        raise CommandError("A date is {start, end?} as YYYY-MM-DD or YYYY-MM-DDTHH:MM.")
    _only(value, {"start", "end"}, "date")
    out: dict[str, str] = {}
    for key in ("start", "end"):
        raw = value.get(key)
        if raw is None:
            continue
        if not isinstance(raw, str) or not DAY.match(raw):
            raise CommandError("A date is YYYY-MM-DD or YYYY-MM-DDTHH:MM.")
        try:
            date.fromisoformat(raw[:10])
        except ValueError as exc:
            raise CommandError("That date does not exist.") from exc
        out[key] = raw
    if "start" not in out:
        raise CommandError("A date needs a start.")
    if "end" in out and out["end"] < out["start"]:
        raise CommandError("A date range ends after it starts.")
    return out


def _options(value: Any, status: bool) -> list[dict[str, Any]]:
    if not isinstance(value, list) or len(value) > MAX_OPTIONS:
        raise CommandError(f"A choice property has at most {MAX_OPTIONS} options.")
    out, ids, names = [], set(), set()
    for raw in value:
        if not isinstance(raw, dict):
            raise CommandError("An option is {id?, name, color, group?}.")
        _only(raw, {"id", "name", "color", "group"}, "option")
        oid = raw.get("id") or _new("op_", 6)
        if not isinstance(oid, str) or not OPTION_ID.match(oid) or oid in ids:
            raise CommandError("Each option has its own id.")
        name = _text(raw.get("name", ""), "An option's name", 100).strip()
        if not name or name.lower() in names:
            raise CommandError("Options have different, non-empty names.")
        color = raw.get("color", "default")
        if color not in COLORS:
            raise CommandError(f"An option's colour is one of: {', '.join(COLORS)}.")
        option = {"id": oid, "name": name, "color": color}
        if status:
            group = raw.get("group", "todo")
            if group not in STATUS_GROUPS:
                raise CommandError("A status option is in the todo, doing or done group.")
            option["group"] = group
        ids.add(oid)
        names.add(name.lower())
        out.append(option)
    return out


def _config(kind: str, value: Any) -> dict[str, Any]:
    if kind == "button":
        from .buttons import button_config
        return button_config(value)
    raw = value or {}
    if not isinstance(raw, dict):
        raise CommandError("A property's settings are an object.")
    _only(raw, {"options", "currency", "decimals", "personal", "time", "wrap", "target", "twoWay", "backProp", "relation", "property",
                "fn", "resultType", "expression"}, "property settings")
    out: dict[str, Any] = {}
    if kind == "relation":
        target = raw.get("target")
        if not isinstance(target, str) or not (TABLE_ID.match(target) or target in SYSTEM_TARGETS):
            raise CommandError("A relation points at one of your tables, or at Accounts, Routines, Tasks or Phones.")
        out["target"] = target
        if raw.get("backProp") is not None:
            if not isinstance(raw["backProp"], str) or not PROP_ID.match(raw["backProp"]):
                raise CommandError("A relation's other side is malformed.")
            out["backProp"] = raw["backProp"]
    if kind == "rollup":
        relation, prop, fn = raw.get("relation"), raw.get("property"), raw.get("fn", "count")
        if not isinstance(relation, str) or not PROP_ID.match(relation):
            raise CommandError("A rollup names one of this table's relations.")
        if prop is not None and (not isinstance(prop, str) or not PROP_ID.match(prop)):
            raise CommandError("A rollup's property is malformed.")
        if fn not in ROLLUP_FNS:
            raise CommandError(f"A rollup calculates one of: {', '.join(ROLLUP_FNS)}.")
        out.update(relation=relation, property=prop, fn=fn)
    if kind == "formula":
        expression = raw.get("expression")
        if not isinstance(expression, str):
            raise CommandError('A formula has an expression, like prop("Estimate") * 1.21.')
        _text(expression, "A formula", fx.MAX_LENGTH)
        try:
            fx.parse(expression)
        except fx.FormulaError as exc:
            raise CommandError(f"Formula: {exc}") from exc
        out["expression"] = expression
    if kind in ("select", "multi_select", "status"):
        options = raw.get("options")
        if options is None and kind == "status":
            options = [{"name": n, "color": c, "group": g} for n, c, g in DEFAULT_STATUS]
        out["options"] = _options(options or [], kind == "status")
    if kind == "currency":
        currency = raw.get("currency", "EUR")
        if currency not in CURRENCIES:
            raise CommandError(f"The currency is one of: {', '.join(CURRENCIES)}.")
        out["currency"] = currency
    if kind in ("number", "currency", "percent"):
        decimals = raw.get("decimals", 2 if kind == "currency" else 0)
        if type(decimals) is not int or not 0 <= decimals <= 6:
            raise CommandError("Decimals are 0..6.")
        out["decimals"] = decimals
    if kind == "date":
        out["time"] = raw.get("time") is True
    for flag in ("personal", "wrap"):
        if raw.get(flag) is True:
            out[flag] = True
        elif flag in raw and raw[flag] is not False:
            raise CommandError(f"{flag} is true or false.")
    return out


def cell_value(prop: dict[str, Any], value: Any) -> Any:
    """One cell checked against its property. None clears it."""
    kind = prop["type"]
    if kind in COMPUTED:
        raise CommandError(f"{prop['name']} is filled in by Cyclone.")
    if value is None or value == "" or value == []:
        return False if kind == "checkbox" else None
    if kind in ("title", "text"):
        return _text(value, prop["name"], MAX_TITLE if kind == "title" else MAX_TEXT)
    if kind in ("number", "currency", "percent"):
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or abs(value) > 1e15:
            raise CommandError(f"{prop['name']} is a number.")
        return value
    if kind == "checkbox":
        if not isinstance(value, bool):
            raise CommandError(f"{prop['name']} is checked or not.")
        return value
    if kind == "date":
        return _date_value(value)
    if kind == "relation":
        if not isinstance(value, list) or len(value) > MAX_LINKS or any(not isinstance(v, str) or not LINK_ID.match(v) for v in value):
            raise CommandError(f"{prop['name']} links at most {MAX_LINKS} rows.")
        return list(dict.fromkeys(value))
    options = {o["id"] for o in prop["config"].get("options", [])}
    if kind in ("select", "status"):
        if value not in options:
            raise CommandError(f"Pick one of {prop['name']}'s options.")
        return value
    if kind == "multi_select":
        if not isinstance(value, list) or len(value) > MAX_OPTIONS or any(v not in options for v in value):
            raise CommandError(f"Pick {prop['name']}'s options from its list.")
        return list(dict.fromkeys(value))
    text = _text(value, prop["name"], MAX_TEXT).strip()
    pattern = {"email": EMAIL, "url": URL, "phone_number": PHONE}[kind]
    if not pattern.match(text):
        raise CommandError(f"{prop['name']} is not a valid {kind.replace('_', ' ')}.")
    return text


def display(prop: dict[str, Any], value: Any) -> str:
    """A cell as plain text (search, export, conversions)."""
    if value is None:
        return ""
    kind = prop["type"]
    if kind == "checkbox":
        return "Yes" if value else "No"
    if kind in ("select", "status"):
        return next((o["name"] for o in prop["config"].get("options", []) if o["id"] == value), "")
    if kind == "multi_select":
        names = {o["id"]: o["name"] for o in prop["config"].get("options", [])}
        return ", ".join(names[v] for v in value if v in names)
    if kind == "date":
        return value["start"] + (f" → {value['end']}" if value.get("end") else "")
    if kind == "relation":
        return f"{len(value)} linked" if value else ""
    if kind in ("rollup", "formula"):
        return fx.text_of(value)
    if kind == "button":
        return str(value.get("label", "")) if isinstance(value, dict) else ""
    if isinstance(value, float) and value.is_integer():
        return str(int(value))
    return str(value)


class TableStore:
    """The owner's tables, in the Command Center's database (same lock and audit chain)."""

    def __init__(self, center: "CommandCenter") -> None:
        self._c = center
        with center._lock:
            center._db.executescript(TABLES_SCHEMA)

    # ------------------------------------------------------------------ tables

    def list(self) -> list[dict[str, Any]]:
        with self._c._lock:
            rows = self._c._db.execute("SELECT * FROM cc_table WHERE archived_at IS NULL ORDER BY updated_at DESC").fetchall()
            return [{**self._table_meta(r), "rows": self._count(r["id"])} for r in rows]

    def trash(self) -> dict[str, Any]:
        with self._c._lock:
            tables = self._c._db.execute("SELECT * FROM cc_table WHERE archived_at IS NOT NULL ORDER BY archived_at DESC LIMIT 100").fetchall()
            rows = self._c._db.execute(
                "SELECT r.*, t.title AS table_title FROM cc_row r JOIN cc_table t ON t.id = r.table_id"
                " WHERE r.archived_at IS NOT NULL AND t.archived_at IS NULL ORDER BY r.archived_at DESC LIMIT 200").fetchall()
            out_rows = []
            for r in rows:
                props = self._props(r["table_id"])
                title = next((p for p in props if p["type"] == "title"), None)
                out_rows.append({"id": r["id"], "tableId": r["table_id"], "table": r["table_title"], "archivedAt": r["archived_at"],
                                 "title": display(title, json.loads(r["cells"]).get(title["id"])) if title else ""})
            return {"tables": [{**self._table_meta(t), "archivedAt": t["archived_at"]} for t in tables], "rows": out_rows}

    def create(self, body: Any, *, actor: str = "owner") -> dict[str, Any]:
        if not isinstance(body, dict):
            raise CommandError("Send {title, icon?, description?}.")
        _only(body, {"title", "icon", "description"}, "table")
        title = _text(body.get("title", "Untitled table"), "The title", 200).strip() or "Untitled table"
        icon = _icon(body.get("icon", "🗂️"))
        description = _text(body.get("description", ""), "The description", 1000)
        with self._c._lock:
            if self._c._db.execute("SELECT COUNT(*) FROM cc_table").fetchone()[0] >= MAX_TABLES:
                raise CommandError(f"The workspace holds at most {MAX_TABLES} tables. Empty the trash first.")
            now = self._c._clock()
            table_id = _new("tb_", 12)
            self._c._db.execute("INSERT INTO cc_table(id, title, icon, description, version, created_at, updated_at, archived_at)"
                                " VALUES (?,?,?,?,1,?,?,NULL)", (table_id, title, icon, description, now, now))
            name = self._insert_prop(table_id, "Name", "title", {})
            status = self._insert_prop(table_id, "Status", "status", _config("status", {}))
            self._insert_view(table_id, "Table", "table", {})
            self._insert_view(table_id, "Board", "board", {"groupBy": status["id"]})
            del name
            self._c._audit(actor, "table.create", table_id)
            return self._public(table_id)

    def get(self, table_id: str) -> dict[str, Any]:
        with self._c._lock:
            self._table(table_id)
            return self._public(table_id)

    def update(self, table_id: str, body: Any, *, actor: str = "owner") -> dict[str, Any]:
        if not isinstance(body, dict):
            raise CommandError("Send {title?, icon?, description?}.")
        _only(body, {"title", "icon", "description"}, "table")
        fields: dict[str, Any] = {}
        if "title" in body:
            fields["title"] = _text(body["title"], "The title", 200).strip() or "Untitled table"
        if "icon" in body:
            fields["icon"] = _icon(body["icon"])
        if "description" in body:
            fields["description"] = _text(body["description"], "The description", 1000)
        with self._c._lock:
            self._live_table(table_id)
            if fields:
                sets = ", ".join(f"{k} = ?" for k in fields)
                self._c._db.execute(f"UPDATE cc_table SET {sets}, version = version + 1, updated_at = ? WHERE id = ?",
                                    (*fields.values(), self._c._clock(), table_id))
                self._c._audit(actor, "table.edit", table_id)
            return self._public(table_id)

    def archive(self, table_id: str) -> dict[str, Any]:
        with self._c._lock:
            self._live_table(table_id)
            self._c._db.execute("UPDATE cc_table SET archived_at = ? WHERE id = ?", (self._c._clock(), table_id))
            self._c._audit("owner", "table.archive", table_id)
            return {"id": table_id, "archived": True}

    def restore(self, table_id: str) -> dict[str, Any]:
        with self._c._lock:
            if self._table(table_id)["archived_at"] is None:
                raise CommandError("That table is not in the trash.")
            self._c._db.execute("UPDATE cc_table SET archived_at = NULL, updated_at = ? WHERE id = ?", (self._c._clock(), table_id))
            self._c._audit("owner", "table.restore", table_id)
            return self._public(table_id)

    def delete(self, table_id: str) -> dict[str, Any]:
        with self._c._lock:
            if self._table(table_id)["archived_at"] is None:
                raise CommandError("Move the table to the trash first.")
            for other in self._c._db.execute("SELECT id, table_id, type, config FROM cc_property WHERE type = 'relation' AND table_id != ?",
                                             (table_id,)).fetchall():
                config = json.loads(other["config"])
                if config.get("target") == table_id:
                    self._drop_property(other["table_id"], {"id": other["id"], "type": "relation", "config": config}, pair=False)
            rows = [r["id"] for r in self._c._db.execute("SELECT id FROM cc_row WHERE table_id = ?", (table_id,))]
            self._c._db.executemany("DELETE FROM cc_row_history WHERE row_id = ?", [(r,) for r in rows])
            for sql in ("DELETE FROM cc_row WHERE table_id = ?", "DELETE FROM cc_property WHERE table_id = ?",
                        "DELETE FROM cc_view WHERE table_id = ?", "DELETE FROM cc_table WHERE id = ?"):
                self._c._db.execute(sql, (table_id,))
            self._c._audit("owner", "table.delete", table_id, {"rows": len(rows)})
            return {"id": table_id, "deleted": True}

    # ------------------------------------------------------------------ properties

    def add_property(self, table_id: str, body: Any) -> dict[str, Any]:
        if not isinstance(body, dict):
            raise CommandError("Send {name, type, config?}.")
        _only(body, {"name", "type", "config"}, "property")
        kind = body.get("type")
        if kind not in TYPES or kind == "title":
            raise CommandError(f"A property's type is one of: {', '.join(t for t in TYPES if t != 'title')}.")
        name = _name(body.get("name"))
        raw_config = body.get("config") or {}
        config = _config(kind, raw_config)
        with self._c._lock:
            table = self._live_table(table_id)
            props = self._props(table_id)
            if len(props) >= MAX_PROPERTIES:
                raise CommandError(f"A table has at most {MAX_PROPERTIES} properties.")
            if any(p["name"].lower() == name.lower() for p in props):
                raise CommandError("Another property already has that name.")
            prop_id = _new("pr_", 8)
            if kind == "relation":
                config.pop("backProp", None)
                target = config["target"]
                if TABLE_ID.match(target):
                    target_table = self._live_table(target)
                    if raw_config.get("twoWay", True) is not False:
                        # Two-way: the other table gets the way back, named after this table.
                        back_id = _new("pr_", 8)
                        taken = {p["name"].lower() for p in self._props(target)} | ({name.lower()} if target == table_id else set())
                        back_name = table["title"][:70] or "Linked"
                        while back_name.lower() in taken:
                            back_name = f"{back_name} (linked)"[:80]
                        if len(self._props(target)) + (1 if target == table_id else 0) >= MAX_PROPERTIES:
                            raise CommandError(f"{target_table['title']} already has {MAX_PROPERTIES} properties.")
                        config["backProp"] = back_id
                        self._insert_prop(target, back_name, "relation", {"target": table_id, "backProp": prop_id}, prop_id=back_id)
                        self._touch(target)
            elif kind == "rollup":
                config["resultType"] = self._rollup_type(table_id, props, config)
            elif kind == "formula":
                self._check_formula(props, config["expression"], None)
            elif kind == "button":
                self._c.buttons.check(props, config, None)
            prop = self._insert_prop(table_id, name, kind, config, prop_id=prop_id)
            self._touch(table_id)
            self._c._audit("owner", "table.property.add", table_id, {"property": prop["id"], "type": kind})
            return self._public(table_id)

    def update_property(self, table_id: str, prop_id: str, body: Any) -> dict[str, Any]:
        if not isinstance(body, dict):
            raise CommandError("Send {name?, type?, config?, before?}.")
        _only(body, {"name", "type", "config", "before"}, "property")
        with self._c._lock:
            self._live_table(table_id)
            props = self._props(table_id)
            prop = next((p for p in props if p["id"] == prop_id), None)
            if prop is None:
                raise CommandError("No such property.")
            name, kind = prop["name"], prop["type"]
            if "name" in body:
                name = _name(body["name"])
                if any(p["name"].lower() == name.lower() and p["id"] != prop_id for p in props):
                    raise CommandError("Another property already has that name.")
            if "type" in body and body["type"] != kind:
                if kind == "title" or body["type"] == "title" or body["type"] not in TYPES:
                    raise CommandError("The title property stays the title; other properties change to any type but title.")
                if kind in LINKED or body["type"] in LINKED:
                    raise CommandError("Relations, rollups and formulas can't change type. Delete this property and add a new one.")
                kind = body["type"]
            config = _config(kind, body["config"]) if "config" in body else (_config(kind, None) if kind != prop["type"] else prop["config"])
            if kind == "relation":
                # Where a relation points, and its way back, never change.
                config = {**config, "target": prop["config"]["target"]}
                config.pop("backProp", None)
                if prop["config"].get("backProp"):
                    config["backProp"] = prop["config"]["backProp"]
            elif kind == "rollup":
                config["resultType"] = self._rollup_type(table_id, props, config)
            elif kind == "formula":
                self._check_formula(props, config["expression"], prop_id)
            elif kind == "button":
                self._c.buttons.check(props, config, prop_id)
            if name != prop["name"]:
                self._rename_in_formulas(table_id, prop["name"], name)
            if kind != prop["type"]:
                config = self._convert(table_id, prop, kind, config)
            elif "config" in body and kind in ("select", "multi_select", "status"):
                self._drop_missing_options(table_id, prop, config)
            position = prop["position"]
            if body.get("before") is not None:
                before = next((p for p in props if p["id"] == body["before"]), None)
                if before is None or before["id"] == prop_id:
                    raise CommandError("Place it before another property of this table.")
                earlier = [p["position"] for p in props if p["position"] < before["position"] and p["id"] != prop_id]
                position = (before["position"] + (max(earlier) if earlier else before["position"] - 2)) / 2
            self._c._db.execute("UPDATE cc_property SET name = ?, type = ?, config = ?, position = ? WHERE id = ?",
                                (name, kind, json.dumps(config, ensure_ascii=False), position, prop_id))
            self._touch(table_id)
            self._c._audit("owner", "table.property.edit", table_id, {"property": prop_id})
            return self._public(table_id)

    def delete_property(self, table_id: str, prop_id: str) -> dict[str, Any]:
        with self._c._lock:
            self._live_table(table_id)
            prop = next((p for p in self._props(table_id) if p["id"] == prop_id), None)
            if prop is None:
                raise CommandError("No such property.")
            if prop["type"] == "title":
                raise CommandError("Every table keeps its title property.")
            self._drop_property(table_id, prop)
            self._touch(table_id)
            self._c._audit("owner", "table.property.delete", table_id, {"property": prop_id})
            return self._public(table_id)

    def _drop_property(self, table_id: str, prop: dict[str, Any], *, pair: bool = True) -> None:
        """Removes a property, its values, its place in views, the rollups over it, and a relation's way back."""
        prop_id = prop["id"]
        if prop["type"] == "relation" and pair and prop["config"].get("backProp"):
            target = prop["config"]["target"]
            back = next((p for p in self._props(target) if p["id"] == prop["config"]["backProp"]), None) if TABLE_ID.match(target) else None
            if back is not None:
                self._drop_property(target, back, pair=False)
                self._touch(target)
        for other in self._c._db.execute("SELECT id, table_id, config FROM cc_property WHERE type = 'rollup'").fetchall():
            config = json.loads(other["config"])
            if other["id"] != prop_id and (config.get("relation") == prop_id or config.get("property") == prop_id):
                self._drop_property(other["table_id"], {"id": other["id"], "type": "rollup", "config": config}, pair=False)
        self._c._db.execute("DELETE FROM cc_property WHERE id = ?", (prop_id,))
        for r in self._c._db.execute("SELECT id, cells FROM cc_row WHERE table_id = ?", (table_id,)).fetchall():
            cells = json.loads(r["cells"])
            if prop_id in cells:
                cells.pop(prop_id)
                self._c._db.execute("UPDATE cc_row SET cells = ? WHERE id = ?", (json.dumps(cells, ensure_ascii=False), r["id"]))
        for v in self._c._db.execute("SELECT id, config FROM cc_view WHERE table_id = ?", (table_id,)).fetchall():
            config = self._scrub_view(json.loads(v["config"]), prop_id)
            self._c._db.execute("UPDATE cc_view SET config = ? WHERE id = ?", (json.dumps(config), v["id"]))

    # ------------------------------------------------------------------ views

    def add_view(self, table_id: str, body: Any) -> dict[str, Any]:
        if not isinstance(body, dict):
            raise CommandError("Send {name, layout, config?}.")
        _only(body, {"name", "layout", "config"}, "view")
        with self._c._lock:
            self._live_table(table_id)
            if self._c._db.execute("SELECT COUNT(*) FROM cc_view WHERE table_id = ?", (table_id,)).fetchone()[0] >= MAX_VIEWS:
                raise CommandError(f"A table has at most {MAX_VIEWS} views.")
            layout = body.get("layout", "table")
            if layout not in LAYOUTS:
                raise CommandError(f"A view's layout is one of: {', '.join(LAYOUTS)}.")
            props = self._props(table_id)
            config = self._view_config(body.get("config"), props, layout)
            view = self._insert_view(table_id, _text(body.get("name", "View"), "A view's name", 80).strip() or "View", layout, config)
            self._touch(table_id)
            return {**self._public(table_id), "viewId": view["id"]}

    def update_view(self, table_id: str, view_id: str, body: Any) -> dict[str, Any]:
        if not isinstance(body, dict):
            raise CommandError("Send {name?, layout?, config?, before?}.")
        _only(body, {"name", "layout", "config", "before"}, "view")
        with self._c._lock:
            self._live_table(table_id)
            views = self._views(table_id)
            view = next((v for v in views if v["id"] == view_id), None)
            if view is None:
                raise CommandError("No such view.")
            layout = body.get("layout", view["layout"])
            if layout not in LAYOUTS:
                raise CommandError(f"A view's layout is one of: {', '.join(LAYOUTS)}.")
            config = self._view_config(body["config"] if "config" in body else view["config"], self._props(table_id), layout)
            name = _text(body["name"], "A view's name", 80).strip() or view["name"] if "name" in body else view["name"]
            position = view["position"]
            if body.get("before") is not None:
                before = next((v for v in views if v["id"] == body["before"]), None)
                if before is None or before["id"] == view_id:
                    raise CommandError("Place it before another view of this table.")
                earlier = [v["position"] for v in views if v["position"] < before["position"] and v["id"] != view_id]
                position = (before["position"] + (max(earlier) if earlier else before["position"] - 2)) / 2
            self._c._db.execute("UPDATE cc_view SET name = ?, layout = ?, config = ?, position = ? WHERE id = ?",
                                (name, layout, json.dumps(config), position, view_id))
            self._touch(table_id)
            return self._public(table_id)

    def delete_view(self, table_id: str, view_id: str) -> dict[str, Any]:
        with self._c._lock:
            self._live_table(table_id)
            views = self._views(table_id)
            if not any(v["id"] == view_id for v in views):
                raise CommandError("No such view.")
            if len(views) == 1:
                raise CommandError("A table keeps at least one view.")
            self._c._db.execute("DELETE FROM cc_view WHERE id = ?", (view_id,))
            self._touch(table_id)
            return self._public(table_id)

    # ------------------------------------------------------------------ rows

    def rows(self, table_id: str, view_id: str | None = None, query: str | None = None, *, personal: bool = True) -> dict[str, Any]:
        """The rows as a view shows them: filtered, sorted and (for a board) grouped, computed here."""
        with self._c._lock:
            self._live_table(table_id)
            props = self._props(table_id)
            views = self._views(table_id)
            view = next((v for v in views if v["id"] == view_id), None) if view_id else views[0]
            if view is None:
                raise CommandError("No such view.")
            rows = self._publics(table_id, props, self._c._db.execute(
                "SELECT * FROM cc_row WHERE table_id = ? AND archived_at IS NULL ORDER BY position, created_at", (table_id,)).fetchall())
            labels = self._labels(props, rows)
        by_id = {p["id"]: p for p in props}
        config = view["config"]
        rows = [r for r in rows if _matches(r, config.get("filters", []), config.get("match", "and"), by_id)]
        if query:
            needle = query.strip().lower()
            rows = [r for r in rows if any(needle in _shown(by_id[k], v, labels).lower() for k, v in r["cells"].items() if k in by_id)]
        for sort in reversed(config.get("sorts", [])):
            prop = by_id.get(sort["property"])
            if prop is not None:
                rows.sort(key=_sort_key(prop), reverse=sort["direction"] == "desc")
        if not personal:
            hidden = {p["id"] for p in props if p["config"].get("personal")}
            rows = [{**r, "cells": {k: v for k, v in r["cells"].items() if k not in hidden}} for r in rows]
        out: dict[str, Any] = {"table": self.get(table_id), "view": view, "rows": rows, "total": len(rows), "links": labels}
        group = by_id.get(config.get("groupBy") or "")
        if view["layout"] == "board" and group is not None:
            out["groups"] = _groups(group, rows)
        return out

    def all_rows(self, table_id: str) -> list[dict[str, Any]]:
        """Every live row, whatever the views filter (for engines such as Create accounts)."""
        with self._c._lock:
            self._live_table(table_id)
            return self._publics(table_id, self._props(table_id), self._c._db.execute(
                "SELECT * FROM cc_row WHERE table_id = ? AND archived_at IS NULL ORDER BY position, created_at", (table_id,)).fetchall())

    def get_row(self, table_id: str, row_id: str) -> dict[str, Any]:
        with self._c._lock:
            self._live_table(table_id)
            row = self._row(table_id, row_id)
            props = self._props(table_id)
            history = self._c._db.execute("SELECT at, actor, change FROM cc_row_history WHERE row_id = ? ORDER BY seq DESC LIMIT 50",
                                          (row_id,)).fetchall()
            public = self._publics(table_id, props, [row])[0]
            return {**public, "blocks": json.loads(row["blocks"]), "links": self._labels(props, [public]),
                    "table": self._public(table_id), "buttonRuns": self._c.buttons.history(table_id, row_id),
                    "history": [{"at": h["at"], "actor": h["actor"], "change": json.loads(h["change"])} for h in history]}

    def create_row(self, table_id: str, body: Any, *, actor: str = "owner") -> dict[str, Any]:
        if not isinstance(body, dict):
            raise CommandError("Send {cells?, before?}.")
        _only(body, {"cells", "before"}, "row")
        with self._c._lock:
            self._live_table(table_id)
            if self._c._db.execute("SELECT COUNT(*) FROM cc_row WHERE table_id = ?", (table_id,)).fetchone()[0] >= MAX_ROWS:
                raise CommandError(f"A table holds at most {MAX_ROWS} rows.")
            props = self._props(table_id)
            cells = self._cells(props, body.get("cells") or {}, {})
            now = self._c._clock()
            row_id = _new("rw_", 12)
            position = self._position(table_id, body.get("before"))
            self._c._db.execute("INSERT INTO cc_row(id, table_id, cells, blocks, plain, position, version, created_at, updated_at, archived_at)"
                                " VALUES (?,?,?,?,?,?,1,?,?,NULL)",
                                (row_id, table_id, json.dumps(cells, ensure_ascii=False), "[]", "", position, now, now))
            self._history(row_id, actor, {"created": True})
            self._sync_links(table_id, row_id, props, {}, cells, actor)
            self._touch(table_id)
            return self._publics(table_id, props, [self._row(table_id, row_id)])[0]

    def update_row(self, table_id: str, row_id: str, body: Any, *, actor: str = "owner") -> dict[str, Any]:
        """Changes some cells. A version, when sent, must be current (two tabs never overwrite each other)."""
        if not isinstance(body, dict):
            raise CommandError("Send {cells, version?}.")
        _only(body, {"cells", "version", "before"}, "row")
        with self._c._lock:
            self._live_table(table_id)
            row = self._row(table_id, row_id)
            if row["archived_at"] is not None:
                raise CommandError("That row is in the trash. Restore it to edit it.")
            if "version" in body and body["version"] != row["version"]:
                raise TableConflict("This row changed elsewhere since you opened it. It has been reloaded.")
            props = self._props(table_id)
            old = json.loads(row["cells"])
            cells = self._cells(props, body.get("cells") or {}, old)
            change = {k: [old.get(k), cells.get(k)] for k in set(old) | set(cells) if old.get(k) != cells.get(k)}
            position = self._position(table_id, body["before"], exclude=row_id) if body.get("before") is not None else row["position"]
            if not change and position == row["position"]:
                return self._publics(table_id, props, [row])[0]
            self._c._db.execute("UPDATE cc_row SET cells = ?, position = ?, version = version + 1, updated_at = ? WHERE id = ?",
                                (json.dumps(cells, ensure_ascii=False), position, self._c._clock(), row_id))
            if change:
                self._history(row_id, actor, {"cells": change})
                self._sync_links(table_id, row_id, props, old, cells, actor)
            self._touch(table_id)
            return self._publics(table_id, props, [self._row(table_id, row_id)])[0]

    def save_row_page(self, table_id: str, row_id: str, body: Any) -> dict[str, Any]:
        """The row's own page, under its properties: the same typed blocks as any page."""
        if not isinstance(body, dict) or "version" not in body:
            raise CommandError("Send {version, blocks}.")
        _only(body, {"version", "blocks"}, "row page")
        blocks = validate_blocks(body.get("blocks", []))
        if any(b["type"] == "table" for b in blocks):
            raise CommandError("A row's page can't hold a table.")
        with self._c._lock:
            self._live_table(table_id)
            row = self._row(table_id, row_id)
            if body["version"] != row["version"]:
                raise TableConflict("This row changed elsewhere since you opened it. It has been reloaded.")
            self._c._db.execute("UPDATE cc_row SET blocks = ?, plain = ?, version = version + 1, updated_at = ? WHERE id = ?",
                                (json.dumps(blocks, ensure_ascii=False), plain_text(blocks), self._c._clock(), row_id))
            return self.get_row(table_id, row_id)

    def undo(self, table_id: str, row_id: str, *, actor: str = "owner") -> dict[str, Any]:
        """Reverts the row's last cell change (a value that no longer fits its property is cleared)."""
        with self._c._lock:
            self._live_table(table_id)
            row = self._row(table_id, row_id)
            last = self._c._db.execute("SELECT seq, change FROM cc_row_history WHERE row_id = ? AND change LIKE '{\"cells\"%'"
                                       " ORDER BY seq DESC LIMIT 1", (row_id,)).fetchone()
            if last is None:
                raise CommandError("Nothing to undo on this row.")
            props = {p["id"]: p for p in self._props(table_id)}
            cells = json.loads(row["cells"])
            for prop_id, (before, _after) in json.loads(last["change"])["cells"].items():
                prop = props.get(prop_id)
                if prop is None or prop["type"] in COMPUTED:
                    continue
                try:
                    value = cell_value(prop, before)
                except CommandError:
                    value = None
                if value is None:
                    cells.pop(prop_id, None)
                else:
                    cells[prop_id] = value
            self._c._db.execute("DELETE FROM cc_row_history WHERE seq = ?", (last["seq"],))
            self._c._db.execute("UPDATE cc_row SET cells = ?, version = version + 1, updated_at = ? WHERE id = ?",
                                (json.dumps(cells, ensure_ascii=False), self._c._clock(), row_id))
            self._history(row_id, actor, {"undo": True})
            self._sync_links(table_id, row_id, list(props.values()), json.loads(row["cells"]), cells, actor)
            return self._publics(table_id, list(props.values()), [self._row(table_id, row_id)])[0]

    def archive_row(self, table_id: str, row_id: str) -> dict[str, Any]:
        with self._c._lock:
            self._live_table(table_id)
            self._row(table_id, row_id)
            self._c._db.execute("UPDATE cc_row SET archived_at = ? WHERE id = ?", (self._c._clock(), row_id))
            self._history(row_id, "owner", {"archived": True})
            self._touch(table_id)
            return {"id": row_id, "archived": True}

    def restore_row(self, table_id: str, row_id: str) -> dict[str, Any]:
        with self._c._lock:
            self._live_table(table_id)
            if self._row(table_id, row_id)["archived_at"] is None:
                raise CommandError("That row is not in the trash.")
            self._c._db.execute("UPDATE cc_row SET archived_at = NULL, updated_at = ? WHERE id = ?", (self._c._clock(), row_id))
            self._history(row_id, "owner", {"restored": True})
            self._touch(table_id)
            return self._publics(table_id, self._props(table_id), [self._row(table_id, row_id)])[0]

    def delete_row(self, table_id: str, row_id: str) -> dict[str, Any]:
        with self._c._lock:
            self._live_table(table_id)
            row = self._row(table_id, row_id)
            if row["archived_at"] is None:
                raise CommandError("Move the row to the trash first.")
            self._sync_links(table_id, row_id, self._props(table_id), json.loads(row["cells"]), {}, "owner")
            self._c._db.execute("DELETE FROM cc_row WHERE id = ?", (row_id,))
            self._c._db.execute("DELETE FROM cc_row_history WHERE row_id = ?", (row_id,))
            self._c._audit("owner", "table.row.delete", table_id, {"row": row_id})
            return {"id": row_id, "deleted": True}

    def export_csv(self, table_id: str, view_id: str | None = None) -> str:
        """The view as CSV. Personal properties are left out."""
        import csv
        import io
        data = self.rows(table_id, view_id, personal=False)
        props = [p for p in data["table"]["properties"] if not p["config"].get("personal") and p["id"] not in data["view"]["config"].get("hidden", [])]
        out = io.StringIO()
        writer = csv.writer(out)
        writer.writerow([p["name"] for p in props])
        for row in data["rows"]:
            writer.writerow([_csv_safe(_shown(p, row["cells"].get(p["id"]), data["links"])) for p in props])
        return out.getvalue()

    # ------------------------------------------------------------------ helpers

    def _table(self, table_id: Any) -> Any:
        if not isinstance(table_id, str) or not TABLE_ID.match(table_id):
            raise CommandError("No such table.")
        row = self._c._db.execute("SELECT * FROM cc_table WHERE id = ?", (table_id,)).fetchone()
        if row is None:
            raise CommandError("No such table.")
        return row

    def _live_table(self, table_id: Any) -> Any:
        row = self._table(table_id)
        if row["archived_at"] is not None:
            raise CommandError("That table is in the trash.")
        return row

    def _row(self, table_id: str, row_id: Any) -> Any:
        if not isinstance(row_id, str) or not ROW_ID.match(row_id):
            raise CommandError("No such row.")
        row = self._c._db.execute("SELECT * FROM cc_row WHERE id = ? AND table_id = ?", (row_id, table_id)).fetchone()
        if row is None:
            raise CommandError("No such row.")
        return row

    def _props(self, table_id: str) -> list[dict[str, Any]]:
        return [{"id": r["id"], "name": r["name"], "type": r["type"], "config": json.loads(r["config"]), "position": r["position"]}
                for r in self._c._db.execute("SELECT * FROM cc_property WHERE table_id = ? ORDER BY position", (table_id,))]

    def _views(self, table_id: str) -> list[dict[str, Any]]:
        return [{"id": r["id"], "name": r["name"], "layout": r["layout"], "config": json.loads(r["config"]), "position": r["position"]}
                for r in self._c._db.execute("SELECT * FROM cc_view WHERE table_id = ? ORDER BY position", (table_id,))]

    def _count(self, table_id: str) -> int:
        return self._c._db.execute("SELECT COUNT(*) FROM cc_row WHERE table_id = ? AND archived_at IS NULL", (table_id,)).fetchone()[0]

    @staticmethod
    def _table_meta(r: Any) -> dict[str, Any]:
        return {"id": r["id"], "title": r["title"], "icon": r["icon"], "description": r["description"], "version": r["version"],
                "createdAt": r["created_at"], "updatedAt": r["updated_at"]}

    def _public(self, table_id: str) -> dict[str, Any]:
        row = self._table(table_id)
        return {**self._table_meta(row), "archivedAt": row["archived_at"], "properties": self._props(table_id),
                "views": self._views(table_id), "rows": self._count(table_id)}

    @staticmethod
    def _row_public(r: Any, props: list[dict[str, Any]]) -> dict[str, Any]:
        cells = json.loads(r["cells"])
        for p in props:
            if p["type"] == "created_time":
                cells[p["id"]] = _day(r["created_at"])
            elif p["type"] == "edited_time":
                cells[p["id"]] = _day(r["updated_at"])
        return {"id": r["id"], "tableId": r["table_id"], "cells": cells, "version": r["version"], "position": r["position"],
                "createdAt": r["created_at"], "updatedAt": r["updated_at"], "archivedAt": r["archived_at"],
                "hasPage": r["blocks"] != "[]"}

    def _insert_prop(self, table_id: str, name: str, kind: str, config: dict[str, Any], *, prop_id: str | None = None) -> dict[str, Any]:
        top = self._c._db.execute("SELECT MAX(position) FROM cc_property WHERE table_id = ?", (table_id,)).fetchone()[0]
        prop_id = prop_id or _new("pr_", 8)
        self._c._db.execute("INSERT INTO cc_property(id, table_id, name, type, config, position) VALUES (?,?,?,?,?,?)",
                            (prop_id, table_id, name, kind, json.dumps(config, ensure_ascii=False), (top or 0) + 1))
        return {"id": prop_id, "name": name, "type": kind, "config": config}

    def _insert_view(self, table_id: str, name: str, layout: str, config: dict[str, Any]) -> dict[str, Any]:
        top = self._c._db.execute("SELECT MAX(position) FROM cc_view WHERE table_id = ?", (table_id,)).fetchone()[0]
        view_id = _new("vw_", 8)
        full = {"filters": [], "match": "and", "sorts": [], "groupBy": None, "hidden": [], "widths": {}, **config}
        self._c._db.execute("INSERT INTO cc_view(id, table_id, name, layout, config, position) VALUES (?,?,?,?,?,?)",
                            (view_id, table_id, name, layout, json.dumps(full), (top or 0) + 1))
        return {"id": view_id}

    def _view_config(self, value: Any, props: list[dict[str, Any]], layout: str) -> dict[str, Any]:
        raw = value or {}
        if not isinstance(raw, dict):
            raise CommandError("A view's settings are an object.")
        _only(raw, {"filters", "match", "sorts", "groupBy", "hidden", "widths", "dateProp"}, "view settings")
        by_id = {p["id"]: p for p in props}
        filters = raw.get("filters", [])
        if not isinstance(filters, list) or len(filters) > MAX_FILTERS:
            raise CommandError(f"A view has at most {MAX_FILTERS} filters.")
        clean_filters = []
        for f in filters:
            if not isinstance(f, dict):
                raise CommandError("A filter is {property, op, value?}.")
            _only(f, {"property", "op", "value"}, "filter")
            prop = by_id.get(f.get("property"))
            if prop is None:
                raise CommandError("A filter names a property of this table.")
            op = f.get("op")
            ops = OPS_FOR[_kind(prop)]
            if op not in ops:
                raise CommandError(f"{prop['name']} filters with: {', '.join(ops)}.")
            clean_filters.append({"property": prop["id"], "op": op, "value": _filter_value(prop, op, f.get("value"))})
        match = raw.get("match", "and")
        if match not in ("and", "or"):
            raise CommandError("Filters match all (and) or any (or).")
        sorts = raw.get("sorts", [])
        if not isinstance(sorts, list) or len(sorts) > 5:
            raise CommandError("A view sorts by at most 5 properties.")
        clean_sorts = []
        for s in sorts:
            if not isinstance(s, dict):
                raise CommandError("A sort is {property, direction}.")
            _only(s, {"property", "direction"}, "sort")
            if s.get("property") not in by_id or s.get("direction", "asc") not in ("asc", "desc"):
                raise CommandError("A sort names a property of this table, ascending or descending.")
            clean_sorts.append({"property": s["property"], "direction": s.get("direction", "asc")})
        group = raw.get("groupBy")
        if group is not None and (group not in by_id or by_id[group]["type"] not in GROUPABLE):
            raise CommandError("A board groups by a select, status, multi-select or checkbox property.")
        if layout == "board" and group is None:
            group = next((p["id"] for p in props if p["type"] in ("status", "select")), None)
        hidden = raw.get("hidden", [])
        if not isinstance(hidden, list) or any(h not in by_id or by_id[h]["type"] == "title" for h in hidden):
            raise CommandError("Hide properties of this table (the title always shows).")
        widths = raw.get("widths", {})
        if not isinstance(widths, dict) or any(k not in by_id or type(v) is not int or not 60 <= v <= 800 for k, v in widths.items()):
            raise CommandError("Column widths are 60..800 pixels per property.")
        dated = [p["id"] for p in props if p["type"] in ("date", "created_time", "edited_time")]
        date_prop = raw.get("dateProp")
        if date_prop is not None and date_prop not in dated:
            raise CommandError("A timeline or calendar lays rows out by a date property.")
        if layout in DATED_LAYOUTS and date_prop is None:
            date_prop = dated[0] if dated else None
        return {"filters": clean_filters, "match": match, "sorts": clean_sorts, "groupBy": group,
                "hidden": list(dict.fromkeys(hidden)), "widths": widths, "dateProp": date_prop}

    # ------------------------------------------------------------------ plan 43 T2: relations, rollups, formulas

    def candidates(self, table_id: str, prop_id: str, query: str | None = None) -> list[dict[str, Any]]:
        """What a relation cell can link to: rows of the target table, or Cyclone's own records."""
        with self._c._lock:
            self._live_table(table_id)
            prop = next((p for p in self._props(table_id) if p["id"] == prop_id), None)
            if prop is None or prop["type"] != "relation":
                raise CommandError("That is not a relation property.")
            items = self._targets(prop["config"]["target"])
        needle = (query or "").strip().lower()
        return [i for i in items if not needle or needle in i["label"].lower()][:200]

    def _targets(self, target: str, ids: list[str] | None = None) -> list[dict[str, Any]]:
        """Linkable things of a target, with labels: all of them, or just [ids]."""
        db = self._c._db
        if TABLE_ID.match(target):
            table = db.execute("SELECT title, icon, archived_at FROM cc_table WHERE id = ?", (target,)).fetchone()
            if table is None:
                return []
            title = next((p for p in self._props(target) if p["type"] == "title"), None)
            sql = "SELECT id, cells FROM cc_row WHERE table_id = ? AND archived_at IS NULL"
            params: list[Any] = [target]
            if ids is not None:
                sql += f" AND id IN ({','.join('?' * len(ids))})"
                params += ids
            rows = db.execute(sql + " ORDER BY position LIMIT 2000", params).fetchall() if ids != [] else []
            return [{"id": r["id"], "label": (display(title, json.loads(r["cells"]).get(title["id"])) if title else "") or "Untitled",
                     "tableId": target} for r in rows]
        if target == "sys:accounts":
            rows = db.execute("SELECT id, handle, service FROM account ORDER BY handle").fetchall()
            items = [{"id": r["id"], "label": f"{r['handle']} · {r['service']}"} for r in rows]
        elif target == "sys:routines":
            items = [{"id": r["id"], "label": r["title"]} for r in db.execute("SELECT id, title FROM routine ORDER BY title")]
        elif target == "sys:tasks":
            items = [{"id": r["id"], "label": r["title"]} for r in db.execute("SELECT id, title FROM task ORDER BY created_at DESC LIMIT 500")]
        elif target == "sys:phones":
            try:
                listed = self._c._devices()
            except Exception:  # noqa: BLE001 - no phone list: phones already linked still show by their id
                listed = []
            items = [{"id": str(d.get("deviceId")), "label": str(d.get("name") or d.get("model") or d.get("deviceId"))}
                     for d in listed if d.get("deviceId")]
        else:
            items = []
        items = [{**i, "tableId": target} for i in items]
        if ids is not None:
            known = {i["id"]: i for i in items}
            return [known.get(i, {"id": i, "label": i if target == "sys:phones" else "Removed", "tableId": target}) for i in ids]
        return items

    def _check_links(self, prop: dict[str, Any], ids: list[str], old: list[str]) -> None:
        new = [i for i in ids if i not in old]
        if not new:
            return
        target = prop["config"]["target"]
        if TABLE_ID.match(target):
            found = {t["id"] for t in self._targets(target, new)}
        else:
            found = {t["id"] for t in self._targets(target) if t["id"] in new}
        missing = [i for i in new if i not in found]
        if missing:
            raise CommandError(f"{prop['name']} can only link to rows that exist and are not in the trash.")

    def _sync_links(self, table_id: str, row_id: str, props: list[dict[str, Any]], old: dict[str, Any], new: dict[str, Any], actor: str) -> None:
        """Two-way relations: when this row links or unlinks rows, their way back changes too."""
        for prop in props:
            if prop["type"] != "relation" or not prop["config"].get("backProp") or not TABLE_ID.match(prop["config"]["target"]):
                continue
            before, after = set(old.get(prop["id"]) or []), set(new.get(prop["id"]) or [])
            back = prop["config"]["backProp"]
            for other in (before ^ after):
                if other == row_id and prop["config"]["target"] == table_id and back == prop["id"]:
                    continue
                row = self._c._db.execute("SELECT id, cells FROM cc_row WHERE id = ?", (other,)).fetchone()
                if row is None:
                    continue
                cells = json.loads(row["cells"])
                links = [i for i in (cells.get(back) or []) if i != row_id]
                if other in after:
                    links.append(row_id)
                previous = cells.get(back) or []
                if links:
                    cells[back] = links
                else:
                    cells.pop(back, None)
                if links != previous:
                    self._c._db.execute("UPDATE cc_row SET cells = ?, version = version + 1, updated_at = ? WHERE id = ?",
                                        (json.dumps(cells, ensure_ascii=False), self._c._clock(), other))
                    self._history(other, actor, {"cells": {back: [previous or None, links or None]}, "linked": row_id})

    def _labels(self, props: list[dict[str, Any]], rows: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
        """The label of everything the rows link to, so a relation reads as names, not ids."""
        out: dict[str, dict[str, Any]] = {}
        for prop in props:
            if prop["type"] != "relation":
                continue
            ids = list(dict.fromkeys(i for r in rows for i in (r["cells"].get(prop["id"]) or [])))
            if ids:
                for item in self._targets(prop["config"]["target"], ids):
                    out[item["id"]] = {"label": item["label"], "tableId": item["tableId"]}
        return out

    def _rollup_type(self, table_id: str, props: list[dict[str, Any]], config: dict[str, Any]) -> str:
        relation = next((p for p in props if p["id"] == config["relation"] and p["type"] == "relation"), None)
        if relation is None:
            raise CommandError("A rollup is calculated over one of this table's relations.")
        fn = config["fn"]
        if fn in ("count", "count_values"):
            return "number"
        target = relation["config"]["target"]
        if not TABLE_ID.match(target):
            if fn != "show":
                raise CommandError("Over Accounts, Routines, Tasks or Phones a rollup can count or show them.")
            return "text"
        over = next((p for p in self._props(target) if p["id"] == config.get("property")), None)
        if over is None:
            raise CommandError("Pick the property of the linked table to calculate over.")
        if fn == "show":
            return "text"
        if fn in ("sum", "average", "min", "max"):
            if over["type"] not in ("number", "currency", "percent", "rollup", "formula"):
                raise CommandError(f"{fn.title()} needs a number property.")
            return "number"
        if fn in ("earliest", "latest"):
            if over["type"] not in ("date", "created_time", "edited_time"):
                raise CommandError(f"{fn.title()} needs a date property.")
            return "date"
        if over["type"] != "checkbox":
            raise CommandError("Percent checked needs a checkbox property.")
        return "number"

    def _check_formula(self, props: list[dict[str, Any]], expression: str, self_id: str | None) -> None:
        known = {p["name"] for p in props if p["id"] != self_id}
        unknown = sorted(fx.names(fx.parse(expression)) - known)
        if unknown:
            raise CommandError(f"Formula: this table has no property “{unknown[0]}”.")

    def _rename_in_formulas(self, table_id: str, old: str, new: str) -> None:
        for p in self._props(table_id):
            if p["type"] == "formula":
                expression = fx.rename(p["config"]["expression"], old, new)
                if expression != p["config"]["expression"]:
                    self._c._db.execute("UPDATE cc_property SET config = ? WHERE id = ?",
                                        (json.dumps({**p["config"], "expression": expression}, ensure_ascii=False), p["id"]))

    def _publics(self, table_id: str, props: list[dict[str, Any]], raw_rows: list[Any]) -> list[dict[str, Any]]:
        """Rows as Glass sees them, with created/edited times, rollups and formulas worked out."""
        rows = [self._row_public(r, props) for r in raw_rows]
        self._c.buttons.cells(table_id, props, rows)
        rollups = [p for p in props if p["type"] == "rollup"]
        formulas = [p for p in props if p["type"] == "formula"]
        if not rows or not (rollups or formulas):
            return rows
        by_id = {p["id"]: p for p in props}
        cache: dict[str, dict[str, Any]] = {}
        for prop in rollups:
            relation = by_id.get(prop["config"].get("relation"))
            if relation is None:
                continue
            target = relation["config"]["target"]
            ids = list(dict.fromkeys(i for r in rows for i in (r["cells"].get(relation["id"]) or [])))
            if TABLE_ID.match(target):
                missing = [i for i in ids if i not in cache]
                if missing:
                    target_props = self._props(target)
                    for chunk in range(0, len(missing), 500):
                        part = missing[chunk:chunk + 500]
                        found = self._c._db.execute(f"SELECT * FROM cc_row WHERE archived_at IS NULL AND id IN ({','.join('?' * len(part))})",
                                                    part).fetchall()
                        for r in self._publics(target, [p for p in target_props if p["type"] != "rollup"], found) if found else []:
                            cache[r["id"]] = {"cells": r["cells"], "props": {p["id"]: p for p in target_props}}
                labels = {}
            else:
                labels = {t["id"]: t["label"] for t in self._targets(target, ids)} if ids else {}
            for r in rows:
                linked = r["cells"].get(relation["id"]) or []
                r["cells"][prop["id"]] = _rollup(prop, linked, cache, labels, not TABLE_ID.match(target))
        today = _day(self._c._clock())["start"][:10]
        for r in rows:
            by_name = {p["name"]: p for p in props}
            doing: set[str] = set()

            def read(name: str, r: dict[str, Any] = r) -> Any:
                p = by_name.get(name)
                if p is None:
                    raise fx.FormulaError(f"No property “{name}”.")
                if p["type"] == "formula":
                    if p["id"] in doing:
                        raise fx.FormulaError("Formulas refer to each other in a circle.")
                    if p["id"] not in r["cells"]:
                        doing.add(p["id"])
                        r["cells"][p["id"]] = _formula(p, read, today)
                        doing.discard(p["id"])
                    return r["cells"][p["id"]]
                return _read_value(p, r["cells"].get(p["id"]))
            for prop in formulas:
                if prop["id"] not in r["cells"]:
                    doing.add(prop["id"])
                    r["cells"][prop["id"]] = _formula(prop, read, today)
                    doing.discard(prop["id"])
        return rows

    @staticmethod
    def _scrub_view(config: dict[str, Any], prop_id: str) -> dict[str, Any]:
        config["filters"] = [f for f in config.get("filters", []) if f["property"] != prop_id]
        config["sorts"] = [s for s in config.get("sorts", []) if s["property"] != prop_id]
        config["hidden"] = [h for h in config.get("hidden", []) if h != prop_id]
        config.get("widths", {}).pop(prop_id, None)
        if config.get("groupBy") == prop_id:
            config["groupBy"] = None
        return config

    def _cells(self, props: list[dict[str, Any]], changes: Any, old: dict[str, Any]) -> dict[str, Any]:
        if not isinstance(changes, dict):
            raise CommandError("cells is {propertyId: value}.")
        by_id = {p["id"]: p for p in props}
        unknown = [k for k in changes if k not in by_id]
        if unknown:
            raise CommandError("A cell names a property this table doesn't have.")
        cells = {k: v for k, v in old.items() if k in by_id and by_id[k]["type"] not in COMPUTED}
        for prop_id, value in changes.items():
            clean = cell_value(by_id[prop_id], value)
            if by_id[prop_id]["type"] == "relation" and clean:
                self._check_links(by_id[prop_id], clean, old.get(prop_id) or [])
            if clean is None or clean is False and by_id[prop_id]["type"] == "checkbox":
                cells.pop(prop_id, None)
            else:
                cells[prop_id] = clean
        return cells

    def _convert(self, table_id: str, prop: dict[str, Any], kind: str, config: dict[str, Any]) -> dict[str, Any]:
        """A property changed type: each cell is converted where it makes sense, otherwise cleared."""
        rows = self._c._db.execute("SELECT id, cells FROM cc_row WHERE table_id = ?", (table_id,)).fetchall()
        texts = {r["id"]: display(prop, json.loads(r["cells"]).get(prop["id"])) for r in rows}
        if kind in ("select", "multi_select", "status") and not config.get("options"):
            names: list[str] = []
            for text in texts.values():
                for piece in (text.split(",") if kind == "multi_select" else [text]):
                    piece = piece.strip()[:100]
                    if piece and piece.lower() not in {n.lower() for n in names} and len(names) < MAX_OPTIONS:
                        names.append(piece)
            config = _config(kind, {"options": [{"name": n, "color": COLORS[(i % (len(COLORS) - 1)) + 1],
                                                  **({"group": "todo"} if kind == "status" else {})} for i, n in enumerate(names)]})
        target = {"id": prop["id"], "name": prop["name"], "type": kind, "config": config}
        lookup = {o["name"].lower(): o["id"] for o in config.get("options", [])}
        for r in rows:
            cells = json.loads(r["cells"])
            text = texts[r["id"]]
            cells.pop(prop["id"], None)
            value: Any = None
            if text:
                if kind in ("text", "title"):
                    value = text[:MAX_TEXT]
                elif kind in ("number", "currency", "percent"):
                    try:
                        value = float(text.replace(",", ".").replace("€", "").replace("$", "").strip())
                        value = int(value) if value.is_integer() else value
                    except ValueError:
                        value = None
                elif kind in ("select", "status"):
                    value = lookup.get(text.strip().lower())
                elif kind == "multi_select":
                    value = [lookup[p.strip().lower()] for p in text.split(",") if p.strip().lower() in lookup] or None
                elif kind == "checkbox":
                    value = text.strip().lower() in ("yes", "true", "1", "x", "✓") or None
                else:
                    try:
                        value = cell_value(target, text)
                    except CommandError:
                        value = None
            if value is not None:
                cells[prop["id"]] = value
            self._c._db.execute("UPDATE cc_row SET cells = ? WHERE id = ?", (json.dumps(cells, ensure_ascii=False), r["id"]))
        for v in self._c._db.execute("SELECT id, config FROM cc_view WHERE table_id = ?", (table_id,)).fetchall():
            view_config = json.loads(v["config"])
            view_config["filters"] = [f for f in view_config.get("filters", []) if f["property"] != prop["id"]]
            if view_config.get("groupBy") == prop["id"] and kind not in GROUPABLE:
                view_config["groupBy"] = None
            self._c._db.execute("UPDATE cc_view SET config = ? WHERE id = ?", (json.dumps(view_config), v["id"]))
        return config

    def _drop_missing_options(self, table_id: str, prop: dict[str, Any], config: dict[str, Any]) -> None:
        keep = {o["id"] for o in config.get("options", [])}
        for r in self._c._db.execute("SELECT id, cells FROM cc_row WHERE table_id = ?", (table_id,)).fetchall():
            cells = json.loads(r["cells"])
            value = cells.get(prop["id"])
            if value is None:
                continue
            if isinstance(value, list):
                kept = [v for v in value if v in keep]
                if kept == value:
                    continue
                if kept:
                    cells[prop["id"]] = kept
                else:
                    cells.pop(prop["id"])
            elif value not in keep:
                cells.pop(prop["id"])
            else:
                continue
            self._c._db.execute("UPDATE cc_row SET cells = ? WHERE id = ?", (json.dumps(cells, ensure_ascii=False), r["id"]))

    def _position(self, table_id: str, before: Any, *, exclude: str | None = None) -> float:
        if before is None:
            top = self._c._db.execute("SELECT MAX(position) FROM cc_row WHERE table_id = ?", (table_id,)).fetchone()[0]
            return float(top) + 1.0 if top is not None else 1.0
        brow = self._row(table_id, before)
        prev = self._c._db.execute("SELECT MAX(position) FROM cc_row WHERE table_id = ? AND position < ? AND id IS NOT ?",
                                   (table_id, brow["position"], exclude)).fetchone()[0]
        return (brow["position"] + (prev if prev is not None else brow["position"] - 2)) / 2

    def _history(self, row_id: str, actor: str, change: dict[str, Any]) -> None:
        self._c._db.execute("INSERT INTO cc_row_history(row_id, at, actor, change) VALUES (?,?,?,?)",
                            (row_id, self._c._clock(), actor, json.dumps(change, ensure_ascii=False)))
        extra = self._c._db.execute("SELECT seq FROM cc_row_history WHERE row_id = ? ORDER BY seq DESC LIMIT -1 OFFSET ?",
                                    (row_id, MAX_HISTORY)).fetchall()
        self._c._db.executemany("DELETE FROM cc_row_history WHERE seq = ?", [(e["seq"],) for e in extra])

    def _touch(self, table_id: str) -> None:
        self._c._db.execute("UPDATE cc_table SET updated_at = ?, version = version + 1 WHERE id = ?", (self._c._clock(), table_id))


# ---------------------------------------------------------------------- filtering, sorting, grouping (pure)

def _day(ms: int) -> dict[str, str]:
    from datetime import datetime, timezone
    return {"start": datetime.fromtimestamp(ms / 1000, tz=timezone.utc).strftime("%Y-%m-%dT%H:%M")}


def _shown(prop: dict[str, Any], value: Any, labels: dict[str, dict[str, Any]]) -> str:
    """A cell as text for search and export: relations by their labels."""
    if prop["type"] == "relation":
        return ", ".join(labels.get(i, {}).get("label", "") for i in (value or []))
    return display(prop, value)


def _read_value(prop: dict[str, Any], value: Any) -> Any:
    """A property's value as a formula reads it: choices by name, links by count."""
    kind = prop["type"]
    if value is None:
        return 0.0 if kind in ("number", "currency", "percent") else None
    if kind in ("select", "status"):
        return display(prop, value)
    if kind == "multi_select":
        return display(prop, value)
    if kind == "relation":
        return float(len(value))
    if kind in ("number", "currency", "percent"):
        return float(value)
    if kind == "button":
        return display(prop, value)
    return value


def _formula(prop: dict[str, Any], read: Callable[[str], Any], today: str) -> Any:
    try:
        return fx.result(fx.evaluate(fx.parse(prop["config"]["expression"]), read, today=today))
    except (fx.FormulaError, TypeError, ValueError, OverflowError, ZeroDivisionError, RecursionError):
        return None


def _rollup(prop: dict[str, Any], linked: list[str], cache: dict[str, dict[str, Any]], labels: dict[str, str], system: bool) -> Any:
    fn = prop["config"]["fn"]
    if fn == "count":
        return float(len(linked))
    if system:
        return ", ".join(labels.get(i, "") for i in linked) or None if fn == "show" else None
    over = prop["config"].get("property")
    values = []
    for i in linked:
        row = cache.get(i)
        if row is None:
            continue
        p = row["props"].get(over)
        v = row["cells"].get(over)
        values.append((p, v))
    present = [(p, v) for p, v in values if v is not None and v != "" and v != []]
    if fn == "count_values":
        return float(len(present))
    if fn == "show":
        return ", ".join(display(p, v) if p else str(v) for p, v in present) or None
    if fn == "percent_checked":
        return round(100.0 * sum(1 for _, v in values if v is True) / len(linked), 1) if linked else None
    numbers = [float(v) for _, v in present if isinstance(v, (int, float)) and not isinstance(v, bool)]
    if fn in ("sum", "average", "min", "max"):
        if not numbers:
            return 0.0 if fn == "sum" else None
        return {"sum": sum(numbers), "average": sum(numbers) / len(numbers), "min": min(numbers), "max": max(numbers)}[fn]
    days = sorted(v["start"] for _, v in present if isinstance(v, dict) and "start" in v)
    if not days:
        return None
    return {"start": days[0] if fn == "earliest" else days[-1]}


def _kind(prop: dict[str, Any]) -> str:
    """The type a filter or sort treats a property as: a rollup by what it works out to."""
    if prop["type"] == "rollup":
        return {"number": "number", "date": "date", "text": "text"}.get(prop["config"].get("resultType", "number"), "number")
    return prop["type"]


def _csv_safe(text: str) -> str:
    """A cell never starts a spreadsheet formula."""
    return "'" + text if text[:1] in ("=", "+", "-", "@") and not re.match(r"^-?\d", text) else text


def _filter_value(prop: dict[str, Any], op: str, value: Any) -> Any:
    if op in ("empty", "not_empty"):
        return None
    kind = _kind(prop)
    if kind == "formula":
        if op in NUMBER_OPS:
            if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
                raise CommandError(f"{prop['name']} compares with a number.")
            return value
        return _text(value if isinstance(value, str) else "", "A filter's text", 200)
    if kind == "relation":
        if not isinstance(value, str) or not LINK_ID.match(value):
            raise CommandError(f"{prop['name']} filters by one linked row.")
        return value
    if kind in OPS_FOR and OPS_FOR[kind] is TEXT_OPS:
        return _text(value if isinstance(value, str) else "", "A filter's text", 200)
    if kind in ("number", "currency", "percent"):
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
            raise CommandError(f"{prop['name']} filters with a number.")
        return value
    if kind == "checkbox":
        if not isinstance(value, bool):
            raise CommandError(f"{prop['name']} filters with checked or not.")
        return value
    if kind in ("date", "created_time", "edited_time"):
        if not isinstance(value, str) or not DAY.match(value):
            raise CommandError(f"{prop['name']} filters with a date (YYYY-MM-DD).")
        return value[:10]
    options = {o["id"] for o in prop["config"].get("options", [])}
    if value not in options:
        raise CommandError(f"{prop['name']} filters with one of its options.")
    return value


def _test(prop: dict[str, Any], op: str, want: Any, value: Any) -> bool:
    empty = value is None or value == "" or value == [] or (prop["type"] == "checkbox" and value is False)
    if op == "empty":
        return empty
    if op == "not_empty":
        return not empty
    kind = _kind(prop)
    if kind == "formula":
        if op in NUMBER_OPS:
            if not isinstance(value, (int, float)) or isinstance(value, bool):
                return False
            return {"eq": value == want, "gt": value > want, "lt": value < want, "gte": value >= want, "lte": value <= want}[op]
        text, needle = fx.text_of(value).lower(), str(want or "").lower()
        return needle in text if op == "contains" else text == needle
    if kind == "relation":
        has = want in (value or [])
        return has if op == "contains" else not has
    if kind == "checkbox":
        return bool(value) == want
    if kind in ("number", "currency", "percent"):
        if empty:
            return op == "ne"
        return {"eq": value == want, "ne": value != want, "gt": value > want, "lt": value < want,
                "gte": value >= want, "lte": value <= want}[op]
    if kind in ("date", "created_time", "edited_time"):
        if empty:
            return False
        start = value["start"][:10]
        end = (value.get("end") or value["start"])[:10]
        return {"is": start <= want <= end, "before": start < want, "after": start > want,
                "on_or_before": start <= want, "on_or_after": start >= want}[op]
    if kind in ("select", "status"):
        return (value == want) if op == "is" else (value != want)
    if kind == "multi_select":
        has = want in (value or [])
        return has if op == "contains" else not has
    text = (value or "").lower()
    needle = (want or "").lower()
    return {"contains": needle in text, "not_contains": needle not in text, "is": text == needle, "is_not": text != needle,
            "starts_with": text.startswith(needle)}[op]


def _matches(row: dict[str, Any], filters: list[dict[str, Any]], match: str, props: dict[str, dict[str, Any]]) -> bool:
    if not filters:
        return True
    results = [_test(props[f["property"]], f["op"], f["value"], row["cells"].get(f["property"]))
               for f in filters if f["property"] in props]
    if not results:
        return True
    return all(results) if match == "and" else any(results)


def _sort_key(prop: dict[str, Any]) -> Callable[[dict[str, Any]], Any]:
    kind = _kind(prop)
    order = {o["id"]: i for i, o in enumerate(prop["config"].get("options", []))}

    def key(row: dict[str, Any]) -> Any:
        value = row["cells"].get(prop["id"])
        if value is None or value == [] or value == "":
            return (1, 0)  # Empty last, whichever way.
        if kind in ("number", "currency", "percent"):
            return (0, value) if isinstance(value, (int, float)) else (1, 0)
        if kind == "formula":
            return (0, value) if isinstance(value, (int, float)) and not isinstance(value, bool) else (0, fx.text_of(value).lower())
        if kind == "relation":
            return (0, len(value))
        if kind == "button":
            return (0, value.get("at", 0) if isinstance(value, dict) else 0)
        if kind in ("select", "status"):
            return (0, order.get(value, len(order)))
        if kind == "multi_select":
            return (0, min(order.get(v, len(order)) for v in value))
        if kind == "checkbox":
            return (0, 0 if value else 1)
        if kind in ("date", "created_time", "edited_time"):
            return (0, value["start"])
        return (0, str(value).lower())
    return key


def _groups(prop: dict[str, Any], rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Board columns: one per option in its order (checkbox: checked / not), then "No value"."""
    if prop["type"] == "checkbox":
        buckets = [{"key": True, "name": "Checked", "color": "green"}, {"key": False, "name": "Not checked", "color": "default"}]
        for b in buckets:
            b["rowIds"] = [r["id"] for r in rows if bool(r["cells"].get(prop["id"])) == b["key"]]
        return buckets
    buckets = [{"key": o["id"], "name": o["name"], "color": o["color"], **({"group": o["group"]} if "group" in o else {}), "rowIds": []}
               for o in prop["config"].get("options", [])]
    by_key = {b["key"]: b for b in buckets}
    none: list[str] = []
    for r in rows:
        value = r["cells"].get(prop["id"])
        keys = value if isinstance(value, list) else [value]
        placed = False
        for k in keys:
            if k in by_key:
                by_key[k]["rowIds"].append(r["id"])
                placed = True
        if not placed:
            none.append(r["id"])
    return [{"key": None, "name": f"No {prop['name']}", "color": "default", "rowIds": none}] + buckets
