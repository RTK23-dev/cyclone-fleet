"""Guards for the app dictionary (plan 36 §7): structure only, one gatekeeper, JEV watches, agents can't edit it."""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
MOBILE = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile"
MANUAL = MOBILE / "manual"
DICT = MANUAL / "dictionary"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def test_no_app_specific_rules_in_the_reader_or_the_organizer():
    """The same code reads every app: no package names of other apps in the manual or the mapper's crawler."""
    pattern = re.compile(r'"(?:com|org|net|io)\.(?!cyclone\b)[a-z0-9_]+\.[a-z0-9_.]+"')
    for folder in (MANUAL, MOBILE / "mapping/crawl"):
        for path in folder.rglob("*.kt"):
            assert not pattern.search(read(path)), f"{path.name} names another app's package"


def test_only_the_organizer_and_the_owner_admit_sets():
    """Describers, the reader and models can only propose: a set becomes confirmed or locked in Organizer.kt alone."""
    for path in MOBILE.rglob("*.kt"):
        text = read(path)
        if re.search(r"status\s*=\s*EntryStatus\.(CONFIRMED|LOCKED)", text):
            assert path.name == "Organizer.kt", f"{path.name} admits a set outside the organizer"
    organizer = read(DICT / "Organizer.kt")
    # The model's answer is applied only through the fixed choices, and only to ids it was offered.
    assert "val allowed = question.nearby.map { it.id }.toSet()" in organizer
    assert "enum class Choice" in organizer and all(c in organizer for c in ('NEW("new")', 'SAME_AS("same_as")', 'SUBSET_OF("subset_of")', 'KEEP("keep")', 'REJECT("reject")'))
    # A locked set is never changed by the organizer: questions are only asked about candidates.
    assert 'val entry = dict.entries[question.entryId]?.takeIf { it.status == EntryStatus.CANDIDATE } ?: return dict' in organizer


def test_the_dictionary_holds_structure_only():
    model = read(DICT / "DictionaryModel.kt")
    entry = model[model.index("data class DictEntry("):model.index("data class AuditEvent")]
    for forbidden in ("member", "people", "contact", "count", "rowText", "value"):
        assert forbidden not in entry.split(") {")[0], f"DictEntry must not hold {forbidden}"
    keys = re.search(r"val ENTRY_KEYS = setOf\((.*?)\)", model, re.S).group(1)
    assert not re.search(r"member|people|text\"|value|email|phone", keys)
    # Every text is screened on the way in and out.
    assert "fun entry(entry: DictEntry): DictEntry?" in model and "AtlasPrivacy.structuralLabel" in model
    reader = read(MANUAL / "StructureReader.kt")
    # Only the app's own words leave the reader.
    assert "lexicon::chrome" in reader and "lexicon.chrome(" in reader
    # Row text is never read into a proposal: rows only contribute shapes, developer ids and markers the lexicon proves.
    assert "it.proof == ChromeProof.LEXICON" in reader
    lexicon = read(MANUAL / "AppLexicon.kt")
    # A label only matches a template through its digits, so a name slot can never make a name chrome.
    assert 'key.replace(DIGITS, "#")' in lexicon


def test_the_model_question_carries_no_screen_content():
    prompt = read(DICT / "OrganizerPrompt.kt")
    for forbidden in ("UiNode", "GatewayObservation", "fieldValues", "screenshot", "pngBase64"):
        assert forbidden not in prompt


def test_jev_only_watches_the_organizer():
    runtime = read(MANUAL / "ManualRuntime.kt")
    assert "Organizer.apply" not in runtime
    watched = runtime[runtime.index("private class WatchedJudge"):runtime.index("private fun jevWatching")]
    assert "val decisions = inner.decide(questions)" in watched and "return decisions" in watched
    assert "OrganizerPrompt.jevParse" in runtime and "dict.jev.add(" in runtime


def test_agents_cannot_read_or_edit_the_dictionary_or_pick_models():
    for folder in (ROOT / "tools/cyclone-agent-mcp", ROOT / "tools/codex-phone-mcp"):
        for path in folder.rglob("*.py"):
            text = read(path)
            for token in ("dictionary.edit", "dictionary/edit", "dictionary.get", "/dictionary", "models.list", "/models\""):
                assert token not in text, f"{path} reaches {token}"


def test_the_pc_sends_a_model_name_never_a_key():
    contract = read(ROOT / "apps/device-gateway/cyclone_device_gateway/desktop_runtime/v5_contract.py")
    assert 'set(describer) != {"model"}' in contract
    assert '_validate_models_response' in contract and '{"id", "label", "vision"}' in contract
    adapter = read(MOBILE / "gateway/GatewayV5MappingAdapter.kt")
    assert 'filter { it != "model" }' in adapter


def test_user_made_labels_never_reach_the_stored_dictionary():
    """A name proven only word by word could be a user's own folder or chat title (alpha.60).

    The reader may see it so a probe can prove it, but only the app's exact strings are recorded; a proven downloaded
    name waits in memory for the owner's "app word or yours?", and "Mine" keeps only a hash.
    """
    reader = read(MANUAL / "StructureReader.kt")
    assert "private val allowVocabulary: Boolean = false" in reader
    assert "word.proof == ChromeProof.LEXICON" in reader, "a screen title must be exactly one of the app's strings"
    assert "named.filter { it.proof == ChromeProof.LEXICON }" in reader, "neighbour names must be app strings only"
    memory = read(MANUAL / "PassMemory.kt")
    assert "marked.filter { it.name.proof == ChromeProof.LEXICON }" in memory
    runtime = read(MANUAL / "ManualRuntime.kt")
    assert "Organizer.record(dict, split.appStrings" in runtime and "Organizer.record(dict, split.downloaded" not in runtime
    assert "allowVocabulary = true" in runtime  # read, then split: see above
    for path in MOBILE.rglob("*.kt"):
        if path.name not in {"ManualRuntime.kt"}:
            assert "allowVocabulary = true" not in read(path), f"{path.name} turns on word-by-word names"
    model = read(DICT / "DictionaryModel.kt")
    write = model[model.index("fun write(dictionary: AppDictionary)"):model.index("fun entry(e: DictEntry)")]
    assert "review" not in write, "the review queue is never written to disk"
    assert "HASH.matches" in write, "declined names are stored as hashes only"


def test_reveal_doors_stay_behind_the_same_safety_check():
    ports = read(MOBILE / "mapping/crawl/AndroidMapperPorts.kt")
    reveal = ports[ports.index("private fun revealer("):ports.index("private fun inferPurpose(")]
    assert "if (!element.iconOnly && !floating && role != \"imagebutton\") return false" in reveal, "text rows are never revealers"
    walker = read(MOBILE / "mapping/crawl/SafeMapperWalker.kt")
    # Every candidate door, reveal included, is judged by the safety port before the one tap of a step.
    assert "val danger = safety.classify(observation, door)" in walker
    assert "MappingDoorKind.REVEAL to 2" in walker
    risk = read(MOBILE / "mapping/crawl/MapperDoorRisk.kt")
    assert "add to|buy|purchase" in risk
