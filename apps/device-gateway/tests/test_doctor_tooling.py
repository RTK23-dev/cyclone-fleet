from __future__ import annotations

import json
from pathlib import Path
import types

from cyclone_device_gateway.adb.client import ADBDevice
from cyclone_device_gateway.cyclone_bridge.client import BridgeOperationError
from cyclone_device_gateway.doctor import (
    DEGRADED,
    ERROR,
    MISSING,
    OFF,
    READY,
    TOKEN_MISMATCH,
    UNAUTHORIZED,
    BROKEN,
    BridgeDoctor,
    format_human,
)
from cyclone_device_gateway.tooling_seam import (
    PRODUCT_LEGACY_COMPANION,
    PRODUCT_ONE,
    persist_runtime_bearer,
)

FAILING = {MISSING, UNAUTHORIZED, OFF, BROKEN, ERROR, TOKEN_MISMATCH}


class QuietADB:
    def available(self):
        return False

    def devices(self):
        return []


class ReadyADB:
    def __init__(self, state="device"):
        self.serial = None
        self.state = state
        self.forward_calls = 0

    def available(self):
        return True

    def devices(self):
        return [ADBDevice("PIXEL8", self.state, "Pixel_8")]

    def select_device(self, requested_serial=None):
        device = self.devices()[0]
        self.serial = device.serial
        return device

    def shell(self, *args, timeout=15):
        if args[:2] == ("pm", "path"):
            return "package:/data/app/com.cyclone.mobile/base.apk\n"
        return ""

    def ensure_bridge_forward(self, port=8766):
        self.forward_calls += 1
        return True

    def forward_mappings(self):
        return [("PIXEL8", "tcp:8766", "localabstract:cyclone_gateway")]


class ReadyBridge:
    def request(self, op, args=None):
        assert op == "bridge.status"
        return {
            "gatewayEnabled": True,
            "socketListening": True,
            "accessibilityConnected": True,
            "capabilities": {"phoneTools": ["phone.observe", "phone.click"]},
        }


def _isolate(monkeypatch, tmp_path: Path) -> None:
    monkeypatch.setenv("CYCLONE_TOOLING_TEST_ROOT", str(tmp_path))
    monkeypatch.setenv("LOCALAPPDATA", str(tmp_path))
    monkeypatch.setenv("CYCLONE_CURSOR_MCP_PATH", str(tmp_path / "cursor-mcp.json"))
    monkeypatch.delenv("CYCLONE_DEVICE_GATEWAY_TOKEN", raising=False)
    monkeypatch.delenv("CYCLONE_DEVICE_GATEWAY_URL", raising=False)
    monkeypatch.delenv("CYCLONE_DEVICE_GATEWAY_PORT", raising=False)
    monkeypatch.delenv("CYCLONE_DEVICE_GATEWAY_RUNTIME", raising=False)


def _stub_mcp_installed(monkeypatch) -> None:
    import cyclone_device_gateway.doctor as doctor_mod

    real_find_spec = doctor_mod.importlib.util.find_spec

    def fake_find_spec(name, package=None):
        if name == "cyclone_phone_mcp":
            return types.SimpleNamespace(name=name)
        return real_find_spec(name, package)

    monkeypatch.setattr(doctor_mod.importlib.util, "find_spec", fake_find_spec)


def test_doctor_uses_persisted_bearer_without_env_scrape(monkeypatch, tmp_path):
    _isolate(monkeypatch, tmp_path)
    token = "pc-secret-that-must-not-leak"
    persist_runtime_bearer(
        token,
        "http://127.0.0.1:43123",
        port=43123,
        runtime=tmp_path / "Cyclone One" / "runtime",
        mcp_executable=tmp_path / "Cyclone One" / "CycloneAgentMCP.exe",
        write_cursor=True,
    )
    monkeypatch.delenv("CYCLONE_DEVICE_GATEWAY_TOKEN", raising=False)
    monkeypatch.delenv("CYCLONE_DEVICE_GATEWAY_URL", raising=False)

    def boom(*args, **kwargs):
        raise OSError("gateway down")

    monkeypatch.setattr("cyclone_device_gateway.doctor.urllib.request.urlopen", boom)

    doctor = BridgeDoctor({}, adb=QuietADB())
    captured: dict[str, str] = {}
    real_check = doctor._pc_gateway_check

    def wrapped():
        status, detail = real_check()
        captured["status"] = status
        captured["detail"] = detail
        captured["token"] = doctor.env.get("CYCLONE_DEVICE_GATEWAY_TOKEN") or ""
        return status, detail

    monkeypatch.setattr(doctor, "_pc_gateway_check", wrapped)
    report = doctor.run()
    serialized = json.dumps(report)
    human = format_human(report)

    assert captured["token"] == token
    assert captured["detail"] != "PC Gateway token is not configured for this process"
    assert report["checks"]["PC Bearer"]["status"] == READY
    assert "sessionSecretPersisted=true" in report["checks"]["PC Bearer"]["detail"]
    assert report["sessionSecretPersisted"] is True
    assert report["product"] == PRODUCT_ONE
    assert report["security"]["tokens_in_output"] is False
    assert token not in serialized
    assert token not in human
    assert "PC Bearer" in human
    assert "Install Path" in human
    assert "Cursor MCP" in human
    assert "MCP Session" in human
    assert "session_id=default-foreground" in human
    assert human.index("PC Gateway") < human.index("PC Bearer") < human.index("Install Path") < human.index("Cursor MCP")


def test_doctor_legacy_companion_install_path_is_degraded_not_fatal(monkeypatch, tmp_path):
    _isolate(monkeypatch, tmp_path)
    token = "pc-secret-that-must-not-leak"
    persist_runtime_bearer(
        token,
        "http://127.0.0.1:43123",
        port=43123,
        runtime=tmp_path / "Cyclone One" / "runtime",
        mcp_executable=tmp_path / "Cyclone One" / "CycloneAgentMCP.exe",
        write_cursor=True,
    )
    monkeypatch.delenv("CYCLONE_DEVICE_GATEWAY_TOKEN", raising=False)
    (tmp_path / PRODUCT_ONE).mkdir(exist_ok=True)
    (tmp_path / PRODUCT_LEGACY_COMPANION).mkdir()
    _stub_mcp_installed(monkeypatch)

    doctor = BridgeDoctor(
        {"CYCLONE_ANDROID_BRIDGE_TOKEN": "android-secret-that-must-not-leak"},
        adb=ReadyADB(),
        bridge_factory=lambda *args, **kwargs: ReadyBridge(),
    )
    monkeypatch.setattr(doctor, "_pc_gateway_check", lambda: (READY, "Loopback HTTP gateway authenticated"))
    report = doctor.run()
    serialized = json.dumps(report)
    human = format_human(report)

    install = report["checks"]["Install Path"]
    assert install["status"] == DEGRADED
    assert install["status"] not in FAILING
    assert PRODUCT_LEGACY_COMPANION in install["detail"]
    assert report["overall"] == READY
    assert report["product"] == PRODUCT_ONE
    assert token not in serialized
    assert token not in human
    assert "android-secret-that-must-not-leak" not in serialized
    assert "android-secret-that-must-not-leak" not in human


def test_doctor_new_checks_exist_with_bare_env(monkeypatch):
    secret = "android-secret-that-must-not-leak"
    pc_secret = "pc-secret-that-must-not-leak"

    class RejectingBridge:
        def request(self, op, args=None):
            raise BridgeOperationError("AUTH_REJECTED")

    doctor = BridgeDoctor(
        {
            "CYCLONE_ANDROID_BRIDGE_TOKEN": secret,
            "CYCLONE_DEVICE_GATEWAY_TOKEN": pc_secret,
        },
        adb=ReadyADB(),
        bridge_factory=lambda *args, **kwargs: RejectingBridge(),
    )
    monkeypatch.setattr(doctor, "_pc_gateway_check", lambda: (ERROR, "not running in test"))
    report = doctor.run()
    serialized = json.dumps(report)
    human = format_human(report)

    for name in ("PC Bearer", "Install Path", "Cursor MCP", "MCP Session"):
        assert name in report["checks"]
        assert "status" in report["checks"][name]
        assert "detail" in report["checks"][name]
        assert pc_secret not in report["checks"][name]["detail"]
        assert secret not in report["checks"][name]["detail"]
    assert report["checks"]["PC Bearer"]["status"] == READY
    assert report["product"] == PRODUCT_ONE
    assert isinstance(report["sessionSecretPersisted"], bool)
    assert report["security"]["tokens_in_output"] is False
    assert secret not in serialized
    assert pc_secret not in serialized
    assert secret not in human
    assert pc_secret not in human
    assert "session_id=default-foreground" in human
    assert report["checks"]["MCP Session"]["status"] == READY


def test_doctor_mcp_session_documents_default_foreground(monkeypatch, tmp_path):
    _isolate(monkeypatch, tmp_path)
    doctor = BridgeDoctor({}, adb=QuietADB())
    report = doctor.run()
    serialized = json.dumps(report)
    human = format_human(report)
    session = report["checks"]["MCP Session"]
    assert session["status"] == READY
    assert "session_id=default-foreground" in session["detail"]
    assert "session_id=default-foreground" in human
    assert "MCP Session" in human
    assert "observe/act/locate/search/inspect/screenshot/skill_run/group_act" in session["detail"]
    assert "display 0" in session["detail"]
    assert "Layer 2" in session["detail"]
    assert "VD" in session["detail"]
    assert "PC Bearer" in human
    assert "Install Path" in human
    assert "Cursor MCP" in human
    assert report["security"]["tokens_in_output"] is False
    assert "Tokens are never printed by doctor." in human
    assert "Bearer" not in session["detail"]
    token_like = ("CYCLONE_DEVICE_GATEWAY_TOKEN", "CYCLONE_ANDROID_BRIDGE_TOKEN")
    for marker in token_like:
        assert marker not in human
        assert marker not in serialized

