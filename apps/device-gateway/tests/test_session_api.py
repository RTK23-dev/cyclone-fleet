from __future__ import annotations

import base64
import queue
from types import SimpleNamespace

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from cyclone_device_gateway.api.stream_api import create_stream_router
from cyclone_device_gateway.cyclone_bridge.client import BridgeOperationError
from cyclone_device_gateway.desktop_runtime.agent import DesktopAgentService
from cyclone_device_gateway.desktop_runtime.events import FleetEventBroker
from cyclone_device_gateway.desktop_runtime.models import DesktopRuntimeError, RuntimeErrorCode
from cyclone_device_gateway.desktop_runtime.sessions import ExecutionSessionService
from cyclone_device_gateway.execution_scope import (
    DEFAULT_FOREGROUND_SESSION_ID,
    classify_session_plane,
    parse_execution_identity,
)


_PNG_1X1 = (
    b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01"
    b"\x08\x06\x00\x00\x00\x1f\x15\xc4\x89\x00\x00\x00\rIDAT\x08\xd7c\xf8\xcf\xc0\xf0\x1f\x00\x05\x00\x01\xff\x89\x99"
    b"=\x1d\x00\x00\x00\x00IEND\xaeB`\x82"
)


class SessionBridge:
    def __init__(self, *, list_unavailable: bool = False, workspace_display: int = 7):
        self.calls = []
        self.list_unavailable = list_unavailable
        self.workspace_display = workspace_display
        self.sessions = {
            DEFAULT_FOREGROUND_SESSION_ID: {
                "sessionId": DEFAULT_FOREGROUND_SESSION_ID,
                "displayId": 0,
                "state": "FOREGROUND",
                "executable": True,
            },
        }
        self.started = 0
        self.observation = 0

    def request(self, op, args=None, request_id=None):
        args = dict(args or {})
        self.calls.append((op, args))
        if op == "session.list":
            if self.list_unavailable:
                raise BridgeOperationError("CAPABILITY_UNAVAILABLE")
            return {"sessions": list(self.sessions.values())}
        if op == "session.start":
            self.started += 1
            session_id = f"workspace-{self.started}"
            descriptor = {
                "sessionId": session_id,
                "displayId": 11,
                "state": "ACTIVE",
                "executable": True,
                "targetPackage": args.get("package"),
            }
            self.sessions[session_id] = descriptor
            return dict(descriptor)
        if op == "session.status":
            session_id = str(args.get("sessionId") or "")
            if session_id == "workspace-zero":
                return {"sessionId": session_id, "displayId": 0, "state": "ACTIVE", "executable": True}
            found = self.sessions.get(session_id)
            if found is None:
                raise BridgeOperationError("STALE_SESSION")
            return dict(found)
        if op == "session.stop":
            session_id = str(args.get("sessionId") or "")
            previous = self.sessions.pop(session_id, {"sessionId": session_id, "displayId": 11, "state": "STOPPED"})
            return {**previous, "state": "STOPPED"}
        if op == "session.pause":
            return {**self.sessions[args["sessionId"]], "state": "PAUSED"}
        if op == "session.continue":
            return {**self.sessions[args["sessionId"]], "state": "ACTIVE"}
        if op == "session.handoff":
            return {**self.sessions[args["sessionId"]], "state": "HANDED_OFF"}
        if op == "observe.semantic":
            self.observation += 1
            context = args.get("executionContext") or args
            return {
                "observationId": f"obs-{self.observation}",
                "sessionId": context.get("sessionId"),
                "displayId": context.get("displayId"),
                "pageKey": "HOME",
                "package": "com.android.settings",
            }
        if op == "session.snapshot":
            context = args.get("executionContext") or {}
            return {
                "sessionId": context.get("sessionId"),
                "displayId": context.get("displayId"),
                "foregroundSubstitution": False,
                "pngBase64": base64.b64encode(_PNG_1X1).decode("ascii"),
                "width": 1,
                "height": 1,
            }
        if op == "action.execute":
            return {"execution": {"ok": True}, "verification": {"ok": True, "status": "PASSED"}}
        if op == "bridge.status":
            return {
                "gatewayEnabled": True,
                "socketListening": True,
                "accessibilityConnected": True,
                "executionSessions": [
                    {
                        "sessionId": DEFAULT_FOREGROUND_SESSION_ID,
                        "displayId": 0,
                        "state": "FOREGROUND",
                        "executable": True,
                    },
                    {
                        "sessionId": "share-1",
                        "displayId": 4,
                        "state": "SHARE",
                        "executable": False,
                        "package": "com.android.systemui",
                    },
                    {
                        "sessionId": "workspace-a",
                        "displayId": self.workspace_display,
                        "state": "ACTIVE",
                        "executable": True,
                        "targetPackage": "com.android.settings",
                    },
                ],
            }
        raise AssertionError(op)


def _session_and_fleet(bridge=None):
    bridge = bridge or SessionBridge()
    device = SimpleNamespace(
        device_id="dev_glass",
        credential="paired",
        input_owner="HUMAN",
        screen_awake=True,
        bridge_ok=True,
        last_heartbeat_ms=1,
        reconnect_attempts=0,
        next_reconnect_at_ms=None,
        bridge_last_error=None,
        bridge_error_class=None,
        bridge=lambda token=None, auto_forward=False: bridge,
    )
    fleet = SimpleNamespace(
        get=lambda device_id: device if device_id == device.device_id else (_ for _ in ()).throw(
            DesktopRuntimeError(RuntimeErrorCode.DEVICE_NOT_FOUND, "not found")
        ),
        events=FleetEventBroker(),
        record_bridge_status=lambda *_: None,
    )
    return device, fleet, bridge


def test_session_rest_list_start_stop_and_events():
    _device, fleet, bridge = _session_and_fleet()
    service = ExecutionSessionService(fleet)
    runtime_sessions = service

    class FakeRuntime:
        def __init__(self):
            self.fleet = fleet
            self.sessions = runtime_sessions

    app = FastAPI()
    app.include_router(create_stream_router(FakeRuntime(), "pc-secret"))
    client = TestClient(app)
    headers = {"Authorization": "Bearer pc-secret"}
    q = fleet.events.subscribe()
    listed = client.get("/v1/devices/dev_glass/sessions", headers=headers)
    assert listed.status_code == 200
    body = listed.json()
    assert body["sessions"][0]["sessionId"] == DEFAULT_FOREGROUND_SESSION_ID
    assert body["sessions"][0]["displayId"] == 0
    assert body["sessions"][0]["executable"] is True
    assert body["sessions"][0]["inputOwner"] == "HUMAN"
    assert body["sessions"][0]["plane"]["kind"] == "foreground"
    assert body["sessions"][0]["plane"]["label"] == "Foreground"
    assert body["sessions"][0]["plane"]["workspaceId"] is None
    added_foreground = q.get_nowait()
    assert added_foreground["event"] == "session.added"
    assert added_foreground["deviceId"] == "dev_glass"
    assert added_foreground["sessionId"] == DEFAULT_FOREGROUND_SESSION_ID
    assert added_foreground["session_id"] == DEFAULT_FOREGROUND_SESSION_ID
    assert added_foreground["displayId"] == 0
    assert added_foreground["inputOwner"] == "HUMAN"
    assert added_foreground["owner"] == "HUMAN"

    started = client.post(
        "/v1/devices/dev_glass/sessions",
        headers=headers,
        json={"package": "com.android.settings"},
    )
    assert started.status_code == 200
    session = started.json()["session"]
    assert session["sessionId"] == "workspace-1"
    assert session["displayId"] == 11
    assert session["displayId"] != 0
    assert session["inputOwner"] in {"AI", "HUMAN"}
    assert session["plane"]["kind"] == "session_kernel_vd"
    assert session["plane"]["sessionId"] == "workspace-1"
    assert session["plane"]["displayId"] == 11
    assert session["plane"]["workspaceId"] is None
    assert session["plane"]["workspaceGeneration"] is None
    assert session["plane"]["label"] == "Session Kernel VD"
    added = q.get_nowait()
    assert added["event"] == "session.added"
    assert added["sessionId"] == "workspace-1"
    assert added["session_id"] == "workspace-1"
    assert added["displayId"] == 11
    assert added["displayId"] > 0
    assert added["executable"] is True
    assert added["state"] == "ACTIVE"
    assert added["inputOwner"] in {"AI", "HUMAN"}
    assert added["owner"] == added["inputOwner"]
    assert added["plane"]["kind"] == "session_kernel_vd"

    stopped = client.post("/v1/devices/dev_glass/sessions/workspace-1/stop", headers=headers)
    assert stopped.status_code == 200
    removed = q.get_nowait()
    assert removed["event"] == "session.removed"
    assert removed["deviceId"] == "dev_glass"
    assert removed["sessionId"] == "workspace-1"
    assert removed["session_id"] == "workspace-1"


def test_session_list_synthetic_foreground_when_android_unavailable():
    _device, fleet, _bridge = _session_and_fleet(SessionBridge(list_unavailable=True))
    service = ExecutionSessionService(fleet)
    q = fleet.events.subscribe()
    result = service.list("dev_glass")
    assert result["sessions"] == [
        {
            "sessionId": DEFAULT_FOREGROUND_SESSION_ID,
            "displayId": 0,
            "state": "FOREGROUND",
            "inputOwner": "HUMAN",
            "executable": True,
            "kind": "FOREGROUND",
            "readOnly": False,
            "targetPackage": None,
            "plane": {
                "kind": "foreground",
                "sessionId": DEFAULT_FOREGROUND_SESSION_ID,
                "displayId": 0,
                "workspaceId": None,
                "workspaceGeneration": None,
                "label": "Foreground",
            },
        },
    ]
    event = q.get_nowait()
    assert event["event"] == "session.added"
    assert event["sessionId"] == DEFAULT_FOREGROUND_SESSION_ID
    assert event["session_id"] == DEFAULT_FOREGROUND_SESSION_ID
    assert event["displayId"] == 0
    assert event["executable"] is True
    assert event["inputOwner"] == "HUMAN"
    assert event["owner"] == "HUMAN"
    assert event["plane"]["kind"] == "foreground"


def test_list_delta_emits_removed_when_android_session_disappears():
    _device, fleet, bridge = _session_and_fleet()
    service = ExecutionSessionService(fleet)
    q = fleet.events.subscribe()
    started = service.start("dev_glass", "com.android.settings")
    session_id = started["session"]["sessionId"]
    while True:
        try:
            q.get_nowait()
        except queue.Empty:
            break
    bridge.sessions.pop(session_id)
    service.list("dev_glass")
    events = []
    while True:
        try:
            events.append(q.get_nowait())
        except queue.Empty:
            break
    removed = [item for item in events if item["event"] == "session.removed"]
    assert removed
    assert removed[0]["sessionId"] == session_id
    assert removed[0]["session_id"] == session_id
    assert removed[0]["deviceId"] == "dev_glass"


def test_named_workspace_display_zero_rejected():
    _device, fleet, _bridge = _session_and_fleet()
    service = ExecutionSessionService(fleet)
    with pytest.raises(DesktopRuntimeError) as raised:
        service.observe("dev_glass", "workspace-zero")
    assert raised.value.code in {
        RuntimeErrorCode.PROTOCOL_MISMATCH.value,
        RuntimeErrorCode.BACKGROUND_MODE_UNAVAILABLE.value,
    }
    with pytest.raises(ValueError):
        parse_execution_identity({"sessionId": "workspace-a", "displayId": 0})


def test_exact_session_snapshot_never_substitutes_display_zero():
    _device, fleet, _bridge = _session_and_fleet()
    service = ExecutionSessionService(fleet)
    started = service.start("dev_glass", "com.android.settings")
    session_id = started["session"]["sessionId"]

    class FakeRuntime:
        def __init__(self):
            self.fleet = fleet
            self.sessions = service

    app = FastAPI()
    app.include_router(create_stream_router(FakeRuntime(), "pc-secret"))
    client = TestClient(app)
    headers = {"Authorization": "Bearer pc-secret"}
    response = client.get(f"/v1/devices/dev_glass/sessions/{session_id}/snapshot", headers=headers)
    assert response.status_code == 200
    assert response.headers["x-cyclone-foreground-substitution"] == "false"
    assert response.headers["X-Cyclone-Foreground-Substitution".lower()] == "false"
    assert response.headers["x-cyclone-session-id"] == session_id
    assert response.headers["x-cyclone-display-id"] == "11"
    assert response.headers["x-cyclone-display-id"] != "0"
    assert response.content.startswith(b"\x89PNG\r\n\x1a\n")


def test_agent_status_lists_foreground_and_share_readonly():
    device, fleet, _bridge = _session_and_fleet()
    agent = DesktopAgentService(fleet)
    status = agent.status(device.device_id)
    sessions = {item["sessionId"]: item for item in status["sessions"]}
    assert sessions[DEFAULT_FOREGROUND_SESSION_ID]["kind"] == "FOREGROUND"
    assert sessions[DEFAULT_FOREGROUND_SESSION_ID]["executable"] is True
    assert sessions[DEFAULT_FOREGROUND_SESSION_ID]["readOnly"] is False
    assert sessions["share-1"]["kind"] == "BACKGROUND"
    assert sessions["share-1"]["executable"] is False
    assert sessions["share-1"]["readOnly"] is True
    assert sessions["workspace-a"]["displayId"] == 7
    assert sessions["workspace-a"]["executable"] is True
    assert sessions["workspace-a"]["readOnly"] is False
    assert status["inputOwner"] == "HUMAN"
    assert status["handoff"]["companionOwner"] == "HUMAN"


def test_classify_session_plane_labels_foreground_and_named_vd():
    named = classify_session_plane("workspace-a", 7)
    assert named == {
        "kind": "session_kernel_vd",
        "sessionId": "workspace-a",
        "displayId": 7,
        "workspaceId": None,
        "workspaceGeneration": None,
        "label": "Session Kernel VD",
    }
    foreground = classify_session_plane(DEFAULT_FOREGROUND_SESSION_ID, 0)
    assert foreground["kind"] == "foreground"
    assert foreground["label"] == "Foreground"
    assert foreground["displayId"] == 0
    assert classify_session_plane(DEFAULT_FOREGROUND_SESSION_ID)["kind"] == "foreground"
    with pytest.raises(ValueError):
        classify_session_plane("workspace-a", 0)
    with pytest.raises(ValueError):
        parse_execution_identity({"sessionId": "workspace-a", "displayId": 0})
