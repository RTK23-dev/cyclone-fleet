"""V3.8.2 Agent D: consume Agent A capability.v1 LayerOutcome; leftover Pixel blobs are not PROTOCOL_MISMATCH."""
from __future__ import annotations

import unittest

from cyclone_phone_mcp.compact import compact_observation
from cyclone_phone_mcp.desktop_envelope import normalize_desktop_action
from cyclone_phone_mcp.protocol import classify_failure


PIXEL_PAGE_TEXT_V1 = {
    "protocol": "cyclone-page-text-v1",
    "lines": [
        {"text": "Settings"},
        {"text": "Network & internet"},
        {"text": "Apps"},
    ],
}

PIXEL_PAGE_SUMMARY_V1 = {
    "protocol": "cyclone-page-summary-v1",
    "title": "Settings",
    "contentNote": "4 visible text lines, 12 interactive nodes",
    "buttons": ["Network & internet", "Apps"],
}


def agent_a_translated_success(*, android_ok=True, verification_ok=True, after_id="obs-after-1"):
    android = {"ok": android_ok, "error": None if android_ok else {"code": "ELEMENT_NOT_FOUND"}}
    return {
        "protocol_version": "cyclone.gateway.capability.v1",
        "capability_id": "phone.open_app",
        "device_id": "dev_pixel8",
        "ok": bool(android_ok and verification_ok),
        "transport": {"ok": True},
        "execution": {
            "ok": android_ok,
            "authoritative": "ANDROID",
            "status": "android_succeeded" if android_ok else "android_failed",
            "androidExecution": android,
            "android_execution": android,
        },
        "verification": {
            "ok": verification_ok,
            "passed": verification_ok,
            "status": "PASSED" if verification_ok else "FAILED",
            "after_observation_id": after_id,
        },
        "afterState": {"observationId": after_id, "pageKey": "settings::root", "package": "com.android.settings"},
        "error": None if android_ok and verification_ok else {
            "code": "ELEMENT_NOT_FOUND" if not android_ok else "VERIFICATION_FAILED",
            "layer": "EXECUTION" if not android_ok else "VERIFICATION",
        },
    }


def leftover_pixel_desktop(*, android_ok=True, verification_ok=True, after_id="obs-after-1"):
    blob = {
        "execution": {"ok": android_ok, "error": None},
        "androidExecution": {"ok": android_ok},
        "verification": {"ok": verification_ok, "status": "PASSED" if verification_ok else "FAILED", "pageChanged": android_ok},
        "pageChanged": android_ok,
    }
    assert "ok" not in blob
    return {
        "transport": {"ok": True},
        "execution": blob,
        "verification": {
            "passed": bool(android_ok and verification_ok and after_id),
            "status": "PASSED" if verification_ok else "FAILED",
            "after_observation_id": after_id,
            "after_page_key": "settings::root",
        },
        "after": {"pageKey": "settings::root", "pageText": PIXEL_PAGE_TEXT_V1, "pageSummary": PIXEL_PAGE_SUMMARY_V1},
    }


class AgentAEnvelopeTests(unittest.TestCase):
    def test_agent_a_translated_success_is_canonical_ok_not_protocol_mismatch(self):
        translated = agent_a_translated_success()
        self.assertTrue(translated["ok"])
        self.assertTrue(translated["execution"]["ok"])
        self.assertTrue(translated["execution"]["androidExecution"]["ok"])
        self.assertTrue(translated["verification"]["ok"])
        self.assertTrue(translated["verification"]["passed"])
        self.assertIsNone(classify_failure(translated))
        passed = normalize_desktop_action("dev_pixel8", "phone.open_app", translated)
        self.assertIs(passed, translated)
        self.assertIsNone(classify_failure(passed))

    def test_nested_blob_without_layer_ok_is_ok_after_translation(self):
        leftover = leftover_pixel_desktop()
        self.assertIsNone(leftover["execution"].get("ok"))
        canonical = normalize_desktop_action("dev_pixel8", "phone.open_app", leftover)
        self.assertIsNone(classify_failure(canonical), classify_failure(canonical))
        self.assertTrue(canonical["ok"])
        self.assertTrue(canonical["execution"]["ok"])
        self.assertTrue(canonical["verification"]["passed"])
        self.assertNotEqual("PROTOCOL_MISMATCH", (canonical.get("error") or {}).get("code"))

    def test_android_failure_stays_failure(self):
        translated = agent_a_translated_success(android_ok=False, verification_ok=False)
        self.assertIs(normalize_desktop_action("dev_pixel8", "phone.open_app", translated), translated)
        failure = classify_failure(translated)
        self.assertIsNotNone(failure)
        self.assertEqual("EXECUTION_FAILED", failure.code)
        leftover = leftover_pixel_desktop(android_ok=False, verification_ok=False)
        canonical = normalize_desktop_action("dev_pixel8", "phone.open_app", leftover)
        self.assertFalse(canonical["ok"])
        self.assertEqual("EXECUTION_FAILED", classify_failure(canonical).code)

    def test_verification_disagreement_is_fail_closed(self):
        translated = agent_a_translated_success(android_ok=True, verification_ok=False)
        failure = classify_failure(translated)
        self.assertIsNotNone(failure)
        self.assertEqual("VERIFICATION_FAILED", failure.code)
        leftover = leftover_pixel_desktop(android_ok=True, verification_ok=False)
        leftover["verification"]["passed"] = False
        canonical = normalize_desktop_action("dev_pixel8", "phone.open_app", leftover)
        self.assertFalse(canonical["ok"])
        self.assertEqual("VERIFICATION_FAILED", classify_failure(canonical).code)

    def test_malformed_execution_without_ok_is_fail_closed(self):
        leftover = leftover_pixel_desktop()
        leftover["execution"] = {"status": "mystery"}
        canonical = normalize_desktop_action("dev_pixel8", "phone.open_app", leftover)
        self.assertFalse(canonical["ok"])
        self.assertFalse(canonical["execution"]["ok"])
        self.assertIsNotNone(classify_failure(canonical))

    def test_non_bool_execution_ok_is_fail_closed(self):
        leftover = {
            "transport": {"ok": True},
            "execution": {"ok": "yes", "androidExecution": {"ok": "yes"}},
            "verification": {"passed": True, "after_observation_id": "obs-after-1"},
        }
        canonical = normalize_desktop_action("dev_pixel8", "phone.open_app", leftover)
        self.assertFalse(canonical["ok"])
        self.assertIsNotNone(classify_failure(canonical))

    def test_agent_a_malformed_envelope_keeps_protocol_mismatch(self):
        translated = agent_a_translated_success()
        translated["ok"] = False
        translated["execution"] = {"ok": False, "authoritative": "ANDROID", "status": "malformed", "androidExecution": None}
        translated["verification"] = {"ok": False, "passed": False}
        translated["error"] = {"code": "PROTOCOL_MISMATCH", "layer": "PROTOCOL"}
        passed = normalize_desktop_action("dev_pixel8", "phone.open_app", translated)
        self.assertIs(passed, translated)
        self.assertEqual("PROTOCOL_MISMATCH", passed["error"]["code"])
        self.assertIsNotNone(classify_failure(passed))

    def test_compact_preserves_cyclone_page_text_v1_objects(self):
        card = compact_observation({
            "observation": {
                "pageKey": "settings::root",
                "pageText": PIXEL_PAGE_TEXT_V1,
                "pageSummary": PIXEL_PAGE_SUMMARY_V1,
                "controls": [{"id": "apps-row", "label": "Apps", "clickable": True}],
            }
        }, goal="Apps")
        self.assertIn("Network & internet", card["pageText"])
        self.assertIn("Apps", card["pageText"])
        self.assertIn("Settings", card["pageSummary"])


if __name__ == "__main__":
    unittest.main()
