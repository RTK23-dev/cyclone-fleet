import json
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer

from cyclone_phone_mcp.gateway import GatewayClient, GatewayError


class Handler(BaseHTTPRequestHandler):
    token = "test-token"
    posts = []

    def do_GET(self):
        if self.headers.get("Authorization") != "Bearer test-token":
            self.send_response(401); self.end_headers(); return
        self.send_response(200); self.send_header("Content-Type", "application/json"); self.end_headers()
        if self.path == "/v1/capabilities":
            response = {
                "protocol_version": "cyclone.gateway.capability.v1",
                "capabilities": [
                    {"capability_id": "phone.observe"},
                    {"capability_id": "phone.find"},
                    {"capability_id": "phone.wait_for"},
                    {"capability_id": "phone.click"},
                    {"capability_id": "phone.home"},
                ],
                "gateway_health": {"state": "READY"},
            }
        elif self.path in {"/v1/devices", "/v1/fleet"}:
            response = {
                "protocol": "cyclone.desktop.v1",
                "devices": [
                    {
                        "deviceId": "dev_a",
                        "id": "dev_a",
                        "state": "READY",
                        "name": "Pixel 8",
                        "model": "Pixel 8",
                        "serialSuffix": "061G",
                        "paired": True,
                        "pairing": False,
                        "screen": "AWAKE",
                        "display": {"width": 1080, "height": 2400},
                    }
                ],
            }
        elif self.path == "/v1/device/status":
            response = {
                "serial": "MOCK-061G",
                "model": "Pixel 8",
                "cyclone_bridge_reachable": True,
            }
        elif self.path == "/v1/devices/dev_a/agent/capabilities":
            response = {
                "protocol_version": "cyclone.gateway.capability.v1",
                "device_id": "dev_a",
                "capabilities": [
                    {"capability_id": "phone.click"},
                    {"capability_id": "phone.observe"},
                ],
                "gateway_health": {"state": "READY"},
            }
        elif self.path == "/v1/devices/dev_a/agent/status":
            response = {"device_id": "dev_a", "status": {"gatewayEnabled": True}}
        else:
            response = {"path": self.path}
        self.wfile.write(json.dumps(response).encode())

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        payload = json.loads(self.rfile.read(length) or b"{}")
        Handler.posts.append((self.path, payload))
        if self.path == "/structured-error":
            self.send_response(409); self.send_header("Content-Type", "application/json"); self.end_headers()
            self.wfile.write(json.dumps({"error": {"code": "STALE_OBSERVATION", "layer": "protocol"}}).encode())
            return
        if self.path == "/v1/pair/required":
            self.send_response(401); self.send_header("Content-Type", "application/json"); self.end_headers()
            self.wfile.write(json.dumps({
                "detail": {"code": "PAIRING_REQUIRED", "message": "Pair this phone first", "retryable": True},
            }).encode())
            return
        if self.path == "/v1/fleet/scan":
            response = {
                "protocol": "cyclone.desktop.v1",
                "devices": [{"deviceId": "dev_a", "id": "dev_a", "state": "READY", "paired": True}],
                "discovery": {"adbAvailable": True, "rawAdbDeviceCount": 1},
            }
            self.send_response(200); self.send_header("Content-Type", "application/json"); self.end_headers()
            self.wfile.write(json.dumps(response).encode())
            return
        if self.path == "/v1/devices/dev_a/agent/observe":
            response = {
                "device_id": "dev_a",
                "mode": payload.get("mode", "compact"),
                "observation": {"observationId": "obs-dev-1", "pageKey": "home"},
                "witness": {"observation_id": "obs-dev-1", "page_key": "home"},
                "screenshot": None,
            }
            self.send_response(200); self.send_header("Content-Type", "application/json"); self.end_headers()
            self.wfile.write(json.dumps(response).encode())
            return
        if self.path == "/v1/devices/dev_a/agent/action":
            response = {
                "device_id": "dev_a",
                "capability_id": payload.get("capability_id"),
                "transport": {"ok": True},
                "execution": {"ok": True, "status": "android_succeeded"},
                "verification": {
                    "passed": True,
                    "before_observation_id": payload.get("expected_observation_id"),
                    "after_observation_id": "obs-dev-2",
                    "after_page_key": "apps",
                },
                "after": {"pageKey": "apps"},
            }
            self.send_response(200); self.send_header("Content-Type", "application/json"); self.end_headers()
            self.wfile.write(json.dumps(response).encode())
            return
        if self.path.endswith("/observe"):
            response = {"protocol_version": "cyclone.gateway.capability.v1", "correlation_id": payload["correlation_id"], "capability_id": "phone.observe", "ok": True, "transport": {"ok": True, "status": "connected"}, "witness": {"observation_id": "obs-7"}, "observation": {"pageKey": "home"}, "error": None}
        else:
            response = {"protocol_version": "cyclone.gateway.capability.v1", "correlation_id": payload["correlation_id"], "capability_id": payload["capability_id"], "ok": True, "transport": {"ok": True}, "execution": {"ok": True}, "verification": {"ok": True, "status": "verified"}, "error": None}
        self.send_response(200); self.send_header("Content-Type", "application/json"); self.end_headers()
        self.wfile.write(json.dumps(response).encode())

    def log_message(self, *args):
        pass


class LegacyOnlyHandler(Handler):
    def do_GET(self):
        if self.headers.get("Authorization") != "Bearer test-token":
            self.send_response(401); self.end_headers(); return
        if self.path in {"/v1/devices", "/v1/fleet"}:
            self.send_response(404); self.send_header("Content-Type", "application/json"); self.end_headers()
            self.wfile.write(b'{"detail": "Not Found"}')
            return
        super().do_GET()


class GatewayTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = HTTPServer(("127.0.0.1", 0), Handler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True); cls.thread.start()
        cls.url = f"http://127.0.0.1:{cls.server.server_port}"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown(); cls.server.server_close()

    def test_bearer_auth_and_query_encoding(self):
        result = GatewayClient(self.url, "test-token").ui_search("Apps & notifications")
        self.assertIn("q=Apps+%26+notifications", result["path"])

    def test_requires_token(self):
        with self.assertRaises(GatewayError):
            GatewayClient(self.url, "").status()

    def test_observation_authority_is_scoped_per_session(self):
        client = GatewayClient(self.url, "test-token")
        client.observe(session_id="workspace-a", display_id=7)
        path, observe_payload = Handler.posts[-1]
        self.assertEqual("/v1/capabilities/observe", path)
        self.assertEqual("workspace-a", observe_payload["sessionId"])
        self.assertEqual(7, observe_payload["displayId"])
        with self.assertRaises(GatewayError) as raised:
            client.action("phone.click", {"elementId": "1"}, "Open Apps")
        self.assertEqual("STALE_OBSERVATION", raised.exception.body["error"]["code"])
        client.action("phone.click", {"elementId": "1"}, "Open Apps", session_id="workspace-a", display_id=7)
        path, action_payload = Handler.posts[-1]
        self.assertEqual("/v1/capabilities/action", path)
        self.assertEqual("workspace-a", action_payload["sessionId"])
        self.assertEqual(7, action_payload["displayId"])
        self.assertEqual("workspace-a", action_payload["params"]["sessionId"])
        self.assertEqual(7, action_payload["params"]["displayId"])

    def test_typed_action_uses_last_observation_and_never_forwards_authorization(self):
        client = GatewayClient(self.url, "test-token")
        client.observe()
        client.action("phone.click", {"selector": {"text": "Apps"}}, "Open Apps")
        path, payload = Handler.posts[-1]
        self.assertEqual("/v1/capabilities/action", path)
        self.assertEqual("obs-7", payload["expected_observation_id"])
        self.assertNotIn("user_authorized", payload)

    def test_mutating_action_before_observe_fails_locally_with_typed_stale_error(self):
        before = len(Handler.posts)
        client = GatewayClient(self.url, "test-token")
        with self.assertRaises(GatewayError) as raised:
            client.action("phone.home", {}, "Go home")
        self.assertEqual(before, len(Handler.posts))
        self.assertEqual("STALE_OBSERVATION", raised.exception.body["error"]["code"])
        self.assertEqual("PROTOCOL", raised.exception.body["error"]["layer"])

    def test_structured_non_200_body_is_preserved(self):
        with self.assertRaises(GatewayError) as raised:
            GatewayClient(self.url, "test-token")._request("POST", "/structured-error", {})
        self.assertEqual(409, raised.exception.status)
        self.assertEqual("STALE_OBSERVATION", raised.exception.body["error"]["code"])

    def test_fastapi_detail_error_is_mapped_to_typed_envelope(self):
        with self.assertRaises(GatewayError) as raised:
            GatewayClient(self.url, "test-token")._request("POST", "/v1/pair/required", {})
        self.assertEqual("PAIRING_REQUIRED", raised.exception.body["error"]["code"])
        self.assertTrue(raised.exception.body["error"]["retryable"])

    def test_devices_lists_fleet_and_forces_scan(self):
        client = GatewayClient(self.url, "test-token")
        result = client.devices(scan=True)
        self.assertEqual("fleet", result["surface"])
        self.assertEqual("dev_a", result["devices"][0]["deviceId"])
        self.assertTrue(any(path == "/v1/fleet/scan" for path, _ in Handler.posts))
        self.assertEqual("061G", result["selectedSerialSuffix"])

    def test_devices_falls_back_to_legacy_single_device(self):
        server = HTTPServer(("127.0.0.1", 0), LegacyOnlyHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
        try:
            url = f"http://127.0.0.1:{server.server_port}"
            result = GatewayClient(url, "test-token").devices()
            self.assertEqual("legacy", result["surface"])
            self.assertEqual(1, len(result["devices"]))
            self.assertEqual("061G", result["devices"][0]["serialSuffix"])
            self.assertEqual("READY", result["devices"][0]["state"])
        finally:
            server.shutdown(); server.server_close()

    def test_device_mutation_requires_fresh_device_observation(self):
        before = len(Handler.posts)
        client = GatewayClient(self.url, "test-token")
        with self.assertRaises(GatewayError) as raised:
            client.device_action("dev_a", "phone.click", {"selector": {"text": "Apps"}}, "Open Apps")
        self.assertEqual("STALE_OBSERVATION", raised.exception.body["error"]["code"])
        self.assertEqual(before, len(Handler.posts))

    def test_device_action_normalizes_desktop_envelope(self):
        client = GatewayClient(self.url, "test-token")
        client.device_observe("dev_a")
        observe_post = next(payload for path, payload in Handler.posts if path == "/v1/devices/dev_a/agent/observe")
        self.assertEqual({"mode", "include_screenshot"}, set(observe_post.keys()))
        result = client.device_action("dev_a", "phone.click", {"selector": {"text": "Apps"}}, "Open Apps")
        action_post = next(payload for path, payload in Handler.posts if path == "/v1/devices/dev_a/agent/action")
        self.assertEqual(
            {"capability_id", "params", "goal", "expected_observation_id"},
            set(action_post.keys()),
        )
        self.assertEqual("obs-dev-1", action_post["expected_observation_id"])
        self.assertEqual("cyclone.gateway.capability.v1", result["protocol_version"])
        self.assertTrue(result["ok"])
        self.assertEqual("verified", result["verification"]["status"])
        self.assertEqual("obs-dev-1", result["verification"]["before_observation_id"])
        with self.assertRaises(GatewayError) as raised:
            client.device_action("dev_a", "phone.click", {"selector": {"text": "Other"}}, "Open Other")
        self.assertEqual("STALE_OBSERVATION", raised.exception.body["error"]["code"])

    def test_device_action_rejects_unadvertised_capability(self):
        client = GatewayClient(self.url, "test-token")
        with self.assertRaises(GatewayError) as raised:
            client.device_action("dev_a", "phone.shell", {}, "No shell")
        self.assertEqual("CAPABILITY_UNAVAILABLE", raised.exception.body["error"]["code"])

    def test_rejects_non_loopback_gateway(self):
        with self.assertRaises(GatewayError):
            GatewayClient("https://example.com", "test-token")


if __name__ == "__main__":
    unittest.main()
