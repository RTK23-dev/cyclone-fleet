"""Alpha.102 Numbers: every number Cyclone can receive codes on, in one place. Numbers only, never a text or a code."""
from __future__ import annotations

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from cyclone_device_gateway.desktop_runtime.models import DesktopRuntimeError
from cyclone_device_gateway.desktop_runtime.v5_contract import V5ContractService
from cyclone_device_gateway.numbers.api import create_numbers_router
from cyclone_device_gateway.numbers.service import NumbersError, NumbersService, clean_number, number_key

from test_v5_contract import FakeBridge, FakeFleet

DAY = 86_400_000
NOW = 1_800_000_000_000


class World:
    """Two phones, one forwarder plugin, two accounts; the owner's clock."""

    def __init__(self) -> None:
        self.now = NOW
        self.devices = [
            {"deviceId": "pixel", "name": "Pixel 8", "paired": True, "state": "ready"},
            {"deviceId": "galaxy", "name": "Galaxy A54", "paired": True, "state": "ready"},
            {"deviceId": "spare", "name": "Spare", "paired": False, "state": "unpaired"},
        ]
        self.reports = {
            "pixel": {"enabled": True, "canRead": True, "numbers": [{"number": "+31612345678", "source": "sim", "slot": 1}]},
            "galaxy": {"enabled": False, "canRead": True, "numbers": [{"number": "0687654321", "source": "confirmed", "slot": None}]},
        }
        self.reads: list[str] = []
        self.plugins = [
            {"name": "sms-forwarder", "status": "active", "serves": [{"port": "code.in", "allowed": True}]},
            {"name": "logger", "status": "active", "serves": [{"port": "log.line", "allowed": True}]},
        ]
        self.accounts = [{"id": "acc_food", "service": "Instagram", "handle": "brand.food"},
                         {"id": "acc_fit", "service": "Instagram", "handle": "brand.fit"}]

    def read_phone(self, device_id: str):
        self.reads.append(device_id)
        report = self.reports[device_id]
        if isinstance(report, Exception):
            raise report
        return report

    def service(self, tmp_path) -> NumbersService:
        return NumbersService(tmp_path / "numbers.db", devices=lambda: self.devices, read_phone=self.read_phone,
                              plugins=lambda: self.plugins, accounts=lambda: self.accounts, clock=lambda: self.now)


@pytest.fixture
def world():
    return World()


def by_number(overview, key):
    return next(n for n in overview["numbers"] if number_key(n["number"]) == key)


def test_numbers_are_cleaned_and_one_number_written_two_ways_is_one():
    assert clean_number(" +31 6 1234 5678 ") == "+31612345678"
    assert clean_number("06-1234-5678") == "0612345678"
    assert number_key("+31612345678") == number_key("0612345678")
    for bad in ["12345", "+31 6 abc", "phone", "+1234567890123456", 612345678]:
        with pytest.raises(NumbersError):
            clean_number(bad)


def test_the_fleets_phones_report_their_numbers_with_their_origin(world, tmp_path):
    service = world.service(tmp_path)
    overview = service.overview()
    pixel = by_number(overview, "612345678")
    assert pixel["origin"] == "phone" and pixel["source"]["name"] == "Pixel 8" and pixel["source"]["slot"] == 1
    assert pixel["state"] == "ready"
    galaxy = by_number(overview, "687654321")
    assert galaxy["state"] == "codes_off" and "off" in galaxy["why"]
    phones = {p["deviceId"]: p for p in overview["phones"]}
    assert phones["pixel"]["state"] == "ready" and phones["galaxy"]["state"] == "off"
    assert "spare" not in phones, "an unpaired phone isn't asked"
    assert overview["summary"]["byOrigin"]["phone"] == 2 and overview["summary"]["ready"] == 1
    assert [p["name"] for p in overview["plugins"]] == ["sms-forwarder"], "only plugins that forward codes are sources"


def test_phones_are_read_at_most_once_a_minute_unless_asked(world, tmp_path):
    service = world.service(tmp_path)
    service.overview()
    service.overview()
    assert world.reads.count("pixel") == 1
    service.overview(refresh=True)
    assert world.reads.count("pixel") == 2
    world.now += 61_000
    service.overview()
    assert world.reads.count("pixel") == 3


def test_an_offline_or_older_phone_keeps_its_numbers_and_says_why(world, tmp_path):
    service = world.service(tmp_path)
    service.overview()
    world.devices[0]["state"] = "disconnected"
    world.reports["galaxy"] = DesktopRuntimeError("CAPABILITY_UNAVAILABLE", "old app")
    overview = service.overview(refresh=True)
    assert by_number(overview, "612345678")["state"] == "offline"
    phones = {p["deviceId"]: p for p in overview["phones"]}
    assert phones["pixel"]["state"] == "offline"
    assert phones["galaxy"]["state"] == "unreadable" and "Update Cyclone" in phones["galaxy"]["hint"]
    assert by_number(overview, "687654321")["origin"] == "phone", "a number is never dropped because a read failed"


def test_rented_and_forwarded_numbers_are_added_by_the_owner(world, tmp_path):
    service = world.service(tmp_path)
    rented = service.add({"number": "+44 7700 900123", "label": "Food niche", "origin": "rental", "provider": "Acme Numbers",
                          "source": "sms-forwarder", "expiresAt": NOW + 3 * DAY})
    assert rented["origin"] == "rental" and rented["source"]["provider"] == "Acme Numbers"
    assert rented["state"] == "ready" and rented["expiresSoon"] is True
    forwarded = service.add({"number": "+32470111222", "origin": "plugin", "source": "missing-plugin"})
    assert forwarded["state"] == "no_source"
    tracked = service.add({"number": "+33612121212", "origin": "other"})
    assert tracked["state"] == "manual"
    with pytest.raises(NumbersError, match="already here"):
        service.add({"number": "0044 7700 900123", "origin": "other"})
    with pytest.raises(NumbersError, match="provider"):
        service.add({"number": "+49151000111", "origin": "rental"})
    with pytest.raises(NumbersError, match="appear by themselves"):
        service.add({"number": "+49151000111", "origin": "phone"})
    with pytest.raises(NumbersError, match="end date"):
        service.add({"number": "+49151000111", "origin": "other", "expiresAt": NOW})
    world.now = NOW + 4 * DAY
    assert by_number(service.overview(), "700900123")["state"] == "expired"


def test_one_number_one_account(world, tmp_path):
    service = world.service(tmp_path)
    service.overview()
    pixel = by_number(service.overview(), "612345678")
    assigned = service.update(pixel["id"], {"accountId": "acc_food", "label": "Food"})
    assert assigned["account"]["handle"] == "brand.food" and assigned["label"] == "Food"
    other = service.add({"number": "+447700900123", "origin": "rental", "provider": "Acme"})
    with pytest.raises(NumbersError, match="One account, one number"):
        service.update(other["id"], {"accountId": "acc_food"})
    with pytest.raises(NumbersError, match="No such account"):
        service.update(other["id"], {"accountId": "acc_none"})
    assert service.number_for_account("acc_food")["number"] == "+31612345678"
    assert service.update(pixel["id"], {"accountId": None})["account"] is None
    assert service.number_for_account("acc_food") is None


def test_pausing_and_removing(world, tmp_path):
    service = world.service(tmp_path)
    service.overview()
    pixel = by_number(service.overview(), "612345678")
    assert service.update(pixel["id"], {"paused": True})["state"] == "paused"
    with pytest.raises(NumbersError, match="listed by the phone"):
        service.remove(pixel["id"])
    with pytest.raises(NumbersError, match="source can't change"):
        service.update(pixel["id"], {"provider": "x"})
    rented = service.add({"number": "+447700900123", "origin": "rental", "provider": "Acme"})
    assert service.remove(rented["id"]) == {"id": rented["id"], "removed": True}
    with pytest.raises(NumbersError, match="No such number"):
        service.update(rented["id"], {"label": "x"})


def test_a_rented_number_that_turns_up_on_a_phone_moves_to_the_phone(world, tmp_path):
    service = world.service(tmp_path)
    rented = service.add({"number": "+31612345678", "origin": "rental", "provider": "Acme", "label": "Kept"})
    moved = by_number(service.overview(refresh=True), "612345678")
    assert moved["id"] == rented["id"] and moved["origin"] == "phone" and moved["label"] == "Kept"


def test_numbers_list_is_checked_on_the_pc():
    class NumbersBridge(FakeBridge):
        def __init__(self, result):
            super().__init__()
            self.result = result

        def request(self, op, args, request_id=None):
            if op == "numbers.list":
                return self.result
            return super().request(op, args, request_id)

    good = {"enabled": True, "canRead": True, "numbers": [{"number": "+31612345678", "source": "sim", "slot": 1}]}
    assert V5ContractService(FakeFleet(NumbersBridge(good))).numbers_list("phone-1") == good
    for bad in [
        {"enabled": True, "canRead": True},
        {**good, "texts": []},
        {**good, "numbers": [{"number": "+31 6 12", "source": "sim", "slot": 1}]},
        {**good, "numbers": [{"number": "+31612345678", "source": "sms", "slot": 1}]},
        {**good, "numbers": [{"number": "+31612345678", "source": "sim", "slot": 1, "body": "Your code is 123456"}]},
        {**good, "numbers": [{"number": "+31612345678", "source": "sim", "slot": None}] * 9},
    ]:
        with pytest.raises(DesktopRuntimeError) as error:
            V5ContractService(FakeFleet(NumbersBridge(bad))).numbers_list("phone-1")
        assert error.value.code == "PROTOCOL_MISMATCH"
    from cyclone_device_gateway.cyclone_bridge.protocol import ALLOWED_OPS
    assert "numbers.list" in ALLOWED_OPS


def test_routes_need_the_bearer_and_speak_plainly(world, tmp_path):
    app = FastAPI()
    app.include_router(create_numbers_router(world.service(tmp_path), "secret-token"))
    client = TestClient(app)
    assert client.get("/v1/numbers").status_code == 401
    auth = {"Authorization": "Bearer secret-token"}
    overview = client.get("/v1/numbers?refresh=true", headers=auth).json()
    assert overview["summary"]["total"] == 2
    added = client.post("/v1/numbers", headers=auth, json={"number": "+447700900123", "origin": "rental", "provider": "Acme"})
    assert added.status_code == 200
    number_id = added.json()["id"]
    assert client.post("/v1/numbers", headers=auth, json={"number": "07700900123", "origin": "other"}).status_code == 409
    assert client.post(f"/v1/numbers/{number_id}", headers=auth, json={"accountId": "acc_fit"}).json()["account"]["handle"] == "brand.fit"
    assert client.get("/v1/accounts/acc_fit/number", headers=auth).json()["number"]["number"] == "+447700900123"
    assert client.post("/v1/numbers/num_missing/delete", headers=auth).status_code == 404
    assert client.post(f"/v1/numbers/{number_id}/delete", headers=auth).json()["removed"] is True
