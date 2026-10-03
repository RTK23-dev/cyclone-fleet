import base64
import json
import os
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from pathlib import Path
from unittest.mock import Mock, patch

from cyclone_phone_mcp.live_phone_bridge import (
    Bridge,
    LATEST_PROTOCOL,
    SUPPORTED_PROTOCOLS,
    Server,
    _legacy_catalog,
    catalog,
)
from cyclone_phone_mcp.live_phone_ipc import root


class DirectBridgeTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        env = patch.dict(os.environ, {"LOCALAPPDATA": self.tmp.name})
        env.start()
        self.addCleanup(env.stop)
        root().mkdir(parents=True)
        self.control(True)
        self.broker = Mock()
        self.broker.lock = threading.RLock()
        self.broker.engine.root = root()
        self.broker.engine.observations = {"pixel": ("o1", 0, True)}
        self.broker.handle.return_value = {"ok": True, "device": "pixel"}
        self.bridge = Bridge(self.broker)

    def control(self, enabled, generation="one"):
        (root() / "control.json").write_text(
            json.dumps(
                {
                    "enabled": enabled,
                    "stopped": not enabled,
                    "generation": generation,
                }
            )
        )

    def start_http(self):
        server = Server(("127.0.0.1", 0), self.bridge, "test-credential")
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        return server, f"http://127.0.0.1:{server.server_port}/mcp"

    @staticmethod
    def request(url, payload=None, *, token=True, headers=None, method=None):
        merged = {"Content-Type": "application/json"}
        if token:
            merged["Authorization"] = "Bearer test-credential"
        merged.update(headers or {})
        body = None if payload is None else json.dumps(payload).encode()
        return urllib.request.Request(url, body, merged, method=method)

    def test_exact_typed_cloud_catalog_and_no_native_route(self):
        names = {tool["name"] for tool in catalog()}
        self.assertEqual(
            {
                "cyclone_devices",
                "cyclone_see",
                "cyclone_find",
                "cyclone_inspect",
                "cyclone_act",
                "cyclone_session",
            },
            names,
        )
        self.assertEqual(6, len(names))
        self.assertNotIn("phone_act", names)
        self.assertFalse(any(name.startswith("cyclone_phone_") for name in names))
        for tool in catalog():
            self.assertFalse(tool["inputSchema"]["additionalProperties"])
            self.assertFalse(
                {
                    "shell",
                    "token",
                    "url",
                    "display_id",
                    "session_id",
                    "workspaceId",
                }
                & set(tool["inputSchema"]["properties"])
            )
            self.assertTrue(tool["title"].startswith("Cyclone "))
        self.assertNotIn(
            "CycloneLivePhone",
            Path("scripts/pc-companion/entrypoints/agent_mcp.py").read_text(),
        )

    def test_legacy_typed_actions_remain_accepted_but_hidden(self):
        legacy = {tool["name"]: tool["inputSchema"] for tool in _legacy_catalog()}
        self.assertEqual(16, len(legacy))
        self.assertNotIn("cyclone_phone_home", {tool["name"] for tool in catalog()})
        for op in (
            "tap",
            "long_press",
            "type",
            "clear_text",
            "swipe",
            "scroll",
            "back",
            "home",
            "open_app",
        ):
            schema = legacy["cyclone_phone_" + op]
            args = {key: "test" for key in schema["required"]}
            args.update(device="pixel", observation_id="o1")
            if "direction" in args:
                args["direction"] = "forward"
            self.bridge.dispatch(
                "tools/call",
                {"name": "cyclone_phone_" + op, "arguments": args},
            )
            self.assertEqual(
                "o1", self.broker.handle.call_args.args[0]["observation_id"]
            )
        with self.assertRaises(ValueError):
            self.bridge.dispatch(
                "tools/call",
                {"name": "cyclone_phone_home", "arguments": {"device": "pixel"}},
            )
        with self.assertRaises(ValueError):
            self.bridge.dispatch(
                "tools/call",
                {
                    "name": "cyclone_phone_scroll",
                    "arguments": {
                        "device": "pixel",
                        "goal": "move",
                        "observation_id": "o1",
                        "direction": "sideways",
                    },
                },
            )

    def test_inline_vision_and_expiring_authenticated_resource(self):
        from PIL import Image

        path = root() / "latest.png"
        Image.new("RGB", (8, 12), "blue").save(path)
        self.broker.handle.return_value = {
            "ok": True,
            "observation_id": "o1",
            "device": "pixel",
            "ui": {},
            "vision": {
                "ready": True,
                "path": str(path),
                "mime": "image/png",
                "width": 8,
                "height": 12,
            },
            "screenshot_path": str(path),
        }
        result = self.bridge.dispatch(
            "tools/call",
            {"name": "cyclone_phone_observe", "arguments": {"device": "pixel"}},
        )
        self.assertEqual(
            path.read_bytes(), base64.b64decode(result["content"][1]["data"])
        )
        self.assertEqual(
            ["assistant"], result["content"][1]["annotations"]["audience"]
        )
        self.assertNotIn(str(path), result["content"][0]["text"])
        uri = result["structuredContent"]["screenshot"]["reference"]
        self.assertEqual(
            path.read_bytes(),
            base64.b64decode(self.bridge.read_frame({"uri": uri})["contents"][0]["blob"]),
        )
        self.control(False, "pause")
        with self.assertRaises(ValueError):
            self.bridge.read_frame({"uri": uri})
        with self.assertRaises(ValueError):
            self.bridge.read_frame({"uri": "file:///etc/passwd"})

    def test_http_authentication_initialization_and_protocol_negotiation(self):
        _, url = self.start_http()
        with self.assertRaises(urllib.error.HTTPError) as denied:
            urllib.request.urlopen(
                self.request(
                    url,
                    {
                        "jsonrpc": "2.0",
                        "id": 1,
                        "method": "tools/list",
                    },
                    token=False,
                ),
                timeout=3,
            )
        self.assertEqual(401, denied.exception.code)

        for requested in SUPPORTED_PROTOCOLS:
            init = {
                "jsonrpc": "2.0",
                "id": requested,
                "method": "initialize",
                "params": {
                    "protocolVersion": requested,
                    "capabilities": {},
                    "clientInfo": {"name": "test", "version": "1"},
                },
            }
            result = json.load(
                urllib.request.urlopen(self.request(url, init), timeout=3)
            )
            self.assertEqual(
                requested, result["result"]["protocolVersion"]
            )

        newer = {
            "jsonrpc": "2.0",
            "id": 9,
            "method": "initialize",
            "params": {
                "protocolVersion": "2099-01-01",
                "capabilities": {},
                "clientInfo": {"name": "test", "version": "1"},
            },
        }
        result = json.load(
            urllib.request.urlopen(self.request(url, newer), timeout=3)
        )
        self.assertEqual(LATEST_PROTOCOL, result["result"]["protocolVersion"])

    def test_streamable_http_batch_notifications_origin_and_protocol_guards(self):
        _, url = self.start_http()

        batch = [
            {"jsonrpc": "2.0", "id": 1, "method": "tools/list", "params": {}},
            {"jsonrpc": "2.0", "method": "notifications/initialized"},
            {"jsonrpc": "2.0", "id": 2, "method": "ping", "params": {}},
        ]
        response = json.load(
            urllib.request.urlopen(
                self.request(
                    url,
                    batch,
                    headers={
                        "Accept": "application/json, text/event-stream",
                        "MCP-Protocol-Version": "2025-06-18",
                        "Origin": "https://chatgpt.com",
                    },
                ),
                timeout=3,
            )
        )
        self.assertEqual([1, 2], [item["id"] for item in response])
        self.assertEqual(6, len(response[0]["result"]["tools"]))

        notification = {
            "jsonrpc": "2.0",
            "method": "notifications/initialized",
        }
        with urllib.request.urlopen(
            self.request(
                url,
                notification,
                headers={"Origin": "https://chatgpt.com"},
            ),
            timeout=3,
        ) as accepted:
            self.assertEqual(202, accepted.status)
            self.assertEqual(b"", accepted.read())

        with self.assertRaises(urllib.error.HTTPError) as bad_origin:
            urllib.request.urlopen(
                self.request(
                    url,
                    {"jsonrpc": "2.0", "id": 3, "method": "ping"},
                    headers={"Origin": "https://evil.example"},
                ),
                timeout=3,
            )
        self.assertEqual(403, bad_origin.exception.code)

        with self.assertRaises(urllib.error.HTTPError) as bad_protocol:
            urllib.request.urlopen(
                self.request(
                    url,
                    {"jsonrpc": "2.0", "id": 4, "method": "ping"},
                    headers={"MCP-Protocol-Version": "1900-01-01"},
                ),
                timeout=3,
            )
        self.assertEqual(400, bad_protocol.exception.code)

        with self.assertRaises(urllib.error.HTTPError) as no_sse:
            urllib.request.urlopen(
                self.request(
                    url,
                    token=True,
                    method="GET",
                    headers={"Origin": "https://chatgpt.com"},
                ),
                timeout=3,
            )
        self.assertEqual(405, no_sse.exception.code)

    def test_initialize_cannot_be_batched_and_shell_is_never_exposed(self):
        _, url = self.start_http()
        init = {
            "jsonrpc": "2.0",
            "id": 1,
            "method": "initialize",
            "params": {
                "protocolVersion": "2025-03-26",
                "capabilities": {},
                "clientInfo": {"name": "test", "version": "1"},
            },
        }
        response = json.load(
            urllib.request.urlopen(self.request(url, [init]), timeout=3)
        )
        self.assertEqual(-32600, response["error"]["code"])
        with self.assertRaises(ValueError):
            self.bridge.dispatch(
                "tools/call", {"name": "shell", "arguments": {}}
            )
        self.broker.handle.assert_not_called()


if __name__ == "__main__":
    unittest.main()
