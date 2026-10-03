"""MRZ discovery: offline recovery, durable association, owner approvals and constrained outputs."""
import json
import threading
from pathlib import Path
from types import SimpleNamespace

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from cyclone_device_gateway.command.api import create_command_router
from cyclone_device_gateway.command.center import CommandCenter, CommandError
from cyclone_device_gateway.command.mcp import McpError, Response
from cyclone_device_gateway.command.mrz import DEFAULT_API, local_base, recipe


def health(**changes):
    return {"mode": "local", "ok": True, "dryRun": False, "worker": {"online": True},
            "photoshop": {"found": True}, "template": {"present": True}, **changes}


@pytest.fixture
def world(tmp_path):
    script = tmp_path / "server.js"
    script.write_text("// fixture; discovery must not execute this file")
    data = {"version": 1, "studio": {"api_base": DEFAULT_API, "ui_base": "http://localhost:5173"},
            "connectors": [{"id": "employee-id", "enabled": True, "glass_kind": "local_stdio", "command": "node", "args": [str(script)]}]}
    file = tmp_path / "settings.json"
    file.write_text(json.dumps(data))
    calls = []
    responses = {"health": health(), "settings": data, "offline": False}

    def send(method, url, **kwargs):
        calls.append((method, url, kwargs))
        assert method == "GET"
        assert url.startswith("http://127.0.0.1:")
        if responses["offline"]:
            raise McpError("Unavailable")
        body = responses["settings"] if url.endswith("/api/mcp-connections") else responses["health"]
        return Response(200, {"content-type": "application/json"}, json.dumps(body).encode())

    def make():
        return CommandCenter(tmp_path / "cc.db", SimpleNamespace(), lambda: [], connections={"mrz_settings": file, "mrz_send": send})

    center = make()
    yield SimpleNamespace(center=center, mrz=center.connections.mrz, data=data, file=file, calls=calls, responses=responses, make=make)
    center.stop()


def test_discovery_reads_only_and_distinguishes_worker_and_dry_run(world):
    status = world.mrz.scan()
    assert status["state"] == "found" and status["ready"]
    assert status["uiBase"] == "http://127.0.0.1:5173"
    assert world.center.connections.list() == []
    assert world.center.connections.calls() == []
    world.responses["health"] = health(worker={"online": False})
    assert not world.mrz.scan()["ready"]
    world.responses["health"] = health(dryRun=True, photoshop={"found": False})
    assert "placeholder" in world.mrz.scan()["detail"]
    assert world.mrz.snapshot()["ready"]
    assert all(c[2]["timeout"] == 2 for c in world.calls)


def test_offline_to_online_and_missing_file_http_fallback(world):
    world.file.unlink()
    world.responses["offline"] = True
    assert world.mrz.scan()["state"] == "offline"
    world.responses["offline"] = False
    assert world.mrz.scan()["state"] == "found"
    assert any(c[1].endswith("/api/mcp-connections") for c in world.calls)


def test_connect_is_idempotent_retains_approval_boundary_and_adopts_paste(world):
    world.mrz.scan()
    pasted = world.center.connections.add({"config": world.mrz.snapshot()["recipe"]})
    assert world.mrz.snapshot()["connectionId"] == pasted["id"]
    result = world.mrz.connect()
    assert result["id"] == pasted["id"]
    assert result["status"] == "needs_approval"
    assert result["allowed"] == [] and not result["running"]
    assert world.mrz.connect()["id"] == result["id"]
    assert world.center.connections.add({"config": world.mrz.snapshot()["recipe"]})["id"] == result["id"]
    assert len(world.center.connections.list()) == 1


def test_paste_before_discovery_is_adopted_without_execution(world):
    pasted = world.center.connections.add({"config": recipe(world.data)[2]["config"]})
    assert world.mrz.snapshot()["connectionId"] is None
    assert world.mrz.scan()["connectionId"] == pasted["id"]
    assert pasted["status"] == "needs_approval" and not pasted["running"]
    assert world.center.connections.calls() == []


def test_similar_program_with_different_args_is_not_adopted(world):
    config = recipe(world.data)[2]["config"]
    config["mcpServers"]["employee-id"]["args"].append("--another-service")
    pasted = world.center.connections.add({"config": config})
    assert world.mrz.scan()["connectionId"] is None
    assert world.mrz.connect()["id"] != pasted["id"]


def test_link_survives_runtime_update_and_removal_requires_new_owner_connect(world):
    first = world.mrz.connect()
    second = world.make()
    try:
        assert second.connections.mrz.scan()["connectionId"] == first["id"]
        assert second.connections.mrz.connect()["id"] == first["id"]
        second.connections.remove(first["id"])
        second.connections.mrz.scan()
        assert second.connections.mrz.snapshot()["connectionId"] is None
        assert second.connections.list() == []
    finally:
        second.stop()


def test_changed_recipe_disabled_and_corrupt_file_never_replace_permissions(world):
    first = world.mrz.connect()
    world.data["connectors"][0]["args"].append("--changed")
    world.file.write_text(json.dumps(world.data))
    assert world.mrz.scan()["settingsChanged"]
    with pytest.raises(CommandError, match="recipe changed"):
        world.mrz.connect()
    assert world.center.connections.get(first["id"])["launch"]["args"] != world.data["connectors"][0]["args"]
    world.data["connectors"][0]["enabled"] = False
    world.file.write_text(json.dumps(world.data))
    assert "disabled" in world.mrz.scan()["detail"]
    world.file.write_text("{broken")
    assert world.mrz.scan()["state"] == "attention"
    assert world.file.read_text() == "{broken"


@pytest.mark.parametrize("url", ["https://example.org", "http://192.168.1.2:8787", "http://127.0.0.1.evil.test", "file:///tmp/x", "http://user:pass@localhost:8787", "http://localhost:8787?secret=x", "http://localhost:8787/api", "http://localhost:99999"])
def test_nonlocal_or_ambiguous_origins_are_rejected(url):
    with pytest.raises(ValueError):
        local_base(url)


def test_settings_cannot_redirect_discovery_off_pc(world):
    world.data["studio"]["api_base"] = "http://192.168.1.1"
    world.file.write_text(json.dumps(world.data))
    assert world.mrz.scan()["state"] == "attention"
    assert world.calls == []


def test_only_linked_studio_job_artifact_routes_are_allowed(world):
    connected = world.mrz.connect()
    allow = lambda url: world.mrz.artifact_allowed(connected["id"], url)
    assert allow(DEFAULT_API + "/api/jobs/job-123/files/result.png")
    assert allow("http://localhost:8787/api/jobs/job-123/files/result-front.png")
    for url in [DEFAULT_API + "/control/settings.json", DEFAULT_API + "/api/jobs/../../files/result.png", DEFAULT_API + "/api/jobs/job-123/files/%2e%2e", "http://127.0.0.1:8765/api/jobs/job-123/files/result.png", DEFAULT_API + "/api/jobs/job-123/files/result.png?url=evil"]:
        assert not allow(url)
    assert not world.mrz.artifact_allowed("another-connection", DEFAULT_API + "/api/jobs/job-123/files/result.png")


def test_health_is_not_a_blanket_execution_grant_or_replayed_job(world, monkeypatch):
    connected = world.mrz.connect()
    cid = connected["id"]
    refreshed = []
    monkeypatch.setattr(world.center.connections, "refresh", lambda i: refreshed.append(i))
    world.center._db.execute("UPDATE connection SET status='error', allowed='[\"employee_id_health\"]' WHERE id=?", (cid,))
    world.mrz.recover(cid, "employee_id_health")
    assert refreshed == [], "unapproved programs never restart"
    launch = json.loads(world.center.connections._row(cid)["launch"])
    launch["approvedHash"] = launch["hash"]
    world.center._db.execute("UPDATE connection SET launch=? WHERE id=?", (json.dumps(launch), cid))
    world.mrz.recover(cid, "employee_id_generate")
    assert refreshed == [], "unallowed tools cannot cause recovery"
    world.mrz.recover(cid, "employee_id_health")
    assert refreshed == [cid]
    assert world.center.connections.calls() == [], "recovery only lists tools; no generation replay"


def test_background_checks_continue_without_glass_and_stop_cleanly(world, monkeypatch):
    from cyclone_device_gateway.command import mrz
    monkeypatch.setattr(mrz, "INTERVAL", .01)
    seen = threading.Event()
    original = world.mrz.send
    def send(*args, **kwargs):
        result = original(*args, **kwargs)
        if len(world.calls) >= 2:
            seen.set()
        return result
    world.mrz.send = send
    world.mrz.start()
    assert seen.wait(2)
    world.mrz.close()
    assert not world.mrz._thread.is_alive()


def test_discovery_routes_require_owner_bearer(world):
    app = FastAPI()
    app.include_router(create_command_router(SimpleNamespace(command=world.center), "test-secret"))
    with TestClient(app) as client:
        assert client.get("/v1/cc/integrations/mrz").status_code == 401
        assert client.post("/v1/cc/integrations/mrz/connect", json={}).status_code == 401
        headers = {"Authorization": "Bearer test-secret"}
        assert client.post("/v1/cc/integrations/mrz/check", headers=headers, json={}).json()["state"] == "found"
        result = client.post("/v1/cc/integrations/mrz/connect", headers=headers, json={})
        assert result.status_code == 200 and result.json()["status"] == "needs_approval"


def test_local_http_recipe_is_supported_but_never_remote_discovery(world):
    c = world.data["connectors"][0]
    c.update(glass_kind="remote_http", http_url="http://localhost:8791/mcp")
    assert recipe(world.data)[2]["url"] == "http://127.0.0.1:8791/mcp"
    c["http_url"] = "https://example.com/mcp"
    with pytest.raises(ValueError):
        recipe(world.data)


def test_output_redirect_cannot_escape_linked_route(world, tmp_path):
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
    from cyclone_device_gateway.command.mcp import fetch_file
    seen = []
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass
        def do_GET(self):
            seen.append(self.path)
            self.send_response(302)
            self.send_header("Location", "/private.json")
            self.end_headers()
    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    base = f"http://127.0.0.1:{server.server_port}"
    world.data["studio"]["api_base"] = base
    world.file.write_text(json.dumps(world.data))
    cid = world.mrz.connect()["id"]
    try:
        with pytest.raises(McpError, match="approved output routes"):
            fetch_file(base + "/api/jobs/job1/files/result.png", tmp_path / "result", limit=1000, allow_loopback=True,
                       url_policy=lambda url: world.mrz.artifact_allowed(cid, url))
        assert seen == ["/api/jobs/job1/files/result.png"]
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


def test_linked_local_program_can_keep_a_real_studio_output(world, monkeypatch):
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
    from cyclone_device_gateway.command import mcp
    import hashlib
    class RefuseProxy:
        def open(self, *args, **kwargs):
            raise AssertionError("Studio output must not use the system proxy opener")
    monkeypatch.setattr(mcp, "_OPENER", RefuseProxy())
    payload = b"\x89PNG\r\n\x1a\nfixture-image"
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass
        def do_GET(self):
            assert self.path == "/api/jobs/job1/files/result.png"
            self.send_response(200)
            self.send_header("Content-Type", "image/png")
            self.end_headers()
            self.wfile.write(payload)
    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    base = f"http://127.0.0.1:{server.server_port}"
    world.data["studio"]["api_base"] = base
    world.file.write_text(json.dumps(world.data))
    cid = world.mrz.connect()["id"]
    try:
        row = world.center.connections._row(cid)
        made = world.center.connections._keep(
            {"content": [{"type": "text", "text": json.dumps({"output_url": base + "/api/jobs/job1/files/result.png"})}]}, row,
            {"id": "call_fixture", "tool": "employee_id_status", "task_id": None}, {})
        assert len(made) == 1
        aid = made[0]
        artifact, file = world.center.connections.artifact(aid)
        assert file.read_bytes() == payload
        assert artifact["sha256"] == hashlib.sha256(payload).hexdigest()
        assert artifact["connectionId"] == cid
        with pytest.raises(McpError, match="job-output routes"):
            world.center.connections._save_url(base + "/private.png", row,
                                               {"id": "call_fixture", "tool": "employee_id_status", "task_id": None}, "")
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


def test_local_probe_refuses_redirect_and_bounds_body(tmp_path):
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
    from cyclone_device_gateway.command.mrz import local_http, MAX_SETTINGS
    seen = []
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass
        def do_GET(self):
            seen.append(self.path)
            if self.path == "/redirect":
                self.send_response(302)
                self.send_header("Location", "/other")
                self.end_headers()
            else:
                self.send_response(200)
                self.end_headers()
                self.wfile.write(b"x" * (MAX_SETTINGS + 10))
    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    base = f"http://127.0.0.1:{server.server_port}"
    try:
        with pytest.raises(McpError):
            local_http("GET", base + "/redirect", headers={}, timeout=2)
        assert seen == ["/redirect"]
        result = local_http("GET", base + "/large", headers={}, timeout=2)
        assert len(result.body) == MAX_SETTINGS + 1
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)
