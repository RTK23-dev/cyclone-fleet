from __future__ import annotations

import json
from pathlib import Path

from cyclone_device_gateway.tooling_seam import (
    CURSOR_SERVER_KEY,
    PRODUCT_LEGACY_COMPANION,
    PRODUCT_ONE,
    apply_gateway_env,
    detect_legacy_companion,
    load_connection,
    load_locator,
    persist_runtime_bearer,
    public_locator_summary,
    session_secret_persisted,
    write_cursor_mcp_json,
)


def _isolate(monkeypatch, tmp_path: Path) -> None:
    monkeypatch.setenv("CYCLONE_TOOLING_TEST_ROOT", str(tmp_path))
    monkeypatch.setenv("LOCALAPPDATA", str(tmp_path))
    monkeypatch.setenv("CYCLONE_CURSOR_MCP_PATH", str(tmp_path / "cursor-mcp.json"))
    monkeypatch.delenv("CYCLONE_DEVICE_GATEWAY_TOKEN", raising=False)
    monkeypatch.delenv("CYCLONE_DEVICE_GATEWAY_URL", raising=False)
    monkeypatch.delenv("CYCLONE_DEVICE_GATEWAY_PORT", raising=False)
    monkeypatch.delenv("CYCLONE_DEVICE_GATEWAY_RUNTIME", raising=False)


def test_persist_roundtrip_without_process_env(monkeypatch, tmp_path):
    _isolate(monkeypatch, tmp_path)
    token = "pc-secret-that-must-not-leak"
    result = persist_runtime_bearer(
        token,
        "http://127.0.0.1:43123",
        port=43123,
        runtime=tmp_path / "Cyclone One" / "runtime",
        mcp_executable=tmp_path / "Cyclone One" / "CycloneAgentMCP.exe",
        write_cursor=True,
    )
    assert result["sessionSecretPersisted"] is True
    monkeypatch.delenv("CYCLONE_DEVICE_GATEWAY_TOKEN", raising=False)
    monkeypatch.delenv("CYCLONE_DEVICE_GATEWAY_URL", raising=False)
    loaded = load_connection(include_env=False)
    assert loaded is not None
    assert loaded["token"] == token
    assert loaded["url"] == "http://127.0.0.1:43123"
    locator = load_locator()
    assert locator is not None
    assert locator["sessionSecretPersisted"] is True
    assert "token" not in locator
    assert token not in json.dumps(locator)
    assert session_secret_persisted() is True
    summary = public_locator_summary()
    assert token not in json.dumps(summary)
    assert summary["sessionSecretPersisted"] is True


def test_apply_gateway_env_loads_persisted_bearer(monkeypatch, tmp_path):
    _isolate(monkeypatch, tmp_path)
    persist_runtime_bearer(
        "persisted-bearer",
        "http://127.0.0.1:51234",
        write_cursor=False,
    )
    env: dict[str, str] = {}
    public = apply_gateway_env(env)
    assert env["CYCLONE_DEVICE_GATEWAY_TOKEN"] == "persisted-bearer"
    assert env["CYCLONE_DEVICE_GATEWAY_URL"] == "http://127.0.0.1:51234"
    assert env["CYCLONE_DEVICE_GATEWAY_PORT"] == "51234"
    assert "persisted-bearer" not in json.dumps(public)


def test_cursor_mcp_json_points_at_one_and_omits_token(monkeypatch, tmp_path):
    _isolate(monkeypatch, tmp_path)
    one_exe = str(tmp_path / "Cyclone One" / "CycloneAgentMCP.exe")
    written = write_cursor_mcp_json(
        command=one_exe,
        url="http://127.0.0.1:8765",
        port=8765,
        runtime=str(tmp_path / "Cyclone One" / "runtime"),
    )
    payload = json.loads(Path(written["path"]).read_text(encoding="utf-8"))
    server = payload["mcpServers"][CURSOR_SERVER_KEY]
    assert server["command"] == one_exe
    assert server["args"] == ["serve"]
    assert "CYCLONE_DEVICE_GATEWAY_TOKEN" not in json.dumps(server)
    assert server["env"]["CYCLONE_DEVICE_GATEWAY_URL"] == "http://127.0.0.1:8765"
    assert PRODUCT_LEGACY_COMPANION.lower() not in server["command"].lower()


def test_cursor_writer_rewrites_legacy_companion_command(monkeypatch, tmp_path):
    _isolate(monkeypatch, tmp_path)
    one_exe = tmp_path / "Cyclone One" / "CycloneAgentMCP.exe"
    one_exe.parent.mkdir(parents=True, exist_ok=True)
    one_exe.write_text("", encoding="utf-8")
    written = write_cursor_mcp_json(
        command=str(tmp_path / "Cyclone PC Companion" / "CycloneAgentMCP.exe"),
        url="http://127.0.0.1:8765",
        port=8765,
        runtime=str(tmp_path / "Cyclone One" / "runtime"),
    )
    assert written["legacyCompanion"] is False
    payload = json.loads(Path(written["path"]).read_text(encoding="utf-8"))
    assert payload["mcpServers"][CURSOR_SERVER_KEY]["command"] == str(one_exe)


def test_settings_from_env_loads_persisted_bearer(monkeypatch, tmp_path):
    _isolate(monkeypatch, tmp_path)
    persist_runtime_bearer(
        "settings-bearer",
        "http://127.0.0.1:41234",
        write_cursor=False,
    )
    monkeypatch.delenv("CYCLONE_DEVICE_GATEWAY_TOKEN", raising=False)
    monkeypatch.setenv("CYCLONE_DESKTOP_PAIRING_BOOTSTRAP", "1")
    from cyclone_device_gateway.config import Settings

    loaded = Settings.from_env()
    assert loaded.token == "settings-bearer"
    assert loaded.port == 41234


def test_legacy_companion_beside_one_warns(monkeypatch, tmp_path):
    _isolate(monkeypatch, tmp_path)
    (tmp_path / PRODUCT_ONE).mkdir()
    (tmp_path / PRODUCT_LEGACY_COMPANION).mkdir()
    report = detect_legacy_companion()
    assert report["present"] is True
    assert report["besideOne"] is True
    assert report["warn"] is True
    assert "3.8" in (report["versionHint"] or "") or "Prefer" in report["detail"]
