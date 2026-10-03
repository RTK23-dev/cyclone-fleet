import unittest

from cyclone_phone_mcp.compact import compact_observation, redact


class CompactTests(unittest.TestCase):
    def test_caps_controls_and_preserves_counts(self):
        payload = {"pageKey": "p", "rawNodeCount": 2500, "semanticControlCount": 80, "agentControlCount": 36, "controls": [{"id": str(i), "label": f"C{i}"} for i in range(80)]}
        result = compact_observation(payload)
        self.assertEqual(len(result["controls"]), 12)
        self.assertEqual(result["counts"]["raw"], 2500)
        self.assertEqual(result["counts"]["semantic"], 80)
        self.assertEqual(result["counts"]["agent"], 36)

    def test_redacts_secret_keys(self):
        self.assertEqual(redact({"password": "x", "otp_code": "123456"}), {"password": "<redacted>", "otp_code": "<redacted>"})

    def test_typed_observation_retains_correlation_and_witness(self):
        result = compact_observation({
            "correlation_id": "corr-1",
            "witness": {"observation_id": "obs-1", "gateway_record_id": "record-1"},
            "observation": {"pageKey": "home", "controls": []},
        })
        self.assertEqual("corr-1", result["correlationId"])
        self.assertEqual("obs-1", result["witness"]["observation_id"])

    def test_page_card_preserves_meaningful_context_and_goal_ranks_candidates(self):
        result = compact_observation({
            "correlation_id": "corr-2",
            "witness": {"observation_id": "obs-2"},
            "observation": {
                "page": {
                    "package": "com.android.settings",
                    "activity": "Settings",
                    "title": "Apps",
                    "pageKey": "settings/apps",
                    "location": "Settings > Apps",
                    "pageText": "Apps. Recently opened apps and notifications.",
                    "pageSummary": "App settings list",
                    "controls": [
                        {"id": "nav", "label": "Network", "clickable": True},
                        {"id": "apps", "label": "Apps", "resourceId": "android:id/apps", "clickable": True},
                    ],
                },
                "counts": {"raw": 2200, "semantic": 80, "agent": 30},
            },
        }, goal="Open Apps")
        self.assertEqual("page_card", result["kind"])
        self.assertEqual("obs-2", result["observationScope"]["id"])
        self.assertEqual("Settings > Apps", result["location"]["location"])
        self.assertEqual("Apps", result["location"]["title"])
        self.assertIn("Recently opened", result["pageText"])
        self.assertEqual("App settings list", result["pageSummary"])
        self.assertEqual("apps", result["candidates"]["goalRanked"][0]["elementId"])
        self.assertTrue(result["truncated"]["rawTreeExcluded"])

    def test_page_card_preserves_session_identity(self):
        result = compact_observation({
            "sessionId": "workspace-a",
            "displayId": 7,
            "observation": {
                "pageKey": "home",
                "sessionId": "workspace-a",
                "displayId": 7,
                "controls": [],
            },
        })
        self.assertEqual("workspace-a", result["sessionId"])
        self.assertEqual(7, result["displayId"])
        self.assertEqual("workspace-a", result["observationScope"]["sessionId"])
        self.assertEqual(7, result["observationScope"]["displayId"])

    def test_page_card_preserves_element_index_and_perception_mode(self):
        result = compact_observation({
            "observation": {
                "pageKey": "settings",
                "perceptionMode": "a11y",
                "treeUseful": True,
                "controls": [
                    {"id": "apps", "label": "Apps", "clickable": True, "elementIndex": 1},
                    {"id": "network", "label": "Network", "clickable": True, "elementIndex": 2},
                ],
            },
        })
        self.assertEqual(1, result["candidates"]["current"][0]["elementIndex"])
        self.assertEqual("a11y", result["perceptionMode"])
        self.assertTrue(result["treeUseful"])
        self.assertEqual(1, result["refs"]["e1"]["elementIndex"])

    def test_page_card_preserves_device_artifact_and_verified_route_hints(self):
        result = compact_observation({
            "screenshot": {"available": True, "artifact": {"kind": "LOCAL_FILE", "reference": "C:/safe.jpg"}},
            "observation": {
                "pageKey": "settings", "controls": [],
                "nextHopHints": [{"confidence": 0.9, "freshness": "CURRENT", "action": {"tool": "phone.click"}}],
            },
        })
        self.assertEqual("LOCAL_FILE", result["screenshot"]["artifact"]["kind"])
        self.assertEqual("phone.click", result["knownRouteHints"][0]["action"]["tool"])


    def test_cyclone_page_text_v1_object_is_not_dropped(self):
        result = compact_observation({
            "observation": {
                "pageKey": "settings::root",
                "pageText": {
                    "protocol": "cyclone-page-text-v1",
                    "lines": [{"text": "Settings"}, {"text": "Network & internet"}],
                },
                "pageSummary": {
                    "protocol": "cyclone-page-summary-v1",
                    "title": "Settings",
                    "contentNote": "Settings home",
                    "buttons": ["Apps"],
                },
                "controls": [],
            }
        })
        self.assertIn("Network & internet", result["pageText"])
        self.assertIn("Settings", result["pageSummary"])
        self.assertIn("Apps", result["pageSummary"])


    def test_editable_near_end_stays_in_current_candidates(self):
        controls = [{"id": f"c{i}", "label": f"Row {i}", "clickable": True} for i in range(19)]
        controls.append({
            "id": "task-input",
            "label": "Phone task input",
            "editable": True,
            "focused": True,
            "clickable": False,
        })
        result = compact_observation({"pageKey": "overlay", "controls": controls}, goal="Type into the composer")
        current = result["candidates"]["current"]
        self.assertEqual(12, len(current))
        editable = next(item for item in current if item.get("label") == "Phone task input")
        self.assertTrue(editable["editable"])
        self.assertEqual("task-input", editable["elementId"])
        ranked = result["candidates"]["goalRanked"]
        self.assertEqual("task-input", ranked[0]["elementId"])


if __name__ == "__main__":
    unittest.main()
