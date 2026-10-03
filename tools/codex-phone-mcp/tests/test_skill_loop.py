import json
import unittest

from cyclone_phone_mcp.compact import compact_observation
from cyclone_phone_mcp.mcp_server import DEFAULT_SURFACE, McpServer
from cyclone_phone_mcp.gateway import GatewayError
from cyclone_phone_mcp.skills import (
    ANDROID_SKILL_OPS_MISSING,
    COMPILER,
    STORE_CLASS,
    build_save_payload,
    matched_verified_skill,
    normalize_run,
    strip_secret_slots,
)
from cyclone_phone_mcp.surface import PhoneTools

FG = {"session_id": "default-foreground"}


def _verified_step(tool, page_from, page_to, label="Apps"):
    return {
        "tool": tool,
        "verified": True,
        "ok": True,
        "params": {"elementId": "obs-scoped-id", "selector": {"elementId": "obs-scoped-id", "label": label}},
        "envelope": {
            "ok": True,
            "pageChanged": page_from != page_to,
            "before": {"pageKey": page_from, "package": "com.android.launcher3", "activity": "Home"},
            "after": {
                "pageKey": page_to,
                "package": "com.android.settings",
                "activity": "Settings",
                "pageCard": {"pageKey": page_to, "pageText": "Settings", "pageSummary": "Settings home"},
            },
            "delta": {"appeared": ["Settings"], "disappeared": [], "focused": None},
            "errorClass": None,
            "generation": "obs-1",
        },
    }


class SkillGateway:
    def __init__(self):
        self.writes = []
        self.runs = []
        self.skills = {
            "skill.verified.open-settings": {
                "id": "skill.verified.open-settings",
                "status": "verified",
                "goal": "Open Settings",
                "pageKey": "home",
                "storeClass": STORE_CLASS,
                "steps": [
                    {"ok": True, "kind": "phone_action_result", "tool": "phone.click",
                     "pageChanged": True, "before": {"pageKey": "home"},
                     "after": {"pageKey": "settings", "pageCard": {"pageKey": "settings", "pageText": "Settings"}},
                     "delta": "Page changed: home → settings.", "errorClass": None, "generation": "obs-1"},
                    {"ok": True, "kind": "phone_action_result", "tool": "phone.click",
                     "pageChanged": False, "before": {"pageKey": "settings"},
                     "after": {"pageKey": "settings", "pageCard": {"pageKey": "settings", "pageText": "Settings"}},
                     "delta": "No location or current-candidate change was observed.",
                     "errorClass": None, "generation": "obs-2"},
                ],
            },
            "skill.draft.wifi": {
                "id": "skill.draft.wifi",
                "status": "draft",
                "goal": "Open Wi-Fi",
                "pageKey": "home",
                "storeClass": STORE_CLASS,
                "steps": [{"ok": True}, {"ok": True}],
            },
        }

    def status(self):
        return {"ok": True, "state": "READY"}

    def observe(self, **kwargs):
        return {
            "pageKey": "home",
            "package": "com.android.launcher3",
            "activity": "Home",
            "title": "Home",
            "pageText": "Home screen with apps and Settings.",
            "pageSummary": "Launcher home",
            "controls": [{"id": "settings", "label": "Settings", "clickable": True}],
        }

    def ui_search(self, query, **kwargs):
        return {"candidates": [{"id": "settings", "label": query}]}

    def skill_match(self, goal, page_key=""):
        for skill in self.skills.values():
            if skill["goal"].lower() != goal.lower():
                continue
            if page_key and skill["pageKey"] != page_key:
                continue
            return {"matched": skill["status"] == "verified", "skill": skill}
        return {"matched": False, "skills": []}

    def skill_get(self, skill_id):
        return self.skills.get(skill_id)

    def skill_save(self, payload):
        self.writes.append(payload)
        skill_id = "skill.draft.from-mcp"
        skill = {
            **payload,
            "id": skill_id,
            "status": "draft",
            "enabled": False,
            "storeClass": STORE_CLASS,
            "compiler": COMPILER,
        }
        self.skills[skill_id] = skill
        return {"skill": skill, "storeClass": STORE_CLASS, "status": "draft"}

    def skill_run(self, skill_id, *, dry_run=False, params=None):
        self.runs.append({"skill_id": skill_id, "dry_run": dry_run, "params": params or {}})
        skill = self.skills[skill_id]
        if skill["status"] == "draft" and not dry_run:
            return {"ok": False, "status": "draft", "denied": True, "errorClass": "DRAFT_RUN_DENIED", "steps": []}
        return {"ok": True, "status": skill["status"], "storeClass": STORE_CLASS, "steps": skill["steps"], "dryRun": dry_run}


class MissingOpsGateway(SkillGateway):
    """Android skill.* ops are absent: UNKNOWN_OPERATION / CAPABILITY_UNAVAILABLE."""

    def skill_match(self, goal, page_key=""):
        raise GatewayError(
            "Android skill.match is not advertised",
            body={"error": {"code": "CAPABILITY_UNAVAILABLE", "layer": "CAPABILITY"}},
        )

    def skill_save(self, payload):
        raise GatewayError(
            "Android skill.compile is not advertised",
            body={"error": {"code": "UNKNOWN_OPERATION", "layer": "CAPABILITY"}},
        )

    def skill_run(self, skill_id, *, dry_run=False, params=None):
        raise GatewayError(
            "Android skill.run is not advertised",
            body={"error": {"code": "UNKNOWN_OPERATION", "layer": "CAPABILITY"}},
        )

    def skill_get(self, skill_id):
        raise GatewayError(
            "Android skill.get is not advertised",
            body={"error": {"code": "UNKNOWN_OPERATION", "layer": "CAPABILITY"}},
        )


class FakeListTools:
    def __init__(self):
        self.last_call_failed = False

    def call(self, name, arguments):
        return [{"type": "text", "text": "{}"}]


class SkillLoopTests(unittest.TestCase):
    def test_four_tools_listed_as_default_surface(self):
        server = McpServer(FakeListTools())
        response = server.handle({"jsonrpc": "2.0", "id": 1, "method": "tools/list"})
        tools = {tool["name"]: tool for tool in response["result"]["tools"]}
        surface = response["result"]["defaultSurface"]
        self.assertEqual(surface[:3], ["phone_status", "phone_locate", "phone_act"])
        self.assertEqual(set(surface[3:]), {"phone_skill_save", "phone_skill_run"})
        self.assertEqual(len(surface), 5)
        for name in surface:
            self.assertIn(name, tools)
            self.assertTrue(tools[name]["annotations"]["cycloneDefaultSurface"])
        self.assertFalse(tools["phone_devices"]["annotations"]["cycloneDefaultSurface"])
        self.assertNotIn("phone_shell", tools)

    def test_save_verified_two_plus_steps_writes_draft_in_automation_store(self):
        gateway = SkillGateway()
        tools = PhoneTools(gateway)
        steps = [
            _verified_step("phone.click", "home", "settings"),
            _verified_step("phone.click", "settings", "settings", label="Network"),
        ]
        payload = json.loads(tools.call("phone_skill_save", {
            "goal": "Open Settings",
            "pageKey": "home",
            "app": "com.android.settings",
            "steps": steps,
            "params": {"city": "Amsterdam"},
        })[0]["text"])
        self.assertTrue(payload["ok"])
        self.assertTrue(payload["written"])
        self.assertEqual("draft", payload["status"])
        self.assertEqual(STORE_CLASS, payload["storeClass"])
        self.assertEqual(COMPILER, payload["compiler"])
        self.assertIs(False, payload["enabled"])
        self.assertEqual(1, len(gateway.writes))
        written = gateway.writes[0]
        self.assertEqual("draft", written["status"])
        self.assertIs(False, written["enabled"])
        self.assertEqual(STORE_CLASS, written["storeClass"])
        self.assertEqual(COMPILER, written["compiler"])
        self.assertEqual(2, len(written["steps"]))
        self.assertNotIn("elementId", json.dumps(written["steps"]))

    def test_save_unverified_does_not_write(self):
        gateway = SkillGateway()
        tools = PhoneTools(gateway)
        unverified = [
            {"tool": "phone.click", "verified": False, "ok": False, "params": {"elementId": "x"}},
            _verified_step("phone.click", "home", "settings"),
        ]
        payload = json.loads(tools.call("phone_skill_save", {
            "goal": "Open Settings",
            "steps": unverified,
        })[0]["text"])
        self.assertFalse(payload["ok"])
        self.assertFalse(payload["written"])
        self.assertEqual("UNVERIFIED_STEPS", payload["errorClass"])
        self.assertEqual([], gateway.writes)

    def test_run_verified_returns_per_step_envelopes(self):
        tools = PhoneTools(SkillGateway())
        payload = json.loads(tools.call("phone_skill_run", {
            "skill_id": "skill.verified.open-settings",
            **FG,
        })[0]["text"])
        self.assertTrue(payload["ok"])
        self.assertEqual(2, len(payload["steps"]))
        for index, envelope in enumerate(payload["steps"]):
            self.assertEqual("phone_action_result", envelope["kind"])
            self.assertTrue(envelope["ok"])
            self.assertIn("pageChanged", envelope)
            self.assertIn("before", envelope)
            self.assertIn("after", envelope)
            self.assertIn("pageCard", envelope["after"])
            self.assertIn("delta", envelope)
            self.assertIn("errorClass", envelope)
            self.assertIn("generation", envelope)
            self.assertEqual(index, envelope["stepIndex"])

    def test_run_draft_without_dry_run_is_denied(self):
        gateway = SkillGateway()
        tools = PhoneTools(gateway)
        payload = json.loads(tools.call("phone_skill_run", {
            "skill_id": "skill.draft.wifi",
            **FG,
        })[0]["text"])
        self.assertFalse(payload["ok"])
        self.assertEqual("DRAFT_RUN_DENIED", payload["errorClass"])
        self.assertEqual([], payload["steps"])
        self.assertEqual([], gateway.runs)
        dry = json.loads(tools.call("phone_skill_run", {
            "skill_id": "skill.draft.wifi",
            "dryRun": True,
            **FG,
        })[0]["text"])
        self.assertTrue(dry["ok"])
        self.assertTrue(dry["dryRun"])
        self.assertEqual(1, len(gateway.runs))
        self.assertTrue(gateway.runs[0]["dry_run"])

    def test_locate_goal_includes_page_text_and_page_summary(self):
        tools = PhoneTools(SkillGateway())
        located = json.loads(tools.call("phone_locate", {"goal": "Open Settings", **FG})[0]["text"])
        card = located["pageCard"]
        self.assertEqual("page_card", card["kind"])
        self.assertIn("Home screen", card["pageText"])
        self.assertEqual("Launcher home", card["pageSummary"])
        self.assertEqual("home", card["pageKey"])
        self.assertIsNotNone(located["matchedSkill"])
        self.assertTrue(located["matchedSkill"]["skipModel"])
        self.assertTrue(located["skipModel"])
        self.assertIn("phone_skill_run", located["next"])

    def test_secret_slot_stripped_on_save(self):
        gateway = SkillGateway()
        tools = PhoneTools(gateway)
        payload = json.loads(tools.call("phone_skill_save", {
            "goal": "Sign in",
            "pageKey": "login",
            "steps": [
                _verified_step("phone.click", "login", "login", label="Email"),
                _verified_step("phone.click", "login", "home", label="Submit"),
            ],
            "params": {"password": "super-secret-password", "city": "Amsterdam", "otp": "123456"},
        })[0]["text"])
        self.assertTrue(payload["ok"])
        dumped = json.dumps(gateway.writes)
        self.assertNotIn("super-secret-password", dumped)
        self.assertNotIn("123456", dumped)
        self.assertNotIn("password", dumped.lower())
        self.assertIn("Amsterdam", dumped)
        self.assertEqual({"city": "Amsterdam"}, gateway.writes[0]["params"])

    def test_strip_secret_slots_helper_and_unverified_builder(self):
        stripped = strip_secret_slots({"password": "x", "token": "y", "label": "Wi-Fi"})
        self.assertEqual({"label": "Wi-Fi"}, stripped)
        denied = build_save_payload({"goal": "x", "steps": [{"ok": True}]})
        self.assertFalse(denied["written"])
        match = matched_verified_skill(
            {"skill": {"id": "s1", "status": "verified", "goal": "Open Settings", "pageKey": "home"}},
            "Open Settings",
            "home",
        )
        self.assertEqual("s1", match["id"])
        run = normalize_run({"status": "draft", "denied": True}, skill_id="draft-1", dry_run=False)
        self.assertEqual("DRAFT_RUN_DENIED", run["errorClass"])

    def test_locate_draft_skill_does_not_skip_model(self):
        tools = PhoneTools(SkillGateway())
        located = json.loads(tools.call("phone_locate", {"goal": "Open Wi-Fi", **FG})[0]["text"])
        self.assertEqual("page_card", located["pageCard"]["kind"])
        self.assertIsNone(located["matchedSkill"])
        self.assertFalse(located["skipModel"])
        self.assertNotIn("phone_skill_run", located["next"])

    def test_save_missing_android_ops_fail_closed(self):
        gateway = MissingOpsGateway()
        tools = PhoneTools(gateway)
        payload = json.loads(tools.call("phone_skill_save", {
            "goal": "Open Settings",
            "pageKey": "home",
            "steps": [
                _verified_step("phone.click", "home", "settings"),
                _verified_step("phone.click", "settings", "settings", label="Network"),
            ],
        })[0]["text"])
        self.assertFalse(payload["ok"])
        self.assertFalse(payload["written"])
        self.assertEqual(ANDROID_SKILL_OPS_MISSING, payload["errorClass"])
        self.assertEqual([], gateway.writes)

    def test_run_missing_android_ops_fail_closed(self):
        gateway = MissingOpsGateway()
        tools = PhoneTools(gateway)
        payload = json.loads(tools.call("phone_skill_run", {
            "skill_id": "skill.verified.open-settings",
            **FG,
        })[0]["text"])
        self.assertFalse(payload["ok"])
        self.assertEqual(ANDROID_SKILL_OPS_MISSING, payload["errorClass"])
        self.assertEqual([], payload.get("steps") or [])
        self.assertEqual([], gateway.runs)

    def test_locate_missing_android_ops_fail_closed_keeps_page_card(self):
        tools = PhoneTools(MissingOpsGateway())
        located = json.loads(tools.call("phone_locate", {"goal": "Open Settings", **FG})[0]["text"])
        self.assertEqual("phone_locate", located["kind"])
        self.assertEqual("page_card", located["pageCard"]["kind"])
        self.assertIn("Home screen", located["pageCard"]["pageText"])
        self.assertIsNone(located["matchedSkill"])
        self.assertFalse(located["skipModel"])
        self.assertEqual(ANDROID_SKILL_OPS_MISSING, located["errorClass"])
        self.assertFalse(located["ok"])

    def test_http_200_run_without_ok_envelopes_is_not_success(self):
        gateway = SkillGateway()
        gateway.skills["skill.verified.open-settings"]["steps"] = [
            {"kind": "phone_action_result", "ok": False, "errorClass": "VERIFICATION_FAILED"},
            {"kind": "phone_action_result", "ok": True},
        ]
        payload = json.loads(PhoneTools(gateway).call("phone_skill_run", {
            "skill_id": "skill.verified.open-settings",
            **FG,
        })[0]["text"])
        self.assertFalse(payload["ok"])
        self.assertEqual(2, len(payload["steps"]))

    def test_compact_observation_keeps_page_text_for_locate_contract(self):
        card = compact_observation({
            "observation": {
                "pageKey": "home",
                "pageText": "Home screen with apps.",
                "pageSummary": "Launcher home",
                "controls": [{"id": "1", "label": "Apps"}],
            }
        }, goal="Open Apps")
        self.assertEqual("Home screen with apps.", card["pageText"])
        self.assertEqual("Launcher home", card["pageSummary"])


if __name__ == "__main__":
    unittest.main()
