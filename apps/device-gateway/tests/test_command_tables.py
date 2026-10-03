"""Plan 43 (T1, alpha.78): tables. Typed properties and cells, saved views (filters, sorts, board groups) computed on
the PC, row pages, history and undo, type conversion, the trash, CSV without personal columns; secrets refused;
every route behind the bearer."""
from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from cyclone_device_gateway.command.api import create_command_router
from cyclone_device_gateway.command.center import CommandCenter, CommandError
from cyclone_device_gateway.command.tables import TableConflict

TOKEN = "tok_tables_0123456789abcdef"


class Contract:
    def cc_start(self, *a: Any, **k: Any) -> dict[str, Any]:
        raise AssertionError("no phone in these tests")


class Clock:
    ms = int(datetime(2026, 9, 30, 9, 0, tzinfo=timezone.utc).timestamp() * 1000)

    def __call__(self) -> int:
        self.ms += 1
        return self.ms


@pytest.fixture()
def center(tmp_path: Path):
    cc = CommandCenter(tmp_path / "cc.db", Contract(), lambda: [], clock=Clock(), connections={"spawn": lambda fn: fn(), "sleep": lambda s: None})
    yield cc
    cc.stop()


def prop(table: dict[str, Any], name: str) -> dict[str, Any]:
    return next(p for p in table["properties"] if p["name"] == name)


def option(p: dict[str, Any], name: str) -> str:
    return next(o["id"] for o in p["config"]["options"] if o["name"] == name)


def ledger(center: CommandCenter) -> dict[str, Any]:
    """The owner's screenshot, as a table: orders with an amount, a date range, an email and a status."""
    t = center.tables.create({"title": "Orders Ledger", "icon": "📦", "description": "Order, payment, payout, refund, and status tracking."})
    tid = t["id"]
    center.tables.add_property(tid, {"name": "Estimate", "type": "currency", "config": {"currency": "EUR"}})
    center.tables.add_property(tid, {"name": "Charge / Payout", "type": "date"})
    center.tables.add_property(tid, {"name": "Email", "type": "email", "config": {"personal": True}})
    center.tables.add_property(tid, {"name": "Tags", "type": "multi_select", "config": {"options": [{"name": "RTK", "color": "purple"}, {"name": "NL", "color": "blue"}]}})
    status = prop(center.tables.get(tid), "Status")
    center.tables.update_property(tid, status["id"], {"config": {"options": [
        {"name": "Planned payment", "color": "purple", "group": "todo"},
        {"name": "Pending Refund", "color": "orange", "group": "doing"},
        {"name": "Payment Made", "color": "blue", "group": "done"}]}})
    return center.tables.get(tid)


def fill(center: CommandCenter, t: dict[str, Any]) -> dict[str, str]:
    name, amount, when, email, status = (prop(t, n)["id"] for n in ("Name", "Estimate", "Charge / Payout", "Email", "Status"))
    s = prop(t, "Status")
    ids = {}
    for title, euros, start, end, mail, state in (
        ("Meesman Wise", 711, "2026-06-01", None, "owner1@example.com", "Planned payment"),
        ("Bux Wise 004", 650, "2026-05-28", "2026-06-07", "owner2@example.com", "Planned payment"),
        ("0142 Vivid", 250, "2026-05-19", "2026-05-22", "owner3@example.com", "Pending Refund"),
        ("Krak Marta", 300, "2026-05-18", "2026-05-21", "owner4@example.com", "Payment Made"),
    ):
        date = {"start": start, **({"end": end} if end else {})}
        row = center.tables.create_row(t["id"], {"cells": {name: title, amount: euros, when: date, email: mail, status: option(s, state)}})
        ids[title] = row["id"]
    return ids


def test_a_table_starts_with_a_title_a_status_and_two_views(center):
    t = center.tables.create({"title": "Brands"})
    assert [p["type"] for p in t["properties"]] == ["title", "status"]
    assert [o["group"] for o in t["properties"][1]["config"]["options"]] == ["todo", "doing", "done"]
    assert [(v["name"], v["layout"]) for v in t["views"]] == [("Table", "table"), ("Board", "board")]
    assert t["views"][1]["config"]["groupBy"] == t["properties"][1]["id"]
    assert center.tables.list()[0]["rows"] == 0


def test_cells_are_typed_and_secrets_are_refused(center):
    t = ledger(center)
    tid = t["id"]
    amount, email, when, tags = (prop(t, n)["id"] for n in ("Estimate", "Email", "Charge / Payout", "Tags"))
    for bad in ({amount: "711"}, {amount: True}, {email: "not an email"}, {when: "2026-02-30"},
                {when: {"start": "2026-06-07", "end": "2026-05-28"}}, {tags: ["op_nothere"]}, {"pr_unknown00": "x"}):
        with pytest.raises(CommandError):
            center.tables.create_row(tid, {"cells": bad})
    with pytest.raises(CommandError, match="password"):
        center.tables.create_row(tid, {"cells": {prop(t, "Name")["id"]: "password: hunter2"}})
    for name in ("Password", "PIN code", "API key", "OTP"):
        with pytest.raises(CommandError, match="vault"):
            center.tables.add_property(tid, {"name": name, "type": "text"})
    with pytest.raises(CommandError):
        center.tables.add_property(tid, {"name": "Second title", "type": "title"})
    with pytest.raises(CommandError):
        center.tables.add_property(tid, {"name": "Estimate", "type": "number"})


def test_views_filter_sort_and_group_on_the_pc(center):
    t = ledger(center)
    ids = fill(center, t)
    tid = t["id"]
    status, amount, when = prop(t, "Status"), prop(t, "Estimate")["id"], prop(t, "Charge / Payout")["id"]
    table_view, board_view = t["views"][0]["id"], t["views"][1]["id"]

    center.tables.update_view(tid, table_view, {"config": {"sorts": [{"property": amount, "direction": "desc"}]}})
    rows = center.tables.rows(tid, table_view)
    assert [r["id"] for r in rows["rows"]] == [ids["Meesman Wise"], ids["Bux Wise 004"], ids["Krak Marta"], ids["0142 Vivid"]]

    center.tables.update_view(tid, table_view, {"config": {"filters": [
        {"property": status["id"], "op": "is", "value": option(status, "Planned payment")},
        {"property": amount, "op": "gt", "value": 700}], "match": "and"}})
    assert [r["id"] for r in center.tables.rows(tid, table_view)["rows"]] == [ids["Meesman Wise"]]
    center.tables.update_view(tid, table_view, {"config": {"filters": [{"property": when, "op": "is", "value": "2026-06-03"}]}})
    assert [r["id"] for r in center.tables.rows(tid, table_view)["rows"]] == [ids["Bux Wise 004"]], "a date range covers the days in it"

    board = center.tables.rows(tid, board_view)
    groups = {g["name"]: g["rowIds"] for g in board["groups"]}
    assert groups["Planned payment"] == [ids["Meesman Wise"], ids["Bux Wise 004"]]
    assert groups["Pending Refund"] == [ids["0142 Vivid"]] and groups["No Status"] == []
    assert center.tables.rows(tid, board_view, "krak")["total"] == 1

    with pytest.raises(CommandError):
        center.tables.update_view(tid, table_view, {"config": {"filters": [{"property": amount, "op": "contains", "value": "x"}]}})
    with pytest.raises(CommandError):
        center.tables.update_view(tid, board_view, {"config": {"groupBy": amount}})


def test_moving_a_card_sets_its_group_and_every_change_can_be_undone(center):
    t = ledger(center)
    ids = fill(center, t)
    tid = t["id"]
    status = prop(t, "Status")
    row = center.tables.update_row(tid, ids["0142 Vivid"], {"cells": {status["id"]: option(status, "Payment Made")}})
    assert row["cells"][status["id"]] == option(status, "Payment Made") and row["version"] == 2
    detail = center.tables.get_row(tid, row["id"])
    assert detail["history"][0]["change"]["cells"][status["id"]] == [option(status, "Pending Refund"), option(status, "Payment Made")]
    back = center.tables.undo(tid, row["id"])
    assert back["cells"][status["id"]] == option(status, "Pending Refund")
    with pytest.raises(TableConflict):
        center.tables.update_row(tid, row["id"], {"cells": {}, "version": 1})


def test_row_pages_hold_blocks_but_not_tables(center):
    t = ledger(center)
    ids = fill(center, t)
    row = center.tables.get_row(t["id"], ids["Krak Marta"])
    saved = center.tables.save_row_page(t["id"], row["id"], {"version": row["version"], "blocks": [{"type": "p", "text": [{"t": "RTK payment"}]}]})
    assert saved["blocks"][0]["text"][0]["t"] == "RTK payment" and saved["hasPage"]
    with pytest.raises(CommandError):
        center.tables.save_row_page(t["id"], row["id"], {"version": saved["version"], "blocks": [{"type": "table", "tableId": t["id"]}]})


def test_changing_a_type_converts_cells(center):
    t = center.tables.create({"title": "Brands"})
    tid = t["id"]
    center.tables.add_property(tid, {"name": "Platform", "type": "text"})
    platform = prop(center.tables.get(tid), "Platform")["id"]
    for name, where in (("A", "Instagram"), ("B", "TikTok"), ("C", "Instagram")):
        center.tables.create_row(tid, {"cells": {prop(t, "Name")["id"]: name, platform: where}})
    changed = center.tables.update_property(tid, platform, {"type": "select"})
    p = prop(changed, "Platform")
    assert [o["name"] for o in p["config"]["options"]] == ["Instagram", "TikTok"]
    rows = center.tables.rows(tid)["rows"]
    assert [r["cells"][platform] for r in rows] == [option(p, "Instagram"), option(p, "TikTok"), option(p, "Instagram")]
    center.tables.update_property(tid, platform, {"type": "number"})
    assert all(platform not in r["cells"] for r in center.tables.rows(tid)["rows"]), "text that isn't a number is cleared"


def test_removing_an_option_or_a_property_cleans_rows_and_views(center):
    t = ledger(center)
    ids = fill(center, t)
    tid = t["id"]
    status = prop(t, "Status")
    keep = [o for o in status["config"]["options"] if o["name"] != "Pending Refund"]
    center.tables.update_property(tid, status["id"], {"config": {"options": keep}})
    assert status["id"] not in center.tables.get_row(tid, ids["0142 Vivid"])["cells"]
    view = t["views"][1]["id"]
    center.tables.delete_property(tid, status["id"])
    assert center.tables.get(tid)["views"][1]["config"]["groupBy"] is None
    assert view
    with pytest.raises(CommandError):
        center.tables.delete_property(tid, prop(t, "Name")["id"])


def test_trash_first_then_delete(center):
    t = ledger(center)
    ids = fill(center, t)
    tid = t["id"]
    center.tables.archive_row(tid, ids["Krak Marta"])
    assert center.tables.rows(tid)["total"] == 3
    assert [r["title"] for r in center.tables.trash()["rows"]] == ["Krak Marta"]
    with pytest.raises(CommandError):
        center.tables.update_row(tid, ids["Krak Marta"], {"cells": {}})
    center.tables.restore_row(tid, ids["Krak Marta"])
    with pytest.raises(CommandError, match="trash first"):
        center.tables.delete_row(tid, ids["Krak Marta"])
    with pytest.raises(CommandError, match="trash first"):
        center.tables.delete(tid)
    center.tables.archive(tid)
    assert center.tables.list() == []
    center.tables.delete(tid)
    with pytest.raises(CommandError):
        center.tables.get(tid)


def test_csv_leaves_out_personal_columns_and_formulas(center):
    t = ledger(center)
    fill(center, t)
    center.tables.create_row(t["id"], {"cells": {prop(t, "Name")["id"]: "=HYPERLINK(1)"}})
    text = center.tables.export_csv(t["id"])
    assert "Email" not in text and "@example.com" not in text
    assert "'=HYPERLINK(1)" in text and "711" in text


def test_a_page_can_show_a_table(center):
    t = center.tables.create({"title": "Brands"})
    page = center.pages.create({"title": "Home", "blocks": [{"type": "table", "tableId": t["id"], "viewId": t["views"][1]["id"]}]})
    assert page["blocks"][0]["tableId"] == t["id"]
    assert [p["id"] for p in center.pages.backlinks("table", t["id"])] == [page["id"]]
    with pytest.raises(CommandError):
        center.pages.create({"title": "Bad", "blocks": [{"type": "table", "tableId": "nope"}]})


def test_routes_need_the_bearer_and_report_conflicts(center):
    runtime = type("R", (), {"command": center})()
    app = FastAPI()
    app.include_router(create_command_router(runtime, TOKEN))
    client = TestClient(app)
    assert client.get("/v1/cc/tables").status_code == 401
    h = {"Authorization": f"Bearer {TOKEN}"}
    t = client.post("/v1/cc/tables", json={"title": "Brands"}, headers=h).json()
    name = t["properties"][0]["id"]
    row = client.post(f"/v1/cc/tables/{t['id']}/rows", json={"cells": {name: "Brand A"}}, headers=h).json()
    client.post(f"/v1/cc/tables/{t['id']}/rows/{row['id']}", json={"cells": {name: "Brand A1"}}, headers=h)
    stale = client.post(f"/v1/cc/tables/{t['id']}/rows/{row['id']}", json={"cells": {name: "x"}, "version": 1}, headers=h)
    assert stale.status_code == 409 and stale.json()["detail"]["code"] == "ROW_CHANGED"
    listed = client.get(f"/v1/cc/tables/{t['id']}/rows", params={"view": t["views"][1]["id"]}, headers=h).json()
    assert listed["total"] == 1 and listed["groups"][0]["rowIds"] == [row["id"]]
    assert client.get(f"/v1/cc/tables/{t['id']}/export.csv", headers=h).text.splitlines()[1] == "Brand A1,"
    assert client.post(f"/v1/cc/tables/{t['id']}/rows", json={"cells": {name: "token: abc"}}, headers=h).status_code == 400
