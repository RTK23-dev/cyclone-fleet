from __future__ import annotations

import importlib.util
import json
import math
from pathlib import Path
import random
import sys
import unittest

MODULE_PATH = Path(__file__).resolve().parents[1] / "gesture_lab.py"
spec = importlib.util.spec_from_file_location("gesture_lab", MODULE_PATH)
lab = importlib.util.module_from_spec(spec)
assert spec and spec.loader
sys.modules[spec.name] = lab
spec.loader.exec_module(lab)


class GestureLabTest(unittest.TestCase):
    def trace(self, **overrides):
        value = {
            "schema": lab.TRACE_SCHEMA,
            "gesture_type": "swipe",
            "profile": "NORMAL",
            "seed": 7,
            "viewport": {"width_px": 1000, "height_px": 2000},
            "duration_ms": 200,
            "points": [
                {"u": 0.2, "v": 0.8, "t": 0.0},
                {"u": 0.22, "v": 0.5, "t": 0.5},
                {"u": 0.2, "v": 0.2, "t": 1.0},
            ],
        }
        value.update(overrides)
        return value

    def test_straight_line_metrics(self):
        trace = self.trace(
            profile="OFF",
            points=[
                {"u": 0.5, "v": 0.8, "t": 0.0},
                {"u": 0.5, "v": 0.5, "t": 0.5},
                {"u": 0.5, "v": 0.2, "t": 1.0},
            ],
        )
        metrics = lab.analyze_trace(trace)
        self.assertAlmostEqual(metrics["path_chord_ratio"], 1.0, places=9)
        self.assertAlmostEqual(metrics["max_perpendicular_deviation_px"], 0.0, places=9)
        self.assertEqual(metrics["curvature_sign_changes"], 0)
        self.assertTrue(metrics["viewport_compliant"])

    def test_target_compliance_and_inset(self):
        trace = self.trace(
            target={"bounds_norm": {"left": 0.1, "top": 0.1, "right": 0.4, "bottom": 0.4}}
        )
        metrics = lab.analyze_trace(trace)
        self.assertTrue(metrics["endpoint_in_target"])
        self.assertGreater(metrics["endpoint_inset_px"], 0)

    def test_deterministic_hash_ignores_non_motion_metadata(self):
        trace = self.trace()
        a = lab.stable_hash(trace)
        trace["debug_note"] = "not motion relevant"
        b = lab.stable_hash(trace)
        self.assertEqual(a, b)

    def test_hash_changes_with_motion(self):
        trace = self.trace()
        a = lab.stable_hash(trace)
        trace["points"][1]["u"] += 0.01
        self.assertNotEqual(a, lab.stable_hash(trace))

    def test_rejects_nan(self):
        trace = self.trace()
        trace["points"][1]["u"] = math.nan
        with self.assertRaises(lab.TraceError):
            lab.analyze_trace(trace)

    def test_rejects_out_of_bounds(self):
        trace = self.trace()
        trace["points"][1]["v"] = 1.01
        with self.assertRaises(lab.TraceError):
            lab.analyze_trace(trace)

    def test_rejects_time_regression(self):
        trace = self.trace()
        trace["points"][1]["t"] = 0.9
        trace["points"][2]["t"] = 0.8
        with self.assertRaises(lab.TraceError):
            lab.analyze_trace(trace)

    def test_rejects_zero_duration_movement(self):
        trace = self.trace(duration_ms=0)
        with self.assertRaises(lab.TraceError):
            lab.analyze_trace(trace)

    def test_synthetic_replay_is_deterministic(self):
        rng = random.Random(1)
        for seed in range(100):
            a = lab.synthetic_trace(seed, profile="NORMAL", case=seed % 8)
            b = lab.synthetic_trace(seed, profile="NORMAL", case=seed % 8)
            self.assertEqual(lab.stable_hash(a), lab.stable_hash(b))

    def test_fuzz_smoke(self):
        report = lab.run_fuzz(2000, 12345)
        self.assertEqual(report["executed_count"], 2000)
        self.assertEqual(report["failure_count"], 0, json.dumps(report["failures"], indent=2))

    def test_profile_separation_on_nontrivial_motion(self):
        rng = random.Random(9)
        samples = {profile: [] for profile in lab.PROFILES}
        for profile in lab.PROFILES:
            for seed in range(200, 500):
                metrics = lab.analyze_trace(lab.synthetic_trace(seed, profile=profile, case=0))
                if metrics["chord_length_px"] > 8:
                    samples[profile].append(metrics["max_perpendicular_deviation_chord_ratio"])
        off = sum(samples["OFF"]) / len(samples["OFF"])
        light = sum(samples["LIGHT"]) / len(samples["LIGHT"])
        normal = sum(samples["NORMAL"]) / len(samples["NORMAL"])
        self.assertLess(off, light)
        self.assertLess(light, normal)


if __name__ == "__main__":
    unittest.main()
