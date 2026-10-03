"""Background stability guard (plan 28).

Alpha.40-43 background work could stop for reasons that had nothing to do with the task: an accessibility interrupt
closed every background screen, a phone lock left the screen paused for good, an approval paused it before the
approved tap, and any failed action (even a missed tap) moved the task to the owner's screen. These rules keep those
fixes in place.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[3]
BASE = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile"


def read(name: str) -> str:
    return (BASE / name).read_text(encoding="utf-8")


def body(text: str, signature: str) -> str:
    """The text of one Kotlin function, from its signature to the next function at the same indent."""
    start = text.index(signature)
    indent = text[text.rfind("\n", 0, start) + 1:start]
    following = re.search(r"\n" + re.escape(indent) + r"(private |internal |override |fun |@)", text[start + len(signature):])
    return text[start:start + len(signature) + (following.start() if following else len(text))]


class BackgroundStableGuards(unittest.TestCase):
    def test_accessibility_interrupt_keeps_background_screens(self):
        service = read("CycloneAccessibilityService.kt")
        interrupt = re.search(r"override fun onInterrupt\(\)[^\n]*", service).group(0)
        self.assertNotIn("invalidateAll", interrupt)

    def test_a_locked_phone_never_pauses_the_background_screen(self):
        runtime = read("runtime/background/WorkspaceRuntime.kt")
        authorize = body(runtime, "private fun authorizeTouchLocked(")
        self.assertIn("SCREEN_LOCKED", authorize)
        self.assertNotIn("pause(", authorize)

    def test_mission_approvals_use_the_overlay_grant_without_pausing(self):
        executor = read("PhoneToolExecutor.kt")
        gate = body(executor, "private fun workspaceGate(")
        mission = gate[gate.index("backgroundSessionId"):gate.index("runtime.requestConfirmation")]
        self.assertIn("OverlayChromeRuntime.consumeGateApproval", mission)
        self.assertIn("OverlayChromeRuntime.registerGateChallenge", mission)
        self.assertNotIn("pause(", mission)
        # Every gated background action goes through that one function.
        self.assertNotIn("GateClassifier.classify(", body(executor, "private fun executeWorkspace("))
        self.assertGreaterEqual(body(executor, "private fun executeWorkspace(").count("workspaceGate("), 2)

    def test_background_failures_keep_their_reason(self):
        executor = read("PhoneToolExecutor.kt")
        self.assertNotIn("could not complete in its current scope", executor)
        self.assertIn('"Background screen: ${workspaceReason(error)}"', executor)

    def test_only_steps_that_need_the_screen_move_the_task(self):
        planes = read("runtime/plane/MissionPlanes.kt")
        after = body(planes, "override fun after(")
        self.assertIn("BackgroundFailure.classify", after)
        self.assertNotIn("current scope", after)

    def test_gesture_fallback_never_repeats_a_delivered_gesture(self):
        executor = read("PhoneToolExecutor.kt")
        fallback = body(executor, "private fun shellGesture(")
        self.assertIn("REASON_NOT_QUEUED", fallback)

    def test_background_check_decides_automatic(self):
        planes = read("runtime/plane/MissionPlanes.kt")
        self.assertIn("BackgroundCheck.blocker(context)", body(planes, "fun blocker("))


if __name__ == "__main__":
    unittest.main()
