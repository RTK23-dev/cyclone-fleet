from __future__ import annotations

from types import SimpleNamespace

from fastapi import FastAPI
from fastapi.testclient import TestClient

from cyclone_device_gateway.api.stream_api import create_stream_router
from cyclone_device_gateway.desktop_runtime.api import create_desktop_router
from cyclone_device_gateway.desktop_runtime.readiness import enrich_device_public


class FakeVideo:
    def __init__(self, *, active=True, frames=1, failures=0, last_event="server.frame.first"):
        self.active = active
        self.frames = frames
        self.failures = failures
        self.last_event = last_event

    def diagnostics(self):
        return {
            "subscriberCount": 1 if self.active else 0,
            "activeProfiles": ["focus"] if self.active else [],
            "framesByProfile": {"thumbnail": 0, "focus": self.frames},
            "failuresByProfile": {"thumbnail": 0, "focus": self.failures},
            "lastEvent": self.last_event,
            "lastFrameAvailable": self.frames > 0,
        }

    def snapshot(self):
        return {
            "data": b"frame",
            "codec": "image/jpeg",
            "width": 720,
            "height": 1280,
            "timestamp_ms": 1234,
            "sequence": 7,
        }


class FakeSession:
    def __init__(self, *, credential=None, bridge_ok=True, bridge_error=None, adb_state="device", video=None):
        self.device_id = "dev_test"
        self.adb_device = SimpleNamespace(state=adb_state)
        self.credential = credential
        self.bridge_ok = bridge_ok
        self.bridge_last_error = bridge_error
        self.bridge_error_class = None
        self.pending_pairing = None
        self.screen_awake = True
        self.video = video or FakeVideo()

    def public(self):
        return {
            "deviceId": self.device_id,
            "id": self.device_id,
            "state": "ATTENTION" if self.bridge_ok is False else "READY",
            "name": "Pixel",
            "paired": self.credential is not None,
            "screen": "AWAKE",
            "lastSeenEpochMs": 1,
            "connectionLabel": "legacy",
            "video": {"mode": "SCREENSHOT", "width": 1080, "height": 2400, "rotationDegrees": 0},
            "capabilities": {"keyboard": bool(self.credential), "clipboard": bool(self.credential), "clipboardSync": False, "reconnect": True},
        }


def test_live_media_is_independent_from_ai_trust():
    session = FakeSession(credential=None, bridge_ok=True)
    public = enrich_device_public(session)

    assert public["state"] == "UNPAIRED"
    assert public["planes"] == {
        "discovery": "ADB_READY",
        "media": "LIVE",
        "bridge": "CONNECTED",
        "aiTrust": "UNPAIRED",
    }
    assert public["connectionLabel"] == "Screen connected · Allow AI control on phone"
    assert public["readiness"]["liveDisplay"]["ready"] is True
    assert public["readiness"]["aiCodexAccess"]["ready"] is False


def test_bridge_degradation_does_not_turn_live_phone_into_attention():
    session = FakeSession(
        credential="T" * 43,
        bridge_ok=False,
        bridge_error="Cyclone bridge temporarily unavailable",
    )
    public = enrich_device_public(session)

    assert public["state"] == "READY"
    assert public["planes"]["media"] == "LIVE"
    assert public["planes"]["bridge"] == "DEGRADED"
    assert public["planes"]["aiTrust"] == "TRUSTED"
    assert public["connectionLabel"] == "Screen live · AI bridge reconnecting"
    assert public["readiness"]["liveDisplay"]["ready"] is True
    assert public["readiness"]["aiCodexAccess"]["state"] == "RECOVERING"


def test_runtime_http_and_websocket_self_test_share_session_binding():
    runtime = SimpleNamespace(
        instance_id="instance-a",
        settings=SimpleNamespace(port=18765),
    )
    app = FastAPI()
    app.include_router(create_desktop_router(runtime, "session-secret"))
    client = TestClient(app)

    denied = client.get("/v1/runtime/self-test")
    assert denied.status_code in {401, 403}

    response = client.get(
        "/v1/runtime/self-test",
        headers={"Authorization": "Bearer session-secret"},
    )
    assert response.status_code == 200
    http_value = response.json()

    with client.websocket_connect(
        "/v1/runtime/self-test/ws",
        subprotocols=["cyclone-v1", "cyclone-token.session-secret"],
    ) as socket:
        ws_value = socket.receive_json()

    assert http_value["sessionBinding"] == ws_value["sessionBinding"]
    assert http_value["runtimePort"] == ws_value["runtimePort"] == 18765
    assert http_value["runtimeInstanceId"] == ws_value["runtimeInstanceId"] == "instance-a"


def test_stream_fallback_requires_adb_trust_not_ai_credential():
    session = FakeSession(credential=None, bridge_ok=False)
    fleet = SimpleNamespace(get=lambda device_id: session)
    runtime = SimpleNamespace(fleet=fleet)
    app = FastAPI()
    app.include_router(create_stream_router(runtime, "gateway-secret"))
    client = TestClient(app)

    response = client.get(
        "/v1/devices/dev_test/stream/snapshot?profile=focus",
        headers={"Authorization": "Bearer gateway-secret"},
    )
    assert response.status_code == 200
    assert response.content == b"frame"
    assert response.headers["x-cyclone-frame-codec"] == "image/jpeg"


def test_stream_fallback_still_requires_adb_authorization():
    session = FakeSession(credential=None, adb_state="unauthorized")
    fleet = SimpleNamespace(get=lambda device_id: session)
    runtime = SimpleNamespace(fleet=fleet)
    app = FastAPI()
    app.include_router(create_stream_router(runtime, "gateway-secret"))
    client = TestClient(app)

    response = client.get(
        "/v1/devices/dev_test/stream/snapshot",
        headers={"Authorization": "Bearer gateway-secret"},
    )
    assert response.status_code == 409
    assert response.json()["detail"]["code"] == "DEVICE_UNAUTHORIZED"
