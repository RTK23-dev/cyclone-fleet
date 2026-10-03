import unittest
import tempfile

from cyclone_phone_mcp.mcp_server import McpServer
from cyclone_phone_mcp.reports import SessionRecorder
from cyclone_phone_mcp.tools import PhoneTools


class FakePhoneTools:
    def __init__(self): self.last_call_failed = False
    def call(self, name, arguments): return [{"type": "text", "text": "{}"}]


class ObserveGateway:
    def __init__(self, response): self.response = response
    def observe(self, **_): return self.response


class McpProtocolTests(unittest.TestCase):
    def setUp(self): self.server = McpServer(FakePhoneTools())

    def test_initialize_echoes_protocol_and_instructions(self):
        response = self.server.handle({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocolVersion": "2025-06-18"}})
        self.assertEqual(response["result"]["protocolVersion"], "2025-06-18")
        self.assertIn("semantic-first", response["result"]["instructions"])
        self.assertIn("Fast Path", response["result"]["instructions"])
        self.assertIn("elementIndex", response["result"]["instructions"])
        self.assertIn("session_id is required", response["result"]["instructions"])
        self.assertIn("default-foreground", response["result"]["instructions"])
        self.assertIn("params.package=com.android.chrome", response["result"]["instructions"])
        self.assertIn("does not use OpenRouter", response["result"]["instructions"])

    def test_tool_list_has_no_shell(self):
        response = self.server.handle({"jsonrpc": "2.0", "id": 2, "method": "tools/list"})
        names = {t["name"] for t in response["result"]["tools"]}
        self.assertIn("phone_act", names)
        self.assertFalse(any("shell" in name or "root" in name or "adb" in name for name in names))

    def test_tool_list_exposes_device_autodetection_and_scoping(self):
        response = self.server.handle({"jsonrpc": "2.0", "id": 6, "method": "tools/list"})
        tools = {tool["name"]: tool for tool in response["result"]["tools"]}
        self.assertIn("phone_devices", tools)
        self.assertEqual(
            ["scan"],
            list(tools["phone_devices"]["inputSchema"]["properties"].keys()),
        )
        self.assertIn("device_id", tools["phone_observe"]["inputSchema"]["properties"])
        self.assertIn("session_id", tools["phone_observe"]["inputSchema"]["properties"])
        self.assertIn("display_id", tools["phone_observe"]["inputSchema"]["properties"])
        self.assertIn("session_id", tools["phone_observe"]["inputSchema"]["required"])
        self.assertIn("session_id", tools["phone_act"]["inputSchema"]["properties"])
        self.assertIn("display_id", tools["phone_act"]["inputSchema"]["properties"])
        self.assertIn("session_id", tools["phone_act"]["inputSchema"]["required"])
        self.assertIn("request_ai_control", tools["phone_act"]["inputSchema"]["properties"])
        self.assertIn("session_id", tools["phone_skill_run"]["inputSchema"]["required"])
        self.assertNotIn("session_id", tools["phone_status"]["inputSchema"].get("required") or [])
        self.assertIn("phone_locate", tools)
        self.assertIn("goal", tools["phone_locate"]["inputSchema"]["required"])
        self.assertIn("device_id", tools["phone_act"]["inputSchema"]["properties"])
        self.assertIn("device_id", tools["phone_debug_bundle"]["inputSchema"]["properties"])
        self.assertIn("device_ids", tools["phone_group_act"]["inputSchema"]["properties"])
        self.assertNotIn("phone.type", tools["phone_group_act"]["inputSchema"]["properties"]["tool"]["enum"])
        self.assertIn("current observation-scoped elementId", tools["phone_act"]["description"])
        self.assertEqual("ui", tools["phone_act"]["annotations"]["cycloneSurface"])
        self.assertEqual("planner", tools["phone_status"]["annotations"]["cycloneSurface"])
        self.assertEqual("ui", tools["phone_observe"]["annotations"]["cycloneSurface"])
        self.assertTrue(tools["phone_act"]["annotations"]["cycloneFastPath"])
        self.assertIn("elementIndex", tools["phone_act"]["description"])
        package = tools["phone_act"]["inputSchema"]["properties"]["params"]["properties"]["package"]
        self.assertEqual("string", package["type"])
        self.assertIn("com.android.chrome", package["description"])
        self.assertIn("packageName", package["description"])

    def test_phone_act_schema_lists_params_package(self):
        response = self.server.handle({"jsonrpc": "2.0", "id": 7, "method": "tools/list"})
        tools = {tool["name"]: tool for tool in response["result"]["tools"]}
        properties = tools["phone_act"]["inputSchema"]["properties"]["params"]["properties"]
        self.assertIn("package", properties)
        self.assertIn("required Android package id for phone.open_app", properties["package"]["description"])

    def test_unknown_tool_rejected(self):
        response = self.server.handle({"jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": {"name": "root_shell", "arguments": {}}})
        self.assertIn("error", response)

    def test_tools_call_uses_canonical_failure_flag_not_error_substring(self):
        self.server.phone_tools.last_call_failed = True
        response = self.server.handle({"jsonrpc": "2.0", "id": 4, "method": "tools/call", "params": {"name": "phone_status", "arguments": {}}})
        self.assertTrue(response["result"]["isError"])
        self.server.phone_tools.last_call_failed = False
        response = self.server.handle({"jsonrpc": "2.0", "id": 5, "method": "tools/call", "params": {"name": "phone_status", "arguments": {}}})
        self.assertFalse(response["result"]["isError"])

    def test_compact_observe_failure_and_protocol_mismatch_are_mcp_errors(self):
        responses = (
            {
                "protocol_version": "cyclone.gateway.capability.v1",
                "capability_id": "phone.observe",
                "ok": False,
                "transport": {"ok": False, "error": {"code": "DEVICE_DISCONNECTED", "layer": "TRANSPORT"}},
                "error": {"code": "DEVICE_DISCONNECTED", "layer": "TRANSPORT"},
            },
            {
                "protocol_version": "future.protocol.v9",
                "capability_id": "phone.observe",
                "ok": False,
                "transport": {"ok": True},
                "error": {"code": "PROTOCOL_MISMATCH", "layer": "PROTOCOL"},
            },
        )
        with tempfile.TemporaryDirectory() as report_dir:
            for index, raw in enumerate(responses):
                server = McpServer(PhoneTools(ObserveGateway(raw), SessionRecorder(report_dir)))
                response = server.handle({
                    "jsonrpc": "2.0",
                    "id": 20 + index,
                    "method": "tools/call",
                    "params": {"name": "phone_observe", "arguments": {"mode": "compact", "session_id": "default-foreground"}},
                })
                self.assertTrue(response["result"]["isError"])
                self.assertIn(raw["error"]["code"], response["result"]["content"][0]["text"])


if __name__ == "__main__": unittest.main()
