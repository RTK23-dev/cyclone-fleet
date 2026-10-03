import json
import tempfile
import unittest

from cyclone_phone_mcp.mcp_server import McpServer, TOOLS
from cyclone_phone_mcp.reports import SessionRecorder
from cyclone_phone_mcp.session import (
    SESSION_DISPLAY_MISMATCH,
    SESSION_REQUIRED,
    SessionScopeError,
    classify_session_plane,
    parse_execution_scope,
    parse_tool_execution_scope,
    require_tool_execution_scope,
    scope_cache_key,
)
from cyclone_phone_mcp.tools import PhoneTools


class SessionParseTests(unittest.TestCase):
    def test_omitted_identity_is_none(self):
        self.assertIsNone(parse_execution_scope({}))
        self.assertIsNone(parse_execution_scope(None))
        self.assertIsNone(parse_execution_scope({"goal": "Open Apps"}))

    def test_default_foreground_optional_display_zero(self):
        self.assertEqual(
            parse_execution_scope({"session_id": "default-foreground"}),
            {"sessionId": "default-foreground", "displayId": 0},
        )
        self.assertEqual(
            parse_execution_scope({"sessionId": "default-foreground", "displayId": 0}),
            {"sessionId": "default-foreground", "displayId": 0},
        )

    def test_workspace_without_display_fails_closed(self):
        with self.assertRaises(ValueError):
            parse_execution_scope({"session_id": "workspace-a"})

    def test_workspace_display_zero_fails_closed(self):
        with self.assertRaises(ValueError):
            parse_execution_scope({"sessionId": "workspace-a", "displayId": 0})
        with self.assertRaises(ValueError):
            parse_execution_scope({"session_id": "workspace-a", "display_id": 0})

    def test_aliases_session_id_display_id_work(self):
        self.assertEqual(
            parse_execution_scope({"session_id": "workspace-a", "display_id": 7}),
            {"sessionId": "workspace-a", "displayId": 7},
        )
        self.assertEqual(
            parse_execution_scope({"sessionId": "workspace-b", "displayId": 8}),
            {"sessionId": "workspace-b", "displayId": 8},
        )
        self.assertEqual(
            parse_execution_scope({"executionContext": {"sessionId": "workspace-c", "displayId": 9}}),
            {"sessionId": "workspace-c", "displayId": 9},
        )

    def test_conflicting_aliases_rejected(self):
        with self.assertRaises(ValueError):
            parse_execution_scope({"session_id": "A", "sessionId": "B", "display_id": 2})
        with self.assertRaises(ValueError):
            parse_execution_scope({"session_id": "A", "display_id": 2, "displayId": 3})
        with self.assertRaises(ValueError):
            parse_execution_scope({
                "sessionId": "A",
                "displayId": 2,
                "executionContext": {"sessionId": "B", "displayId": 2},
            })

    def test_params_must_agree_with_top_level(self):
        with self.assertRaises(ValueError):
            parse_tool_execution_scope({
                "session_id": "workspace-a",
                "display_id": 4,
                "params": {"sessionId": "workspace-b", "displayId": 4, "elementId": "1"},
            })
        self.assertEqual(
            parse_tool_execution_scope({
                "session_id": "workspace-a",
                "display_id": 4,
                "params": {"sessionId": "workspace-a", "displayId": 4, "elementId": "1"},
            }),
            {"sessionId": "workspace-a", "displayId": 4},
        )

    def test_scope_cache_key_includes_session(self):
        self.assertEqual(scope_cache_key("dev_a", None), "dev_a::default-foreground")
        self.assertEqual(scope_cache_key("dev_a", "workspace-a"), "dev_a::workspace-a")
        self.assertNotEqual(scope_cache_key("dev_a", "workspace-a"), scope_cache_key("dev_a", "workspace-b"))

    def test_named_session_classifies_as_session_kernel_vd(self):
        plane = classify_session_plane("workspace-a", 7)
        self.assertEqual(plane["kind"], "session_kernel_vd")
        self.assertEqual(plane["sessionId"], "workspace-a")
        self.assertEqual(plane["displayId"], 7)
        self.assertEqual(plane["label"], "Session Kernel VD")
        self.assertNotIn("workspaceId", plane)

    def test_default_foreground_classifies_as_foreground(self):
        plane = classify_session_plane("default-foreground", 0)
        self.assertEqual(plane["kind"], "foreground")
        self.assertEqual(plane["sessionId"], "default-foreground")
        self.assertEqual(plane["displayId"], 0)
        self.assertEqual(plane["label"], "Foreground")
        omitted_display = classify_session_plane("default-foreground")
        self.assertEqual(omitted_display["kind"], "foreground")
        self.assertEqual(omitted_display["displayId"], 0)

    def test_named_display_zero_classifies_as_session_display_mismatch(self):
        with self.assertRaises(SessionScopeError) as raised:
            classify_session_plane("workspace-a", 0)
        self.assertEqual(raised.exception.error_class, SESSION_DISPLAY_MISMATCH)
        with self.assertRaises(SessionScopeError) as required:
            require_tool_execution_scope({"session_id": "workspace-a", "display_id": 0})
        self.assertEqual(required.exception.error_class, SESSION_DISPLAY_MISMATCH)

    def test_missing_session_id_classifies_as_session_required(self):
        with self.assertRaises(SessionScopeError) as raised:
            classify_session_plane(None)
        self.assertEqual(raised.exception.error_class, SESSION_REQUIRED)
        with self.assertRaises(SessionScopeError) as required:
            require_tool_execution_scope({"goal": "Open Apps"})
        self.assertEqual(required.exception.error_class, SESSION_REQUIRED)


class RecordingGateway:
    def __init__(self):
        self.observe_calls = []
        self.action_calls = []
        self.device_observe_calls = []
        self.device_action_calls = []
        self.page = "home"

    def observe(self, **kwargs):
        self.observe_calls.append(kwargs)
        session = kwargs.get("session_id") or "default-foreground"
        return {
            "witness": {"observation_id": f"obs-{len(self.observe_calls)}"},
            "observation": {
                "pageKey": self.page,
                "title": "Home" if self.page == "home" else "Apps",
                "pageText": "Home screen" if self.page == "home" else "Installed apps",
                "sessionId": kwargs.get("session_id"),
                "displayId": kwargs.get("display_id"),
                "controls": [{"id": f"{session}-apps", "label": "Apps", "clickable": True, "elementIndex": 1}],
            },
        }

    def action(self, tool, params, goal, **kwargs):
        self.action_calls.append({"tool": tool, "params": params, "goal": goal, **kwargs})
        self.page = "apps"
        return {
            "protocol_version": "cyclone.gateway.capability.v1",
            "capability_id": tool,
            "ok": True,
            "transport": {"ok": True},
            "execution": {"ok": True},
            "verification": {"ok": True, "status": "verified"},
            "error": None,
        }

    def device_observe(self, device_id, **kwargs):
        self.device_observe_calls.append({"device_id": device_id, **kwargs})
        return {
            "device_id": device_id,
            "witness": {"observation_id": f"obs-dev-{len(self.device_observe_calls)}"},
            "observation": {
                "observationId": f"obs-dev-{len(self.device_observe_calls)}",
                "pageKey": "home",
                "title": "Home",
                "pageText": "Home screen",
                "sessionId": kwargs.get("session_id"),
                "displayId": kwargs.get("display_id"),
                "controls": [{"id": f"{device_id}-apps", "label": "Apps", "clickable": True}],
            },
        }

    def device_action(self, device_id, tool, params, goal, **kwargs):
        self.device_action_calls.append({
            "device_id": device_id, "tool": tool, "params": params, "goal": goal, **kwargs,
        })
        return {
            "protocol_version": "cyclone.gateway.capability.v1",
            "capability_id": tool,
            "device_id": device_id,
            "ok": True,
            "transport": {"ok": True},
            "execution": {"ok": True},
            "verification": {"ok": True, "status": "verified"},
            "error": None,
        }

    def status(self):
        return {"ok": True}

    def device_status(self, device_id):
        return {"device_id": device_id}

    def ui_search(self, query, **kwargs):
        return {"candidates": []}

    def device_ui_search(self, device_id, query, **kwargs):
        return {"device_id": device_id, "candidates": []}


class SessionToolTests(unittest.TestCase):
    def test_phone_observe_forwards_session_identity(self):
        gateway = RecordingGateway()
        with tempfile.TemporaryDirectory() as report_dir:
            tools = PhoneTools(gateway, SessionRecorder(report_dir))
            card = json.loads(tools.call("phone_observe", {
                "session_id": "workspace-a",
                "display_id": 7,
                "goal": "Open Apps",
            })[0]["text"])
        self.assertEqual(gateway.observe_calls[-1]["session_id"], "workspace-a")
        self.assertEqual(gateway.observe_calls[-1]["display_id"], 7)
        self.assertEqual("workspace-a", card["sessionId"])
        self.assertEqual(7, card["displayId"])

    def test_phone_act_forwards_identity_and_does_not_reuse_element_ids_across_sessions(self):
        gateway = RecordingGateway()
        with tempfile.TemporaryDirectory() as report_dir:
            tools = PhoneTools(gateway, SessionRecorder(report_dir))
            observed_a = json.loads(tools.call("phone_observe", {
                "session_id": "workspace-a", "display_id": 7, "goal": "Open Apps",
            })[0]["text"])
            element_a = observed_a["candidates"]["current"][0]["elementId"]
            tools.call("phone_observe", {
                "session_id": "workspace-b", "display_id": 8, "goal": "Open Apps",
            })
            stale = json.loads(tools.call("phone_act", {
                "session_id": "workspace-b",
                "display_id": 8,
                "tool": "phone.click",
                "params": {"elementId": element_a},
                "goal": "Open Apps",
            })[0]["text"])
            self.assertEqual("STALE_OBSERVATION", stale["errorClass"])
            acted = json.loads(tools.call("phone_act", {
                "session_id": "workspace-a",
                "display_id": 7,
                "tool": "phone.click",
                "params": {"elementId": element_a},
                "goal": "Open Apps",
            })[0]["text"])
        self.assertTrue(acted["ok"])
        self.assertEqual("workspace-a", gateway.action_calls[-1]["session_id"])
        self.assertEqual(7, gateway.action_calls[-1]["display_id"])
        self.assertEqual("workspace-a", gateway.action_calls[-1]["params"]["sessionId"])
        self.assertEqual(7, gateway.action_calls[-1]["params"]["displayId"])
        self.assertTrue(gateway.action_calls[-1]["params"]["fastPath"])

    def test_schema_includes_session_fields_and_keeps_fast_path_annotations(self):
        tools = {tool["name"]: tool for tool in TOOLS}
        required_names = (
            "phone_observe", "phone_act", "phone_locate", "phone_ui_search",
            "phone_inspect_element", "phone_screenshot", "phone_skill_run", "phone_group_act",
        )
        for name in required_names:
            properties = tools[name]["inputSchema"]["properties"]
            self.assertIn("session_id", properties)
            self.assertIn("display_id", properties)
            self.assertIn("session_id", tools[name]["inputSchema"]["required"])
            self.assertFalse(tools[name]["inputSchema"]["additionalProperties"])
        self.assertNotIn("session_id", tools["phone_status"]["inputSchema"].get("required") or [])
        self.assertNotIn("session_id", tools["phone_skill_save"]["inputSchema"].get("required") or [])
        self.assertEqual("ui", tools["phone_observe"]["annotations"]["cycloneSurface"])
        self.assertEqual("ui", tools["phone_act"]["annotations"]["cycloneSurface"])
        self.assertTrue(tools["phone_act"]["annotations"]["cycloneFastPath"])
        self.assertTrue(tools["phone_observe"]["annotations"]["cycloneFastPath"])
        listed = McpServer().handle({"jsonrpc": "2.0", "id": 1, "method": "tools/list"})
        listed_tools = {tool["name"]: tool for tool in listed["result"]["tools"]}
        self.assertEqual("planner", listed_tools["phone_status"]["annotations"]["cycloneSurface"])
        self.assertTrue(listed_tools["phone_act"]["annotations"]["cycloneFastPath"])
        initialized = McpServer().handle({"jsonrpc": "2.0", "id": 2, "method": "initialize"})
        self.assertIn("session_id is required", initialized["result"]["instructions"])

    def _payload(self, tools, name, arguments):
        return json.loads(tools.call(name, arguments)[0]["text"])

    def test_phone_observe_without_session_id_fails_closed(self):
        gateway = RecordingGateway()
        with tempfile.TemporaryDirectory() as report_dir:
            tools = PhoneTools(gateway, SessionRecorder(report_dir))
            payload = self._payload(tools, "phone_observe", {"goal": "Open Apps"})
        self.assertEqual("SESSION_REQUIRED", payload["errorClass"])
        self.assertEqual("SESSION_REQUIRED", payload["error"]["code"])
        self.assertEqual([], gateway.observe_calls)

    def test_phone_act_without_session_id_fails_closed(self):
        gateway = RecordingGateway()
        with tempfile.TemporaryDirectory() as report_dir:
            tools = PhoneTools(gateway, SessionRecorder(report_dir))
            payload = self._payload(tools, "phone_act", {
                "tool": "phone.home", "params": {}, "goal": "Go home",
            })
        self.assertEqual("SESSION_REQUIRED", payload["errorClass"])
        self.assertEqual([], gateway.action_calls)
        self.assertEqual([], gateway.observe_calls)

    def test_phone_locate_without_session_id_fails_closed(self):
        gateway = RecordingGateway()
        with tempfile.TemporaryDirectory() as report_dir:
            tools = PhoneTools(gateway, SessionRecorder(report_dir))
            payload = self._payload(tools, "phone_locate", {"goal": "Open Apps"})
        self.assertEqual("SESSION_REQUIRED", payload["errorClass"])
        self.assertEqual([], gateway.observe_calls)

    def test_default_foreground_session_forwards_display_zero(self):
        gateway = RecordingGateway()
        with tempfile.TemporaryDirectory() as report_dir:
            tools = PhoneTools(gateway, SessionRecorder(report_dir))
            card = self._payload(tools, "phone_observe", {
                "session_id": "default-foreground", "goal": "Open Apps",
            })
            acted = self._payload(tools, "phone_act", {
                "session_id": "default-foreground",
                "tool": "phone.click",
                "params": {"elementId": card["candidates"]["current"][0]["elementId"]},
                "goal": "Open Apps",
            })
        self.assertEqual("default-foreground", gateway.observe_calls[-1]["session_id"])
        self.assertEqual(0, gateway.observe_calls[-1]["display_id"])
        self.assertTrue(acted["ok"])
        self.assertEqual("default-foreground", gateway.action_calls[-1]["session_id"])
        self.assertEqual(0, gateway.action_calls[-1]["display_id"])

    def test_sessionId_alias_satisfies_requirement(self):
        gateway = RecordingGateway()
        with tempfile.TemporaryDirectory() as report_dir:
            tools = PhoneTools(gateway, SessionRecorder(report_dir))
            self._payload(tools, "phone_observe", {"sessionId": "default-foreground", "goal": "Open Apps"})
        self.assertEqual("default-foreground", gateway.observe_calls[-1]["session_id"])
        self.assertEqual(0, gateway.observe_calls[-1]["display_id"])

    def test_workspace_session_without_display_id_fails_closed(self):
        gateway = RecordingGateway()
        with tempfile.TemporaryDirectory() as report_dir:
            tools = PhoneTools(gateway, SessionRecorder(report_dir))
            payload = self._payload(tools, "phone_observe", {"session_id": "workspace-a", "goal": "Open Apps"})
        self.assertEqual("SESSION_DISPLAY_MISMATCH", payload["errorClass"])
        self.assertEqual([], gateway.observe_calls)

    def test_workspace_display_zero_fails_closed(self):
        gateway = RecordingGateway()
        with tempfile.TemporaryDirectory() as report_dir:
            tools = PhoneTools(gateway, SessionRecorder(report_dir))
            payload = self._payload(tools, "phone_observe", {
                "session_id": "workspace-a", "display_id": 0, "goal": "Open Apps",
            })
        self.assertEqual("SESSION_DISPLAY_MISMATCH", payload["errorClass"])
        self.assertEqual([], gateway.observe_calls)

    def test_workspace_display_seven_forwards_both(self):
        gateway = RecordingGateway()
        with tempfile.TemporaryDirectory() as report_dir:
            tools = PhoneTools(gateway, SessionRecorder(report_dir))
            self._payload(tools, "phone_observe", {
                "session_id": "workspace-a", "display_id": 7, "goal": "Open Apps",
            })
        self.assertEqual("workspace-a", gateway.observe_calls[-1]["session_id"])
        self.assertEqual(7, gateway.observe_calls[-1]["display_id"])

    def test_conflicting_aliases_fail_closed_at_tool_surface(self):
        gateway = RecordingGateway()
        with tempfile.TemporaryDirectory() as report_dir:
            tools = PhoneTools(gateway, SessionRecorder(report_dir))
            payload = self._payload(tools, "phone_observe", {
                "session_id": "A", "sessionId": "B", "display_id": 2, "goal": "Open Apps",
            })
        self.assertEqual("SESSION_REQUIRED", payload["errorClass"])
        self.assertEqual([], gateway.observe_calls)


if __name__ == "__main__":
    unittest.main()
