"""Plan 34 M3 + M4 (alpha.58): the API maker (OpenAPI / Swagger descriptions become connections), result chaining
into the next step and into a phone's goal as quoted data, and connector cards."""
from __future__ import annotations

import json
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import pytest

from cyclone_device_gateway.command import openapi, steps as chain
from cyclone_device_gateway.command.center import CommandCenter, CommandError
from cyclone_device_gateway.command.connections import card_hash, tool_hash
from cyclone_device_gateway.command import mcp

import fake_api
from fake_api import PDF, FakeApi
from fake_mcp import FakeMcp

KEY = "shop-key-CANARY-7731"
REDIRECT = "http://127.0.0.1:8765/v1/cc/connections/oauth/callback"


class Phone:
    def __init__(self) -> None:
        self.started: list[dict[str, Any]] = []

    def cc_start(self, device_id: str, goal: str, **extra: Any) -> dict[str, Any]:
        self.started.append({"device": device_id, "goal": goal, **extra})
        return {"accepted": True, "missionId": f"m{len(self.started)}"}

    def cc_status(self, device_id: str, mission_id: str) -> dict[str, Any]:
        return {"missionId": mission_id, "status": "completed", "live": False, "turns": 2, "workingMs": 5, "costUsd": 0.0,
                "summary": "Replied.", "moment": None, "leases": []}

    def cc_answer(self, *a: Any, **k: Any) -> dict[str, Any]:
        return {"handled": True}


class Clock:
    ms = int(datetime(2026, 9, 28, 9, 0, tzinfo=timezone.utc).timestamp() * 1000)

    def __call__(self) -> int:
        return self.ms


@pytest.fixture()
def world(tmp_path: Path):
    phone = Phone()
    clock = Clock()
    center = CommandCenter(tmp_path / "cc.db", phone, lambda: [{"deviceId": "phone-a", "paired": True, "state": "ready"}], clock=clock,
                           local_now=lambda: datetime.fromtimestamp(clock.ms / 1000, timezone.utc),
                           connections={"spawn": lambda fn: fn(), "sleep": lambda s: None})
    yield center, phone, tmp_path
    center.stop()


@pytest.fixture()
def shop():
    server = FakeApi()
    yield server
    server.close()


def ready_shop(center: CommandCenter, shop: FakeApi) -> dict[str, Any]:
    con = center.connections.add({"specUrl": shop.spec_url})
    assert con["status"] == "needs_key" and con["kind"] == "api" and con["keyHeader"] == "X-Shop-Key"
    con = center.connections.set_key(con["id"], {"header": "X-Shop-Key", "value": KEY})
    assert con["status"] == "ready", con
    return center.connections.settings(con["id"], {"allowReads": True, "allowed": ["listOrders", "getOrder", "replyToOrder", "getInvoice", "health"]})


def approve_open(center: CommandCenter) -> None:
    for approval in center.list_approvals():
        if approval["kind"] == "spend":
            center.answer(approval["id"], {"action": "approve"})


# ---------------------------------------------------------------------------------------------- reading descriptions


def test_openapi_3_becomes_tools_with_inputs_classes_and_sign_in():
    api = openapi.normalize(fake_api.spec("https://shop.example"))
    names = [op["name"] for op in api["operations"]]
    assert names == ["listOrders", "getOrder", "cancelOrder", "replyToOrder", "searchProducts", "getInvoice", "goElsewhere", "health"]
    assert api["base"] == "https://shop.example/v1" and api["scheme"] == {"id": "shop", "type": "header", "name": "X-Shop-Key"}
    assert any("uploadPhoto" not in s and "UPLOAD" not in s for s in api["skipped"]) and "POST /upload" in api["skipped"][0]
    reply = next(op for op in api["operations"] if op["name"] == "replyToOrder")
    assert [(i["arg"], i["in"], i["required"]) for i in reply["inputs"]] == [("id", "path", True), ("text", "body", True), ("notify", "body", False)]
    health = next(op for op in api["operations"] if op["name"] == "health")
    assert health["auth"] is False and reply["auth"] is True
    classes = {op["name"]: openapi.classify(op) for op in api["operations"]}
    assert classes == {"listOrders": "read", "getOrder": "read", "cancelOrder": "sensitive", "replyToOrder": "sensitive",
                       "searchProducts": "change", "getInvoice": "read", "goElsewhere": "read", "health": "read"}
    tool = next(t for t in openapi.tools(api) if t["name"] == "listOrders")
    assert tool["inputSchema"]["properties"]["status"]["enum"] == ["open", "done"] and tool["annotations"]["readOnlyHint"] is True


def test_swagger_2_yaml_with_query_key_form_and_body_param():
    text = """
swagger: "2.0"
info: {title: Old Weather, version: "1"}
host: weather.example
basePath: /api
schemes: [https]
securityDefinitions:
  key: {type: apiKey, in: query, name: appid}
security: [{key: []}]
paths:
  /today:
    get:
      operationId: today
      parameters:
        - {name: city, in: query, type: string, required: true}
        - {name: days, in: query, type: integer}
  /notes:
    post:
      operationId: addNote
      consumes: [application/x-www-form-urlencoded]
      parameters:
        - {name: text, in: formData, type: string, required: true}
  /bulk:
    put:
      operationId: bulkSet
      parameters:
        - {name: payload, in: body, required: true, schema: {type: array, items: {type: string}}}
"""
    api = openapi.normalize(openapi.load_text(text))
    assert api["base"] == "https://weather.example/api" and api["scheme"]["type"] == "query" and api["scheme"]["name"] == "appid"
    ops = {op["name"]: op for op in api["operations"]}
    assert ops["addNote"]["body"]["type"] == "form" and ops["addNote"]["inputs"][0]["arg"] == "text"
    assert ops["bulkSet"]["body"]["whole"] is True and ops["bulkSet"]["inputs"][0]["arg"] == "body"
    session = openapi.ApiSession(api, credential=lambda: {"query": "appid", "value": "k"})
    url, headers, body = session.build(ops["today"], {"city": "Utrecht", "days": "3"})
    assert url == "https://weather.example/api/today?city=Utrecht&days=3" and body is None
    url, headers, body = session.build(ops["addNote"], {"text": "a&b=c"})
    assert body == b"text=a%26b%3Dc" and headers["Content-Type"] == "application/x-www-form-urlencoded"
    url, headers, body = session.build(ops["bulkSet"], {"body": '["x", "y"]'})
    assert json.loads(body) == ["x", "y"]


def test_unsafe_or_unusable_descriptions_are_refused():
    with pytest.raises(openapi.SpecError, match="aliases"):
        openapi.load_text("a: &x [1, 2]\nb: *x\nopenapi: 3.0.0\n")
    with pytest.raises(openapi.SpecError, match="not an OpenAPI"):
        openapi.normalize({"hello": "world"})
    with pytest.raises(openapi.SpecError, match="where the API is"):
        openapi.normalize({"openapi": "3.0.0", "paths": {"/x": {"get": {}}}})
    with pytest.raises(openapi.SpecError, match="https"):
        openapi.normalize({"openapi": "3.0.0", "servers": [{"url": "http://shop.example"}], "paths": {"/x": {"get": {}}}}, base_url="http://shop.example")
    doc = {"openapi": "3.1.0", "servers": [{"url": "https://a.example"}], "components": {"schemas": {"Loop": {"$ref": "#/components/schemas/Loop"}}},
           "paths": {"/x": {"post": {"operationId": "x", "requestBody": {"content": {"application/json": {"schema": {"$ref": "https://evil.example/s.json"}}}}}},
                     "/y": {"post": {"operationId": "y", "requestBody": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/Loop"}}}}}}}}
    api = openapi.normalize(doc)  # remote and looping references become an empty schema, never a fetch or a hang
    assert [op["name"] for op in api["operations"]] == ["x", "y"]
    described = openapi.normalize({"openapi": "3.0.0", "servers": [{"url": "https://a.example"}],
                                   "paths": {"/x": {"get": {"description": "Example: api_key=sk-live-123 then call"}}}})
    assert "sk-live-123" not in json.dumps(described)


def test_requests_are_built_safely():
    api = openapi.normalize(fake_api.spec("https://shop.example"))
    ops = {op["name"]: op for op in api["operations"]}
    session = openapi.ApiSession(api, credential=lambda: None)
    url, _, _ = session.build(ops["getOrder"], {"id": "a/../../admin?x=1"})
    assert url == "https://shop.example/v1/orders/a%2F..%2F..%2Fadmin%3Fx%3D1"
    for bad, match in (({"id": ".."}, "not a valid"), ({}, "needs id"), ({"id": "1", "extra": 2}, "no input extra")):
        with pytest.raises(mcp.McpError, match=match):
            session.build(ops["getOrder"], bad)
    with pytest.raises(mcp.McpError, match="one of"):
        session.build(ops["listOrders"], {"status": "all"})
    with pytest.raises(mcp.McpError, match="number"):
        session.build(ops["listOrders"], {"limit": "many"})
    with pytest.raises(mcp.NeedsSignIn):
        session._sign("https://shop.example/v1/orders", {})


def test_private_and_loopback_addresses_are_refused_at_call_time():
    with pytest.raises(mcp.McpError, match="private network"):
        openapi.resolve("https://10.1.2.3/v1", allow_loopback=False)
    with pytest.raises(mcp.McpError, match="on this PC"):
        openapi.resolve("http://127.0.0.1:9/v1", allow_loopback=False)
    assert openapi.resolve("http://localhost:9/v1", allow_loopback=True) == "127.0.0.1"
    with pytest.raises(mcp.McpError):
        openapi.request("GET", "http://example.com/", headers={})  # plain http off this PC is refused before any socket


def test_a_card_round_trip_keeps_every_tool_identical():
    api = openapi.normalize(fake_api.spec("https://shop.example"))
    again = openapi.normalize(openapi.to_openapi(api))
    assert [tool_hash(t) for t in openapi.tools(again)] == [tool_hash(t) for t in openapi.tools(api)]
    assert again["scheme"] == api["scheme"] and again["base"] == api["base"]
    oauth = openapi.normalize(fake_api.spec("https://shop.example", auth="oauth"))
    assert openapi.normalize(openapi.to_openapi(oauth))["scheme"] == oauth["scheme"]
    assert oauth["scheme"]["scopes"] == ["orders:read", "orders:write"]


# ---------------------------------------------------------------------------------------------- API connections


def test_api_connection_with_a_header_key_reads_without_asking_and_writes_ask_first(world, shop):
    center, _, tmp = world
    con = ready_shop(center, shop)
    tools = {t["name"]: t for t in con["tools"]}
    assert tools["listOrders"]["class"] == "read" and tools["replyToOrder"]["class"] == "sensitive" and tools["replyToOrder"]["rule"] == "always"
    assert con["api"]["operations"] == 8 and con["api"]["scheme"]["name"] == "X-Shop-Key" and con["url"] == shop.base + "/v1"
    call = center.connections.call(con["id"], "listOrders", {"status": "open", "limit": 1})
    assert call["state"] == "done" and call["result"]["orders"][0]["id"] == "ord-1001", call
    seen = shop.requests[-1]
    assert seen["headers"]["X-Shop-Key"] == KEY and seen["headers"]["User-Agent"].startswith("Cyclone") and seen["query"] == {"status": ["open"], "limit": ["1"]}
    # The write waits for the owner, then sends the JSON body.
    call = center.connections.call(con["id"], "replyToOrder", {"id": "ord-1001", "text": "Thanks Sam!"})
    assert call["state"] == "waiting"
    assert center.list_approvals()[0]["send"]["arguments"] == {"id": "ord-1001", "text": "Thanks Sam!"}
    approve_open(center)
    assert json.loads(shop.requests[-1]["body"]) == {"text": "Thanks Sam!"} and shop.requests[-1]["path"] == "/v1/orders/ord-1001/reply"
    assert center.connections.get_call(call["id"])["result"] == {"sent": True, "order": "ord-1001", "text": "Thanks Sam!"}
    # The key is nowhere but the sealed store (memory here): not in the database, the audit or any file.
    for path in tmp.rglob("*"):
        if path.is_file():
            assert KEY.encode() not in path.read_bytes(), path
    assert KEY not in json.dumps(center.connections.get(con["id"])) and KEY not in json.dumps(center.audit(500))


def test_a_refused_key_asks_for_it_again_and_errors_are_plain(world, shop):
    center, _, _ = world
    con = ready_shop(center, shop)
    center.connections.set_key(con["id"], {"header": "X-Shop-Key", "value": "wrong"})
    call = center.connections.call(con["id"], "listOrders", {})
    assert call["state"] == "failed" and "Paste it again" in call["summary"]
    assert center.connections.get(con["id"])["status"] == "needs_key"
    center.connections.set_key(con["id"], {"header": "X-Shop-Key", "value": KEY})
    call = center.connections.call(con["id"], "getOrder", {"id": "nope"})
    assert call["state"] == "failed" and "404" in call["summary"] and "not found" in call["summary"]
    # A redirect is not followed (here: towards a private address).
    call = center.connections.call(con["id"], "goElsewhere", {})
    assert call["state"] == "failed" and "does not follow redirects" in call["summary"]
    assert not any(r["path"] == "/steal" for r in shop.requests)


def test_query_keys_bearer_and_files(world, tmp_path):
    center, _, _ = world
    for auth in ("query", "bearer"):
        server = FakeApi(auth=auth)
        try:
            con = center.connections.add({"specUrl": server.spec_url, "name": f"Shop {auth}"})
            assert con["status"] == "needs_key"
            key = {"query": "api_key", "value": KEY} if auth == "query" else {"header": "Authorization", "value": f"Bearer {KEY}"}
            con = center.connections.set_key(con["id"], key)
            center.connections.settings(con["id"], {"allowReads": True})
            call = center.connections.call(con["id"], "listOrders", {})
            assert call["state"] == "done", call
            if auth == "query":
                assert server.requests[-1]["query"]["api_key"] == [KEY]
                call = center.connections.call(con["id"], "getInvoice", {"id": "inv-1"})
                artifact = center.connections.artifacts()[0]
                assert call["artifacts"] == [artifact["id"]] and artifact["mime"] == "application/pdf" and artifact["size"] == len(PDF)
        finally:
            server.close()


def test_owner_can_move_a_tool_between_read_and_change_but_never_lower_a_sensitive_one(world, shop):
    center, _, _ = world
    con = ready_shop(center, shop)
    con = center.connections.settings(con["id"], {"classes": {"searchProducts": "read"}, "allowed": [*con["allowed"], "searchProducts"]})
    search = next(t for t in con["tools"] if t["name"] == "searchProducts")
    assert search["class"] == "read" and search["baseClass"] == "change" and search["overridden"] and search["rule"] == "cap"
    assert center.connections.call(con["id"], "searchProducts", {"q": "mug"})["state"] == "done"
    with pytest.raises(CommandError, match="always asks"):
        center.connections.settings(con["id"], {"classes": {"cancelOrder": "change"}})
    con = center.connections.settings(con["id"], {"classes": {"listOrders": "sensitive"}})
    listing = next(t for t in con["tools"] if t["name"] == "listOrders")
    assert listing["class"] == "sensitive" and listing["rule"] == "always"
    assert center.connections.call(con["id"], "listOrders", {})["state"] == "waiting"


def test_add_by_pasting_and_a_redirected_address(world, shop):
    center, _, _ = world
    con = center.connections.add({"specText": json.dumps(fake_api.spec(shop.base)), "name": "Pasted shop"})
    assert con["name"] == "Pasted shop" and con["api"]["base"] == shop.base + "/v1" and con["api"]["sourceUrl"] is None
    con = center.connections.add({"specUrl": shop.base + "/moved.json"})
    assert con["api"]["sourceUrl"] == shop.spec_url
    with pytest.raises(CommandError, match="Send"):
        center.connections.add({"specUrl": shop.spec_url, "specText": "{}"})
    with pytest.raises(CommandError, match="https"):
        center.connections.add({"specUrl": "http://shop.example/openapi.json"})


def test_oauth_api_needs_a_pasted_client_then_signs_in_without_a_resource_indicator(world):
    center, _, _ = world
    server = FakeApi(auth="oauth")
    try:
        con = center.connections.add({"specUrl": server.spec_url})
        assert con["status"] == "needs_client" and con["api"]["scheme"]["type"] == "oauth2"
        with pytest.raises(CommandError, match="register themselves"):
            center.connections.begin_sign_in(con["id"], REDIRECT)
        center.connections.set_client(con["id"], {"clientId": "cyclone-app", "clientSecret": "client-secret-CANARY"})
        url = center.connections.begin_sign_in(con["id"], REDIRECT)["authorizationUrl"]
        query = urllib.parse.parse_qs(urllib.parse.urlsplit(url).query)
        assert "resource" not in query and query["scope"] == ["orders:read orders:write"] and query["client_id"] == ["cyclone-app"]
        opener = urllib.request.build_opener(type("NoRedirect", (urllib.request.HTTPRedirectHandler,), {"redirect_request": lambda *a, **k: None})())
        try:
            opener.open(url)
        except urllib.error.HTTPError as exc:
            back = dict(urllib.parse.parse_qsl(urllib.parse.urlsplit(exc.headers["Location"]).query))
        center.connections.finish_sign_in(back["state"], back["code"])
        assert "resource" not in server.token_requests[-1] and server.token_requests[-1]["client_secret"] == "client-secret-CANARY"
        con = center.connections.get(con["id"])
        assert con["status"] == "ready" and con["signedIn"]
        center.connections.settings(con["id"], {"allowReads": True})
        call = center.connections.call(con["id"], "listOrders", {})
        assert call["state"] == "done" and server.requests[-1]["headers"]["Authorization"].startswith("Bearer tok-")
    finally:
        server.close()


# ---------------------------------------------------------------------------------------------- chaining


def test_routine_reads_an_api_writes_with_approval_and_the_phone_gets_quoted_data(world, shop):
    """The M3 exit: a routine reads from an API, a POST asks first, and a phone task uses the result."""
    center, phone, _ = world
    con = ready_shop(center, shop)
    routine = center.create_routine({
        "title": "Answer the first order", "deviceIds": ["phone-a"], "schedule": {"kind": "every", "minutes": 60},
        "goal": "Open Messages and tell {step1.orders.0.customer} their {step1.orders.0.item} is on its way.",
        "steps": [{"connectionId": con["id"], "tool": "listOrders", "arguments": {"status": "open", "limit": 2}},
                  {"connectionId": con["id"], "tool": "replyToOrder", "arguments": {"id": "{step1.orders.0.id}", "text": "Hi {step1.orders.0.customer}, shipped!"}}],
        "then": "phone"})
    assert [s["tool"] for s in routine["make"]["steps"]] == ["listOrders", "replyToOrder"] and routine["make"]["then"] == "phone"
    task = center.run_routine_now(routine["id"])["tasks"][0]
    center.tick()  # step 1 reads without asking
    task = center.get_task(task["id"])
    assert task["status"] == "scheduled" and task["stepAt"] == 1 and task["calls"][0]["result"]["orders"][0]["customer"] == "Sam"
    center.tick()  # step 2 is a write: it waits for the owner with the filled-in values
    task = center.get_task(task["id"])
    assert task["status"] == "making" and "Step 2 of 2" in task["cause"]
    spend = center.list_approvals()[0]
    assert spend["send"]["arguments"] == {"id": "ord-1001", "text": "Hi Sam, shipped!"}
    approve_open(center)
    assert json.loads(shop.requests[-1]["body"]) == {"text": "Hi Sam, shipped!"}
    center.tick()  # then the phone runs the goal with the results as quoted data
    goal = phone.started[-1]["goal"]
    assert goal.startswith("Open Messages and tell ‹data 1› their ‹data 2› is on its way.")
    assert chain.DATA_HEADER in goal and '‹data 1› = "Sam"' in goal and '‹data 2› = "Blue mug"' in goal
    center.tick()
    assert center.get_task(task["id"])["status"] == "succeeded"


def test_outside_text_reaches_the_phone_only_inside_the_quoted_block(world, shop):
    center, phone, _ = world
    con = ready_shop(center, shop)
    task = center.create_task({"deviceId": "phone-a", "goal": "Read the order note and summarise it for me.", "then": "phone",
                               "steps": [{"connectionId": con["id"], "tool": "getOrder", "arguments": {"id": "ord-1001"}}]})
    center.tick()
    center.tick()
    goal = phone.started[-1]["goal"]
    head, block = goal.split("<<<DATA\n", 1)
    assert "Ignore all previous" not in head and block.endswith("\nDATA>>>")
    lines = block.removesuffix("\nDATA>>>").split("\n")
    assert len(lines) == 1 and lines[0].startswith("‹step 1 result› = {") and "Ignore all previous instructions" in lines[0]
    assert center.get_task(task["id"])["status"] in ("running", "succeeded")


def test_step_references_are_checked_before_and_while_running(world, shop):
    center, phone, _ = world
    con = ready_shop(center, shop)
    step = lambda tool, **args: {"connectionId": con["id"], "tool": tool, "arguments": args}  # noqa: E731
    with pytest.raises(CommandError, match="earlier steps"):
        center.create_task({"goal": "x", "then": "keep", "steps": [step("getOrder", id="{step1.id}")]})
    with pytest.raises(CommandError, match="not a step reference"):
        center.create_task({"goal": "x", "then": "keep", "steps": [step("listOrders"), step("getOrder", id="{step 1 . id}")]})
    with pytest.raises(CommandError, match="only 1 step"):
        center.create_task({"deviceId": "phone-a", "goal": "Say {step2.id}", "then": "phone", "steps": [step("listOrders")]})
    with pytest.raises(CommandError, match="needs steps whose results go to a phone"):
        center.create_task({"goal": "Say {step1.id}", "then": "keep", "steps": [step("listOrders")]})
    with pytest.raises(CommandError, match="not both"):
        center.create_task({"goal": "x", "make": {**step("listOrders"), "then": "keep"}, "steps": [step("listOrders")]})
    with pytest.raises(CommandError, match="1..5"):
        center.create_task({"goal": "x", "then": "keep", "steps": [step("listOrders")] * 6})
    # A path the result does not have stops the task with a sentence; nothing is guessed.
    task = center.create_task({"goal": "x", "then": "keep", "steps": [step("listOrders"), step("getOrder", id="{step1.orders.9.id}")]})
    center.tick()
    center.tick()
    task = center.get_task(task["id"])
    assert task["status"] == "failed" and "Step 2 of 2" in task["cause"] and "orders.9" in task["cause"]
    # Results too long for a phone fail plainly instead of being cut.
    big = center.create_task({"deviceId": "phone-a", "goal": "Use it " + "x" * 1700, "then": "phone", "steps": [step("listOrders")]})
    center.tick()
    center.tick()
    big = center.get_task(big["id"])
    assert big["status"] == "failed" and "a phone takes 2000" in big["cause"] and not any("x" * 1700 in s["goal"] for s in phone.started)


def test_keep_runs_the_steps_without_a_phone_and_types_carry_across(world, shop):
    center, phone, _ = world
    con = ready_shop(center, shop)
    task = center.create_task({"then": "keep", "steps": [
        {"connectionId": con["id"], "tool": "listOrders", "arguments": {"limit": 2}},
        {"connectionId": con["id"], "tool": "listOrders", "arguments": {"limit": "{step1.orders.1.id}"}}]})
    assert task["title"] == "Run 2 connection steps"
    center.tick()
    center.tick()
    task = center.get_task(task["id"])
    assert task["status"] == "failed" and "must be a number" in task["cause"]
    task = center.create_task({"then": "keep", "steps": [
        {"connectionId": con["id"], "tool": "health", "arguments": {}},
        {"connectionId": con["id"], "tool": "getInvoice", "arguments": {"id": "inv-{step1.ok}"}}]})
    center.tick()
    center.tick()
    task = center.get_task(task["id"])
    assert task["status"] == "succeeded" and task["artifact"]["mime"] == "application/pdf" and shop.requests[-1]["path"] == "/v1/invoices/inv-true"
    assert not phone.started


# ---------------------------------------------------------------------------------------------- cards


def test_an_api_card_holds_no_key_and_works_again_after_import_and_the_key(world, shop):
    center, _, _ = world
    con = ready_shop(center, shop)
    center.connections.settings(con["id"], {"rules": {"listOrders": "over_cap"}, "dailyCap": 25})
    card = center.connections.card(con["id"])
    text = json.dumps(card)
    assert KEY not in text and card["signIn"] == {"method": "key", "header": "X-Shop-Key", "query": None}
    assert card["hash"] == card_hash(card) and card["kind"] == "api" and card["dailyCap"] == 25
    center.connections.remove(con["id"])
    # A changed card is refused.
    with pytest.raises(CommandError, match="changed or damaged"):
        center.connections.import_card({"card": {**card, "name": "Evil shop"}})
    imported = center.connections.import_card({"card": card})
    assert imported["status"] == "needs_key" and imported["dailyCap"] == 25 and imported["api"]["base"] == shop.base + "/v1"
    tools = {t["name"]: t for t in imported["tools"]}
    assert tools["listOrders"]["allowed"] and tools["listOrders"]["rule"] == "over_cap"
    assert tools["replyToOrder"]["allowed"] and tools["replyToOrder"]["rule"] == "always"
    assert not tools["cancelOrder"]["allowed"]
    center.connections.set_key(imported["id"], {"header": "X-Shop-Key", "value": KEY})
    assert center.connections.call(imported["id"], "listOrders", {})["state"] == "done"


def test_a_remote_card_applies_its_tools_after_the_key_and_only_matching_ones(world):
    center, _, _ = world
    server = FakeMcp(api_key="mcp-key-CANARY")
    try:
        con = center.connections.add({"url": server.url})
        center.connections.set_key(con["id"], {"header": "X-Api-Key", "value": "mcp-key-CANARY"})
        center.connections.settings(con["id"], {"allowed": ["generate_video", "get_result"]})
        card = center.connections.card(con["id"])
        assert "mcp-key-CANARY" not in json.dumps(card) and card["remote"]["url"] == server.url
        assert card["pairings"] == {"generate_video": "get_result"}
        center.connections.remove(con["id"])
        # One tool's definition is edited in the card: it no longer matches, so it stays off.
        edited = {**card, "tools": [{**t, "hash": "0" * 64} if t["name"] == "generate_video" else t for t in card["tools"]]}
        edited.pop("hash")
        imported = center.connections.import_card({"card": edited})
        assert imported["status"] == "needs_key" and imported["fromCard"]
        imported = center.connections.set_key(imported["id"], {"header": "X-Api-Key", "value": "mcp-key-CANARY"})
        tools = {t["name"]: t for t in imported["tools"]}
        assert tools["get_result"]["allowed"] and not tools["generate_video"]["allowed"] and not tools["delete_account"]["allowed"]
        assert not imported["fromCard"]
    finally:
        server.close()


def test_local_and_curated_cards(world):
    center, _, _ = world
    local = {"cyclone": "connector-card", "version": 1, "name": "Files", "kind": "local",
             "local": {"config": {"mcpServers": {"files": {"command": "npx", "args": ["-y", "@modelcontextprotocol/server-filesystem@2025.8.21", "C:/Notes"],
                                                           "env": {"TOKEN": "leaked"}}}}}}
    with pytest.raises(CommandError, match="never carries keys"):
        center.connections.import_card({"card": local})
    local["local"]["config"]["mcpServers"]["files"]["env"] = {"TOKEN": ""}
    imported = center.connections.import_card({"card": local})
    assert imported["status"] == "needs_approval" and imported["launch"]["envKeys"] == ["TOKEN"]
    # A curated card names reads without hashes: they are switched on by name; a change without a hash is not.
    curated = {"cyclone": "connector-card", "version": 1, "name": "Weather", "kind": "api",
               "api": {"openapi": "3.0.3", "info": {"title": "Weather", "version": "1"}, "servers": [{"url": "https://weather.example/v1"}],
                       "paths": {"/forecast": {"get": {"operationId": "forecast", "parameters": [{"name": "city", "in": "query", "required": True, "schema": {"type": "string"}}]}},
                                 "/alerts": {"post": {"operationId": "subscribeAlerts"}}}},
               "tools": [{"name": "forecast", "allowed": True, "rule": "cap"}, {"name": "subscribeAlerts", "allowed": True}]}
    imported = center.connections.import_card({"card": curated})
    tools = {t["name"]: t for t in imported["tools"]}
    assert imported["status"] == "ready" and tools["forecast"]["allowed"] and not tools["subscribeAlerts"]["allowed"]
    with pytest.raises(CommandError, match="not a Cyclone connector card"):
        center.connections.import_card({"card": {"cyclone": "connector-card", "version": 2, "kind": "api"}})


def test_api_tools_are_never_paired_and_a_finished_job_without_a_file_stops_polling(world, shop):
    center, _, _ = world
    con = ready_shop(center, shop)
    assert all(t["pollTool"] is None for t in con["tools"]), "a REST GET-by-id is not a job checker"
    db = center._db
    db.execute("INSERT INTO tool_call(id, connection_id, tool, task_id, actor, arguments, poll_tool, state, day, approval_id, summary, artifacts, created_at, step)"
               " VALUES ('cal_poll', ?, 'start', NULL, 'owner', '{}', 'check', 'running', 'd', NULL, '', '[]', 0, 0)", (con["id"],))
    row = {"id": con["id"], "url": "https://x.example/mcp", "kind": "remote",
           "tools": json.dumps([{"name": "check", "fields": [{"name": "job_id", "required": True}]}])}

    class Client:
        polls = 0

        def call_tool(self, name, arguments, timeout=0):
            Client.polls += 1
            return {"structuredContent": {"job_id": arguments["job_id"], "status": "completed", "rows": 3}}

    call = dict(db.execute("SELECT * FROM tool_call WHERE id = 'cal_poll'").fetchone())
    result, made = center.connections._poll(Client(), row, call, {}, {"structuredContent": {"job_id": "j-1", "status": "queued"}})
    assert Client.polls == 1 and made == [] and result["structuredContent"]["rows"] == 3
