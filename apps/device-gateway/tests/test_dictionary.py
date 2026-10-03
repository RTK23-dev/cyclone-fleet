from __future__ import annotations

from types import SimpleNamespace

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from cyclone_device_gateway.api.v5_contract_api import create_v5_contract_router
from cyclone_device_gateway.cyclone_bridge.protocol import ALLOWED_OPS
from cyclone_device_gateway.desktop_runtime.models import DesktopRuntimeError
from cyclone_device_gateway.desktop_runtime.v5_contract import V5_OPS

from test_market import service

PLACE = "package:com.instagram.android"
ANCHOR = {"kind": "view", "roomKey": "screen:list:0123456789abcdef", "screenTitle": "Messages", "position": 0,
          "siblings": ["General", "Requests"], "groups": [], "rowShape": None, "searchable": False, "searchLabel": None}
ENTRY = {
    "id": "set:primary", "kind": "conversation", "name": "Primary", "shownName": "Primary", "nameProof": "lexicon", "aliases": [],
    "parentId": "set:messages", "path": "Conversation › Messages › Primary", "status": "confirmed", "redirectTo": None,
    "anchors": [ANCHOR], "markers": [], "observations": 3, "days": 1, "versions": ["402.0"], "missedPasses": 0,
    "note": "gates: all gates passed", "failedGates": [], "waiting": None,
}
DICT = {
    "placeId": PLACE, "appLabel": "Instagram", "currentVersion": "402.0", "passes": 2, "updatedAt": 1_700_000_000_000,
    "coreKinds": [{"wire": "person", "label": "Person"}], "entries": [ENTRY], "truncated": False,
    "audit": [{"at": 1, "action": "confirmed", "entryId": "set:primary", "detail": "“Primary”", "by": "gate"}],
    "health": {"orphans": [], "nearDuplicates": [], "tooDeep": [], "tooWide": [], "staleCandidates": [], "notSeenInVersion": []},
    "jev": {"summary": "No decisions yet.", "answered": 0, "agreed": 0, "sureAnswered": 0, "sureAgreed": 0},
    "glossary": "Dictionary of Instagram:\n  set:primary = Conversation › Messages › Primary",
    "screens": [{"roomKey": "screen:list:0123456789abcdef", "name": "Messages", "title": "Messages", "category": "Primary", "via": None,
                 "panelOf": None, "items": ["Add photos and files"], "sets": ["set:primary"], "seen": 3, "purpose": "Your direct messages.",
                 "list": {"shape": "2 texts · image", "order": "newest_first", "groups": [], "searchable": True, "searchLabel": "Search"}},
                {"roomKey": "screen:unknown:fedcba98765abcde", "name": "Add photos and files", "title": None, "category": None,
                 "via": "Add photos and files", "panelOf": "screen:list:0123456789abcdef", "items": ["Camera", "Files"], "sets": [], "seen": 1,
                 "purpose": None, "list": None}],
    "doors": [{"edgeId": "edge:" + "a" * 64, "from": "screen:list:0123456789abcdef", "to": "screen:unknown:fedcba98765abcde",
               "label": "Add photos and files", "kind": "reveal"}],
    "review": [{"id": "rv:0123456789abcdef", "name": "Close friends", "screenTitle": "Messages", "siblings": ["Primary"], "seenAt": 5}],
}
MODELS = {"active": {"id": "anthropic/claude-fable-5.1", "label": "Claude Fable 5.1", "vision": True},
          "models": [{"id": "anthropic/claude-fable-5.1", "label": "Claude Fable 5.1", "vision": True}]}


def test_dictionary_ops_are_registered_without_forbidden_words():
    for op in ("dictionary.get", "dictionary.edit", "models.list"):
        assert op in ALLOWED_OPS and op in V5_OPS
        assert not any(word in op for word in ("shell", "powershell", "root", "su", "command", "script", "adb"))


def test_dictionary_get_and_edit_go_to_the_phone():
    svc, bridge = service({"dictionary.get": DICT, "dictionary.edit": DICT})
    assert svc.dictionary_get("phone-1", PLACE)["entries"][0]["path"] == "Conversation › Messages › Primary"
    svc.dictionary_edit("phone-1", {"placeId": PLACE, "action": "lock", "id": "set:primary"})
    assert bridge.calls == [("dictionary.get", {"placeId": PLACE}), ("dictionary.edit", {"placeId": PLACE, "action": "lock", "id": "set:primary"})]


@pytest.mark.parametrize("body", [
    {"placeId": PLACE, "action": "drop", "id": "set:primary"},
    {"placeId": PLACE, "action": "lock", "id": "../etc"},
    {"placeId": PLACE, "action": "rename", "id": "set:primary", "label": "x" * 200},
    {"placeId": PLACE, "action": "lock", "id": "set:primary", "members": ["Sam"]},
    {"placeId": "com.instagram.android", "action": "lock", "id": "set:primary"},
])
def test_bad_edits_never_reach_the_phone(body):
    svc, bridge = service({"dictionary.edit": DICT})
    with pytest.raises(DesktopRuntimeError):
        svc.dictionary_edit("phone-1", body)
    assert bridge.calls == []


@pytest.mark.parametrize("broken", [
    {**DICT, "members": ["Sam Jones"]},
    {**DICT, "entries": [{**ENTRY, "members": ["Sam Jones"]}]},
    {**DICT, "entries": [{**ENTRY, "id": "stock.instagram"}]},
    {**DICT, "entries": [{**ENTRY, "name": "x" * 90}]},
    {**DICT, "entries": [{**ENTRY, "anchors": [{**ANCHOR, "rowText": "See you at 7"}]}]},
    {**DICT, "placeId": "package:com.other.app"},
])
def test_the_phones_dictionary_reply_is_validated(broken):
    svc, _ = service({"dictionary.get": broken})
    with pytest.raises(DesktopRuntimeError):
        svc.dictionary_get("phone-1", PLACE)


def test_models_and_the_describer_choice():
    svc, bridge = service({"models.list": MODELS, "mapping.start": {}})
    assert svc.models_list("phone-1")["active"]["label"] == "Claude Fable 5.1"
    with pytest.raises(DesktopRuntimeError):
        svc.forward("phone-1", "mapping.start", {"placeId": PLACE, "persona": "mapping", "describer": {"model": "phone", "key": "sk-or-x"}})
    with pytest.raises(DesktopRuntimeError):
        svc.forward("phone-1", "mapping.start", {"placeId": PLACE, "persona": "mapping", "describer": "phone"})
    broken, _ = service({"models.list": {**MODELS, "active": {**MODELS["active"], "key": "sk-or-v1"}}})
    with pytest.raises(DesktopRuntimeError):
        broken.models_list("phone-1")


def test_the_dictionary_routes():
    svc, bridge = service({"dictionary.get": DICT, "dictionary.edit": DICT, "models.list": MODELS})
    app = FastAPI()
    app.include_router(create_v5_contract_router(SimpleNamespace(v5_contract=svc, fleet=None), "secret"))
    client = TestClient(app)
    auth = {"Authorization": "Bearer secret"}
    assert client.get(f"/v1/devices/phone-1/dictionary?placeId={PLACE}").status_code == 401
    assert client.get(f"/v1/devices/phone-1/dictionary?placeId={PLACE}", headers=auth).json()["appLabel"] == "Instagram"
    edit = client.post("/v1/devices/phone-1/dictionary/edit", headers=auth, json={"placeId": PLACE, "action": "confirm", "id": "set:primary"})
    assert edit.status_code == 200
    assert client.get("/v1/devices/phone-1/models", headers=auth).json()["models"][0]["vision"] is True


def test_screens_doors_and_the_review_queue_are_validated_and_answered():
    svc, bridge = service({"dictionary.get": DICT, "dictionary.edit": DICT})
    assert svc.dictionary_get("phone-1", PLACE)["screens"][1]["panelOf"] == "screen:list:0123456789abcdef"
    svc.dictionary_edit("phone-1", {"placeId": PLACE, "action": "app_word", "id": "rv:0123456789abcdef"})
    assert bridge.calls[-1] == ("dictionary.edit", {"placeId": PLACE, "action": "app_word", "id": "rv:0123456789abcdef"})
    for body in ({"placeId": PLACE, "action": "mine", "id": "set:primary"},
                 {"placeId": PLACE, "action": "app_word", "id": "rv:0123456789abcdef", "label": "x"}):
        with pytest.raises(DesktopRuntimeError):
            svc.dictionary_edit("phone-1", body)
    for broken in ({**DICT, "screens": [{**DICT["screens"][0], "rowText": "See you"}]},
                   {**DICT, "screens": [{**DICT["screens"][0], "roomKey": "../etc"}]},
                   {**DICT, "doors": [{**DICT["doors"][0], "label": "x" * 90}]},
                   {**DICT, "review": [{**DICT["review"][0], "members": ["Sam"]}]}):
        bad, _ = service({"dictionary.get": broken})
        with pytest.raises(DesktopRuntimeError):
            bad.dictionary_get("phone-1", PLACE)


ABILITY = {"id": "ab:0123456789ab", "kind": "offer", "name": "Camera (in Add photos and files)", "place": "screen:unknown:fedcba98765abcde",
           "placeName": "Add photos and files", "path": ["Messages", "Add photos and files", "Camera"], "tap": None, "pick": "Camera",
           "effect": "choose", "setId": None, "provenance": "mapped", "confidence": 0.75, "note": None, "say": ["Camera"]}
MANUAL = {
    "placeId": PLACE, "appLabel": "Instagram", "currentVersion": "402.0", "abilities": [ABILITY], "truncated": False,
    "query": "attach a photo", "hits": [{"id": "ab:0123456789ab", "score": 0.91}], "clear": False,
    "quiz": {"at": 5, "asked": 2, "answered": 1, "goals": [{"goal": "attach a photo", "abilityId": "ab:0123456789ab", "score": 0.91},
                                                          {"goal": "order a pizza", "abilityId": None, "score": 0.0}]},
    "scores": {"map": 0.75, "dictionary": 1.0, "quiz": 0.5, "walks": None, "places": 1, "named": 1, "panels": 1, "lists": 1, "ordered": 1,
               "abilities": 1, "walkedAbilities": 0},
    "markdown": "# Instagram (com.instagram.android) · 402.0 · look only\n",
}


def test_the_manual_is_read_only_validated_and_routed():
    assert "manual.get" in ALLOWED_OPS and "manual.get" in V5_OPS
    svc, bridge = service({"manual.get": MANUAL})
    assert svc.manual_get("phone-1", PLACE, " attach a photo ")["hits"][0]["id"] == "ab:0123456789ab"
    assert bridge.calls == [("manual.get", {"placeId": PLACE, "query": "attach a photo"})]
    with pytest.raises(DesktopRuntimeError):
        svc.manual_get("phone-1", PLACE, "x" * 300)
    with pytest.raises(DesktopRuntimeError):
        svc.forward("phone-1", "manual.get", {"placeId": PLACE, "members": True})
    for broken in ({**MANUAL, "members": ["Sam"]},
                   {**MANUAL, "abilities": [{**ABILITY, "rowText": "See you at 7"}]},
                   {**MANUAL, "abilities": [{**ABILITY, "effect": "pay"}]},
                   {**MANUAL, "hits": [{"id": "ab:ffffffffffff", "score": 0.9}]},
                   {**MANUAL, "scores": {**MANUAL["scores"], "map": 3}},
                   {**MANUAL, "quiz": {**MANUAL["quiz"], "goals": [{"goal": "x" * 200, "abilityId": None, "score": 0}]}}):
        bad, _ = service({"manual.get": broken})
        with pytest.raises(DesktopRuntimeError):
            bad.manual_get("phone-1", PLACE)
    app = FastAPI()
    app.include_router(create_v5_contract_router(SimpleNamespace(v5_contract=svc, fleet=None), "secret"))
    client = TestClient(app)
    assert client.get(f"/v1/devices/phone-1/manual?placeId={PLACE}").status_code == 401
    got = client.get(f"/v1/devices/phone-1/manual?placeId={PLACE}&q=attach", headers={"Authorization": "Bearer secret"})
    assert got.status_code == 200 and got.json()["scores"]["quiz"] == 0.5


def test_map_deeper_is_a_plain_flag():
    svc, bridge = service({"mapping.start": {}})
    with pytest.raises(DesktopRuntimeError):
        svc.forward("phone-1", "mapping.start", {"placeId": PLACE, "persona": "mapping", "deeper": ["order", "pizza"]})
    assert bridge.calls == []
