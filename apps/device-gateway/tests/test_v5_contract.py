from __future__ import annotations

import json
from pathlib import Path

import pytest
from jsonschema import Draft202012Validator

from cyclone_device_gateway.desktop_runtime.models import DesktopRuntimeError
from cyclone_device_gateway.desktop_runtime.v5_contract import V5ContractService


class FakeBridge:
    def __init__(self):
        self.calls = []

    def request(self, op, args, request_id=None):
        self.calls.append((op, dict(args), request_id))
        if op == "atlas.places":
            return {"places": []}
        if op == "atlas.get":
            package = args["placeId"].removeprefix("package:")
            return {
                "place": {
                    "placeId": args["placeId"],
                    "kind": "package",
                    "label": package.rsplit(".", 1)[-1],
                    "packageName": package,
                },
                "persona": args["persona"],
                "mapStatus": "unmapped",
                "screens": [],
                "edges": [],
                "capabilities": [],
                "confidence": 0.0,
                "lastObservedAt": None,
                "lastVerifiedAt": None,
            }
        if op == "secrets.slots":
            return {"placeId": args["placeId"], "persona": args["persona"], "slots": {}}
        if op == "secrets.request":
            return {"state": "needs-secret", "request": dict(args)}
        if op == "atlas.diff":
            return {
                "placeId": args["placeId"],
                "persona": args["persona"],
                "since": args.get("since"),
                "cursor": "c1:aaaaaaaaaaaaaaaaaaaa:0",
                "resyncRequired": False,
                "changes": [],
            }
        if op.startswith("mapping."):
            state = {
                "mapping.start": "running",
                "mapping.pause": "paused",
                "mapping.stop": "stopped",
                "mapping.status": "running",
            }[op]
            return {
                "mappingJobId": "map-phone-0001",
                "placeId": "package:com.example.app",
                "persona": "mapping",
                "state": state,
                "sessionId": args["sessionId"],
                "displayId": args["displayId"],
                "plane": {
                    "kind": "foreground",
                    "sessionId": args["sessionId"],
                    "displayId": args["displayId"],
                    "workspaceId": None,
                    "workspaceGeneration": None,
                    "label": "Foreground",
                },
                "controlRevision": 7,
                "executionGeneration": None,
                "budget": {
                    "maxNewScreens": 40,
                    "maxElapsedMs": 600000,
                    "maxConsecutiveNonProgress": 6,
                    "maxAttemptsPerDoor": 3,
                },
                "currentAtlasNodeId": None,
                "progress": {
                    "newScreens": 0,
                    "verifiedMutations": 0,
                    "consecutiveNonProgress": 0,
                    "attemptedDoors": 0,
                    "remainingDarkRegions": 0,
                },
                "atlasStatus": None,
                "danger": None,
                "boundary": None,
                "startedAtEpochMs": 100,
                "updatedAtEpochMs": 100,
                "failureCode": None,
            }
        raise AssertionError(op)


class FakeSession:
    credential = "paired"

    def __init__(self, bridge):
        self._bridge = bridge

    def bridge(self):
        return self._bridge


class FakeFleet:
    def __init__(self, bridge):
        self.session = FakeSession(bridge)

    def get(self, device_id):
        assert device_id == "phone-1"
        return self.session


@pytest.fixture
def service():
    bridge = FakeBridge()
    return V5ContractService(FakeFleet(bridge)), bridge


def test_forwards_all_run1_ops_to_phone_authority(service):
    svc, bridge = service
    assert svc.atlas_places("phone-1") == {"places": []}
    assert svc.atlas_get("phone-1", "package:com.example.app", "live")["mapStatus"] == "unmapped"
    assert svc.secret_slots("phone-1", "package:com.example.app", "mapping")["slots"] == {}
    ack = svc.secret_request(
        "phone-1",
        place_id="package:com.example.app",
        persona="live",
        slot="password",
        reason="Login required",
    )
    assert ack["state"] == "needs-secret"
    assert [call[0] for call in bridge.calls] == [
        "atlas.places",
        "atlas.get",
        "secrets.slots",
        "secrets.request",
    ]


def test_android_response_is_rejected_before_secret_can_enter_pc_context(service):
    svc, bridge = service

    def secret_result(op, args, request_id=None):
        bridge.calls.append((op, dict(args), request_id))
        if op == "atlas.get":
            return {
                "place": {
                    "placeId": args["placeId"],
                    "kind": "package",
                    "label": "Example",
                    "packageName": "com.example.app",
                },
                "persona": args["persona"],
                "mapStatus": "mapped",
                "screens": [{"screenId": "login", "factSlots": [{"name": "password"}]}],
                "edges": [],
                "capabilities": [],
                "confidence": 1.0,
                "lastObservedAt": None,
                "lastVerifiedAt": None,
            }
        raise AssertionError(op)

    bridge.request = secret_result
    with pytest.raises(DesktopRuntimeError):
        svc.atlas_get("phone-1", "package:com.example.app", "live")


def test_secret_slot_presence_accepts_secret_slot_names_only_as_booleans(service):
    svc, bridge = service

    def slots_result(op, args, request_id=None):
        bridge.calls.append((op, dict(args), request_id))
        if op == "secrets.slots":
            return {
                "placeId": args["placeId"],
                "persona": args["persona"],
                "slots": {"password": True, "otp": False},
            }
        raise AssertionError(op)

    bridge.request = slots_result
    result = svc.secret_slots("phone-1", "package:com.example.app", "live")
    assert result["slots"] == {"password": True, "otp": False}

    def bad_slots_result(op, args, request_id=None):
        bridge.calls.append((op, dict(args), request_id))
        return {
            "placeId": args["placeId"],
            "persona": args["persona"],
            "slots": {"password": 1},
        }

    bridge.request = bad_slots_result
    with pytest.raises(DesktopRuntimeError):
        svc.secret_slots("phone-1", "package:com.example.app", "live")


def test_secret_payload_is_rejected_not_stripped_or_forwarded(service):
    svc, bridge = service
    with pytest.raises(DesktopRuntimeError):
        svc.forward(
            "phone-1",
            "secrets.request",
            {
                "placeId": "package:com.example.app",
                "persona": "live",
                "slot": "password",
                "reason": "Login required",
                "password": True,
            },
        )
    assert bridge.calls == []


def _schema(name):
    root = Path(__file__).resolve().parents[3]
    return json.loads((root / "protocol" / name).read_text(encoding="utf-8"))


def test_valid_empty_atlas_document_validates():
    document = {
        "place": {
            "placeId": "package:com.example.app",
            "kind": "package",
            "label": "Example",
            "packageName": "com.example.app",
        },
        "persona": "live",
        "mapStatus": "unmapped",
        "screens": [],
        "edges": [],
        "capabilities": [],
        "confidence": 0.0,
        "lastObservedAt": None,
        "lastVerifiedAt": None,
    }
    Draft202012Validator(_schema("cyclone-atlas-v1.schema.json")).validate(document)


def test_partial_atlas_document_validates():
    document = {
        "place": {
            "placeId": "package:com.example.app",
            "kind": "package",
            "label": "Example",
            "packageName": "com.example.app",
        },
        "persona": "live",
        "mapStatus": "partial",
        "screens": [],
        "edges": [],
        "capabilities": [],
        "confidence": 0.5,
        "lastObservedAt": None,
        "lastVerifiedAt": None,
    }
    Draft202012Validator(_schema("cyclone-atlas-v1.schema.json")).validate(document)


def test_valid_slot_presence_validates_and_value_field_is_rejected():
    validator = Draft202012Validator(_schema("cyclone-secrets-v1.schema.json"))
    validator.validate({
        "placeId": "package:com.example.app",
        "persona": "live",
        "slots": {"username": True, "password": True, "otp": False},
    })
    for forbidden_field in ("password", "value"):
        with pytest.raises(Exception):
            validator.validate({
                "placeId": "package:com.example.app",
                "persona": "live",
                "slot": "password",
                "reason": "Login required",
                forbidden_field: True,
            })


def test_run2_atlas_diff_is_android_forwarded_and_cursor_is_phone_owned(service):
    svc, bridge = service
    result = svc.atlas_diff(
        "phone-1",
        "package:com.example.app",
        "mapping",
        None,
    )
    assert result["cursor"] == "c1:aaaaaaaaaaaaaaaaaaaa:0"
    assert result["changes"] == []
    assert bridge.calls[-1][0] == "atlas.diff"
    assert bridge.calls[-1][1] == {
        "placeId": "package:com.example.app",
        "persona": "mapping",
        "since": None,
    }


def test_run2_mapping_state_is_forwarded_from_android_not_created_on_pc(service):
    svc, bridge = service
    result = svc.forward(
        "phone-1",
        "mapping.start",
        {
            "placeId": "package:com.example.app",
            "persona": "mapping",
            "sessionId": "default-foreground",
            "displayId": 0,
        },
    )
    assert result["mappingJobId"] == "map-phone-0001"
    assert result["state"] == "running"
    assert result["controlRevision"] == 7
    assert bridge.calls[-1][0] == "mapping.start"


def test_run2_mapping_requires_explicit_session_before_forwarding(service):
    svc, bridge = service
    with pytest.raises(DesktopRuntimeError) as caught:
        svc.forward(
            "phone-1",
            "mapping.start",
            {
                "placeId": "package:com.example.app",
                "persona": "mapping",
                "displayId": 0,
            },
        )
    assert str(caught.value.code) == "SESSION_REQUIRED"
    assert bridge.calls == []


def test_run2_named_display_zero_is_rejected_before_forwarding(service):
    svc, bridge = service
    with pytest.raises(DesktopRuntimeError) as caught:
        svc.forward(
            "phone-1",
            "mapping.start",
            {
                "placeId": "package:com.example.app",
                "persona": "mapping",
                "sessionId": "workspace-1",
                "displayId": 0,
                "executionGeneration": 1,
            },
        )
    assert str(caught.value.code) == "SESSION_DISPLAY_MISMATCH"
    assert bridge.calls == []


def test_run2_mapping_secret_bearing_extra_is_rejected_not_stripped(service):
    svc, bridge = service
    with pytest.raises(DesktopRuntimeError):
        svc.forward(
            "phone-1",
            "mapping.start",
            {
                "placeId": "package:com.example.app",
                "persona": "mapping",
                "sessionId": "default-foreground",
                "displayId": 0,
                "password": True,
            },
        )
    assert bridge.calls == []


def test_run2_diff_rejects_non_structural_android_payload(service):
    svc, bridge = service

    def bad_diff(op, args, request_id=None):
        bridge.calls.append((op, dict(args), request_id))
        return {
            "placeId": args["placeId"],
            "persona": args["persona"],
            "since": args.get("since"),
            "cursor": "c1:aaaaaaaaaaaaaaaaaaaa:1",
            "resyncRequired": False,
            "changes": [{
                "cursor": "c1:aaaaaaaaaaaaaaaaaaaa:1",
                "entity": "screen",
                "change": "upsert",
                "id": "screen:home",
                "label": "unexpected",
            }],
        }

    bridge.request = bad_diff
    with pytest.raises(DesktopRuntimeError):
        svc.atlas_diff("phone-1", "package:com.example.app", "mapping", None)


GLASS_BUDGET = {
    "maxNewScreens": 12,
    "maxElapsedMs": 180000,
    "maxConsecutiveNonProgress": 6,
    "maxAttemptsPerDoor": 2,
}


def test_alpha3_glass_start_resume_pause_bodies_are_forwarded_unchanged(service):
    """The exact bodies Glass Maps sends (atlasClient.mappingCall) must pass the PC contract."""
    svc, bridge = service
    plane = {"sessionId": "default-foreground", "displayId": 0}

    svc.forward("phone-1", "mapping.start", {
        "placeId": "package:com.example.app", "persona": "mapping", "budget": dict(GLASS_BUDGET), **plane,
    })
    svc.forward("phone-1", "mapping.start", {"resumeJobId": "map-phone-0001", **plane})
    svc.forward("phone-1", "mapping.pause", {"mappingJobId": "map-phone-0001", **plane})
    svc.forward("phone-1", "mapping.stop", {"mappingJobId": "map-phone-0001", **plane})
    svc.forward("phone-1", "mapping.status", dict(plane))

    ops = [call[0] for call in bridge.calls]
    assert ops == ["mapping.start", "mapping.start", "mapping.pause", "mapping.stop", "mapping.status"]
    assert bridge.calls[0][1]["budget"] == GLASS_BUDGET
    assert bridge.calls[1][1] == {"resumeJobId": "map-phone-0001", **plane}


def test_alpha3_glass_resume_cannot_smuggle_a_new_place(service):
    svc, bridge = service
    with pytest.raises(DesktopRuntimeError):
        svc.forward("phone-1", "mapping.start", {
            "resumeJobId": "map-phone-0001", "placeId": "package:com.other.app",
            "sessionId": "default-foreground", "displayId": 0,
        })
    assert bridge.calls == []


ASK_STATUS = {
    "taskId": "task-1",
    "state": "needs-secret",
    "title": "Gmail → Facebook",
    "app": "Facebook",
    "currentMilestone": "Finding the dm of Louella",
    "milestones": [
        {"label": "Finding the signed-in email address", "state": "done"},
        {"label": "Finding the dm of Louella", "state": "action-needed"},
    ],
    "supportingCopy": "Secure input is required to continue.",
    "outcomeCopy": None,
    "sessionId": "default-foreground",
    "displayId": 0,
}


class AskBridge(FakeBridge):
    def __init__(self, status=None):
        super().__init__()
        self.status = dict(status or ASK_STATUS)

    def request(self, op, args, request_id=None):
        if op == "ask.start":
            self.calls.append((op, dict(args), request_id))
            return {"accepted": True, "sessionId": "default-foreground", "displayId": 0}
        if op == "ask.status":
            self.calls.append((op, dict(args), request_id))
            return dict(self.status)
        return super().request(op, args, request_id)


def _ask_service(status=None):
    bridge = AskBridge(status)
    return V5ContractService(FakeFleet(bridge)), bridge


FOREGROUND = {"sessionId": "default-foreground", "displayId": 0}


def test_alpha4_ask_start_forwards_goal_text_on_the_foreground_plane():
    svc, bridge = _ask_service()
    goal = "open Gmail, check my current logged in email, then go to facebook and find the dm of Louella"
    result = svc.forward("phone-1", "ask.start", {"goal": goal, **FOREGROUND})
    assert result["accepted"] is True
    assert bridge.calls == [("ask.start", {"goal": goal, **FOREGROUND}, bridge.calls[0][2])]


def test_alpha4_ask_status_is_the_phone_snapshot():
    svc, _ = _ask_service()
    status = svc.forward("phone-1", "ask.status", dict(FOREGROUND))
    assert status["state"] == "needs-secret"
    assert status["milestones"][1]["state"] == "action-needed"


@pytest.mark.parametrize("body, code", [
    ({"goal": "open clock", "sessionId": "", "displayId": 0}, "SESSION_REQUIRED"),
    ({"goal": "open clock", "sessionId": "vd-mail", "displayId": 3}, "SESSION_DISPLAY_MISMATCH"),
    ({"goal": "", **FOREGROUND}, "INVALID_REQUEST"),
    ({"goal": "x" * 2001, **FOREGROUND}, "INVALID_REQUEST"),
    ({"goal": "open clock", "placeId": "package:com.x.y", **FOREGROUND}, "INVALID_REQUEST"),
])
def test_alpha4_ask_start_rejects_bad_requests_before_forwarding(body, code):
    svc, bridge = _ask_service()
    with pytest.raises(DesktopRuntimeError) as caught:
        svc.forward("phone-1", "ask.start", body)
    assert str(caught.value.code) == code
    assert bridge.calls == []


def test_alpha4_secrets_never_travel_in_a_goal():
    svc, bridge = _ask_service()
    with pytest.raises(DesktopRuntimeError):
        svc.forward("phone-1", "ask.start", {"goal": "log in with password: hunter2", **FOREGROUND})
    with pytest.raises(DesktopRuntimeError):
        svc.forward("phone-1", "ask.start", {"goal": "open clock", "password": "hunter2", **FOREGROUND})
    assert bridge.calls == []


REQUEST = {"requestId": "req-0123abcd-456", "state": "done", "lane": "instant", "decider": "phone",
           "why": "the phone model knows this one", "decideMs": 3, "startedAtMs": 1, "routedAtMs": 5, "finishedAtMs": 900,
           "say": "Volume up."}


def test_alpha91_ask_defaults_to_the_main_screen_and_reads_one_request_back():
    svc, bridge = _ask_service({**ASK_STATUS, "request": REQUEST})
    svc.forward("phone-1", "ask.start", {"goal": "make it louder"})
    assert bridge.calls[0][1] == {"goal": "make it louder", **FOREGROUND}
    status = svc.forward("phone-1", "ask.status", {"requestId": "req-0123abcd-456"})
    assert status["request"]["lane"] == "instant" and status["request"]["decider"] == "phone"
    with pytest.raises(DesktopRuntimeError) as caught:
        svc.forward("phone-1", "ask.status", {"requestId": "../etc"})
    assert str(caught.value.code) == "INVALID_REQUEST"
    for bad in ({**REQUEST, "lane": "teleport"}, {**REQUEST, "extra": 1}, {**REQUEST, "requestId": "x"}):
        broken, _ = _ask_service({**ASK_STATUS, "request": bad})
        with pytest.raises(DesktopRuntimeError) as caught:
            broken.forward("phone-1", "ask.status", {})
        assert str(caught.value.code) == "PROTOCOL_MISMATCH"


def test_alpha4_malformed_phone_ask_status_is_rejected():
    svc, _ = _ask_service({**ASK_STATUS, "state": "walking"})
    with pytest.raises(DesktopRuntimeError) as caught:
        svc.forward("phone-1", "ask.status", dict(FOREGROUND))
    assert str(caught.value.code) == "PROTOCOL_MISMATCH"


GMAIL_APP = {
    "placeId": "package:com.google.android.gm",
    "kind": "package",
    "label": "Gmail",
    "packageName": "com.google.android.gm",
    "origin": None,
    "installed": True,
    "installedVersion": {"versionName": "2026.09.01", "versionCode": 900},
    "mapStatus": "mapped",
    "rooms": 6,
    "doors": 11,
    "lastVerifiedAt": 1000,
    "needsRemap": True,
    "personas": [{"persona": "mapping", "mapStatus": "mapped", "rooms": 6, "doors": 11, "lastVerifiedAt": 1000}],
    "mappedVersions": [{"versionName": "2026.08.01", "versionCode": 880, "doors": 9}],
}


class AppsBridge(FakeBridge):
    def __init__(self, result):
        super().__init__()
        self.result = result

    def request(self, op, args, request_id=None):
        if op == "apps.list":
            self.calls.append((op, dict(args), request_id))
            return self.result
        return super().request(op, args, request_id)


def test_glass_alpha1_apps_list_is_the_phone_catalog():
    bridge = AppsBridge({"apps": [GMAIL_APP], "truncated": False})
    svc = V5ContractService(FakeFleet(bridge))
    assert svc.forward("phone-1", "apps.list", {}) == {"apps": [GMAIL_APP], "truncated": False}
    assert bridge.calls[0][:2] == ("apps.list", {})
    with pytest.raises(DesktopRuntimeError) as error:
        svc.forward("phone-1", "apps.list", {"all": True})
    assert error.value.code == "INVALID_REQUEST"
    assert len(bridge.calls) == 1


@pytest.mark.parametrize(
    "bad",
    [
        {"apps": [GMAIL_APP]},
        {"apps": [{**GMAIL_APP, "screens": []}], "truncated": False},
        {"apps": [{**GMAIL_APP, "placeId": "file:///sdcard"}], "truncated": False},
        {"apps": [{**GMAIL_APP, "mapStatus": "walking"}], "truncated": False},
        {"apps": [{**GMAIL_APP, "installedVersion": {"versionName": "1", "versionCode": "x"}}], "truncated": False},
        {"apps": [{**GMAIL_APP, "mappedVersions": [{"versionName": "1", "versionCode": 1, "selector": "x"}]}], "truncated": False},
        {"apps": [{**GMAIL_APP, "personas": [{"persona": "you", "mapStatus": "mapped", "rooms": 1, "doors": 1, "lastVerifiedAt": None}]}], "truncated": False},
        {"apps": [{**GMAIL_APP, "label": "x" * 81}], "truncated": False},
    ],
)
def test_glass_alpha1_malformed_phone_catalog_is_rejected(bad):
    svc = V5ContractService(FakeFleet(AppsBridge(bad)))
    with pytest.raises(DesktopRuntimeError) as error:
        svc.forward("phone-1", "apps.list", {})
    assert error.value.code == "PROTOCOL_MISMATCH"


def test_glass_alpha1_apps_list_is_an_allowed_bridge_op():
    from cyclone_device_gateway.cyclone_bridge.protocol import ALLOWED_OPS, UNAUTHENTICATED_OPS

    assert "apps.list" in ALLOWED_OPS
    assert "apps.list" not in UNAUTHENTICATED_OPS


RUN_SUMMARY = {
    "runId": "ai-run-1",
    "goal": "find the dm of Louella",
    "model": "model-x",
    "status": "failed",
    "startedAt": 1000,
    "endedAt": 5000,
    "durationMs": 4000,
    "decisions": 3,
    "stepCount": 2,
    "metrics": {"toolCalls": 2, "toolFailures": 0, "verificationFailures": 0, "recoveries": 0, "visionChecks": 0, "verifiedActions": 1},
    "cause": {"kind": "needs-secret", "stepIndex": 1, "headline": "Stopped at a login wall", "detail": "Facebook wants a password", "fix": "Set the password slot."},
}
RUN_STEP = {
    "index": 1,
    "startedAt": 2000,
    "endedAt": 4000,
    "title": "Opening the selected control: Messages",
    "action": "click:Messages",
    "pageId": "bbbbbbbbbbbbbbbb",
    "outcome": "failed",
    "verification": None,
    "recovery": None,
    "vision": False,
    "eventsTruncated": False,
    "events": [{"at": 2000, "kind": "GATE_SUSPEND", "text": "Facebook wants a password", "code": "gate.need_secret", "ok": None, "detail": None}],
}
RUN_DETAIL = {**RUN_SUMMARY, "result": "Waiting for a password", "stepsTruncated": False, "steps": [RUN_STEP]}

NAV_CLAUSE = {"id": "clause-1", "text": "check which Gmail I am logged in with", "place": "package:com.google.android.gm", "status": "verified", "proof": "Live signed-in email: j***@gmail.com"}
NAV_FACT = {"key": "signed-in-email", "value": "j***@gmail.com", "sourcePlace": "package:com.google.android.gm", "sourceRoom": "screen:account:0123456789abcdef", "persona": "live", "readAtMs": 1000}


def test_navigation_fields_are_optional_and_accept_masked_live_facts():
    detail = {**RUN_DETAIL, "clauses": [NAV_CLAUSE], "ledger": [NAV_FACT], "steps": [{**RUN_STEP, "expectedRoomId": "screen:account:0123456789abcdef"}]}
    svc = V5ContractService(FakeFleet(RunsBridge(detail=detail)))
    assert svc.runs_get("phone-1", "ai-run-1") == detail


@pytest.mark.parametrize("fields", [
    {"clauses": [{**NAV_CLAUSE, "status": "made-up"}]},
    {"clauses": [{**NAV_CLAUSE, "place": "chrome:https://facebook.com/messages?token=hidden"}]},
    {"clauses": [NAV_CLAUSE] * 17},
    {"clauses": [NAV_CLAUSE, NAV_CLAUSE]},
    {"ledger": [{**NAV_FACT, "value": "jane@gmail.com"}]},
    {"ledger": [{**NAV_FACT, "persona": "mapping"}]},
    {"ledger": [{**NAV_FACT, "readAtMs": True}]},
    {"ledger": [{**NAV_FACT, "sourceRoom": "raw text"}]},
    {"ledger": [{**NAV_FACT, "extra": "hidden"}]},
    {"ledger": [NAV_FACT] * 5},
    {"steps": [{**RUN_STEP, "expectedRoomId": "raw screen text"}]},
])
def test_navigation_rejects_malformed_or_unmasked_phone_fields(fields):
    svc = V5ContractService(FakeFleet(RunsBridge(detail={**RUN_DETAIL, **fields})))
    with pytest.raises(DesktopRuntimeError):
        svc.runs_get("phone-1", "ai-run-1")


class RunsBridge(FakeBridge):
    def __init__(self, listing=None, detail=None, error=None):
        super().__init__()
        self.listing = listing if listing is not None else {"runs": [RUN_SUMMARY]}
        self.detail = detail if detail is not None else RUN_DETAIL
        self.error = error

    def request(self, op, args, request_id=None):
        if op in {"runs.list", "runs.get"}:
            self.calls.append((op, dict(args), request_id))
            if self.error:
                raise self.error
            return self.listing if op == "runs.list" else self.detail
        return super().request(op, args, request_id)


def test_glass_alpha2_runs_are_the_phone_trace():
    bridge = RunsBridge()
    svc = V5ContractService(FakeFleet(bridge))
    assert svc.runs_list("phone-1", 20, "failed") == {"runs": [RUN_SUMMARY]}
    assert bridge.calls[0][:2] == ("runs.list", {"limit": 20, "filter": "failed"})
    assert svc.forward("phone-1", "runs.get", {"runId": "ai-run-1"}) == RUN_DETAIL
    assert bridge.calls[1][:2] == ("runs.get", {"runId": "ai-run-1"})


@pytest.mark.parametrize(
    "call",
    [
        lambda s: s.runs_list("phone-1", 0, "all"),
        lambda s: s.runs_list("phone-1", 20, "everything"),
        lambda s: s.runs_get("phone-1", "../../etc/passwd"),
        lambda s: s.forward("phone-1", "runs.get", {"runId": "ai-run-1", "extra": 1}),
        lambda s: s.forward("phone-1", "runs.list", {"sql": "x"}),
    ],
)
def test_glass_alpha2_bad_run_requests_never_reach_the_phone(call):
    bridge = RunsBridge()
    with pytest.raises(DesktopRuntimeError) as error:
        call(V5ContractService(FakeFleet(bridge)))
    assert error.value.code == "INVALID_REQUEST"
    assert bridge.calls == []


@pytest.mark.parametrize(
    "listing, detail",
    [
        ({"runs": [{**RUN_SUMMARY, "status": "exploded"}]}, None),
        ({"runs": [{**RUN_SUMMARY, "screenshot": "base64"}]}, None),
        ({"runs": [{**RUN_SUMMARY, "cause": {**RUN_SUMMARY["cause"], "kind": "Bad Kind!"}}]}, None),
        (None, {**RUN_DETAIL, "runId": "ai-other"}),
        (None, {**RUN_DETAIL, "steps": [{**RUN_STEP, "outcome": "great"}]}),
        (None, {**RUN_DETAIL, "steps": [{**RUN_STEP, "events": [{**RUN_STEP["events"][0], "text": "x" * 361}]}]}),
        (None, {**RUN_DETAIL, "steps": [{**RUN_STEP, "events": [{**RUN_STEP["events"][0], "text": "password: hunter2"}]}]}),
    ],
)
def test_glass_alpha2_malformed_phone_runs_are_rejected(listing, detail):
    svc = V5ContractService(FakeFleet(RunsBridge(listing=listing, detail=detail)))
    with pytest.raises(DesktopRuntimeError) as error:
        if listing is not None:
            svc.runs_list("phone-1")
        else:
            svc.runs_get("phone-1", "ai-run-1")
    assert error.value.code in {"PROTOCOL_MISMATCH", "INVALID_REQUEST"}


def test_glass_alpha2_run_not_found_is_named():
    from cyclone_device_gateway.cyclone_bridge.client import BridgeOperationError

    svc = V5ContractService(FakeFleet(RunsBridge(error=BridgeOperationError("RUN_NOT_FOUND"))))
    with pytest.raises(DesktopRuntimeError) as error:
        svc.runs_get("phone-1", "ai-missing")
    assert error.value.code == "RUN_NOT_FOUND"


RUN_V2_SUMMARY = {
    **RUN_SUMMARY,
    "mapSteps": 1,
    "modelSteps": 1,
    "places": [{"placeId": "package:com.facebook.katana", "appVersion": "512.0.0", "route": ["screen:home:0123456789abcdef", "screen:list:aaaaaaaaaaaaaaaa"]}],
}
RUN_V2_STEP = {**RUN_STEP, "roomId": "screen:home:0123456789abcdef", "roomAfter": None, "placeId": "package:com.facebook.katana", "appVersion": "512.0.0", "decisionSource": "map"}


def test_glass_alpha4_run_record_v2_passes_and_v1_phones_still_work():
    detail = {**RUN_V2_SUMMARY, "result": "x", "stepsTruncated": False, "steps": [RUN_V2_STEP]}
    svc = V5ContractService(FakeFleet(RunsBridge(listing={"runs": [RUN_V2_SUMMARY, RUN_SUMMARY]}, detail=detail)))
    assert svc.runs_list("phone-1")["runs"][0]["mapSteps"] == 1
    assert svc.runs_get("phone-1", "ai-run-1")["steps"][0]["decisionSource"] == "map"


@pytest.mark.parametrize(
    "listing, detail",
    [
        ({"runs": [{**RUN_SUMMARY, "mapSteps": 1}]}, None),  # half a v2 record
        ({"runs": [{**RUN_V2_SUMMARY, "places": [{**RUN_V2_SUMMARY["places"][0], "route": ["Inbox of alice@example.com"]}]}]}, None),
        ({"runs": [{**RUN_V2_SUMMARY, "places": [{**RUN_V2_SUMMARY["places"][0], "placeId": "https://evil"}]}]}, None),
        (None, {**RUN_V2_SUMMARY, "result": "x", "stepsTruncated": False, "steps": [{**RUN_V2_STEP, "decisionSource": "vibes"}]}),
        (None, {**RUN_V2_SUMMARY, "result": "x", "stepsTruncated": False, "steps": [{**RUN_V2_STEP, "roomId": "password: x"}]}),
    ],
)
def test_glass_alpha4_malformed_run_record_v2_is_rejected(listing, detail):
    svc = V5ContractService(FakeFleet(RunsBridge(listing=listing, detail=detail)))
    with pytest.raises(DesktopRuntimeError) as error:
        if listing is not None:
            svc.runs_list("phone-1")
        else:
            svc.runs_get("phone-1", "ai-run-1")
    assert error.value.code in {"PROTOCOL_MISMATCH", "INVALID_REQUEST"}


GM = "package:com.google.android.gm"
VERSIONS = {
    "placeId": GM,
    "installedVersion": {"versionName": "2026.09.14", "versionCode": 120},
    "needsRemap": False,
    "versions": [{"versionName": "2026.09.14", "versionCode": 120, "installed": True, "doors": 3, "rooms": 4, "failingDoors": 0, "lastSeenAt": 5}],
    "staleDoorCount": 1,
    "staleDoors": [{"edgeId": "edge:abc", "fromScreenId": "screen:home:aaaaaaaaaaaaaaaa", "toScreenId": "screen:menu:bbbbbbbbbbbbbbbb", "versionName": "2026.08.01", "versionCode": 100}],
}
SCENARIOS = {
    "placeId": GM,
    "persona": "mapping",
    "entryScreenId": "screen:home:aaaaaaaaaaaaaaaa",
    "scenarios": [{
        "scenarioId": "sc_0123456789abcdef01",
        "title": "Reach Settings",
        "startScreenId": "screen:home:aaaaaaaaaaaaaaaa",
        "endScreenId": "screen:settings:cccccccccccccccc",
        "route": ["screen:home:aaaaaaaaaaaaaaaa", "screen:settings:cccccccccccccccc"],
        "steps": 1,
        "danger": False,
        "health": "passing",
        "lastVerifiedAt": 5,
        "appVersion": "2026.09.14",
        "runs": [{"runId": "ai-run-1", "status": "completed", "startedAt": 4}],
    }],
}


class KnowledgeBridge(FakeBridge):
    def __init__(self, versions=None, scenarios=None):
        super().__init__()
        self.versions = versions if versions is not None else VERSIONS
        self.scenarios = scenarios if scenarios is not None else SCENARIOS

    def request(self, op, args, request_id=None):
        if op in {"atlas.versions", "scenarios.list"}:
            self.calls.append((op, dict(args), request_id))
            return self.versions if op == "atlas.versions" else self.scenarios
        return super().request(op, args, request_id)


def test_glass_alpha5_versions_and_scenarios_come_from_the_phone():
    bridge = KnowledgeBridge()
    svc = V5ContractService(FakeFleet(bridge))
    assert svc.atlas_versions("phone-1", GM) == VERSIONS
    assert svc.scenarios_list("phone-1", GM) == SCENARIOS
    assert svc.forward("phone-1", "scenarios.list", {"placeId": GM, "persona": "live"}) == SCENARIOS
    assert [call[:2] for call in bridge.calls] == [
        ("atlas.versions", {"placeId": GM}),
        ("scenarios.list", {"placeId": GM, "persona": "mapping"}),
        ("scenarios.list", {"placeId": GM, "persona": "live"}),
    ]


def test_scenarios_may_say_they_are_sign_in_scenarios():
    signed = {**SCENARIOS, "scenarios": [
        {**SCENARIOS["scenarios"][0], "kind": "sign-in", "title": "Sign in"},
        {**SCENARIOS["scenarios"][0], "kind": "signed-in", "title": "Already signed in", "scenarioId": "sc_0123456789abcdef02"},
    ]}
    svc = V5ContractService(FakeFleet(KnowledgeBridge(scenarios=signed)))
    assert svc.scenarios_list("phone-1", GM) == signed


@pytest.mark.parametrize("call", [
    lambda s: s.atlas_versions("phone-1", "chrome:https://example.com"),
    lambda s: s.scenarios_list("phone-1", GM, "boss"),
    lambda s: s.forward("phone-1", "atlas.versions", {"placeId": GM, "extra": 1}),
])
def test_glass_alpha5_bad_knowledge_requests_never_reach_the_phone(call):
    bridge = KnowledgeBridge()
    with pytest.raises(DesktopRuntimeError) as error:
        call(V5ContractService(FakeFleet(bridge)))
    assert error.value.code == "INVALID_REQUEST"
    assert bridge.calls == []


@pytest.mark.parametrize("versions, scenarios", [
    ({**VERSIONS, "labels": ["Inbox"]}, None),
    ({**VERSIONS, "staleDoors": [{**VERSIONS["staleDoors"][0], "fromScreenId": "Inbox of alice"}]}, None),
    (None, {**SCENARIOS, "scenarios": [{**SCENARIOS["scenarios"][0], "health": "great"}]}),
    (None, {**SCENARIOS, "scenarios": [{**SCENARIOS["scenarios"][0], "steps": 5}]}),
    (None, {**SCENARIOS, "scenarios": [{**SCENARIOS["scenarios"][0], "title": "password: hunter2"}]}),
    (None, {**SCENARIOS, "scenarios": [{**SCENARIOS["scenarios"][0], "kind": "pay"}]}),
])
def test_glass_alpha5_malformed_knowledge_is_rejected(versions, scenarios):
    svc = V5ContractService(FakeFleet(KnowledgeBridge(versions=versions, scenarios=scenarios)))
    with pytest.raises(DesktopRuntimeError) as error:
        if versions is not None:
            svc.atlas_versions("phone-1", GM)
        else:
            svc.scenarios_list("phone-1", GM)
    assert error.value.code in {"PROTOCOL_MISMATCH", "INVALID_REQUEST"}


KNOWLEDGE = {
    "vault": {"slotCount": 1, "setCount": 1, "slots": [{"placeId": "package:com.facebook.katana", "persona": "live", "slot": "password", "set": True, "updatedAt": 5}]},
    "skills": [{"id": "skill-1", "name": "Morning brief", "steps": 3, "enabled": True, "version": 1}],
    "automations": [{"id": "auto-1", "name": "Plug in", "trigger": "manual", "steps": 1, "enabled": False}],
    "atlas": {"places": 2, "rooms": 10, "doors": 12},
}


class SummaryBridge(FakeBridge):
    def __init__(self, summary):
        super().__init__()
        self.summary = summary

    def request(self, op, args, request_id=None):
        if op == "knowledge.get":
            self.calls.append((op, dict(args), request_id))
            return self.summary
        return super().request(op, args, request_id)


def test_glass_knowledge_summary_is_presence_and_names_only():
    svc = V5ContractService(FakeFleet(SummaryBridge(KNOWLEDGE)))
    assert svc.knowledge_summary("phone-1") == KNOWLEDGE
    with pytest.raises(DesktopRuntimeError):
        svc.forward("phone-1", "knowledge.get", {"all": True})


GUARDED = {"placeId": "package:com.example.shop", "label": "Shop", "persona": "mapping", "danger": "payment", "doors": 2, "rooms": 1}


def test_glass_knowledge_summary_may_carry_the_never_pay_list():
    summary = {**KNOWLEDGE, "guarded": [GUARDED, {**GUARDED, "danger": "delete-account", "roomIds": ["screen:settings:cccccccccccccccc"]}]}
    assert V5ContractService(FakeFleet(SummaryBridge(summary))).knowledge_summary("phone-1") == summary


@pytest.mark.parametrize("summary", [
    {**KNOWLEDGE, "vault": {**KNOWLEDGE["vault"], "slots": [{**KNOWLEDGE["vault"]["slots"][0], "value": "hunter2"}]}},
    {**KNOWLEDGE, "vault": {**KNOWLEDGE["vault"], "slots": [{**KNOWLEDGE["vault"]["slots"][0], "set": "yes"}]}},
    {**KNOWLEDGE, "skills": [{**KNOWLEDGE["skills"][0], "name": "x" * 81}]},
    {**KNOWLEDGE, "people": ["Louella"]},
    {**KNOWLEDGE, "guarded": [{**GUARDED, "danger": "authentication"}]},
    {**KNOWLEDGE, "guarded": [{**GUARDED, "selector": "Buy now"}]},
    {**KNOWLEDGE, "guarded": [{**GUARDED, "doors": -1}]},
    {**KNOWLEDGE, "guarded": [{**GUARDED, "roomIds": ["Buy now button"]}]},
])
def test_glass_knowledge_summary_rejects_values_and_extras(summary):
    svc = V5ContractService(FakeFleet(SummaryBridge(summary)))
    with pytest.raises(DesktopRuntimeError) as error:
        svc.knowledge_summary("phone-1")
    assert error.value.code == "PROTOCOL_MISMATCH"


class MarkBridge(RunsBridge):
    def request(self, op, args, request_id=None):
        if op == "runs.mark":
            self.calls.append((op, dict(args), request_id))
            return {"runId": args["runId"], "expected": args["expected"]}
        return super().request(op, args, request_id)


def test_glass_runs_can_be_marked_expected_on_the_phone():
    bridge = MarkBridge(listing={"runs": [{**RUN_SUMMARY, "expected": True}]})
    svc = V5ContractService(FakeFleet(bridge))
    assert svc.runs_mark("phone-1", "ai-run-1", True) == {"runId": "ai-run-1", "expected": True}
    assert svc.runs_list("phone-1")["runs"][0]["expected"] is True
    for bad in (lambda: svc.runs_mark("phone-1", "../x", True), lambda: svc.runs_mark("phone-1", "ai-run-1", "yes")):
        with pytest.raises(DesktopRuntimeError):
            bad()
    with pytest.raises(DesktopRuntimeError):
        V5ContractService(FakeFleet(RunsBridge(listing={"runs": [{**RUN_SUMMARY, "expected": "yes"}]}))).runs_list("phone-1")


class HereBridge(FakeBridge):
    def __init__(self, here):
        super().__init__()
        self.here = here

    def request(self, op, args, request_id=None):
        if op == "atlas.here":
            return self.here
        return super().request(op, args, request_id)


def test_glass_you_are_here_is_structural_only():
    here = {"placeId": "package:com.google.android.gm", "roomId": "screen:list:aaaaaaaaaaaaaaaa", "appVersion": "2026.09.14", "observedAt": 5}
    assert V5ContractService(FakeFleet(HereBridge(here))).atlas_here("phone-1") == here
    empty = {"placeId": None, "roomId": None, "appVersion": None, "observedAt": None}
    assert V5ContractService(FakeFleet(HereBridge(empty))).atlas_here("phone-1") == empty
    for bad in ({**here, "roomId": "Inbox of alice"}, {**here, "label": "Inbox"}):
        with pytest.raises(DesktopRuntimeError):
            V5ContractService(FakeFleet(HereBridge(bad))).atlas_here("phone-1")


def test_apps_list_may_carry_scenario_health_counts():
    counts = {"passing": 3, "warning": 1, "critical": 0, "untested": 2}
    svc = V5ContractService(FakeFleet(AppsBridge({"apps": [{**GMAIL_APP, "scenarios": counts}], "truncated": False})))
    assert svc.forward("phone-1", "apps.list", {})["apps"][0]["scenarios"] == counts
    for bad in ({**counts, "names": 1}, {**counts, "passing": "3"}):
        svc = V5ContractService(FakeFleet(AppsBridge({"apps": [{**GMAIL_APP, "scenarios": bad}], "truncated": False})))
        with pytest.raises(DesktopRuntimeError):
            svc.forward("phone-1", "apps.list", {})


# ---- alpha.38: mapping identity (own = look only, test = test account) -------------------------------------------------

def test_mapping_start_forwards_a_known_identity_and_refuses_others(service):
    svc, bridge = service
    plane = {"sessionId": "default-foreground", "displayId": 0}
    for identity in ("own", "test"):
        svc.forward("phone-1", "mapping.start", {"placeId": "package:com.example.app", "persona": "mapping", "identity": identity, **plane})
        assert bridge.calls[-1][1]["identity"] == identity
    calls = len(bridge.calls)
    for bad in ("admin", "", None, 1):
        with pytest.raises(DesktopRuntimeError):
            svc.forward("phone-1", "mapping.start", {"placeId": "package:com.example.app", "persona": "mapping", "identity": bad, **plane})
    with pytest.raises(DesktopRuntimeError):
        svc.forward("phone-1", "mapping.start", {"resumeJobId": "map-phone-0001", "identity": "test", **plane})
    assert len(bridge.calls) == calls


def test_mapping_responses_may_carry_the_identity(service):
    svc, bridge = service
    plane = {"sessionId": "default-foreground", "displayId": 0}
    original = bridge.request

    def with_identity(value):
        def request(op, args, request_id=None):
            result = original(op, args, request_id)
            return {**result, "identity": value} if op.startswith("mapping.") else result
        return request

    bridge.request = with_identity("test")
    assert svc.forward("phone-1", "mapping.status", dict(plane))["identity"] == "test"
    bridge.request = with_identity("admin")
    with pytest.raises(DesktopRuntimeError):
        svc.forward("phone-1", "mapping.status", dict(plane))
