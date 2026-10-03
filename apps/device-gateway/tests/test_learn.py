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

LEARNED = {
    "runId": "run-123", "learned": True, "alreadyLearned": False,
    "sentence": "Learned 3 screens, 15 controls and 1 move in Launcher, Calculator.",
    "apps": [{"package": "com.google.android.calculator", "label": "Calculator", "screens": 2, "newScreens": 2, "controls": 14, "transitions": 1}],
    "refusal": None,
}
REFUSED = {
    "runId": "run-123", "learned": False, "alreadyLearned": False, "sentence": "", "apps": [],
    "refusal": {"code": "NOTHING_TO_LEARN", "message": "This run is from before Learn existed. Run it again and press Learn."},
}


def test_learn_is_a_registered_op_without_forbidden_words():
    assert "learn.run" in ALLOWED_OPS and "learn.run" in V5_OPS
    assert not any(word in "learn.run" for word in ("shell", "powershell", "root", "su", "command", "script", "adb"))


def test_learn_forwards_one_run_id_and_returns_counts():
    svc, bridge = service({"learn.run": LEARNED})
    assert svc.learn_run("phone-1", "run-123")["sentence"].startswith("Learned 3 screens")
    assert bridge.calls == [("learn.run", {"runId": "run-123"})]
    refused, _ = service({"learn.run": REFUSED})
    assert refused.learn_run("phone-1", "run-123")["refusal"]["code"] == "NOTHING_TO_LEARN"


def test_learn_refuses_a_malformed_run_id_before_the_phone():
    svc, bridge = service({"learn.run": LEARNED})
    for bad in ("../x", "", "a b c d", 5):
        with pytest.raises(DesktopRuntimeError):
            svc.learn_run("phone-1", bad)
    assert bridge.calls == []


@pytest.mark.parametrize("broken", [
    {**LEARNED, "extra": 1},
    {**LEARNED, "runId": "other-run"},
    {**LEARNED, "apps": [{**LEARNED["apps"][0], "selector": {"text": "7"}}]},
    {**LEARNED, "apps": [{**LEARNED["apps"][0], "controls": "many"}]},
    {**LEARNED, "sentence": ""},
    {**LEARNED, "refusal": REFUSED["refusal"]},
    {**REFUSED, "refusal": {"code": "SOMETHING", "message": "x"}},
    {**REFUSED, "apps": LEARNED["apps"]},
])
def test_the_phones_learn_reply_is_validated(broken):
    svc, _ = service({"learn.run": broken})
    with pytest.raises(DesktopRuntimeError):
        svc.learn_run("phone-1", "run-123")


def test_the_learn_route_posts_to_the_phone():
    svc, bridge = service({"learn.run": LEARNED})
    app = FastAPI()
    app.include_router(create_v5_contract_router(SimpleNamespace(v5_contract=svc, fleet=None), "secret"))
    client = TestClient(app)
    assert client.post("/v1/devices/phone-1/runs/run-123/learn").status_code == 401
    response = client.post("/v1/devices/phone-1/runs/run-123/learn", headers={"Authorization": "Bearer secret"})
    assert response.status_code == 200 and response.json()["learned"] is True
    assert bridge.calls == [("learn.run", {"runId": "run-123"})]
