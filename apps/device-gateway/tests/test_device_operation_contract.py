from __future__ import annotations

import time
from pathlib import Path
from types import SimpleNamespace

from fastapi.testclient import TestClient

from cyclone_device_gateway.adb.client import ADBDevice, ADBError
from cyclone_device_gateway.config import Settings
from cyclone_device_gateway.cyclone_bridge.client import BridgeOperationError
from cyclone_device_gateway.desktop_runtime.api import DesktopRuntime, create_desktop_app
from cyclone_device_gateway.desktop_runtime.fleet import DeviceFleetManager
from cyclone_device_gateway.desktop_runtime.models import DeviceFleetState, deterministic_device_id
from cyclone_device_gateway.desktop_runtime.readiness import enrich_device_public


class Inventory:
    def __init__(self, devices):
        self.current = list(devices)
        self.mappings = []
        self.removed_stale = []

    def devices(self):
        return list(self.current)

    def forward_mappings(self):
        return list(self.mappings)

    def remove_stale_forward(self, port):
        self.removed_stale.append(port)
        self.mappings = [item for item in self.mappings if item[1] != f"tcp:{port}"]


class TrackerStdout:
    def __init__(self):
        self._lines = [b"List of devices attached\n"]

    def readline(self):
        return self._lines.pop(0) if self._lines else b""


class TrackerProcess:
    def __init__(self):
        self.stdout = TrackerStdout()

    def terminate(self):
        return None

    def wait(self, timeout=None):
        return 0

    def kill(self):
        return None


class RecoveringTrackerInventory(Inventory):
    def __init__(self, devices):
        super().__init__(devices)
        self.tracker_calls = 0

    def start_track_devices(self):
        self.tracker_calls += 1
        if self.tracker_calls == 1:
            raise ADBError("tracker process exited")
        return TrackerProcess()


class DeviceAdb:
    def __init__(self, serial):
        self.serial = serial
        self.forward_calls = 0
        self.fail_forward = False

    def ensure_bridge_forward(self, _port):
        self.forward_calls += 1
        if self.fail_forward:
            self.fail_forward = False
            raise ADBError("Cyclone local forward port is already owned by another device")
        return True

    def remove_forward(self, _port):
        return None

    def shell(self, *args, timeout=15):
        if args[:2] == ("pm", "path"):
            return "package:/data/app/com.cyclone.mobile/base.apk\n"
        if args[:2] == ("dumpsys", "power"):
            return "mWakefulness=Awake\n"
        if args[:2] == ("wm", "size"):
            return "Physical size: 1080x2400\n"
        return ""


class FakeVideo:
    def snapshot(self):
        return {
            "data": b"bounded-frame",
            "codec": "image/jpeg",
            "width": 720,
            "height": 1280,
            "timestamp_ms": 1234,
            "sequence": 7,
        }

    def diagnostics(self):
        return {
            "activeProfiles": ["focus"],
            "framesByProfile": {"focus": 1},
            "failuresByProfile": {},
            "lastEvent": "server.frame.first",
            "lastFrameAvailable": True,
        }


class SemanticBridge:
    def __init__(self):
        self.observation = 0

    def request(self, op, args=None, request_id=None):
        if op == "bridge.status":
            return {"gatewayEnabled": True, "socketListening": True, "accessibilityConnected": True}
        if op == "observe.semantic":
            self.observation += 1
            return {
                "observationId": f"obs-{self.observation}",
                "pageKey": "HOME",
                "package": "com.android.launcher3",
                "activity": "Home",
                "accessibilityFingerprint": "home-fingerprint",
                "pageText": {"protocol": "cyclone-page-text-v1", "lines": [{"text": "Home"}]},
                "pageSummary": {"protocol": "cyclone-page-summary-v1", "title": "Home"},
                "semanticControls": [{"elementId": "semantic:apps", "label": "Apps"}],
                "rawAccessibility": {"nodes": [{"id": "must-not-reach-compact"}]},
            }
        if op == "ui.search":
            return {"items": [{"elementId": "semantic:apps", "label": args["query"]}]}
        if op == "ui.element":
            return {"elementId": args["elementId"], "label": "Apps"}
        if op == "action.execute":
            return {
                "execution": {"ok": True},
                "androidExecution": {"ok": True},
                "verification": {"ok": True, "status": "PASSED", "semanticSuccessClaimed": True},
            }
        raise AssertionError(op)


class DelayedLaunchBridge(SemanticBridge):
    def __init__(self):
        super().__init__()
        self.action_seen = False
        self.post_action_observations = 0

    def request(self, op, args=None, request_id=None):
        if op == "action.execute":
            self.action_seen = True
            return {
                "execution": {"ok": True},
                "androidExecution": {"ok": True},
                "verification": {"ok": False, "status": "OBSERVED", "semanticSuccessClaimed": False},
            }
        if op == "observe.semantic" and self.action_seen:
            self.observation += 1
            self.post_action_observations += 1
            changed = self.post_action_observations >= 2
            return {
                "observationId": f"obs-{self.observation}",
                "pageKey": "SETTINGS" if changed else "HOME",
                "package": "com.android.settings" if changed else "com.android.launcher3",
                "activity": "Settings" if changed else "Home",
                "accessibilityFingerprint": "settings" if changed else "home-fingerprint",
                "pageText": {"protocol": "cyclone-page-text-v1", "lines": [{"text": "Settings" if changed else "Home"}]},
                "pageSummary": {"protocol": "cyclone-page-summary-v1", "title": "Settings" if changed else "Home"},
                "rawAccessibility": {"nodes": [{"id": "must-not-reach-action-after"}]},
            }
        return super().request(op, args, request_id)


def make_runtime(tmp_path, bridge=None):
    inventory = Inventory([ADBDevice("CONTRACT-1", "device", "Pixel_8")])
    adbs = {}

    def adb_factory(serial):
        return adbs.setdefault(serial, DeviceAdb(serial))

    fleet = DeviceFleetManager(inventory_adb=inventory, adb_factory=adb_factory, poll_seconds=20)
    fleet.refresh_once()
    session = fleet.get(deterministic_device_id("CONTRACT-1"))
    session.credential = "T" * 43
    session.bridge_ok = True
    session.bridge_gateway_enabled = True
    session.bridge_socket_listening = True
    session.accessibility_connected = True
    session.input_owner = "AI"
    session.video = FakeVideo()
    bridge = bridge or SemanticBridge()
    session.bridge = lambda token=None, auto_forward=False: bridge
    runtime = DesktopRuntime(Settings("gateway-secret", None, "adb", tmp_path), fleet=fleet)
    return runtime, session


def test_device_scoped_observe_search_inspect_act_and_screenshot_share_a_contract(tmp_path):
    runtime, session = make_runtime(tmp_path)
    app = create_desktop_app(runtime.settings, runtime)
    headers = {"Authorization": "Bearer gateway-secret"}

    with TestClient(app) as client:
        base = f"/v1/devices/{session.device_id}/agent"
        observed = client.post(f"{base}/observe", headers=headers, json={"include_screenshot": True})
        searched = client.get(f"{base}/ui/search?q=Apps", headers=headers)
        inspected = client.get(f"{base}/ui/element/semantic:apps", headers=headers)
        acted = client.post(
            f"{base}/action",
            headers=headers,
            json={"capability_id": "phone.home", "expected_observation_id": "obs-1"},
        )
        screenshot = client.get(f"{base}/screenshot", headers=headers)
        health = client.get(f"/v1/devices/{session.device_id}/health", headers=headers)
        discovery = client.get("/v1/diagnostics/discovery", headers=headers)

    for response, operation in (
        (observed, "observe"), (searched, "search"), (inspected, "inspect"),
        (acted, "act"), (screenshot, "screenshot"),
    ):
        assert response.status_code == 200
        body = response.json()
        assert body["deviceId"] == session.device_id
        assert body["device_id"] == session.device_id
        assert body["operation"] == operation
        assert body["deviceContract"] == {
            "version": "cyclone.desktop.device-operation.v1",
            "targetDeviceId": session.device_id,
        }
        assert body["capability"]["available"] is True

    screenshot_body = screenshot.json()
    assert screenshot_body["screenshot"]["available"] is True
    assert screenshot_body["artifact"]["kind"] == "LOCAL_FILE"
    assert Path(screenshot_body["artifact"]["reference"]).is_file()
    assert observed.json()["screenshot"]["artifact"]["kind"] == "LOCAL_FILE"
    assert observed.json()["observation"]["pageText"] == "Home"
    assert "Home" in observed.json()["observation"]["pageSummary"]
    assert observed.json()["observation"]["pageTextCard"]["protocol"] == "cyclone-page-text-v1"
    assert observed.json()["observation"]["pageSummaryCard"]["protocol"] == "cyclone-page-summary-v1"
    assert observed.json()["observation"]["compact"]["pageTextPreserved"] is True
    assert observed.json()["observation"]["compact"]["pageSummaryPreserved"] is True
    assert observed.json()["observation"]["compact"]["rawTreeExcluded"] is True
    assert "rawAccessibility" not in observed.json()["observation"]
    assert "rawAccessibility" not in acted.json()["after"]
    assert acted.json()["afterState"]["observationId"] == "obs-2"
    assert health.status_code == 200
    assert health.json()["health"]["planes"]["usbAuthorization"]["reasonCode"] == "USB_AUTHORIZED"
    assert {"gateway", "accessibility", "tokenSession", "semantic", "media"}.issubset(health.json()["health"]["planes"])
    assert discovery.json()["devices"][0]["deviceId"] == session.device_id


def test_page_transition_waits_for_delayed_android_after_state(tmp_path):
    bridge = DelayedLaunchBridge()
    runtime, session = make_runtime(tmp_path, bridge=bridge)
    runtime.agent._after_action_timeout_seconds = 0.05
    runtime.agent._after_action_poll_seconds = 0.01
    app = create_desktop_app(runtime.settings, runtime)
    headers = {"Authorization": "Bearer gateway-secret"}

    with TestClient(app) as client:
        base = f"/v1/devices/{session.device_id}/agent"
        observed = client.post(f"{base}/observe", headers=headers, json={})
        acted = client.post(
            f"{base}/action",
            headers=headers,
            json={
                "capability_id": "phone.open_app",
                "expected_observation_id": observed.json()["observation"]["observationId"],
                "params": {"package": "com.android.settings"},
            },
        )

    assert acted.status_code == 200
    assert bridge.post_action_observations >= 2
    assert acted.json()["afterState"]["package"] == "com.android.settings"
    assert acted.json()["afterState"]["pageKey"] == "SETTINGS"
    assert acted.json()["after"]["compact"]["rawTreeExcluded"] is True
    assert "rawAccessibility" not in acted.json()["after"]


def test_screenshot_unavailable_is_device_scoped_and_explicit():
    session = SimpleNamespace(
        device_id="dev_no_media",
        credential=None,
        adb_device=SimpleNamespace(state="device"),
    )
    fleet = SimpleNamespace(get=lambda device_id: session)
    from cyclone_device_gateway.desktop_runtime.agent import DesktopAgentService

    result = DesktopAgentService(fleet).screenshot("dev_no_media")

    assert result["deviceId"] == result["device_id"] == "dev_no_media"
    assert result["operation"] == "screenshot"
    assert result["capability"] == {"available": False, "reasonCode": "SCREENSHOT_CAPABILITY_UNAVAILABLE"}
    assert result["screenshot"]["artifact"] is None


def test_health_planes_keep_usb_gateway_accessibility_trust_semantic_and_media_independent():
    session = SimpleNamespace(
        device_id="dev_health",
        adb_device=SimpleNamespace(state="device"),
        credential="trusted",
        bridge_ok=True,
        bridge_last_error=None,
        bridge_error_class=None,
        bridge_gateway_enabled=True,
        bridge_socket_listening=True,
        accessibility_connected=False,
        pending_pairing=None,
        screen_awake=True,
        video=FakeVideo(),
        public=lambda: {"deviceId": "dev_health", "id": "dev_health", "state": "READY", "paired": True},
    )

    trust = enrich_device_public(session, {"sessionReady": True, "pcLabel": "DESK\u0007-PC" * 20})["trust"]
    assert trust["sessionReady"] is True and len(trust["pcLabel"]) == 80
    assert enrich_device_public(session, {"pcLabel": 7})["trust"]["pcLabel"] is None

    health = enrich_device_public(session)["health"]
    planes = health["planes"]
    assert health["version"] == "cyclone.device-health.v1"
    assert planes["usbAuthorization"]["reasonCode"] == "USB_AUTHORIZED"
    assert planes["gateway"]["reasonCode"] == "GATEWAY_CONNECTED"
    assert planes["accessibility"]["reasonCode"] == "ACCESSIBILITY_DISABLED"
    assert planes["tokenSession"]["reasonCode"] == "TOKEN_SESSION_MATCHED"
    assert planes["semantic"]["reasonCode"] == "SEMANTIC_ACCESSIBILITY_UNAVAILABLE"
    assert planes["media"]["reasonCode"] == "MEDIA_LIVE"

    session.bridge_ok = False
    session.bridge_last_error = "loopback bridge unavailable"
    degraded = enrich_device_public(session)["health"]["planes"]
    assert degraded["usbAuthorization"]["reasonCode"] == "USB_AUTHORIZED"
    assert degraded["gateway"]["reasonCode"] == "GATEWAY_UNAVAILABLE"


def test_tracker_reclaims_only_a_provably_stale_forward_and_auth_drift_fails_closed():
    inventory = Inventory([ADBDevice("RECOVER-1", "device")])
    adbs = {}

    def adb_factory(serial):
        return adbs.setdefault(serial, DeviceAdb(serial))

    fleet = DeviceFleetManager(inventory_adb=inventory, adb_factory=adb_factory, poll_seconds=20)
    fleet.refresh_once()
    session = fleet.get(deterministic_device_id("RECOVER-1"))
    inventory.mappings = [("DISCONNECTED-OLD", f"tcp:{session.local_port}", "localabstract:cyclone_gateway")]
    adbs["RECOVER-1"].fail_forward = True

    fleet.refresh_once()

    assert inventory.removed_stale == [session.local_port]
    assert adbs["RECOVER-1"].forward_calls >= 3

    session.credential = "stale-token"
    session.bridge = lambda token=None, auto_forward=False: SimpleNamespace(
        request=lambda *args, **kwargs: (_ for _ in ()).throw(BridgeOperationError("AUTH_REJECTED"))
    )
    fleet.refresh_once()

    assert session.credential is None
    assert session.state == DeviceFleetState.UNPAIRED
    assert session.bridge_error_class == "TOKEN_SESSION_MISMATCH"


def test_adb_tracker_recovers_after_exit_while_fallback_inventory_stays_authoritative():
    inventory = RecoveringTrackerInventory([ADBDevice("TRACKER-1", "device")])
    fleet = DeviceFleetManager(
        inventory_adb=inventory,
        adb_factory=lambda serial: DeviceAdb(serial),
        poll_seconds=20,
    )
    fleet._tracker_restart_delay_seconds = 0.01

    fleet.start()
    try:
        deadline = time.monotonic() + 2
        while time.monotonic() < deadline:
            diagnostic = fleet.diagnostics()
            if diagnostic["trackerRestarts"] >= 1 and diagnostic["lastScanSource"] == "adb-event":
                break
            time.sleep(0.01)
        else:
            raise AssertionError(f"tracker did not recover: {fleet.diagnostics()}")
    finally:
        fleet.stop()

    assert inventory.tracker_calls >= 2
    assert diagnostic["fleetDeviceCount"] == 1
    assert diagnostic["authorizedAdbDeviceCount"] == 1
