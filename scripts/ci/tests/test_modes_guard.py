"""Plan 42, Cyclone Live: Instant is fast, but it never writes, sends, pays or deletes, and it never goes around the executor."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
MOBILE = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile"


def read(path: str) -> str:
    return (MOBILE / path).read_text(encoding="utf-8")


class ModesGuard(unittest.TestCase):
    def test_requests_reach_missions_only_through_the_router(self):
        callers = [p.relative_to(MOBILE).as_posix() for p in MOBILE.rglob("*.kt")
                   if "MindMissions.start(" in p.read_text(encoding="utf-8")]
        self.assertEqual(sorted(callers), ["mind/modes/CycloneModes.kt"], callers)
        overlay = read("ui/overlay/OverlayChromeRuntime.kt")
        self.assertIn("com.cyclone.mobile.mind.modes.CycloneModes.handle(context, request, attachment", overlay)

    def test_instant_moves_go_through_the_agent_environment(self):
        hands = read("mind/modes/AndroidInstantHands.kt")
        self.assertIn("CycloneAgentEnvironment(context, userTaskGoal = goal, ownerMission = true)", hands)
        for forbidden in ("PhoneToolExecutor.execute", "dispatchGesture", "performGlobalAction", "startActivity(", "CALL_PHONE"):
            self.assertNotIn(forbidden, hands, forbidden)
        # Direct actions (torch, volume, media) run inside the executor, not in Instant.
        executor = read("PhoneToolExecutor.kt")
        for tool in ("phone.direct_flashlight", "phone.direct_volume", "phone.direct_media"):
            self.assertIn(f'"{tool}" -> direct(', executor)

    def test_instant_never_writes_and_code_decides_risk(self):
        run = read("mind/modes/InstantRun.kt")
        self.assertIn("Pilot.irreversible(label)", run)
        self.assertIn("screen.sensitive", run)
        self.assertIn("!Pilot.irreversible(it)", run, "a decision box never picks an irreversible control")
        for forbidden in ("phone.type", "typeText", "awaitApproval", "vault", "task_finish"):
            self.assertNotIn(forbidden, run, forbidden)
        self.assertIn("CALL_WINDOW_MS = 2_000L", run)
        hands = read("mind/modes/AndroidInstantHands.kt")
        self.assertIn("Pilot.keepOff(card.packageName, app)", hands)
        self.assertIn('c.evidence.optBoolean("password")', hands)

    def test_the_router_sends_writing_money_and_accounts_to_the_mind(self):
        router = read("mind/modes/ModeRouter.kt")
        for word in ("message", "send", "pay", "delete", "password", "login"):
            self.assertIn(word, router)
        self.assertIn("if (speed == Speed.MIND) return Route(Mode.MIND", router)
        modes = read("mind/modes/CycloneModes.kt")
        self.assertIn("Speed.of(p.getString(\"speed\", null))", modes)
        # Alpha 89: Auto is the default (JEV decides what the grammar doesn't; the phone model takes over what it earned).
        self.assertIn("?: AUTO }", router, "Auto is the default")
        # A stop never turns into a mission.
        self.assertLess(modes.index("running.stopped && !outcome.cancelled"), modes.index("promotion != null ->"))

    def test_voice_quick_commands_still_only_submit(self):
        session = read("voice/VoiceSession.kt")
        quick = session[session.index("is VoiceEffect.Quick -> {"):]
        quick = quick[: quick.index("is VoiceEffect.KeepListening")]
        self.assertIn("OverlayChromeRuntime.submitRequest(effect.goal, driving = true)", quick)
        turn = read("voice/VoiceTurn.kt")
        self.assertIn("!answering && followUp == null && !taskLive && !quickLive", turn,
                      "a quick command never answers an open question or a readback")


if __name__ == "__main__":
    unittest.main()
