"""V4 slice 5: fixture-driven golden locate tests (synthetic, Pixel-unverified)."""
from __future__ import annotations

import sys
import unittest
from pathlib import Path

_TESTS_DIR = Path(__file__).resolve().parent
if str(_TESTS_DIR) not in sys.path:
    sys.path.insert(0, str(_TESTS_DIR))

from golden_locate import (
    AGENT_CONTEXT_TRUNCATION,
    TOP_HITS,
    assert_no_plaintext_secrets,
    assert_no_raw_tree,
    assert_page_text_survived,
    compact_golden,
    golden_paths,
    iter_goldens,
    locate_rank,
    observation,
    truncation_failure,
)

REQUIRED_PAGE_FIELDS = (
    "package",
    "activity",
    "pageKey",
    "title",
    "pageText",
    "pageSummary",
    "controls",
    "counts",
)


class GoldenLocateTests(unittest.TestCase):
    def test_corpus_has_twelve_synthetic_goldens(self):
        paths = golden_paths()
        self.assertEqual(12, len(paths), f"expected 12 goldens, found {[p.name for p in paths]}")
        stems = {path.stem for path in paths}
        expected = {
            "settings_home",
            "settings_apps",
            "messages_thread",
            "chrome_blank",
            "play_store",
            "clock",
            "calculator",
            "phone_dialer",
            "files",
            "chrome_search_results",
            "food_shop_cart",
            "pay_confirmation",
        }
        self.assertEqual(expected, stems)

    def test_each_fixture_is_synthetic_page_card_not_pixel_capture(self):
        for path, golden in iter_goldens():
            with self.subTest(page=path.stem):
                self.assertTrue(golden.get("synthetic") is True)
                self.assertTrue(golden.get("pixelCapture") is False)
                self.assertIn("Not a Pixel 8 capture", str(golden.get("note") or ""))
                source = observation(golden)
                for field in REQUIRED_PAGE_FIELDS:
                    self.assertIn(field, source, f"{path.stem} missing {field}")
                self.assertTrue(str(source.get("pageText") or "").strip())
                self.assertTrue(str(source.get("pageSummary") or "").strip())
                self.assertEqual("cyclone-page-text-v1", source.get("pageTextSchema"))
                self.assertEqual("cyclone-page-summary-v1", source.get("pageSummarySchema"))
                self.assertIsInstance(source.get("controls"), list)
                self.assertGreaterEqual(len(source["controls"]), 4)
                self.assertIsInstance(source.get("counts"), dict)
                for count_key in ("raw", "semantic", "agent"):
                    self.assertIn(count_key, source["counts"])
                goal = str(golden.get("goal") or "").strip()
                expected = golden.get("expectedTarget")
                self.assertTrue(goal, f"{path.stem} missing locate goal")
                self.assertIsInstance(expected, dict)
                self.assertTrue(
                    expected.get("label")
                    or expected.get("resourceId")
                    or expected.get("contentDescription"),
                    f"{path.stem} expectedTarget needs label, resourceId, or contentDescription",
                )

    def test_page_text_and_summary_survive_compact(self):
        for path, golden in iter_goldens():
            with self.subTest(page=path.stem):
                card = compact_golden(golden)
                try:
                    assert_page_text_survived(card, observation(golden), page_name=path.stem)
                except AssertionError as exc:
                    self.assertIn(AGENT_CONTEXT_TRUNCATION, str(exc))
                    raise
                self.assertEqual("page_card", card.get("kind"))
                self.assertEqual(observation(golden).get("pageKey"), card.get("pageKey"))
                self.assertTrue(card.get("truncated", {}).get("rawTreeExcluded"))

    def test_locate_ranks_labelled_control_in_top_hits(self):
        for path, golden in iter_goldens():
            with self.subTest(page=path.stem, goal=golden.get("goal")):
                card = compact_golden(golden)
                hits = card.get("candidates", {}).get("goalRanked")
                self.assertIsInstance(hits, list)
                self.assertTrue(hits, f"{path.stem} locate ranking returned no hits for {golden.get('goal')!r}")
                rank = locate_rank(hits, golden["expectedTarget"])
                self.assertIsNotNone(
                    rank,
                    f"{path.stem} labelled control not in goalRanked hits: {hits!r}",
                )
                self.assertLess(
                    rank,
                    TOP_HITS,
                    f"{path.stem} labelled control ranked {rank}, expected top {TOP_HITS}",
                )

    def test_silent_drop_of_page_context_is_agent_context_truncation(self):
        path, golden = next(iter_goldens())
        card = compact_golden(golden)
        dropped = dict(card)
        dropped["pageText"] = None
        dropped["pageSummary"] = None
        with self.assertRaises(AssertionError) as caught:
            assert_page_text_survived(dropped, observation(golden), page_name=path.stem)
        self.assertIn(AGENT_CONTEXT_TRUNCATION, str(caught.exception))
        with self.assertRaises(AssertionError) as explicit:
            raise truncation_failure("compact dropped pageSummary")
        self.assertIn(AGENT_CONTEXT_TRUNCATION, str(explicit.exception))

    def test_goldens_have_no_plaintext_secrets_or_raw_trees(self):
        for path, golden in iter_goldens():
            with self.subTest(page=path.stem):
                assert_no_plaintext_secrets(golden, page_name=path.stem)
                assert_no_raw_tree(observation(golden), page_name=path.stem)
                blob = str(golden).lower()
                self.assertNotIn("hunter2", blob)
                self.assertNotIn("4111111111111111", blob)


if __name__ == "__main__":
    unittest.main()
