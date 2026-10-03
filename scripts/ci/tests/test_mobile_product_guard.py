import importlib.util
import unittest
from pathlib import Path

SCRIPT = Path(__file__).parents[1] / "mobile_product_guard.py"
SPEC = importlib.util.spec_from_file_location("mobile_product_guard", SCRIPT)
guard = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
SPEC.loader.exec_module(guard)


class MobileProductGuardTest(unittest.TestCase):
    def test_current_product_is_android_and_optional_companion_only(self):
        self.assertEqual([], guard.check())

    def test_missing_tokens_reports_only_absent_invariants(self):
        self.assertEqual(
            ["Teach", "Brain"],
            guard.missing_tokens(
                "Home AI Settings",
                ("Home", "Teach", "AI", "Brain", "Settings"),
            ),
        )

    def test_all_tokens_present_is_clean(self):
        self.assertEqual(
            [],
            guard.missing_tokens(
                "Home Teach AI Brain Settings",
                ("Home", "Teach", "AI", "Brain", "Settings"),
            ),
        )


if __name__ == "__main__":
    unittest.main()
