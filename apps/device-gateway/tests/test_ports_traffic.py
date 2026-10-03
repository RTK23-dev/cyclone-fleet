"""Plan 48 run 3: live traffic through the gateway's Port Hub, end to end over real HTTP: the kit's example plugins
receive the run's messages, answer waiting runs through /v1/ports/{run}/{port}/deliver, and fetch one-time artifacts."""
from __future__ import annotations

import importlib.util
import json
import socket
import sys
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

import pytest
import uvicorn
from fastapi import FastAPI

from cyclone_device_gateway.ports.api import create_ports_router
from cyclone_device_gateway.ports.hub import PortHub
from cyclone_ports import PluginServer, deliver
from cyclone_ports.sdk import parse_secret

KIT = Path(__file__).resolve().parents[3] / "tools" / "cyclone-ports-sdk"
TOKEN = "t" * 32
CODE = "482913"


def load_example(name: str):
    spec = importlib.util.spec_from_file_location(f"traffic_example_{name}", KIT / "examples" / name / "plugin.py")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class Gateway:
    """The ports router on a real loopback server, so plugins can deliver back to it."""

    def __init__(self, root: Path) -> None:
        self.port = free_port()
        self.hub = PortHub(root, base_url=f"http://127.0.0.1:{self.port}")
        app = FastAPI()
        app.include_router(create_ports_router(type("R", (), {"ports": self.hub})(), TOKEN))
        self.server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=self.port, log_level="error"))
        self.thread = threading.Thread(target=self.server.run, daemon=True)
        self.thread.start()
        deadline = time.time() + 10
        while not self.server.started and time.time() < deadline:
            time.sleep(0.02)

    def add(self, server: PluginServer, allowed: list[str] | None = None) -> None:
        """Adds a plugin like the owner: preview, add, give it its key, run the checks."""
        preview = self.hub.preview(server.url)
        added = self.hub.add(server.url, allowed or [s["port"] for s in preview["serves"]])
        kid, secret = parse_secret(added["key"]["value"])
        server.keys = {kid: secret}
        assert self.hub.check(server.manifest["name"])["plugin"]["status"] == "active"

    def call(self, method: str, path: str, body=None, auth=True):
        headers = {"Content-Type": "application/json"}
        if auth:
            headers["Authorization"] = f"Bearer {TOKEN}"
        request = urllib.request.Request(f"http://127.0.0.1:{self.port}{path}", method=method, headers=headers,
                                         data=None if body is None else json.dumps(body).encode())
        try:
            with urllib.request.urlopen(request, timeout=40) as response:
                return response.status, json.loads(response.read() or b"{}")
        except urllib.error.HTTPError as error:
            return error.code, json.loads(error.read() or b"{}")

    def stop(self) -> None:
        self.hub.traffic.stop()
        self.server.should_exit = True
        self.thread.join(timeout=5)
        self.hub.store.close()


@pytest.fixture
def gateway(tmp_path):
    g = Gateway(tmp_path / "ports")
    yield g
    g.stop()


def post_sms(url: str, text: str) -> None:
    request = urllib.request.Request(f"{url}/sms", method="POST", data=json.dumps({"from": "Example", "text": text}).encode(),
                                     headers={"Content-Type": "application/json", "X-Forwarder-Token": "fwd"})
    urllib.request.urlopen(request, timeout=5).read()


def test_signup_scenario_through_the_gateway_hub(gateway, tmp_path):
    logger = load_example("logger").build("k9.x", tmp_path / "logger").start()
    sms = load_example("sms_plugin").build("k9.x", "my-second-phone", "fwd").start()
    try:
        gateway.add(logger)
        gateway.add(sms)
        meta = {"taskId": "task_1", "rowId": "row_1", "app": "com.example.app"}
        status, sent = gateway.call("POST", "/v1/ports/runs/run_signup1/emit", {"port": "run.event", "data": {"stage": "started"}, "meta": meta})
        assert status == 200 and sent["sentTo"] == ["run-logger"]
        status, sent = gateway.call("POST", "/v1/ports/runs/run_signup1/emit", {
            "port": "screen.shot", "meta": meta, "pageKey": "signup:birthday",
            "file": {"base64": "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==", "mime": "image/png"}})
        assert status == 200
        status, refused = gateway.call("POST", "/v1/ports/runs/run_signup1/emit",
                                       {"port": "account.fields", "data": {"fields": {"name": "Sam", "password": "x"}}})
        assert status == 400 and "password" in refused["detail"]["message"]
        gateway.hub.traffic.flush()
        lines = [json.loads(l) for l in (tmp_path / "logger" / "runs.jsonl").read_text().splitlines()]
        lines = [l for l in lines if l["runId"] == "run_signup1"]  # adding it ran the checks, which send samples too
        assert [l["port"] for l in lines] == ["run.event", "screen.shot"]
        assert lines[1]["savedAs"] and lines[1]["pageKey"] == "signup:birthday"

        status, asked = gateway.call("POST", "/v1/ports/runs/run_signup1/await",
                                     {"port": "code.in", "match": {"from": "Example", "pattern": r"\b\d{6}\b"}, "timeoutS": 60, "meta": meta})
        assert status == 200 and asked["state"] == "waiting" and asked["plugin"] == "sms-codes"
        gateway.hub.traffic.flush()
        post_sms(sms.url, f"Your Example code is {CODE}")
        status, result = gateway.call("GET", f"/v1/ports/waits/{asked['awaitId']}?waitS=10")
        assert result["state"] == "delivered" and result["codeLength"] == 6 and result["source"] == "my-second-phone"
        assert CODE not in json.dumps(result), "the run is told a code came, never the code"
        assert gateway.hub.traffic.take_code(asked["awaitId"]) == CODE
        assert gateway.hub.traffic.take_code(asked["awaitId"]) is None, "the code is taken once"
        for path in tmp_path.rglob("*"):
            if path.is_file():
                assert CODE.encode() not in path.read_bytes(), path

        status, run = gateway.call("GET", "/v1/ports/runs/run_signup1")
        kinds = [(a["kind"], a["port"]) for a in run["activity"]]
        assert ("deliver", "code.in") in kinds and ("emit", "screen.shot") in kinds
        assert CODE not in json.dumps(run)
        status, runs = gateway.call("GET", "/v1/ports/runs")
        assert runs["runs"][0]["runId"] == "run_signup1"
    finally:
        logger.stop()
        sms.stop()


def test_image_from_the_pc_lands_in_the_run_folder(gateway, tmp_path):
    folder = tmp_path / "pictures"
    folder.mkdir()
    (folder / "avatar.png").write_bytes(b"\x89PNG fake image")
    images = load_example("pc_images").build("k9.x", folder).start()
    try:
        gateway.add(images)
        asked = gateway.hub.traffic.wait("run_image1", "file.in", {"kind": "image"}, 30)
        result = gateway.hub.traffic.result(asked["awaitId"], wait_s=10)
        assert result["state"] == "delivered" and result["name"] == "avatar.png"
        assert (tmp_path / "ports" / "runs" / "run_image1" / "files" / "avatar.png").read_bytes() == b"\x89PNG fake image"
    finally:
        images.stop()


@pytest.fixture
def quiet(gateway):
    """A plugin that takes waits and remembers them, and lets the test deliver by hand."""
    asked, cancels = [], []
    server = PluginServer({"contract": "cyclone.ports/1", "name": "values", "version": "1.0.0", "endpoint": "http://127.0.0.1:1",
                           "serves": [{"port": "value.in", "way": "in"}, {"port": "log.line", "way": "out"}],
                           "needs": {"personal": True}}, "k9.x")
    server.on_await = lambda port, request: asked.append(request)
    server.on_cancel = lambda port, request: cancels.append(request["awaitId"])
    server.start()
    gateway.add(server)
    yield server, asked, cancels
    server.stop()


def test_deliveries_follow_the_contract(gateway, quiet):
    server, asked, _ = quiet
    url = lambda run: f"http://127.0.0.1:{gateway.port}/v1/ports/{run}/value.in/deliver"  # noqa: E731
    assert deliver(url("run_nobody"), "x", {"v": 1, "value": 1}, attempts=1)[0] == 404
    wait = gateway.hub.traffic.wait("run_val1", "value.in", {"ask": "a caption"}, 30)
    gateway.hub.traffic.flush()
    request = asked[-1]
    assert request["awaitId"] == wait["awaitId"] and request["deliverUrl"] == url("run_val1")
    assert deliver(url("run_val1"), "wrong", {"v": 1, "value": 1}, attempts=1)[0] == 401
    assert deliver(url("run_val1"), request["token"], {"v": 1}, attempts=1)[0] == 422
    body = {"v": 1, "value": "Sunset run", "deliveryId": "dl_00000001"}
    assert deliver(url("run_val1"), request["token"], body, attempts=1)[0] == 200
    status, again = deliver(url("run_val1"), request["token"], body, attempts=1)
    assert status == 200 and again["duplicate"] is True
    assert deliver(url("run_val1"), request["token"], dict(body, deliveryId="dl_00000002"), attempts=1)[0] == 409
    assert gateway.hub.traffic.result(wait["awaitId"])["value"] == "Sunset run"
    assert "Sunset run" not in (gateway.hub.store._db.execute("SELECT result FROM wait").fetchone()[0])


def test_waits_time_out_and_survive_a_restart(gateway, quiet, tmp_path):
    server, asked, cancels = quiet
    wait = gateway.hub.traffic.wait("run_slow1", "value.in", {}, 30)
    gateway.hub.traffic.flush()
    first = asked[-1]

    # the gateway restarts: a new hub over the same files asks again with the same awaitId and token
    reborn = PortHub(tmp_path / "ports", base_url=f"http://127.0.0.1:{gateway.port}")
    reborn.traffic.resume()
    reborn.traffic.flush()
    assert asked[-1]["awaitId"] == first["awaitId"] and asked[-1]["token"] == first["token"]
    assert any(a["kind"] == "await" and "after a restart" in a["detail"] for a in reborn.store.activity(20))

    # time runs out: the run is told, and the plugin gets a cancel
    gateway.hub.store._db.execute("UPDATE wait SET timeout_at = 1 WHERE await_id = ?", (wait["awaitId"],))
    gateway.hub.store._db.commit()
    gateway.hub.traffic.tick()
    gateway.hub.traffic.flush()
    assert gateway.hub.traffic.result(wait["awaitId"])["state"] == "timed_out"
    deadline = time.time() + 5
    while wait["awaitId"] not in cancels and time.time() < deadline:
        time.sleep(0.05)
    assert wait["awaitId"] in cancels
    url = f"http://127.0.0.1:{gateway.port}/v1/ports/run_slow1/value.in/deliver"
    assert deliver(url, first["token"], {"v": 1, "value": "late"}, attempts=1)[0] == 410
    reborn.traffic.stop()


def test_routes_say_why_a_port_has_no_plugin(gateway, quiet):
    assert gateway.hub.traffic.wait("run_x1", "code.in", {}, 30)["state"] == "empty"
    gateway.hub.set_binding("default", "value.in", [])
    assert gateway.hub.traffic.wait("run_x1", "value.in", {}, 30)["state"] == "off"
    status, error = gateway.call("POST", "/v1/ports/runs/run_x1/emit", {"port": "value.in"})
    assert status == 400 and "wait on it" in error["detail"]["message"]
    assert gateway.call("POST", "/v1/ports/runs/run_x1/emit", {"port": "log.line"}, auth=False)[0] == 401


def test_owner_test_runs(gateway, quiet):
    server, asked, _ = quiet
    status, run = gateway.call("POST", "/v1/ports/test-runs", {"scenario": "value"})
    assert status == 200 and run["runId"].startswith("run_test_")
    deadline = time.time() + 10
    mine = lambda: [a for a in asked if a["runId"] == run["runId"]]  # noqa: E731 - the checks asked once too
    while not mine() and time.time() < deadline:
        time.sleep(0.05)
    request = mine()[-1]
    assert deliver(request["deliverUrl"], request["token"], {"v": 1, "value": "A caption"}, attempts=1)[0] == 200
    while time.time() < deadline:
        run = gateway.call("GET", f"/v1/ports/test-runs/{run['runId']}")[1]
        if run["state"] != "running":
            break
        time.sleep(0.1)
    assert run["state"] == "done", run
    assert [s["state"] for s in run["steps"]] == ["skipped", "ok", "skipped"]  # no plugin serves run.event here
    assert "a value" in run["steps"][1]["detail"]
    assert gateway.call("POST", "/v1/ports/test-runs", {"scenario": "nope"})[0] == 400
