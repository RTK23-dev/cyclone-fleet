from __future__ import annotations

from types import SimpleNamespace
from pathlib import Path

from fastapi.testclient import TestClient

from cyclone_device_gateway.cloud_control.service import SECRET_FIELD_NAMES, strip_secrets
from cyclone_device_gateway.desktop_runtime.api import create_desktop_app
from cyclone_device_gateway.desktop_runtime.models import DesktopRuntimeError, RuntimeErrorCode, deterministic_device_id
from cyclone_device_gateway.vmos import PHONE_MUTATION_ENGINE


class FakeAdb:
    def __init__(self, installed=True, running=True):
        self.installed = installed
        self.running = running
        self.calls = []

    def shell(self, *args, timeout=5):
        self.calls.append(args)
        if args[:2] == ("pm", "path"):
            return "package:/data/app/com.cyclone.mobile.apk" if self.installed else ""
        if args[:1] == ("pidof",):
            return "4321" if self.running else ""
        return ""


class FakeDevice:
    def __init__(self, serial="localhost:63670"):
        self.device_id = deterministic_device_id(serial)
        self.serial = serial
        self.adb_device = SimpleNamespace(state="device")
        self.adb = FakeAdb()
        self.credential = "paired-token"
        self.display_width = 1080
        self.display_height = 1920

    def public(self):
        return {"deviceId": self.device_id, "name": "VMOS pad-1", "id": self.device_id}


class FakeFleet:
    def __init__(self, device: FakeDevice):
        self.device = device

    def list_public(self):
        return [{"deviceId": self.device.device_id, "name": "VMOS pad-1"}]

    def get(self, device_id: str):
        if device_id != self.device.device_id:
            raise DesktopRuntimeError(RuntimeErrorCode.DEVICE_NOT_FOUND, "Device is not connected.")
        return self.device

    def find_by_serial(self, serial: str):
        return self.device if serial == self.device.serial else None


class FakeAgent:
    def __init__(self):
        self.actions = []
        self.observes = 0

    def observe(self, device_id, *, mode="compact", include_screenshot=False, payload=None):
        self.observes += 1
        return {
            "witness": {"observation_id": f"obs-{self.observes}"},
            "afterState": {"pageSummary": "Home launcher", "package": "com.android.launcher"},
            "screenshot": {"available": False},
        }

    def action(self, device_id, payload):
        self.actions.append(payload)
        return {"ok": True, "capability_id": payload.get("capability_id"), "connectKey": "MUST-NOT-LEAK"}


class FakeTrust:
    def status(self, device_id):
        return {"trusted": True, "sessionReady": True}


class FakeControls:
    def __init__(self):
        self.owner = "HUMAN"

    def set_owner(self, device_id, owner):
        self.owner = owner
        return {"ok": True, "inputOwner": owner}


class FakeRuntime:
    def __init__(self):
        self.fleet = FakeFleet(FakeDevice())
        self.agent = FakeAgent()
        self.trust = FakeTrust()
        self.controls = FakeControls()

    def start(self) -> None:
        return None

    def stop(self) -> None:
        return None


def _client(tmp_path: Path, runtime: FakeRuntime | None = None):
    from cyclone_device_gateway.config import Settings

    settings = Settings("gateway-secret", None, "adb", tmp_path)
    app = create_desktop_app(settings, runtime or FakeRuntime())
    return TestClient(app), runtime or app.state.desktop_runtime


def test_cloud_health_names_phonetoolexecutor(tmp_path):
    client, _ = _client(tmp_path)
    response = client.get("/cloud/v1/health")
    assert response.status_code == 200
    body = response.json()
    assert body["mutationEngine"] == PHONE_MUTATION_ENGINE
    assert body["architecture"]["provider_native_mutation_allowed"] is False
    assert "SESSION_TOKEN" in body["handoffFields"]
    assert "connectKey" not in body["handoffFields"]
    assert body["auth"] == "session-token-header-or-bearer"
    assert body["sessionHeader"] == "X-Cyclone-Session-Token"
    assert body["localBase"].rstrip("/").endswith("/cloud")


def test_cloud_root_exposes_local_base(tmp_path):
    client, _ = _client(tmp_path)
    response = client.get("/cloud")
    assert response.status_code == 200
    body = response.json()
    assert body["ok"] is True
    assert body["health"] == "/cloud/v1/health"
    assert body["localBase"].rstrip("/").endswith("/cloud")
    assert body["auth"] == "session-token-header-or-bearer"


def test_session_mint_and_observe_tap_use_session_bearer(tmp_path):
    runtime = FakeRuntime()
    client, _ = _client(tmp_path, runtime)
    device_id = runtime.fleet.device.device_id
    minted = client.post(
        "/cloud/v1/sessions",
        headers={"Authorization": "Bearer gateway-secret"},
        json={"deviceId": device_id, "ttlSeconds": 120},
    )
    assert minted.status_code == 200
    session = minted.json()
    assert session["sessionToken"]
    assert session["sessionId"]
    headers = {"Authorization": f"Bearer {session['sessionToken']}"}
    status = client.get(f"/cloud/v1/devices/{device_id}/status", headers=headers)
    assert status.status_code == 200
    assert status.json()["adb"] == "device"
    assert status.json()["mobileInstalled"] is True
    observe = client.post(
        f"/cloud/v1/devices/{device_id}/observe",
        headers=headers,
        json={"sessionId": session["sessionId"]},
    )
    assert observe.status_code == 200
    assert observe.json()["summary"] == "Home launcher"
    tap = client.post(
        f"/cloud/v1/devices/{device_id}/tap",
        headers=headers,
        json={"sessionId": session["sessionId"], "x": 0.5, "y": 0.4, "normalized": True},
    )
    assert tap.status_code == 200
    assert tap.json()["mutationEngine"] == PHONE_MUTATION_ENGINE
    assert runtime.agent.actions
    assert runtime.agent.actions[0]["capability_id"] == "phone.click"
    assert runtime.agent.actions[0]["request_ai_control"] is True
    dumped = tap.text + observe.text + status.text + minted.text
    assert "MUST-NOT-LEAK" not in dumped
    assert "connectKey" not in dumped
    assert "gateway-secret" not in dumped


def test_custom_gpt_can_use_pasted_session_token_header(tmp_path):
    runtime = FakeRuntime()
    client, _ = _client(tmp_path, runtime)
    device_id = runtime.fleet.device.device_id
    minted = client.post(
        "/cloud/v1/sessions",
        headers={"Authorization": "Bearer gateway-secret"},
        json={"deviceId": device_id, "ttlSeconds": 120},
    )
    assert minted.status_code == 200
    session = minted.json()
    headers = {"X-Cyclone-Session-Token": session["sessionToken"]}

    status = client.get(
        f"/cloud/v1/devices/{device_id}/status?sessionId={session['sessionId']}",
        headers=headers,
    )
    assert status.status_code == 200
    assert status.json()["gatewayReady"] is True
    assert status.json()["trustReady"] is True

    observe = client.post(
        f"/cloud/v1/devices/{device_id}/observe",
        headers=headers,
        json={"sessionId": session["sessionId"]},
    )
    assert observe.status_code == 200
    tap = client.post(
        f"/cloud/v1/devices/{device_id}/tap",
        headers=headers,
        json={"sessionId": session["sessionId"], "x": 12, "y": 34},
    )
    assert tap.status_code == 200
    assert runtime.agent.actions[-1]["capability_id"] == "phone.click"


def test_session_token_cannot_see_another_device(tmp_path):
    runtime = FakeRuntime()
    client, _ = _client(tmp_path, runtime)
    minted = client.post(
        "/cloud/v1/sessions",
        headers={"Authorization": "Bearer gateway-secret"},
        json={"deviceId": runtime.fleet.device.device_id},
    )
    headers = {"Authorization": f"Bearer {minted.json()['sessionToken']}"}
    other = client.get("/cloud/v1/devices/dev_other/status", headers=headers)
    assert other.status_code == 401


def test_cloud_control_rejects_missing_session_credential(tmp_path):
    client, _ = _client(tmp_path)
    response = client.get("/cloud/v1/devices")
    assert response.status_code == 401


def test_cloud_control_rejects_bad_session_header(tmp_path):
    client, _ = _client(tmp_path)
    response = client.get(
        "/cloud/v1/devices",
        headers={"X-Cyclone-Session-Token": "not-a-real-session"},
    )
    assert response.status_code == 401


def test_strip_secrets_drops_vmos_and_ssh_fields():
    payload = {
        "deviceId": "dev_1",
        "connectKey": "ssh-secret",
        "accessKey": "vmos-access",
        "secretAccessKey": "vmos-secret",
        "nested": {"vmosApiKey": "still-secret", "adb": "device"},
    }
    cleaned = strip_secrets(payload)
    assert cleaned["deviceId"] == "dev_1"
    assert cleaned["nested"]["adb"] == "device"
    for name in SECRET_FIELD_NAMES:
        assert name not in cleaned
        assert name not in cleaned["nested"]


def test_mint_by_serial_matches_fleet_device(tmp_path):
    runtime = FakeRuntime()
    client, _ = _client(tmp_path, runtime)
    minted = client.post(
        "/cloud/v1/sessions",
        headers={"Authorization": "Bearer gateway-secret"},
        json={"serial": "localhost:63670"},
    )
    assert minted.status_code == 200
    assert minted.json()["deviceId"] == runtime.fleet.device.device_id
