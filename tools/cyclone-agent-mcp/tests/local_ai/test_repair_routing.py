from __future__ import annotations

from cyclone_agent_mcp.adapters import get_adapter
from cyclone_agent_mcp.adapters.local_ai_adapter import repair_actions


def test_missing_local_ai_config_routes_to_configure():
    actions = repair_actions(
        ai_state_value="DETECTED",
        ai_configured=False,
        phone_state_value="READY",
        engine_ready=True,
    )
    assert len(actions) == 1
    assert actions[0].layer == "local_ai"
    assert actions[0].action == "configure"
    assert actions[0].label == "Configure"
    assert actions[0].detail == "Configuration missing"


def test_disconnected_phone_routes_to_connect_phone():
    actions = repair_actions(
        ai_state_value="CONNECTED",
        ai_configured=True,
        phone_state_value="DISCONNECTED",
        engine_ready=True,
    )
    assert [(item.layer, item.label) for item in actions] == [("phone", "Connect phone")]


def test_stopped_engine_routes_to_restart():
    actions = repair_actions(
        ai_state_value="CONFIGURED",
        ai_configured=True,
        phone_state_value="READY",
        engine_ready=False,
    )
    assert [(item.layer, item.label) for item in actions] == [("engine", "Restart")]


def test_layers_repair_independently():
    actions = repair_actions(
        ai_state_value="DETECTED",
        ai_configured=False,
        phone_state_value="DISCONNECTED",
        engine_ready=False,
    )
    assert [item.layer for item in actions] == ["engine", "local_ai", "phone"]
    assert [item.label for item in actions] == ["Restart", "Configure", "Connect phone"]


def test_codex_adapter_repair_configures_when_detected(monkeypatch, tmp_path):
    path = tmp_path / "config.toml"
    monkeypatch.setattr("cyclone_agent_mcp.status.host_installed", lambda host: host == "codex")
    monkeypatch.setattr("cyclone_agent_mcp.status.codex_config_path", lambda: path)
    monkeypatch.setattr("cyclone_agent_mcp.connector.codex_config_path", lambda: path)
    result = get_adapter("codex").repair(executable=str(tmp_path / "CycloneAgentMCP.exe"))
    assert result.layer == "local_ai"
    assert result.action == "configure"
    assert result.ok is True
    assert path.exists()
