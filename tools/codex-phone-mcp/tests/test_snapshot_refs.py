import json
import tempfile
import unittest

from cyclone_phone_mcp.compact import compact_observation, build_snapshot
from cyclone_phone_mcp.reports import SessionRecorder
from cyclone_phone_mcp.tools import PhoneTools

FG = {"session_id": "default-foreground"}


class SnapshotCompactTests(unittest.TestCase):
    def test_snapshot_yaml_and_refs_for_settings_clock_calculator(self):
        controls = [
            {"id": "wifi", "label": "Network & internet", "role": "row", "clickable": True},
            {"id": "apps", "label": "Apps", "role": "row", "clickable": True},
            {"id": "timer", "label": "Timer", "role": "tab", "clickable": True, "selected": True},
            {"id": "alarm", "label": "Alarm", "role": "tab", "clickable": True, "selected": False},
            {"id": "seven", "label": "7", "role": "button", "clickable": True},
            {"id": "composer", "label": "Phone task input", "role": "textbox", "editable": True, "focused": True},
        ]
        for extra in range(20):
            controls.append({"id": f"pad{extra}", "label": str(extra % 10), "role": "button", "clickable": True})
        card = compact_observation({"pageKey": "mixed", "controls": controls}, goal="7")
        self.assertIn("snapshot", card)
        self.assertIn('tab "Timer" [ref=', card["snapshot"])
        self.assertIn("[selected]", card["snapshot"])
        self.assertIn('button "7" [ref=', card["snapshot"])
        self.assertTrue(card["refs"])
        self.assertGreaterEqual(len(card["refs"]), 8)
        self.assertLessEqual(len(card["refs"]), 80)
        ranked = card["candidates"]["goalRanked"]
        self.assertTrue(ranked)
        self.assertEqual("7", ranked[0]["label"])
        current_ids = {item.get("elementId") for item in card["candidates"]["current"]}
        snapshot_ids = {host.get("elementId") for host in card["refs"].values()}
        self.assertIn("seven", snapshot_ids)
        self.assertEqual(12, len(card["candidates"]["current"]))

    def test_files_row_snapshot_keeps_apk_filename(self):
        card = compact_observation({
            "pageKey": "files",
            "controls": [
                {"id": "row", "label": "Cyclone-3.6.0-beta.2.apk Tue, 01 Sept 69,98 MB", "role": "row", "clickable": True},
                {"id": "date", "label": "Tue, 01 Sept", "role": "button", "clickable": True},
            ],
        }, goal="Cyclone-3.6.0-beta")
        self.assertIn("Cyclone-3.6.0-beta.2.apk", card["snapshot"])
        ranked = card["candidates"]["goalRanked"]
        self.assertTrue(ranked)
        self.assertIn("Cyclone-3.6.0-beta.2.apk", ranked[0]["label"])

    def test_one_character_goal_ranks_instead_of_emptying(self):
        card = compact_observation({
            "pageKey": "calc",
            "controls": [
                {"id": "seven", "label": "7", "role": "button", "clickable": True},
                {"id": "eight", "label": "8", "role": "button", "clickable": True},
            ],
        }, goal="7")
        self.assertEqual("seven", card["candidates"]["goalRanked"][0]["elementId"])


class SnapshotActTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.gateway = FreshSessionGateway()
        self.tools = PhoneTools(self.gateway, SessionRecorder(self.temp.name))

    def tearDown(self):
        self.temp.cleanup()

    def test_auto_observe_before_first_mutate(self):
        payload = json.loads(self.tools.call("phone_act", {
            "tool": "phone.click",
            "params": {"ref": "e1"},
            "goal": "Apps",
            **FG,
        })[0]["text"])
        self.assertNotEqual("STALE_OBSERVATION", payload.get("errorClass"))
        self.assertTrue(payload.get("ok") or payload.get("afterPageCard"))
        self.assertGreaterEqual(self.gateway.observe_calls, 1)

    def test_stale_ref_does_not_mutate(self):
        self.tools.call("phone_observe", dict(FG))
        before = self.gateway.action_calls
        payload = json.loads(self.tools.call("phone_act", {
            "tool": "phone.click",
            "params": {"ref": "e99"},
            "goal": "missing",
            **FG,
        })[0]["text"])
        self.assertEqual("STALE_OBSERVATION", payload["errorClass"])
        self.assertEqual(before, self.gateway.action_calls)

    def test_ref_and_role_name_resolve_against_current_snapshot(self):
        located = json.loads(self.tools.call("phone_locate", {"goal": "Apps", **FG})[0]["text"])
        ref = None
        for key, host in located["pageCard"]["refs"].items():
            if host.get("name") == "Apps":
                ref = key
                break
        self.assertIsNotNone(ref)
        clicked = json.loads(self.tools.call("phone_act", {
            "tool": "phone.click",
            "params": {"ref": ref},
            "goal": "Apps",
            **FG,
        })[0]["text"])
        self.assertTrue(clicked["ok"])
        self.assertIn("snapshot", clicked["afterPageCard"])
        named = json.loads(self.tools.call("phone_act", {
            "tool": "phone.click",
            "params": {"role": "button", "name": "Apps"},
            "goal": "Apps",
            **FG,
        })[0]["text"])
        self.assertTrue(named["ok"])


class FreshSessionGateway:
    def __init__(self):
        self.observe_calls = 0
        self.action_calls = 0

    def status(self):
        return {"ok": True, "available": True}

    def devices(self, *, scan=False):
        return {"surface": "fleet", "devices": [{"deviceId": "dev_a"}]}

    def observe(self, **kwargs):
        self.observe_calls += 1
        return {
            "witness": {"observation_id": f"obs-{self.observe_calls}"},
            "observation": {
                "pageKey": "home",
                "title": "Home",
                "pageText": "Apps. See all.",
                "controls": [
                    {"id": "apps", "label": "Apps", "role": "button", "clickable": True},
                    {"id": "seven", "label": "7", "role": "button", "clickable": True},
                ],
            },
        }

    def ui_search(self, query, **kwargs):
        return {"results": [{"id": "apps", "label": query, "clickable": True}]}

    def action(self, tool, params, goal, **kwargs):
        self.action_calls += 1
        return {
            "protocol_version": "cyclone.gateway.capability.v1",
            "capability_id": tool,
            "ok": True,
            "transport": {"ok": True},
            "execution": {"ok": True},
            "verification": {"ok": True, "status": "verified"},
            "error": None,
        }

    def debug_bundle(self, expected, goal):
        return {}

    def teach_start(self, goal):
        return {"active": True}

    def teach_status(self):
        return {"active": True}

    def teach_stop(self, compile_for_review):
        return {"active": False}


if __name__ == "__main__":
    unittest.main()
