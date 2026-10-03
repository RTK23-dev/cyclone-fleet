"""Plan 33 §7 (C4 moved forward): the Command Center's AI project manager on the owner's OpenRouter key.

A scripted OpenRouter stands in for the provider. What must hold: the key is write-only and never stored in the
database, audit, status or a model's context; the tools are a fixed set without delete, approve or vault; page and
card edits are proposals unless the owner allows workspace edits; tasks and routines are always proposals; budgets
and step limits stop a turn; provider errors are explained; hidden reasoning is never kept; every route needs the
bearer."""
from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from types import SimpleNamespace

from cyclone_device_gateway.command import mcp
from cyclone_device_gateway.command.ai import AiStore, MAX_STEPS, TOOLS
from cyclone_device_gateway.command.api import create_command_router
from cyclone_device_gateway.command.center import CommandCenter, CommandError
from cyclone_device_gateway.command.connections import GrantStore
from cyclone_device_gateway.command.pagetext import blocks_from_markdown, page_markdown

TOKEN = "tok_ai_0123456789abcdef"
KEY = "sk-or-v1-CANARY0123456789abcdefCANARY"
MODELS = {"data": [
    {"id": "acme/planner-large", "name": "Acme: Planner Large", "context_length": 200000,
     "pricing": {"prompt": "0.000003", "completion": "0.000015"}, "supported_parameters": ["tools", "tool_choice", "max_tokens"]},
    {"id": "acme/tiny-free:free", "name": "Acme: Tiny (free)", "context_length": 32000,
     "pricing": {"prompt": "0", "completion": "0"}, "supported_parameters": ["tools"]},
    {"id": "acme/no-tools", "name": "Acme: Chat only", "context_length": 8000,
     "pricing": {"prompt": "0.000001", "completion": "0.000002"}, "supported_parameters": ["max_tokens"]},
    {"id": "bad id with spaces", "name": "Broken"},
]}


class Contract:
    def cc_start(self, *a: Any, **k: Any) -> dict[str, Any]:
        raise AssertionError("no phone in these tests")


class Clock:
    ms = int(datetime(2026, 9, 28, 9, 0, tzinfo=timezone.utc).timestamp() * 1000)

    def __call__(self) -> int:
        self.ms += 1
        return self.ms


def call(name: str, arguments: Any, ident: str | None = None) -> dict[str, Any]:
    return {"id": ident or f"call_{name}", "type": "function",
            "function": {"name": name, "arguments": arguments if isinstance(arguments, str) else json.dumps(arguments)}}


def answer(text: str = "", calls: list[dict[str, Any]] | None = None, cost: float = 0.001, **extra: Any) -> dict[str, Any]:
    message: dict[str, Any] = {"role": "assistant", "content": text}
    if calls:
        message["tool_calls"] = calls
    message.update(extra)
    return {"id": "gen-1", "choices": [{"index": 0, "message": message, "finish_reason": "tool_calls" if calls else "stop"}],
            "usage": {"prompt_tokens": 1200, "completion_tokens": 80, "cost": cost}}


class FakeRouter:
    """OpenRouter's /key, /models and /chat/completions, scripted."""

    def __init__(self) -> None:
        self.requests: list[dict[str, Any]] = []
        self.script: list[Any] = []
        self.key_status = 200

    def __call__(self, method: str, url: str, *, headers: dict[str, str], body: bytes | None = None, timeout: float = 60.0,
                 **_: Any) -> mcp.Response:
        assert url.startswith("https://openrouter.ai/api/v1/"), url
        payload = json.loads(body) if body else None
        self.requests.append({"method": method, "url": url, "headers": dict(headers), "body": payload})
        if url.endswith("/key"):
            if self.key_status != 200:
                return mcp.Response(self.key_status, {}, b'{"error":{"message":"No auth credentials found"}}')
            return mcp.Response(200, {}, json.dumps({"data": {"label": KEY[:12] + "...", "usage": 1.25, "limit": 10, "limit_remaining": 8.75}}).encode())
        if url.endswith("/models"):
            return mcp.Response(200, {}, json.dumps(MODELS).encode())
        assert url.endswith("/chat/completions") and method == "POST"
        step = self.script.pop(0) if self.script else answer("Done.")
        if callable(step):
            step = step(payload)
        if isinstance(step, tuple):
            return mcp.Response(step[0], {}, json.dumps(step[1]).encode())
        return mcp.Response(200, {}, json.dumps(step).encode())

    @property
    def chats(self) -> list[dict[str, Any]]:
        return [r["body"] for r in self.requests if r["url"].endswith("/chat/completions")]


@pytest.fixture()
def router() -> FakeRouter:
    return FakeRouter()


@pytest.fixture()
def center(tmp_path: Path, router: FakeRouter):
    devices = lambda: [{"deviceId": "phone-a", "name": "Pixel 8", "state": "ready"}]  # noqa: E731
    cc = CommandCenter(tmp_path / "cc.db", Contract(), devices, clock=Clock(),
                       connections={"spawn": lambda fn: fn(), "sleep": lambda s: None},
                       ai={"send": router, "spawn": lambda fn: fn()})
    yield cc
    cc.stop()


def ready(center: CommandCenter, **settings: Any) -> None:
    center.ai.set_key({"key": KEY})
    center.ai.update_settings({"model": "acme/planner-large", **settings})


def test_the_key_is_write_only_and_checked_with_openrouter(center, router, tmp_path):
    ai = center.ai
    with pytest.raises(CommandError, match="does not look like"):
        ai.set_key({"key": "short"})
    router.key_status = 401
    with pytest.raises(CommandError, match="refused this key"):
        ai.set_key({"key": KEY})
    assert ai.status()["keySaved"] is False
    router.key_status = 200
    saved = ai.set_key({"key": KEY})
    assert saved["keySaved"] is True and saved["check"] == {"ok": True, "usedUsd": 1.25, "limitUsd": 10.0, "remainingUsd": 8.75}
    # The key went to OpenRouter as a bearer only, and is nowhere Glass or the audit can read.
    assert router.requests[-1]["headers"]["Authorization"] == f"Bearer {KEY}"
    assert KEY not in json.dumps(ai.status()) and KEY[8:20] not in json.dumps(saved)
    assert KEY not in json.dumps(center.audit(1000))
    for path in tmp_path.glob("cc.db*"):
        assert KEY.encode() not in path.read_bytes()
    assert ai.test_key()["check"]["ok"] is True
    assert ai.forget_key()["keySaved"] is False
    with pytest.raises(CommandError, match="Send \\{key\\}"):
        ai.set_key({"key": KEY, "label": "x"})


def test_a_kept_key_is_sealed_on_disk(tmp_path, router):
    flip = lambda data: bytes(b ^ 0x5A for b in data)  # noqa: E731 - a stand-in for DPAPI
    grants = GrantStore(tmp_path / "connections.dpapi", protect=flip, unprotect=flip)
    cc = CommandCenter(tmp_path / "cc.db", Contract(), lambda: [], clock=Clock(),
                       connections={"grants": grants, "spawn": lambda fn: fn()}, ai={"send": router, "spawn": lambda fn: fn()})
    try:
        assert cc.ai.set_key({"key": KEY})["keyKept"] is True
        assert KEY.encode() not in (tmp_path / "connections.dpapi").read_bytes()
    finally:
        cc.stop()


def test_models_come_from_openrouter_with_prices_and_only_tool_users_can_be_picked(center, router):
    listed = center.ai.models()
    assert [m["id"] for m in listed["models"]] == ["acme/planner-large", "acme/tiny-free:free"]
    assert listed["total"] == 3
    large = listed["models"][0]
    assert large == {"id": "acme/planner-large", "name": "Acme: Planner Large", "contextLength": 200000,
                     "promptPerM": 3.0, "completionPerM": 15.0, "tools": True, "free": False}
    assert listed["models"][1]["free"] is True
    assert [m["id"] for m in center.ai.models(everything=True)["models"]] == ["acme/no-tools", "acme/planner-large", "acme/tiny-free:free"]
    with pytest.raises(CommandError, match="cannot use tools"):
        center.ai.update_settings({"model": "acme/no-tools"})
    with pytest.raises(CommandError, match="not in OpenRouter's list"):
        center.ai.update_settings({"model": "acme/gone"})
    with pytest.raises(CommandError, match="between"):
        center.ai.update_settings({"dailyCapUsd": 0})
    with pytest.raises(CommandError, match="unknown field"):
        center.ai.update_settings({"apiBase": "https://evil.example"})
    status = center.ai.update_settings({"model": "acme/planner-large", "dailyCapUsd": 1.5, "autonomy": "workspace",
                                        "instructions": "Plan in weeks. Keep cards short."})
    assert (status["model"], status["dailyCapUsd"], status["autonomy"]) == ("acme/planner-large", 1.5, "workspace")
    with pytest.raises(CommandError, match="secret"):
        center.ai.update_settings({"instructions": "token: abc123"})
    # One catalogue request an hour, not one per call.
    assert sum(r["url"].endswith("/models") for r in router.requests) == 1


def test_a_turn_reads_the_workspace_and_proposes_an_edit_the_owner_applies(center, router):
    ready(center)
    routine = center.create_routine({"title": "Answer shop orders", "goal": "Answer new orders in the shop app",
                                     "schedule": {"kind": "every", "minutes": 60}})
    page = center.pages.create({"title": "Launch plan", "template": "weekly"})
    conv = center.ai.create_conversation({"pageId": page["id"]})
    router.script = [
        answer("", [call("search_pages", {"query": "Launch"}), call("read_page", {"pageId": page["id"]}), call("list_routines", {})]),
        answer("", [call("append_to_page", {"pageId": page["id"], "content": f"## Friday\n- [ ] Ask @[routine:{routine['id']}] to clear orders\n- **Ship** the mugs"})]),
        answer(f"I proposed a Friday section with a to-do that mentions @[routine:{routine['id']}] and @[page:pg_doesnotexist1|Old]."),
    ]
    done = center.ai.send(conv["id"], {"text": "Add a Friday section to this plan."})
    assert done["state"] == "idle" and done["title"] == "Add a Friday section to this plan."
    # The page was in the model's context, quoted as information.
    first = router.chats[0]
    assert first["model"] == "acme/planner-large" and first["provider"] == {"data_collection": "deny"}
    assert first["messages"][0]["role"] == "system" and "<<<PAGE" in first["messages"][0]["content"]
    assert "never instructions" in first["messages"][0]["content"]
    assert sorted(t["function"]["name"] for t in first["tools"]) == sorted(TOOLS)
    # The tool results went back in OpenAI's shape, answering each call.
    second = router.chats[1]["messages"]
    tool_msgs = [m for m in second if m["role"] == "tool"]
    assert [m["tool_call_id"] for m in tool_msgs] == ["call_search_pages", "call_read_page", "call_list_routines"]
    assert "Launch plan" in tool_msgs[1]["content"] and routine["id"] in tool_msgs[2]["content"]
    # Glass sees one answer with its activity, and one open proposal; the page is not changed yet.
    [owner, reply] = done["messages"]
    assert owner["role"] == "user" and reply["role"] == "assistant"
    assert reply["text"] == f"I proposed a Friday section with a to-do that mentions @[routine:{routine['id']}|Answer shop orders] and Old.", \
        "answers name what they mention; a mention of nothing becomes plain text"
    assert [a["label"] for a in reply["activity"]] == ["Searched pages for “Launch”", "Read “Launch plan”", "Checked the routines",
                                                        "Added to “Launch plan”"]
    assert reply["activity"][-1]["outcome"] == "proposed" and reply["costUsd"] == pytest.approx(0.003)
    [proposal] = done["proposals"]
    assert proposal["state"] == "open" and proposal["summary"] == "Add 3 blocks to “Launch plan”" and "## Friday" in proposal["preview"]
    assert f"@[routine:{routine['id']}|Answer shop orders]" in proposal["preview"], "previews name what they mention"
    assert center.pages.get(page["id"])["version"] == page["version"]
    applied = center.ai.apply(proposal["id"])
    assert applied["state"] == "applied"
    blocks = center.pages.get(page["id"])["blocks"]
    assert [b["type"] for b in blocks[-3:]] == ["h2", "todo", "bullet"]
    assert blocks[-2]["text"][1] == {"ref": {"kind": "routine", "id": routine["id"], "label": "Answer shop orders"}}
    assert blocks[-1]["text"][0] == {"t": "Ship", "b": True}
    assert center.pages.backlinks("routine", routine["id"])[0]["id"] == page["id"]
    with pytest.raises(CommandError, match="already applied"):
        center.ai.apply(proposal["id"])
    assert [m["role"] for m in center.ai.get(conv["id"])["messages"]] == ["user", "assistant"], "notes for the model stay out of the owner's chat"
    # The model hears about it at the start of the next message.
    router.script = [answer("Noted.")]
    center.ai.send(conv["id"], {"text": "Thanks"})
    last_user = [m for m in router.chats[-1]["messages"] if m["role"] == "user"][-1]["content"]
    assert last_user.startswith("(Cyclone, since your last answer: The owner applied: Add 3 blocks") and last_user.endswith("Thanks")
    assert center.verify_audit()


def test_workspace_edits_can_be_direct_but_tasks_and_routines_always_wait_for_the_owner(center, router):
    ready(center, autonomy="workspace")
    page = center.pages.create({"title": "Week"})
    conv = center.ai.create_conversation({})
    router.script = [
        answer("", [call("add_card", {"pageId": page["id"], "title": "Film the mug", "status": "doing", "due": "2026-10-02",
                                      "links": ["device:phone-a"]}),
                    call("create_task", {"goal": "Open Instagram and post the mug video", "deviceId": "phone-a"}),
                    call("create_routine", {"title": "Morning check", "goal": "Check the shop", "schedule": {"kind": "daily", "time": "07:30", "days": [1, 2, 3, 4, 5]}})]),
        answer("Added the card; the task and routine wait for you."),
    ]
    done = center.ai.send(conv["id"], {"text": "Plan filming the mug"})
    board = center.pages.get(page["id"])["blocks"][0]
    assert board["type"] == "board" and board["items"][0]["title"] == "Film the mug" and board["items"][0]["status"] == "doing"
    assert board["items"][0]["refs"] == [{"kind": "device", "id": "phone-a", "label": "Pixel 8"}]
    assert [a["outcome"] for a in done["messages"][1]["activity"]] == ["done", "proposed", "proposed"]
    assert center.list_tasks() == [] and center.list_routines() == []
    assert any(e["actor"] == "ai" and e["action"] == "page.edit" for e in center.audit(100)["entries"])
    task_prop, routine_prop = done["proposals"]
    assert task_prop["summary"] == "Start a task on Pixel 8: “Open Instagram and post the mug video”"
    assert routine_prop["summary"].startswith("Create the routine “Morning check”")
    center.ai.apply(task_prop["id"])
    center.ai.discard(routine_prop["id"])
    [task] = center.list_tasks()
    assert task["goal"] == "Open Instagram and post the mug video" and task["deviceId"] == "phone-a"
    assert center.list_routines() == []
    assert center.ai.get(conv["id"])["proposals"][1]["state"] == "discarded"


def test_bad_calls_come_back_as_errors_the_model_can_fix(center, router):
    ready(center)
    page = center.pages.create({"title": "Notes"})
    conv = center.ai.create_conversation({})
    router.script = [
        answer("", [call("delete_page", {"pageId": page["id"]}, "c1"),
                    call("append_to_page", "{not json", "c2"),
                    call("append_to_page", {"pageId": page["id"], "content": "password: hunter2"}, "c3"),
                    call("append_to_page", {"pageId": page["id"], "content": "Ask @[routine:rtn_madeup01]"}, "c4"),
                    call("create_task", {"goal": "Post it", "deviceId": "phone-z"}, "c5"),
                    call("read_page", {"pageId": page["id"], "extra": 1}, "c6"),
                    call("update_block", {"pageId": page["id"], "blockId": "bnope1"}, "c7")]),
        answer("Sorry."),
    ]
    done = center.ai.send(conv["id"], {"text": "Try things"})
    results = {m["tool_call_id"]: json.loads(m["content"]) for m in router.chats[1]["messages"] if m["role"] == "tool"}
    assert "no tool delete_page" in results["c1"]["error"]
    assert "not valid JSON" in results["c2"]["error"]
    assert "secret" in results["c3"]["error"]
    assert "no routine rtn_madeup01" in results["c4"]["error"]
    assert "no phone" in results["c5"]["error"]
    assert "unknown field extra" in results["c6"]["error"]
    assert "not on the page" in results["c7"]["error"]
    assert done["proposals"] == [] and center.pages.get(page["id"])["blocks"] == []
    assert all(a["outcome"] in ("error", "done") for a in done["messages"][1]["activity"])
    # The toolbox itself has no delete, approve, vault or shell.
    assert not {n for n in TOOLS if any(w in n for w in ("delete", "approve", "vault", "shell", "answer", "account_update"))}


def test_owner_text_with_a_secret_is_refused_and_reasoning_is_never_kept(center, router, tmp_path):
    ready(center)
    conv = center.ai.create_conversation({})
    with pytest.raises(CommandError, match="Leave passwords"):
        center.ai.send(conv["id"], {"text": "My password: hunter2, log in please"})
    router.script = [answer("Here is the plan.", reasoning="HIDDEN-REASONING-CANARY", reasoning_details=[{"text": "HIDDEN-REASONING-CANARY"}])]
    center.ai.send(conv["id"], {"text": "Plan my week"})
    for path in tmp_path.glob("cc.db*"):
        assert b"HIDDEN-REASONING-CANARY" not in path.read_bytes()
    assert "HIDDEN" not in json.dumps(center.ai.get(conv["id"]))


def test_budgets_and_step_limits_stop_a_turn(center, router):
    ready(center, dailyCapUsd=0.05)
    conv = center.ai.create_conversation({})
    router.script = [answer("", [call("list_pages", {})], cost=0.06), answer("never reached")]
    done = center.ai.send(conv["id"], {"text": "Look around"})
    assert done["state"] == "failed" and "Today's AI limit ($0.05)" in done["detail"]
    assert len(router.chats) == 1 and done["messages"][-1]["role"] == "note"
    assert center.ai.status()["spentTodayUsd"] == pytest.approx(0.06)
    # Without a budget in the way, a model that never stops is stopped after MAX_STEPS.
    center.ai.update_settings({"dailyCapUsd": 100})
    router.script = [answer("", [call("list_pages", {}, f"c{i}")], cost=0.0) for i in range(MAX_STEPS + 3)]
    looping = center.ai.send(center.ai.create_conversation({})["id"], {"text": "Loop"})
    assert f"Stopped after {MAX_STEPS} steps" in looping["detail"] and len(router.chats) == 1 + MAX_STEPS


def test_provider_errors_are_explained_and_private_providers_can_be_turned_off(center, router):
    ready(center)
    conv = center.ai.create_conversation({})
    router.script = [(402, {"error": {"code": 402, "message": "Insufficient credits"}})]
    done = center.ai.send(conv["id"], {"text": "Hi"})
    assert done["state"] == "failed" and "out of credits" in done["detail"]
    router.script = [(404, {"error": {"code": 404, "message": "No endpoints found matching your data policy"}})]
    assert "keeps your data private" in center.ai.send(conv["id"], {"text": "Hi"})["detail"]
    center.ai.update_settings({"privateOnly": False})
    router.script = [answer("Hello!")]
    assert center.ai.send(conv["id"], {"text": "Hi"})["state"] == "idle"
    assert "provider" not in router.chats[-1]
    # A turn needs a key and a model; the model can differ per conversation.
    center.ai.forget_key()
    from cyclone_device_gateway.command.ai import AiError
    with pytest.raises(AiError, match="Add your OpenRouter key"):
        center.ai.send(conv["id"], {"text": "Hi"})
    center.ai.set_key({"key": KEY})
    center.ai.update_conversation(conv["id"], {"model": "acme/tiny-free:free"})
    router.script = [answer("Free model here.")]
    center.ai.send(conv["id"], {"text": "Hi"})
    assert router.chats[-1]["model"] == "acme/tiny-free:free"


def test_a_turn_interrupted_by_a_restart_is_marked_and_stop_works(tmp_path, router):
    holder: dict[str, Callable[[], None]] = {}
    cc = CommandCenter(tmp_path / "cc.db", Contract(), lambda: [], clock=Clock(), connections={"spawn": lambda fn: fn()},
                       ai={"send": router, "spawn": lambda fn: holder.__setitem__("run", fn)})
    ready(cc)
    conv = cc.ai.create_conversation({})
    assert cc.ai.send(conv["id"], {"text": "Plan"})["state"] == "working"
    with pytest.raises(CommandError, match="still answering"):
        cc.ai.send(conv["id"], {"text": "Again"})
    cc.ai.stop(conv["id"])
    holder["run"]()
    stopped = cc.ai.get(conv["id"])
    assert stopped["state"] == "idle" and stopped["messages"][-1]["text"] == "Stopped." and router.chats == []
    cc.ai.send(conv["id"], {"text": "Plan again"})  # left working: the runtime "stops" here
    cc.stop()
    again = CommandCenter(tmp_path / "cc.db", Contract(), lambda: [], clock=Clock(), ai={"send": router})
    try:
        assert again.ai.get(conv["id"])["state"] == "failed"
        assert again.ai.get(conv["id"])["detail"] == "Cyclone restarted while this ran."
    finally:
        again.stop()


def test_markdown_becomes_typed_blocks_and_pages_read_back_with_ids(center):
    page = center.pages.create({"title": "Plan", "icon": "🗓️", "template": "weekly"})
    lookup = lambda kind, ident: "Plan" if ident == page["id"] else None  # noqa: E731
    blocks = blocks_from_markdown(f"# Title\n\n## Sub\n### Small\n- [ ] open\n- [x] done\n- item\n1. first\n> quoted\n! note\n---\n"
                                  f"Plain *it* and `code` and ~~gone~~ see @[page:{page['id']}]\n```\ncode line\n```", lookup)
    assert [b["type"] for b in blocks] == ["h1", "h2", "h3", "todo", "todo", "bullet", "number", "quote", "callout", "divider", "p", "p"]
    assert blocks[4]["checked"] is True and blocks[3]["checked"] is False
    assert blocks[10]["text"] == [{"t": "Plain "}, {"t": "it", "i": True}, {"t": " and "}, {"t": "code", "c": True}, {"t": " and "},
                                  {"t": "gone", "s": True}, {"t": " see "}, {"ref": {"kind": "page", "id": page["id"], "label": "Plan"}}]
    assert blocks[11]["text"] == [{"t": "code line", "c": True}]
    with pytest.raises(CommandError):
        blocks_from_markdown("", lookup)
    text = page_markdown(center.pages.get(page["id"]))
    assert text.startswith("# 🗓️ Plan\n") and "(plan board \"Plan\", shown as a board; 3 cards)" in text
    assert "(todo) Decide this week's posts" in text and f"[{page['blocks'][0]['id']}] ! Plan the week" in text


def test_every_ai_route_needs_the_bearer_and_errors_map_cleanly(center, router):
    app = FastAPI()
    app.include_router(create_command_router(SimpleNamespace(command=center), TOKEN))
    client = TestClient(app)
    auth = {"Authorization": f"Bearer {TOKEN}"}
    for method, path in (("get", "/v1/cc/ai"), ("post", "/v1/cc/ai/settings"), ("post", "/v1/cc/ai/key"), ("post", "/v1/cc/ai/key/test"),
                         ("post", "/v1/cc/ai/key/forget"), ("get", "/v1/cc/ai/models"), ("get", "/v1/cc/ai/conversations"),
                         ("post", "/v1/cc/ai/conversations"), ("get", "/v1/cc/ai/conversations/ai_abcdefgh"),
                         ("post", "/v1/cc/ai/conversations/ai_abcdefgh/messages"), ("post", "/v1/cc/ai/proposals/prp_abcdefgh/apply"),
                         ("get", "/v1/cc/pages/pg_abcdefgh/version")):
        assert client.request(method.upper(), path, json={} if method == "post" else None).status_code == 401, path
    assert client.get("/v1/cc/ai", headers=auth).json()["provider"]["name"] == "OpenRouter"
    assert client.post("/v1/cc/ai/key", headers=auth, json={"key": "x"}).status_code == 400
    assert KEY not in client.post("/v1/cc/ai/key", headers=auth, json={"key": KEY}).text
    conv = client.post("/v1/cc/ai/conversations", headers=auth, json={}).json()
    assert client.post(f"/v1/cc/ai/conversations/{conv['id']}/messages", headers=auth, json={"text": "Hi"}).json()["detail"]["message"] \
        == "Pick a model in AI settings first."
    client.post("/v1/cc/ai/settings", headers=auth, json={"model": "acme/planner-large"})
    router.script = [answer("Hello from the model.")]
    sent = client.post(f"/v1/cc/ai/conversations/{conv['id']}/messages", headers=auth, json={"text": "Hi"}).json()
    assert sent["messages"][-1]["text"] == "Hello from the model."
    client.post("/v1/cc/ai/key/forget", headers=auth)
    no_key = client.post(f"/v1/cc/ai/conversations/{conv['id']}/messages", headers=auth, json={"text": "Hi"})
    assert no_key.status_code == 409 and no_key.json()["detail"]["code"] == "AI_UNAVAILABLE"
    page = center.pages.create({"title": "V"})
    assert client.get(f"/v1/cc/pages/{page['id']}/version", headers=auth).json()["version"] == 1
