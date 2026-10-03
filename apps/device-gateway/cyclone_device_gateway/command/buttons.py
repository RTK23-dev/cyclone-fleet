"""Action buttons (plan 43 T3): a table property that does something for its row.

A button holds an ordered list of actions:

- **routine**: run a Command Center routine now (on its own phones);
- **prompt**: a new request for a phone, written as a template filled from the row (`{{Customer}}`, `{{Bank.Name}}`);
- **skill**: a phone's saved skill, by name, with an optional template for its inputs;
- **update**: set some of the row's properties ("Status → Checked", a date to `@today`);
- **open**: a link, opened in the owner's browser.

At most one phone action (routine, prompt or skill) per button. Where it runs: any ready phone, one fixed phone, or
the phone linked in the row. After the run, the result can go back into the row (properties to set on success or on
failure, the run's summary into a text property). The button cell shows the latest run's state.

Rules: a press only ever creates ordinary Command Center tasks, so the phone's approvals still ask for pay, send,
delete, permission and sign-in steps. A press never approves anything. Templates fill only from the row's own
properties and linked rows, never from the vault.
"""
from __future__ import annotations

import json
import re
from typing import TYPE_CHECKING, Any

from .center import CommandError, _id
from .pages import _only

if TYPE_CHECKING:  # pragma: no cover
    from .center import CommandCenter

BUTTON_SCHEMA = """
CREATE TABLE IF NOT EXISTS button_run (
  id TEXT PRIMARY KEY, table_id TEXT NOT NULL, row_id TEXT NOT NULL, prop_id TEXT NOT NULL, task_ids TEXT NOT NULL, state TEXT NOT NULL,
  summary TEXT NOT NULL DEFAULT '', actor TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS button_run_row ON button_run(table_id, row_id, prop_id, created_at);
"""

ACTIONS = ("routine", "prompt", "skill", "update", "open")
PHONE_ACTIONS = ("routine", "prompt", "skill")
COLORS = ("default", "gray", "brown", "orange", "yellow", "green", "blue", "purple", "pink", "red")
MAX_ACTIONS = 6
MAX_PROMPT = 1_500
PROP_ID = re.compile(r"^pr_[A-Za-z0-9_-]{4,40}$")
ROUTINE_ID = re.compile(r"^rtn_[A-Za-z0-9_-]{6,40}$")
DEVICE_ID = re.compile(r"^[A-Za-z0-9._:-]{1,120}$")
URL = re.compile(r"^https?://[^\s<>\"]{1,1990}$")
FIELD = re.compile(r"\{\{\s*([^{}.]{1,80}?)\s*(?:\.\s*([^{}]{1,80}?)\s*)?\}\}")
#: Special update values: today's date, and now (date and time).
TODAY, NOW = "@today", "@now"
#: A task's state -> the button's run state.
RUN_STATE = {"scheduled": "queued", "making": "queued", "waiting_device": "queued", "running": "running", "needs_you": "needs_you",
             "succeeded": "done", "failed": "failed", "cancelled": "cancelled"}
STATE_LABEL = {"queued": "Queued", "running": "Running", "needs_you": "Needs you", "done": "Done ✓", "failed": "Failed", "cancelled": "Cancelled",
               "updated": "Done ✓"}
OPEN_STATES = ("queued", "running", "needs_you")


def button_config(raw: Any) -> dict[str, Any]:
    """A button's settings, checked in shape (the properties it names are checked against the table when saved)."""
    raw = raw or {}
    if not isinstance(raw, dict):
        raise CommandError("A button's settings are an object.")
    _only(raw, {"label", "color", "actions", "runOn", "then"}, "button")
    label = raw.get("label", "Run")
    if not isinstance(label, str) or not label.strip() or len(label) > 40:
        raise CommandError("A button's label is 1..40 characters.")
    color = raw.get("color", "purple")
    if color not in COLORS:
        raise CommandError(f"A button's colour is one of: {', '.join(COLORS)}.")
    actions = raw.get("actions", [])
    if not isinstance(actions, list) or len(actions) > MAX_ACTIONS:
        raise CommandError(f"A button does at most {MAX_ACTIONS} things.")
    out_actions = [_action(a) for a in actions]
    if sum(1 for a in out_actions if a["do"] in PHONE_ACTIONS) > 1:
        raise CommandError("A button runs at most one routine, prompt or skill. Add another button for more.")
    run_on = raw.get("runOn") or {"kind": "any"}
    if not isinstance(run_on, dict) or run_on.get("kind") not in ("any", "phone", "row"):
        raise CommandError("Where it runs is any ready phone, one phone, or the phone in a row's property.")
    if run_on["kind"] == "phone":
        _only(run_on, {"kind", "deviceId"}, "where it runs")
        if not isinstance(run_on.get("deviceId"), str) or not DEVICE_ID.match(run_on["deviceId"]):
            raise CommandError("Pick the phone it runs on.")
    elif run_on["kind"] == "row":
        _only(run_on, {"kind", "propId"}, "where it runs")
        if not isinstance(run_on.get("propId"), str) or not PROP_ID.match(run_on["propId"]):
            raise CommandError("Pick the property that holds the row's phone.")
    else:
        _only(run_on, {"kind"}, "where it runs")
    then = raw.get("then") or {}
    if not isinstance(then, dict):
        raise CommandError("What happens after is an object.")
    _only(then, {"success", "failure", "summary"}, "after the run")
    out_then: dict[str, Any] = {}
    for key in ("success", "failure"):
        if then.get(key) is not None:
            out_then[key] = _cells(then[key])
    if then.get("summary") is not None:
        if not isinstance(then["summary"], str) or not PROP_ID.match(then["summary"]):
            raise CommandError("The run's summary goes into one of this table's text properties.")
        out_then["summary"] = then["summary"]
    return {"label": label.strip(), "color": color, "actions": out_actions, "runOn": dict(run_on), "then": out_then}


def _action(raw: Any) -> dict[str, Any]:
    if not isinstance(raw, dict) or raw.get("do") not in ACTIONS:
        raise CommandError(f"Each action is one of: {', '.join(ACTIONS)}.")
    kind = raw["do"]
    if kind == "routine":
        _only(raw, {"do", "routineId"}, "run routine")
        if not isinstance(raw.get("routineId"), str) or not ROUTINE_ID.match(raw["routineId"]):
            raise CommandError("Pick the routine to run.")
        return {"do": kind, "routineId": raw["routineId"]}
    if kind == "prompt":
        _only(raw, {"do", "prompt"}, "prompt")
        return {"do": kind, "prompt": _template(raw.get("prompt"), required=True)}
    if kind == "skill":
        _only(raw, {"do", "skill", "prompt"}, "run skill")
        skill = raw.get("skill")
        if not isinstance(skill, str) or not skill.strip() or len(skill) > 80:
            raise CommandError("Name the saved skill to run (up to 80 characters).")
        return {"do": kind, "skill": skill.strip(), "prompt": _template(raw.get("prompt"), required=False)}
    if kind == "update":
        _only(raw, {"do", "cells"}, "update the row")
        cells = _cells(raw.get("cells"))
        if not cells:
            raise CommandError("An update sets at least one property.")
        return {"do": kind, "cells": cells}
    _only(raw, {"do", "url"}, "open")
    url = raw.get("url")
    if not isinstance(url, str) or not URL.match(url.strip()):
        raise CommandError("Open takes an http(s) link.")
    return {"do": kind, "url": url.strip()}


def _template(value: Any, *, required: bool) -> str:
    if value is None or value == "":
        if required:
            raise CommandError("Write what the phone should do.")
        return ""
    if not isinstance(value, str) or len(value) > MAX_PROMPT:
        raise CommandError(f"A prompt is at most {MAX_PROMPT} characters.")
    return value.strip()


def _cells(value: Any) -> dict[str, Any]:
    if not isinstance(value, dict) or len(value) > 20 or not all(isinstance(k, str) and PROP_ID.match(k) for k in value):
        raise CommandError("Properties to set are {propertyId: value}, at most 20.")
    return dict(value)


class ButtonStore:
    def __init__(self, center: "CommandCenter") -> None:
        self._c = center
        with center._lock:
            center._db.executescript(BUTTON_SCHEMA)

    # ------------------------------------------------------------------ checking a button against its table

    def check(self, props: list[dict[str, Any]], config: dict[str, Any], self_id: str | None) -> None:
        """The properties a button names exist and take the values it sets; its routine exists."""
        from .tables import COMPUTED, cell_value
        by_id = {p["id"]: p for p in props if p["id"] != self_id}

        def cells(values: dict[str, Any]) -> None:
            for prop_id, value in values.items():
                prop = by_id.get(prop_id)
                if prop is None or prop["type"] in COMPUTED or prop["type"] == "button":
                    raise CommandError("A button sets only this table's own editable properties.")
                if value in (TODAY, NOW):
                    if prop["type"] != "date":
                        raise CommandError(f"{value} fits only a date property.")
                    continue
                cell_value(prop, value)

        for action in config["actions"]:
            if action["do"] == "update":
                cells(action["cells"])
            elif action["do"] == "routine":
                self._c.get_routine(action["routineId"])
            elif action["do"] in ("prompt", "skill"):
                self._template_names(props, action["prompt"])
        for key in ("success", "failure"):
            if key in config["then"]:
                cells(config["then"][key])
        summary = config["then"].get("summary")
        if summary is not None and (summary not in by_id or by_id[summary]["type"] != "text"):
            raise CommandError("The run's summary goes into a text property.")
        if config["runOn"]["kind"] == "row":
            prop = by_id.get(config["runOn"]["propId"])
            if prop is None or prop["type"] != "relation" or prop["config"].get("target") != "sys:phones":
                raise CommandError("The row's phone comes from a relation to Phones.")

    def _template_names(self, props: list[dict[str, Any]], template: str) -> None:
        names = {p["name"].lower(): p for p in props}
        for match in FIELD.finditer(template):
            prop = names.get(match.group(1).strip().lower())
            if prop is None:
                raise CommandError(f"The prompt names “{match.group(1).strip()}”, which this table doesn't have.")
            if match.group(2) and prop["type"] != "relation":
                raise CommandError(f"“{prop['name']}.{match.group(2).strip()}” reads through a relation; {prop['name']} is not one.")

    # ------------------------------------------------------------------ pressing

    def press(self, table_id: str, row_id: str, prop_id: str, *, actor: str = "owner") -> dict[str, Any]:
        """Runs the button for one row: updates now, a phone task when it has one. Never approves anything."""
        tables = self._c.tables
        with self._c._lock:
            table = tables.get(table_id)
            prop = next((p for p in table["properties"] if p["id"] == prop_id), None)
            if prop is None or prop["type"] != "button":
                raise CommandError("No such button.")
            row = tables.get_row(table_id, row_id)
            config = prop["config"]
            if not config["actions"]:
                raise CommandError(f"“{config['label']}” has no actions yet. Edit the button to add one.")
            open_run = self._c._db.execute(f"SELECT id FROM button_run WHERE table_id = ? AND row_id = ? AND prop_id = ? AND state IN "
                                           f"({','.join('?' * len(OPEN_STATES))})", (table_id, row_id, prop_id, *OPEN_STATES)).fetchone()
            if open_run is not None:
                raise CommandError(f"“{config['label']}” is still running for this row.")
            tasks: list[str] = []
            opens: list[str] = []
            for action in config["actions"]:
                if action["do"] == "update":
                    tables.update_row(table_id, row_id, {"cells": self._values(action["cells"])}, actor=actor)
                elif action["do"] == "open":
                    opens.append(action["url"])
                elif action["do"] == "routine":
                    created = self._c.run_routine_now(action["routineId"])
                    tasks += [t["id"] for t in created.get("tasks") or []]
                else:
                    tasks.append(self._task(table, row, config, action, actor)["id"])
            now = self._c._clock()
            run_id = _id("btn")
            state = "queued" if tasks else "updated"
            self._c._db.execute("INSERT INTO button_run(id, table_id, row_id, prop_id, task_ids, state, summary, actor, created_at, updated_at)"
                                " VALUES (?,?,?,?,?,?,?,?,?,?)", (run_id, table_id, row_id, prop_id, json.dumps(tasks), state, "", actor, now, now))
            self._c._audit(actor, "table.button.press", table_id, {"row": row_id, "button": prop_id, "tasks": tasks})
            if not tasks:
                self._then(table_id, row_id, config, "success", "")
            return {"runId": run_id, "state": state, "tasks": tasks, "open": opens}

    def _task(self, table: dict[str, Any], row: dict[str, Any], config: dict[str, Any], action: dict[str, Any], actor: str) -> dict[str, Any]:
        filled = self.fill(table, row, action["prompt"])
        if action["do"] == "skill":
            goal = f"Run your saved skill “{action['skill']}”." + (f" {filled}" if filled else "")
        else:
            goal = filled
        title_prop = next((p for p in table["properties"] if p["type"] == "title"), None)
        name = str(row["cells"].get(title_prop["id"]) or "") if title_prop else ""
        body: dict[str, Any] = {"title": f"{config['label']}: {name}".strip(": ")[:80] or config["label"], "goal": goal}
        device = self._device(table, row, config["runOn"])
        if device:
            body["deviceId"] = device
        return self._c.create_task(body, actor=actor)

    def fill(self, table: dict[str, Any], row: dict[str, Any], template: str) -> str:
        """{{Property}} -> the row's value as text; {{Relation.Property}} -> the linked rows' values."""
        from .tables import display
        props = {p["name"].lower(): p for p in table["properties"]}

        def value(match: re.Match[str]) -> str:
            prop = props.get(match.group(1).strip().lower())
            if prop is None:
                return ""
            cell = row["cells"].get(prop["id"])
            if not match.group(2):
                if prop["type"] == "relation":
                    return ", ".join(self._labels(prop, cell or []))
                return display(prop, cell)
            if prop["type"] != "relation":
                return ""
            return ", ".join(self._linked(prop, cell or [], match.group(2).strip()))

        return FIELD.sub(value, template).strip()[:1800]

    def _labels(self, prop: dict[str, Any], ids: list[str]) -> list[str]:
        return [t["label"] for t in self._c.tables._targets(prop["config"]["target"], ids)] if ids else []

    def _linked(self, prop: dict[str, Any], ids: list[str], name: str) -> list[str]:
        from .tables import TABLE_ID, display
        target = prop["config"]["target"]
        if not ids or not TABLE_ID.match(target):
            return self._labels(prop, ids)
        other = self._c.tables.get(target)
        field = next((p for p in other["properties"] if p["name"].lower() == name.lower()), None)
        if field is None:
            return []
        out = []
        for linked in ids:
            try:
                out.append(display(field, self._c.tables.get_row(target, linked)["cells"].get(field["id"])))
            except CommandError:
                continue
        return [v for v in out if v]

    def _device(self, table: dict[str, Any], row: dict[str, Any], run_on: dict[str, Any]) -> str | None:
        if run_on["kind"] == "phone":
            return run_on["deviceId"]
        if run_on["kind"] == "row":
            linked = row["cells"].get(run_on["propId"])
            if isinstance(linked, list) and linked:
                return linked[0]
            raise CommandError("This row has no phone yet: link one in its phone property.")
        return None

    def _values(self, cells: dict[str, Any]) -> dict[str, Any]:
        from datetime import datetime
        now = datetime.fromtimestamp(self._c._clock() / 1000).astimezone()
        out = {}
        for prop_id, value in cells.items():
            if value == TODAY:
                value = {"start": now.strftime("%Y-%m-%d")}
            elif value == NOW:
                value = {"start": now.strftime("%Y-%m-%dT%H:%M")}
            out[prop_id] = value
        return out

    # ------------------------------------------------------------------ following runs

    def sync(self) -> None:
        """Called every engine tick: a finished run writes its result into the row."""
        with self._c._lock:
            runs = self._c._db.execute(f"SELECT * FROM button_run WHERE state IN ({','.join('?' * len(OPEN_STATES))})", OPEN_STATES).fetchall()
            for run in runs:
                ids = json.loads(run["task_ids"])
                rows = [self._c._db.execute("SELECT status, cause FROM task WHERE id = ?", (i,)).fetchone() for i in ids]
                states = [RUN_STATE.get(r["status"], "queued") if r else "failed" for r in rows]
                state = ("needs_you" if "needs_you" in states else "running" if "running" in states else "queued" if "queued" in states
                         else "failed" if "failed" in states else "cancelled" if "cancelled" in states else "done")
                summary = run["summary"]
                if state not in OPEN_STATES:
                    summary = self._summary(ids)
                if state != run["state"] or summary != run["summary"]:
                    self._c._db.execute("UPDATE button_run SET state = ?, summary = ?, updated_at = ? WHERE id = ?",
                                        (state, summary, self._c._clock(), run["id"]))
                if state in ("done", "failed"):
                    try:
                        prop = next((p for p in self._c.tables.get(run["table_id"])["properties"] if p["id"] == run["prop_id"]), None)
                        if prop is not None:
                            self._then(run["table_id"], run["row_id"], prop["config"], "success" if state == "done" else "failure", summary)
                    except CommandError:
                        pass  # the table or row is gone; the run stays in history

    def _summary(self, task_ids: list[str]) -> str:
        parts = []
        for task_id in task_ids:
            run = self._c._db.execute("SELECT summary, cause FROM run WHERE task_id = ? ORDER BY started_at DESC LIMIT 1", (task_id,)).fetchone()
            task = self._c._db.execute("SELECT cause FROM task WHERE id = ?", (task_id,)).fetchone()
            text = (run["summary"] if run and run["summary"] else "") or (run["cause"] if run else "") or (task["cause"] if task else "")
            if text:
                parts.append(text)
        return " · ".join(parts)[:1900]

    def _then(self, table_id: str, row_id: str, config: dict[str, Any], outcome: str, summary: str) -> None:
        cells = dict(self._values(config["then"].get(outcome) or {}))
        if summary and config["then"].get("summary"):
            cells[config["then"]["summary"]] = summary[:1900]
        if cells:
            try:
                self._c.tables.update_row(table_id, row_id, {"cells": cells}, actor="button")
            except CommandError:
                pass  # a property changed since the button was made; the run's state still shows

    # ------------------------------------------------------------------ the button cell

    def cells(self, table_id: str, props: list[dict[str, Any]], rows: list[dict[str, Any]]) -> None:
        """Fills each button cell with its latest run: {state, label, at, taskIds, summary}."""
        buttons = [p for p in props if p["type"] == "button"]
        if not buttons or not rows:
            return
        latest: dict[tuple[str, str], Any] = {}
        for r in self._c._db.execute("SELECT * FROM button_run WHERE table_id = ? ORDER BY created_at", (table_id,)):
            latest[(r["row_id"], r["prop_id"])] = r
        for row in rows:
            for prop in buttons:
                run = latest.get((row["id"], prop["id"]))
                row["cells"][prop["id"]] = None if run is None else {
                    "state": run["state"], "label": STATE_LABEL.get(run["state"], run["state"]), "at": run["updated_at"],
                    "taskIds": json.loads(run["task_ids"]), "summary": run["summary"][:300]}

    def history(self, table_id: str, row_id: str) -> list[dict[str, Any]]:
        with self._c._lock:
            return [{"id": r["id"], "button": r["prop_id"], "state": r["state"], "label": STATE_LABEL.get(r["state"], r["state"]),
                     "taskIds": json.loads(r["task_ids"]), "summary": r["summary"], "actor": r["actor"], "at": r["created_at"]}
                    for r in self._c._db.execute("SELECT * FROM button_run WHERE table_id = ? AND row_id = ? ORDER BY created_at DESC LIMIT 20",
                                                 (table_id, row_id))]
