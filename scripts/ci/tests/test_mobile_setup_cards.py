"""Setup cards guard (plan 30, alpha.46).

The setup cards explain a setting and open the place where the owner turns it on. They never turn anything on
themselves: no phone actions, no accessibility taps inside Android's settings, no shell, no new permissions.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[3]
BASE = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile"
SETUP = [BASE / "setup/SetupCards.kt", BASE / "setup/SetupState.kt", BASE / "ui/v32/SetupCardSheet.kt"]
FORBIDDEN = [
    "PhoneToolExecutor", "performAction", "dispatchGesture", "performGlobalAction", "CycloneAccessibilityService",
    "Runtime.getRuntime", "ProcessBuilder", "Shizuku.newProcess", "WorkspaceRuntime", "TaskCommands.",
    "Settings.Secure.put", "Settings.Global.put", "Settings.System.put",
]


class SetupCardsGuards(unittest.TestCase):
    def test_cards_never_act_on_the_phone(self):
        for path in SETUP:
            text = path.read_text(encoding="utf-8")
            for word in FORBIDDEN:
                self.assertNotIn(word, text, f"{path.name} must not use {word}")

    def test_every_card_opens_androids_own_screen_or_dialog(self):
        state = (BASE / "setup/SetupState.kt").read_text(encoding="utf-8")
        cards = re.findall(r"^\s{4}([A-Z_]+)\(\n?\s*\"", (BASE / "setup/SetupCards.kt").read_text(encoding="utf-8"), re.M)
        self.assertGreaterEqual(len(cards), 8)
        open_fn = state[state.index("fun open("):state.index("fun runtimePermissions(")]
        runtime = state[state.index("fun runtimePermissions("):state.index("private fun start(")]
        for card in cards:
            self.assertTrue(f"SetupCard.{card}" in open_fn or f"SetupCard.{card}" in runtime,
                            f"{card} has no destination")
        self.assertIn("ActivityCompat.requestPermissions", open_fn)
        self.assertIn("BackgroundSetupActivity", open_fn)

    def test_the_card_follows_tilt_glass(self):
        sheet = (BASE / "ui/v32/SetupCardSheet.kt").read_text(encoding="utf-8")
        for part in ["tiltGlass(", "TiltGlassTheme", "FollowPhoneLight()", "GlassCapsuleButton", "GlassRoundButton",
                     "SetupCopy.SKIP", "SetupCopy.CLOSE", "OverlayStackGeometry.CARD_RADIUS_DP"]:
            self.assertIn(part, sheet)

    def test_settings_show_the_cards_again(self):
        settings = (BASE / "ui/v32/CycloneSettings426.kt").read_text(encoding="utf-8")
        for card in ["PHONE_CONTROL", "RESULTS", "BACKGROUND"]:
            self.assertIn(f"com.cyclone.mobile.setup.SetupCard.{card}", settings)
        self.assertIn("onInfo(card)", settings)


if __name__ == "__main__":
    unittest.main()
