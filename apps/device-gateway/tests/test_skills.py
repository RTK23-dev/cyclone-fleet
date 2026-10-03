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

SKILL = {
    "skillId": "you.0123456789ab", "name": "Set a 10 minute timer", "placeId": "package:com.google.android.deskclock",
    "ground": "grounded", "detail": "1 known move to “Timer”.", "routeMoves": 1,
    "route": [{"title": "Alarm", "screenId": "page:bWluZC0xMjM"}, {"title": "Timer", "screenId": None}],
    "finishSteps": 4, "savedAt": 1_700_000_000_000,
}
LEGACY = {**SKILL, "skillId": "you.fedcba987654", "placeId": None, "ground": "not-grounded", "detail": "Saved before skills were grounded.",
          "routeMoves": None, "route": [], "finishSteps": 0, "savedAt": None}
LISTED = {"skills": [SKILL, LEGACY], "truncated": False}


def test_skills_list_is_a_registered_op_without_forbidden_words():
    assert "skills.list" in ALLOWED_OPS and "skills.list" in V5_OPS
    assert not any(word in "skills.list" for word in ("shell", "powershell", "root", "su", "command", "script", "adb"))


def test_skills_list_returns_the_phones_skills():
    svc, bridge = service({"skills.list": LISTED})
    assert [s["ground"] for s in svc.skills_list("phone-1")["skills"]] == ["grounded", "not-grounded"]
    assert bridge.calls == [("skills.list", {})]


@pytest.mark.parametrize("broken", [
    {**LISTED, "extra": 1},
    {"skills": [{**SKILL, "goal": "Set a timer for Louella"}], "truncated": False},
    {"skills": [{**SKILL, "skillId": "stock.instagram"}], "truncated": False},
    {"skills": [{**SKILL, "ground": "great"}], "truncated": False},
    {"skills": [{**SKILL, "route": [{"title": "Alarm", "screenId": "../etc"}]}], "truncated": False},
    {"skills": [{**SKILL, "route": [{"title": "Alarm", "screenId": None, "text": "hi"}]}], "truncated": False},
    {"skills": [{**LEGACY, "route": SKILL["route"]}], "truncated": False},
    {"skills": [{**SKILL, "finishSteps": "four"}], "truncated": False},
])
def test_the_phones_skills_reply_is_validated(broken):
    svc, _ = service({"skills.list": broken})
    with pytest.raises(DesktopRuntimeError):
        svc.skills_list("phone-1")


def test_the_skills_route_reads_from_the_phone():
    svc, bridge = service({"skills.list": LISTED})
    app = FastAPI()
    app.include_router(create_v5_contract_router(SimpleNamespace(v5_contract=svc, fleet=None), "secret"))
    client = TestClient(app)
    assert client.get("/v1/devices/phone-1/skills").status_code == 401
    response = client.get("/v1/devices/phone-1/skills", headers={"Authorization": "Bearer secret"})
    assert response.status_code == 200 and response.json()["skills"][0]["name"] == "Set a 10 minute timer"
    assert bridge.calls == [("skills.list", {})]
