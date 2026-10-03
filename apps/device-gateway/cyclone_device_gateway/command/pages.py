"""Pages (plan 33, C5): the Command Center's workspace. The owner writes pages in Glass (Notion-like blocks), nests them,
and references phones, skills, routines, tasks, accounts, connections and other pages from any of them. A page can
hold live views of Command Center data and planning boards (board, table or calendar).

Rules:
- **Typed blocks only.** Every block and every inline span is checked against a fixed shape; unknown fields are refused.
  Glass renders them with DOM APIs, so nothing in a page is ever markup or code.
- **No secrets.** Text that looks like a password, code or key is refused, like every other Command Center text.
  References carry ids and a short label, never values.
- **Saves are versioned.** A save names the version it edited; a save made on an older version is refused, so two
  Glass tabs cannot silently overwrite each other.
- **References are indexed** (``page_ref``), so a page, a routine or a phone can show the pages that mention it.
- **Trash first.** Deleting moves a page (and its sub-pages) to the trash; only a page already in the trash can be
  deleted for good.
"""
from __future__ import annotations

import json
import re
import secrets
from typing import TYPE_CHECKING, Any

from ..desktop_runtime.v5_contract import INLINE_SECRET

if TYPE_CHECKING:  # pragma: no cover
    from .center import CommandCenter

from .center import CommandError

PAGES_SCHEMA = """
CREATE TABLE IF NOT EXISTS page (
  id TEXT PRIMARY KEY, parent_id TEXT, title TEXT NOT NULL, icon TEXT NOT NULL, blocks TEXT NOT NULL, plain TEXT NOT NULL,
  position REAL NOT NULL, version INTEGER NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, archived_at INTEGER);
CREATE TABLE IF NOT EXISTS page_ref (page_id TEXT NOT NULL, kind TEXT NOT NULL, target TEXT NOT NULL, PRIMARY KEY(page_id, kind, target));
CREATE INDEX IF NOT EXISTS page_parent ON page(parent_id);
CREATE INDEX IF NOT EXISTS page_ref_target ON page_ref(kind, target);
"""

PAGE_ID = re.compile(r"^pg_[A-Za-z0-9_-]{8,40}$")
BLOCK_ID = re.compile(r"^b[A-Za-z0-9_-]{4,31}$")
ITEM_ID = re.compile(r"^i[A-Za-z0-9_-]{4,31}$")
REF_IDS: dict[str, re.Pattern[str]] = {
    "page": PAGE_ID,
    "device": re.compile(r"^[A-Za-z0-9._:-]{1,120}$"),
    "skill": re.compile(r"^you\.[a-f0-9]{12}$"),
    "routine": re.compile(r"^rtn_[A-Za-z0-9_-]{6,40}$"),
    "task": re.compile(r"^tsk_[A-Za-z0-9_-]{6,40}$"),
    "account": re.compile(r"^acc_[A-Za-z0-9_-]{6,40}$"),
    "connection": re.compile(r"^con_[A-Za-z0-9_-]{6,40}$"),
    # Plan 43 (T1): a table, mentioned or shown on a page.
    "table": re.compile(r"^tb_[A-Za-z0-9_-]{8,40}$"),
}
VIEW_REF = re.compile(r"^vw_[A-Za-z0-9_-]{6,40}$")
TEXT_BLOCKS = ("p", "h1", "h2", "h3", "todo", "bullet", "number", "quote", "callout")
VIEW_SOURCES = ("tasks", "routines", "approvals", "phones", "pages", "results", "accounts", "connections")
LAYOUTS = ("list", "table", "board", "calendar", "gallery")
BOARD_LAYOUTS = ("board", "table", "calendar")
ITEM_STATES = ("todo", "doing", "done")
MARKS = ("b", "i", "c", "s")
MAX_BLOCKS = 1000
MAX_SPANS = 300
MAX_BLOCK_TEXT = 10_000
MAX_ITEMS = 500
MAX_PAGE_BYTES = 1_000_000
MAX_PAGES = 5_000
MAX_DEPTH = 10
ICON = re.compile(r"^[^\x00-\x1f<>&\"'`]{0,8}$")

TEMPLATES: dict[str, dict[str, Any]] = {
    "blank": {"title": "Untitled", "icon": "", "blocks": []},
    "weekly": {"title": "Weekly plan", "icon": "🗓️", "blocks": [
        {"type": "callout", "icon": "💡", "text": [{"t": "Plan the week here. Mention a phone, routine or skill with @, and turn a card into a task when it is ready."}]},
        {"type": "h2", "text": [{"t": "This week"}]},
        {"type": "board", "title": "Plan", "layout": "board", "items": [
            {"title": "Decide this week's posts", "status": "todo"},
            {"title": "Check the shop's replies", "status": "doing"},
            {"title": "Review last week's results", "status": "done"}]},
        {"type": "h2", "text": [{"t": "Running now"}]},
        {"type": "view", "source": "tasks", "layout": "list", "filter": {"status": "open"}},
        {"type": "h2", "text": [{"t": "Notes"}]},
        {"type": "p", "text": []}]},
    "daily": {"title": "Daily app check", "icon": "✅", "blocks": [
        {"type": "h2", "text": [{"t": "Every morning"}]},
        {"type": "todo", "checked": False, "text": [{"t": "Waiting approvals are answered"}]},
        {"type": "todo", "checked": False, "text": [{"t": "Last night's routines succeeded"}]},
        {"type": "todo", "checked": False, "text": [{"t": "Every phone is online"}]},
        {"type": "h2", "text": [{"t": "Waiting for you"}]},
        {"type": "view", "source": "approvals", "layout": "list"},
        {"type": "h2", "text": [{"t": "Phones"}]},
        {"type": "view", "source": "phones", "layout": "gallery"},
        {"type": "h2", "text": [{"t": "Routines"}]},
        {"type": "view", "source": "routines", "layout": "table"}]},
    "content": {"title": "Content calendar", "icon": "🎬", "blocks": [
        {"type": "p", "text": [{"t": "What goes out when. Give each post a date, mention the account, and run it as a task when it is ready."}]},
        {"type": "board", "title": "Posts", "layout": "calendar", "items": []},
        {"type": "h2", "text": [{"t": "Recent results"}]},
        {"type": "view", "source": "results", "layout": "list"}]},
}


class PageConflict(CommandError):
    """A save made on an older version of the page."""


def _new(prefix: str, n: int = 10) -> str:
    return f"{prefix}{secrets.token_urlsafe(n)}"


def _text(value: Any, label: str, limit: int, *, allow_empty: bool = True) -> str:
    if not isinstance(value, str):
        raise CommandError(f"{label} must be text.")
    if len(value) > limit:
        raise CommandError(f"{label} is at most {limit} characters.")
    if not allow_empty and not value.strip():
        raise CommandError(f"{label} is required.")
    if any(ord(c) < 32 and c not in "\n\t" for c in value):
        raise CommandError(f"{label} has control characters.")
    if INLINE_SECRET.search(value):
        raise CommandError("That looks like a password, code or key. Pages never keep secrets; the vault does.")
    return value


def _only(value: dict[str, Any], allowed: set[str], label: str) -> None:
    extra = set(value) - allowed
    if extra:
        raise CommandError(f"{label}: unknown field {sorted(extra)[0]}.")


def _ref(value: Any) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise CommandError("A reference is {kind, id, label}.")
    _only(value, {"kind", "id", "label", "deviceId"}, "reference")
    kind, ident = value.get("kind"), value.get("id")
    if kind not in REF_IDS or not isinstance(ident, str) or not REF_IDS[kind].match(ident):
        raise CommandError("A reference names a page, phone, skill, routine, task, account, connection or table by its id.")
    out = {"kind": kind, "id": ident, "label": _text(value.get("label", ""), "A reference's label", 120).strip()}
    if kind == "skill" and value.get("deviceId") is not None:
        if not isinstance(value["deviceId"], str) or not REF_IDS["device"].match(value["deviceId"]):
            raise CommandError("A skill reference's deviceId is malformed.")
        out["deviceId"] = value["deviceId"]
    return out


def _spans(value: Any) -> list[dict[str, Any]]:
    if not isinstance(value, list) or len(value) > MAX_SPANS:
        raise CommandError(f"A block's text is a list of at most {MAX_SPANS} pieces.")
    out: list[dict[str, Any]] = []
    total = 0
    for span in value:
        if not isinstance(span, dict):
            raise CommandError("A piece of text is {t} or {ref}.")
        if "ref" in span:
            _only(span, {"ref"}, "mention")
            out.append({"ref": _ref(span["ref"])})
            continue
        _only(span, {"t", *MARKS}, "text")
        text = _text(span.get("t", ""), "Text", MAX_BLOCK_TEXT)
        total += len(text)
        piece: dict[str, Any] = {"t": text}
        for mark in MARKS:
            if span.get(mark) is True:
                piece[mark] = True
            elif mark in span and span[mark] is not False:
                raise CommandError("Text marks are true or false.")
        if text:
            out.append(piece)
    if total > MAX_BLOCK_TEXT:
        raise CommandError(f"A block holds at most {MAX_BLOCK_TEXT} characters.")
    return out


def _block(value: Any, ids: set[str]) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise CommandError("A block is an object.")
    kind = value.get("type")
    block_id = value.get("id")
    if block_id is None:
        block_id = _new("b", 9)
    if not isinstance(block_id, str) or not BLOCK_ID.match(block_id) or block_id in ids:
        raise CommandError("Each block has its own id.")
    ids.add(block_id)
    out: dict[str, Any] = {"id": block_id, "type": kind}
    if kind in TEXT_BLOCKS:
        _only(value, {"id", "type", "text", "checked", "icon"}, "block")
        out["text"] = _spans(value.get("text", []))
        if kind == "todo":
            if not isinstance(value.get("checked", False), bool):
                raise CommandError("A to-do is checked or not.")
            out["checked"] = bool(value.get("checked", False))
        if kind == "callout":
            icon = value.get("icon", "💡")
            if not isinstance(icon, str) or not ICON.match(icon):
                raise CommandError("A callout's icon is one emoji.")
            out["icon"] = icon
        return out
    if kind == "divider":
        _only(value, {"id", "type"}, "block")
        return out
    if kind == "ref":
        _only(value, {"id", "type", "ref"}, "block")
        out["ref"] = _ref(value.get("ref"))
        return out
    if kind == "view":
        _only(value, {"id", "type", "source", "layout", "filter", "title"}, "block")
        if value.get("source") not in VIEW_SOURCES:
            raise CommandError(f"A live view shows one of: {', '.join(VIEW_SOURCES)}.")
        if value.get("layout", "list") not in LAYOUTS:
            raise CommandError(f"A live view's layout is one of: {', '.join(LAYOUTS)}.")
        out.update(source=value["source"], layout=value.get("layout", "list"), title=_text(value.get("title", ""), "A view's title", 120))
        raw = value.get("filter") or {}
        if not isinstance(raw, dict):
            raise CommandError("A view's filter is an object.")
        _only(raw, {"status", "deviceId", "routineId", "accountId"}, "filter")
        flt: dict[str, Any] = {}
        if "status" in raw:
            if raw["status"] not in ("open", "done", "all"):
                raise CommandError("A view's status filter is open, done or all.")
            flt["status"] = raw["status"]
        for key, kind_name in (("deviceId", "device"), ("routineId", "routine"), ("accountId", "account")):
            if raw.get(key) is not None:
                if not isinstance(raw[key], str) or not REF_IDS[kind_name].match(raw[key]):
                    raise CommandError(f"A view's {key} is malformed.")
                flt[key] = raw[key]
        out["filter"] = flt
        return out
    if kind == "table":
        # Plan 43 (T1): one of the owner's tables, shown through one of its saved views. The rows live in the table.
        _only(value, {"id", "type", "tableId", "viewId"}, "block")
        table_id, view_id = value.get("tableId"), value.get("viewId")
        if not isinstance(table_id, str) or not REF_IDS["table"].match(table_id):
            raise CommandError("A table block names a table by its id.")
        if view_id is not None and (not isinstance(view_id, str) or not VIEW_REF.match(view_id)):
            raise CommandError("A table block's view id is malformed.")
        out.update(tableId=table_id, viewId=view_id)
        return out
    if kind == "board":
        _only(value, {"id", "type", "title", "layout", "items"}, "block")
        if value.get("layout", "board") not in BOARD_LAYOUTS:
            raise CommandError("A plan's layout is board, table or calendar.")
        items = value.get("items", [])
        if not isinstance(items, list) or len(items) > MAX_ITEMS:
            raise CommandError(f"A plan holds at most {MAX_ITEMS} cards.")
        item_ids: set[str] = set()
        out.update(title=_text(value.get("title", ""), "A plan's title", 120), layout=value.get("layout", "board"),
                   items=[_item(i, item_ids) for i in items])
        return out
    raise CommandError("Unknown block type.")


def _item(value: Any, ids: set[str]) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise CommandError("A card is an object.")
    _only(value, {"id", "title", "status", "due", "refs", "note", "taskId"}, "card")
    item_id = value.get("id") or _new("i", 9)
    if not isinstance(item_id, str) or not ITEM_ID.match(item_id) or item_id in ids:
        raise CommandError("Each card has its own id.")
    ids.add(item_id)
    status = value.get("status", "todo")
    if status not in ITEM_STATES:
        raise CommandError("A card is todo, doing or done.")
    due = value.get("due")
    if due is not None and (type(due) is not int or not 0 <= due <= 32_503_680_000_000):
        raise CommandError("A card's due date is a time in epoch milliseconds.")
    refs = value.get("refs", [])
    if not isinstance(refs, list) or len(refs) > 10:
        raise CommandError("A card links at most 10 things.")
    task = value.get("taskId")
    if task is not None and (not isinstance(task, str) or not REF_IDS["task"].match(task)):
        raise CommandError("A card's taskId is malformed.")
    return {"id": item_id, "title": _text(value.get("title", ""), "A card's title", 300), "status": status, "due": due,
            "refs": [_ref(r) for r in refs], "note": _text(value.get("note", ""), "A card's note", 2000), "taskId": task}


def validate_blocks(value: Any) -> list[dict[str, Any]]:
    if not isinstance(value, list) or len(value) > MAX_BLOCKS:
        raise CommandError(f"A page holds at most {MAX_BLOCKS} blocks.")
    ids: set[str] = set()
    blocks = [_block(b, ids) for b in value]
    if len(json.dumps(blocks, ensure_ascii=False).encode()) > MAX_PAGE_BYTES:
        raise CommandError("The page is too large (1 MB). Split it into sub-pages.")
    return blocks


def references(blocks: list[dict[str, Any]]) -> set[tuple[str, str]]:
    refs: set[tuple[str, str]] = set()
    for block in blocks:
        for span in block.get("text") or []:
            if "ref" in span:
                refs.add((span["ref"]["kind"], span["ref"]["id"]))
        if block["type"] == "ref":
            refs.add((block["ref"]["kind"], block["ref"]["id"]))
        if block["type"] == "table":
            refs.add(("table", block["tableId"]))
        for item in block.get("items") or []:
            refs.update((r["kind"], r["id"]) for r in item["refs"])
            if item.get("taskId"):
                refs.add(("task", item["taskId"]))
    return refs


def plain_text(blocks: list[dict[str, Any]]) -> str:
    parts: list[str] = []
    for block in blocks:
        for span in block.get("text") or []:
            parts.append(span["t"] if "t" in span else span["ref"]["label"])
        if block.get("title"):
            parts.append(block["title"])
        for item in block.get("items") or []:
            parts.extend((item["title"], item["note"]))
    return " ".join(p for p in parts if p)[:200_000]


class PageStore:
    """The workspace's pages, in the Command Center's database (same lock and audit chain)."""

    def __init__(self, center: "CommandCenter") -> None:
        self._c = center
        with center._lock:
            center._db.executescript(PAGES_SCHEMA)

    # ------------------------------------------------------------------ reading

    def tree(self) -> list[dict[str, Any]]:
        with self._c._lock:
            rows = self._c._db.execute("SELECT id, parent_id, title, icon, position, updated_at, created_at FROM page"
                                       " WHERE archived_at IS NULL ORDER BY position, created_at").fetchall()
            return [self._meta(r) for r in rows]

    def trash(self) -> list[dict[str, Any]]:
        with self._c._lock:
            rows = self._c._db.execute("SELECT id, parent_id, title, icon, position, updated_at, created_at, archived_at FROM page"
                                       " WHERE archived_at IS NOT NULL ORDER BY archived_at DESC LIMIT 200").fetchall()
            return [{**self._meta(r), "archivedAt": r["archived_at"]} for r in rows]

    @staticmethod
    def _meta(r: Any) -> dict[str, Any]:
        return {"id": r["id"], "parentId": r["parent_id"], "title": r["title"], "icon": r["icon"], "position": r["position"],
                "updatedAt": r["updated_at"], "createdAt": r["created_at"]}

    def get(self, page_id: str) -> dict[str, Any]:
        with self._c._lock:
            row = self._row(page_id)
            return self._public(row)

    def version(self, page_id: str) -> dict[str, Any]:
        """A cheap check Glass polls, so a page changed elsewhere (another window, the AI) shows up while open."""
        with self._c._lock:
            row = self._row(page_id)
            return {"id": row["id"], "version": row["version"], "updatedAt": row["updated_at"], "archivedAt": row["archived_at"]}

    def _row(self, page_id: Any) -> Any:
        if not isinstance(page_id, str) or not PAGE_ID.match(page_id):
            raise CommandError("No such page.")
        row = self._c._db.execute("SELECT * FROM page WHERE id = ?", (page_id,)).fetchone()
        if row is None:
            raise CommandError("No such page.")
        return row

    def _public(self, r: Any) -> dict[str, Any]:
        path = []
        parent = r["parent_id"]
        while parent and len(path) < MAX_DEPTH + 1:
            up = self._c._db.execute("SELECT id, parent_id, title, icon FROM page WHERE id = ?", (parent,)).fetchone()
            if up is None:
                break
            path.insert(0, {"id": up["id"], "title": up["title"], "icon": up["icon"]})
            parent = up["parent_id"]
        children = self._c._db.execute("SELECT id, parent_id, title, icon, position, updated_at, created_at FROM page"
                                       " WHERE parent_id = ? AND archived_at IS NULL ORDER BY position, created_at", (r["id"],)).fetchall()
        return {**self._meta(r), "blocks": json.loads(r["blocks"]), "version": r["version"], "archivedAt": r["archived_at"],
                "path": path, "children": [self._meta(c) for c in children], "backlinks": self._backlinks("page", r["id"])}

    def _backlinks(self, kind: str, target: str) -> list[dict[str, Any]]:
        rows = self._c._db.execute(
            "SELECT p.id, p.parent_id, p.title, p.icon, p.position, p.updated_at, p.created_at FROM page_ref r JOIN page p ON p.id = r.page_id"
            " WHERE r.kind = ? AND r.target = ? AND p.archived_at IS NULL AND p.id != ? ORDER BY p.updated_at DESC LIMIT 50",
            (kind, target, target if kind == "page" else "")).fetchall()
        return [self._meta(r) for r in rows]

    def backlinks(self, kind: Any, target: Any) -> list[dict[str, Any]]:
        if kind not in REF_IDS or not isinstance(target, str) or not REF_IDS[kind].match(target):
            raise CommandError("Name a page, phone, skill, routine, task, account or connection by its id.")
        with self._c._lock:
            return self._backlinks(kind, target)

    def search(self, query: Any) -> list[dict[str, Any]]:
        if not isinstance(query, str) or not 1 <= len(query.strip()) <= 100:
            raise CommandError("Search for 1..100 characters.")
        needle = "%" + query.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        with self._c._lock:
            rows = self._c._db.execute(
                "SELECT id, parent_id, title, icon, position, updated_at, created_at, plain FROM page WHERE archived_at IS NULL"
                " AND (title LIKE ? ESCAPE '\\' OR plain LIKE ? ESCAPE '\\') ORDER BY (title LIKE ? ESCAPE '\\') DESC, updated_at DESC LIMIT 50",
                (needle, needle, needle)).fetchall()
            out = []
            q = query.strip().lower()
            for r in rows:
                plain = r["plain"]
                at = plain.lower().find(q)
                snippet = plain[max(0, at - 40): at + 80] if at >= 0 else ""
                out.append({**self._meta(r), "snippet": snippet})
            return out

    # ------------------------------------------------------------------ writing

    def create(self, body: Any, *, actor: str = "owner") -> dict[str, Any]:
        if not isinstance(body, dict):
            raise CommandError("Send {title?, icon?, parentId?, template?}.")
        _only(body, {"title", "icon", "parentId", "template", "blocks"}, "page")
        template = body.get("template", "blank")
        if template not in TEMPLATES:
            raise CommandError(f"template is one of: {', '.join(TEMPLATES)}.")
        base = TEMPLATES[template]
        title = _text(body.get("title", base["title"]), "The title", 200).strip() or "Untitled"
        icon = body.get("icon", base["icon"])
        if not isinstance(icon, str) or not ICON.match(icon):
            raise CommandError("The icon is one emoji.")
        blocks = validate_blocks(body["blocks"] if "blocks" in body else json.loads(json.dumps(base["blocks"])))
        with self._c._lock:
            if self._c._db.execute("SELECT COUNT(*) FROM page").fetchone()[0] >= MAX_PAGES:
                raise CommandError(f"The workspace holds at most {MAX_PAGES} pages. Empty the trash or delete some first.")
            parent = body.get("parentId")
            if parent is not None:
                prow = self._row(parent)
                if prow["archived_at"] is not None:
                    raise CommandError("That page is in the trash.")
                if self._depth(parent) >= MAX_DEPTH:
                    raise CommandError(f"Pages nest at most {MAX_DEPTH} deep.")
            now = self._c._clock()
            page_id = _new("pg_", 12)
            self._c._db.execute(
                "INSERT INTO page(id, parent_id, title, icon, blocks, plain, position, version, created_at, updated_at, archived_at)"
                " VALUES (?,?,?,?,?,?,?,?,?,?,NULL)",
                (page_id, parent, title, icon, json.dumps(blocks, ensure_ascii=False), plain_text(blocks), self._next_position(parent), 1, now, now))
            self._index(page_id, blocks)
            self._c._audit(actor, "page.create", page_id, {"parent": parent, "template": template})
            return self._public(self._row(page_id))

    def update(self, page_id: str, body: Any, *, actor: str = "owner") -> dict[str, Any]:
        if not isinstance(body, dict) or "version" not in body:
            raise CommandError("Send {version, title?, icon?, blocks?}.")
        _only(body, {"version", "title", "icon", "blocks"}, "page")
        fields: dict[str, Any] = {}
        if "title" in body:
            fields["title"] = _text(body["title"], "The title", 200).strip() or "Untitled"
        if "icon" in body:
            if not isinstance(body["icon"], str) or not ICON.match(body["icon"]):
                raise CommandError("The icon is one emoji.")
            fields["icon"] = body["icon"]
        blocks = validate_blocks(body["blocks"]) if "blocks" in body else None
        with self._c._lock:
            row = self._row(page_id)
            if row["archived_at"] is not None:
                raise CommandError("That page is in the trash. Restore it to edit it.")
            if body["version"] != row["version"]:
                raise PageConflict("This page changed in another tab or window since you opened it. It has been reloaded.")
            if blocks is not None:
                fields["blocks"] = json.dumps(blocks, ensure_ascii=False)
                fields["plain"] = plain_text(blocks)
            if not fields:
                return self._public(row)
            sets = ", ".join(f"{k} = ?" for k in fields)
            self._c._db.execute(f"UPDATE page SET {sets}, version = version + 1, updated_at = ? WHERE id = ?",
                                (*fields.values(), self._c._clock(), page_id))
            if blocks is not None:
                self._index(page_id, blocks)
            if "title" in fields and fields["title"] != row["title"]:
                self._c._audit(actor, "page.rename", page_id)
            elif actor != "owner":
                self._c._audit(actor, "page.edit", page_id)
            return self._public(self._row(page_id))

    def move(self, page_id: str, body: Any) -> dict[str, Any]:
        if not isinstance(body, dict):
            raise CommandError("Send {parentId, before?}.")
        _only(body, {"parentId", "before"}, "move")
        with self._c._lock:
            row = self._row(page_id)
            parent = body.get("parentId")
            if parent is not None:
                prow = self._row(parent)
                if prow["archived_at"] is not None:
                    raise CommandError("That page is in the trash.")
                walk: Any = parent
                while walk:
                    if walk == page_id:
                        raise CommandError("A page cannot move inside itself.")
                    walk = self._c._db.execute("SELECT parent_id FROM page WHERE id = ?", (walk,)).fetchone()["parent_id"]
                if self._depth(parent) + self._height(page_id) >= MAX_DEPTH:
                    raise CommandError(f"Pages nest at most {MAX_DEPTH} deep.")
            before = body.get("before")
            if before is not None:
                brow = self._row(before)
                if brow["parent_id"] != parent or before == page_id:
                    raise CommandError("Place it before a page under the same parent.")
                prev = self._c._db.execute("SELECT MAX(position) FROM page WHERE parent_id IS ? AND position < ? AND id != ? AND archived_at IS NULL",
                                           (parent, brow["position"], page_id)).fetchone()[0]
                position = (brow["position"] + (prev if prev is not None else brow["position"] - 2)) / 2
            else:
                position = self._next_position(parent, exclude=page_id)
            self._c._db.execute("UPDATE page SET parent_id = ?, position = ?, updated_at = ? WHERE id = ?", (parent, position, self._c._clock(), page_id))
            self._c._audit("owner", "page.move", page_id, {"parent": parent, "from": row["parent_id"]})
            return self._public(self._row(page_id))

    def archive(self, page_id: str) -> dict[str, Any]:
        with self._c._lock:
            self._row(page_id)
            ids = self._subtree(page_id)
            now = self._c._clock()
            self._c._db.executemany("UPDATE page SET archived_at = ? WHERE id = ? AND archived_at IS NULL", [(now, i) for i in ids])
            self._c._audit("owner", "page.archive", page_id, {"pages": len(ids)})
            return {"id": page_id, "archived": len(ids)}

    def restore(self, page_id: str) -> dict[str, Any]:
        with self._c._lock:
            row = self._row(page_id)
            if row["archived_at"] is None:
                raise CommandError("That page is not in the trash.")
            ids = [i for i in self._subtree(page_id) if self._c._db.execute("SELECT archived_at FROM page WHERE id = ?", (i,)).fetchone()["archived_at"] == row["archived_at"]]
            self._c._db.executemany("UPDATE page SET archived_at = NULL WHERE id = ?", [(i,) for i in ids])
            parent = row["parent_id"]
            if parent and self._c._db.execute("SELECT archived_at FROM page WHERE id = ?", (parent,)).fetchone()["archived_at"] is not None:
                self._c._db.execute("UPDATE page SET parent_id = NULL, position = ? WHERE id = ?", (self._next_position(None), page_id))
            self._c._audit("owner", "page.restore", page_id, {"pages": len(ids)})
            return self._public(self._row(page_id))

    def delete(self, page_id: str) -> dict[str, Any]:
        with self._c._lock:
            row = self._row(page_id)
            if row["archived_at"] is None:
                raise CommandError("Move the page to the trash first.")
            ids = self._subtree(page_id)
            self._c._db.executemany("DELETE FROM page WHERE id = ?", [(i,) for i in ids])
            self._c._db.executemany("DELETE FROM page_ref WHERE page_id = ?", [(i,) for i in ids])
            self._c._audit("owner", "page.delete", page_id, {"pages": len(ids)})
            return {"id": page_id, "deleted": len(ids)}

    # ------------------------------------------------------------------ helpers

    def _index(self, page_id: str, blocks: list[dict[str, Any]]) -> None:
        self._c._db.execute("DELETE FROM page_ref WHERE page_id = ?", (page_id,))
        self._c._db.executemany("INSERT INTO page_ref(page_id, kind, target) VALUES (?,?,?)", [(page_id, k, t) for k, t in sorted(references(blocks))])

    def _next_position(self, parent: str | None, *, exclude: str | None = None) -> float:
        top = self._c._db.execute("SELECT MAX(position) FROM page WHERE parent_id IS ? AND id IS NOT ?", (parent, exclude)).fetchone()[0]
        return float(top) + 1.0 if top is not None else 1.0

    def _depth(self, page_id: str) -> int:
        depth, walk = 1, self._c._db.execute("SELECT parent_id FROM page WHERE id = ?", (page_id,)).fetchone()["parent_id"]
        while walk and depth <= MAX_DEPTH + 1:
            depth += 1
            walk = self._c._db.execute("SELECT parent_id FROM page WHERE id = ?", (walk,)).fetchone()["parent_id"]
        return depth

    def _height(self, page_id: str) -> int:
        kids = [r["id"] for r in self._c._db.execute("SELECT id FROM page WHERE parent_id = ?", (page_id,))]
        return 1 + max((self._height(k) for k in kids), default=0)

    def _subtree(self, page_id: str) -> list[str]:
        out, todo = [], [page_id]
        while todo:
            current = todo.pop()
            out.append(current)
            todo.extend(r["id"] for r in self._c._db.execute("SELECT id FROM page WHERE parent_id = ?", (current,)))
        return out
