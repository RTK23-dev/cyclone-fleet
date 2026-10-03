from __future__ import annotations

import json
from pathlib import Path

import cyclone_agent_mcp.connector as connector
from cyclone_agent_mcp.profiles import SERVER_KEY, cursor_mcp_path, cursor_profile


def test_cursor_mcp_path_honors_override(tmp_path, monkeypatch):
    target = tmp_path / "cursor" / "mcp.json"
    monkeypatch.setenv("CYCLONE_CURSOR_MCP_PATH", str(target))
    assert cursor_mcp_path() == target


def test_cursor_profile_omits_gateway_token_and_points_at_one():
    command = r"C:\Users\Agent\AppData\Local\Cyclone One\CycloneAgentMCP.exe"
    profile = cursor_profile(
        command,
        ["serve"],
        env={
            "CYCLONE_DEVICE_GATEWAY_URL": "http://127.0.0.1:8765",
            "CYCLONE_DEVICE_GATEWAY_PORT": "8765",
            "CYCLONE_DEVICE_GATEWAY_RUNTIME": r"C:\Users\Agent\AppData\Local\Cyclone One\runtime",
            "CYCLONE_DEVICE_GATEWAY_TOKEN": "must-not-appear",
        },
    )
    rendered = json.dumps(profile)
    assert "CYCLONE_DEVICE_GATEWAY_TOKEN" not in rendered
    assert "must-not-appear" not in rendered
    server = profile["mcpServers"][SERVER_KEY]
    assert server["command"] == command
    assert "Cyclone One" in server["command"]
    assert "Cyclone PC Companion" not in server["command"]
    assert server["args"] == ["serve"]
    assert "CYCLONE_DEVICE_GATEWAY_TOKEN" not in server["env"]


def test_cursor_connect_points_at_one_and_preserves_other_servers(tmp_path, monkeypatch):
    path = tmp_path / "mcp.json"
    path.write_text(
        json.dumps(
            {
                "mcpServers": {
                    "other": {"command": "other.exe", "args": []},
                    "cyclone-phone": {
                        "command": str(tmp_path / "Cyclone PC Companion" / "CycloneAgentMCP.exe"),
                        "args": ["serve"],
                        "env": {"CYCLONE_DEVICE_GATEWAY_TOKEN": "stale-secret"},
                    },
                }
            },
            indent=2,
        ),
        encoding="utf-8",
    )
    one_exe = tmp_path / "Cyclone One" / "CycloneAgentMCP.exe"
    one_exe.parent.mkdir(parents=True)
    one_exe.write_bytes(b"")
    monkeypatch.setenv("CYCLONE_CURSOR_MCP_PATH", str(path))
    monkeypatch.setenv("LOCALAPPDATA", str(tmp_path))
    monkeypatch.setenv("CYCLONE_TOOLING_TEST_ROOT", str(tmp_path))
    monkeypatch.delenv("CYCLONE_DEVICE_GATEWAY_TOKEN", raising=False)
    monkeypatch.delenv("CYCLONE_AGENT_MCP_EXE", raising=False)
    result = connector.connect("cursor", executable=str(one_exe))
    payload = json.loads(path.read_text(encoding="utf-8"))
    blob = json.dumps(payload)
    server = payload["mcpServers"][SERVER_KEY]
    assert result["host"] == "cursor"
    assert payload["mcpServers"]["other"]["command"] == "other.exe"
    assert Path(server["command"]) == one_exe.resolve()
    assert "Cyclone One" in server["command"]
    assert "Cyclone PC Companion" not in server["command"]
    assert "CYCLONE_DEVICE_GATEWAY_TOKEN" not in blob
    assert "CYCLONE_DEVICE_GATEWAY_TOKEN" not in server.get("env", {})


def test_cursor_connect_rewrites_legacy_companion_command(tmp_path, monkeypatch):
    path = tmp_path / "mcp.json"
    monkeypatch.setenv("CYCLONE_CURSOR_MCP_PATH", str(path))
    monkeypatch.setenv("LOCALAPPDATA", str(tmp_path))
    monkeypatch.setenv("CYCLONE_TOOLING_TEST_ROOT", str(tmp_path))
    monkeypatch.delenv("CYCLONE_DEVICE_GATEWAY_TOKEN", raising=False)
    monkeypatch.delenv("CYCLONE_AGENT_MCP_EXE", raising=False)
    one_exe = tmp_path / "Cyclone One" / "CycloneAgentMCP.exe"
    one_exe.parent.mkdir(parents=True)
    one_exe.write_bytes(b"")
    legacy = tmp_path / "Cyclone PC Companion" / "CycloneAgentMCP.exe"
    result = connector.connect("cursor", executable=str(legacy))
    payload = json.loads(path.read_text(encoding="utf-8"))
    command = payload["mcpServers"][SERVER_KEY]["command"]
    assert "Cyclone PC Companion" not in command
    assert "Cyclone One" in command
    assert Path(command) == one_exe.resolve()
    assert "CYCLONE_DEVICE_GATEWAY_TOKEN" not in json.dumps(result["configuration"])
    assert "CYCLONE_DEVICE_GATEWAY_TOKEN" not in json.dumps(payload)


def test_cursor_disconnect_removes_only_cyclone(tmp_path, monkeypatch):
    path = tmp_path / "mcp.json"
    monkeypatch.setenv("CYCLONE_CURSOR_MCP_PATH", str(path))
    path.write_text(
        json.dumps(
            {
                "mcpServers": {
                    "other": {"command": "other.exe"},
                    "cyclone-phone": {"command": "CycloneAgentMCP.exe", "args": ["serve"], "env": {}},
                }
            }
        ),
        encoding="utf-8",
    )
    result = connector.disconnect("cursor")
    payload = json.loads(path.read_text(encoding="utf-8"))
    assert result["changed"] is True
    assert "cyclone-phone" not in payload["mcpServers"]
    assert payload["mcpServers"]["other"]["command"] == "other.exe"


def test_cursor_host_installed_when_dot_cursor_exists(tmp_path, monkeypatch):
    monkeypatch.setattr(connector.Path, "home", lambda *_args, **_kwargs: tmp_path)
    monkeypatch.setattr(connector.shutil, "which", lambda _name: None)
    assert connector.host_installed("cursor") is False
    (tmp_path / ".cursor").mkdir()
    assert connector.host_installed("cursor") is True


def test_cursor_host_installed_when_cursor_exe_on_path(tmp_path, monkeypatch):
    monkeypatch.setattr(connector.Path, "home", lambda *_args, **_kwargs: tmp_path)
    monkeypatch.setattr(
        connector.shutil,
        "which",
        lambda name: str(tmp_path / "cursor.exe") if name in {"cursor", "cursor.exe"} else None,
    )
    assert connector.host_installed("cursor") is True
