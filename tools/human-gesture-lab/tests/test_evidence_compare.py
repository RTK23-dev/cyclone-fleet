from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest

MODULE_PATH = Path(__file__).resolve().parents[1] / "evidence_compare.py"
spec = importlib.util.spec_from_file_location("evidence_compare", MODULE_PATH)
evidence = importlib.util.module_from_spec(spec)
assert spec and spec.loader
sys.modules[spec.name] = evidence
spec.loader.exec_module(evidence)


class EvidenceCompareTest(unittest.TestCase):
    def trace(self, source: str, profile: str = "LIGHT", scenario: str = "vertical_feed"):
        return {
            "schema": evidence.lab.TRACE_SCHEMA,
            "source": source,
            "scenario": scenario,
            "gesture_type": "swipe",
            "profile": profile,
            "seed": 7,
            "viewport": {"width_px": 1000, "height_px": 2000},
            "duration_ms": 200,
            "points": [
                {"u": 0.5, "v": 0.8, "t": 0.0},
                {"u": 0.51, "v": 0.5, "t": 0.5},
                {"u": 0.5, "v": 0.2, "t": 1.0},
            ],
        }

    def test_provenance_classes_stay_separate(self):
        report = evidence.compare_traces([
            self.trace("production_core"),
            self.trace("device_capture"),
            self.trace("synthetic_reference"),
        ])
        self.assertEqual(report["sample_count"], 3)
        self.assertEqual(report["source_counts"]["production_core"], 1)
        self.assertEqual(report["source_counts"]["device_capture"], 1)
        self.assertEqual(report["source_counts"]["synthetic_reference"], 1)
        self.assertEqual(len(report["groups"]), 3)
        self.assertIn("never human behavior", report["provenance_note"])

    def test_unknown_source_is_not_promoted_to_device_evidence(self):
        report = evidence.compare_traces([self.trace("phone-ish")])
        self.assertEqual(report["source_counts"]["unclassified"], 1)
        self.assertEqual(report["source_counts"]["device_capture"], 0)

    def test_supplied_hash_is_verified(self):
        trace = self.trace("production_core")
        trace["deterministic_hash"] = "0" * 64
        report = evidence.compare_traces([trace])
        self.assertEqual(report["hash_mismatch_count"], 1)

    def test_invalid_trace_is_reported_not_silently_counted(self):
        trace = self.trace("device_capture")
        trace["points"][1]["u"] = 2.0
        report = evidence.compare_traces([trace])
        self.assertEqual(report["invalid_count"], 1)
        self.assertEqual(report["sample_count"], 0)

    def test_directory_and_bundle_ingestion(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "single.json").write_text(json.dumps(self.trace("production_core")), encoding="utf-8")
            (root / "bundle.json").write_text(json.dumps({
                "traces": [
                    self.trace("device_capture", "NORMAL", "edge_swipe"),
                    self.trace("synthetic_reference", "OFF", "straight"),
                ]
            }), encoding="utf-8")
            traces = evidence.load_corpus([str(root)])
            self.assertEqual(len(traces), 3)


if __name__ == "__main__":
    unittest.main()
