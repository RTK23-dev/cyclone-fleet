from __future__ import annotations

import json
from pathlib import Path

import pytest

from cyclone_device_gateway.adb.client import ADBClient, ADBDevice, ADBError
from cyclone_device_gateway.adb.connection import BridgeConnectionManager, BridgeReadinessError
from cyclone_device_gateway.cyclone_bridge.client import BridgeOperationError
from cyclone_device_gateway.doctor import BridgeDoctor, TOKEN_MISMATCH


class SelectionADB(ADBClient):
    def __init__(self, devices):
        self.serial = None
        self._devices = devices

    def devices(self):
        return list(self._devices)


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
        if device.state == "unauthorized":
            raise ADBError("ADB device unauthorized; accept the USB debugging prompt")
        self.serial = device.serial
        return device

    def shell(self, *args, timeout=15):
        if args[:2] == ("pm", "path"):
            return "package:/data/app/com.cyclone.mobile/base.apk\n"
        return ""

    def ensure_bridge_forward(self, port=8766):
        self.forward_calls += 1
        return self.forward_calls == 1

    def forward_mappings(self):
        return [("PIXEL8", "tcp:8766", "localabstract:cyclone_gateway")]


class ToggleBridge:
    def __init__(self):
        self.connected = True
        self.error_code = None

    def request(self, op, args=None):
        if self.error_code:
            raise BridgeOperationError(self.error_code)
        if not self.connected:
            raise OSError("usb disconnected")
        assert op == "bridge.status"
        return {
            "gatewayEnabled": True,
            "socketListening": True,
            "accessibilityConnected": True,
            "capabilities": {"phoneTools": ["phone.observe", "phone.click"]},
        }


def test_multiple_adb_devices_require_explicit_serial():
    adb = SelectionADB([ADBDevice("A", "device"), ADBDevice("B", "device")])
    with pytest.raises(ADBError, match="Multiple authorized"):
        adb.select_device()
    assert adb.select_device("B").serial == "B"


def test_unauthorized_device_has_clear_error():
    adb = SelectionADB([ADBDevice("PIXEL8", "unauthorized", "Pixel_8")])
    with pytest.raises(ADBError, match="unauthorized"):
        adb.select_device()


def test_connection_manager_recovers_after_disconnect_and_rechecks_forward():
    adb = ReadyADB()
    bridge = ToggleBridge()
    manager = BridgeConnectionManager(adb, bridge)

    bridge.connected = False
    with pytest.raises(BridgeReadinessError) as disconnected:
        manager.ensure_ready()
    assert disconnected.value.code == "DEVICE_DISCONNECTED"

    bridge.connected = True
    ready = manager.ensure_ready()
    assert ready.device.serial == "PIXEL8"
    assert ready.bridge_status["accessibilityConnected"] is True
    assert adb.forward_calls == 2


def test_connection_manager_maps_android_token_rejection():
    bridge = ToggleBridge()
    bridge.error_code = "AUTH_REJECTED"
    with pytest.raises(BridgeReadinessError) as rejected:
        BridgeConnectionManager(ReadyADB(), bridge).ensure_ready()
    assert rejected.value.code == "AUTH_REJECTED"


def test_doctor_reports_token_mismatch_without_printing_tokens(monkeypatch):
    secret = "android-secret-that-must-not-leak"
    pc_secret = "pc-secret-that-must-not-leak"
    adb = ReadyADB()

    class RejectingBridge:
        def request(self, op, args=None):
            raise BridgeOperationError("AUTH_REJECTED")

    doctor = BridgeDoctor(
        {
            "CYCLONE_ANDROID_BRIDGE_TOKEN": secret,
            "CYCLONE_DEVICE_GATEWAY_TOKEN": pc_secret,
        },
        adb=adb,
        bridge_factory=lambda *args, **kwargs: RejectingBridge(),
    )
    monkeypatch.setattr(doctor, "_pc_gateway_check", lambda: ("ERROR", "not running in test"))
    report = doctor.run()
    serialized = json.dumps(report)
    assert report["checks"]["Authentication"]["status"] == TOKEN_MISMATCH
    assert secret not in serialized
    assert pc_secret not in serialized
    assert report["security"]["generic_shell_exposed"] is False
    assert report["security"]["arbitrary_adb_exposed"] is False


def test_windows_setup_scripts_have_dry_run_and_do_not_persist_android_token():
    repo = Path(__file__).resolve().parents[3]
    setup = (repo / "scripts" / "phone-gateway" / "setup-cyclone-bridge.ps1").read_text(encoding="utf-8")
    start = (repo / "scripts" / "phone-gateway" / "start-cyclone-bridge.ps1").read_text(encoding="utf-8")
    assert "[switch]$DryRun" in setup
    assert "Export-Clixml" in setup
    assert "pc-token.clixml" in setup
    assert "Android token remains session-only" in start
    assert "Set-Content -Path $TokenFile" not in start
    assert "localabstract:cyclone_gateway" in setup
    assert "0.0.0.0" not in setup + start
    assert "adb shell" not in setup.lower() + start.lower()
