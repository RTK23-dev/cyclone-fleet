"""Plan 33 (C2): phone keys and trust, sealed leases bound to task/phone/app/slot, delivery with the task, outcomes."""
from __future__ import annotations

import base64
import json
import os
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import pytest

from cyclone_device_gateway.command.center import CommandCenter, CommandError
from cyclone_device_gateway.command.delivery import place_for
from cyclone_device_gateway.desktop_runtime.models import DesktopRuntimeError, RuntimeErrorCode
from cyclone_device_gateway.desktop_runtime.v5_contract import validate_android_response

KEY = base64.b64encode(b"\x04" + os.urandom(64)).decode()
FINGERPRINT = "ABCD 0123 4567 89AB CDEF 0123 4567 89AB"


def b64(n: int) -> str:
    return base64.b64encode(os.urandom(n)).decode()


class Contract:
    def __init__(self) -> None:
        self.key = {"publicKey": KEY, "fingerprint": FINGERPRINT, "strongBox": True, "suite": "DHKEM(P-256,HKDF-SHA256)/HKDF-SHA256/AES-256-GCM"}
        self.started: list[dict[str, Any]] = []
        self.status: dict[str, dict[str, Any]] = {}
        self.reject = False

    def cc_key(self, device_id: str) -> dict[str, Any]:
        return dict(self.key)

    def cc_start(self, device_id: str, goal: str, *, task_id: str | None = None, sealed: list | None = None) -> dict[str, Any]:
        if self.reject and sealed:
            raise DesktopRuntimeError(RuntimeErrorCode.SEALED_REJECTED, "refused")
        mission = f"mdeliv{len(self.started):04d}"
        self.started.append({"device": device_id, "goal": goal, "taskId": task_id, "sealed": sealed})
        self.status[mission] = {"missionId": mission, "status": "running", "live": True, "turns": 1, "workingMs": 1, "costUsd": 0.0,
                                "summary": "", "moment": None, "leases": []}
        return {"accepted": True, "missionId": mission}

    def cc_status(self, device_id: str, mission_id: str) -> dict[str, Any]:
        return dict(self.status[mission_id])

    def cc_answer(self, *args: Any, **kwargs: Any) -> dict[str, Any]:
        return {"handled": True, "detail": "ok"}


class Clock:
    ms = int(datetime(2026, 9, 28, 9, 0, tzinfo=timezone.utc).timestamp() * 1000)

    def __call__(self) -> int:
        return self.ms


@pytest.fixture()
def world(tmp_path: Path):
    contract = Contract()
    clock = Clock()
    center = CommandCenter(tmp_path / "cc.db", contract, lambda: [{"deviceId": "phone-a", "paired": True, "state": "ready", "name": "Pixel"}], clock=clock)
    center.vault.init({"kdf": {"name": "PBKDF2", "hash": "SHA-256", "iterations": 600_000}, "salt": b64(16),
                       "wrappedVk": {"iv": b64(12), "ct": b64(48)}, "recoveryWrappedVk": {"iv": b64(12), "ct": b64(48)}})
    account = center.create_account({"service": "com.example.shop", "handle": "@shop", "ownerBasis": "mine"})
    center.vault.put_item({"id": "vi_shoplogin000000000", "accountId": account["id"], "kind": "login", "iv": b64(12), "ct": b64(100),
                           "wrappedKey": {"iv": b64(12), "ct": b64(48)}, "version": 1})
    yield center, contract, clock, account
    center.stop()


def lease_id(clock: Clock, suffix: str = "a") -> str:
    return f"ls_{clock.ms:011x}{suffix * 12}"


def envelope(center: CommandCenter, task_id: str, clock: Clock, *, slot: str = "password", lid: str | None = None, **overrides: Any) -> dict[str, Any]:
    lid = lid or lease_id(clock)
    bound = {"deviceKey": FINGERPRINT, "expiresAt": clock.ms + 30 * 60_000, "leaseId": lid, "place": "package:com.example.shop",
             "slot": slot, "taskId": task_id, **overrides}
    return {"leaseId": lid, "slot": slot, "enc": base64.b64encode(b"\x04" + os.urandom(64)).decode(), "ct": b64(40),
            "aad": json.dumps(bound, sort_keys=True, separators=(",", ":"))}


def trusted(center: CommandCenter) -> None:
    center.delivery.fetch_key("phone-a")
    center.delivery.trust("phone-a", {"fingerprint": FINGERPRINT.lower()})


def test_a_phone_is_trusted_only_with_its_own_fingerprint(world):
    center, *_ = world
    center.delivery.fetch_key("phone-a")
    assert center.delivery.phones()[0]["key"]["trusted"] is False
    with pytest.raises(CommandError):
        center.delivery.trust("phone-a", {"fingerprint": "0000 0000 0000 0000 0000 0000 0000 0000"})
    center.delivery.trust("phone-a", {"fingerprint": FINGERPRINT})
    phone = center.delivery.phones()[0]
    assert phone["key"]["trusted"] and phone["key"]["strongBox"]


def test_a_vault_task_needs_its_account_a_trusted_phone_and_its_own_item(world):
    center, _, _, account = world
    with pytest.raises(CommandError):  # the phone is not trusted yet
        center.create_task({"goal": "Sign in", "accountId": account["id"], "deviceId": "phone-a", "vaultItemId": "vi_shoplogin000000000"})
    trusted(center)
    with pytest.raises(CommandError):  # no phone chosen
        center.create_task({"goal": "Sign in", "accountId": account["id"], "vaultItemId": "vi_shoplogin000000000"})
    other = center.create_account({"service": "example.org", "handle": "me", "ownerBasis": "mine"})
    with pytest.raises(CommandError):  # the item belongs to another account
        center.create_task({"goal": "Sign in", "accountId": other["id"], "deviceId": "phone-a", "vaultItemId": "vi_shoplogin000000000"})
    task = center.create_task({"goal": "Sign in", "accountId": account["id"], "deviceId": "phone-a", "vaultItemId": "vi_shoplogin000000000"})
    assert task["vaultItemId"] == "vi_shoplogin000000000"


def test_without_a_lease_the_task_waits_and_is_listed_for_glass_to_seal(world):
    center, contract, clock, account = world
    trusted(center)
    task = center.create_task({"goal": "Sign in", "accountId": account["id"], "deviceId": "phone-a", "vaultItemId": "vi_shoplogin000000000"})
    center.tick()
    assert contract.started == []
    assert "Waiting for the vault" in center.get_task(task["id"])["cause"]
    pending = center.delivery.pending()
    assert pending[0]["taskId"] == task["id"] and pending[0]["place"] == "package:com.example.shop"
    assert pending[0]["deviceKey"]["fingerprint"] == FINGERPRINT


def test_the_bound_data_must_match_the_task_phone_app_and_slot(world):
    center, _, clock, account = world
    trusted(center)
    task = center.create_task({"goal": "Sign in", "accountId": account["id"], "deviceId": "phone-a", "vaultItemId": "vi_shoplogin000000000"})
    for bad in (
        {"taskId": "tsk_someotherone"},
        {"place": "package:com.evil.phish"},
        {"deviceKey": "0000 0000 0000 0000 0000 0000 0000 0000"},
        {"expiresAt": clock.ms + 3 * 24 * 60 * 60_000},
    ):
        with pytest.raises(CommandError):
            center.delivery.submit(task["id"], {"envelopes": [envelope(center, task["id"], clock, **bad)]})
    mismatched = envelope(center, task["id"], clock)
    mismatched["slot"] = "otp"  # the envelope says otp, the sealed data says password
    with pytest.raises(CommandError):
        center.delivery.submit(task["id"], {"envelopes": [mismatched]})
    stale = f"ls_{clock.ms - 60 * 60_000:011x}{'b' * 12}"
    with pytest.raises(CommandError):
        center.delivery.submit(task["id"], {"envelopes": [envelope(center, task["id"], clock, lid=stale)]})
    with pytest.raises(CommandError):
        center.delivery.submit(task["id"], {"envelopes": [{**envelope(center, task["id"], clock), "value": "hunter2"}]})


def test_the_envelope_rides_with_the_task_once_and_the_phone_reports_it_used(world):
    center, contract, clock, account = world
    trusted(center)
    task = center.create_task({"goal": "Sign in", "accountId": account["id"], "deviceId": "phone-a", "vaultItemId": "vi_shoplogin000000000"})
    sent = envelope(center, task["id"], clock)
    center.delivery.submit(task["id"], {"envelopes": [sent]})
    center.tick()
    start = contract.started[-1]
    assert start["taskId"] == task["id"] and start["sealed"] == [sent]
    assert "vault_fill" in start["goal"] and "you never see the value" in start["goal"]
    assert center.delivery.leases()[0]["state"] == "delivered"
    mission = next(iter(contract.status))
    contract.status[mission]["leases"] = [{"leaseId": sent["leaseId"], "state": "used"}]
    center.tick()
    assert center.delivery.leases()[0]["state"] == "used"
    with pytest.raises(CommandError):  # the task started: no new lease for it
        center.delivery.submit(task["id"], {"envelopes": [envelope(center, task["id"], clock, lid=lease_id(clock, "c"))]})
    actions = [e["action"] for e in center.audit()["entries"]]
    assert {"lease.create", "lease.delivered", "lease.used"} <= set(actions)


def test_a_changed_phone_key_is_untrusted_and_revokes_ready_leases(world):
    center, contract, clock, account = world
    trusted(center)
    task = center.create_task({"goal": "Sign in", "accountId": account["id"], "deviceId": "phone-a", "vaultItemId": "vi_shoplogin000000000"})
    center.delivery.submit(task["id"], {"envelopes": [envelope(center, task["id"], clock)]})
    contract.key = {**contract.key, "publicKey": base64.b64encode(b"\x04" + os.urandom(64)).decode(), "fingerprint": "FFFF 0123 4567 89AB CDEF 0123 4567 89AB"}
    center.delivery.fetch_key("phone-a")
    assert center.delivery.phones()[0]["key"]["trusted"] is False
    assert center.delivery.leases()[0]["state"] == "revoked"
    center.tick()
    assert contract.started == []


def test_a_refused_envelope_fails_the_task_and_expired_leases_are_not_sent(world):
    center, contract, clock, account = world
    trusted(center)
    task = center.create_task({"goal": "Sign in", "accountId": account["id"], "deviceId": "phone-a", "vaultItemId": "vi_shoplogin000000000"})
    center.delivery.submit(task["id"], {"envelopes": [envelope(center, task["id"], clock)]})
    clock.ms += 31 * 60_000
    center.tick()
    assert contract.started == [] and center.delivery.leases()[0]["state"] == "expired"
    center.delivery.submit(task["id"], {"envelopes": [envelope(center, task["id"], clock, lid=lease_id(clock, "d"))]})
    contract.reject = True
    clock.ms += 31_000
    center.tick()
    assert center.get_task(task["id"])["status"] == "failed"
    assert center.delivery.leases()[0]["state"] == "rejected"


def test_cancel_and_revoke_stop_a_lease_before_it_is_sent(world):
    center, _, clock, account = world
    trusted(center)
    task = center.create_task({"goal": "Sign in", "accountId": account["id"], "deviceId": "phone-a", "vaultItemId": "vi_shoplogin000000000"})
    made = center.delivery.submit(task["id"], {"envelopes": [envelope(center, task["id"], clock)]})
    center.delivery.revoke(made["leases"][0]["id"])
    with pytest.raises(CommandError):
        center.delivery.revoke(made["leases"][0]["id"])
    center.delivery.submit(task["id"], {"envelopes": [envelope(center, task["id"], clock, lid=lease_id(clock, "e"))]})
    center.cancel_task(task["id"])
    assert {l["state"] for l in center.delivery.leases()} == {"revoked"}


def test_places_and_the_phone_contract():
    assert place_for("com.instagram.android") == "package:com.instagram.android"
    assert place_for("shop.example.co.uk") == "chrome:https://shop.example.co.uk"
    key = {"publicKey": KEY, "fingerprint": FINGERPRINT, "strongBox": False, "suite": "DHKEM(P-256,HKDF-SHA256)/HKDF-SHA256/AES-256-GCM"}
    assert validate_android_response("cc.key", key, {})
    with pytest.raises(DesktopRuntimeError):
        validate_android_response("cc.key", {**key, "privateKey": "x"}, {})
    status = {"missionId": "mabcdefg1", "status": "running", "live": True, "turns": 1, "workingMs": 1, "costUsd": 0.0, "summary": "",
              "moment": None, "leases": [{"leaseId": "ls_0123456789aaaaaaaaaaaa", "state": "used"}]}
    assert validate_android_response("cc.status", status, {"missionId": "mabcdefg1"})
    with pytest.raises(DesktopRuntimeError):
        validate_android_response("cc.status", {**status, "leases": [{"leaseId": "ls_0123456789aaaaaaaaaaaa", "state": "used", "value": "x"}]}, {"missionId": "mabcdefg1"})
