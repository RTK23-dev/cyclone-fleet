"""Plan 43 (T3): action buttons. A row's button runs a routine, a prompt or a skill on a phone, sets properties, and
writes the result back; a press only creates ordinary tasks and never approves anything."""
from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import pytest

from cyclone_device_gateway.command.center import CommandCenter, CommandError


class Contract:
    def __init__(self) -> None:
        self.started: list[tuple[str, str]] = []
        self.status: dict[str, dict[str, Any]] = {}

    def cc_start(self, device_id: str, goal: str, **extra: Any) -> dict[str, Any]:
        mission = f"mtest{len(self.started) + 1:04d}"
        self.started.append((device_id, goal))
        self.status[mission] = {"missionId": mission, "status": "running", "live": True, "turns": 1, "workingMs": 1, "costUsd": 0.0,
                                "summary": "", "moment": None, "leases": []}
        return {"accepted": True, "missionId": mission}

    def cc_status(self, device_id: str, mission_id: str) -> dict[str, Any]:
        return dict(self.status[mission_id])

    def cc_answer(self, *a: Any, **k: Any) -> dict[str, Any]:
        return {"handled": True, "detail": "ok"}


class Clock:
    ms = int(datetime(2026, 9, 30, 9, 0, tzinfo=timezone.utc).timestamp() * 1000)

    def __call__(self) -> int:
        return self.ms


PHONES = [{"deviceId": "pixel8-abc", "name": "Pixel 8", "paired": True, "state": "ready"},
          {"deviceId": "galaxy-s24", "name": "Galaxy S24", "paired": True, "state": "ready"}]


@pytest.fixture()
def setup(tmp_path: Path):
    contract = Contract()
    center = CommandCenter(tmp_path / "cc.db", contract, lambda: PHONES, clock=Clock(),
                           connections={"spawn": lambda fn: fn(), "sleep": lambda s: None})
    yield center, contract
    center.stop()


def prop(table: dict[str, Any], name: str) -> dict[str, Any]:
    return next(p for p in table["properties"] if p["name"] == name)


def option(table: dict[str, Any], name: str, value: str) -> str:
    return next(o["id"] for o in prop(table, name)["config"]["options"] if o["name"] == value)


def customers(center: CommandCenter) -> dict[str, Any]:
    t = center.tables
    banks = t.create({"title": "Banks"})
    customers = t.create({"title": "Customers"})
    t.add_property(customers["id"], {"name": "Checked", "type": "date"})
    t.add_property(customers["id"], {"name": "Notes", "type": "text"})
    t.add_property(customers["id"], {"name": "Phone", "type": "relation", "config": {"target": "sys:phones"}})
    t.add_property(customers["id"], {"name": "Bank", "type": "relation", "config": {"target": banks["id"]}})
    banks = t.get(banks["id"])
    t.add_property(banks["id"], {"name": "IBAN country", "type": "text"})
    banks = t.get(banks["id"])
    bank = t.create_row(banks["id"], {"cells": {prop(banks, "Name")["id"]: "Wise ES | 002", prop(banks, "IBAN country")["id"]: "ES"}})
    table = t.get(customers["id"])
    row = t.create_row(table["id"], {"cells": {prop(table, "Name")["id"]: "Anna", prop(table, "Phone")["id"]: ["galaxy-s24"],
                                             prop(table, "Bank")["id"]: [bank["id"]]}})
    return {"table": t.get(table["id"]), "row": row, "banks": banks}


def test_a_prompt_button_fills_from_the_row_runs_on_the_rows_phone_and_writes_back(setup):
    center, contract = setup
    world = customers(center)
    table, row = world["table"], world["row"]
    status = next(p for p in table["properties"] if p["type"] == "status")
    table = center.tables.add_property(table["id"], {"name": "Check WhatsApp", "type": "button", "config": {
        "label": "Check WhatsApp", "color": "blue",
        "actions": [{"do": "prompt", "prompt": "Check WhatsApp for messages from {{Name}} about {{Bank}} ({{Bank.IBAN country}}) and summarise them."},
                    {"do": "update", "cells": {prop(table, "Checked")["id"]: "@today"}}],
        "runOn": {"kind": "row", "propId": prop(table, "Phone")["id"]},
        "then": {"success": {status["id"]: option(table, status["name"], "Done")}, "summary": prop(table, "Notes")["id"]}}})
    button = prop(table, "Check WhatsApp")
    assert button["type"] == "button" and button["config"]["color"] == "blue"
    pressed = center.buttons.press(table["id"], row["id"], button["id"])
    assert pressed["state"] == "queued" and len(pressed["tasks"]) == 1
    cells = center.tables.get_row(table["id"], row["id"])["cells"]
    assert cells[prop(table, "Checked")["id"]] == {"start": "2026-09-30"}
    assert cells[button["id"]]["label"] == "Queued"
    with pytest.raises(CommandError, match="still running"):
        center.buttons.press(table["id"], row["id"], button["id"])
    center.tick()
    device, goal = contract.started[0]
    assert device == "galaxy-s24"
    assert goal.startswith("Check WhatsApp for messages from Anna about Wise ES | 002 (ES) and summarise them.")
    center.tick()
    assert center.tables.get_row(table["id"], row["id"])["cells"][button["id"]]["label"] == "Running"
    mission = next(iter(contract.status))
    contract.status[mission].update(live=False, status="completed", summary="Two new messages: Anna asks about the refund.")
    center.tick()
    got = center.tables.get_row(table["id"], row["id"])
    assert got["cells"][button["id"]]["label"] == "Done ✓"
    assert got["cells"][prop(table, "Notes")["id"]] == "Two new messages: Anna asks about the refund."
    assert got["cells"][status["id"]] == option(table, status["name"], "Done")
    assert got["buttonRuns"][0]["state"] == "done"


def test_routine_skill_update_and_open_buttons(setup):
    center, contract = setup
    world = customers(center)
    table, row = world["table"], world["row"]
    routine = center.create_routine({"title": "Check inbox", "goal": "Check the shop inbox", "deviceIds": ["pixel8-abc"],
                                     "schedule": {"kind": "every", "minutes": 60}})
    table = center.tables.add_property(table["id"], {"name": "Inbox", "type": "button", "config": {
        "actions": [{"do": "routine", "routineId": routine["id"]}]}})
    table = center.tables.add_property(table["id"], {"name": "Skill", "type": "button", "config": {
        "label": "Refund", "actions": [{"do": "skill", "skill": "Refund an order", "prompt": "for {{Name}}"}],
        "runOn": {"kind": "phone", "deviceId": "pixel8-abc"}}})
    table = center.tables.add_property(table["id"], {"name": "Open", "type": "button", "config": {
        "label": "Open bank", "actions": [{"do": "open", "url": "https://wise.com"},
                                          {"do": "update", "cells": {prop(table, "Notes")["id"]: "Opened"}}]}})
    inbox = center.buttons.press(table["id"], row["id"], prop(table, "Inbox")["id"])
    assert len(inbox["tasks"]) == 1 and center.get_task(inbox["tasks"][0])["routineId"] == routine["id"]
    skill = center.buttons.press(table["id"], row["id"], prop(table, "Skill")["id"])
    task = center.get_task(skill["tasks"][0])
    assert task["goal"] == "Run your saved skill “Refund an order”. for Anna" and task["deviceId"] == "pixel8-abc"
    opened = center.buttons.press(table["id"], row["id"], prop(table, "Open")["id"])
    assert opened == {**opened, "state": "updated", "tasks": [], "open": ["https://wise.com"]}
    cells = center.tables.get_row(table["id"], row["id"])["cells"]
    assert cells[prop(table, "Notes")["id"]] == "Opened" and cells[prop(table, "Open")["id"]]["label"] == "Done ✓"


def test_buttons_are_checked_against_their_table(setup):
    center, _ = setup
    table = customers(center)["table"]
    add = lambda config: center.tables.add_property(table["id"], {"name": "B", "type": "button", "config": config})  # noqa: E731
    with pytest.raises(CommandError, match="one of"):
        add({"actions": [{"do": "shell", "cmd": "rm"}]})
    with pytest.raises(CommandError, match="at most one"):
        add({"actions": [{"do": "prompt", "prompt": "a"}, {"do": "prompt", "prompt": "b"}]})
    with pytest.raises(CommandError, match="doesn't have"):
        add({"actions": [{"do": "prompt", "prompt": "Call {{Nope}}"}]})
    with pytest.raises(CommandError, match="own editable"):
        add({"actions": [{"do": "update", "cells": {"pr_missing01": "x"}}]})
    with pytest.raises(CommandError, match="date property"):
        add({"actions": [{"do": "update", "cells": {prop(table, "Notes")["id"]: "@today"}}]})
    with pytest.raises(CommandError, match="relation to Phones"):
        add({"actions": [{"do": "prompt", "prompt": "x"}], "runOn": {"kind": "row", "propId": prop(table, "Notes")["id"]}})
    with pytest.raises(CommandError, match="No such routine"):
        add({"actions": [{"do": "routine", "routineId": "rtn_missing000"}]})
    table = add({"label": "Empty"})
    with pytest.raises(CommandError, match="filled in by Cyclone"):
        center.tables.update_row(table["id"], center.tables.all_rows(table["id"])[0]["id"], {"cells": {prop(table, "B")["id"]: "x"}})
    with pytest.raises(CommandError, match="no actions"):
        center.buttons.press(table["id"], center.tables.all_rows(table["id"])[0]["id"], prop(table, "B")["id"])
    with pytest.raises(CommandError, match="can't change type"):
        center.tables.update_property(table["id"], prop(table, "B")["id"], {"type": "text"})


def test_a_row_without_its_phone_says_so(setup):
    center, _ = setup
    table = customers(center)["table"]
    row = center.tables.create_row(table["id"], {"cells": {prop(table, "Name")["id"]: "No phone"}})
    table = center.tables.add_property(table["id"], {"name": "Go", "type": "button", "config": {
        "actions": [{"do": "prompt", "prompt": "Hi {{Name}}"}], "runOn": {"kind": "row", "propId": prop(table, "Phone")["id"]}}})
    with pytest.raises(CommandError, match="no phone yet"):
        center.buttons.press(table["id"], row["id"], prop(table, "Go")["id"])


def test_agents_read_tables_and_propose_presses(setup):
    center, _ = setup
    world = customers(center)
    table, row = world["table"], world["row"]
    table = center.tables.add_property(table["id"], {"name": "Email", "type": "email", "config": {"personal": True}})
    table = center.tables.add_property(table["id"], {"name": "Ping", "type": "button", "config": {
        "actions": [{"do": "prompt", "prompt": "Say hi to {{Name}}"}]}})
    ai = center.ai
    listed = ai._read("list_tables", {})
    customers_t = next(t for t in listed["tables"] if t["title"] == "Customers")
    assert {"name": "Ping", "type": "button", "button": "Run"} in customers_t["properties"]
    rows = ai._read("query_table", {"tableId": table["id"]})
    assert rows["rows"][0]["Name"] == "Anna" and "Email" not in rows["rows"][0]
    with center._lock:
        summary, _ = ai._prepare("press_button", {"tableId": table["id"], "rowId": row["id"], "button": "Ping"})
        assert summary == "Press “Run” in “Customers”"
        done = ai._execute("press_button", {"tableId": table["id"], "rowId": row["id"], "button": "ping"}, actor="ai")
        assert done["state"] == "queued" and len(done["tasks"]) == 1
        with pytest.raises(CommandError, match="personal"):
            ai._prepare("update_row", {"tableId": table["id"], "rowId": row["id"], "cells": {"Email": "a@b.co"}})
        made = ai._execute("create_row", {"tableId": table["id"], "cells": {"Name": "Bram", "Checked": "2026-10-01"}}, actor="ai")
    assert center.tables.get_row(table["id"], made["rowId"])["cells"][prop(table, "Checked")["id"]] == {"start": "2026-10-01"}
    assert center.get_task(done["tasks"][0])["status"] == "scheduled"  # a press starts work; it never approves anything
