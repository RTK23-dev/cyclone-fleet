"""Direct-first guard (plan 29, alpha.45).

Calendar, contacts and the clock run without any screen. These rules keep them inside the harness and the owner's
consent: only PhoneToolExecutor reaches Android's providers, only Android's own dialog grants access, and secrets never
go into a calendar.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[3]
BASE = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile"
MANIFEST = ROOT / "apps/mobile/app/src/main/AndroidManifest.xml"


def sources():
    for path in BASE.rglob("*.kt"):
        yield path.relative_to(BASE).as_posix(), path.read_text(encoding="utf-8")


class DirectFirstGuards(unittest.TestCase):
    def test_direct_actions_only_through_the_executor(self):
        callers = {name for name, text in sources()
                   if re.search(r"DirectActions\.(calendarFind|calendarAdd|contactsFind|alarm|timer)\(", text)}
        self.assertEqual(callers, {"PhoneToolExecutor.kt"})

    def test_access_is_asked_only_by_androids_dialog(self):
        callers = {name for name, text in sources() if "DirectActions.requestAccess(" in text}
        self.assertEqual(callers, {"mind/mission/AndroidMindPorts.kt"})
        manifest = MANIFEST.read_text(encoding="utf-8")
        activity = re.search(r'<activity\s+android:name="\.direct\.DirectAccessActivity"[^>]*>', manifest, re.S).group(0)
        self.assertIn('android:exported="false"', activity)

    def test_calendar_events_never_carry_secrets(self):
        toolbox = (BASE / "mind/PhoneMindToolbox.kt").read_text(encoding="utf-8")
        add = toolbox[toolbox.index("private fun calendarAdd("):toolbox.index("private fun contactFind(")]
        self.assertLess(add.index("sensitive("), add.index('directCall("calendar_add"'))

    def test_the_mind_is_told_direct_first(self):
        prompt = (BASE / "mind/MindPrompt.kt").read_text(encoding="utf-8")
        self.assertIn("Direct first", prompt)
        for tool in ("calendar_add", "calendar_find", "contact_find", "reply_notification"):
            self.assertIn(tool, prompt)

    def test_timers_and_alarms_do_not_pull_background_work_to_the_screen(self):
        planes = (BASE / "runtime/plane/MissionPlanes.kt").read_text(encoding="utf-8")
        before = planes[planes.index("override fun before("):planes.index("override fun after(")]
        self.assertNotIn('"set_timer"', before)
        self.assertNotIn('"set_alarm"', before)


if __name__ == "__main__":
    unittest.main()
