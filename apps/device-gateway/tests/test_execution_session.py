from types import SimpleNamespace

import pytest

from cyclone_device_gateway.desktop_runtime.agent import DesktopAgentService
from cyclone_device_gateway.desktop_runtime.models import DesktopRuntimeError, RuntimeErrorCode
from cyclone_device_gateway.execution_scope import parse_execution_identity


class RecordingBridge:
    def __init__(self):
        self.calls = []
        self.observation = 0

    def request(self, op, args=None, request_id=None):
        self.calls.append((op, dict(args or {})))
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
            }
        if op == "action.execute":
            return {
                "execution": {"ok": True},
                "androidExecution": {"ok": True},
                "verification": {"ok": True, "status": "PASSED", "semanticSuccessClaimed": True},
            }
        raise AssertionError(op)


def _agent(bridge=None):
    bridge = bridge or RecordingBridge()
    session = SimpleNamespace(
        credential="paired",
        bridge_ok=True,
        last_heartbeat_ms=1,
        reconnect_attempts=0,
        next_reconnect_at_ms=None,
        bridge_last_error=None,
        bridge_error_class=None,
        bridge=lambda token=None, auto_forward=False: bridge,
    )
    fleet = SimpleNamespace(get=lambda device_id: session, record_bridge_status=lambda *_: None)
    return DesktopAgentService(fleet), bridge


def test_parse_omitted_identity_is_none():
    assert parse_execution_identity({}) is None
    assert parse_execution_identity({"capability_id": "phone.click"}) is None


def test_agent_observe_forwards_session_identity():
    agent, bridge = _agent()
    result = agent.observe(
        "dev_a",
        payload={"session_id": "workspace-a", "display_id": 7},
    )
    assert bridge.calls[0] == ("observe.semantic", {"sessionId": "workspace-a", "displayId": 7})
    assert result["observation"]["sessionId"] == "workspace-a"
    assert result["observation"]["displayId"] == 7


def test_agent_action_forwards_identity_to_execute_and_after_observe():
    agent, bridge = _agent()
    agent.observe("dev_a", payload={"sessionId": "workspace-a", "displayId": 7})
    result = agent.action("dev_a", {
        "capability_id": "phone.home",
        "expected_observation_id": "obs-1",
        "session_id": "workspace-a",
        "display_id": 7,
        "params": {},
        "goal": "Go home",
    })
    ops = [op for op, _ in bridge.calls]
    assert ops[0] == "observe.semantic"
    assert ops[1] == "action.execute"
    assert ops[2] == "observe.semantic"
    execute_args = bridge.calls[1][1]
    assert execute_args["sessionId"] == "workspace-a"
    assert execute_args["displayId"] == 7
    assert execute_args["params"]["sessionId"] == "workspace-a"
    assert execute_args["params"]["displayId"] == 7
    after_args = bridge.calls[2][1]
    assert after_args == {"sessionId": "workspace-a", "displayId": 7}
    assert result["ok"] is True


def test_missing_display_for_workspace_session_fails_closed():
    agent, bridge = _agent()
    with pytest.raises(DesktopRuntimeError) as raised:
        agent.observe("dev_a", payload={"session_id": "workspace-a"})
    assert raised.value.code == RuntimeErrorCode.INVALID_REQUEST
    assert bridge.calls == []
    with pytest.raises(DesktopRuntimeError) as raised:
        agent.action("dev_a", {
            "capability_id": "phone.home",
            "expected_observation_id": "obs-1",
            "sessionId": "workspace-a",
            "displayId": 0,
        })
    assert raised.value.code == RuntimeErrorCode.INVALID_REQUEST


def test_omitted_identity_still_works_legacy():
    agent, bridge = _agent()
    observed = agent.observe("dev_a")
    assert bridge.calls[0] == ("observe.semantic", {})
    assert "sessionId" not in (observed.get("observation") or {})
    acted = agent.action("dev_a", {
        "capability_id": "phone.home",
        "expected_observation_id": observed["observation"]["observationId"],
        "params": {},
        "goal": "Go home",
    })
    execute_args = next(args for op, args in bridge.calls if op == "action.execute")
    assert "sessionId" not in execute_args
    assert "displayId" not in execute_args
    after_observe = [args for op, args in bridge.calls if op == "observe.semantic"][-1]
    assert after_observe == {}
    assert acted["ok"] is True
