from __future__ import annotations

import json
from pathlib import Path
import tempfile
import time
import unittest

from cyclone_phone_mcp.cip import CipEngine


class FakeTools:
    def __init__(self):
        self.calls = []
        self.outcome = {
            "ok": True,
            "actionStatus": {
                "transport": "ok",
                "execution": "ok",
                "gatewayVerification": "passed",
            },
        }

    def phone_ui_search(self, args):
        return {"results": [{"elementId": "e1", "label": args["query"]}]}

    def phone_inspect_element(self, args):
        return {"elementId": args["element_id"], "role": "button"}

    def _run_verified_mutation(self, device, tool, params, goal, scope=None):
        self.calls.append((device, tool, params, goal, scope))
        return dict(self.outcome)


class FakeLive:
    def __init__(self, root: Path, devices=None):
        self.root = root
        self.root.mkdir(parents=True, exist_ok=True)
        control = self.root / "control.json"
        if not control.exists():
            control.write_text(
                json.dumps({"generation": "g1", "enabled": True, "stopped": False})
            )
        self.devices = devices or [{"device_id": "pixel", "state": "ready"}]
        self.observations = {}
        self.paused = False
        self.tools = FakeTools()
        self.seq = 0

    def execute(self, request):
        if request["operation"] == "devices":
            return {"devices": self.devices}
        if request["operation"] == "observe":
            return self.observe(request)
        raise AssertionError(request)

    def observe(self, request):
        self.seq += 1
        obs = f"obs-{self.seq}"
        self.observations[request["device"]] = (obs, time.monotonic(), True)
        return {
            "ok": True,
            "device": request["device"],
            "observation_id": obs,
            "package": "com.example",
            "screenshot": {
                "available": True,
                "width": 1000,
                "height": 2000,
                "mime": "image/png",
            },
            "ui": {
                "candidates": {
                    "current": [{"elementId": "e1", "label": "Go"}]
                }
            },
        }


class CipTests(unittest.TestCase):
    def make(self, devices=None):
        temp = tempfile.TemporaryDirectory()
        live = FakeLive(Path(temp.name), devices=devices)
        return temp, live, CipEngine(live)

    def test_single_device_auto_select_and_see(self):
        temp, live, cip = self.make()
        self.addCleanup(temp.cleanup)
        seen = cip.see(goal="Go")
        self.assertTrue(seen["ok"])
        self.assertEqual(seen["observation"]["device"], "pixel")
        self.assertEqual(
            seen["observation"]["surface"],
            {"session": "default-foreground", "display": 0},
        )
        self.assertEqual(seen["matches"]["results"][0]["elementId"], "e1")
        self.assertIsNone(seen["observation"]["frame"]["rotation"])

    def test_multiple_devices_fail_closed(self):
        temp, live, cip = self.make([{"device_id": "a"}, {"device_id": "b"}])
        self.addCleanup(temp.cleanup)
        result = cip.dispatch("see", {})
        self.assertEqual(result["error"]["code"], "MULTIPLE_DEVICES")

    def test_normalized_swipe_compiles_using_observation_geometry(self):
        temp, live, cip = self.make()
        self.addCleanup(temp.cleanup)
        seen = cip.see()
        obs = seen["observation"]["id"]
        result = cip.act(
            request_id="request-0001",
            observation_id=obs,
            action={
                "kind": "swipe",
                "from": {"x": .5, "y": .8},
                "to": {"x": .5, "y": .2},
                "duration_ms": 350,
            },
            goal="Move the visible surface upward",
        )
        self.assertEqual(result["status"], "VERIFIED")
        _, tool, params, _, _ = live.tools.calls[0]
        self.assertEqual(tool, "phone.swipe")
        self.assertEqual(params["x1"], 500)
        self.assertEqual(params["y1"], 1599)
        self.assertEqual(params["x2"], 500)
        self.assertEqual(params["y2"], 400)
        self.assertEqual(params["durationMs"], 350)
        self.assertEqual(params["humanize"], "auto")

    def test_coordinate_tap_is_observation_bound(self):
        temp, live, cip = self.make()
        self.addCleanup(temp.cleanup)
        obs = cip.see()["observation"]["id"]
        result = cip.act(
            request_id="request-0005",
            observation_id=obs,
            action={"kind": "tap", "target": {"point": {"x": .25, "y": .75}}},
            goal="Tap the visible target",
        )
        self.assertEqual(result["status"], "VERIFIED")
        _, tool, params, _, _ = live.tools.calls[0]
        self.assertEqual(tool, "phone.click")
        self.assertEqual(params["selector"], {"x": 250, "y": 1499})
        self.assertEqual(params["humanize"], "auto")

    def test_element_tap_and_after_observation(self):
        temp, live, cip = self.make()
        self.addCleanup(temp.cleanup)
        obs = cip.see()["observation"]["id"]
        result = cip.act(
            request_id="request-0002",
            observation_id=obs,
            action={"kind": "tap", "target": {"element_id": "e1"}},
            goal="Tap Go",
        )
        self.assertEqual(result["status"], "VERIFIED")
        self.assertEqual(result["after"]["id"], "obs-2")
        self.assertEqual(live.tools.calls[0][1], "phone.click")
        self.assertEqual(live.tools.calls[0][2]["elementId"], "e1")
        self.assertEqual(live.tools.calls[0][2]["humanize"], "auto")

    def test_queued_gesture_timeout_is_uncertain_never_retry(self):
        temp, live, cip = self.make()
        self.addCleanup(temp.cleanup)
        live.tools.outcome = {
            "ok": False,
            "errorClass": "TIMEOUT",
            "actionStatus": {
                "transport": "ok",
                "execution": "timeout",
                "gatewayVerification": "not_run",
            },
        }
        obs = cip.see()["observation"]["id"]
        result = cip.act(
            request_id="request-gesture-timeout",
            observation_id=obs,
            action={"kind": "tap", "target": {"element_id": "e1"}},
            goal="Tap Go",
        )
        self.assertFalse(result["ok"])
        self.assertEqual(result["status"], "UNCERTAIN")
        self.assertEqual(result["retry"], "NEVER_RETRY_MUTATION")
        self.assertEqual(live.tools.calls[0][2]["humanize"], "auto")

    def test_mutation_request_id_is_at_most_once(self):
        temp, live, cip = self.make()
        self.addCleanup(temp.cleanup)
        obs = cip.see()["observation"]["id"]
        args = dict(
            request_id="request-0003",
            observation_id=obs,
            action={"kind": "back"},
            goal="Go back",
        )
        first = cip.act(**args)
        replay = cip.act(**args)
        self.assertTrue(replay["replayed"])
        self.assertEqual(len(live.tools.calls), 1)
        self.assertEqual(first["request_id"], replay["request_id"])

    def test_mutation_request_id_survives_engine_restart(self):
        temp, live, cip = self.make()
        self.addCleanup(temp.cleanup)
        obs = cip.see()["observation"]["id"]
        args = dict(
            request_id="request-restart-1",
            observation_id=obs,
            action={"kind": "back"},
            goal="Go back",
        )
        first = cip.act(**args)
        self.assertEqual(first["status"], "VERIFIED")
        replacement_live = FakeLive(live.root)
        replacement = CipEngine(replacement_live)
        replay = replacement.act(**args)
        self.assertTrue(replay["replayed"])
        self.assertEqual(replay["status"], "VERIFIED")
        self.assertEqual(replacement_live.tools.calls, [])

    def test_pending_journal_replays_uncertain_without_dispatch(self):
        temp, live, cip = self.make()
        self.addCleanup(temp.cleanup)
        obs = cip.see()["observation"]["id"]
        action = {"kind": "back"}
        goal = "Go back"
        req = "request-pending-1"
        from cyclone_phone_mcp.cip import _signature
        signed = _signature(
            {
                "generation": "g1",
                "device": "pixel",
                "observation_id": obs,
                "action": action,
                "goal": goal,
            }
        )
        cip._journal_begin(req, signed)
        replacement_live = FakeLive(live.root)
        replacement = CipEngine(replacement_live)
        replay = replacement.act(
            request_id=req,
            observation_id=obs,
            action=action,
            goal=goal,
        )
        self.assertEqual(replay["status"], "UNCERTAIN")
        self.assertEqual(replay["retry"], "NEVER_RETRY_MUTATION")
        self.assertEqual(replacement_live.tools.calls, [])

    def test_generation_change_revokes_observation(self):
        temp, live, cip = self.make()
        self.addCleanup(temp.cleanup)
        obs = cip.see()["observation"]["id"]
        (live.root / "control.json").write_text(
            json.dumps({"generation": "g2", "enabled": True, "stopped": False})
        )
        result = cip.dispatch("find", {"observation_id": obs, "query": "Go"})
        self.assertEqual(result["error"]["code"], "STALE_OBSERVATION")

    def test_bad_normalized_coordinate_is_rejected_before_mutation(self):
        temp, live, cip = self.make()
        self.addCleanup(temp.cleanup)
        obs = cip.see()["observation"]["id"]
        result = cip.dispatch(
            "act",
            {
                "request_id": "request-0004",
                "observation_id": obs,
                "action": {
                    "kind": "tap",
                    "target": {"point": {"x": 1.2, "y": .5}},
                },
                "goal": "Tap visible target",
            },
        )
        self.assertEqual(result["error"]["code"], "INVALID_COORDINATE")
        self.assertEqual(live.tools.calls, [])

    def test_action_grammar_rejects_irrelevant_fields(self):
        temp, live, cip = self.make()
        self.addCleanup(temp.cleanup)
        obs = cip.see()["observation"]["id"]
        result = cip.dispatch(
            "act",
            {
                "request_id": "request-bad-shape",
                "observation_id": obs,
                "action": {
                    "kind": "back",
                    "target": {"element_id": "e1"},
                },
            },
        )
        self.assertEqual(result["error"]["code"], "INVALID_REQUEST")
        self.assertEqual(live.tools.calls, [])

    def test_type_and_clear_require_explicit_authorization(self):
        temp, live, cip = self.make()
        self.addCleanup(temp.cleanup)
        for index, kind in enumerate(("type", "clear"), start=1):
            obs = cip.see()["observation"]["id"]
            action = {"kind": kind, "target": {"element_id": "e1"}}
            if kind == "type":
                action["text"] = "hello"
            result = cip.dispatch(
                "act",
                {
                    "request_id": f"request-auth-{index}",
                    "observation_id": obs,
                    "action": action,
                },
            )
            self.assertEqual(result["error"]["code"], "AUTHORIZATION_REQUIRED")
        self.assertEqual(live.tools.calls, [])

    def test_open_app_requires_package_id(self):
        temp, live, cip = self.make()
        self.addCleanup(temp.cleanup)
        obs = cip.see()["observation"]["id"]
        result = cip.dispatch(
            "act",
            {
                "request_id": "request-open-bad",
                "observation_id": obs,
                "action": {"kind": "open_app", "package": "Chrome"},
            },
        )
        self.assertEqual(result["error"]["code"], "INVALID_REQUEST")
        self.assertEqual(live.tools.calls, [])


if __name__ == "__main__":
    unittest.main()
