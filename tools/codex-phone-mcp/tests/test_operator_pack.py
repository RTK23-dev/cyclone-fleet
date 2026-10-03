import json
import os
import re
import tempfile
import time
import unittest
from pathlib import Path

from cyclone_phone_mcp import mcp_server, tools as tools_mod
from cyclone_phone_mcp.reports import SessionRecorder
from cyclone_phone_mcp.tools import PhoneTools


FG = {"session_id": "default-foreground"}
CHROME_PACKAGE = "com.android.chrome"


class OperatorGateway:
    def __init__(self):
        self.page = {
            "pageKey": "home",
            "title": "Home",
            "package": "com.android.launcher3",
            "pageText": "Phone. Messages. Chrome.",
            "controls": [{"id": "phone", "label": "Phone", "clickable": True, "elementIndex": 1}],
        }
        self.actions = []

    def status(self):
        return {"ok": True, "gatewayEnabled": True, "sessions": [{"sessionId": "default-foreground", "displayId": 0}]}

    def devices(self, *, scan=False):
        return {"surface": "fleet", "devices": []}

    def observe(self, **kwargs):
        return {
            "witness": {"observation_id": "obs-operator"},
            "observation": dict(self.page),
        }

    def ui_search(self, query, **kwargs):
        return {"candidates": [{"id": "1", "label": query}]}

    def action(self, tool, params, goal, **kwargs):
        forwarded = dict(params)
        self.actions.append({
            "tool": tool,
            "params": forwarded,
            "goal": goal,
            "session_id": kwargs.get("session_id"),
        })
        if tool == "phone.open_app":
            self.page = {
                "pageKey": "chrome",
                "title": "Chrome",
                "package": CHROME_PACKAGE,
                "pageText": "Google Chrome",
                "controls": [{"id": "omnibox", "label": "Search or type URL", "clickable": True}],
            }
        return {
            "protocol_version": "cyclone.gateway.capability.v1",
            "capability_id": tool,
            "ok": True,
            "transport": {"ok": True},
            "execution": {"ok": True},
            "verification": {"ok": True, "status": "verified"},
            "error": None,
        }


def _payload(content):
    return json.loads(content[0]["text"])


def run_operator_browse_path(phone_tools: PhoneTools) -> dict:
    """Deterministic typed MCP browse: status → observe → locate → home → open Chrome."""
    started = time.perf_counter()
    steps = []

    def step(name, args):
        t0 = time.perf_counter()
        payload = _payload(phone_tools.call(name, args))
        steps.append({
            "name": name,
            "latencyMs": int((time.perf_counter() - t0) * 1000),
            "session_id": args.get("session_id"),
            "result": payload,
        })
        return payload

    step("phone_status", {})
    step("phone_observe", dict(FG))
    step("phone_locate", {"goal": "Open Chrome", **FG})
    step("phone_act", {"tool": "phone.home", "params": {}, "goal": "Go home", **FG})
    open_app = step("phone_act", {
        "tool": "phone.open_app",
        "params": {"package": CHROME_PACKAGE},
        "goal": "Open Chrome",
        **FG,
    })
    return {
        "ok": all(
            item["result"].get("ok") is not False and item["result"].get("error") in (None, {}, "")
            for item in steps
        ),
        "session_id": FG["session_id"],
        "open_app_params": {"package": CHROME_PACKAGE},
        "open_app": open_app,
        "steps": steps,
        "latencyMs": int((time.perf_counter() - started) * 1000),
    }


class OperatorPackTests(unittest.TestCase):
    def setUp(self):
        self.hidden = {
            key: os.environ.pop(key)
            for key in list(os.environ)
            if "openrouter" in key.lower()
        }
        self.temp = tempfile.TemporaryDirectory()
        self.gateway = OperatorGateway()
        self.tools = PhoneTools(self.gateway, SessionRecorder(self.temp.name))

    def tearDown(self):
        os.environ.update(self.hidden)
        self.temp.cleanup()

    def test_browse_path_does_not_require_openrouter_key(self):
        for key in list(os.environ):
            self.assertNotIn("openrouter", key.lower())
        result = run_operator_browse_path(self.tools)
        rendered = json.dumps(result)
        self.assertNotRegex(rendered, r"(?i)openrouter")
        self.assertTrue(result["ok"])
        names = [step["name"] for step in result["steps"]]
        self.assertEqual(
            ["phone_status", "phone_observe", "phone_locate", "phone_act", "phone_act"],
            names,
        )
        self.assertEqual("phone.home", result["steps"][3]["result"]["tool"])
        self.assertEqual("phone.open_app", result["steps"][4]["result"]["tool"])
        self.assertEqual({"package": CHROME_PACKAGE}, result["open_app_params"])
        self.assertEqual("default-foreground", result["session_id"])
        for step in result["steps"]:
            if step["name"] in {"phone_observe", "phone_locate", "phone_act"}:
                self.assertEqual("default-foreground", step["session_id"])
            self.assertIn("latencyMs", step)
            self.assertIsInstance(step["latencyMs"], int)
            self.assertGreaterEqual(step["latencyMs"], 0)
        self.assertIn("latencyMs", result)
        self.assertIsInstance(result["latencyMs"], int)
        self.assertGreaterEqual(result["latencyMs"], sum(step["latencyMs"] for step in result["steps"]))
        open_calls = [item for item in self.gateway.actions if item["tool"] == "phone.open_app"]
        self.assertEqual(1, len(open_calls))
        self.assertEqual(CHROME_PACKAGE, open_calls[0]["params"]["package"])
        self.assertNotIn("packageName", open_calls[0]["params"])
        self.assertNotIn("app", open_calls[0]["params"])
        self.assertNotIn("name", open_calls[0]["params"])
        self.assertEqual("default-foreground", open_calls[0]["session_id"])
        for item in self.gateway.actions:
            self.assertEqual("default-foreground", item["session_id"])

    def test_operator_browse_path_never_inspects_openrouter_env(self):
        for path in (Path(tools_mod.__file__), Path(mcp_server.__file__)):
            text = path.read_text(encoding="utf-8")
            self.assertNotIn("OPENROUTER", text)
            self.assertNotIn("openrouter_key", text)
            self.assertNotRegex(text, r"os\.(getenv|environ).{0,40}openrouter", re.I)
        probed: list[str] = []
        real_getenv = tools_mod.os.getenv

        def wrapped_getenv(key, default=None):
            probed.append(str(key))
            if "openrouter" in str(key).lower():
                raise AssertionError("operator browse path must not inspect OpenRouter env")
            return real_getenv(key, default)

        tools_mod.os.getenv = wrapped_getenv
        try:
            result = run_operator_browse_path(self.tools)
        finally:
            tools_mod.os.getenv = real_getenv
        self.assertTrue(result["ok"])
        self.assertFalse(any("openrouter" in key.lower() for key in probed))
        self.assertEqual(CHROME_PACKAGE, result["open_app_params"]["package"])
        self.assertEqual({"package"}, set(result["open_app_params"]))


if __name__ == "__main__":
    unittest.main()
