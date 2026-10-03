"""Plan 40 P2, Cyclone Carry: memory, skills and settings follow the owner across profiles; secrets never do."""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
MOBILE = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile"


def read(path: str) -> str:
    return (MOBILE / path).read_text(encoding="utf-8")


def callers(pattern: str) -> list:
    found = []
    for file in MOBILE.rglob("*.kt"):
        if re.search(pattern, file.read_text(encoding="utf-8")):
            found.append(file.relative_to(MOBILE).as_posix())
    return sorted(found)


class ProfileCarryGuard(unittest.TestCase):
    def test_only_a_switch_carries_and_only_the_receiving_service_takes_in(self):
        self.assertEqual(callers(r"ProfileBootstrapRuntime\.carry\("), ["runtime/workspaces/ProfileSetupRuntime.kt"])
        self.assertEqual(callers(r"ProfileCarry\.absorb\("), ["runtime/workspaces/ProfileBootstrapService.kt"])
        self.assertEqual(callers(r"ProfileCarry\.pack\("), ["runtime/workspaces/ProfileBootstrapRuntime.kt"])
        # A carry never blocks the switch: it runs inside runCatching, after the task check of openProfile.
        setup = read("runtime/workspaces/ProfileSetupRuntime.kt")
        open_profile = setup[setup.index("fun openProfile("):setup.index("fun apps(")]
        self.assertIn("runCatching { ProfileBootstrapRuntime.carry(context, user) }", open_profile)
        self.assertLess(open_profile.index("Finish the current task"), open_profile.index("ProfileBootstrapRuntime.carry("))

    def test_no_model_or_gateway_path_reaches_the_carry(self):
        for file in MOBILE.rglob("*.kt"):
            rel = file.relative_to(MOBILE).as_posix()
            if rel.startswith(("agent/", "mind/", "gateway/", "ai/", "automation/")):
                text = file.read_text(encoding="utf-8")
                self.assertNotIn("ProfileCarry", text, rel)
                self.assertNotIn("ProfileBootstrapRuntime.carry", text, rel)

    def test_the_bundle_is_sealed_and_bound_to_its_profile_and_switch(self):
        runtime = read("runtime/workspaces/ProfileBootstrapRuntime.kt")
        carry = runtime[runtime.index("fun carry("):runtime.index("fun requestRepair(")]
        self.assertIn("ProfileTransferCipher.seal(publicKey, plain, CarryRules.cipherContext(target, nonce))", carry)
        self.assertIn("plain.fill(0)", carry)
        self.assertNotIn("OpenRouterSecretStore", carry)
        service = read("runtime/workspaces/ProfileBootstrapService.kt")
        take_in = service[service.index("private fun takeInCarry("):service.index("companion object")]
        self.assertIn("CarryRules.cipherContext(me, nonce)", take_in)
        self.assertIn('check(envelope.getInt("target") == me', take_in)
        self.assertNotIn("Log.", take_in)
        cipher = read("runtime/workspaces/ProfileTransferCipher.kt")
        self.assertIn('"AES/GCM/NoPadding"', cipher)
        self.assertIn("updateAAD", cipher)

    def test_nothing_secret_is_packed(self):
        pack = read("runtime/workspaces/ProfileCarry.kt")
        for secret in ("OpenRouterSecretStore", "cyclone_ai", "Vault", "gateway", "token", "History"):
            self.assertNotIn(secret, pack[pack.index("fun pack("):pack.index("fun absorb(")], secret)
        rules = read("runtime/workspaces/CarryRules.kt")
        settings = rules[rules.index("val settings:"):rules.index("private val privateName")]
        self.assertEqual(sorted(re.findall(r'"(cyclone_[a-z_]+)" to', settings)), ["cyclone_drive", "cyclone_ui"])
        memory = read("mind/MemoryCarry.kt")
        self.assertIn("facts.filterNot(::secret)", memory)
        self.assertIn("secret(incoming)", memory)

    def test_memory_stays_labelled_by_profile(self):
        memory = read("mind/MindMemory.kt")
        # What this profile learns lands on its own cards, never on a card carried from another profile.
        self.assertIn("it.profile == null && it.kind == PERSON && it.subject.equals(person", memory)
        self.assertIn("it.profile == null && it.kind == kind", memory)
        self.assertIn('" (from ${fact.profileLabel', memory)
        self.assertIn("MemoryCarry.forgotten(it, clock())", memory)


if __name__ == "__main__":
    unittest.main()
