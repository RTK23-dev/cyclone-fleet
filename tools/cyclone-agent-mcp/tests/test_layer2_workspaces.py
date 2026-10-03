from __future__ import annotations

import pytest

from cyclone_agent_mcp.tool_catalog import TOOL_NAMES
from cyclone_agent_mcp.tools import PhoneTools


FG = {"session_id": "default-foreground", "display_id": 0}

SWITCH_PAYLOAD = {
    "execution": {"ok": True},
    "verification": {"ok": True, "status": "PASSED", "semanticSuccessClaimed": True},
    "workspaceId": "a",
    "workspaceGeneration": 4,
}


class FakeGateway:
    def __init__(self, payloads=None):
        self.calls = []
        self.payloads = payloads or {}

    def observe(self, device_id=None, include_screenshot=False, mode="compact", **kwargs):
        self.calls.append(("observe", device_id, kwargs))
        return {}

    def action(self, tool, params, goal, device_id=None, **kwargs):
        self.calls.append((tool, dict(params), goal, device_id, kwargs))
        if tool in self.payloads:
            return dict(self.payloads[tool])
        if str(tool).startswith("workspace."):
            return dict(SWITCH_PAYLOAD)
        return {"ok": True, "tool": tool, "params": params, "device_id": device_id}


def test_catalog_includes_phone_workspace():
    assert "phone_workspace" in TOOL_NAMES


def test_switch_then_click_without_workspace_ids_raises():
    gateway = FakeGateway()
    tools = PhoneTools(gateway=gateway)
    switched = tools.phone_workspace({"operation": "switch", "params": {"id": "a"}, **FG})
    assert switched["workspaceId"] == "a"
    assert switched["workspaceGeneration"] == 4
    with pytest.raises(ValueError, match="MUTATE_LOCK"):
        tools.phone_act({
            "tool": "phone.click",
            "params": {"elementId": "e1"},
            "goal": "Open Apps",
            **FG,
        })


def test_switch_then_click_forwards_matching_workspace_ids():
    gateway = FakeGateway()
    tools = PhoneTools(gateway=gateway)
    tools.phone_workspace({"operation": "switch", "params": {"id": "a"}, **FG})
    tools.phone_act({
        "tool": "phone.click",
        "params": {"elementId": "e1", "workspaceId": "a", "workspaceGeneration": 4},
        "goal": "Open Apps",
        **FG,
    })
    click_calls = [call for call in gateway.calls if call[0] == "phone.click"]
    assert len(click_calls) == 1
    forwarded = click_calls[0][1]
    assert forwarded["workspaceId"] == "a"
    assert forwarded["workspaceGeneration"] == 4
    assert forwarded["elementId"] == "e1"


def test_stale_generation_raises():
    gateway = FakeGateway()
    tools = PhoneTools(gateway=gateway)
    tools.phone_workspace({"operation": "switch", "params": {"id": "a"}, **FG})
    with pytest.raises(ValueError, match="stale generation"):
        tools.phone_act({
            "tool": "phone.click",
            "params": {"elementId": "e1", "workspaceId": "a", "workspaceGeneration": 3},
            "goal": "Open Apps",
            **FG,
        })


def test_named_session_phone_workspace_raises():
    tools = PhoneTools(gateway=FakeGateway())
    with pytest.raises(ValueError, match="default-foreground"):
        tools.phone_workspace({
            "operation": "switch",
            "params": {"id": "a"},
            "session_id": "workspace-a",
            "display_id": 7,
        })


def test_shell_param_raises():
    tools = PhoneTools(gateway=FakeGateway())
    with pytest.raises(ValueError):
        tools.phone_workspace({"operation": "switch", "params": {"shell": "bad"}, **FG})


def test_pause_clears_requirement():
    gateway = FakeGateway(payloads={
        "workspace.pause": {"execution": {"ok": True}, "holder": None},
    })
    tools = PhoneTools(gateway=gateway)
    tools.phone_workspace({"operation": "switch", "params": {"id": "a"}, **FG})
    tools.phone_workspace({"operation": "pause", "params": {}, **FG})
    result = tools.phone_act({
        "tool": "phone.click",
        "params": {"elementId": "e1"},
        "goal": "Open Apps",
        **FG,
    })
    assert result["ok"] is True
    click_calls = [call for call in gateway.calls if call[0] == "phone.click"]
    assert "workspaceId" not in click_calls[0][1]


def test_tool_is_discoverable():
    assert "phone_workspace" in TOOL_NAMES
