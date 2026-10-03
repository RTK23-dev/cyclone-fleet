"""Plan 33 (C5, alpha.59): pages. Typed blocks with inline references, live views and planning boards; nesting,
versioned saves, backlinks, search, templates and the trash; secrets refused; every route behind the bearer."""
from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from cyclone_device_gateway.command.api import create_command_router
from cyclone_device_gateway.command.center import CommandCenter, CommandError
from cyclone_device_gateway.command.pages import MAX_DEPTH, PageConflict, references

TOKEN = "tok_pages_0123456789abcdef"


class Contract:
    def cc_start(self, *a: Any, **k: Any) -> dict[str, Any]:
        raise AssertionError("no phone in these tests")


class Clock:
    ms = int(datetime(2026, 9, 28, 9, 0, tzinfo=timezone.utc).timestamp() * 1000)

    def __call__(self) -> int:
        self.ms += 1
        return self.ms


@pytest.fixture()
def center(tmp_path: Path):
    cc = CommandCenter(tmp_path / "cc.db", Contract(), lambda: [], clock=Clock(), connections={"spawn": lambda fn: fn(), "sleep": lambda s: None})
    yield cc
    cc.stop()


def mention(kind: str, ident: str, label: str) -> dict[str, Any]:
    return {"ref": {"kind": kind, "id": ident, "label": label}}


def test_pages_nest_save_with_versions_and_index_their_references(center):
    pages = center.pages
    home = pages.create({"title": "Shop", "icon": "🛍️"})
    week = pages.create({"parentId": home["id"], "template": "weekly"})
    assert week["title"] == "Weekly plan" and week["path"] == [{"id": home["id"], "title": "Shop", "icon": "🛍️"}]
    assert [b["type"] for b in week["blocks"]][:3] == ["callout", "h2", "board"]
    assert all(b["id"].startswith("b") for b in week["blocks"]) and len({b["id"] for b in week["blocks"]}) == len(week["blocks"])
    blocks = [
        {"type": "h1", "text": [{"t": "Monday"}]},
        {"type": "p", "text": [{"t": "Ask "}, mention("device", "phone-a", "Pixel 8"), {"t": " to run ", "b": True},
                               mention("routine", "rtn_abcdef12", "Answer orders"), {"t": " with "}, mention("skill", "you.0123456789ab", "Reply in Messages")]},
        {"type": "todo", "checked": True, "text": [{"t": "Check "}, mention("page", home["id"], "Shop")]},
        {"type": "ref", "ref": {"kind": "account", "id": "acc_shop00001", "label": "@corner.shop"}},
        {"type": "view", "source": "tasks", "layout": "board", "filter": {"status": "open", "deviceId": "phone-a"}},
        {"type": "board", "title": "Launch", "layout": "calendar", "items": [
            {"title": "Film the mug", "status": "doing", "due": 1790000000000, "refs": [{"kind": "connection", "id": "con_higgs0001", "label": "Higgsfield"}]}]},
        {"type": "divider"},
    ]
    saved = pages.update(week["id"], {"version": week["version"], "blocks": blocks, "title": "Week 40"})
    assert saved["version"] == week["version"] + 1 and saved["title"] == "Week 40"
    assert saved["blocks"][1]["text"][2] == {"t": " to run ", "b": True}
    assert saved["blocks"][5]["items"][0]["id"].startswith("i") and saved["blocks"][5]["items"][0]["refs"][0]["label"] == "Higgsfield"
    # A save made on the old version is refused, never merged silently.
    with pytest.raises(PageConflict):
        pages.update(week["id"], {"version": week["version"], "title": "Stale tab"})
    # References are indexed: each thing shows the pages that mention it.
    for kind, ident in (("device", "phone-a"), ("routine", "rtn_abcdef12"), ("skill", "you.0123456789ab"), ("account", "acc_shop00001"),
                        ("connection", "con_higgs0001")):
        assert [p["id"] for p in pages.backlinks(kind, ident)] == [week["id"]], kind
    assert [p["id"] for p in pages.get(home["id"])["backlinks"]] == [week["id"]]
    assert pages.get(home["id"])["children"][0]["id"] == week["id"]
    # Removing a mention removes the backlink.
    pages.update(week["id"], {"version": saved["version"], "blocks": blocks[:1]})
    assert pages.backlinks("device", "phone-a") == []


def test_blocks_are_typed_and_secrets_are_refused(center):
    page = center.pages.create({"title": "Notes"})
    bad = [
        ([{"type": "script", "text": []}], "Unknown block"),
        ([{"type": "p", "text": [{"t": "x", "html": "<b>"}]}], "unknown field html"),
        ([{"type": "p", "text": [{"t": "password: hunter2"}]}], "never keep secrets"),
        ([{"type": "p", "text": [mention("device", "phone a/../x", "x")]}], "by its id"),
        ([{"type": "p", "text": [mention("vault", "vi_x", "x")]}], "by its id"),
        ([{"type": "view", "source": "vault"}], "live view shows"),
        ([{"type": "board", "items": [{"title": "x", "status": "blocked"}]}], "todo, doing or done"),
        ([{"type": "p", "id": "bsame1", "text": []}, {"type": "p", "id": "bsame1", "text": []}], "own id"),
        ([{"type": "ref", "ref": {"kind": "task", "id": "tsk_abcdef12", "label": "api_key=abc"}}], "never keep secrets"),
    ]
    for blocks, match in bad:
        with pytest.raises(CommandError, match=match):
            center.pages.update(page["id"], {"version": page["version"], "blocks": blocks})
    with pytest.raises(CommandError, match="never keep secrets"):
        center.pages.create({"title": "token: abc"})
    with pytest.raises(CommandError, match="one emoji"):
        center.pages.create({"title": "x", "icon": "<img>"})
    with pytest.raises(CommandError, match="at most 1000 blocks"):
        center.pages.update(page["id"], {"version": page["version"], "blocks": [{"type": "divider"}] * 1001})
    assert center.pages.get(page["id"])["version"] == page["version"], "nothing was saved"


def test_moving_nesting_limits_search_and_the_trash(center):
    pages = center.pages
    a = pages.create({"title": "Alpha"})
    b = pages.create({"title": "Beta"})
    c = pages.create({"title": "Gamma", "parentId": a["id"]})
    with pytest.raises(CommandError, match="inside itself"):
        pages.move(a["id"], {"parentId": c["id"]})
    pages.move(b["id"], {"parentId": None, "before": a["id"]})
    assert [p["title"] for p in pages.tree() if p["parentId"] is None] == ["Beta", "Alpha"]
    chain = [a]
    for i in range(MAX_DEPTH - 1):
        chain.append(pages.create({"title": f"Level {i}", "parentId": chain[-1]["id"]}))
    with pytest.raises(CommandError, match="nest at most"):
        pages.create({"title": "Too deep", "parentId": chain[-1]["id"]})
    pages.update(b["id"], {"version": b["version"], "blocks": [{"type": "p", "text": [{"t": "The blue mug ships on Friday"}]}]})
    found = pages.search("blue mug")
    assert found[0]["id"] == b["id"] and "blue mug" in found[0]["snippet"]
    assert pages.search("50%") == [] and pages.search("_") == []
    # The trash takes a page with its sub-pages, restores them together, and deletes only from the trash.
    with pytest.raises(CommandError, match="trash first"):
        pages.delete(a["id"])
    assert pages.archive(a["id"])["archived"] == MAX_DEPTH + 1
    assert all(p["id"] != c["id"] for p in pages.tree()) and pages.trash()[0]["id"] in {a["id"], c["id"]} | {x["id"] for x in chain}
    with pytest.raises(CommandError, match="in the trash"):
        pages.update(a["id"], {"version": a["version"], "title": "x"})
    with pytest.raises(CommandError, match="in the trash"):
        pages.create({"title": "Child", "parentId": a["id"]})
    restored = pages.restore(a["id"])
    assert restored["children"][0]["id"] == c["id"]
    pages.archive(c["id"])
    pages.archive(a["id"])
    assert pages.restore(c["id"])["parentId"] is None, "a page restored out of a trashed parent goes to the top"
    assert pages.delete(a["id"])["deleted"] >= 2
    with pytest.raises(CommandError, match="No such page"):
        pages.get(a["id"])
    audit = [e["action"] for e in center.audit(200)["entries"]]
    for action in ("page.create", "page.move", "page.archive", "page.restore", "page.delete"):
        assert action in audit
    assert center.verify_audit()


def test_references_collects_every_kind_of_mention():
    blocks = [{"type": "p", "text": [mention("page", "pg_abcdefgh12", "x")]}, {"type": "ref", "ref": {"kind": "task", "id": "tsk_abcdef12", "label": ""}},
              {"type": "board", "items": [{"refs": [{"kind": "device", "id": "d1"}], "taskId": "tsk_zzzzzz12"}]}]
    assert references(blocks) == {("page", "pg_abcdefgh12"), ("task", "tsk_abcdef12"), ("device", "d1"), ("task", "tsk_zzzzzz12")}


def test_routes_need_the_bearer_and_a_stale_save_is_a_409(center):
    app = FastAPI()
    app.include_router(create_command_router(SimpleNamespace(command=center), TOKEN))
    client = TestClient(app)
    auth = {"Authorization": f"Bearer {TOKEN}"}
    assert client.get("/v1/cc/pages").status_code == 401
    listed = client.get("/v1/cc/pages", headers=auth).json()
    assert listed == {"pages": [], "templates": ["blank", "content", "daily", "weekly"]}
    page = client.post("/v1/cc/pages", headers=auth, json={"template": "daily"}).json()
    assert page["title"] == "Daily app check"
    saved = client.post(f"/v1/cc/pages/{page['id']}", headers=auth, json={"version": page["version"], "title": "Morning"}).json()
    stale = client.post(f"/v1/cc/pages/{page['id']}", headers=auth, json={"version": page["version"], "title": "Old"})
    assert stale.status_code == 409 and stale.json()["detail"]["code"] == "PAGE_CHANGED"
    assert client.get("/v1/cc/pages-search", headers=auth, params={"q": "Morning"}).json()["pages"][0]["id"] == page["id"]
    assert client.get("/v1/cc/backlinks", headers=auth, params={"kind": "device", "id": "d1"}).json() == {"pages": []}
    assert client.post(f"/v1/cc/pages/{page['id']}/archive", headers=auth).json()["archived"] == 1
    assert client.get("/v1/cc/pages-trash", headers=auth).json()["pages"][0]["title"] == "Morning"
    assert client.post(f"/v1/cc/pages/{page['id']}/delete", headers=auth).json()["deleted"] == 1
    assert client.get(f"/v1/cc/pages/{saved['id']}", headers=auth).status_code == 400
    # Nothing of the page is kept outside its row: the audit holds actions and ids only.
    assert "Morning" not in json.dumps(center.audit(100))
