"""Plan 49: codes from this phone's own texts.

- Texts are read in memory only: nothing in the codes package logs, traces or stores a text or a code.
- The model never sees a code: no tool result, brief or status carries one.
- Only reading: no SMS sending or receiving permission, and never in a Lab mission.
- Android 17 holds code texts back three hours from apps targeting it, so targetSdk stays at 36 or lower until plan 49
  §6.3 is done.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
MOBILE = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile"
CODES = MOBILE / "codes"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


class CodesGuard(unittest.TestCase):
    def test_target_sdk_stays_below_android_17(self):
        gradle = read(ROOT / "apps/mobile/app/build.gradle.kts")
        target = int(re.search(r"targetSdk\s*=\s*(\d+)", gradle).group(1))
        self.assertLessEqual(target, 36, "Android 17 delays code texts for apps targeting it: see plan 49 §1 and §6.3 first")

    def test_texts_are_never_logged_or_stored(self):
        for path in CODES.glob("*.kt"):
            text = read(path)
            self.assertNotRegex(text, r"\bLog\.|addLog|println|Trace|trail\?\.|journal", path.name)
            # The only thing kept is the owner's switch and numbers.
            for key in re.findall(r"put(?:String|StringSet|Boolean)\((\w+)", text):
                self.assertIn(key, {"ENABLED", "NUMBERS"}, path.name)

    def test_the_model_never_sees_a_code(self):
        box = read(MOBILE / "mind/PhoneMindToolbox.kt")
        section = box[box.index("// ---- plan 49: codes from this phone's own texts"):box.index("// ---- tracking and finishing")]
        self.assertNotRegex(section, r'"[^"\n]*\$\{?(caught\.code|code)\b[^"\n]*"')
        port = read(MOBILE / "mind/MindCodes.kt")
        self.assertNotRegex(port, r"fun \w+\([^)]*\)\s*:\s*String\b")

    def test_read_only_and_never_in_the_lab(self):
        manifest = read(ROOT / "apps/mobile/app/src/main/AndroidManifest.xml")
        self.assertIn("android.permission.READ_SMS", manifest)
        self.assertNotIn("android.permission.SEND_SMS", manifest)
        self.assertNotIn("android.permission.RECEIVE_SMS", manifest)
        missions = read(MOBILE / "mind/mission/MindMissions.kt")
        self.assertIn("codes = if (run.mission.lab == null) com.cyclone.mobile.codes.AndroidMindCodes(context) else null", missions)

    def test_private_apps_and_payments_are_never_automatic(self):
        policy = read(CODES / "AutoCodePolicy.kt")
        self.assertIn('if (facts.keptPrivate) return Result(Decision.NEVER', policy)
        self.assertIn('if (PAYMENT.containsMatchIn(facts.screenText)) return Result(Decision.NEVER', policy)
        box = read(MOBILE / "mind/PhoneMindToolbox.kt")
        self.assertIn("keptPrivate = com.cyclone.mobile.mind.pilot.Pilot.keepOff(page.packageName, app)", box)


if __name__ == "__main__":
    unittest.main()
