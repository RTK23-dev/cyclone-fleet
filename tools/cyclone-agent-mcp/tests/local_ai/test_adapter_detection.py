from __future__ import annotations

from cyclone_agent_mcp.adapters import ADAPTERS, discover_adapters, get_adapter


def test_adapter_registry_covers_supported_local_ai_hosts():
    assert set(ADAPTERS) == {"codex", "grok", "cursor", "opencode", "copilot", "generic"}
    for host in ADAPTERS:
        adapter = get_adapter(host)
        assert adapter.id == host
        detection = adapter.detect()
        assert detection.id == host
        assert isinstance(detection.detected, bool)


def test_generic_adapter_is_always_available():
    detection = get_adapter("generic").detect()
    assert detection.detected is True
    assert detection.installed is True


def test_discover_adapters_returns_all_hosts(monkeypatch):
    monkeypatch.setattr("cyclone_agent_mcp.status.host_installed", lambda host: host in {"codex", "cursor"})
    monkeypatch.setattr("cyclone_agent_mcp.status._command_exists", lambda _: True)
    discovered = {item["id"]: item for item in discover_adapters()}
    assert set(discovered) == set(ADAPTERS)
    assert discovered["codex"]["detected"] is True
    assert discovered["cursor"]["detected"] is True
    assert discovered["grok"]["detected"] is False
    assert discovered["generic"]["detected"] is True


def test_missing_provider_stays_unknown(monkeypatch, tmp_path):
    monkeypatch.setattr("cyclone_agent_mcp.status.host_installed", lambda host: False)
    monkeypatch.setattr("cyclone_agent_mcp.status.codex_config_path", lambda: tmp_path / "missing.toml")
    status = get_adapter("codex").status()
    assert status.state == "UNKNOWN"
    assert status.detected is False
    assert status.configured is False
