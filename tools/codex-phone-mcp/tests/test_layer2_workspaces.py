import unittest

from cyclone_phone_mcp.mcp_server import TOOLS
from cyclone_phone_mcp.tools import PhoneTools, _validate_mcp_action_params


FG = {"session_id": "default-foreground", "display_id": 0}

SWITCH_PAYLOAD = {
    "execution": {"ok": True},
    "verification": {"ok": True, "status": "PASSED", "semanticSuccessClaimed": True},
    "workspaceId": "a",
    "workspaceGeneration": 4,
}

PAGE = {
    "observation": {
        "pageKey": "home",
        "title": "Home",
        "controls": [
            {"id": "e1", "elementId": "e1", "label": "Apps", "clickable": True, "elementIndex": 1},
        ],
    },
    "witness": {"observation_id": "obs-1"},
}


class FakeGateway:
    def __init__(self):
        self.calls = []

    def observe(self, **kwargs):
        self.calls.append(("observe", kwargs))
        return {}

    def action(self, tool, params, goal, **kwargs):
        self.calls.append((tool, params, kwargs))
        return {"execution": {"ok": False, "error": {"code": "POLICY_DENIED"}}}


class LeaseGateway:
    def __init__(self, payloads=None):
        self.calls = []
        self.payloads = payloads or {}

    def observe(self, **kwargs):
        self.calls.append(("observe", kwargs))
        return dict(PAGE)

    def action(self, tool, params, goal, **kwargs):
        self.calls.append((tool, dict(params), kwargs))
        if tool in self.payloads:
            return dict(self.payloads[tool])
        if str(tool).startswith("workspace."):
            return dict(SWITCH_PAYLOAD)
        return {
            "protocol_version": "cyclone.gateway.capability.v1",
            "capability_id": tool,
            "ok": True,
            "transport": {"ok": True},
            "execution": {"ok": True},
            "verification": {"ok": True, "status": "verified"},
            "error": None,
        }


def _click(tools, params):
    return tools.phone_act({
        "tool": "phone.click",
        "params": params,
        "goal": "Open Apps",
        **FG,
    })


class Layer2Tests(unittest.TestCase):
    def test_mcp_switch_keeps_scope_and_android_failure(self):
        gateway = FakeGateway()
        result = PhoneTools(gateway=gateway).phone_workspace({
            "operation": "switch", "params": {"id": "a"}, **FG,
        })
        self.assertFalse(result["execution"]["ok"])
        self.assertEqual(gateway.calls[0][0], "observe")
        self.assertEqual(gateway.calls[1][0], "workspace.switch")
        self.assertEqual(gateway.calls[1][1]["id"], "a")
        self.assertEqual(gateway.calls[1][2]["session_id"], "default-foreground")

    def test_no_shell_or_background_scope(self):
        tools = PhoneTools(gateway=FakeGateway())
        with self.assertRaises(ValueError):
            tools.phone_workspace({"operation": "switch", "params": {"shell": "bad"}, "session_id": "default-foreground"})
        with self.assertRaises(ValueError):
            tools.phone_workspace({"operation": "switch", "params": {"id": "a"}, "session_id": "background-a", "display_id": 2})

    def test_named_session_phone_workspace_raises(self):
        tools = PhoneTools(gateway=FakeGateway())
        with self.assertRaises(ValueError):
            tools.phone_workspace({"operation": "switch", "params": {"id": "a"}, "session_id": "workspace-a", "display_id": 7})

    def test_shell_param_raises(self):
        tools = PhoneTools(gateway=FakeGateway())
        with self.assertRaises(ValueError):
            tools.phone_workspace({"operation": "list", "params": {"shell": "id"}, **FG})

    def test_workspace_identity_preserves_semantic_validation(self):
        _validate_mcp_action_params("phone.click", {"elementId": "e1", "workspaceId": "a", "workspaceGeneration": 4})
        with self.assertRaises(ValueError):
            _validate_mcp_action_params("phone.click", {"workspaceId": "a", "workspaceGeneration": 4})
        with self.assertRaises(ValueError):
            _validate_mcp_action_params("phone.click", {"elementId": "e1", "workspaceId": "a", "workspaceGeneration": True})

    def test_tool_is_discoverable(self):
        tool = next(item for item in TOOLS if item["name"] == "phone_workspace")
        params = tool["inputSchema"]["properties"]["params"]
        self.assertEqual(params["additionalProperties"], False)
        self.assertEqual(params["properties"]["displayId"]["const"], 0)
        self.assertEqual(
            tool["inputSchema"]["properties"]["operation"]["enum"],
            ["list", "register", "switch", "pause", "release", "arm", "next"],
        )
        act = next(item for item in TOOLS if item["name"] == "phone_act")
        self.assertIn("workspaceId", act["inputSchema"]["properties"]["params"]["description"])
        self.assertIn("workspaceGeneration", act["inputSchema"]["properties"]["params"]["properties"])

    def test_switch_then_click_without_workspace_ids_raises(self):
        gateway = LeaseGateway()
        tools = PhoneTools(gateway=gateway)
        switched = tools.phone_workspace({"operation": "switch", "params": {"id": "a"}, **FG})
        self.assertEqual(switched["workspaceId"], "a")
        self.assertEqual(switched["workspaceGeneration"], 4)
        self.assertIn("Layer 2 is not a VD session", switched["next"])
        with self.assertRaises(ValueError) as raised:
            _click(tools, {"elementId": "e1"})
        self.assertIn("MUTATE_LOCK", str(raised.exception))

    def test_switch_then_click_forwards_matching_workspace_ids(self):
        gateway = LeaseGateway()
        tools = PhoneTools(gateway=gateway)
        tools.phone_workspace({"operation": "switch", "params": {"id": "a"}, **FG})
        _click(tools, {"elementId": "e1", "workspaceId": "a", "workspaceGeneration": 4})
        click_calls = [call for call in gateway.calls if call[0] == "phone.click"]
        self.assertEqual(len(click_calls), 1)
        forwarded = click_calls[0][1]
        self.assertEqual(forwarded["workspaceId"], "a")
        self.assertEqual(forwarded["workspaceGeneration"], 4)
        self.assertEqual(forwarded["elementId"], "e1")

    def test_stale_generation_raises(self):
        gateway = LeaseGateway()
        tools = PhoneTools(gateway=gateway)
        tools.phone_workspace({"operation": "switch", "params": {"id": "a"}, **FG})
        with self.assertRaises(ValueError) as raised:
            _click(tools, {"elementId": "e1", "workspaceId": "a", "workspaceGeneration": 3})
        self.assertIn("MUTATE_LOCK", str(raised.exception))
        self.assertIn("stale generation", str(raised.exception))

    def test_pause_clears_requirement(self):
        gateway = LeaseGateway(payloads={
            "workspace.pause": {"execution": {"ok": True}, "holder": None},
        })
        tools = PhoneTools(gateway=gateway)
        tools.phone_workspace({"operation": "switch", "params": {"id": "a"}, **FG})
        tools.phone_workspace({"operation": "pause", "params": {}, **FG})
        _click(tools, {"elementId": "e1"})
        click_calls = [call for call in gateway.calls if call[0] == "phone.click"]
        self.assertEqual(len(click_calls), 1)
        self.assertNotIn("workspaceId", click_calls[0][1])
