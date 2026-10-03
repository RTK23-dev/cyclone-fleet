from __future__ import annotations

import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from cyclone_phone_mcp.gateway import GatewayClient, GatewayError
from cyclone_phone_mcp.tooling import apply_gateway_env, load_connection


class ToolingBootstrapTests(unittest.TestCase):
    def test_gateway_client_loads_persisted_token_without_env(self):
        token = "persisted-test-bearer"
        url = "http://127.0.0.1:18765"
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            runtime = root / "Cyclone One" / "runtime"
            runtime.mkdir(parents=True)
            (runtime / "gateway-locator.json").write_text(
                json.dumps(
                    {
                        "schema": "cyclone.one.gateway.locator.v1",
                        "product": "Cyclone One",
                        "url": url,
                        "port": 18765,
                        "runtime": str(runtime),
                        "sessionSecretPersisted": True,
                    }
                ),
                encoding="utf-8",
            )
            (runtime / "gateway-token.json").write_text(
                json.dumps({"version": 1, "token": token, "url": url, "port": 18765}),
                encoding="utf-8",
            )
            extra = {
                "CYCLONE_TOOLING_TEST_ROOT": str(root),
                "LOCALAPPDATA": str(root),
            }
            with patch.dict(os.environ, extra, clear=False):
                for key in (
                    "CYCLONE_DEVICE_GATEWAY_TOKEN",
                    "CYCLONE_DEVICE_GATEWAY_URL",
                    "CYCLONE_DEVICE_GATEWAY_PORT",
                    "CYCLONE_DEVICE_GATEWAY_RUNTIME",
                ):
                    os.environ.pop(key, None)
                self.assertNotIn("CYCLONE_DEVICE_GATEWAY_TOKEN", os.environ)
                loaded = load_connection(include_env=False)
                client = GatewayClient(timeout=1)
            self.assertIsNotNone(loaded)
            self.assertEqual(loaded["token"], token)
            self.assertEqual(client.token, token)
            self.assertEqual(client.base_url.rstrip("/"), url)

    def test_apply_gateway_env_public_summary_omits_token(self):
        token = "persisted-test-bearer"
        url = "http://127.0.0.1:19876"
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            runtime = root / "Cyclone One" / "runtime"
            runtime.mkdir(parents=True)
            (runtime / "gateway-locator.json").write_text(
                json.dumps(
                    {
                        "schema": "cyclone.one.gateway.locator.v1",
                        "product": "Cyclone One",
                        "url": url,
                        "port": 19876,
                        "runtime": str(runtime),
                        "sessionSecretPersisted": True,
                    }
                ),
                encoding="utf-8",
            )
            (runtime / "gateway-token.json").write_text(
                json.dumps({"version": 1, "token": token, "url": url, "port": 19876}),
                encoding="utf-8",
            )
            extra = {
                "CYCLONE_TOOLING_TEST_ROOT": str(root),
                "LOCALAPPDATA": str(root),
            }
            with patch.dict(os.environ, extra, clear=False):
                for key in (
                    "CYCLONE_DEVICE_GATEWAY_TOKEN",
                    "CYCLONE_DEVICE_GATEWAY_URL",
                    "CYCLONE_DEVICE_GATEWAY_PORT",
                    "CYCLONE_DEVICE_GATEWAY_RUNTIME",
                ):
                    os.environ.pop(key, None)
                target: dict[str, str] = {}
                public = apply_gateway_env(target)
                self.assertEqual(target.get("CYCLONE_DEVICE_GATEWAY_TOKEN"), token)
                self.assertEqual(target.get("CYCLONE_DEVICE_GATEWAY_URL"), url)
                self.assertEqual(target.get("CYCLONE_DEVICE_GATEWAY_PORT"), "19876")
                self.assertNotIn("CYCLONE_DEVICE_GATEWAY_TOKEN", json.dumps(public))
                self.assertEqual(public.get("sessionSecretPersisted"), "true")

    def test_missing_bearer_asks_to_start_cyclone_one(self):
        with tempfile.TemporaryDirectory() as raw:
            extra = {"CYCLONE_TOOLING_TEST_ROOT": raw, "LOCALAPPDATA": raw}
            with patch.dict(os.environ, extra, clear=False):
                for key in (
                    "CYCLONE_DEVICE_GATEWAY_TOKEN",
                    "CYCLONE_DEVICE_GATEWAY_URL",
                    "CYCLONE_DEVICE_GATEWAY_PORT",
                    "CYCLONE_DEVICE_GATEWAY_RUNTIME",
                ):
                    os.environ.pop(key, None)
                with self.assertRaises(GatewayError) as raised:
                    GatewayClient("http://127.0.0.1:8765", "").status()
        message = str(raised.exception)
        self.assertIn("not persisted", message.lower())
        self.assertIn("Cyclone One", message)
        self.assertNotIn("is not set", message)


if __name__ == "__main__":
    unittest.main()
