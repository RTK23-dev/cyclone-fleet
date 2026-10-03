import json
import tempfile
import unittest

from cyclone_phone_mcp.gateway import CAPABILITY_PROTOCOL_VERSION, GatewayClient, GatewayError
from cyclone_phone_mcp.mcp_server import McpServer
from cyclone_phone_mcp.reports import SessionRecorder
from cyclone_phone_mcp.tools import PhoneTools


class FakeGatewayClient(GatewayClient):
    def __init__(self):
        super().__init__("http://127.0.0.1:8765", "pc-token")
        self.calls = []

    def _request(self, method, path, payload=None):
        self.calls.append((method, path, payload))
        if path == "/v1/capabilities":
            return {
                "protocol_version": CAPABILITY_PROTOCOL_VERSION,
                "gateway_health": {"state": "AVAILABLE"},
                "capabilities": [
                    {"capability_id": "phone.click"},
                    {"capability_id": "phone.observe"},
                ],
            }
        if path == "/v1/capabilities/observe":
            return {
                "protocol_version": CAPABILITY_PROTOCOL_VERSION,
                "capability_id": "phone.observe",
                "ok": True,
                "transport": {"ok": True, "status": "connected"},
                "witness": {"observation_id": "obs-1"},
                "observation": {},
            }
        if path == "/v1/capabilities/action":
            return {
                "protocol_version": CAPABILITY_PROTOCOL_VERSION,
                "correlation_id": payload["correlation_id"],
                "capability_id": payload["capability_id"],
                "ok": True,
                "transport": {"ok": True, "status": "connected"},
                "execution": {"ok": True, "status": "android_succeeded"},
                "verification": {"ok": True, "status": "page_changed"},
                "error": None,
            }
        return {}


class V31DiscoveryTests(unittest.TestCase):
    def test_capability_discovery_is_cached_and_typed(self):
        gateway = FakeGatewayClient()
        first = gateway.capabilities()
        second = gateway.capabilities()
        self.assertEqual(first, second)
        self.assertEqual(
            len([call for call in gateway.calls if call[1] == "/v1/capabilities"]),
            1,
        )

    def test_unknown_action_is_rejected_before_http_action(self):
        gateway = FakeGatewayClient()
        gateway.observe()
        with self.assertRaises(GatewayError) as error:
            gateway.action("phone.shell", {}, "No shell")
        self.assertEqual(error.exception.body["error"]["code"], "CAPABILITY_UNAVAILABLE")
        self.assertFalse(any(call[1] == "/v1/capabilities/action" for call in gateway.calls))

    def test_mutation_requires_reobserve_after_success(self):
        gateway = FakeGatewayClient()
        gateway.observe()
        gateway.action("phone.click", {"selector": {"text": "Apps"}}, "Open Apps")
        with self.assertRaises(GatewayError) as error:
            gateway.action("phone.click", {"selector": {"text": "Other"}}, "Open Other")
        self.assertEqual(error.exception.body["error"]["code"], "STALE_OBSERVATION")

    def test_mcp_discovery_surface_has_no_generic_shell_or_adb(self):
        server = McpServer()
        tools = server.handle({"jsonrpc": "2.0", "id": 1, "method": "tools/list"})["result"]["tools"]
        names = {tool["name"] for tool in tools}
        self.assertIn("phone_capabilities", names)
        self.assertTrue({"phone_status", "phone_observe", "phone_ui_search", "phone_inspect_element", "phone_act", "phone_debug_bundle", "phone_teach_start"}.issubset(names))
        joined = " ".join(names).lower()
        self.assertNotIn("shell", joined)
        self.assertNotIn("adb", joined)
        self.assertNotIn("root", joined)

    def test_phone_capabilities_tool_returns_discovery(self):
        gateway = FakeGatewayClient()
        with tempfile.TemporaryDirectory() as report_dir:
            tools = PhoneTools(gateway, SessionRecorder(report_dir))
            content = tools.call("phone_capabilities", {})
        payload = json.loads(content[0]["text"])
        self.assertEqual(payload["protocol_version"], CAPABILITY_PROTOCOL_VERSION)


if __name__ == "__main__":
    unittest.main()
