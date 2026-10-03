from __future__ import annotations

from fastapi.testclient import TestClient

from cyclone_device_gateway.desktop_runtime.api import create_desktop_app
from cyclone_device_gateway.desktop_runtime.models import RuntimeErrorCode
from cyclone_device_gateway.execution_scope import DEFAULT_FOREGROUND_SESSION_ID
from test_canonical_action_envelope import PIXEL_OPEN_APP_SUCCESS, make_runtime


class Layer2Bridge:
    def __init__(self):
        self.calls: list[tuple[str, dict]] = []
        self.generation = 0
        self.holder = None
        self.armed: list[str] = []
        self.gated = False
        self.fail_message = None
        self.observation = 0
        self.workspaces = [
            {
                "id": "mail",
                "label": "Mail",
                "appPackage": "com.google.android.gm",
                "androidUserId": 0,
                "displayId": 0,
                "state": "idle",
            }
        ]

    def request(self, op, args=None, request_id=None):
        args = dict(args or {})
        self.calls.append((op, args))
        if op == "bridge.status":
            return {
                "gatewayEnabled": True,
                "socketListening": True,
                "accessibilityConnected": True,
                "capabilities": {
                    "phoneTools": [
                        "phone.observe",
                        "phone.click",
                        "phone.open_app",
                        "workspace.list",
                        "workspace.register",
                        "workspace.switch",
                        "phone.workspace_switch",
                        "workspace.pause",
                        "workspace.release",
                        "workspace.arm",
                        "workspace.next",
                    ]
                },
            }
        if op == "observe.semantic":
            self.observation += 1
            return {
                "observationId": f"obs-{self.observation}",
                "pageKey": "INBOX" if self.holder else "HOME",
                "package": "com.google.android.gm" if self.holder else "com.android.launcher3",
                "activity": "Inbox" if self.holder else "Home",
                "accessibilityFingerprint": "mail" if self.holder else "home",
                "pageText": {"protocol": "cyclone-page-text-v1", "lines": [{"text": "Mail" if self.holder else "Home"}]},
                "pageSummary": {"protocol": "cyclone-page-summary-v1", "title": "Mail" if self.holder else "Home"},
            }
        if op == "action.execute":
            tool = str(args.get("tool") or "")
            if self.fail_message and tool != "workspace.list":
                return {
                    "execution": {
                        "ok": False,
                        "error": {"code": "CAPABILITY_UNAVAILABLE", "message": self.fail_message},
                    }
                }
            params = args.get("params") if isinstance(args.get("params"), dict) else {}
            if tool == "workspace.list":
                return self._status()
            if tool in {"workspace.switch", "phone.workspace_switch"}:
                self.generation += 1
                self.holder = params.get("id")
                self._mark_running()
                return {
                    "execution": {
                        "ok": True,
                        "payload": {
                            "workspaceId": self.holder,
                            "workspaceGeneration": self.generation,
                            "sessionId": DEFAULT_FOREGROUND_SESSION_ID,
                            "displayId": 0,
                            "verified": True,
                            "next": "phone.observe; include workspaceId and workspaceGeneration on every mutation",
                        },
                    },
                    "verification": {"ok": True, "status": "PASSED", "basis": "WORKSPACE_ENGINE_POSTCONDITION"},
                }
            if tool == "workspace.pause":
                self.holder = None
                self._mark_paused()
                return self._status()
            if tool == "workspace.release":
                self.holder = None
                self.armed = []
                self._mark_paused()
                return self._status()
            if tool == "workspace.register":
                entry = {
                    "id": params.get("id"),
                    "label": params.get("label"),
                    "appPackage": params.get("appPackage"),
                    "androidUserId": params.get("androidUserId", 0),
                    "displayId": 0,
                    "state": "idle",
                }
                self.workspaces = [item for item in self.workspaces if item.get("id") != entry["id"]]
                self.workspaces.append(entry)
                return self._status()
            if tool == "workspace.arm":
                workspace_id = params.get("id")
                if workspace_id and workspace_id not in self.armed:
                    self.armed.append(workspace_id)
                return self._status()
            if tool == "workspace.next":
                if not self.armed:
                    return {
                        "execution": {
                            "ok": False,
                            "error": {"code": "CAPABILITY_UNAVAILABLE", "message": "QUEUE_EMPTY: arm a workspace job first"},
                        }
                    }
                workspace_id = self.armed[0]
                self.generation += 1
                self.holder = workspace_id
                self._mark_running()
                return {
                    "execution": {
                        "ok": True,
                        "payload": {
                            "workspaceId": workspace_id,
                            "workspaceGeneration": self.generation,
                            "sessionId": DEFAULT_FOREGROUND_SESSION_ID,
                            "displayId": 0,
                            "verified": True,
                            "next": "phone.observe",
                            "goal": params.get("goal") or "",
                        },
                    }
                }
            if tool == "phone.click":
                return dict(PIXEL_OPEN_APP_SUCCESS)
        raise AssertionError(op)

    def _status(self):
        return {
            "execution": {
                "ok": True,
                "payload": {
                    "workspaces": list(self.workspaces),
                    "holder": self.holder,
                    "workspaceGeneration": self.generation if self.holder else None,
                    "armed": list(self.armed),
                    "gated": self.gated,
                    "root": "Unknown",
                    "displayId": 0,
                },
            }
        }

    def _mark_running(self):
        for item in self.workspaces:
            item["state"] = "running" if item.get("id") == self.holder else "paused"

    def _mark_paused(self):
        for item in self.workspaces:
            if item.get("state") == "running":
                item["state"] = "paused"


def _client(tmp_path, bridge=None):
    bridge = bridge or Layer2Bridge()
    runtime, session = make_runtime(tmp_path, bridge)
    app = create_desktop_app(runtime.settings, runtime)
    return TestClient(app), runtime, session, bridge


def _headers():
    return {"Authorization": "Bearer gateway-secret"}


def test_get_list_returns_layer2_protocol(tmp_path):
    client, _runtime, session, _bridge = _client(tmp_path)
    headers = _headers()
    with client:
        listed = client.get(f"/v1/devices/{session.device_id}/workspaces", headers=headers)
    assert listed.status_code == 200
    body = listed.json()
    assert body["protocol"] == "cyclone.one.layer2.v1"
    assert body["displayId"] == 0
    assert body["sessionId"] == DEFAULT_FOREGROUND_SESSION_ID
    assert body["plane"] == "layer2"
    assert body["workspaces"][0]["id"] == "mail"
    assert body["holder"] is None
    assert body["workspaceGeneration"] is None
    assert body["armed"] == []
    assert body["gated"] is False
    assert body["lockOwner"] is None


def test_post_switch_forwards_foreground_and_returns_generation(tmp_path):
    client, _runtime, session, bridge = _client(tmp_path)
    headers = _headers()
    with client:
        switched = client.post(
            f"/v1/devices/{session.device_id}/workspaces",
            headers=headers,
            json={"operation": "switch", "params": {"id": "mail"}},
        )
    assert switched.status_code == 200
    body = switched.json()
    assert body["workspaceId"] == "mail"
    assert body["workspaceGeneration"] == 1
    assert body["verified"] is True
    assert body["displayId"] == 0
    assert body["sessionId"] == DEFAULT_FOREGROUND_SESSION_ID
    forwarded = [args for op, args in bridge.calls if op == "action.execute" and args.get("tool") == "workspace.switch"]
    assert forwarded
    assert forwarded[0]["sessionId"] == DEFAULT_FOREGROUND_SESSION_ID
    assert forwarded[0]["displayId"] == 0
    assert forwarded[0]["source"] == "PC_CODEX"
    assert forwarded[0]["params"]["id"] == "mail"
    assert forwarded[0]["params"]["sessionId"] == DEFAULT_FOREGROUND_SESSION_ID
    assert forwarded[0]["params"]["displayId"] == 0


def test_post_pause_and_release(tmp_path):
    client, runtime, session, _bridge = _client(tmp_path)
    headers = _headers()
    path = f"/v1/devices/{session.device_id}/workspaces"
    with client:
        switched = client.post(path, headers=headers, json={"operation": "switch", "params": {"id": "mail"}})
        assert switched.status_code == 200
        assert runtime.layer2.lease(session.device_id)["workspaceId"] == "mail"
        paused = client.post(path, headers=headers, json={"operation": "pause", "params": {}})
        assert paused.status_code == 200
        assert paused.json()["holder"] is None
        assert runtime.layer2.lease(session.device_id) is None
        client.post(path, headers=headers, json={"operation": "switch", "params": {"id": "mail"}})
        released = client.post(path, headers=headers, json={"operation": "release", "params": {}})
        assert released.status_code == 200
        assert runtime.layer2.lease(session.device_id) is None


def test_rejects_shell_params(tmp_path):
    client, _runtime, session, _bridge = _client(tmp_path)
    headers = _headers()
    with client:
        blocked = client.post(
            f"/v1/devices/{session.device_id}/workspaces",
            headers=headers,
            json={"operation": "switch", "params": {"id": "mail", "shell": "id"}},
        )
    assert blocked.status_code == 400
    assert blocked.json()["detail"]["code"] == RuntimeErrorCode.INVALID_REQUEST.value


def test_rejects_named_session_and_nonzero_display(tmp_path):
    client, _runtime, session, _bridge = _client(tmp_path)
    headers = _headers()
    path = f"/v1/devices/{session.device_id}/workspaces"
    with client:
        named = client.post(
            path,
            headers=headers,
            json={"operation": "switch", "params": {"id": "mail", "sessionId": "workspace-a", "displayId": 2}},
        )
        display = client.post(
            path,
            headers=headers,
            json={"operation": "switch", "params": {"id": "mail", "displayId": 2}},
        )
    assert named.status_code == 400
    assert named.json()["detail"]["code"] == RuntimeErrorCode.INVALID_REQUEST.value
    assert display.status_code == 400
    assert display.json()["detail"]["code"] == RuntimeErrorCode.INVALID_REQUEST.value


def test_agent_click_without_lease_tokens_is_mutate_lock(tmp_path):
    client, _runtime, session, _bridge = _client(tmp_path)
    headers = _headers()
    path = f"/v1/devices/{session.device_id}/workspaces"
    action = f"/v1/devices/{session.device_id}/agent/action"
    with client:
        client.post(path, headers=headers, json={"operation": "switch", "params": {"id": "mail"}})
        blocked = client.post(
            action,
            headers=headers,
            json={"capability_id": "phone.click", "params": {"elementId": "e1"}},
        )
    assert blocked.status_code == 409
    assert blocked.json()["detail"]["code"] == RuntimeErrorCode.MUTATE_LOCK.value


def test_matching_generation_is_forwarded_on_click(tmp_path):
    client, _runtime, session, bridge = _client(tmp_path)
    headers = _headers()
    device = session.device_id
    with client:
        switched = client.post(
            f"/v1/devices/{device}/workspaces",
            headers=headers,
            json={"operation": "switch", "params": {"id": "mail"}},
        )
        generation = switched.json()["workspaceGeneration"]
        observed = client.post(f"/v1/devices/{device}/agent/observe", headers=headers, json={})
        acted = client.post(
            f"/v1/devices/{device}/agent/action",
            headers=headers,
            json={
                "capability_id": "phone.click",
                "expected_observation_id": observed.json()["observation"]["observationId"],
                "params": {
                    "elementId": "e1",
                    "workspaceId": "mail",
                    "workspaceGeneration": generation,
                },
            },
        )
    assert acted.status_code == 200
    clicks = [args for op, args in bridge.calls if op == "action.execute" and args.get("tool") == "phone.click"]
    assert clicks
    assert clicks[0]["params"]["workspaceId"] == "mail"
    assert clicks[0]["params"]["workspaceGeneration"] == generation


def test_stale_generation_fails_closed(tmp_path):
    client, _runtime, session, _bridge = _client(tmp_path)
    headers = _headers()
    device = session.device_id
    with client:
        client.post(
            f"/v1/devices/{device}/workspaces",
            headers=headers,
            json={"operation": "switch", "params": {"id": "mail"}},
        )
        stale = client.post(
            f"/v1/devices/{device}/agent/action",
            headers=headers,
            json={
                "capability_id": "phone.click",
                "params": {"elementId": "e1", "workspaceId": "mail", "workspaceGeneration": 0},
            },
        )
    assert stale.status_code == 409
    assert stale.json()["detail"]["code"] == RuntimeErrorCode.STALE_WORKSPACE.value


def test_gate_message_is_gate_not_capability_unavailable(tmp_path):
    bridge = Layer2Bridge()
    bridge.fail_message = "GATE: human review required"
    client, _runtime, session, _bridge = _client(tmp_path, bridge)
    headers = _headers()
    with client:
        blocked = client.post(
            f"/v1/devices/{session.device_id}/workspaces",
            headers=headers,
            json={"operation": "switch", "params": {"id": "mail"}},
        )
    assert blocked.status_code == 409
    assert blocked.json()["detail"]["code"] == RuntimeErrorCode.GATE.value
    assert blocked.json()["detail"]["code"] != RuntimeErrorCode.CAPABILITY_UNAVAILABLE.value


def test_target_mismatch_fails_closed(tmp_path):
    bridge = Layer2Bridge()
    bridge.fail_message = "TARGET_MISMATCH: package/user/display could not be verified; input remains paused"
    client, _runtime, session, _bridge = _client(tmp_path, bridge)
    headers = _headers()
    with client:
        blocked = client.post(
            f"/v1/devices/{session.device_id}/workspaces",
            headers=headers,
            json={"operation": "switch", "params": {"id": "mail"}},
        )
    assert blocked.status_code == 409
    assert blocked.json()["detail"]["code"] == RuntimeErrorCode.TARGET_MISMATCH.value


def test_fleet_workspace_is_not_layer2(tmp_path):
    client, _runtime, session, _bridge = _client(tmp_path)
    headers = _headers()
    with client:
        fleet = client.get("/v1/fleet/workspace", headers=headers)
        layer2 = client.get(f"/v1/devices/{session.device_id}/workspaces", headers=headers)
    assert fleet.status_code == 200
    body = fleet.json()
    assert "groups" in body
    assert "selectedDeviceIds" in body
    assert body.get("protocol") != "cyclone.one.layer2.v1"
    assert "schemaVersion" in body
    assert layer2.json()["protocol"] == "cyclone.one.layer2.v1"


def test_capabilities_advertise_workspace_tools(tmp_path):
    client, _runtime, session, _bridge = _client(tmp_path)
    headers = _headers()
    with client:
        caps = client.get(f"/v1/devices/{session.device_id}/agent/capabilities", headers=headers)
    assert caps.status_code == 200
    ids = {item["capability_id"] for item in caps.json()["capabilities"]}
    assert "workspace.list" in ids
    assert "workspace.switch" in ids
    assert "workspace.pause" in ids
    assert "phone.workspace_switch" in ids
