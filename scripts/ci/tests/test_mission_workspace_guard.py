"""Guards for the mission workspace (plan 37, alpha.66): guidance not gates, no new tools, nothing scrapped, the live
state never stored, secrets never collected, and a workspace failure never stops a mission."""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
MIND = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/mind"
WORKSPACE = MIND / "workspace"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def test_the_workspace_adds_no_tools_only_optional_arguments():
    toolbox = read(MIND / "PhoneMindToolbox.kt")
    specs = toolbox[toolbox.index("val SPECS: List<MindToolSpec> = listOf("):]
    # 39 tools, plus plan 41's pilot, which is offered only when Fast mode is on (see test_pilot_guard.py), and plan 43's
    # signup_page / signup_final / signup_done, offered only in a sign-up mapping mission, and T7's setup_page /
    # setup_done, offered only in an Account Setup run, and alpha 92's tap_sequence (one form fill in one call), and plan
    # 48's port_send / port_wait, offered only while the owner's PC is connected (see test_ports_run4_guard.py).
    assert len(re.findall(r'MindToolSpec\("[a-z_]+"', specs)) == 48
    extend = read(WORKSPACE / "WorkspaceSpecs.kt")
    assert 'MindToolSpec("' not in extend, "the workspace must not define tools of its own"
    assert "MindToolSpec(spec.name, spec.description, parameters)" in extend
    # Only recall loses its required topic (turn/stay may replace it); nothing else becomes required.
    assert extend.count('parameters.put("required"') == 1


def test_nothing_in_the_workspace_refuses_an_action():
    for path in WORKSPACE.glob("*.kt"):
        text = read(path)
        assert "MindToolResult" not in text, f"{path.name} must not produce tool results or refusals"
        assert "NOT RUN" not in text, path.name
    toolbox = read(MIND / "PhoneMindToolbox.kt")
    # The finish nudge happens once; a second finish is always accepted (the workspace decides, never loops).
    assert "if (missing.isEmpty() || doneNoted) return null" in read(WORKSPACE / "MissionWorkspace.kt")
    assert 'ws.finishNote(summary)' in toolbox


def test_the_live_state_is_sent_last_and_never_stored():
    loop = read(MIND / "MindLoop.kt")
    assert "val tail = workspace?.let { ws -> guarded { ws.liveState() } }" in loop
    assert "conversation.toWire(native, tail)" in loop
    assert "liveState()" not in loop.replace("val tail = workspace?.let { ws -> guarded { ws.liveState() } }", "")
    conversation = read(MIND / "MindConversation.kt")
    assert 'fun toWire(nativeTools: Boolean, tail: String?): JSONArray' in conversation


def test_every_workspace_call_in_the_loop_is_guarded():
    loop = read(MIND / "MindLoop.kt")
    calls = re.findall(r"ws\.[a-zA-Z]+\(", loop)
    guarded = re.findall(r"guarded \{[^}]*ws\.[a-zA-Z]+\(", loop)
    assert calls and len(guarded) >= len(calls) - 0, (calls, guarded)
    assert "private fun <T> guarded(block: () -> T): T?" in loop


def test_folding_never_removes_a_message_and_recall_brings_it_back():
    conversation = read(MIND / "MindConversation.kt")
    fold = conversation[conversation.index("fun fold(from: Int, to: Int, keepText: Int): Int"):conversation.index("fun unfoldedToolChars")]
    assert "removeAt" not in fold and "messages.remove" not in fold
    assert "message.copy(compacted = true)" in fold
    workspace = read(WORKSPACE / "MissionWorkspace.kt")
    assert "fun recallTurn(number: Int): String?" in workspace and "it.full" in workspace


def test_secrets_are_never_collected_or_journaled():
    toolbox = read(MIND / "PhoneMindToolbox.kt")
    assert "if (MindMemory.looksSecret(text)) return MindToolResult.error(" in toolbox
    assert "bound.filter { it.editable && !it.password }" in toolbox
    store = read(MIND / "mission/MissionStore.kt")
    assert "JSONObject(MindRedaction.scrub(it.toString()))" in store
    workspace = read(WORKSPACE / "MissionWorkspace.kt")
    to_json = workspace[workspace.index("fun toJson(): JSONObject"):workspace.index("companion object")]
    assert '"seen"' not in to_json, "the lines of every screen stay in memory only"


def test_the_owner_keeps_the_classic_context_until_they_choose_and_lab_runs_stay_classic():
    missions = read(MIND / "mission/MindMissions.kt")
    assert "getBoolean(WORKSPACE_KEY, false)" in missions
    assert "variant == null && workspaceEnabled(context)" in missions


def test_a_diversion_at_an_approval_only_adds_a_line():
    toolbox = read(MIND / "PhoneMindToolbox.kt")
    assert 'val asked = (changed?.let { "$it " }.orEmpty()) + done.replaceFirstChar { it.lowercase() }' in toolbox
    assert "owner.awaitApproval(asked, ownerTimeoutMs, MindSend(draft" in toolbox
