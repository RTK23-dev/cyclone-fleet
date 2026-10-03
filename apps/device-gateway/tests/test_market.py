from __future__ import annotations

import json

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from cyclone_device_gateway.cyclone_bridge.protocol import ALLOWED_OPS
from cyclone_device_gateway.desktop_runtime.models import DesktopRuntimeError
from cyclone_device_gateway.desktop_runtime.v5_contract import V5ContractService
from cyclone_device_gateway.market.api import create_market_router
from cyclone_device_gateway.market.pc_connections import HOSTS, PcConnections

LISTING = {
    "id": "cyclone.focus-timer", "kind": "recipe", "version": "1.0.0", "name": "Focus timer",
    "publisher": {"id": "cyclone", "name": "Cyclone", "verified": True}, "summary": "A timer plus Do Not Disturb.",
    "category": "Productivity", "glyph": "⏱", "goal": "Set a timer for {minutes} minutes and turn on Do Not Disturb.",
    "inputs": [{"name": "minutes", "label": "Minutes", "kind": "number", "default": "25", "choices": [], "required": True}],
    "apps": ["com.google.android.deskclock"], "does": ["Uses the Clock app"], "asksFirst": [],
    "suggestFor": ["com.google.android.deskclock"], "featured": True, "added": True, "savedInputs": {"minutes": "10"},
    "runs": 2, "lastRunAt": 1,
}
CATALOG = {"listings": [LISTING], "suggestions": [{"id": "cyclone.focus-timer", "reason": "Because you use Clock"}], "installedCount": 1,
           "connections": [{"id": "openrouter", "name": "OpenRouter", "glyph": "✦", "state": "connected", "detail": "Thinking with x/y", "where": "phone"}]}


class Bridge:
    def __init__(self, responses):
        self.responses = responses
        self.calls = []

    def request(self, op, args, request_id=None):
        self.calls.append((op, dict(args)))
        response = self.responses[op]
        return response(args) if callable(response) else response


class Session:
    credential = "paired"

    def __init__(self, bridge):
        self._bridge = bridge

    def bridge(self):
        return self._bridge


class Fleet:
    def __init__(self, bridge):
        self.session = Session(bridge)

    def get(self, device_id):
        return self.session


def service(responses):
    bridge = Bridge(responses)
    return V5ContractService(Fleet(bridge)), bridge


def test_market_ops_are_registered_without_forbidden_words():
    assert {"market.catalog", "market.install", "market.remove", "market.run"} <= ALLOWED_OPS
    forbidden = ("shell", "powershell", "root", "su", "command", "script", "adb")
    assert not any(word in op for op in ("market.catalog", "market.install", "market.remove", "market.run") for word in forbidden)


def test_the_catalog_is_validated_field_by_field():
    svc, _ = service({"market.catalog": CATALOG})
    assert svc.market_catalog("phone-1")["installedCount"] == 1
    for broken in (
        {**CATALOG, "extra": 1},
        {**CATALOG, "listings": [{**LISTING, "command": "rm"}]},
        {**CATALOG, "listings": [{**LISTING, "id": "../x"}]},
        {**CATALOG, "listings": [{**LISTING, "summary": "x" * 500}]},
        {**CATALOG, "listings": [{**LISTING, "savedInputs": {"note": "password: hunter2"}}]},
        {**CATALOG, "connections": [{**CATALOG["connections"][0], "state": "owned"}]},
        {**CATALOG, "connections": [{**CATALOG["connections"][0], "apiKey": "sk-or-x"}]},
    ):
        bad, _ = service({"market.catalog": broken})
        with pytest.raises(DesktopRuntimeError):
            bad.market_catalog("phone-1")


def test_changes_are_typed_and_never_carry_secrets():
    svc, bridge = service({
        "market.install": lambda a: {"id": a["id"], "added": True, "inputs": a.get("inputs", {})},
        "market.remove": lambda a: {"id": a["id"], "removed": True},
        "market.run": lambda a: {"id": a["id"], "started": True},
    })
    assert svc.market_change("phone-1", "market.install", "cyclone.focus-timer", {"minutes": "10"})["added"] is True
    assert svc.market_change("phone-1", "market.run", "cyclone.focus-timer")["started"] is True
    assert svc.market_change("phone-1", "market.remove", "cyclone.focus-timer")["removed"] is True
    for bad in (
        lambda: svc.market_change("phone-1", "market.delete", "cyclone.focus-timer"),
        lambda: svc.market_change("phone-1", "market.run", "../etc"),
        lambda: svc.market_change("phone-1", "market.install", "cyclone.send-whatsapp", {"message": "password: hunter2"}),
        lambda: svc.market_change("phone-1", "market.install", "cyclone.send-whatsapp", {"token": "x"}),
        lambda: svc.market_change("phone-1", "market.install", "cyclone.send-whatsapp", {"message": "x" * 500}),
    ):
        with pytest.raises(DesktopRuntimeError):
            bad()
    assert [op for op, _ in bridge.calls] == ["market.install", "market.run", "market.remove"]


class FakeRunner:
    def __init__(self, status=None, code=0):
        self.calls = []
        self.status = status
        self.code = code

    def __call__(self, argv, timeout):
        self.calls.append(list(argv))
        if argv[1] == "status":
            return self.code, json.dumps(self.status)
        if argv[1] == "copy-config":
            return 0, '{"mcpServers": {"cyclone-phone": {}}}'
        return 0, json.dumps({"message": "Codex is connected to Cyclone.", "verification": {"ok": True}})


def test_pc_connections_map_the_connector_and_only_run_fixed_commands():
    runner = FakeRunner({"generic_mcp": "READY", "ai": {"adapters": {
        "codex": {"state": "CONNECTED", "detected": True, "configured": True},
        "grok": {"state": "DETECTED", "detected": True, "configured": False},
    }}})
    pc = PcConnections(command=lambda: ["CycloneAgentMCP.exe"], run=runner)
    status = pc.status()
    by_id = {c["id"]: c for c in status["connections"]}
    assert status["available"] and by_id["codex"]["state"] == "connected" and by_id["grok"]["state"] == "detected"
    assert by_id["generic"]["state"] == "ready" and set(by_id) == set(HOSTS)
    assert pc.connect("codex")["ok"] is True
    assert pc.connect("generic")["config"].startswith("{")
    assert runner.calls == [["CycloneAgentMCP.exe", "status", "--probe-gateway"], ["CycloneAgentMCP.exe", "connect", "codex", "--verify"],
                            ["CycloneAgentMCP.exe", "copy-config", "generic"]]
    with pytest.raises(ValueError):
        pc.connect("codex; del C:\\")
    missing = PcConnections(command=lambda: None, run=runner)
    assert missing.status()["available"] is False
    with pytest.raises(RuntimeError):
        missing.connect("codex")
    assert PcConnections(command=lambda: ["x"], run=FakeRunner(code=1)).status()["available"] is False


class Runtime:
    def __init__(self, fleet):
        self.fleet = fleet


def test_routes_need_the_bearer_forward_to_the_phone_and_keep_refusals():
    bridge = Bridge({"market.catalog": CATALOG, "market.run": lambda a: {"id": a["id"], "started": True}})
    app = FastAPI()
    app.include_router(create_market_router(Runtime(Fleet(bridge)), "tok", PcConnections(command=lambda: None)))
    client = TestClient(app)
    assert client.get("/v1/devices/d1/market").status_code in {401, 403}
    headers = {"Authorization": "Bearer tok"}
    assert client.get("/v1/devices/d1/market", headers=headers).json()["installedCount"] == 1
    assert client.post("/v1/devices/d1/market/cyclone.focus-timer/run", headers=headers, json={"inputs": {"minutes": "5"}}).json()["started"]
    assert client.post("/v1/devices/d1/market/cyclone.focus-timer/run", headers=headers, json={"shell": "id"}).status_code == 422
    assert client.post("/v1/devices/d1/market/..%2Fx/run", headers=headers, json={}).status_code in {400, 404}
    assert client.get("/v1/pc/connections", headers=headers).json()["available"] is False
    assert client.post("/v1/pc/connections/evil/connect", headers=headers).status_code == 404
