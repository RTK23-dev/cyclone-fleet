from __future__ import annotations

from pathlib import Path
import time
import zipfile

import pytest
from fastapi.testclient import TestClient

from cyclone_device_gateway.adb.client import ADBDevice, ADBError
from cyclone_device_gateway.config import Settings
from cyclone_device_gateway.cyclone_bridge.client import BridgeDisconnectedError
from cyclone_device_gateway.desktop_runtime.api import DesktopRuntime, create_desktop_app
from cyclone_device_gateway.desktop_runtime.fleet import (
    MAX_RECONNECT_ATTEMPTS,
    DeviceFleetManager,
)
from cyclone_device_gateway.desktop_runtime.models import (
    DesktopRuntimeError,
    DeviceFleetState,
    deterministic_device_id,
)
from cyclone_device_gateway.desktop_runtime.pairing import PairingCoordinator


class Inventory:
    def __init__(self, devices):
        self.current = list(devices)

    def devices(self):
        return list(self.current)


class FakeDeviceADB:
    def __init__(self, serial):
        self.serial = serial
        self.shell_calls = []
        self.removed = []

    def shell(self, *args, timeout=15):
        self.shell_calls.append(args)
        if args[:2] == ("pm", "path"):
            return "package:/data/app/com.cyclone.mobile/base.apk\n"
        if args[:2] == ("dumpsys", "power"):
            return "mWakefulness=Awake\nDisplay Power: state=ON\n"
        if args[:2] == ("wm", "size"):
            return "Physical size: 1080x2400\n"
        if args[:2] == ("pidof", "com.cyclone.mobile"):
            return "12345\n"
        if args[:4] == ("settings", "get", "secure", "accessibility_enabled"):
            return "1\n"
        if args[:4] == ("settings", "get", "secure", "enabled_accessibility_services"):
            return "com.cyclone.mobile/.CycloneAccessibilityService\n"
        return ""

    def ensure_bridge_forward(self, port):
        return True

    def remove_forward(self, port):
        self.removed.append(port)

    def exec_out(self, *args, timeout=15):
        return b""

    def collect_cyclone_crash_diagnostics(self):
        return {
            "pid": "12345",
            "exit_info": "ApplicationExitInfo reason=REASON_CRASH",
            "crash_logcat": "FATAL EXCEPTION: cyclone-stability-test",
            "enabled_accessibility_services": "com.cyclone.mobile/.CycloneAccessibilityService",
            "accessibility_enabled": "1",
        }


class FakeBridge:
    def __init__(self, credentials=None):
        self.credentials = list(credentials or ["A" * 43])
        self.calls = []
        self.heartbeat_failures = 0
        self.fail_heartbeat = False

    def request(self, op, args=None, request_id=None):
        self.calls.append((op, args or {}))
        if op == "bridge.status":
            if self.fail_heartbeat:
                self.heartbeat_failures += 1
                raise BridgeDisconnectedError("simulated bridge drop")
            return {"gatewayEnabled": True, "socketListening": True, "accessibilityConnected": True}
        if op == "pair.revoke":
            return {"revoked": True}
        raise AssertionError(op)

    def request_unauthenticated(self, op, args=None, request_id=None):
        self.calls.append((op, args or {}))
        if op == "pair.begin":
            return {
                "challengeId": "ch-stable",
                "expiresAtMs": int(time.time() * 1000) + 60_000,
                "expiresInMs": 60_000,
            }
        if op == "pair.complete":
            return {"credential": self.credentials.pop(0), "paired": True}
        raise AssertionError(op)


class FailingBeginBridge(FakeBridge):
    def __init__(self):
        super().__init__()
        self.begin_failures = 0

    def request_unauthenticated(self, op, args=None, request_id=None):
        if op == "pair.begin":
            self.begin_failures += 1
            self.calls.append((op, args or {}))
            raise BridgeDisconnectedError("simulated pairing transport race")
        return super().request_unauthenticated(op, args, request_id)


def make_fleet(devices):
    inventory = Inventory(devices)
    adbs = {}

    def factory(serial):
        adbs.setdefault(serial, FakeDeviceADB(serial))
        return adbs[serial]

    fleet = DeviceFleetManager(
        inventory_adb=inventory,
        adb_factory=factory,
        poll_seconds=0.2,
        max_devices=16,
        max_workers=8,
    )
    return fleet, inventory, adbs


def paired_session(fleet, bridge=None):
    fleet.refresh_once()
    session = fleet.get(deterministic_device_id("SERIAL-1"))
    session.credential = "Z" * 43
    bridge = bridge or FakeBridge()
    session.bridge = lambda token=None, auto_forward=False: bridge
    return session, bridge


def test_paired_bridge_drop_enters_reconnecting_with_bounded_backoff_then_attention():
    fleet, _, _ = make_fleet([ADBDevice("SERIAL-1", "device", "Pixel_8")])
    session, bridge = paired_session(fleet)
    bridge.fail_heartbeat = True

    fleet.refresh_once()

    assert session.state == DeviceFleetState.DISCONNECTED
    assert session.reconnect_attempts == 1
    assert session.next_reconnect_at_ms > int(time.time() * 1000)
    assert session.bridge_ok is False
    health = session.public()["connectionHealth"]
    assert health["bridgeReachable"] is False
    assert health["reconnectAttempts"] == 1
    assert health["maxReconnectAttempts"] == MAX_RECONNECT_ATTEMPTS
    assert "simulated bridge drop" in health["lastError"]
    assert "attempt 1 of" in session.public()["connectionLabel"]

    for _ in range(MAX_RECONNECT_ATTEMPTS):
        fleet.refresh_once()

    assert session.state == DeviceFleetState.ATTENTION
    assert session.reconnect_attempts == MAX_RECONNECT_ATTEMPTS
    assert "not ready after" in session.last_safe_error

    bridge.fail_heartbeat = False
    fleet.refresh_once()

    assert session.state == DeviceFleetState.READY
    assert session.reconnect_attempts == 0
    assert session.bridge_ok is True
    assert session.public()["connectionHealth"]["bridgeReachable"] is True


def test_automatic_refreshes_respect_backoff_but_manual_scan_retries_immediately():
    fleet, inventory, _ = make_fleet([ADBDevice("SERIAL-1", "device", "Pixel_8")])
    session, bridge = paired_session(fleet)
    bridge.fail_heartbeat = True

    fleet.refresh_once(source="adb-event")
    assert session.reconnect_attempts == 1
    next_retry = session.next_reconnect_at_ms

    # An ADB event inside the backoff window must not fire another heartbeat.
    fleet.refresh_once(source="adb-event")
    assert session.reconnect_attempts == 1
    assert session.next_reconnect_at_ms == next_retry

    # A manual scan is an explicit user action and retries now.
    fleet.refresh_once(source="manual")
    assert session.reconnect_attempts == 2


def test_pair_begin_failure_message_includes_readiness_and_diagnostics(tmp_path):
    fleet, _, _ = make_fleet([ADBDevice("SERIAL-1", "device", "Pixel_8")])
    session, bridge = paired_session(fleet)
    session.credential = None
    session.bridge = lambda token=None, auto_forward=False: FailingBeginBridge()
    pairing = PairingCoordinator(fleet)
    pairing.BEGIN_RETRY_DELAY_SECONDS = 0
    pairing.diagnostics_dir = tmp_path

    with pytest.raises(DesktopRuntimeError) as err:
        pairing.begin(session.device_id)

    assert err.value.code == "DEVICE_DISCONNECTED"
    assert err.value.retryable is True
    assert "Readiness:" in err.value.safe_message
    assert "Diagnostic file:" in err.value.safe_message
    logs = list(tmp_path.glob("pairing-*.json"))
    assert len(logs) == 1


def test_pair_begin_success_reports_healthy_preflight():
    fleet, _, _ = make_fleet([ADBDevice("SERIAL-1", "device", "Pixel_8")])
    session, bridge = paired_session(fleet)
    session.credential = None
    pairing = PairingCoordinator(fleet)

    result = pairing.begin(session.device_id)

    assert result["pairing"] is True
    assert result["preflight"] == {
        "appRunning": True,
        "accessibilityEnabled": True,
        "accessibilityServiceConfigured": True,
    }
    assert "credential" not in result


def test_connection_bundle_includes_recent_fleet_events(tmp_path):
    fleet, _, _ = make_fleet([ADBDevice("SERIAL-1", "device", "Pixel_8")])
    fleet.refresh_once()
    session = fleet.get(deterministic_device_id("SERIAL-1"))
    settings = Settings("pc-secret", None, "adb", tmp_path)
    runtime = DesktopRuntime(settings, fleet=fleet)
    runtime.live_diagnostics.runtime_root = tmp_path
    app = create_desktop_app(settings, runtime)
    headers = {"Authorization": "Bearer pc-secret"}

    with TestClient(app) as client:
        bundle_response = client.post(
            f"/v1/devices/{session.device_id}/diagnostics/bundle",
            headers=headers,
        )
        assert bundle_response.status_code == 200
        bundle_path = Path(bundle_response.json()["path"])
        with zipfile.ZipFile(bundle_path) as archive:
            manifest = archive.read("connection-manifest.json").decode("utf-8")
            timeline = archive.read("timeline.jsonl").decode("utf-8")
    assert "recentFleetEvents" in manifest
    assert "DEVICE_ADDED" in manifest
    assert "connection.bundle.requested" in timeline
    assert "pc-secret" not in manifest + timeline
