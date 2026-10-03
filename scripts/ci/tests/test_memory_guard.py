"""Guards for memory v2 (plan 37 W3, alpha.67): the owner's "remember" is never missed, memory stays tidy, secrets
and one-off values are refused, people are kept only when the owner told Cyclone about them, and it is sealed at rest."""
from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
MIND = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/mind"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def test_a_remember_request_is_noticed_everywhere_the_owner_speaks():
    toolbox = read(MIND / "PhoneMindToolbox.kt")
    assert "private var rememberAsk: String? = RememberIntent.detect(goal)" in toolbox
    assert "override fun onOwnerMessage(text: String) = ownerSaid(text)" in toolbox
    assert "if (reply.answered) ownerSaid(reply.text)" in toolbox
    loop = read(MIND / "MindLoop.kt")
    assert "runCatching { toolbox.onOwnerMessage(it) }" in loop and "MindPrompt.rememberAsked(asked)" in loop
    missions = read(MIND / "mission/MindMissions.kt")
    assert "com.cyclone.mobile.mind.RememberIntent.detect(run.mission.goal)" in missions


def test_the_finish_reminds_once_and_never_traps():
    toolbox = read(MIND / "PhoneMindToolbox.kt")
    assert "rememberAsk?.takeIf { !remembered && !rememberNoted }?.let { asked ->" in toolbox
    assert "rememberNoted = true" in toolbox


def test_memory_stays_tidy_and_refuses_secrets_and_one_off_values():
    memory = read(MIND / "MindMemory.kt")
    for marker in ("val card = facts.firstOrNull { it.profile == null && it.kind == PERSON && it.subject.equals(person, ignoreCase = true) }",
                   "scored.firstOrNull { it.second >= UPDATE_AT }", "candidate.replaces", "if (looksSecret(everything))",
                   "candidate.source != OWNER && TRANSIENT.containsMatchIn(clean)", "history = pushed(old.history, old.text)"):
        assert marker in memory, marker


def test_people_are_kept_only_when_the_owner_told_cyclone_about_them():
    toolbox = read(MIND / "PhoneMindToolbox.kt")
    assert "if (person != null && rememberAsk == null && !ownerWords.toString().contains(person, ignoreCase = true))" in toolbox


def test_memory_is_sealed_at_rest_and_every_change_is_shown():
    missions = read(MIND / "mission/MindMissions.kt")
    assert "sealer = KeystoreMemorySealer" in missions
    sealer = read(MIND / "mission/KeystoreMemorySealer.kt")
    assert '"AES/GCM/NoPadding"' in sealer and "AndroidKeyStore" in sealer
    toolbox = read(MIND / "PhoneMindToolbox.kt")
    assert "if (changed) owner.memoryUpdated(kept.text)" in toolbox


def test_lab_runs_never_touch_the_owners_memory():
    missions = read(MIND / "mission/MindMissions.kt")
    assert 'File(context.cacheDir, "lab-memory-${run.id}.json")' in missions
