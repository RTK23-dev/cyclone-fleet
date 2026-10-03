"""Plan 31: the Cyclone One window's Remote MCP, ChatGPT Attach and share, served by the gateway to Glass."""
from __future__ import annotations

import json
from pathlib import Path

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from cyclone_device_gateway.pc import common
from cyclone_device_gateway.pc.api import create_pc_router
from cyclone_device_gateway.pc.attach import AttachService, public_config
from cyclone_device_gateway.pc.common import PcFeatureError, ScriptResult, decode_output, extract_json
from cyclone_device_gateway.pc.share import ShareService, find_host
from cyclone_device_gateway.pc.tunnel import TunnelService, load_dot_env

TOKEN = "t" * 32
AUTH = {"Authorization": f"Bearer {TOKEN}"}
BEARER = "tunnel-bearer-secret-value-1234"


def make_pack(root: Path) -> Path:
    pack = root / "pack"
    (pack / "gateway").mkdir(parents=True)
    (pack / "gateway" / "server.js").write_text("// server", encoding="utf-8")
    (pack / "scripts").mkdir()
    for name in ("status-tunnel.ps1", "start-tunnel.ps1", "stop-tunnel.ps1", "rotate-token.ps1", "smoke-gateway.ps1"):
        (pack / "scripts" / name).write_text("#", encoding="utf-8")
    (pack / "docs").mkdir()
    (pack / "docs" / "CONNECTOR_SETUP.md").write_text("# Setup", encoding="utf-8")
    (pack / "config").mkdir()
    (pack / "config" / "gateway.env").write_text("GATEWAY_BEARER_TOKEN=do-not-copy-this", encoding="utf-8")
    return pack


class Recorder:
    def __init__(self, stdout: str = '{"state":"running","mode":"readonly"}'):
        self.calls: list[tuple[list[str], dict[str, str]]] = []
        self.stdout = stdout

    def __call__(self, argv, env, cwd, timeout):
        self.calls.append((list(argv), dict(env)))
        return ScriptResult(0, self.stdout, "")


def tunnel_service(tmp_path: Path, runner: Recorder) -> TunnelService:
    return TunnelService(root=tmp_path / "install" / "mcp-tunnel", pack=make_pack(tmp_path), run=runner,
                         windows=lambda: True, mcp_binary=lambda: "C:/Cyclone One/CycloneAgentMCP.exe")


def attach_pack(tmp_path: Path) -> Path:
    pack = tmp_path / "attach-pack"
    (pack / "scripts").mkdir(parents=True)
    (pack / "scripts" / "Sync-VmosFleet.ps1").write_text("#", encoding="utf-8")
    (pack / "openapi-cloud-control.yaml").write_text("openapi: 3.1.0", encoding="utf-8")
    (pack / "CUSTOM_GPT_INSTRUCTIONS.md").write_text("# Driver", encoding="utf-8")
    (pack / "vmos.fleet.example.json").write_text("{}", encoding="utf-8")
    return pack


def attach_service(tmp_path: Path, runner=None) -> AttachService:
    return AttachService(root=tmp_path / "attach", pack=attach_pack(tmp_path), run=runner or Recorder('{"ok":true,"pads":{"id":"pad-1","ok":true}}'),
                         windows=lambda: True, protect=lambda b: b[::-1], unprotect=lambda b: b[::-1], adb=lambda: "adb.exe")


def client(tmp_path: Path, **services) -> TestClient:
    app = FastAPI()
    router, _ = create_pc_router(TOKEN, 8765, **services)
    app.include_router(router)
    return TestClient(app)


FLEET = {
    "controlApiBase": "",
    "defaultGoal": "Watch",
    "vmosApiKey": "VMOS-ACCESS-KEY-123",
    "pads": [{"id": "pad-1", "label": "Pad 1", "sshHost": "ssh.vmos.example", "sshPort": 1824, "sshUser": "s",
              "localAdbPort": 63670, "remoteAdbSpec": "localhost:1", "connectKey": "CONNECT-KEY-XYZ"}],
}


# ---- shared helpers -------------------------------------------------------------------------------------------------

def test_decode_utf16_and_extract_last_json():
    assert decode_output(b"\xff\xfe" + '{"ok":true}'.encode("utf-16-le")) == '{"ok":true}'
    assert decode_output('{"a":1}'.encode("utf-16-le")) == '{"a":1}'
    assert extract_json('starting\n{"state":"running"}\n')["state"] == "running"
    with pytest.raises(PcFeatureError):
        extract_json("no json here")


def test_every_route_needs_the_bearer(tmp_path):
    api = client(tmp_path, tunnel=tunnel_service(tmp_path, Recorder()), attach=attach_service(tmp_path))
    for method, path in [("get", "/v1/pc/tunnel"), ("post", "/v1/pc/tunnel/start"), ("post", "/v1/pc/tunnel/stop"),
                         ("post", "/v1/pc/tunnel/rotate"), ("get", "/v1/pc/tunnel/token"), ("get", "/v1/pc/attach/fleet"),
                         ("post", "/v1/pc/attach/sync"), ("post", "/v1/pc/attach/share/start"), ("get", "/v1/pc/welcome"),
                         ("post", "/v1/pc/welcome/seen")]:
        assert getattr(api, method)(path).status_code == 401, path
        assert getattr(api, method)(path, headers={"Authorization": "Bearer wrong"}).status_code == 403, path


# ---- Remote MCP tunnel ----------------------------------------------------------------------------------------------

def test_tunnel_start_runs_the_fixed_script_and_never_copies_owner_secrets(tmp_path):
    runner = Recorder()
    service = tunnel_service(tmp_path, runner)
    api = client(tmp_path, tunnel=service)
    response = api.post("/v1/pc/tunnel/start", json={"mode": "full"}, headers=AUTH)
    assert response.status_code == 200
    argv, env = runner.calls[-1]
    assert argv[0] == "powershell.exe" and argv[argv.index("-File") + 1].endswith("start-tunnel.ps1")
    assert argv[-3:] == ["-Json", "-Mode", "full"]
    assert env["MCP_COMMAND"].endswith("CycloneAgentMCP.exe")
    assert not (service.root / "config" / "gateway.env").exists()  # the pack's example secrets are never copied
    assert response.json()["localMcpUrl"] == "http://127.0.0.1:8787/mcp"


def test_tunnel_mode_is_an_enum(tmp_path):
    api = client(tmp_path, tunnel=tunnel_service(tmp_path, Recorder()))
    assert api.post("/v1/pc/tunnel/start", json={"mode": "root"}, headers=AUTH).status_code == 422
    assert api.post("/v1/pc/tunnel/start", json={"mode": "full", "cmd": "calc"}, headers=AUTH).status_code == 422
    assert api.post("/v1/pc/tunnel/mode", json={"mode": "everything"}, headers=AUTH).status_code == 422


def test_tunnel_output_is_redacted_and_token_only_on_request(tmp_path):
    runner = Recorder(f'{{"state":"running","token":"{BEARER}","note":"Bearer {BEARER}"}}')
    service = tunnel_service(tmp_path, runner)
    service.ensure_installed()
    (service.root / "config" / "gateway.env").write_text(f"GATEWAY_BEARER_TOKEN={BEARER}\n", encoding="utf-8")
    api = client(tmp_path, tunnel=service)
    status = api.get("/v1/pc/tunnel", headers=AUTH)
    assert BEARER not in status.text
    token = api.get("/v1/pc/tunnel/token", headers=AUTH).json()
    assert token == {"token": BEARER, "last4": "1234"}


def test_tunnel_set_mode_writes_env(tmp_path):
    service = tunnel_service(tmp_path, Recorder('{"state":"stopped"}'))
    api = client(tmp_path, tunnel=service)
    assert api.post("/v1/pc/tunnel/mode", json={"mode": "full"}, headers=AUTH).status_code == 200
    assert load_dot_env(service.root / "config" / "gateway.env")["GATEWAY_MODE"] == "full"


def test_tunnel_off_windows_reports_stopped(tmp_path):
    service = TunnelService(root=tmp_path / "t", pack=make_pack(tmp_path), run=Recorder(), windows=lambda: False)
    assert service.status()["state"] == "stopped"
    with pytest.raises(PcFeatureError):
        service.start()


# ---- ChatGPT Attach -------------------------------------------------------------------------------------------------

def test_fleet_secrets_are_saved_protected_and_never_returned(tmp_path):
    service = attach_service(tmp_path)
    api = client(tmp_path, attach=service)
    saved = api.put("/v1/pc/attach/fleet", json=FLEET, headers=AUTH)
    assert saved.status_code == 200
    assert "VMOS-ACCESS-KEY-123" not in saved.text and "CONNECT-KEY-XYZ" not in saved.text
    assert saved.json()["hasVmosApiKey"] is True and saved.json()["pads"][0]["hasConnectKey"] is True
    raw = (service.root / "fleet.dpapi").read_bytes()
    assert b"VMOS-ACCESS-KEY-123" not in raw  # stored through protect()
    loaded = api.get("/v1/pc/attach/fleet", headers=AUTH)
    assert "CONNECT-KEY-XYZ" not in loaded.text


def test_empty_secret_fields_keep_the_saved_keys(tmp_path):
    service = attach_service(tmp_path)
    api = client(tmp_path, attach=service)
    api.put("/v1/pc/attach/fleet", json=FLEET, headers=AUTH)
    again = json.loads(json.dumps(FLEET))
    again.pop("vmosApiKey")
    again["pads"][0].pop("connectKey")
    again["pads"][0]["label"] = "Renamed"
    body = api.put("/v1/pc/attach/fleet", json=again, headers=AUTH).json()
    assert body["hasVmosApiKey"] is True and body["pads"][0]["hasConnectKey"] is True
    assert body["pads"][0]["label"] == "Renamed"


def test_fleet_input_is_validated(tmp_path):
    api = client(tmp_path, attach=attach_service(tmp_path))
    bad = json.loads(json.dumps(FLEET))
    bad["pads"][0]["sshHost"] = "host; calc.exe"
    assert api.put("/v1/pc/attach/fleet", json=bad, headers=AUTH).status_code == 422
    bad = json.loads(json.dumps(FLEET))
    bad["controlApiBase"] = "http://evil.example/cloud"
    assert api.put("/v1/pc/attach/fleet", json=bad, headers=AUTH).status_code == 422
    bad = json.loads(json.dumps(FLEET))
    bad["pads"].append(dict(bad["pads"][0]))
    assert api.put("/v1/pc/attach/fleet", json=bad, headers=AUTH).status_code == 422


def test_sync_redacts_keys_and_cleans_up(tmp_path):
    runner = Recorder('{"ok":true,"pads":{"id":"pad-1","ok":true,"note":"key CONNECT-KEY-XYZ used"}}')
    service = attach_service(tmp_path, runner)
    api = client(tmp_path, attach=service)
    api.put("/v1/pc/attach/fleet", json=FLEET, headers=AUTH)
    result = api.post("/v1/pc/attach/sync", headers=AUTH)
    assert result.status_code == 200
    assert "CONNECT-KEY-XYZ" not in result.text
    assert isinstance(result.json()["pads"], list)
    argv, _ = runner.calls[-1]
    assert argv[argv.index("-File") + 1].endswith("Sync-VmosFleet.ps1")
    assert not (service.root / "runtime" / "fleet.ephemeral.json").exists()


def test_handoff_with_a_key_is_refused(tmp_path):
    service = attach_service(tmp_path)
    api = client(tmp_path, attach=service)
    api.put("/v1/pc/attach/fleet", json=FLEET, headers=AUTH)
    assert api.post("/v1/pc/attach/handoff", json={"markdown": "connectKey: abc"}, headers=AUTH).status_code == 422
    assert api.post("/v1/pc/attach/handoff/check", json={"markdown": "use CONNECT-KEY-XYZ"}, headers=AUTH).status_code == 422
    ok = api.post("/v1/pc/attach/handoff", json={"markdown": "DEVICE_ID: pad-1"}, headers=AUTH)
    assert ok.status_code == 200 and Path(ok.json()["path"]).read_text(encoding="utf-8") == "DEVICE_ID: pad-1"


def test_public_config_strips_keys():
    public = public_config({"vmosApiKey": "k", "pads": [{"id": "a", "connectKey": "c"}]})
    assert "vmosApiKey" not in public and "connectKey" not in public["pads"][0]


# ---- Share ----------------------------------------------------------------------------------------------------------

class FakeChild:
    def __init__(self, lines: list[bytes]):
        import io

        self.stderr = io.BytesIO(b"".join(lines))
        self.killed = False

    def poll(self):
        return 0 if self.killed else None

    def kill(self):
        self.killed = True

    def wait(self, timeout=None):
        return 0


def test_share_reads_the_trycloudflare_host_and_stops(tmp_path):
    child = FakeChild([b"INF Requesting new quick Tunnel\n", b"INF |  https://brave-owl-12.trycloudflare.com  |\n"])
    argv_seen: list[list[str]] = []

    def spawn(argv):
        argv_seen.append(argv)
        return child

    share = ShareService(8765, spawn=spawn, exe=lambda: tmp_path / "cloudflared.exe", windows=lambda: True,
                         status_file=tmp_path / "share.json", wait_seconds=2, sleep=lambda _: None)
    status = share.start()
    assert status["url"] == "https://brave-owl-12.trycloudflare.com/cloud"
    assert argv_seen[0][1:] == ["tunnel", "--no-autoupdate", "--url", "http://127.0.0.1:8765", "--protocol", "http2"]
    assert share.stop()["running"] is False and child.killed


def test_find_host_only_accepts_trycloudflare():
    assert find_host("https://evil.example.com") is None
    assert find_host("x https://abc-1.trycloudflare.com y") == "abc-1.trycloudflare.com"


# ---- Welcome card ---------------------------------------------------------------------------------------------------

def test_welcome_card_is_seen_once(tmp_path, monkeypatch):
    monkeypatch.setenv("CYCLONE_DEVICE_GATEWAY_RUNTIME", str(tmp_path / "runtime"))
    api = client(tmp_path)
    assert api.get("/v1/pc/welcome", headers=AUTH).json() == {"seen": False}
    api.post("/v1/pc/welcome/seen", headers=AUTH)
    assert api.get("/v1/pc/welcome", headers=AUTH).json() == {"seen": True}


def test_resources_dir_in_a_checkout_points_at_the_bundled_packs():
    assert (common.resources_dir() / "mcp-tunnel" / "gateway" / "server.js").is_file()
    assert (common.resources_dir() / "chatgpt-attach" / "scripts" / "Sync-VmosFleet.ps1").is_file()
