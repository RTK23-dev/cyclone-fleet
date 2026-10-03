from __future__ import annotations

import json

from cyclone_agent_mcp import status
from cyclone_agent_mcp.adapters import get_adapter, normalize_host
from cyclone_agent_mcp.profiles import SERVER_KEY, grok_profile


def test_connect_goes_through_adapter_detect_configure(tmp_path, monkeypatch):
    path = tmp_path / "config.toml"
    monkeypatch.setattr("cyclone_agent_mcp.connector.codex_config_path", lambda: path)
    monkeypatch.setattr("cyclone_agent_mcp.status.codex_config_path", lambda: path)
    adapter = get_adapter("codex")
    detection = adapter.detect()
    assert detection.id == "codex"
    result = adapter.connect(executable=str(tmp_path / "CycloneAgentMCP.exe"))
    assert result["host"] == "codex"
    assert path.exists()
    text = path.read_text(encoding="utf-8")
    assert f"[mcp_servers.{SERVER_KEY}]" in text
    assert "CYCLONE_DEVICE_GATEWAY_TOKEN" not in text


def test_grok_adapter_writes_local_mcp_profile(tmp_path, monkeypatch):
    path = tmp_path / "mcp.json"
    monkeypatch.setattr("cyclone_agent_mcp.connector.grok_config_path", lambda: path)
    monkeypatch.setattr("cyclone_agent_mcp.status.grok_config_path", lambda: path)
    result = get_adapter("grok").connect(executable=str(tmp_path / "CycloneAgentMCP.exe"))
    payload = json.loads(path.read_text(encoding="utf-8"))
    assert result["host"] == "grok"
    assert SERVER_KEY in payload["mcpServers"]
    assert "CYCLONE_DEVICE_GATEWAY_TOKEN" not in json.dumps(payload)


def test_generic_mcp_adapter_is_copy_only():
    result = get_adapter("generic").connect(dry_run=True, executable="CycloneAgentMCP.exe")
    assert result["host"] == "generic"
    assert result["dry_run"] is True
    assert SERVER_KEY in result["configuration"]["mcpServers"]


def test_connection_status_keeps_ai_and_phone_split(monkeypatch):
    class OfflineGateway:
        def __init__(self, timeout):
            pass

        def list_devices(self):
            raise RuntimeError("offline")

    monkeypatch.setattr(status, "host_installed", lambda _: True)
    monkeypatch.setattr(status, "_command_exists", lambda _: True)
    monkeypatch.setattr(status, "_codex_configured", lambda _: True)
    monkeypatch.setattr(status, "_json_opencode_configured", lambda _: False)
    monkeypatch.setattr(status, "_json_copilot_configured", lambda _: False)
    monkeypatch.setattr(status, "_json_mcp_servers_configured", lambda _: False)
    monkeypatch.setattr(status, "GatewayClient", OfflineGateway)
    payload = status.connection_status(probe_gateway=True)
    assert payload["ai"]["adapters"]["codex"]["state"] == "CONNECTED"
    assert payload["phone"]["state"] == "DISCONNECTED"
    assert payload["ai"]["state"] != "FAILED"
    assert payload["details"]["gateway"]["state"] == "OFFLINE"
    assert not any(item["layer"] == "local_ai" for item in payload["repair"])
    assert any(item["layer"] == "phone" for item in payload["repair"])


def test_host_aliases_stay_provider_neutral():
    assert normalize_host("deepseek-mcp") == "opencode"
    assert normalize_host("generic-mcp") == "generic"
    assert grok_profile("CycloneAgentMCP.exe", ["serve"])["mcpServers"][SERVER_KEY]["command"] == "CycloneAgentMCP.exe"
