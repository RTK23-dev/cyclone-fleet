from __future__ import annotations

from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

from cyclone_device_gateway.actions.envelope import extract_android_execution
from cyclone_device_gateway.actions.router import ActionRouter, ActionValidationError
from cyclone_device_gateway.auth import AuditLog
from cyclone_device_gateway.capabilities.models import CapabilityActionRequest, GatewayErrorCode
from cyclone_device_gateway.capabilities.service import CapabilityService
from cyclone_device_gateway.config import Settings
from cyclone_device_gateway.cyclone_bridge.client import BridgeOperationError
from cyclone_device_gateway.desktop_runtime.agent import DesktopAgentService
from cyclone_device_gateway.desktop_runtime.api import DesktopRuntime, create_desktop_app
from cyclone_device_gateway.desktop_runtime.fleet import DeviceFleetManager
from cyclone_device_gateway.desktop_runtime.models import DesktopRuntimeError, RuntimeErrorCode, deterministic_device_id
from cyclone_device_gateway.adb.client import ADBDevice
from cyclone_device_gateway.state.store import StateStore


PIXEL_OPEN_APP_SUCCESS = {
    # Pixel 3.8.1 action.execute result: nested execution.ok, no LayerOutcome on the
    # Desktop `execution` field. MCP previously classified that as PROTOCOL_MISMATCH
    # even though Android recentActions recorded ok=true and the page changed.
    "execution": {"ok": True, "error": None, "beforeFingerprint": "home", "afterFingerprint": "settings"},
    "androidExecution": {"ok": True},
    "verification": {
        "ok": True,
        "status": "PASSED",
        "semanticSuccessClaimed": True,
        "pageChanged": True,
    },
    "pageChanged": True,
}


class PixelOpenAppBridge:
    def __init__(self, action_result=None):
        self.action_result = action_result if action_result is not None else PIXEL_OPEN_APP_SUCCESS
        self.observation = 0
        self.last_action = None
        self.action_seen = False

    def request(self, op, args=None, request_id=None):
        if op == "bridge.status":
            return {"gatewayEnabled": True, "socketListening": True, "accessibilityConnected": True}
        if op == "action.execute":
            self.last_action = args
            if isinstance(self.action_result, Exception):
                raise self.action_result
            self.action_seen = True
            return self.action_result
        if op == "observe.semantic":
            self.observation += 1
            after = self.action_seen
            return {
                "observationId": f"obs-{self.observation}",
                "pageKey": "SETTINGS" if after else "HOME",
                "package": "com.android.settings" if after else "com.android.launcher3",
                "activity": "Settings" if after else "Home",
                "accessibilityFingerprint": "settings" if after else "home",
                "pageText": {"protocol": "cyclone-page-text-v1", "lines": [{"text": "Settings" if after else "Home"}]},
                "pageSummary": {"protocol": "cyclone-page-summary-v1", "title": "Settings" if after else "Home"},
            }
        raise AssertionError(op)


class Inventory:
    def __init__(self, devices):
        self.current = list(devices)

    def devices(self):
        return list(self.current)

    def forward_mappings(self):
        return []

    def remove_stale_forward(self, port):
        return None


class DeviceAdb:
    def __init__(self, serial):
        self.serial = serial

    def ensure_bridge_forward(self, _port):
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


class OneDeviceFleet:
    def __init__(self, bridge):
        self.session = SimpleNamespace(credential="credential", bridge=lambda: bridge)

    def get(self, device_id):
        assert device_id == "dev_test"
        return self.session


def make_runtime(tmp_path, bridge):
    inventory = Inventory([ADBDevice("PIXEL-ENVELOPE", "device", "Pixel_8")])
    adbs = {}

    def adb_factory(serial):
        return adbs.setdefault(serial, DeviceAdb(serial))

    fleet = DeviceFleetManager(inventory_adb=inventory, adb_factory=adb_factory, poll_seconds=20)
    fleet.refresh_once()
    session = fleet.get(deterministic_device_id("PIXEL-ENVELOPE"))
    session.credential = "T" * 43
    session.bridge_ok = True
    session.bridge_gateway_enabled = True
    session.bridge_socket_listening = True
    session.accessibility_connected = True
    session.input_owner = "AI"
    session.bridge = lambda token=None, auto_forward=False: bridge
    runtime = DesktopRuntime(Settings("gateway-secret", None, "adb", tmp_path), fleet=fleet)
    return runtime, session


def _canonical_ok(result: dict) -> None:
    assert result["protocol_version"] == "cyclone.gateway.capability.v1"
    assert result["ok"] is True
    assert result["transport"]["ok"] is True
    assert result["execution"]["ok"] is True
    assert result["verification"]["ok"] is True
    assert result["verification"]["passed"] is True
    assert result.get("error") in (None, {})


def test_pixel_nested_execution_is_not_protocol_mismatch_without_top_level_ok():
    """Reproduce the 3.8.1 Desktop blob that MCP classified as PROTOCOL_MISMATCH."""
    raw = PIXEL_OPEN_APP_SUCCESS
    assert "ok" not in raw or raw.get("ok") is not False
    # The pre-fix Desktop adapter stuffed this whole object into `execution`.
    assert "ok" not in raw  # no LayerOutcome ok at the Android result root
    parsed = extract_android_execution(raw)
    assert parsed is not None
    assert parsed["ok"] is True


def test_malformed_android_result_stays_protocol_mismatch():
    assert extract_android_execution({"tool": "phone.open_app", "pageChanged": True}) is None
    assert extract_android_execution({"execution": {"ok": "yes"}}) is None
    assert extract_android_execution("not-an-object") is None


def test_desktop_pixel_open_app_success_is_canonical_ok():
    service = DesktopAgentService(OneDeviceFleet(PixelOpenAppBridge()))
    result = service.action(
        "dev_test",
        {
            "capability_id": "phone.open_app",
            "expected_observation_id": "obs-before",
            "params": {"package": "com.android.settings"},
        },
    )
    _canonical_ok(result)
    assert result["execution"]["status"] == "android_succeeded"
    assert result["execution"]["androidExecution"]["ok"] is True
    assert result["afterState"]["pageKey"] == "SETTINGS"


def test_desktop_android_failure_remains_failure():
    service = DesktopAgentService(OneDeviceFleet(PixelOpenAppBridge({
        "execution": {"ok": False, "error": {"code": "ELEMENT_NOT_FOUND"}},
        "androidExecution": {"ok": False, "error": {"code": "ELEMENT_NOT_FOUND"}},
        "verification": {"ok": False, "status": "FAILED"},
    })))
    result = service.action(
        "dev_test",
        {"capability_id": "phone.open_app", "expected_observation_id": "obs-before"},
    )
    assert result["ok"] is False
    assert result["execution"]["ok"] is False
    assert result["verification"]["ok"] is False
    assert result["verification"]["passed"] is False
    assert result["error"]["code"] == "ELEMENT_NOT_FOUND"
    assert result["error"]["layer"] == "EXECUTION"


def test_desktop_verification_disagreement_is_fail_closed():
    service = DesktopAgentService(OneDeviceFleet(PixelOpenAppBridge({
        "execution": {"ok": True},
        "androidExecution": {"ok": True},
        "verification": {"ok": False, "status": "FAILED", "code": "VERIFICATION_FAILED"},
        "pageChanged": False,
    })))
    result = service.action(
        "dev_test",
        {"capability_id": "phone.home", "expected_observation_id": "obs-before"},
    )
    assert result["ok"] is False
    assert result["execution"]["ok"] is True
    assert result["verification"]["ok"] is False
    assert result["verification"]["passed"] is False
    assert result["error"]["code"] == "VERIFICATION_FAILED"
    assert result["error"]["layer"] == "VERIFICATION"


def test_desktop_malformed_android_envelope_is_protocol_mismatch():
    service = DesktopAgentService(OneDeviceFleet(PixelOpenAppBridge({
        "pageChanged": True,
        "verification": {"ok": True, "status": "PASSED"},
    })))
    result = service.action(
        "dev_test",
        {"capability_id": "phone.open_app", "expected_observation_id": "obs-before"},
    )
    assert result["ok"] is False
    assert result["execution"]["ok"] is False
    assert result["error"]["code"] == "PROTOCOL_MISMATCH"
    assert result["error"]["layer"] == "PROTOCOL"


def test_desktop_stale_ids_still_stale_observation():
    service = DesktopAgentService(OneDeviceFleet(PixelOpenAppBridge(
        BridgeOperationError("STALE_OBSERVATION"),
    )))
    with pytest.raises(DesktopRuntimeError) as raised:
        service.action(
            "dev_test",
            {"capability_id": "phone.click", "expected_observation_id": "obs-stale"},
        )
    assert raised.value.code == RuntimeErrorCode.STALE_OBSERVATION


def test_http_pixel_open_app_canonical_envelope(tmp_path):
    bridge = PixelOpenAppBridge()
    runtime, session = make_runtime(tmp_path, bridge)
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
    body = acted.json()
    _canonical_ok(body)
    assert body["afterState"]["package"] == "com.android.settings"


def make_router(tmp_path, bridge):
    store = StateStore(tmp_path / "db.sqlite")

    def observe():
        semantic = bridge.request("observe.semantic")
        oid = store.add_observation(semantic)
        return {**store.get_observation(oid), "device_serial": "PIXEL8"}

    return ActionRouter(bridge, store, AuditLog(tmp_path / "audit.jsonl"), observe, stabilize=lambda: None), store


def test_action_router_pixel_success_without_nested_ok_at_result_root(tmp_path):
    bridge = PixelOpenAppBridge({
        "ok": True,
        "androidExecution": {"ok": True},
        "verification": {"ok": True, "status": "PASSED", "pageChanged": True},
        "pageChanged": True,
    })
    router, store = make_router(tmp_path, bridge)
    store.add_observation(bridge.request("observe.semantic"))
    result = router.execute(
        tool="phone.open_app",
        params={"package": "com.android.settings"},
        goal="Open Settings",
    )
    assert result["success"] is True
    assert result["execution_ok"] is True
    assert result["verification_ok"] is True
    assert result["error_class"] is None


def test_action_router_android_failure_remains_failure(tmp_path):
    bridge = PixelOpenAppBridge({
        "execution": {"ok": False, "error": {"code": "POLICY_DENIED"}},
        "verification": {"ok": False, "status": "FAILED"},
    })
    router, store = make_router(tmp_path, bridge)
    store.add_observation(bridge.request("observe.semantic"))
    result = router.execute(tool="phone.open_app", params={"package": "com.android.settings"})
    assert result["success"] is False
    assert result["execution_ok"] is False
    assert result["error_class"] == "POLICY_DENIED"


def test_action_router_stale_element_id_is_stale_observation(tmp_path):
    bridge = PixelOpenAppBridge()
    router, store = make_router(tmp_path, bridge)
    store.add_observation(bridge.request("observe.semantic"))
    with pytest.raises(ActionValidationError) as raised:
        router.execute(tool="phone.click", params={"elementId": "semantic:stale:gone"})
    assert raised.value.code == "STALE_OBSERVATION"


def test_capability_service_maps_stale_element_to_stale_observation():
    class Router:
        def execute(self, **kwargs):
            raise ActionValidationError("stale element", code="STALE_OBSERVATION")

    class Store:
        def current_observation(self):
            return {
                "id": "gateway-record",
                "semantic": {"observationId": "obs-current", "pageKey": "HOME", "package": "com.test"},
            }

    result = CapabilityService(Router(), Store()).execute(
        CapabilityActionRequest(
            correlation_id="correlation-test",
            capability_id="phone.click",
            params={"selector": {"resourceId": "id/apps"}},
            expected_observation_id="obs-current",
        )
    )
    assert result.ok is False
    assert result.error.code == GatewayErrorCode.STALE_OBSERVATION
    assert result.error.layer.value == "PROTOCOL"

@pytest.mark.parametrize("verification", [
    {"ok": True, "status": "OBSERVED", "semanticSuccessClaimed": False},
    {"ok": False, "status": "FAILED"},
    None,
])
def test_back_dispatch_and_changed_page_cannot_override_missing_or_negative_verification(tmp_path, verification):
    payload = {"execution": {"ok": True, "beforeFingerprint": "before", "afterFingerprint": "after"}}
    if verification is not None:
        payload["verification"] = verification
    bridge = PixelOpenAppBridge(payload)
    router, _ = make_router(tmp_path, bridge)
    result = router.execute(tool="phone.back", params={}, goal="Go back")
    assert bridge.last_action["tool"] == "phone.back"
    assert result["execution_ok"] is True
    assert result["verification_ok"] is False
    assert result["success"] is False


def test_back_with_authoritative_android_postcondition_succeeds(tmp_path):
    router, _ = make_router(tmp_path, PixelOpenAppBridge(PIXEL_OPEN_APP_SUCCESS))
    assert router.execute(tool="phone.back", params={}, goal="Go back")["success"] is True
