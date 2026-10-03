"""V3.8.2 Agent D: type authorization/redaction and skill save/run contracts."""
from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path as P

from cyclone_phone_mcp.mcp_server import DEFAULT_SURFACE, McpServer
from cyclone_phone_mcp.reports import SessionRecorder
from cyclone_phone_mcp.skills import matched_verified_skill
from cyclone_phone_mcp.surface import PhoneTools

FG = {"session_id": "default-foreground"}


class SkillGateway:
    def __init__(self):
        self.writes = []
        self.runs = []
        self.mutations = []
        self.actions = []
        self.skills = {
            "skill.draft.wifi": {
                "id": "skill.draft.wifi", "status": "draft", "enabled": False,
                "goal": "Open Wi-Fi", "pageKey": "home",
            }
        }

    def status(self):
        return {"ok": True, "state": "READY", "device_id": "dev_pixel8"}

    def device_status(self, device_id):
        return {"device_id": device_id, "status": {"ready": True}}

    def observe(self, **kwargs):
        return self.device_observe("dev_pixel8", **kwargs)

    def device_observe(self, device_id, *, include_screenshot=False, mode="compact", **kwargs):
        return {
            "device_id": device_id,
            "observation": {
                "pageKey": "home",
                "pageText": {"protocol": "cyclone-page-text-v1", "lines": [{"text": "Home"}, {"text": "Phone task input"}]},
                "pageSummary": {"protocol": "cyclone-page-summary-v1", "title": "Home", "contentNote": "Launcher", "buttons": ["Settings"]},
                "controls": [
                    {"id": "settings", "label": "Settings", "clickable": True},
                    {"id": "task-input", "label": "Phone task input", "editable": True, "clickable": True},
                ],
            },
            "witness": {"observation_id": "obs-1", "page_key": "home"},
        }

    def ui_search(self, query, **kwargs):
        return {"candidates": [{"id": "settings", "label": query}]}

    def device_ui_search(self, device_id, query, **kwargs):
        return {"results": [{"id": "settings", "label": query}, {"id": "task-input", "label": "Phone task input"}]}

    def action(self, tool, params, goal, **kwargs):
        return self.device_action("dev_pixel8", tool, params, goal)

    def device_action(self, device_id, tool, params, goal, **kwargs):
        from cyclone_phone_mcp.desktop_envelope import normalize_desktop_action
        self.actions.append({"tool": tool, "params": params})
        android = {"ok": True, "error": None}
        typed = params.get("value") or params.get("text")
        raw = {
            "protocol_version": "cyclone.gateway.capability.v1",
            "ok": True,
            "transport": {"ok": True},
            "execution": {
                "ok": True, "authoritative": "ANDROID", "status": "android_succeeded",
                "androidExecution": android, "android_execution": android,
                "typed": typed, "params": dict(params),
            },
            "verification": {"ok": True, "passed": True, "status": "PASSED", "after_observation_id": "obs-after-1"},
            "afterState": {"pageKey": "home"},
            "error": None,
        }
        return normalize_desktop_action(device_id, tool, raw)

    def skill_match(self, goal, page_key=""):
        for skill in self.skills.values():
            if skill["goal"].lower() == goal.lower():
                return {"matched": skill["status"] == "verified", "skill": skill}
        return {"matched": False, "skills": []}

    def device_skill_match(self, device_id, goal, page_key=""):
        return self.skill_match(goal, page_key)

    def skill_get(self, skill_id):
        return self.skills.get(skill_id)

    def device_skill_get(self, device_id, skill_id):
        return self.skill_get(skill_id)

    def skill_save(self, payload):
        self.writes.append(payload)
        skill = {**payload, "id": "skill.draft.from-mcp", "status": "draft", "enabled": False}
        self.skills[skill["id"]] = skill
        return {"skill": skill, "ok": True}

    def device_skill_save(self, device_id, payload):
        return self.skill_save(payload)

    def skill_run(self, skill_id, *, dry_run=False, params=None):
        self.runs.append({"skill_id": skill_id, "dry_run": dry_run})
        if self.skills[skill_id]["status"] == "draft" and not dry_run:
            self.mutations.append("live-draft")
            return {"ok": False, "status": "draft", "denied": True, "errorClass": "DRAFT_RUN_DENIED", "steps": []}
        if not dry_run:
            self.mutations.append("live-run")
        return {
            "ok": True, "status": self.skills[skill_id]["status"], "dryRun": dry_run,
            "steps": [
                {"ok": True, "kind": "phone_action_result", "tool": "phone.click", "pageChanged": True,
                 "before": {"pageKey": "home"}, "after": {"pageKey": "settings", "pageCard": {"pageKey": "settings"}},
                 "delta": "Page changed: home -> settings.", "errorClass": None, "generation": "obs-1"},
            ],
        }

    def device_skill_run(self, device_id, skill_id, *, dry_run=False, params=None):
        return self.skill_run(skill_id, dry_run=dry_run, params=params)


def _verified_step(tool, page_from, page_to):
    return {
        "tool": tool,
        "params": {"package": "com.android.settings"} if tool == "phone.open_app" else {"elementId": "apps-row"},
        "envelope": {
            "kind": "phone_action_result", "ok": True, "tool": tool,
            "pageChanged": page_from != page_to,
            "actionStatus": {"transport": "ok", "execution": "ok", "gatewayVerification": "passed", "afterObserved": True, "verified": True},
            "beforePageCard": {"pageKey": page_from},
            "afterPageCard": {"pageKey": page_to, "pageText": "Settings", "pageSummary": "Settings home"},
            "errorClass": None, "generation": "obs-pixel",
        },
    }


class TypeSkillTests(unittest.TestCase):
    def test_packaged_companion_locate_keeps_page_text(self):
        gateway = SkillGateway()
        with tempfile.TemporaryDirectory() as report_dir:
            server = McpServer(PhoneTools(gateway, SessionRecorder(report_dir)))
            listed = server.handle({"jsonrpc": "2.0", "id": 1, "method": "tools/list"})
            names = {tool["name"] for tool in listed["result"]["tools"]}
            self.assertTrue({"phone_locate", "phone_skill_save", "phone_skill_run"}.issubset(names))
            self.assertEqual(list(DEFAULT_SURFACE)[:3], listed["result"]["defaultSurface"][:3])
            located = server.handle({
                "jsonrpc": "2.0", "id": 2, "method": "tools/call",
                "params": {"name": "phone_locate", "arguments": {"device_id": "dev_pixel8", "goal": "Open Settings", "session_id": "default-foreground"}},
            })
        payload = json.loads(located["result"]["content"][0]["text"])
        self.assertIn("Home", payload["pageCard"]["pageText"])
        self.assertFalse(payload.get("skipModel"))

    def test_authorized_type_succeeds_without_plaintext_leak(self):
        secret = "Open Settings Apps quietly"
        gateway = SkillGateway()
        with tempfile.TemporaryDirectory() as report_dir:
            tools = PhoneTools(gateway, SessionRecorder(report_dir))
            located = json.loads(tools.call("phone_locate", {"device_id": "dev_pixel8", "goal": "Phone task input", **FG})[0]["text"])
            element_id = next(
                item["elementId"] for item in located["pageCard"]["candidates"]["current"]
                if item.get("editable") or "task" in str(item.get("label") or "").lower()
            )
            denied = tools.call("phone_act", {
                "device_id": "dev_pixel8", "tool": "phone.type",
                "params": {"elementId": element_id, "value": secret}, "goal": "Type a harmless task", **FG,
            })[0]["text"]
            self.assertIn("user_authorized", denied)
            self.assertNotIn(secret, denied)
            allowed = json.loads(tools.call("phone_act", {
                "device_id": "dev_pixel8", "tool": "phone.type",
                "params": {"elementId": element_id, "value": secret},
                "goal": "Type a harmless task", "user_authorized": True, **FG,
            })[0]["text"])
            dumped = json.dumps(allowed)
            report = json.dumps(json.loads(next(P(report_dir).glob("*.json")).read_text()))
        outbound = gateway.actions[-1]["params"]
        self.assertTrue(outbound["user_authorized"])
        self.assertTrue(outbound["userAuthorized"])
        self.assertEqual(secret, outbound.get("value"))
        self.assertTrue(allowed["ok"])
        self.assertNotIn(secret, dumped)
        self.assertNotIn(secret, report)

    def test_skill_save_verified_steps_are_disabled_draft(self):
        gateway = SkillGateway()
        payload = json.loads(PhoneTools(gateway).call("phone_skill_save", {
            "device_id": "dev_pixel8", "goal": "Open Settings Apps", "pageKey": "home",
            "steps": [_verified_step("phone.open_app", "home", "settings"), _verified_step("phone.click", "settings", "settings")],
        })[0]["text"])
        self.assertTrue(payload["ok"])
        self.assertTrue(payload["written"])
        self.assertEqual("draft", payload["status"])
        self.assertIs(False, payload["enabled"])
        self.assertEqual(1, len(gateway.writes))
        self.assertEqual([], [w for w in gateway.writes if w.get("enabled") is True])

    def test_skill_save_unverified_writes_nothing(self):
        gateway = SkillGateway()
        payload = json.loads(PhoneTools(gateway).call("phone_skill_save", {
            "goal": "Open Settings Apps",
            "steps": [
                {"tool": "phone.click", "ok": False, "verified": False, "envelope": {"ok": False, "errorClass": "PROTOCOL_MISMATCH"}},
                _verified_step("phone.click", "home", "settings"),
            ],
        })[0]["text"])
        self.assertFalse(payload["ok"])
        self.assertFalse(payload["written"])
        self.assertEqual("UNVERIFIED_STEPS", payload["errorClass"])
        self.assertEqual([], gateway.writes)

    def test_skill_run_denies_live_draft_permits_dry_run_without_mutation(self):
        gateway = SkillGateway()
        tools = PhoneTools(gateway)
        live = json.loads(tools.call("phone_skill_run", {"skill_id": "skill.draft.wifi", **FG})[0]["text"])
        self.assertFalse(live["ok"])
        self.assertEqual("DRAFT_RUN_DENIED", live["errorClass"])
        self.assertEqual([], gateway.runs)
        self.assertEqual([], gateway.mutations)
        dry = json.loads(tools.call("phone_skill_run", {"skill_id": "skill.draft.wifi", "dryRun": True, **FG})[0]["text"])
        self.assertTrue(dry["ok"])
        self.assertTrue(dry["dryRun"])
        self.assertEqual(1, len(gateway.runs))
        self.assertTrue(gateway.runs[0]["dry_run"])
        self.assertEqual([], gateway.mutations)

    def test_draft_skill_match_never_skip_model(self):
        self.assertIsNone(matched_verified_skill(
            {"skill": {"id": "skill.draft.wifi", "status": "draft", "goal": "Open Wi-Fi", "pageKey": "home"}},
            "Open Wi-Fi", "home",
        ))
        located = json.loads(PhoneTools(SkillGateway()).call("phone_locate", {"goal": "Open Wi-Fi", **FG})[0]["text"])
        self.assertIsNone(located["matchedSkill"])
        self.assertFalse(located["skipModel"])


if __name__ == "__main__":
    unittest.main()
