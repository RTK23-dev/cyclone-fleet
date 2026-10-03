from __future__ import annotations

from cyclone_agent_mcp.adapters.local_ai_adapter import overall_ai_state, phone_state, repair_actions
from cyclone_agent_mcp.status import get_ai_status, get_phone_status
from cyclone_agent_mcp.profiles import codex_toml


def test_ai_connected_and_phone_disconnected_are_independent(monkeypatch, tmp_path):
    config = tmp_path / "config.toml"
    config.write_text(codex_toml("CycloneAgentMCP.exe", ["serve"]), encoding="utf-8")

    class OfflineGateway:
        def __init__(self, timeout):
            assert timeout == 2.0

        def list_devices(self):
            raise RuntimeError("phone cable unplugged")

    monkeypatch.setattr("cyclone_agent_mcp.status.host_installed", lambda _: True)
    monkeypatch.setattr("cyclone_agent_mcp.status._command_exists", lambda _: True)
    monkeypatch.setattr("cyclone_agent_mcp.status.codex_config_path", lambda: config)
    monkeypatch.setattr("cyclone_agent_mcp.status.GatewayClient", OfflineGateway)

    ai = get_ai_status()
    phone = get_phone_status()
    assert ai["adapters"]["codex"]["state"] == "CONNECTED"
    assert ai["state"] in {"CONNECTED", "CONFIGURED"}
    assert phone["state"] == "DISCONNECTED"
    assert phone["reachable"] is False
    assert ai["state"] != "FAILED"
    assert "ATTENTION" not in str(ai["adapters"]["codex"]["state"])


def test_phone_offline_does_not_equal_ai_failure():
    assert overall_ai_state(["CONNECTED"]) == "CONNECTED"
    assert phone_state(reachable=False, device_count=0, ready_device_count=0) == "DISCONNECTED"
    actions = repair_actions(
        ai_state_value="CONNECTED",
        ai_configured=True,
        phone_state_value="DISCONNECTED",
        engine_ready=True,
    )
    assert [item.layer for item in actions] == ["phone"]
    assert actions[0].label == "Connect phone"


def test_get_ai_status_never_embeds_phone_fields(monkeypatch):
    monkeypatch.setattr("cyclone_agent_mcp.status.host_installed", lambda _: True)
    monkeypatch.setattr("cyclone_agent_mcp.status._command_exists", lambda _: True)
    ai = get_ai_status()
    assert "reachable" not in ai
    assert "ready_device_count" not in ai
    assert "gateway" not in ai
    assert set(ai["adapters"]) >= {"codex", "grok", "cursor", "opencode", "copilot", "generic"}
