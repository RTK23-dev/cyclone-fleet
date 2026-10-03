from __future__ import annotations

import json
import tomllib
from pathlib import Path

from cyclone_agent_mcp.audit import SafeAuditLog
from cyclone_agent_mcp.safe import redact, validate_typed_params
from cyclone_agent_mcp.tool_catalog import TOOL_NAMES

ROOT = Path(__file__).parents[1]


def test_official_mcp_sdk_is_exactly_pinned():
    data = tomllib.loads((ROOT / "pyproject.toml").read_text(encoding="utf-8"))
    assert "mcp==2.0.0" in data["project"]["dependencies"]


def test_no_shell_or_adb_tools_exist():
    lowered = [name.lower() for name in TOOL_NAMES]
    assert not any("shell" in name or "adb" in name or "exec" in name or "command" in name for name in lowered)


def test_typed_params_reject_nested_host_command_escape_hatches():
    for payload in (
        {"command": "whoami"},
        {"selector": {"shell": "id"}},
        {"provider": {"docker": "run"}},
        {"script": "anything"},
    ):
        try:
            validate_typed_params(payload)
            raise AssertionError("command-shaped payload should be rejected")
        except ValueError:
            pass


def test_redaction_removes_secrets_recursively():
    data = {"token": "abc", "nested": {"api_key": "sk-1234567890123456", "safe": "ok"}, "authorization": "Bearer secret"}
    rendered = json.dumps(redact(data))
    assert "abc" not in rendered
    assert "sk-" not in rendered
    assert "Bearer secret" not in rendered
    assert "ok" in rendered


def test_audit_log_never_receives_argument_values(tmp_path):
    path = tmp_path / "audit.jsonl"
    audit = SafeAuditLog(str(path))
    audit.record("phone.type", ok=False, elapsed_ms=5, error_code="POLICY_DENIED")
    text = path.read_text(encoding="utf-8")
    assert "POLICY_DENIED" in text
    assert "value" not in text.lower()
    assert "token" not in text.lower()

def test_official_mcp_sdk_stdio_tools_list_when_sdk_is_available(monkeypatch):
    import sys
    import pytest
    pytest.importorskip("mcp")
    from cyclone_agent_mcp import connector
    # Test this checkout, not an older Companion discovered in the Windows user's installed-app directory.
    monkeypatch.setattr(connector, "resolve_server_command", lambda _: connector.ServerCommand(sys.executable, ["-m", "cyclone_agent_mcp", "serve"]))
    result = connector.verify_tools_list()
    assert result["ok"] is True


def test_packaged_fleet_tool_is_not_misclassified_as_phone_scoped(monkeypatch):
    from cyclone_agent_mcp import connector

    monkeypatch.setattr(connector.sys, "frozen", True, raising=False)
    assert "phone_devices" in connector._expected_tool_names()
