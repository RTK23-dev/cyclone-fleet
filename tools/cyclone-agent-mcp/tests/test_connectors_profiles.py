from __future__ import annotations

import json
from pathlib import Path
import tomllib

import cyclone_agent_mcp.connector as connector
from cyclone_agent_mcp.tool_catalog import TOOL_NAMES
from cyclone_agent_mcp.profiles import (
    codex_toml,
    copilot_profile,
    deepseek_copilot_notes,
    deepseek_opencode_notes,
    generic_profile,
    opencode_profile,
)


def test_codex_config_generation_has_no_gateway_token():
    snippet = codex_toml(r"C:\Program Files\Cyclone\CycloneAgentMCP.exe", ["serve"])
    assert "CycloneAgentMCP.exe" in snippet
    assert "CYCLONE_DEVICE_GATEWAY_TOKEN" not in snippet
    assert "Bearer" not in snippet
    parsed = tomllib.loads(snippet)
    server = parsed["mcp_servers"]["cyclone-phone"]
    assert server["required"] is False
    assert server["default_tools_approval_mode"] == "writes"
    assert server["startup_timeout_sec"] == 20
    assert server["tool_timeout_sec"] == 120


def test_codex_dry_run_does_not_write(tmp_path, monkeypatch):
    path = tmp_path / "config.toml"
    monkeypatch.setattr(connector, "codex_config_path", lambda: path)
    result = connector.connect("codex", dry_run=True, executable=str(tmp_path / "CycloneAgentMCP.exe"))
    assert result["dry_run"] is True
    assert not path.exists()


def test_codex_connect_is_atomic_idempotent_and_preserves_other_servers(tmp_path, monkeypatch):
    path = tmp_path / "config.toml"
    path.write_text('[mcp_servers.other]\ncommand = "other.exe"\n', encoding="utf-8")
    monkeypatch.setattr(connector, "codex_config_path", lambda: path)
    first = connector.connect("codex", executable=str(tmp_path / "CycloneAgentMCP.exe"))
    second = connector.connect("codex", executable=str(tmp_path / "CycloneAgentMCP.exe"))
    parsed = tomllib.loads(path.read_text(encoding="utf-8"))
    assert first["changed"] is True and first["restart_required"] is True
    assert second["changed"] is False and second["restart_required"] is False
    assert parsed["mcp_servers"]["other"]["command"] == "other.exe"
    assert parsed["mcp_servers"]["cyclone-phone"]["command"].endswith("CycloneAgentMCP.exe")
    assert not list(tmp_path.glob("*.tmp"))


def test_invalid_existing_codex_toml_is_left_unchanged(tmp_path, monkeypatch):
    path = tmp_path / "config.toml"
    original = "[broken\nvalue = true\n"
    path.write_text(original, encoding="utf-8")
    monkeypatch.setattr(connector, "codex_config_path", lambda: path)
    try:
        connector.connect("codex", executable=str(tmp_path / "CycloneAgentMCP.exe"))
    except ValueError as exc:
        assert "left it unchanged" in str(exc)
    else:
        raise AssertionError("invalid Codex TOML was overwritten")
    assert path.read_text(encoding="utf-8") == original


def test_codex_disconnect_removes_only_cyclone_block(tmp_path, monkeypatch):
    path = tmp_path / "config.toml"
    monkeypatch.setattr(connector, "codex_config_path", lambda: path)
    path.write_text('model = "gpt"\n\n' + codex_toml("CycloneAgentMCP.exe", ["serve"]) + '\n[other]\nx = 1\n', encoding="utf-8")
    result = connector.disconnect("codex")
    text = path.read_text(encoding="utf-8")
    assert result["changed"] is True
    assert "CYCLONE AGENT MCP" not in text
    assert 'model = "gpt"' in text and "[other]" in text


def test_opencode_deepseek_profile_is_mcp_only_and_secret_free():
    profile = opencode_profile("CycloneAgentMCP.exe", ["serve"])
    rendered = json.dumps(profile)
    assert profile["mcp"]["servers"]["cyclone-phone"]["command"][0] == "CycloneAgentMCP.exe"
    assert "DEEPSEEK_API_KEY" not in rendered
    notes = deepseek_opencode_notes()
    assert notes["secrets_in_mcp_config"] is False
    assert "/connect" in notes["credential_setup"]


def test_copilot_profile_and_deepseek_byok_notes_are_secret_free():
    profile = copilot_profile("CycloneAgentMCP.exe", ["serve"])
    assert profile["mcpServers"]["cyclone-phone"]["env"] == {}
    notes = deepseek_copilot_notes()
    assert "COPILOT_PROVIDER_API_KEY" in notes["provider_environment"]
    assert notes["credential_value_embedded"] is False
    assert "sk-" not in json.dumps(notes)


def test_generic_mcp_profile_uses_same_server_surface():
    profile = generic_profile("CycloneAgentMCP.exe", ["serve"])
    assert set(profile["mcpServers"]) == {"cyclone-phone"}
    assert profile == copilot_profile("CycloneAgentMCP.exe", ["serve"])


def test_connection_status_contract_values(monkeypatch):
    import cyclone_agent_mcp.status as status
    monkeypatch.setattr(status, "_command_exists", lambda _: True)
    monkeypatch.setattr(status, "host_installed", lambda _: True)
    monkeypatch.setattr(status, "_codex_configured", lambda _: True)
    monkeypatch.setattr(status, "_json_opencode_configured", lambda _: True)
    monkeypatch.setattr(status, "_json_copilot_configured", lambda _: False)
    result = status.connection_status()
    assert result["codex"] in status.HOST_STATES
    assert result["deepseek_harness"] in status.HOST_STATES
    assert result["generic_mcp"] in status.GENERIC_STATES


def test_detailed_codex_status_reports_safe_gateway_readiness(monkeypatch, tmp_path):
    import cyclone_agent_mcp.status as status

    class ReadyDevice:
        ready = True

        def safe_dict(self):
            return {"device_id": "phone-safe", "state": "READY"}

    class FakeGateway:
        def __init__(self, timeout):
            assert timeout == 2.0

        def list_devices(self):
            return [ReadyDevice()]

    config = tmp_path / "config.toml"
    config.write_text(codex_toml("CycloneAgentMCP.exe", ["serve"]), encoding="utf-8")
    monkeypatch.setattr(status, "codex_config_path", lambda: config)
    monkeypatch.setattr(status, "host_installed", lambda _: True)
    monkeypatch.setattr(status, "_command_exists", lambda _: True)
    monkeypatch.setattr(status, "_json_opencode_configured", lambda _: False)
    monkeypatch.setattr(status, "_json_copilot_configured", lambda _: False)
    monkeypatch.setattr(status, "GatewayClient", FakeGateway)
    result = status.connection_status(probe_gateway=True)
    assert result["details"]["gateway"] == {
        "state": "READY",
        "reachable": True,
        "ready_device_count": 1,
        "device_count": 1,
        "devices": [{"device_id": "phone-safe", "state": "READY"}],
    }
    assert result["details"]["mcp"]["tool_count"] == len(TOOL_NAMES)
    assert "token" not in json.dumps(result).lower()


def test_detailed_status_keeps_desktop_online_when_gateway_probe_crashes(monkeypatch):
    import cyclone_agent_mcp.status as status

    class BrokenGateway:
        def __init__(self, timeout):
            assert timeout == 2.0

        def list_devices(self):
            raise RuntimeError("sensitive implementation detail")

    monkeypatch.setattr(status, "host_installed", lambda _: True)
    monkeypatch.setattr(status, "_command_exists", lambda _: True)
    monkeypatch.setattr(status, "GatewayClient", BrokenGateway)
    result = status.connection_status(probe_gateway=True)
    assert result["details"]["gateway"] == {
        "state": "OFFLINE",
        "reachable": False,
        "ready_device_count": 0,
        "device_count": 0,
        "error_code": "GATEWAY_UNAVAILABLE",
    }
    assert "sensitive" not in json.dumps(result)
