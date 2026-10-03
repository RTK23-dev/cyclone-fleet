"""Guards for the App Manual's abilities and navigator (plan 36 §8, alpha.64): the walk never chooses, the manual is
read only everywhere, Map deeper sends a flag and no words, model text is screened, and JEV stays parked."""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
MOBILE = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile"
MANUAL = MOBILE / "manual"
GATEWAY = ROOT / "apps/device-gateway/cyclone_device_gateway"
GLASS = ROOT / "apps/glass/src"
MCP = ROOT / "tools/cyclone-agent-mcp/cyclone_agent_mcp"

NEW_FILES = ("Abilities.kt", "AbilityIndex.kt", "ManualNavigator.kt", "ManualRenderer.kt", "ManualDescriber.kt", "ManualView.kt", "ListOrder.kt")


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def test_the_walk_never_taps_what_is_left_to_choose():
    """Only doors the mapper walked and a switch's category are pressed. An offer or a button (pick) never is."""
    nav = read(MANUAL / "ManualNavigator.kt")
    presses = re.findall(r"port\.press\(([^)]*)\)", nav)
    assert presses and all(arg.strip() in {"door.label!!", "category"} for arg in presses), presses
    assert "ability.tap?.let { category ->" in nav
    assert "pick" not in "".join(presses)
    # Every press is followed by a plain-code screen check before the next one.
    assert "if (matches(dict.screens[door.to], here))" in nav
    toolbox = read(MOBILE / "mind/PhoneMindToolbox.kt")
    # The Mind's walk presses through the one act path (PhoneToolExecutor behind env.act), never anything else.
    walk = toolbox[toolbox.index("private fun goToAbility"):toolbox.index("private fun look()")]
    assert 'act("phone.click"' in walk and "phone.type" not in walk and "phone.submit_text" not in walk


def test_the_abilities_mark_what_asks_the_owner():
    abilities = read(MANUAL / "Abilities.kt")
    # A button the approval rules would ask about is described, never presented as navigation.
    assert "MapperDoorRisk.classify" in abilities and '"asks" else "choose"' in abilities
    assert 'val WALKED = setOf("navigate", "reveal", "switch")' in abilities


def test_model_text_in_the_manual_is_screened_and_kept_short():
    describer = read(MANUAL / "ManualDescriber.kt")
    assert "DictionaryPrivacy.sentence(" in describer and "DictionaryPrivacy::phrasing" in describer and "DictionaryPrivacy::goal" in describer
    # The describer sees the manual skeleton only: never a capture, a screenshot or a row.
    for forbidden in ("UiNode", "GatewayObservation", "pngBase64", "screenshot", "fieldValue"):
        assert forbidden not in describer, forbidden
    model = read(MANUAL / "dictionary/DictionaryModel.kt")
    assert 'Regex("\\\\d{3,}|@|https?:|www\\\\.|[<>{}\\\\[\\\\]]")' in model


def test_list_order_keeps_only_the_conclusion():
    reader = read(MANUAL / "StructureReader.kt")
    assert "order = ListOrder.of(rowTexts, today())" in reader
    # Row texts live only inside findLists: FoundList carries the order, never the texts.
    found = reader[reader.index("private class FoundList("):reader.index("private fun findLists")]
    assert "rowTexts" not in found and "val order: ListOrder?" in found


def test_the_manual_is_read_only_on_every_surface():
    contract = read(GATEWAY / "desktop_runtime/v5_contract.py")
    api = read(GATEWAY / "api/v5_contract_api.py")
    assert '@router.get("/v1/devices/{device_id}/manual"' in api and "/manual\"" not in re.sub(r"@router\.get[^\n]*", "", api)
    assert "manual.get" in contract and "manual.edit" not in contract
    catalog = read(MCP / "tool_catalog.py")
    assert 'ToolContract("phone_app_manual", True, True)' in catalog
    tools = read(MCP / "tools.py")
    method = tools[tools.index("def phone_app_manual"):tools.index("def phone_lab_missions")]
    assert "self.gateway.app_manual(" in method and "action" not in method


def test_map_deeper_sends_a_flag_and_no_words():
    client = read(GLASS / "services/atlasClient.ts")
    assert "if (depth.deeper === true) body.deeper = true;" in client
    contract = read(GATEWAY / "desktop_runtime/v5_contract.py")
    assert 'if "deeper" in args and not isinstance(args["deeper"], bool):' in contract
    adapter = read(MOBILE / "gateway/GatewayV5MappingAdapter.kt")
    # The phone reads its own quiz gaps; the focus words never come from the request.
    assert "SelfQuiz.focusWords(com.cyclone.mobile.manual.ManualRuntime.dictionary(context, pkg).quiz)" in adapter
    walker = read(MOBILE / "mapping/crawl/SafeMapperWalker.kt")
    # Focus only reorders; every candidate still goes through safety.classify.
    assert "compareBy<MappingDoor> { !it.focus }" in walker and "safety.classify(observation, door)" in walker


def test_jev_stays_parked_in_the_new_manual_code():
    for name in NEW_FILES:
        text = read(MANUAL / name)
        assert not re.search(r"\bjev\b|Jev[A-Z(]|JevShadow|OpenRouterVoice", text, re.I), name
