"""Plan 27 / Tilt Glass principle 10: a working status shows the app Cyclone works in, never Cyclone's own logo
when an app is known. Cyclone's mark is only the fallback before any app is known (and the idle bubble)."""
import re
import unittest
from pathlib import Path

MOBILE = Path(__file__).resolve().parents[3] / "apps/mobile/app/src/main/java/com/cyclone/mobile"


class WorkingAppLogo(unittest.TestCase):
    def test_cyclone_mark_is_only_a_fallback_on_the_glass(self):
        stack = (MOBILE / "ui/overlay/OverlayGlassStack.kt").read_text(encoding="utf-8")
        uses = [line.strip() for line in stack.splitlines() if "CycloneMark(" in line and "private fun CycloneMark" not in line]
        self.assertTrue(uses)
        for line in uses:
            self.assertRegex(line, r"(apps\.isEmpty\(\)\)\s*CycloneMark|else CycloneMark\(22\))", f"Cyclone's mark as more than a fallback: {line}")

    def test_task_bars_use_the_working_app_not_the_raw_package(self):
        for path in list((MOBILE / "ui").rglob("*.kt")) + list((MOBILE / "runtime/background").rglob("*.kt")):
            if path.name == "WorkingApp.kt":
                continue
            text = path.read_text(encoding="utf-8")
            self.assertNotRegex(text, r"CycloneAppIcon\((task|snapshot)\.packageName", f"{path.name}: use WorkingApp.forTask")
            self.assertNotRegex(text, r"appIcon\(context, task\.packageName\)", f"{path.name}: use WorkingApp.forTask")
            self.assertNotRegex(text, r"TaskAppTrail\.record\([^)]*\)\.lastOrNull\(\)", f"{path.name}: use WorkingApp.forTask")

    def test_the_accessibility_service_feeds_the_on_screen_app(self):
        service = (MOBILE / "CycloneAccessibilityService.kt").read_text(encoding="utf-8")
        self.assertIn("WorkingApp.seen(it, homePackages)", service)


if __name__ == "__main__":
    unittest.main()
