"""Alpha.78: Cyclone's decisions go through one provider port. JEV is live and reads text only; OpenAI Decisions is
frozen until it is available, and switching is one place (mind/decide/Decisions.kt)."""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
MOBILE = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile"


def read(path: str) -> str:
    return (MOBILE / path).read_text(encoding="utf-8")


class DecisionsGuard(unittest.TestCase):
    def test_the_decisions_endpoint_lives_in_one_place(self):
        users = sorted(p.relative_to(MOBILE).as_posix() for p in MOBILE.rglob("*.kt") if "api/alpha/decisions" in p.read_text(encoding="utf-8"))
        # Drive's JEV watch keeps its own call (voice may not import mind code); it only watches.
        self.assertEqual(users, ["mind/decide/Decisions.kt", "voice/OpenRouterVoice.kt"], users)

    def test_jev_is_live_text_only_and_openai_is_frozen(self):
        decide = read("mind/decide/Decisions.kt")
        self.assertIn('JEV("JEV (TypeSafe)", "~typesafe/jev-latest", "https://openrouter.ai/api/alpha/decisions", vision = false, live = true)', decide)
        self.assertIn('OPENAI_DECISIONS("OpenAI Decisions", model = "", endpoint = null, vision = true, live = false)', decide)
        self.assertIn("val ACTIVE: DecisionProvider = DecisionProvider.JEV", decide)
        self.assertIn("ACTIVE.takeIf { it.live && it.endpoint != null && it.model.isNotBlank() } ?: DecisionProvider.JEV", decide)
        # A provider without vision never receives a screenshot.
        self.assertIn("if (provider.vision) request else request.copy(image = null)", decide)

    def test_every_decision_caller_uses_the_port(self):
        modes = read("mind/modes/CycloneModes.kt")
        self.assertIn("com.cyclone.mobile.mind.decide.Decisions.box(context)", modes)
        self.assertFalse((MOBILE / "mind/modes/OpenRouterDecisionBox.kt").exists())
        fast = read("mind/pilot/FastMode.kt")
        self.assertIn("com.cyclone.mobile.mind.decide.Decisions.active()", fast)
        self.assertNotRegex(fast, r'"https://openrouter\.ai/api/alpha/decisions"')
        # The decision model is not a free text field any more.
        settings = read("ui/overlay/CycloneAiSettingsActivity.kt")
        self.assertNotIn("fast.copy(decisionModel", settings)

    def test_no_screenshot_is_taken_for_a_box_that_cannot_see(self):
        run = read("mind/modes/InstantRun.kt")
        self.assertIn("hands.look(withImage = box?.sees == true)", run)


    def test_the_phone_model_is_taught_by_the_provider_and_acts_only_on_what_it_earned(self):
        """Alpha 89: the phone model learns from JEV's (later OpenAI Decisions') verified decisions, never from its own,
        acts only on earned actions and when sure, keeps being audited, and its lessons never leave the phone."""
        lessons = read("mind/decide/Lessons.kt")
        start = lessons.index("fun teaches(")
        teaches = lessons[start:lessons.index("companion object", start)]
        self.assertNotIn("Decider.PHONE ->", teaches, "the phone model never learns from its own decisions")
        self.assertIn("else -> false", teaches)
        earning = read("mind/decide/PhoneModel.kt")
        for rule in ("MIN_SAMPLES = 50", "MIN_AGREEMENT = 0.98", "SURE = 0.9", "AUDIT_EVERY = 5"):
            self.assertIn(rule, earning)
        router = read("mind/modes/ModeRouter.kt")
        # Grammar and the risk rules come before the phone model; it acts only on earned, sure actions.
        self.assertLess(router.index("forced(text, world)?.let"), router.index("m.guess(text, candidates)"))
        self.assertIn("shadow.intent in phone.earned", router)
        self.assertIn("(box == null || phone?.audit != true)", router)
        # Alpha 91: unearned, the phone model may only do easy-to-undo actions about now, and never a tap.
        reversible = router[router.index("val REVERSIBLE = setOf("):router.index(")", router.index("val REVERSIBLE = setOf("))]
        for risky in ('"tap"', '"photo"', '"selfie"', '"call"'):
            self.assertNotIn(risky, reversible)
        self.assertIn("shadow.intent in REVERSIBLE", router)
        self.assertIn("plainNow(text)", router)
        brain = read("mind/decide/PhoneBrain.kt")
        self.assertIn("MindMemory.looksSecret(it)", brain)
        # Only counts and times leave the phone: the health report carries stats, never lessons.
        health = read("runtime/health/AppHealth.kt")
        self.assertIn("PhoneBrain.stats(", health)
        self.assertNotIn("Lessons.encode", health)
        stats = read("mind/decide/DecisionStats.kt")
        self.assertNotIn(".request", stats)

    def test_routing_waits_for_the_decision_only_briefly(self):
        decide = read("mind/decide/Decisions.kt")
        self.assertIn("ROUTING_DEADLINE_MS = 2_500L", decide)
        self.assertIn("ProviderDecisionBox(key, active(), ROUTING_DEADLINE_MS)", decide)


if __name__ == "__main__":
    unittest.main()
