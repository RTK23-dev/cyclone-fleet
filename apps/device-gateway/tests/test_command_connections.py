"""Plan 33 (C3): connections (MCP with OAuth), allowlists, caps and approvals, artifacts, the make-and-post task, and
pre-authorised leases for routines."""
from __future__ import annotations

import base64
import hashlib
import json
import os
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from cyclone_device_gateway.command import center as center_module
from cyclone_device_gateway.command.api import create_command_router, create_oauth_callback_router
from cyclone_device_gateway.command.center import CommandCenter, CommandError
from cyclone_device_gateway.command.connections import GrantStore
from cyclone_device_gateway.desktop_runtime.models import DesktopRuntimeError
from cyclone_device_gateway.desktop_runtime.v5_contract import validate_android_response

from fake_mcp import VIDEO, FakeMcp

KEY = base64.b64encode(b"\x04" + os.urandom(64)).decode()
FINGERPRINT = "ABCD 0123 4567 89AB CDEF 0123 4567 89AB"


def b64(n: int) -> str:
    return base64.b64encode(os.urandom(n)).decode()


class Contract:
    def __init__(self) -> None:
        self.started: list[dict[str, Any]] = []
        self.media: dict[str, bytearray] = {}
        self.chunks = 0
        self.status: dict[str, dict[str, Any]] = {}

    def cc_key(self, device_id: str) -> dict[str, Any]:
        return {"publicKey": KEY, "fingerprint": FINGERPRINT, "strongBox": True, "suite": "DHKEM(P-256,HKDF-SHA256)/HKDF-SHA256/AES-256-GCM"}

    def cc_start(self, device_id: str, goal: str, *, task_id: str | None = None, sealed: list | None = None, publish: bool = False) -> dict[str, Any]:
        mission = f"mconn{len(self.started):04d}"
        self.started.append({"device": device_id, "goal": goal, "taskId": task_id, "sealed": sealed, "publish": publish})
        self.status[mission] = {"missionId": mission, "status": "completed", "live": False, "turns": 3, "workingMs": 10, "costUsd": 0.0,
                                "summary": "Posted.", "moment": None, "leases": []}
        return {"accepted": True, "missionId": mission}

    def cc_status(self, device_id: str, mission_id: str) -> dict[str, Any]:
        return dict(self.status[mission_id])

    def cc_answer(self, *args: Any, **kwargs: Any) -> dict[str, Any]:
        return {"handled": True, "detail": "ok"}

    def cc_media(self, device_id: str, task_id: str, *, name: str, mime: str, size: int, sha256: str, offset: int, data: bytes) -> dict[str, Any]:
        self.chunks += 1
        buf = self.media.setdefault(task_id, bytearray())
        assert offset == len(buf)
        buf.extend(data)
        done = len(buf) == size and hashlib.sha256(buf).hexdigest() == sha256
        return {"received": len(buf), "done": done, "name": name if done else None, "folder": "Movies/Cyclone" if done else None}


class Clock:
    ms = int(datetime(2026, 9, 28, 9, 0, tzinfo=timezone.utc).timestamp() * 1000)

    def __call__(self) -> int:
        return self.ms


@pytest.fixture()
def fake():
    server = FakeMcp(polls_until_done=2)
    yield server
    server.close()


@pytest.fixture()
def world(tmp_path: Path, fake: FakeMcp):
    contract = Contract()
    clock = Clock()
    center = CommandCenter(tmp_path / "cc.db", contract, lambda: [{"deviceId": "phone-a", "paired": True, "state": "ready", "name": "Pixel"}],
                           clock=clock, local_now=lambda: datetime.fromtimestamp(clock.ms / 1000, timezone.utc),
                           connections={"spawn": lambda fn: fn(), "sleep": lambda s: None})
    yield center, contract, clock
    center.stop()


REDIRECT = "http://127.0.0.1:8765/v1/cc/connections/oauth/callback"


def browser(url: str) -> dict[str, str]:
    """The owner's browser on the sign-in page: it comes back to Cyclone with a code and the state."""
    opener = urllib.request.build_opener(type("NoRedirect", (urllib.request.HTTPRedirectHandler,), {"redirect_request": lambda *a, **k: None})())
    try:
        opener.open(url)
    except urllib.error.HTTPError as exc:
        location = exc.headers["Location"]
        assert location.startswith(REDIRECT)
        return dict(urllib.parse.parse_qsl(urllib.parse.urlsplit(location).query))
    raise AssertionError("no redirect")


def connected(center: CommandCenter, fake: FakeMcp, **settings: Any) -> dict[str, Any]:
    added = center.connections.add({"name": "Higgsfield", "url": fake.url})
    assert added["status"] == "needs_sign_in" and added["auth"] == "oauth"
    back = browser(center.connections.begin_sign_in(added["id"], REDIRECT)["authorizationUrl"])
    center.connections.finish_sign_in(back["state"], back["code"])
    return center.connections.settings(added["id"], {"allowed": ["generate_video", "get_result"], **settings})


def test_sign_in_with_oauth_keeps_the_grant_out_of_the_database_and_glass(world, fake, tmp_path):
    center, *_ = world
    connection = connected(center, fake)
    assert connection["status"] == "ready" and connection["signedIn"] is True
    assert [t["name"] for t in connection["tools"]] == ["generate_video", "get_result", "delete_account"]
    fields = {f["name"]: f for f in connection["tools"][0]["fields"]}
    assert fields["aspect"]["enum"] == ["9:16", "16:9", "1:1"] and fields["prompt"]["required"]
    assert fake.token_requests[0]["grant_type"] == "authorization_code" and fake.token_requests[0]["code_verifier"]
    token = next(iter(fake.tokens))
    assert token not in json.dumps(center.connections.list())
    center.stop()
    for path in tmp_path.rglob("*"):
        if path.is_file():
            assert token.encode() not in path.read_bytes(), f"{path} holds the sign-in token"
    center._db = __import__("sqlite3").connect(":memory:")  # stop() closed it; the fixture stops again


def test_a_sign_in_state_works_once_and_expires(world, fake):
    center, _, clock = world
    added = center.connections.add({"name": "Higgsfield", "url": fake.url})
    back = browser(center.connections.begin_sign_in(added["id"], REDIRECT)["authorizationUrl"])
    with pytest.raises(CommandError):
        center.connections.finish_sign_in("forged-state", back["code"])
    clock.ms += 11 * 60_000
    with pytest.raises(CommandError, match="expired"):
        center.connections.finish_sign_in(back["state"], back["code"])


def test_only_allowed_tools_run_and_secrets_never_go_into_a_call(world, fake):
    center, *_ = world
    connection = connected(center, fake, approval="cap", dailyCap=5)
    with pytest.raises(CommandError, match="not allowed"):
        center.connections.call(connection["id"], "delete_account", {})
    with pytest.raises(CommandError, match="secret"):
        center.connections.call(connection["id"], "generate_video", {"prompt": "password: hunter2"})
    assert [c["name"] for c in fake.calls] == []


def test_always_asks_first_then_polls_and_keeps_the_video(world, fake):
    center, *_ = world
    connection = connected(center, fake)  # the default rule is always
    call = center.connections.call(connection["id"], "generate_video", {"prompt": "A cat surfing", "aspect": "9:16"}, poll_tool="get_result")
    assert call["state"] == "waiting" and fake.calls == []
    [approval] = center.list_approvals()
    assert approval["kind"] == "spend" and approval["answerHere"] and "credits" in approval["text"]
    assert approval["send"]["arguments"]["prompt"] == "A cat surfing"
    with pytest.raises(CommandError):
        center.answer(approval["id"], {"action": "reply", "text": "ok"})
    center.answer(approval["id"], {"action": "approve"})
    done = center.connections.calls()[0]
    assert done["state"] == "done", done
    assert [c["name"] for c in fake.calls] == ["generate_video", "get_result", "get_result"]
    [artifact] = center.connections.artifacts()
    assert artifact["sha256"] == hashlib.sha256(VIDEO).hexdigest() and artifact["mime"] == "video/mp4" and artifact["prompt"] == "A cat surfing"
    meta, path = center.connections.artifact(artifact["id"])
    assert path.read_bytes() == VIDEO and meta["name"] == "job-1.mp4"


def test_a_declined_call_does_not_run(world, fake):
    center, *_ = world
    connection = connected(center, fake)
    center.connections.call(connection["id"], "generate_video", {"prompt": "x"})
    center.answer(center.list_approvals()[0]["id"], {"action": "decline"})
    assert center.connections.calls()[0]["state"] == "declined" and fake.calls == []


def test_the_cap_stops_a_runaway_loop(world, fake):
    center, *_ = world
    connection = connected(center, fake, approval="cap", dailyCap=2)
    for _ in range(2):
        assert center.connections.call(connection["id"], "generate_video", {"prompt": "x"})["state"] in ("done", "failed")
    with pytest.raises(CommandError, match="daily cap"):
        center.connections.call(connection["id"], "generate_video", {"prompt": "x"})
    assert len([c for c in fake.calls if c["name"] == "generate_video"]) == 2
    assert center.connections.calls()[0]["state"] == "refused"
    # over_cap: beyond the cap each call waits for the owner, one at a time.
    center.connections.settings(connection["id"], {"approval": "over_cap"})
    assert center.connections.call(connection["id"], "generate_video", {"prompt": "x"})["state"] == "waiting"
    with pytest.raises(CommandError, match="already waiting"):
        center.connections.call(connection["id"], "generate_video", {"prompt": "x"})


def test_an_expired_sign_in_is_refreshed(world, fake):
    center, _, clock = world
    connection = connected(center, fake, approval="cap")
    clock.ms += 2 * 60 * 60_000
    center.connections.call(connection["id"], "generate_video", {"prompt": "x"})
    assert fake.token_requests[-1]["grant_type"] == "refresh_token"


def test_make_and_post_generates_asks_sends_the_file_and_posts_with_the_send_gate(world, fake, monkeypatch):
    center, contract, _ = world
    monkeypatch.setattr(center_module, "MEDIA_CHUNK", 16 * 1024)
    connection = connected(center, fake)
    account = center.create_account({"service": "com.instagram.android", "handle": "@brand", "ownerBasis": "mine"})
    task = center.create_task({"title": "Daily video", "goal": "Post the video on Instagram with the caption: New drop", "deviceId": "phone-a",
                               "accountId": account["id"], "make": {"connectionId": connection["id"], "tool": "generate_video",
                                                                    "arguments": {"prompt": "Our new sneaker"}, "pollTool": "get_result", "then": "post"}})
    center.tick()
    assert center.get_task(task["id"])["status"] == "making" and contract.started == []
    center.answer(center.list_approvals()[0]["id"], {"action": "approve"})
    made = center.get_task(task["id"])
    assert made["status"] == "scheduled" and made["artifact"]["mime"] == "video/mp4"
    center.tick()  # the file goes to the phone first, in chunks
    assert bytes(contract.media[task["id"]]) == VIDEO and contract.chunks == -(-len(VIDEO) // (16 * 1024))
    assert contract.started == []
    center.tick()  # then the phone posts it
    [start] = contract.started
    assert start["publish"] is True and start["taskId"] == task["id"]
    assert "job-1.mp4" in start["goal"] and "owner's OK" in start["goal"]
    center.tick()
    assert center.get_task(task["id"])["status"] == "succeeded"


def test_make_and_keep_needs_no_phone(world, fake):
    center, contract, _ = world
    connection = connected(center, fake, approval="cap")
    task = center.create_task({"title": "Teaser", "make": {"connectionId": connection["id"], "tool": "generate_video",
                                                            "arguments": {"prompt": "Teaser"}, "pollTool": "get_result", "then": "keep"}})
    center.tick()
    done = center.get_task(task["id"])
    assert done["status"] == "succeeded" and done["artifact"]["sha256"] == hashlib.sha256(VIDEO).hexdigest()
    assert contract.started == [] and contract.media == {}


def test_a_task_over_the_cap_fails_instead_of_looping(world, fake):
    center, _, _ = world
    connection = connected(center, fake, approval="cap", dailyCap=0)
    task = center.create_task({"title": "x", "make": {"connectionId": connection["id"], "tool": "generate_video", "arguments": {"prompt": "x"}, "then": "keep"}})
    center.tick()
    failed = center.get_task(task["id"])
    assert failed["status"] == "failed" and "cap" in failed["cause"]


def test_a_make_step_must_use_allowed_tools_and_plain_values(world, fake):
    center, *_ = world
    connection = connected(center, fake)
    for make in ({"connectionId": connection["id"], "tool": "delete_account", "arguments": {}},
                 {"connectionId": connection["id"], "tool": "generate_video", "arguments": {"prompt": {"nested": 1}}},
                 {"connectionId": connection["id"], "tool": "generate_video", "arguments": {"api_key": "x"}}):
        with pytest.raises(CommandError):
            center.create_task({"title": "x", "make": make})


def test_media_chunks_and_answers_are_checked(world):
    ok = validate_android_response("cc.media", {"received": 10, "done": True, "name": "a.mp4", "folder": "Movies/Cyclone"}, {"size": 10})
    assert ok["done"]
    with pytest.raises(DesktopRuntimeError):
        validate_android_response("cc.media", {"received": 11, "done": True, "name": "a.mp4", "folder": "x"}, {"size": 10})


# ------------------------------------------------------------------------ pre-authorised leases

def trusted_vault(center: CommandCenter) -> dict[str, Any]:
    center.vault.init({"kdf": {"name": "PBKDF2", "hash": "SHA-256", "iterations": 600_000}, "salt": b64(16),
                       "wrappedVk": {"iv": b64(12), "ct": b64(48)}, "recoveryWrappedVk": {"iv": b64(12), "ct": b64(48)}})
    account = center.create_account({"service": "com.example.shop", "handle": "@shop", "ownerBasis": "mine"})
    center.vault.put_item({"id": "vi_shoplogin000000000", "accountId": account["id"], "kind": "login", "iv": b64(12), "ct": b64(100),
                           "wrappedKey": {"iv": b64(12), "ct": b64(48)}, "version": 1})
    center.delivery.fetch_key("phone-a")
    center.delivery.trust("phone-a", {"fingerprint": FINGERPRINT})
    return account


def sealed(task_id: str, due: int, clock: Clock, *, expires: int | None = None, suffix: str = "a") -> dict[str, Any]:
    lid = f"ls_{clock.ms:011x}{suffix * 12}"
    bound = {"deviceKey": FINGERPRINT, "expiresAt": expires or due + 30 * 60_000, "leaseId": lid, "place": "package:com.example.shop",
             "slot": "password", "taskId": task_id}
    return {"leaseId": lid, "slot": "password", "enc": base64.b64encode(b"\x04" + os.urandom(64)).decode(), "ct": b64(40),
            "aad": json.dumps(bound, sort_keys=True, separators=(",", ":"))}


def test_a_routine_prepares_its_next_runs_and_uses_them_while_glass_is_closed(world):
    center, contract, clock = world
    account = trusted_vault(center)
    routine = center.create_routine({"title": "Check orders", "goal": "Check the orders", "deviceIds": ["phone-a"], "accountId": account["id"],
                                     "schedule": {"kind": "daily", "time": "10:00", "days": [1, 2, 3, 4, 5, 6, 7]},
                                     "vaultItemId": "vi_shoplogin000000000", "preauth": 3})
    slots = routine["prepared"]
    assert len(slots) == 3 and [s["dueAt"] - slots[0]["dueAt"] for s in slots] == [0, 86_400_000, 2 * 86_400_000]
    pending = [p for p in center.delivery.pending() if p["ahead"]]
    assert [p["taskId"] for p in pending] == [s["taskId"] for s in slots] and pending[0]["deviceKey"]["fingerprint"] == FINGERPRINT
    # Each prepared lease ends within 30 minutes after its own run.
    with pytest.raises(CommandError, match="30 minutes"):
        center.delivery.submit(slots[1]["taskId"], {"envelopes": [sealed(slots[1]["taskId"], slots[1]["dueAt"], clock, expires=slots[1]["dueAt"] + 31 * 60_000)]})
    for i, slot in enumerate(slots):
        center.delivery.submit(slot["taskId"], {"envelopes": [sealed(slot["taskId"], slot["dueAt"], clock, suffix="abc"[i])]})
    assert all(s["ready"] for s in center.get_routine(routine["id"])["prepared"])
    # The owner closes Glass. The first run comes and the phone gets its password with the task that has that id.
    clock.ms = slots[0]["dueAt"] + 1_000
    center.tick()
    [start] = contract.started
    assert start["taskId"] == slots[0]["taskId"] and start["sealed"][0]["leaseId"] == f"ls_{Clock.ms:011x}{'a' * 12}"
    assert center.list_approvals() == []
    # The plan tops itself up to three prepared runs.
    assert len(center.get_routine(routine["id"])["prepared"]) == 3


def test_without_a_prepared_lease_the_run_waits_with_a_login_request(world):
    center, contract, clock = world
    account = trusted_vault(center)
    routine = center.create_routine({"title": "Check orders", "goal": "Check the orders", "deviceIds": ["phone-a"], "accountId": account["id"],
                                     "schedule": {"kind": "every", "minutes": 60}, "vaultItemId": "vi_shoplogin000000000", "preauth": 1})
    clock.ms = routine["nextRunAt"] + 1_000
    center.tick()
    assert contract.started == []
    [login] = center.list_approvals()
    assert login["kind"] == "login" and not login["answerHere"] and "Unlock the vault" in login["text"]
    with pytest.raises(CommandError, match="Unlock the vault"):
        center.answer(login["id"], {"action": "approve"})
    task = center.list_tasks(status="open")[0]
    center.delivery.submit(task["id"], {"envelopes": [sealed(task["id"], clock.ms, clock)]})
    assert center.list_approvals() == []
    center.tick()
    assert contract.started[0]["taskId"] == task["id"]


def test_changing_or_deleting_a_routine_revokes_its_prepared_leases(world):
    center, _, clock = world
    account = trusted_vault(center)
    routine = center.create_routine({"title": "Check", "goal": "Check", "deviceIds": ["phone-a"], "accountId": account["id"],
                                     "schedule": {"kind": "daily", "time": "10:00", "days": [1, 2, 3, 4, 5, 6, 7]},
                                     "vaultItemId": "vi_shoplogin000000000", "preauth": 2})
    slot = routine["prepared"][0]
    center.delivery.submit(slot["taskId"], {"envelopes": [sealed(slot["taskId"], slot["dueAt"], clock)]})
    center.update_routine(routine["id"], {"schedule": {"kind": "daily", "time": "11:00", "days": [1, 2, 3, 4, 5, 6, 7]}})
    assert center.delivery.leases()[0]["state"] == "revoked"
    assert slot["taskId"] not in [s["taskId"] for s in center.get_routine(routine["id"])["prepared"]]
    fresh = center.get_routine(routine["id"])["prepared"][0]
    clock.ms += 1_000
    center.delivery.submit(fresh["taskId"], {"envelopes": [sealed(fresh["taskId"], fresh["dueAt"], clock, suffix="b")]})
    center.delete_routine(routine["id"])
    assert {l["state"] for l in center.delivery.leases()} == {"revoked"}


def test_preparing_ahead_needs_a_vault_login(world):
    center, *_ = world
    with pytest.raises(CommandError):
        center.create_routine({"title": "x", "goal": "x", "deviceIds": ["phone-a"], "schedule": {"kind": "every", "minutes": 60}, "preauth": 2})


# ------------------------------------------------------------------------ HTTP

def test_the_sign_in_callback_is_the_only_route_without_the_bearer(world, fake):
    center, *_ = world
    runtime = type("Runtime", (), {"command": center})()
    app = FastAPI()
    app.include_router(create_command_router(runtime, "secret-token-for-tests"))
    app.include_router(create_oauth_callback_router(runtime))
    client = TestClient(app)
    assert client.get("/v1/cc/connections").status_code == 401
    page = client.get("/v1/cc/connections/oauth/callback", params={"state": "<script>", "code": "x"})
    assert page.status_code == 200 and "Not signed in" in page.text and "<script>" not in page.text
    added = client.post("/v1/cc/connections", json={"name": "Higgsfield", "url": fake.url}, headers={"Authorization": "Bearer secret-token-for-tests"}).json()
    start = client.post(f"/v1/cc/connections/{added['id']}/sign-in", headers={"Authorization": "Bearer secret-token-for-tests"}).json()
    query = dict(urllib.parse.parse_qsl(urllib.parse.urlsplit(start["authorizationUrl"]).query))
    assert query["redirect_uri"] == "http://testserver/v1/cc/connections/oauth/callback"


def test_grants_on_windows_are_sealed_with_the_given_protection(tmp_path):
    store = GrantStore(tmp_path / "g.dpapi", protect=lambda b: bytes(x ^ 0x5A for x in b), unprotect=lambda b: bytes(x ^ 0x5A for x in b))
    store.put("con_1", {"access_token": "tok-visible"})
    assert b"tok-visible" not in (tmp_path / "g.dpapi").read_bytes()
    assert GrantStore(tmp_path / "g.dpapi", protect=lambda b: bytes(x ^ 0x5A for x in b), unprotect=lambda b: bytes(x ^ 0x5A for x in b)).get("con_1")["access_token"] == "tok-visible"
