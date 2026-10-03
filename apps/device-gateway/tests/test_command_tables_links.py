"""Plan 43 (T2): cross-referencing. Two-way relations (also to Cyclone's own accounts and phones), rollups over them,
formulas worked out on the PC without eval, and the timeline / calendar / gallery / list layouts."""
from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import pytest

from cyclone_device_gateway.command import formula as fx
from cyclone_device_gateway.command.center import CommandCenter, CommandError


class Contract:
    def cc_start(self, *a: Any, **k: Any) -> dict[str, Any]:
        raise AssertionError("no phone in these tests")


class Clock:
    ms = int(datetime(2026, 6, 3, 9, 0, tzinfo=timezone.utc).timestamp() * 1000)

    def __call__(self) -> int:
        self.ms += 1
        return self.ms


PHONES = [{"deviceId": "pixel8-abc", "name": "Pixel 8", "paired": True, "state": "ready"}]


@pytest.fixture()
def center(tmp_path: Path):
    cc = CommandCenter(tmp_path / "cc.db", Contract(), lambda: PHONES, clock=Clock(), connections={"spawn": lambda fn: fn(), "sleep": lambda s: None})
    yield cc
    cc.stop()


def prop(table: dict[str, Any], name: str) -> dict[str, Any]:
    return next(p for p in table["properties"] if p["name"] == name)


def world(center: CommandCenter) -> dict[str, Any]:
    """The owner's example: orders that point at banks, and banks that belong to customers."""
    t = center.tables
    orders = t.create({"title": "Orders"})
    banks = t.create({"title": "Banks"})
    t.add_property(orders["id"], {"name": "Estimate", "type": "currency"})
    t.add_property(orders["id"], {"name": "Charge", "type": "date"})
    t.add_property(orders["id"], {"name": "Paid", "type": "checkbox"})
    orders = t.add_property(orders["id"], {"name": "Bank", "type": "relation", "config": {"target": banks["id"]}})
    banks = t.get(banks["id"])
    o_name, b_name = prop(orders, "Name")["id"], prop(banks, "Name")["id"]
    bank_ids = {n: t.create_row(banks["id"], {"cells": {b_name: n}})["id"] for n in ("Wise ES | 002", "Bunq ES | 001")}
    rel, amount, charge, paid = (prop(orders, n)["id"] for n in ("Bank", "Estimate", "Charge", "Paid"))
    order_ids = {}
    for name, bank, euros, day, done in (("Meesman", "Wise ES | 002", 711, "2026-06-01", False), ("Bux", "Wise ES | 002", 650, "2026-05-28", True),
                                         ("Vivid", "Bunq ES | 001", 250, "2026-05-19", True)):
        order_ids[name] = t.create_row(orders["id"], {"cells": {o_name: name, rel: [bank_ids[bank]], amount: euros,
                                                                  charge: {"start": day}, paid: done}})["id"]
    return {"orders": t.get(orders["id"]), "banks": t.get(banks["id"]), "bank": bank_ids, "order": order_ids}


def test_a_relation_is_two_way(center):
    w = world(center)
    back = prop(w["banks"], "Orders")
    assert back["type"] == "relation" and back["config"]["target"] == w["orders"]["id"]
    assert prop(w["orders"], "Bank")["config"]["backProp"] == back["id"]
    wise = center.tables.get_row(w["banks"]["id"], w["bank"]["Wise ES | 002"])
    assert sorted(wise["cells"][back["id"]]) == sorted([w["order"]["Meesman"], w["order"]["Bux"]])
    assert {v["label"] for v in wise["links"].values()} == {"Meesman", "Bux"}, "a bank shows its orders by name"
    rel = prop(w["orders"], "Bank")["id"]
    center.tables.update_row(w["orders"]["id"], w["order"]["Bux"], {"cells": {rel: [w["bank"]["Bunq ES | 001"]]}})
    wise = center.tables.get_row(w["banks"]["id"], w["bank"]["Wise ES | 002"])
    bunq = center.tables.get_row(w["banks"]["id"], w["bank"]["Bunq ES | 001"])
    assert wise["cells"][back["id"]] == [w["order"]["Meesman"]]
    assert set(bunq["cells"][back["id"]]) == {w["order"]["Vivid"], w["order"]["Bux"]}
    assert bunq["history"][0]["change"]["linked"] == w["order"]["Bux"], "the other side's history says why it changed"


def test_links_must_exist_and_deleting_cleans_both_sides(center):
    w = world(center)
    rel = prop(w["orders"], "Bank")["id"]
    with pytest.raises(CommandError, match="exist"):
        center.tables.update_row(w["orders"]["id"], w["order"]["Vivid"], {"cells": {rel: ["rw_doesnotexist01"]}})
    back = prop(w["banks"], "Orders")["id"]
    center.tables.archive_row(w["orders"]["id"], w["order"]["Vivid"])
    center.tables.delete_row(w["orders"]["id"], w["order"]["Vivid"])
    assert back not in center.tables.get_row(w["banks"]["id"], w["bank"]["Bunq ES | 001"])["cells"]
    center.tables.delete_property(w["orders"]["id"], rel)
    assert "Orders" not in [p["name"] for p in center.tables.get(w["banks"]["id"])["properties"]], "the way back goes too"
    with pytest.raises(CommandError, match="change type"):
        t = center.tables.add_property(w["orders"]["id"], {"name": "Bank again", "type": "relation", "config": {"target": w["banks"]["id"]}})
        center.tables.update_property(w["orders"]["id"], prop(t, "Bank again")["id"], {"type": "text"})


def test_rollups_count_sum_date_show_and_percent(center):
    w = world(center)
    banks, back = w["banks"]["id"], prop(w["banks"], "Orders")["id"]
    orders = w["orders"]
    t = center.tables
    for name, fn, over in (("Order count", "count", None), ("Total", "sum", "Estimate"), ("First charge", "earliest", "Charge"),
                           ("Order names", "show", "Name"), ("Paid %", "percent_checked", "Paid")):
        t.add_property(banks, {"name": name, "type": "rollup", "config": {"relation": back, "fn": fn,
                                                                           "property": prop(orders, over)["id"] if over else None}})
    table = t.get(banks)
    assert prop(table, "Total")["config"]["resultType"] == "number" and prop(table, "First charge")["config"]["resultType"] == "date"
    wise = t.get_row(banks, w["bank"]["Wise ES | 002"])["cells"]
    assert wise[prop(table, "Order count")["id"]] == 2
    assert wise[prop(table, "Total")["id"]] == 1361
    assert wise[prop(table, "First charge")["id"]] == {"start": "2026-05-28"}
    assert wise[prop(table, "Order names")["id"]] == "Meesman, Bux"
    assert wise[prop(table, "Paid %")["id"]] == 50.0
    view = table["views"][0]["id"]
    t.update_view(banks, view, {"config": {"filters": [{"property": prop(table, "Total")["id"], "op": "gt", "value": 1000}],
                                           "sorts": [{"property": prop(table, "Total")["id"], "direction": "desc"}]}})
    assert [r["id"] for r in t.rows(banks, view)["rows"]] == [w["bank"]["Wise ES | 002"]]
    with pytest.raises(CommandError, match="number"):
        t.add_property(banks, {"name": "Bad", "type": "rollup", "config": {"relation": back, "fn": "sum", "property": prop(orders, "Name")["id"]}})


def test_formulas_are_worked_out_on_the_pc(center):
    w = world(center)
    t = center.tables
    oid = w["orders"]["id"]
    t.add_property(oid, {"name": "With VAT", "type": "formula", "config": {"expression": 'round(prop("Estimate") * 1.21, 2)'}})
    t.add_property(oid, {"name": "State", "type": "formula", "config": {"expression": 'if(prop("Paid"), "paid", concat("open ", prop("Name")))'}})
    t.add_property(oid, {"name": "Days", "type": "formula", "config": {"expression": 'dateBetween(now(), prop("Charge"), "days")'}})
    t.add_property(oid, {"name": "Banks", "type": "formula", "config": {"expression": 'prop("Bank") + 0'}})
    table = t.get(oid)
    rows = {r["cells"][prop(table, "Name")["id"]]: r["cells"] for r in t.rows(oid)["rows"]}
    assert rows["Meesman"][prop(table, "With VAT")["id"]] == 860.31
    assert rows["Meesman"][prop(table, "State")["id"]] == "open Meesman" and rows["Bux"][prop(table, "State")["id"]] == "paid"
    assert rows["Meesman"][prop(table, "Days")["id"]] == 2 and rows["Banks" if False else "Vivid"][prop(table, "Banks")["id"]] == 1
    with pytest.raises(CommandError, match="no property"):
        t.add_property(oid, {"name": "Bad", "type": "formula", "config": {"expression": 'prop("Nope") * 2'}})
    with pytest.raises(CommandError, match="Formula"):
        t.add_property(oid, {"name": "Bad", "type": "formula", "config": {"expression": 'prop("Estimate") *'}})
    t.update_property(oid, prop(table, "Estimate")["id"], {"name": "Amount"})
    assert prop(t.get(oid), "With VAT")["config"]["expression"] == 'round(prop("Amount") * 1.21, 2)', "renames follow into formulas"
    view = table["views"][0]["id"]
    t.update_view(oid, view, {"config": {"filters": [{"property": prop(table, "State")["id"], "op": "contains", "value": "open"}]}})
    assert t.rows(oid, view)["total"] == 1
    with pytest.raises(CommandError):
        t.update_row(oid, w["order"]["Bux"], {"cells": {prop(table, "With VAT")["id"]: 1}})


def test_relations_reach_cyclones_own_accounts_and_phones(center):
    account = center.create_account({"service": "com.instagram.android", "handle": "@brand.one", "ownerBasis": "mine"})
    t = center.tables
    brands = t.create({"title": "Brands"})
    t.add_property(brands["id"], {"name": "Instagram", "type": "relation", "config": {"target": "sys:accounts"}})
    brands = t.add_property(brands["id"], {"name": "Phone", "type": "relation", "config": {"target": "sys:phones"}})
    ig, phone = prop(brands, "Instagram"), prop(brands, "Phone")
    assert "backProp" not in ig["config"]
    assert t.candidates(brands["id"], ig["id"]) == [{"id": account["id"], "label": "@brand.one · com.instagram.android", "tableId": "sys:accounts"}]
    assert t.candidates(brands["id"], phone["id"], "pixel")[0]["label"] == "Pixel 8"
    row = t.create_row(brands["id"], {"cells": {prop(brands, "Name")["id"]: "Brand One", ig["id"]: [account["id"]], phone["id"]: ["pixel8-abc"]}})
    links = t.rows(brands["id"])["links"]
    assert links[account["id"]]["label"] == "@brand.one · com.instagram.android" and links["pixel8-abc"]["label"] == "Pixel 8"
    with pytest.raises(CommandError):
        t.update_row(brands["id"], row["id"], {"cells": {phone["id"]: ["some-other-phone"]}})
    t.add_property(brands["id"], {"name": "Accounts", "type": "rollup", "config": {"relation": ig["id"], "fn": "count"}})


def test_timeline_and_calendar_lay_rows_out_by_a_date(center):
    w = world(center)
    oid = w["orders"]["id"]
    charge = prop(w["orders"], "Charge")["id"]
    out = center.tables.add_view(oid, {"name": "Timeline", "layout": "timeline"})
    view = next(v for v in out["views"] if v["id"] == out["viewId"])
    assert view["config"]["dateProp"] == charge
    for layout in ("calendar", "gallery", "list"):
        center.tables.add_view(oid, {"name": layout.title(), "layout": layout})
    with pytest.raises(CommandError, match="date"):
        center.tables.add_view(oid, {"name": "Bad", "layout": "calendar", "config": {"dateProp": prop(w["orders"], "Name")["id"]}})


def test_the_formula_language_is_small_and_safe():
    assert fx.evaluate(fx.parse('1 + 2 * 3 - (4 / 2)'), lambda n: None) == 5
    assert fx.evaluate(fx.parse('"a" + 1'), lambda n: None) == "a1"
    assert fx.evaluate(fx.parse('not (1 > 2) and 3 >= 3'), lambda n: None) is True
    assert fx.evaluate(fx.parse('1 / 0'), lambda n: None) is None
    for bad in ('__import__("os")', 'eval("1")', 'prop(Name)', '1 +', '"unterminated', 'a.b'):
        with pytest.raises(fx.FormulaError):
            fx.parse(bad)
    assert fx.names(fx.parse('prop("A") + if(prop("B"), 1, prop("C"))')) == {"A", "B", "C"}
    assert fx.rename('prop("A") + prop("AB")', "A", 'New "A"') == 'prop("New \\"A\\"") + prop("AB")'
