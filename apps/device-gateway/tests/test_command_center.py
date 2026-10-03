"""Plan 33 (C0): the Command Center's store, job loop, approvals inbox and routes."""
from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from cyclone_device_gateway.command import schedule
from cyclone_device_gateway.command.api import create_command_router
from cyclone_device_gateway.command.center import CommandCenter, CommandError
from cyclone_device_gateway.desktop_runtime.models import DesktopRuntimeError, RuntimeErrorCode
from cyclone_device_gateway.desktop_runtime.v5_contract import validate_android_response

TOKEN = "t" * 32


class FakeContract:
    def __init__(self) -> None:
        self.started: list[tuple[str, str]] = []
        self.answers: list[tuple[str, str, str, str | None]] = []
        self.status: dict[str, dict[str, Any]] = {}
        self.refuse: dict[str, str] = {}
        self.counter = 0

    def cc_start(self, device_id: str, goal: str) -> dict[str, Any]:
        if device_id in self.refuse:
            raise DesktopRuntimeError(RuntimeErrorCode(self.refuse[device_id]), "refused")
        self.counter += 1
        mission = f"mtest{self.counter:04d}"
        self.started.append((device_id, goal))
        self.status[mission] = {"missionId": mission, "status": "running", "live": True, "turns": 1, "workingMs": 10,
                                "costUsd": 0.01, "summary": "", "moment": None}
        return {"accepted": True, "missionId": mission}

    def cc_status(self, device_id: str, mission_id: str) -> dict[str, Any]:
        return dict(self.status[mission_id])

    def cc_answer(self, device_id: str, mission_id: str, action: str, *, request_id: str | None = None,
                  text: str | None = None, values: dict[str, str] | None = None) -> dict[str, Any]:
        moment = self.status[mission_id]["moment"]
        if action != "stop" and (moment is None or moment["requestId"] != request_id):
            raise DesktopRuntimeError(RuntimeErrorCode.MOMENT_CHANGED, "changed")
        self.answers.append((device_id, mission_id, action, request_id))
        return {"handled": True, "detail": "ok"}


class Clock:
    def __init__(self) -> None:
        self.ms = int(datetime(2026, 9, 28, 7, 0, tzinfo=timezone.utc).timestamp() * 1000)

    def __call__(self) -> int:
        return self.ms


@pytest.fixture()
def setup(tmp_path: Path):
    contract = FakeContract()
    clock = Clock()
    devices = [
        {"deviceId": "phone-a", "paired": True, "state": "ready"},
        {"deviceId": "phone-b", "paired": True, "state": "ready"},
        {"deviceId": "phone-c", "paired": False, "state": "unpaired"},
    ]
    center = CommandCenter(tmp_path / "cc.db", contract, lambda: devices, clock=clock,
                           local_now=lambda: datetime.fromtimestamp(clock.ms / 1000, tz=timezone.utc))
    yield center, contract, clock, devices
    center.stop()


def account(center: CommandCenter, **extra: Any) -> dict[str, Any]:
    body = {"service": "com.instagram.android", "handle": "@mybrand", "ownerBasis": "mine", "twofa": "totp", **extra}
    return center.create_account(body)


def test_accounts_hold_metadata_only(setup):
    center, *_ = setup
    acc = account(center, notes="Brand account for the shop")
    assert acc["ownerBasis"] == "mine" and acc["allowedDevices"] == []
    with pytest.raises(CommandError):
        center.create_account({"service": "com.x.app", "handle": "me", "ownerBasis": "mine", "password": "hunter2"})
    with pytest.raises(CommandError):
        center.create_account({"service": "com.x.app", "handle": "me", "ownerBasis": "mine", "notes": "password: hunter2"})
    with pytest.raises(CommandError):
        center.create_account({"service": "com.x.app", "handle": "me", "ownerBasis": "someone-else"})
    with pytest.raises(CommandError):
        account(center)  # duplicate
    assert len(center.list_accounts()) == 1


def test_a_task_runs_on_a_ready_phone_and_finishes_with_the_phone_s_word(setup):
    center, contract, clock, _ = setup
    acc = account(center)
    task = center.create_task({"title": "Post", "goal": "Post today's video", "accountId": acc["id"]})
    center.tick()
    assert contract.started[0][0] == "phone-a"
    assert "Use the account @mybrand (com.instagram.android)." in contract.started[0][1]
    assert center.get_task(task["id"])["status"] == "running"
    mission = center.get_task(task["id"])["run"]["missionId"]
    contract.status[mission].update(status="completed", live=False, summary="Posted.")
    center.tick()
    done = center.get_task(task["id"])
    assert done["status"] == "succeeded" and done["run"]["summary"] == "Posted."
    assert center.results()[0]["status"] == "succeeded"
    assert center.verify_audit()


def test_one_phone_per_account_and_up_to_three_tasks_per_phone(setup):
    center, contract, *_ = setup
    acc = account(center)
    first = center.create_task({"goal": "Check messages", "accountId": acc["id"]})
    second = center.create_task({"goal": "Check comments", "accountId": acc["id"]})
    third = center.create_task({"goal": "Set a timer", "deviceId": "phone-a"})
    center.tick()
    # Plan 26 §6: the phone takes the timer behind the first task; the account lock still holds the second.
    assert [d for d, _ in contract.started] == ["phone-a", "phone-a"]
    assert center.get_task(second["id"])["status"] == "waiting_device"
    assert "Another phone is using this account." in center.get_task(second["id"])["cause"]
    assert center.get_task(third["id"])["status"] == "running"
    assert center.get_task(first["id"])["status"] == "running"
    # Three at once at most; a fourth waits for one of them to end.
    fourth = center.create_task({"goal": "Open the calendar", "deviceId": "phone-a"})
    fifth = center.create_task({"goal": "Open the weather", "deviceId": "phone-a"})
    center.tick()
    assert center.get_task(fourth["id"])["status"] == "running"
    assert center.get_task(fifth["id"])["status"] == "waiting_device"
    assert len(contract.started) == 3


def test_a_phone_that_cannot_take_more_is_asked_once_per_tick(setup):
    center, contract, *_ = setup
    first = center.create_task({"goal": "Open the calendar", "deviceId": "phone-a"})
    center.tick()
    assert center.get_task(first["id"])["status"] == "running"
    contract.refuse["phone-a"] = "ASK_BUSY"
    calls = []
    original = contract.cc_start
    contract.cc_start = lambda device, goal, **kw: (calls.append(device), original(device, goal, **kw))[1]
    for goal in ("Open the weather", "Open the notes"):
        center.create_task({"goal": goal, "deviceId": "phone-a"})
    center.tick()
    assert calls == ["phone-a"]


def test_a_busy_phone_means_wait_not_fail_and_unpaired_phones_are_never_used(setup):
    center, contract, clock, _ = setup
    contract.refuse["phone-a"] = "ASK_BUSY"
    task = center.create_task({"goal": "Open the calendar", "deviceId": "phone-a"})
    center.tick()
    assert center.get_task(task["id"])["status"] == "waiting_device"
    del contract.refuse["phone-a"]
    center.tick()  # not yet: it tries again after 30 s
    assert not contract.started
    clock.ms += 31_000
    center.tick()
    assert center.get_task(task["id"])["status"] == "running"
    unpaired = center.create_task({"goal": "x y", "deviceId": "phone-c"})
    clock.ms += 31_000
    center.tick()
    assert center.get_task(unpaired["id"])["status"] == "waiting_device"


def test_approvals_mirror_the_phone_and_approve_only_that_request(setup):
    center, contract, clock, _ = setup
    task = center.create_task({"goal": "Reply to Anna", "deviceId": "phone-a"})
    center.tick()
    mission = center.get_task(task["id"])["run"]["missionId"]
    moment = {"kind": "approval", "requestId": "req-1", "text": "Send this message?", "gate": "send",
              "send": {"text": "See you at 6", "recipient": "Anna", "app": "WhatsApp"}, "choices": [], "fields": [],
              "approvableHere": True}
    contract.status[mission]["moment"] = moment
    center.tick()
    assert center.get_task(task["id"])["status"] == "needs_you"
    inbox = center.list_approvals()
    assert len(inbox) == 1 and inbox[0]["send"]["text"] == "See you at 6"
    center.tick()  # the same moment is not duplicated
    assert len(center.list_approvals()) == 1
    answered = center.answer(inbox[0]["id"], {"action": "approve"})
    assert answered["handled"] and contract.answers[-1] == ("phone-a", mission, "approve", "req-1")
    with pytest.raises(CommandError):
        center.answer(inbox[0]["id"], {"action": "approve"})  # answered once

    # The phone moved on to a new request: the old one is withdrawn and the new one arrives.
    contract.status[mission]["moment"] = {**moment, "requestId": "req-2", "approvableHere": False}
    center.tick()
    open_now = center.list_approvals()
    assert [a["approvableHere"] for a in open_now] == [False]
    with pytest.raises(CommandError):
        center.answer(open_now[0]["id"], {"action": "approve"})  # redacted: approve on the phone
    center.answer(open_now[0]["id"], {"action": "decline"})
    assert contract.answers[-1][2] == "decline"


def test_secure_input_is_never_answered_from_the_pc(setup):
    center, contract, *_ = setup
    task = center.create_task({"goal": "Log in to the shop", "deviceId": "phone-a"})
    center.tick()
    mission = center.get_task(task["id"])["run"]["missionId"]
    contract.status[mission]["moment"] = {"kind": "secret", "requestId": "req-s", "text": "Type your password",
                                          "gate": None, "send": None, "choices": [], "fields": [], "approvableHere": False}
    center.tick()
    approval = center.list_approvals()[0]
    assert approval["answerHere"] is False
    with pytest.raises(CommandError):
        center.answer(approval["id"], {"action": "reply", "text": "x"})
    with pytest.raises(CommandError):
        center.answer(approval["id"], {"action": "fill", "values": {"password": "hunter2"}})
    assert contract.answers == []


def test_routines_fan_out_to_phones_and_never_replay_missed_runs(setup):
    center, contract, clock, _ = setup
    routine = center.create_routine({"title": "Morning check", "goal": "Check the shop inbox",
                                     "deviceIds": ["phone-a", "phone-b"], "schedule": {"kind": "daily", "time": "07:30", "days": [1, 2, 3, 4, 5, 6, 7]}})
    assert routine["scheduleLabel"] == "Every day at 07:30"
    clock.ms += 30 * 60_000  # 07:30 UTC
    center.tick()
    assert sorted(d for d, _ in contract.started) == ["phone-a", "phone-b"]
    center.tick()
    assert len(contract.started) == 2  # idempotent
    # The PC was off for two days: nothing is replayed, the next run is in the future.
    clock.ms += 2 * 24 * 60 * 60_000 + 60 * 60_000
    for mission in contract.status.values():
        mission.update(status="completed", live=False)
    center.tick()
    assert len(contract.started) == 2
    assert center.get_routine(routine["id"])["nextRunAt"] > clock.ms


def test_pause_all_and_run_now(setup):
    center, contract, clock, _ = setup
    routine = center.create_routine({"title": "Hourly", "goal": "Check stock", "schedule": {"kind": "every", "minutes": 60}})
    center.pause_all(True)
    clock.ms += 2 * 60 * 60_000
    center.tick()
    assert contract.started == []
    center.run_routine_now(routine["id"])
    center.tick()
    assert len(contract.started) == 1


def test_cancel_stops_the_mission_on_the_phone(setup):
    center, contract, *_ = setup
    task = center.create_task({"goal": "Long job", "deviceId": "phone-b"})
    center.tick()
    center.cancel_task(task["id"])
    assert center.get_task(task["id"])["status"] == "cancelled"
    assert contract.answers[-1][2] == "stop"


def test_idempotent_task_creation(setup):
    center, *_ = setup
    first = center.create_task({"goal": "Once", "requestId": "abcdefgh1"})
    again = center.create_task({"goal": "Once", "requestId": "abcdefgh1"})
    assert first["id"] == again["id"]


def test_goals_with_secrets_are_refused(setup):
    center, *_ = setup
    with pytest.raises(CommandError):
        center.create_task({"goal": "Log in with password: hunter2"})


def test_schedules_validate_and_describe():
    with pytest.raises(schedule.ScheduleError):
        schedule.parse({"kind": "every", "minutes": 5})
    with pytest.raises(schedule.ScheduleError):
        schedule.parse({"kind": "daily", "time": "25:00", "days": [1]})
    weekdays = schedule.parse({"kind": "daily", "time": "07:30", "days": [5, 1, 2, 3, 4]})
    assert schedule.describe(weekdays) == "Weekdays at 07:30"
    friday = datetime(2026, 10, 2, 8, 0, tzinfo=timezone.utc)
    assert schedule.next_after(weekdays, friday).isoweekday() == 1


def test_routes_need_the_bearer_and_refuse_secrets(setup):
    center, *_ = setup
    app = FastAPI()
    app.include_router(create_command_router(type("R", (), {"command": center})(), TOKEN))
    client = TestClient(app)
    assert client.get("/v1/cc/accounts").status_code == 401
    headers = {"Authorization": f"Bearer {TOKEN}"}
    made = client.post("/v1/cc/accounts", json={"service": "example.com", "handle": "shop@example.com", "ownerBasis": "company"}, headers=headers)
    assert made.status_code == 200
    bad = client.post("/v1/cc/accounts", json={"service": "example.com", "handle": "x", "ownerBasis": "mine", "token": "abc"}, headers=headers)
    assert bad.status_code == 400
    assert client.get("/v1/cc/overview", headers=headers).json()["accounts"] == 1
    for path in ("/v1/cc/tasks", "/v1/cc/routines", "/v1/cc/results", "/v1/cc/approvals", "/v1/cc/audit"):
        assert client.get(path, headers=headers).status_code == 200


def test_contract_validates_the_phone_s_status():
    moment = {"kind": "approval", "requestId": "req-1", "text": "Send?", "gate": "send",
              "send": {"text": "hi", "recipient": "Anna", "app": "WhatsApp"}, "choices": [], "fields": [], "approvableHere": True}
    value = {"missionId": "mabcdefg1", "status": "running", "live": True, "turns": 2, "workingMs": 5, "costUsd": 0.0,
             "summary": "", "moment": moment, "leases": []}
    assert validate_android_response("cc.status", value, {"missionId": "mabcdefg1"})
    with pytest.raises(DesktopRuntimeError):
        validate_android_response("cc.status", {**value, "moment": {**moment, "typedValue": "x"}}, {"missionId": "mabcdefg1"})
    with pytest.raises(DesktopRuntimeError):
        validate_android_response("cc.start", {"accepted": True, "missionId": "../x"}, {})
