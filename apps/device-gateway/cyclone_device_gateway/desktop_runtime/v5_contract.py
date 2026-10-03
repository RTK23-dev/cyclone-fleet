from __future__ import annotations

import base64
import ipaddress
import json
import re
import secrets
from typing import Any

from ..cyclone_bridge.client import BridgeDisconnectedError, BridgeOperationError, BridgeProtocolError
from .fleet import DeviceFleetManager, DeviceSession
from . import phone_errors
from .models import DesktopRuntimeError, RuntimeErrorCode

V5_CONTRACT_PROTOCOL = "cyclone.v5.run2.contract.v1"
V5_OPS = frozenset({
    "atlas.places",
    "atlas.get",
    "atlas.diff",
    "mapping.start",
    "mapping.pause",
    "mapping.stop",
    "mapping.status",
    "secrets.slots",
    "secrets.request",
    "ask.start",
    "ask.status",
    "ask.cancel",
    "apps.list",
    "runs.list",
    "runs.get",
    "runs.mark",
    "atlas.versions",
    "scenarios.list",
    "knowledge.get",
    "atlas.here",
    "share.status",
    "share.request",
    "lab.start",
    "lab.status",
    "lab.answer",
    "lab.record",
    "market.catalog",
    "market.install",
    "market.remove",
    "market.run",
    "learn.run",
    "skills.list",
    "cc.start",
    "cc.status",
    "cc.answer",
    "cc.key",
    "ports.poll",
    "ports.blob",
    "ports.answer",
    "ports.file",
    "dictionary.get",
    "dictionary.edit",
    "models.list",
    "manual.get",
    "signup.maps",
    "numbers.list",
    "signup.forget",
    "profiles.list",
    "profiles.apps",
    "profiles.switch",
    "profiles.app",
})
ASK_STATES = frozenset({"idle", "working", "action-needed", "needs-secret", "done", "failed"})
ASK_MILESTONE_STATES = frozenset({"pending", "active", "done", "action-needed", "failed"})
ASK_STATUS_KEYS = frozenset({
    "taskId", "state", "title", "app", "currentMilestone", "milestones", "supportingCopy",
    "outcomeCopy", "sessionId", "displayId",
})
# Alpha 91: what became of one request (its id, the lane that took it, who decided, the times). Older phones omit it.
ASK_REQUEST_KEYS = frozenset({
    "requestId", "state", "lane", "decider", "why", "decideMs", "startedAtMs", "routedAtMs", "finishedAtMs", "say",
})
ASK_REQUEST_STATES = frozenset({"waiting", "running", "done", "failed", "cancelled"})
ASK_LANES = frozenset({"instant", "answer", "ignore", "flash", "mind"})
ASK_REQUEST_ID = re.compile(r"^req-[0-9a-f-]{8,40}$")
MAX_GOAL = 2000
PERSONAS = frozenset({"live", "mapping"})
MAPPING_STATES = frozenset({
    "idle",
    "running",
    "paused",
    "needs-secret",
    "human-control",
    "completed",
    "stopped",
    "failed",
})
FORBIDDEN_SECRET_KEYS = frozenset({
    "password", "passcode", "passwd", "pin", "otp", "token", "secret", "api_key",
    "authorization", "cookie", "cvv", "credential", "typed_text", "typed_value",
})
FORBIDDEN_SECRET_TOKENS = frozenset({
    "password", "passcode", "passwd", "pin", "otp", "token", "secret", "apikey",
    "authorization", "cookie", "cvv", "credential", "credentials", "typedtext", "typedvalue",
})
INLINE_SECRET = re.compile(
    r"(?i)(password|passcode|passwd|pin|otp|token|secret|api[_-]?key|authorization|cookie|cvv|credential|typed[_-]?(?:text|value))\s*[:=]"
)
PACKAGE_PLACE = re.compile(r"^package:[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$")
CHROME_PLACE = re.compile(r"^chrome:https?://[^\s/]+(?::[0-9]{1,5})?$")
SLOT = re.compile(r"^[A-Za-z][A-Za-z0-9._-]{0,63}$")
REASON = re.compile(r"^[A-Za-z0-9][A-Za-z0-9 ._/-]{0,119}$")
JOB_ID = re.compile(r"^[A-Za-z0-9_-]{8,120}$")
WORKSPACE_ID = re.compile(r"^[A-Za-z0-9_-]{1,80}$")
CURSOR = re.compile(r"^c1:[a-f0-9]{20}:[0-9]+$")
STRUCTURAL_ID = re.compile(r"^[A-Za-z0-9._:-]{1,180}$")
SCREEN_ID = re.compile(r"^(?:page|screen):[A-Za-z0-9._:-]{1,173}$")
EDGE_ID = re.compile(r"^edge:[A-Za-z0-9._:-]{1,175}$")
MAPPING_JOB_KEYS = frozenset({
    "mappingJobId", "placeId", "persona", "state", "sessionId", "displayId", "plane",
    "controlRevision", "executionGeneration", "budget", "currentAtlasNodeId", "progress",
    "atlasStatus", "danger", "boundary", "startedAtEpochMs", "updatedAtEpochMs", "failureCode",
})
#: Whose account a mapping pass uses: own = the owner's account, look only; test = a test account.
MAPPING_IDENTITIES = frozenset({"own", "test"})
BUDGET_KEYS = frozenset({
    "maxNewScreens",
    "maxElapsedMs",
    "maxConsecutiveNonProgress",
    "maxAttemptsPerDoor",
})
PROGRESS_KEYS = frozenset({
    "newScreens",
    "verifiedMutations",
    "consecutiveNonProgress",
    "attemptedDoors",
    "remainingDarkRegions",
})
PLANE_KEYS = frozenset({
    "kind",
    "sessionId",
    "displayId",
    "workspaceId",
    "workspaceGeneration",
    "label",
})


def _normalized_key(value: str) -> str:
    return re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", value).lower().replace("-", "_").replace(" ", "_")


def _secret_name(value: str) -> bool:
    normalized = _normalized_key(value)
    compact = normalized.replace("_", "")
    return (
        normalized in FORBIDDEN_SECRET_KEYS
        or compact in FORBIDDEN_SECRET_TOKENS
        or any(part in FORBIDDEN_SECRET_TOKENS for part in normalized.split("_"))
    )


def reject_secret_payload(value: Any, path: str = "$") -> None:
    """Fail closed before a secret-bearing payload can be logged or forwarded."""
    if isinstance(value, dict):
        for key, nested in value.items():
            key_text = str(key)
            child = f"{path}.{key_text}"
            if _secret_name(key_text):
                raise DesktopRuntimeError(
                    RuntimeErrorCode.INVALID_REQUEST,
                    "Secret-bearing request payload rejected.",
                )
            reject_secret_payload(nested, child)
        return
    if isinstance(value, list):
        for index, nested in enumerate(value):
            reject_secret_payload(nested, f"{path}[{index}]")
        return
    if isinstance(value, str) and INLINE_SECRET.search(value):
        raise DesktopRuntimeError(
            RuntimeErrorCode.INVALID_REQUEST,
            "Secret-bearing request payload rejected.",
        )


def _is_int(value: Any, *, minimum: int = 0) -> bool:
    return type(value) is int and value >= minimum


def _validate_place_persona(args: dict[str, Any]) -> None:
    place_id = args.get("placeId")
    persona = args.get("persona")
    if not isinstance(place_id, str) or (
        PACKAGE_PLACE.fullmatch(place_id) is None and CHROME_PLACE.fullmatch(place_id) is None
    ):
        raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "Invalid placeId.")
    if persona not in PERSONAS:
        raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "persona must be live or mapping.")


def _validate_mapping_plane(args: dict[str, Any], *, allow_execution_generation: bool) -> None:
    session_id = args.get("sessionId")
    display_id = args.get("displayId")
    if not isinstance(session_id, str) or not session_id.strip() or len(session_id) > 160:
        raise DesktopRuntimeError(RuntimeErrorCode.SESSION_REQUIRED, "sessionId is required for mapping.")
    if not _is_int(display_id):
        raise DesktopRuntimeError(
            RuntimeErrorCode.SESSION_DISPLAY_MISMATCH,
            "displayId is required and must be a non-negative integer.",
        )

    workspace_id = args.get("workspaceId")
    workspace_generation = args.get("workspaceGeneration")
    has_workspace = workspace_id is not None or workspace_generation is not None
    if has_workspace:
        if session_id != "default-foreground" or display_id != 0:
            raise DesktopRuntimeError(
                RuntimeErrorCode.SESSION_DISPLAY_MISMATCH,
                "Layer-2 mapping requires default-foreground/display 0.",
            )
        if not isinstance(workspace_id, str) or WORKSPACE_ID.fullmatch(workspace_id) is None:
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "Invalid workspaceId.")
        if not _is_int(workspace_generation):
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "workspaceGeneration is required.")
    elif session_id == "default-foreground":
        if display_id != 0:
            raise DesktopRuntimeError(
                RuntimeErrorCode.SESSION_DISPLAY_MISMATCH,
                "default-foreground mapping must use display 0.",
            )
    elif display_id <= 0:
        raise DesktopRuntimeError(
            RuntimeErrorCode.SESSION_DISPLAY_MISMATCH,
            "Named mapping sessions require a nonzero displayId.",
        )

    if "executionGeneration" in args:
        if not allow_execution_generation:
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "executionGeneration is not accepted here.")
        if not _is_int(args.get("executionGeneration")):
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "executionGeneration must be non-negative.")


def _validate_budget(value: Any) -> None:
    if not isinstance(value, dict) or set(value) != BUDGET_KEYS:
        raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "Mapping budget has an invalid shape.")
    limits = {
        "maxNewScreens": (1, 500),
        "maxElapsedMs": (1_000, 7_200_000),
        "maxConsecutiveNonProgress": (1, 100),
        "maxAttemptsPerDoor": (1, 20),
    }
    for key, (low, high) in limits.items():
        item = value.get(key)
        if not _is_int(item, minimum=low) or item > high:
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, f"{key} is outside its allowed range.")


def _validate_slot_presence_response(value: dict[str, Any], args: dict[str, Any]) -> None:
    if set(value) != {"placeId", "persona", "slots"}:
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android secrets.slots result is malformed.")
    if value.get("placeId") != args.get("placeId") or value.get("persona") != args.get("persona"):
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android secrets.slots identity mismatch.")
    slots = value.get("slots")
    if not isinstance(slots, dict):
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android secrets.slots presence map is malformed.")
    for slot, present in slots.items():
        if not isinstance(slot, str) or SLOT.fullmatch(slot) is None or type(present) is not bool:
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Secret slot presence must be boolean metadata only.")


def _validate_secret_request_ack(value: dict[str, Any], args: dict[str, Any]) -> None:
    reject_secret_payload(value)
    if set(value) != {"state", "request"} or value.get("state") != "needs-secret":
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android secrets.request acknowledgement is malformed.")
    request = value.get("request")
    if not isinstance(request, dict) or request != args:
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android secrets.request metadata mismatch.")


def _reject_atlas_secret_fact_slots(value: Any) -> None:
    if not isinstance(value, dict):
        return
    screens = value.get("screens")
    if not isinstance(screens, list):
        return
    for screen in screens:
        if not isinstance(screen, dict):
            continue
        slots = screen.get("factSlots")
        if not isinstance(slots, list):
            continue
        for slot in slots:
            if not isinstance(slot, dict):
                continue
            name = slot.get("name")
            if isinstance(name, str) and _secret_name(name):
                raise DesktopRuntimeError(
                    RuntimeErrorCode.PROTOCOL_MISMATCH,
                    "Android Atlas result attempted to expose a secret fact slot.",
                )


def _validate_atlas_diff(value: dict[str, Any], args: dict[str, Any]) -> None:
    expected = {"placeId", "persona", "since", "cursor", "resyncRequired", "changes"}
    if set(value) != expected:
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android atlas.diff result is malformed.")
    if value.get("placeId") != args.get("placeId") or value.get("persona") != args.get("persona"):
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android atlas.diff identity mismatch.")
    expected_since = args.get("since")
    if value.get("since") != expected_since:
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android atlas.diff cursor echo mismatch.")
    cursor = value.get("cursor")
    if not isinstance(cursor, str) or CURSOR.fullmatch(cursor) is None:
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android atlas.diff cursor is malformed.")
    if type(value.get("resyncRequired")) is not bool or not isinstance(value.get("changes"), list):
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android atlas.diff metadata is malformed.")
    if value["resyncRequired"] and value["changes"]:
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Resync-required diff must not fabricate changes.")

    allowed_base = {"cursor", "entity", "change", "id"}
    for change in value["changes"]:
        if not isinstance(change, dict):
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Atlas diff change must be an object.")
        if not allowed_base.issubset(change):
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Atlas diff change is incomplete.")
        if not set(change).issubset(allowed_base | {"mapStatus", "fromScreenId", "toScreenId", "layout"}):
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Atlas diff change exposed an unknown field.")
        if not isinstance(change["cursor"], str) or CURSOR.fullmatch(change["cursor"]) is None:
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Atlas diff change cursor is malformed.")
        if change.get("entity") not in {"place", "screen", "edge"} or change.get("change") not in {"upsert", "remove"}:
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Atlas diff change type is invalid.")
        change_id = change.get("id")
        if not isinstance(change_id, str) or STRUCTURAL_ID.fullmatch(change_id) is None:
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Atlas diff structural id is invalid.")
        entity = change["entity"]
        if entity == "place" and change_id != "place":
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Atlas place diff id must be literal place.")
        if entity == "screen" and SCREEN_ID.fullmatch(change_id) is None:
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Atlas screen diff id must be page:/screen:.")
        if entity == "edge" and EDGE_ID.fullmatch(change_id) is None:
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Atlas edge diff id must use edge:.")
        if "mapStatus" in change and change["mapStatus"] not in {"unmapped", "partial", "mapped", "stale", "blocked"}:
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Atlas diff mapStatus is invalid.")
        endpoints = ("fromScreenId" in change, "toScreenId" in change)
        if endpoints[0] != endpoints[1]:
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Atlas diff edge topology is incomplete.")
        for key in ("fromScreenId", "toScreenId"):
            if key in change and (
                not isinstance(change[key], str) or SCREEN_ID.fullmatch(change[key]) is None
            ):
                raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Atlas diff topology id is invalid.")
        if entity == "edge" and not all(endpoints):
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Atlas edge diff requires topology.")
        if entity != "edge" and any(endpoints):
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Only Atlas edge diffs may carry topology.")
        if entity == "place" and "layout" in change:
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Atlas place diff cannot carry layout.")
        if entity == "screen" and "mapStatus" in change:
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Atlas screen diff cannot carry mapStatus.")
        if entity == "edge" and ("mapStatus" in change or "layout" in change):
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Atlas edge diff carries topology only.")
        if "layout" in change:
            layout = change["layout"]
            if not isinstance(layout, dict) or set(layout) != {"x", "y"}:
                raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Atlas diff layout is malformed.")
            if type(layout["x"]) not in (int, float) or type(layout["y"]) not in (int, float):
                raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Atlas diff layout must be numeric.")


def _validate_mapping_response(value: dict[str, Any], args: dict[str, Any]) -> None:
    # alpha.38+ phones add `identity` (own/test, null when idle); older phones omit it.
    if set(value) not in (MAPPING_JOB_KEYS, MAPPING_JOB_KEYS | {"identity"}):
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android mapping result is malformed.")
    if "identity" in value and value["identity"] not in (MAPPING_IDENTITIES | {None}):
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android mapping identity is invalid.")
    if value.get("state") not in MAPPING_STATES:
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android mapping state is invalid.")
    if value.get("sessionId") != args.get("sessionId") or value.get("displayId") != args.get("displayId"):
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android mapping plane identity mismatch.")
    plane = value.get("plane")
    if not isinstance(plane, dict) or set(plane) != PLANE_KEYS:
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android mapping plane is malformed.")
    if plane.get("sessionId") != value.get("sessionId") or plane.get("displayId") != value.get("displayId"):
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android mapping plane disagrees with result identity.")
    if value["state"] == "idle":
        if value.get("mappingJobId") is not None:
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Idle mapping status must not invent a job.")
    else:
        if not isinstance(value.get("mappingJobId"), str) or JOB_ID.fullmatch(value["mappingJobId"]) is None:
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android mappingJobId is malformed.")
        if not isinstance(value.get("placeId"), str) or (
            PACKAGE_PLACE.fullmatch(value["placeId"]) is None and CHROME_PLACE.fullmatch(value["placeId"]) is None
        ):
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android mapping placeId is malformed.")
        if value.get("persona") not in PERSONAS:
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android mapping persona is invalid.")
        if not _is_int(value.get("controlRevision")):
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android mapping controlRevision is invalid.")

    budget = value.get("budget")
    if budget is not None:
        _validate_budget(budget)
    progress = value.get("progress")
    if not isinstance(progress, dict) or set(progress) != PROGRESS_KEYS:
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android mapping progress is malformed.")
    if not all(_is_int(progress.get(key)) for key in PROGRESS_KEYS):
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android mapping progress counters are invalid.")
    node_id = value.get("currentAtlasNodeId")
    if node_id is not None and (not isinstance(node_id, str) or SCREEN_ID.fullmatch(node_id) is None):
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android mapping node id must be page:/screen:.")
    if value.get("atlasStatus") not in {None, "partial", "mapped"}:
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android mapping atlasStatus is invalid.")


def _lab_mission(mission_id: Any) -> str:
    if not isinstance(mission_id, str) or not LAB_MISSION_ID.match(mission_id):
        raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "missionId is malformed.")
    return mission_id


def _validate_ask_foreground(args: dict[str, Any]) -> None:
    """Ask from Glass runs on the phone's main screen only; never a guessed named display. Alpha 91: the main screen
    is the default, so a client may leave both out."""
    args.setdefault("sessionId", "default-foreground")
    args.setdefault("displayId", 0)
    session_id = args.get("sessionId")
    if not isinstance(session_id, str) or not session_id:
        raise DesktopRuntimeError(RuntimeErrorCode.SESSION_REQUIRED, "sessionId is required for ask.")
    if session_id != "default-foreground" or not _is_int(args.get("displayId")) or args.get("displayId") != 0:
        raise DesktopRuntimeError(
            RuntimeErrorCode.SESSION_DISPLAY_MISMATCH,
            "Ask runs on default-foreground / display 0.",
        )


def _validate_ask_request(value: Any) -> None:
    if value is None:
        return
    if not isinstance(value, dict) or set(value) != ASK_REQUEST_KEYS or not ASK_REQUEST_ID.match(str(value.get("requestId"))) \
            or value.get("state") not in ASK_REQUEST_STATES or (value.get("lane") is not None and value["lane"] not in ASK_LANES):
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android ask request record is malformed.")
    for key in ("why", "say", "decider"):
        if value.get(key) is not None and (not isinstance(value[key], str) or len(value[key]) > 240):
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android ask request record is malformed.")


def _validate_ask_response(op: str, value: dict[str, Any]) -> None:
    if op == "ask.start":
        keys = set(value)
        if keys not in ({"accepted", "sessionId", "displayId"}, {"accepted", "sessionId", "displayId", "requestId"}) \
                or value.get("accepted") is not True:
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android ask.start acknowledgement is malformed.")
        if "requestId" in value and not ASK_REQUEST_ID.match(str(value["requestId"])):
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android ask.start acknowledgement is malformed.")
        return
    if op == "ask.cancel":
        if set(value) - {"cancelled", "detail", "request"} or not isinstance(value.get("cancelled"), bool) \
                or not isinstance(value.get("detail"), str):
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android ask.cancel result is malformed.")
        _validate_ask_request(value.get("request"))
        return
    if "request" in value:
        _validate_ask_request(value.get("request"))
        value = {k: v for k, v in value.items() if k != "request"}
    if set(value) != ASK_STATUS_KEYS or value.get("state") not in ASK_STATES:
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android ask.status result is malformed.")
    milestones = value.get("milestones")
    if not isinstance(milestones, list) or len(milestones) > 8:
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android ask.status milestones are malformed.")
    for milestone in milestones:
        if (
            not isinstance(milestone, dict)
            or set(milestone) != {"label", "state"}
            or not isinstance(milestone.get("label"), str)
            or milestone.get("state") not in ASK_MILESTONE_STATES
        ):
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android ask.status milestone is malformed.")


APP_KEYS = frozenset({
    "placeId", "kind", "label", "packageName", "origin", "installed", "installedVersion", "mapStatus", "rooms",
    "doors", "lastVerifiedAt", "needsRemap", "personas", "mappedVersions",
})
APP_KINDS = frozenset({"package", "chrome-origin"})
MAP_STATUSES = frozenset({"unmapped", "partial", "mapped", "stale"})
MAX_APPS = 600


def _validate_version(value: Any, *, extra: frozenset[str] = frozenset()) -> None:
    if not isinstance(value, dict) or not {"versionName", "versionCode"} <= set(value) <= {"versionName", "versionCode"} | extra:
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android app version is malformed.")
    if value["versionName"] is not None and not isinstance(value["versionName"], str):
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android app versionName is malformed.")
    if value["versionCode"] is not None and not _is_int(value["versionCode"]):
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android app versionCode is malformed.")


def _validate_apps_list(value: dict[str, Any]) -> None:
    """apps.list carries package facts and map counts only: no screens, doors, selectors or user content."""
    if set(value) != {"apps", "truncated"} or not isinstance(value.get("apps"), list) or not isinstance(value.get("truncated"), bool):
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android apps.list result is malformed.")
    if len(value["apps"]) > MAX_APPS:
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android apps.list returned too many apps.")
    for app in value["apps"]:
        if not isinstance(app, dict) or not APP_KEYS <= set(app) <= APP_KEYS | {"scenarios"}:
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android app entry is malformed.")
        if "scenarios" in app:  # alpha.14+: scenario health counts for the Apps page
            counts = app["scenarios"]
            if not isinstance(counts, dict) or set(counts) != {"passing", "warning", "critical", "untested"} or not all(
                _is_int(counts[key]) for key in counts
            ):
                raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android app scenario counts are malformed.")
        place_id = app["placeId"]
        if not isinstance(place_id, str) or not place_id.startswith(("package:", "chrome:")) or len(place_id) > 512:
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android app placeId is malformed.")
        if app["kind"] not in APP_KINDS or app["mapStatus"] not in MAP_STATUSES:
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android app kind or status is unknown.")
        if not isinstance(app["label"], str) or not app["label"] or len(app["label"]) > 80:
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android app label is malformed.")
        if not _is_int(app["rooms"]) or not _is_int(app["doors"]) or not isinstance(app["needsRemap"], bool):
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android app counts are malformed.")
        if app["installed"] is not None and not isinstance(app["installed"], bool):
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android app installed flag is malformed.")
        if app["installedVersion"] is not None:
            _validate_version(app["installedVersion"])
        if not isinstance(app["mappedVersions"], list) or not isinstance(app["personas"], list):
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android app versions are malformed.")
        for version in app["mappedVersions"]:
            _validate_version(version, extra=frozenset({"doors"}))
        for persona in app["personas"]:
            if (
                not isinstance(persona, dict)
                or set(persona) != {"persona", "mapStatus", "rooms", "doors", "lastVerifiedAt"}
                or persona["persona"] not in {"live", "mapping"}
                or persona["mapStatus"] not in MAP_STATUSES
            ):
                raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android app persona is malformed.")


RUN_ID = re.compile(r"^[A-Za-z0-9_-]{4,120}$")
RUN_STATUSES = frozenset({"running", "suspended", "completed", "failed", "cancelled"})
RUN_FILTERS = frozenset({"all", "failed", "completed", "stopped"})
RUN_SUMMARY_KEYS = frozenset({
    "runId", "goal", "model", "status", "startedAt", "endedAt", "durationMs", "decisions", "stepCount", "metrics", "cause",
})
RUN_DETAIL_KEYS = RUN_SUMMARY_KEYS | {"result", "stepsTruncated", "steps"}
RUN_CAUSE_KEYS = frozenset({"kind", "stepIndex", "headline", "detail", "fix"})
RUN_STEP_KEYS = frozenset({
    "index", "startedAt", "endedAt", "title", "action", "pageId", "outcome", "verification", "recovery", "vision",
    "eventsTruncated", "events",
})
RUN_EVENT_KEYS = frozenset({"at", "kind", "text", "code", "ok", "detail"})
# Run record v2 (Glass alpha.4): optional so phones from alpha.8-alpha.10 keep working.
RUN_SUMMARY_V2_KEYS = frozenset({"mapSteps", "modelSteps", "places"})
RUN_OPTIONAL_KEYS = frozenset({"expected", "clauses", "ledger"})
RUN_STEP_V2_KEYS = frozenset({"roomId", "roomAfter", "placeId", "appVersion", "decisionSource"})
RUN_STEP_OPTIONAL_KEYS = frozenset({"expectedRoomId"})
RUN_CLAUSE_KEYS = frozenset({"id", "text", "place", "status", "proof"})
RUN_LEDGER_KEYS = frozenset({"key", "value", "sourcePlace", "sourceRoom", "persona", "readAtMs"})
NAV_PLACE_ID = re.compile(r"^(?:package:[A-Za-z][A-Za-z0-9_.]{1,150}|chrome:https?://[A-Za-z0-9.-]+(?::[0-9]{1,5})?)$")
MASKED_FACT = re.compile(r"^[^\s*@]\*{3}(?:@[A-Za-z0-9.-]+\.[A-Za-z]{2,})?$")
RUN_PLACE_KEYS = frozenset({"placeId", "appVersion", "route"})
ROOM_ID = re.compile(r"^screen:[a-z_]{1,40}:[0-9a-f]{8,64}$")
RUN_PLACE_ID = re.compile(r"^package:[A-Za-z][A-Za-z0-9_.]{1,150}$")
APP_VERSION = re.compile(r"^[A-Za-z0-9._+-]{1,40}$")
MAX_RUN_PLACES = 8
MAX_ROUTE_ROOMS = 60
STEP_OUTCOMES = frozenset({"ok", "failed", "unverified", "recovered", "info"})
MAX_RUNS = 200
MAX_RUN_STEPS = 200
MAX_STEP_EVENTS = 40


def _bad_run(message: str) -> DesktopRuntimeError:
    return DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, f"Android run {message} is malformed.")


def _short_text(value: Any, limit: int, *, nullable: bool = False) -> bool:
    if value is None:
        return nullable
    return isinstance(value, str) and len(value) <= limit


def _optional_match(value: Any, pattern: re.Pattern[str]) -> bool:
    return value is None or (isinstance(value, str) and bool(pattern.match(value)))


def _validate_run_summary(run: Any, keys: frozenset[str]) -> None:
    if not isinstance(run, dict) or not keys <= set(run) <= keys | RUN_SUMMARY_V2_KEYS | RUN_OPTIONAL_KEYS:
        raise _bad_run("summary")
    if "expected" in run and not isinstance(run["expected"], bool):
        raise _bad_run("expected")
    if "clauses" in run:
        clauses = run["clauses"]
        if not isinstance(clauses, list) or len(clauses) > 16:
            raise _bad_run("clauses")
        seen = set()
        for clause in clauses:
            if not isinstance(clause, dict) or set(clause) != RUN_CLAUSE_KEYS:
                raise _bad_run("clause")
            if not isinstance(clause["id"], str) or not re.fullmatch(r"clause-(?:[1-9]|1[0-6])", clause["id"]) or clause["id"] in seen:
                raise _bad_run("clause id")
            seen.add(clause["id"])
            if not _short_text(clause["text"], 500) or not _short_text(clause["proof"], 400, nullable=True):
                raise _bad_run("clause text")
            if not _optional_match(clause["place"], NAV_PLACE_ID) or clause["status"] not in (
                "pending", "active", "verified", "needs-approval", "failed"
            ):
                raise _bad_run("clause state")
    if "ledger" in run:
        ledger = run["ledger"]
        if not isinstance(ledger, list) or len(ledger) > 4:
            raise _bad_run("ledger")
        seen = set()
        for fact in ledger:
            if not isinstance(fact, dict) or set(fact) != RUN_LEDGER_KEYS:
                raise _bad_run("ledger fact")
            if not isinstance(fact["key"], str) or fact["key"] not in (
                "signed-in-email", "found-username", "thread-with", "connected-network"
            ) or fact["key"] in seen:
                raise _bad_run("ledger key")
            seen.add(fact["key"])
            if not isinstance(fact["value"], str) or len(fact["value"]) > 256 or not MASKED_FACT.fullmatch(fact["value"]):
                raise _bad_run("unmasked ledger")
            if not isinstance(fact["sourcePlace"], str) or not NAV_PLACE_ID.fullmatch(fact["sourcePlace"]):
                raise _bad_run("ledger place")
            if not isinstance(fact["sourceRoom"], str) or not ROOM_ID.fullmatch(fact["sourceRoom"]):
                raise _bad_run("ledger room")
            if fact["persona"] != "live" or not _is_int(fact["readAtMs"]):
                raise _bad_run("ledger provenance")
    if RUN_SUMMARY_V2_KEYS & set(run):
        if set(run) & RUN_SUMMARY_V2_KEYS != RUN_SUMMARY_V2_KEYS:
            raise _bad_run("record v2")
        if not _is_int(run["mapSteps"]) or not _is_int(run["modelSteps"]):
            raise _bad_run("map/model steps")
        places = run["places"]
        if not isinstance(places, list) or len(places) > MAX_RUN_PLACES:
            raise _bad_run("places")
        for place in places:
            if not isinstance(place, dict) or set(place) != RUN_PLACE_KEYS:
                raise _bad_run("place")
            if not isinstance(place["placeId"], str) or not RUN_PLACE_ID.match(place["placeId"]):
                raise _bad_run("place id")
            if not _optional_match(place["appVersion"], APP_VERSION):
                raise _bad_run("app version")
            route = place["route"]
            if not isinstance(route, list) or len(route) > MAX_ROUTE_ROOMS or not all(
                isinstance(room, str) and ROOM_ID.match(room) for room in route
            ):
                raise _bad_run("route")
    if not isinstance(run["runId"], str) or not RUN_ID.match(run["runId"]) or run["status"] not in RUN_STATUSES:
        raise _bad_run("identity")
    if not _short_text(run["goal"], 500) or not _short_text(run["model"], 120):
        raise _bad_run("text")
    for key in ("startedAt", "durationMs", "decisions", "stepCount"):
        if not _is_int(run[key]):
            raise _bad_run(key)
    if run["endedAt"] is not None and not _is_int(run["endedAt"]):
        raise _bad_run("endedAt")
    metrics = run["metrics"]
    if not isinstance(metrics, dict) or not all(isinstance(k, str) and _is_int(v) for k, v in metrics.items()) or len(metrics) > 12:
        raise _bad_run("metrics")
    cause = run["cause"]
    if cause is not None:
        if not isinstance(cause, dict) or set(cause) != RUN_CAUSE_KEYS:
            raise _bad_run("cause")
        if not isinstance(cause["kind"], str) or not re.fullmatch(r"[a-z][a-z-]{1,40}", cause["kind"]):
            raise _bad_run("cause kind")
        if cause["stepIndex"] is not None and not _is_int(cause["stepIndex"]):
            raise _bad_run("cause step")
        if not all(_short_text(cause[key], 400) for key in ("headline", "detail", "fix")):
            raise _bad_run("cause text")


def _validate_runs_list(value: dict[str, Any]) -> None:
    if set(value) != {"runs"} or not isinstance(value["runs"], list) or len(value["runs"]) > MAX_RUNS:
        raise _bad_run("list")
    for run in value["runs"]:
        _validate_run_summary(run, RUN_SUMMARY_KEYS)


def _validate_run_detail(value: dict[str, Any], args: dict[str, Any]) -> None:
    _validate_run_summary(value, RUN_DETAIL_KEYS)
    if value["runId"] != args.get("runId"):
        raise _bad_run("id mismatch")
    if not _short_text(value["result"], 1500) or not isinstance(value["stepsTruncated"], bool):
        raise _bad_run("result")
    steps = value["steps"]
    if not isinstance(steps, list) or len(steps) > MAX_RUN_STEPS:
        raise _bad_run("steps")
    for step in steps:
        if not isinstance(step, dict) or not RUN_STEP_KEYS <= set(step) <= RUN_STEP_KEYS | RUN_STEP_V2_KEYS | RUN_STEP_OPTIONAL_KEYS:
            raise _bad_run("step")
        if "expectedRoomId" in step and not _optional_match(step["expectedRoomId"], ROOM_ID):
            raise _bad_run("expected room")
        if step["outcome"] not in STEP_OUTCOMES:
            raise _bad_run("step")
        if RUN_STEP_V2_KEYS & set(step):
            if set(step) & RUN_STEP_V2_KEYS != RUN_STEP_V2_KEYS:
                raise _bad_run("step record v2")
            if not (_optional_match(step["roomId"], ROOM_ID) and _optional_match(step["roomAfter"], ROOM_ID)
                    and _optional_match(step["placeId"], RUN_PLACE_ID) and _optional_match(step["appVersion"], APP_VERSION)):
                raise _bad_run("step location")
            if step["decisionSource"] not in (None, "map", "model"):
                raise _bad_run("decision source")
        if not all(_is_int(step[key]) for key in ("index", "startedAt", "endedAt")) or not isinstance(step["vision"], bool):
            raise _bad_run("step numbers")
        if not _short_text(step["title"], 200) or not all(
            _short_text(step[key], 120, nullable=True) for key in ("action", "pageId", "verification", "recovery")
        ):
            raise _bad_run("step text")
        events = step["events"]
        if not isinstance(events, list) or len(events) > MAX_STEP_EVENTS or not isinstance(step["eventsTruncated"], bool):
            raise _bad_run("step events")
        for event in events:
            if not isinstance(event, dict) or set(event) != RUN_EVENT_KEYS or not _is_int(event["at"]):
                raise _bad_run("event")
            if not _short_text(event["kind"], 40) or not _short_text(event["text"], 360):
                raise _bad_run("event text")
            if not _short_text(event["code"], 120, nullable=True) or not _short_text(event["detail"], 600, nullable=True):
                raise _bad_run("event detail")
            if event["ok"] is not None and not isinstance(event["ok"], bool):
                raise _bad_run("event ok")


KNOWLEDGE_PLACE_ID = re.compile(r"^package:[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+$")
VERSION_ROW_KEYS = frozenset({"versionName", "versionCode", "installed", "doors", "rooms", "failingDoors", "lastSeenAt"})
STALE_DOOR_KEYS = frozenset({"edgeId", "fromScreenId", "toScreenId", "versionName", "versionCode"})
SCENARIO_KEYS = frozenset({
    "scenarioId", "title", "startScreenId", "endScreenId", "route", "steps", "danger", "health", "lastVerifiedAt", "appVersion", "runs",
})
SCENARIO_HEALTH = frozenset({"passing", "warning", "critical", "untested"})
SCENARIO_KINDS = frozenset({"reach", "sign-in", "signed-in"})
SCENARIO_ID = re.compile(r"^sc_[0-9a-f]{18}$")


def _bad_knowledge(message: str) -> DesktopRuntimeError:
    return DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, f"Android {message} is malformed.")


def _version_fields(value: dict[str, Any]) -> bool:
    return _short_text(value.get("versionName"), 64, nullable=True) and (
        value.get("versionCode") is None or _is_int(value.get("versionCode"))
    )


def _validate_atlas_versions(value: dict[str, Any], args: dict[str, Any]) -> None:
    """Version facts and structural ids only: no labels, selectors or screen content."""
    keys = {"placeId", "installedVersion", "needsRemap", "versions", "staleDoorCount", "staleDoors"}
    if set(value) != keys or value["placeId"] != args.get("placeId") or not isinstance(value["needsRemap"], bool):
        raise _bad_knowledge("atlas.versions")
    installed = value["installedVersion"]
    if installed is not None and (not isinstance(installed, dict) or set(installed) != {"versionName", "versionCode"} or not _version_fields(installed)):
        raise _bad_knowledge("installed version")
    versions = value["versions"]
    if not isinstance(versions, list) or len(versions) > 12:
        raise _bad_knowledge("versions")
    for row in versions:
        if not isinstance(row, dict) or set(row) != VERSION_ROW_KEYS or not _version_fields(row) or not isinstance(row["installed"], bool):
            raise _bad_knowledge("version row")
        if not all(_is_int(row[key]) for key in ("doors", "rooms", "failingDoors", "lastSeenAt")):
            raise _bad_knowledge("version counts")
    stale = value["staleDoors"]
    if not _is_int(value["staleDoorCount"]) or not isinstance(stale, list) or len(stale) > 20:
        raise _bad_knowledge("stale doors")
    for door in stale:
        if not isinstance(door, dict) or set(door) != STALE_DOOR_KEYS or not _version_fields(door):
            raise _bad_knowledge("stale door")
        if not EDGE_ID.fullmatch(str(door["edgeId"])) or not all(
            isinstance(door[key], str) and SCREEN_ID.fullmatch(door[key]) for key in ("fromScreenId", "toScreenId")
        ):
            raise _bad_knowledge("stale door ids")


def _validate_scenarios(value: dict[str, Any], args: dict[str, Any]) -> None:
    if set(value) != {"placeId", "persona", "entryScreenId", "scenarios"} or value["placeId"] != args.get("placeId"):
        raise _bad_knowledge("scenarios.list")
    if value["persona"] not in {"live", "mapping"}:
        raise _bad_knowledge("scenario persona")
    entry = value["entryScreenId"]
    if entry is not None and (not isinstance(entry, str) or not SCREEN_ID.fullmatch(entry)):
        raise _bad_knowledge("entry room")
    scenarios = value["scenarios"]
    if not isinstance(scenarios, list) or len(scenarios) > 24:
        raise _bad_knowledge("scenarios")
    for scenario in scenarios:
        if not isinstance(scenario, dict) or set(scenario) - {"kind"} != SCENARIO_KEYS:
            raise _bad_knowledge("scenario")
        if "kind" in scenario and scenario["kind"] not in SCENARIO_KINDS:  # alpha.15+: sign-in scenarios
            raise _bad_knowledge("scenario kind")
        if not isinstance(scenario["scenarioId"], str) or not SCENARIO_ID.match(scenario["scenarioId"]):
            raise _bad_knowledge("scenario id")
        if not _short_text(scenario["title"], 80) or scenario["health"] not in SCENARIO_HEALTH or not isinstance(scenario["danger"], bool):
            raise _bad_knowledge("scenario fields")
        route = scenario["route"]
        if not isinstance(route, list) or not 2 <= len(route) <= 40 or not all(isinstance(room, str) and SCREEN_ID.fullmatch(room) for room in route):
            raise _bad_knowledge("scenario route")
        if route[0] != scenario["startScreenId"] or route[-1] != scenario["endScreenId"] or scenario["steps"] != len(route) - 1:
            raise _bad_knowledge("scenario ends")
        if scenario["lastVerifiedAt"] is not None and not _is_int(scenario["lastVerifiedAt"]):
            raise _bad_knowledge("scenario verified")
        if not _short_text(scenario["appVersion"], 64, nullable=True):
            raise _bad_knowledge("scenario version")
        runs = scenario["runs"]
        if not isinstance(runs, list) or len(runs) > 5:
            raise _bad_knowledge("scenario runs")
        for run in runs:
            if not isinstance(run, dict) or set(run) != {"runId", "status", "startedAt"}:
                raise _bad_knowledge("scenario run")
            if not isinstance(run["runId"], str) or not RUN_ID.match(run["runId"]) or run["status"] not in RUN_STATUSES or not _is_int(run["startedAt"]):
                raise _bad_knowledge("scenario run fields")


SLOT_PLACE_ID = re.compile(r"^(?:package:[A-Za-z][A-Za-z0-9_.]{1,150}|chrome:https?://[A-Za-z0-9.-]{1,190}(?::\d{1,5})?)$")
GUARDED_DANGERS = frozenset({"payment", "send-public", "delete-account", "logout-all", "permission"})
SLOT_NAME = re.compile(r"^[A-Za-z][A-Za-z0-9._-]{0,63}$")


def _validate_knowledge_summary(value: dict[str, Any]) -> None:
    """Slot presence (never values), skill/automation names and counts, Atlas totals."""
    if set(value) - {"guarded"} != {"vault", "skills", "automations", "atlas"}:
        raise _bad_knowledge("knowledge.get")
    vault = value["vault"]
    if not isinstance(vault, dict) or set(vault) != {"slotCount", "setCount", "slots"}:
        raise _bad_knowledge("vault")
    if not _is_int(vault["slotCount"]) or not _is_int(vault["setCount"]) or not isinstance(vault["slots"], list) or len(vault["slots"]) > 200:
        raise _bad_knowledge("vault counts")
    for slot in vault["slots"]:
        if not isinstance(slot, dict) or set(slot) != {"placeId", "persona", "slot", "set", "updatedAt"}:
            raise _bad_knowledge("vault slot")
        if not isinstance(slot["placeId"], str) or not SLOT_PLACE_ID.match(slot["placeId"]) or slot["persona"] not in {"live", "mapping"}:
            raise _bad_knowledge("vault slot place")
        if not isinstance(slot["slot"], str) or not SLOT_NAME.match(slot["slot"]) or not isinstance(slot["set"], bool):
            raise _bad_knowledge("vault slot name")
        if slot["updatedAt"] is not None and not _is_int(slot["updatedAt"]):
            raise _bad_knowledge("vault slot time")
    for key, fields in (("skills", {"id", "name", "steps", "enabled", "version"}), ("automations", {"id", "name", "trigger", "steps", "enabled"})):
        rows = value[key]
        if not isinstance(rows, list) or len(rows) > 100:
            raise _bad_knowledge(key)
        for row in rows:
            if not isinstance(row, dict) or set(row) != fields or not _short_text(row["id"], 80) or not _short_text(row["name"], 80):
                raise _bad_knowledge(f"{key} row")
            if not _is_int(row["steps"]) or not isinstance(row["enabled"], bool):
                raise _bad_knowledge(f"{key} fields")
            if key == "skills" and not _is_int(row["version"]):
                raise _bad_knowledge("skill version")
            if key == "automations" and (not isinstance(row["trigger"], str) or not re.fullmatch(r"[a-z_]{1,40}", row["trigger"])):
                raise _bad_knowledge("automation trigger")
    atlas = value["atlas"]
    if not isinstance(atlas, dict) or set(atlas) != {"places", "rooms", "doors"} or not all(_is_int(atlas[k]) for k in atlas):
        raise _bad_knowledge("atlas totals")
    if "guarded" in value:  # alpha.15+: the never-pay list, counts per app and danger
        guarded = value["guarded"]
        if not isinstance(guarded, list) or len(guarded) > 200:
            raise _bad_knowledge("guarded")
        for row in guarded:
            if not isinstance(row, dict) or set(row) - {"roomIds"} != {"placeId", "label", "persona", "danger", "doors", "rooms"}:
                raise _bad_knowledge("guarded row")
            rooms = row.get("roomIds", [])
            if not isinstance(rooms, list) or len(rooms) > 10 or not all(isinstance(room, str) and SCREEN_ID.fullmatch(room) for room in rooms):
                raise _bad_knowledge("guarded rooms")
            if not isinstance(row["placeId"], str) or not SLOT_PLACE_ID.match(row["placeId"]) or row["persona"] not in {"live", "mapping"}:
                raise _bad_knowledge("guarded place")
            if row["danger"] not in GUARDED_DANGERS or not _short_text(row["label"], 80):
                raise _bad_knowledge("guarded danger")
            if not _is_int(row["doors"]) or not _is_int(row["rooms"]) or row["doors"] < 0 or row["rooms"] < 0:
                raise _bad_knowledge("guarded counts")


def validate_android_response(op: str, value: dict[str, Any], args: dict[str, Any]) -> dict[str, Any]:
    """Keep a phone bug from turning into PC/model secret or mapping authority."""
    if op == "secrets.slots":
        _validate_slot_presence_response(value, args)
        return value
    if op == "secrets.request":
        _validate_secret_request_ack(value, args)
        return value

    if op == "ports.blob":
        # Screenshot bytes: base64 only (checked here, so padding like "...Otp=" is never read as a secret field).
        _validate_ports_blob(value)
        return value
    reject_secret_payload(value)
    if op in PORTS_OPS:
        _validate_ports_response(op, value)
        return value
    if op == "numbers.list":
        _validate_numbers_list(value)
        return value
    if op == "atlas.places":
        if set(value) != {"places"} or not isinstance(value.get("places"), list):
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android atlas.places result is malformed.")
        for summary in value["places"]:
            if not isinstance(summary, dict):
                raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android Atlas place summary is malformed.")
            _reject_atlas_secret_fact_slots(summary)
        return value
    if op == "atlas.get":
        _reject_atlas_secret_fact_slots(value)
        return value
    if op == "atlas.diff":
        _validate_atlas_diff(value, args)
        return value
    if op in {"mapping.start", "mapping.pause", "mapping.stop", "mapping.status"}:
        _validate_mapping_response(value, args)
        return value
    if op in {"ask.start", "ask.status", "ask.cancel"}:
        _validate_ask_response(op, value)
        return value
    if op == "apps.list":
        _validate_apps_list(value)
        return value
    if op == "runs.list":
        _validate_runs_list(value)
        return value
    if op == "runs.get":
        _validate_run_detail(value, args)
        return value
    if op == "atlas.versions":
        _validate_atlas_versions(value, args)
        return value
    if op == "scenarios.list":
        _validate_scenarios(value, args)
        return value
    if op == "runs.mark":
        if set(value) != {"runId", "expected"} or value["runId"] != args.get("runId") or not isinstance(value["expected"], bool):
            raise _bad_run("mark")
        return value
    if op == "knowledge.get":
        _validate_knowledge_summary(value)
        return value
    if op == "share.status":
        _validate_share_status(value)
        return value
    if op == "share.request":
        if set(value) != {"prompted", "sharing"} or not all(isinstance(value[k], bool) for k in value):
            raise _bad_knowledge("share.request")
        return value
    if op in LAB_OPS:
        _validate_lab_response(op, value, args)
        return value
    if op in MARKET_OPS:
        _validate_market_response(op, value, args)
        return value
    if op in CC_OPS:
        _validate_cc_response(op, value, args)
        return value
    if op == "learn.run":
        _validate_learn_response(value, args)
        return value
    if op == "skills.list":
        _validate_skills_response(value)
        return value
    if op in {"dictionary.get", "dictionary.edit"}:
        _validate_dictionary_response(value, args)
        return value
    if op == "models.list":
        _validate_models_response(value)
        return value
    if op == "manual.get":
        _validate_manual_response(value, args)
        return value
    if op in SIGNUP_OPS:
        _validate_signup_response(op, value, args)
        return value
    if op in PROFILE_OPS:
        _validate_profiles_response(op, value, args)
        return value
    if op == "atlas.here":
        if set(value) != {"placeId", "roomId", "appVersion", "observedAt"}:
            raise _bad_knowledge("atlas.here")
        if value["placeId"] is not None and (not isinstance(value["placeId"], str) or not KNOWLEDGE_PLACE_ID.match(value["placeId"])):
            raise _bad_knowledge("here place")
        if value["roomId"] is not None and (not isinstance(value["roomId"], str) or not SCREEN_ID.fullmatch(value["roomId"])):
            raise _bad_knowledge("here room")
        if not _short_text(value["appVersion"], 40, nullable=True) or (value["observedAt"] is not None and not _is_int(value["observedAt"])):
            raise _bad_knowledge("here facts")
        return value
    raise DesktopRuntimeError(RuntimeErrorCode.CAPABILITY_UNAVAILABLE, "Unsupported V5 contract operation.")


LAB_OPS = frozenset({"lab.start", "lab.status", "lab.answer", "lab.record"})
LAB_MISSION_ID = re.compile(r"^m[a-z0-9]{6,40}$")
LAB_RUN_ID = re.compile(r"^[A-Za-z0-9_-]{6,80}$")
LAB_STATUSES = frozenset({"running", "waiting", "completed", "gave_up", "failed", "cancelled", "paused", "interrupted"})
LAB_MOMENT_KINDS = frozenset({"question", "values", "approval", "secret", "handover"})
LAB_ANSWERS = frozenset({"reply", "fill", "decline", "stop", "steer"})
LAB_RECORD_KEYS = frozenset({
    "missionId", "goal", "status", "live", "summary", "evidence", "turns", "workingMs", "resumes", "createdAt",
    "updatedAt", "modelId", "modelLabel", "usage", "traceId", "lab", "metrics", "events", "app",
})


def _bad_lab(message: str) -> DesktopRuntimeError:
    return DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, f"Android lab result is malformed: {message}.")


def _validate_lab_moment(moment: Any) -> None:
    if moment is None:
        return
    if not isinstance(moment, dict) or set(moment) != {"kind", "text", "choices", "fields", "requestId"}:
        raise _bad_lab("moment")
    if moment["kind"] not in LAB_MOMENT_KINDS or not _short_text(moment["text"], 300):
        raise _bad_lab("moment kind")
    if not isinstance(moment["choices"], list) or len(moment["choices"]) > 6 or not all(_short_text(c, 80) for c in moment["choices"]):
        raise _bad_lab("moment choices")
    fields = moment["fields"]
    if not isinstance(fields, list) or len(fields) > 8:
        raise _bad_lab("moment fields")
    for field in fields:
        if not isinstance(field, dict) or set(field) != {"label", "kind"} or not _short_text(field["label"], 60) or not _short_text(field["kind"], 20):
            raise _bad_lab("moment field")
    if moment["requestId"] is not None and not _short_text(moment["requestId"], 80):
        raise _bad_lab("moment request")


def _validate_lab_response(op: str, value: dict[str, Any], args: dict[str, Any]) -> None:
    """The phone's lab answers are facts about one mission; nothing in them can carry a secret or a command."""
    if op == "lab.start":
        if set(value) != {"accepted", "missionId", "taskId"} or value["accepted"] is not True:
            raise _bad_lab("start")
        if not isinstance(value["missionId"], str) or not LAB_MISSION_ID.match(value["missionId"]) or value["taskId"] != f"mission-{value['missionId']}":
            raise _bad_lab("start id")
        return
    if value.get("missionId") != args.get("missionId") and op != "lab.answer":
        raise _bad_lab("mission id")
    if op == "lab.status":
        if set(value) != {"missionId", "status", "live", "turns", "workingMs", "costUsd", "moment"}:
            raise _bad_lab("status")
        if value["status"] not in LAB_STATUSES or not isinstance(value["live"], bool):
            raise _bad_lab("status state")
        if not _is_int(value["turns"]) or not _is_int(value["workingMs"]) or not isinstance(value["costUsd"], (int, float)):
            raise _bad_lab("status numbers")
        _validate_lab_moment(value["moment"])
        return
    if op == "lab.answer":
        if set(value) != {"handled", "detail"} or not isinstance(value["handled"], bool) or not _short_text(value["detail"], 200):
            raise _bad_lab("answer")
        return
    if set(value) != LAB_RECORD_KEYS or value["status"] not in LAB_STATUSES:
        raise _bad_lab("record")
    for key, limit in (("goal", 600), ("summary", 600), ("evidence", 600), ("modelId", 120), ("modelLabel", 120)):
        if not _short_text(value[key], limit):
            raise _bad_lab(key)
    for key in ("turns", "workingMs", "resumes", "createdAt", "updatedAt"):
        if not _is_int(value[key]):
            raise _bad_lab(key)
    if not isinstance(value["usage"], dict) or not isinstance(value["metrics"], dict) or not isinstance(value["app"], dict):
        raise _bad_lab("usage/metrics/app")
    if value["lab"] is not None and (not isinstance(value["lab"], dict) or not isinstance(value["lab"].get("variant"), dict)):
        raise _bad_lab("lab tag")
    events = value["events"]
    if not isinstance(events, list) or len(events) > 60 or not all(isinstance(e, dict) and _short_text(e.get("text"), 200) for e in events):
        raise _bad_lab("events")


LEARN_KEYS = frozenset({"runId", "learned", "alreadyLearned", "sentence", "apps", "refusal"})
LEARN_APP_KEYS = frozenset({"package", "label", "screens", "newScreens", "controls", "transitions"})
LEARN_REFUSALS = frozenset({"RUN_NOT_FOUND", "ASK_BUSY", "NOTHING_TO_LEARN"})


def _bad_learn(message: str) -> DesktopRuntimeError:
    return DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, f"Android learn result is malformed: {message}.")


def _validate_learn_response(value: dict[str, Any], args: dict[str, Any]) -> None:
    """Learn reports counts and app labels only: never a screen's content, a typed value or a selector."""
    if set(value) != LEARN_KEYS or value["runId"] != args.get("runId"):
        raise _bad_learn("keys")
    if not isinstance(value["learned"], bool) or not isinstance(value["alreadyLearned"], bool):
        raise _bad_learn("flags")
    if not isinstance(value["sentence"], str) or len(value["sentence"]) > 600:
        raise _bad_learn("sentence")
    apps = value["apps"]
    if not isinstance(apps, list) or len(apps) > 20:
        raise _bad_learn("apps")
    for app in apps:
        if not isinstance(app, dict) or set(app) != LEARN_APP_KEYS or not _short_text(app["package"], 200) or not _short_text(app["label"], 60):
            raise _bad_learn("app")
        if not all(_is_int(app[key]) for key in ("screens", "newScreens", "controls", "transitions")):
            raise _bad_learn("counts")
    refusal = value["refusal"]
    if value["learned"]:
        if refusal is not None or not value["sentence"]:
            raise _bad_learn("learned")
    elif (not isinstance(refusal, dict) or set(refusal) != {"code", "message"} or refusal["code"] not in LEARN_REFUSALS
          or not _short_text(refusal["message"], 200) or apps):
        raise _bad_learn("refusal")


SKILL_ID = re.compile(r"^you\.[a-f0-9]{12}$")
SKILL_GROUNDS = frozenset({"grounded", "partial", "needs-recheck", "not-grounded"})
SKILL_KEYS = frozenset({"skillId", "name", "placeId", "ground", "detail", "routeMoves", "route", "finishSteps", "savedAt"})


DICT_SET_ID = re.compile(r"^set:[\w]{1,40}$")
DICT_ACTIONS = frozenset({"confirm", "reject", "lock", "unlock", "rename", "merge", "unmerge", "move", "kind", "app_word", "mine"})
DICT_REVIEW_ID = re.compile(r"^rv:[0-9a-f]{16}$")
DICT_ROOM = re.compile(r"^screen:[a-z_]{1,20}:[0-9a-f]{16}$")
DICT_EDGE = re.compile(r"^edge:[0-9a-f]{16,64}$")
DICT_STATUSES = frozenset({"candidate", "confirmed", "locked", "rejected", "merged", "retired"})
DICT_TOP_KEYS = frozenset({"placeId", "appLabel", "currentVersion", "passes", "updatedAt", "coreKinds", "entries", "truncated",
                           "audit", "health", "jev", "glossary", "screens", "doors", "review"})
DICT_SCREEN_KEYS = frozenset({"roomKey", "name", "title", "category", "via", "panelOf", "items", "sets", "seen", "purpose", "list"})
DICT_LIST_KEYS = frozenset({"shape", "order", "groups", "searchable", "searchLabel"})
LIST_ORDERS = frozenset({"newest_first", "oldest_first", "a_z"})
ABILITY_ID = re.compile(r"^ab:[0-9a-f]{12}$")
ABILITY_KINDS = frozenset({"open", "panel", "switch", "offer", "control", "find"})
ABILITY_EFFECTS = frozenset({"navigate", "reveal", "switch", "choose", "asks"})
ABILITY_KEYS = frozenset({"id", "kind", "name", "place", "placeName", "path", "tap", "pick", "effect", "setId", "provenance",
                          "confidence", "note", "say"})
MANUAL_TOP_KEYS = frozenset({"placeId", "appLabel", "currentVersion", "abilities", "truncated", "query", "hits", "clear", "quiz",
                             "scores", "markdown"})
MANUAL_SCORE_KEYS = frozenset({"map", "dictionary", "quiz", "walks", "places", "named", "panels", "lists", "ordered", "abilities",
                               "walkedAbilities"})
MAX_MANUAL_QUERY = 200
DICT_DOOR_KEYS = frozenset({"edgeId", "from", "to", "label", "kind"})
DICT_REVIEW_KEYS = frozenset({"id", "name", "screenTitle", "siblings", "seenAt"})
DICT_ENTRY_KEYS = frozenset({"id", "kind", "name", "shownName", "nameProof", "aliases", "parentId", "path", "status", "redirectTo",
                             "anchors", "markers", "observations", "days", "versions", "missedPasses", "note", "failedGates", "waiting"})
DICT_ANCHOR_KEYS = frozenset({"kind", "roomKey", "screenTitle", "position", "siblings", "groups", "rowShape", "searchable", "searchLabel"})
DESCRIBER_MODEL = re.compile(r"^[A-Za-z0-9._:/~-]{1,200}$")


def _bad_dictionary(message: str) -> DesktopRuntimeError:
    return DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, f"Phone dictionary response is invalid: {message}.")


def _short_list(value: Any, limit: int, item_limit: int = 80) -> bool:
    return isinstance(value, list) and len(value) <= limit and all(isinstance(v, str) and len(v) <= item_limit for v in value)


def _validate_dictionary_response(value: dict[str, Any], args: dict[str, Any]) -> None:
    """The app dictionary (plan 36 §7): structure only. Set names, anchors, ids and counts; never members or content."""
    if set(value) != DICT_TOP_KEYS:
        raise _bad_dictionary("keys")
    if value["placeId"] != args.get("placeId"):
        raise _bad_dictionary("place")
    entries = value["entries"]
    if not isinstance(entries, list) or len(entries) > 400 or not isinstance(value["truncated"], bool):
        raise _bad_dictionary("entries")
    for entry in entries:
        if not isinstance(entry, dict) or set(entry) != DICT_ENTRY_KEYS:
            raise _bad_dictionary("entry keys")
        if not isinstance(entry["id"], str) or not DICT_SET_ID.match(entry["id"]) or entry["status"] not in DICT_STATUSES:
            raise _bad_dictionary("entry id")
        if not _short_text(entry["name"], 60) or not _short_text(entry["shownName"], 60) or not _short_text(entry["path"], 200):
            raise _bad_dictionary("entry name")
        if not _short_list(entry["aliases"], 8, 60) or not _short_list(entry["markers"], 6, 60) or not _short_list(entry["versions"], 8, 40):
            raise _bad_dictionary("entry lists")
        if not _is_int(entry["observations"]) or not _is_int(entry["days"]) or not _is_int(entry["missedPasses"]):
            raise _bad_dictionary("entry counts")
        anchors = entry["anchors"]
        if not isinstance(anchors, list) or len(anchors) > 6:
            raise _bad_dictionary("anchors")
        for anchor in anchors:
            if not isinstance(anchor, dict) or set(anchor) != DICT_ANCHOR_KEYS or anchor["kind"] not in {"list", "view"}:
                raise _bad_dictionary("anchor keys")
            if not _short_list(anchor["siblings"], 11, 60) or not _short_list(anchor["groups"], 12, 60):
                raise _bad_dictionary("anchor lists")
    if not isinstance(value["audit"], list) or len(value["audit"]) > 50:
        raise _bad_dictionary("audit")
    if not isinstance(value["glossary"], str) or len(value["glossary"]) > 6000:
        raise _bad_dictionary("glossary")
    if not isinstance(value["health"], dict) or not isinstance(value["jev"], dict):
        raise _bad_dictionary("health")
    screens, doors, review = value["screens"], value["doors"], value["review"]
    if not isinstance(screens, list) or len(screens) > 300 or not isinstance(doors, list) or len(doors) > 600 \
            or not isinstance(review, list) or len(review) > 20:
        raise _bad_dictionary("screens")
    for screen in screens:
        if not isinstance(screen, dict) or set(screen) != DICT_SCREEN_KEYS or not DICT_ROOM.match(str(screen["roomKey"])):
            raise _bad_dictionary("screen keys")
        for key in ("name", "title", "category", "via"):
            if not _short_text(screen[key], 60, nullable=True):
                raise _bad_dictionary("screen name")
        if screen["panelOf"] is not None and not DICT_ROOM.match(str(screen["panelOf"])):
            raise _bad_dictionary("screen panel")
        if not _short_list(screen["items"], 12, 60) or not _short_list(screen["sets"], 12, 48) or not _is_int(screen["seen"]):
            raise _bad_dictionary("screen lists")
        if not _short_text(screen["purpose"], 120, nullable=True) or not _valid_list_note(screen["list"]):
            raise _bad_dictionary("screen purpose")
    for door in doors:
        if not isinstance(door, dict) or set(door) != DICT_DOOR_KEYS or not DICT_EDGE.match(str(door["edgeId"])):
            raise _bad_dictionary("door keys")
        if not DICT_ROOM.match(str(door["from"])) or not DICT_ROOM.match(str(door["to"])) or not _short_text(door["label"], 60, nullable=True) \
                or not _short_text(door["kind"], 24):
            raise _bad_dictionary("door")
    for item in review:
        if not isinstance(item, dict) or set(item) != DICT_REVIEW_KEYS or not DICT_REVIEW_ID.match(str(item["id"])):
            raise _bad_dictionary("review keys")
        if not _short_text(item["name"], 60) or not _short_text(item["screenTitle"], 60, nullable=True) or not _short_list(item["siblings"], 11, 60):
            raise _bad_dictionary("review")


def _valid_list_note(note: Any) -> bool:
    """A list as the manual keeps it: row shape, order, the app's section headers and its search. Never a row."""
    if note is None:
        return True
    return isinstance(note, dict) and set(note) == DICT_LIST_KEYS and _short_text(note["shape"], 40) \
        and (note["order"] is None or note["order"] in LIST_ORDERS) and _short_list(note["groups"], 12, 60) \
        and isinstance(note["searchable"], bool) and _short_text(note["searchLabel"], 60, nullable=True)


def _score(value: Any) -> bool:
    return value is None or (isinstance(value, (int, float)) and not isinstance(value, bool) and 0 <= value <= 1)


def _validate_manual_response(value: dict[str, Any], args: dict[str, Any]) -> None:
    """The App Manual (plan 36 §8): abilities in the app's own words, the self-quiz, scores and Markdown. Never content."""
    if set(value) != MANUAL_TOP_KEYS or value["placeId"] != args.get("placeId"):
        raise _bad_dictionary("manual keys")
    if not _short_text(value["appLabel"], 80) or not _short_text(value["currentVersion"], 40, nullable=True):
        raise _bad_dictionary("manual app")
    abilities = value["abilities"]
    if not isinstance(abilities, list) or len(abilities) > 400 or not isinstance(value["truncated"], bool) or not isinstance(value["clear"], bool):
        raise _bad_dictionary("abilities")
    ids = set()
    for ability in abilities:
        if not isinstance(ability, dict) or set(ability) != ABILITY_KEYS or not ABILITY_ID.match(str(ability["id"])):
            raise _bad_dictionary("ability keys")
        if ability["kind"] not in ABILITY_KINDS or ability["effect"] not in ABILITY_EFFECTS or ability["provenance"] not in {"mapped", "walked"}:
            raise _bad_dictionary("ability kind")
        if not _short_text(ability["name"], 120) or not DICT_ROOM.match(str(ability["place"])) or not _short_list(ability["path"], 10, 60):
            raise _bad_dictionary("ability place")
        for key in ("placeName", "tap", "pick"):
            if not _short_text(ability[key], 60, nullable=True):
                raise _bad_dictionary("ability words")
        if ability["setId"] is not None and not DICT_SET_ID.match(str(ability["setId"])):
            raise _bad_dictionary("ability set")
        if not _score(ability["confidence"]) or ability["confidence"] is None or not _short_text(ability["note"], 200, nullable=True) \
                or not _short_list(ability["say"], 8, 70):
            raise _bad_dictionary("ability details")
        ids.add(ability["id"])
    if not _short_text(value["query"], MAX_MANUAL_QUERY, nullable=True):
        raise _bad_dictionary("query")
    hits = value["hits"]
    if not isinstance(hits, list) or len(hits) > 8 or any(
            not isinstance(h, dict) or set(h) != {"id", "score"} or h["id"] not in ids or not _score(h["score"]) or h["score"] is None for h in hits):
        raise _bad_dictionary("hits")
    quiz = value["quiz"]
    if quiz is not None:
        if not isinstance(quiz, dict) or set(quiz) != {"at", "asked", "answered", "goals"} or not _is_int(quiz["asked"]) \
                or not _is_int(quiz["answered"]) or not _is_int(quiz["at"]) or not isinstance(quiz["goals"], list) or len(quiz["goals"]) > 24:
            raise _bad_dictionary("quiz")
        for goal in quiz["goals"]:
            if not isinstance(goal, dict) or set(goal) != {"goal", "abilityId", "score"} or not _short_text(goal["goal"], 90) \
                    or (goal["abilityId"] is not None and not ABILITY_ID.match(str(goal["abilityId"]))) or not _score(goal["score"]):
                raise _bad_dictionary("quiz goal")
    scores = value["scores"]
    if not isinstance(scores, dict) or set(scores) != MANUAL_SCORE_KEYS:
        raise _bad_dictionary("scores")
    for key in ("map", "dictionary", "quiz", "walks"):
        if not _score(scores[key]):
            raise _bad_dictionary("score")
    for key in ("places", "named", "panels", "lists", "ordered", "abilities", "walkedAbilities"):
        if not _is_int(scores[key]):
            raise _bad_dictionary("score counts")
    if not isinstance(value["markdown"], str) or len(value["markdown"]) > 60_000:
        raise _bad_dictionary("markdown")


def _validate_models_response(value: dict[str, Any]) -> None:
    """The phone's models for the PC's picker: ids, labels and whether each reads pictures. Never a key."""
    if set(value) != {"active", "models"}:
        raise _bad_dictionary("models keys")
    rows = ([value["active"]] if value["active"] is not None else []) + (value["models"] if isinstance(value["models"], list) else [None])
    if not isinstance(value["models"], list) or len(value["models"]) > 40:
        raise _bad_dictionary("models")
    for row in rows:
        if not isinstance(row, dict) or set(row) != {"id", "label", "vision"} or not isinstance(row["vision"], bool):
            raise _bad_dictionary("model row")
        if not isinstance(row["id"], str) or not DESCRIBER_MODEL.match(row["id"]) or not _short_text(row["label"], 80):
            raise _bad_dictionary("model id")


def _bad_skills(message: str) -> DesktopRuntimeError:
    return DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, f"Phone skills.list response is invalid: {message}.")


def _validate_skills_response(value: dict[str, Any]) -> None:
    """Saved skills: names, structural screen titles, health and counts only; never a goal's inputs or screen content."""
    if set(value) != {"skills", "truncated"} or not isinstance(value["truncated"], bool):
        raise _bad_skills("keys")
    skills = value["skills"]
    if not isinstance(skills, list) or len(skills) > 200:
        raise _bad_skills("skills")
    for skill in skills:
        if not isinstance(skill, dict) or set(skill) != SKILL_KEYS:
            raise _bad_skills("skill keys")
        if not isinstance(skill["skillId"], str) or not SKILL_ID.match(skill["skillId"]) or not _short_text(skill["name"], 80):
            raise _bad_skills("skill id")
        if skill["placeId"] is not None and (not isinstance(skill["placeId"], str) or not KNOWLEDGE_PLACE_ID.match(skill["placeId"])):
            raise _bad_skills("place")
        if skill["ground"] not in SKILL_GROUNDS or not _short_text(skill["detail"], 200):
            raise _bad_skills("ground")
        if skill["routeMoves"] is not None and not _is_int(skill["routeMoves"]):
            raise _bad_skills("moves")
        if not _is_int(skill["finishSteps"]) or (skill["savedAt"] is not None and not _is_int(skill["savedAt"])):
            raise _bad_skills("counts")
        route = skill["route"]
        if not isinstance(route, list) or len(route) > 12 or (route and skill["placeId"] is None):
            raise _bad_skills("route")
        for point in route:
            if not isinstance(point, dict) or set(point) != {"title", "screenId"} or not _short_text(point["title"], 60):
                raise _bad_skills("waypoint")
            if point["screenId"] is not None and (not isinstance(point["screenId"], str) or not SCREEN_ID.fullmatch(point["screenId"])):
                raise _bad_skills("waypoint screen")


# Cyclone Ports (plan 48 run 4): the PC's Port Hub polls the phone's outbox and answers its waits.
PORTS_OPS = frozenset({"ports.poll", "ports.blob", "ports.answer", "ports.file"})
PORTS_ITEM_ID = re.compile(r"^pt_[A-Za-z0-9_-]{6,40}$")
PORTS_RUN_ID = re.compile(r"^[A-Za-z0-9_-]{4,80}$")
PORTS_OUT = frozenset({"run.event", "log.line", "screen.shot", "page.text", "account.fields", "file.out"})
PORTS_IN = frozenset({"code.in", "value.in", "link.in", "file.in"})
PORTS_ANSWER_STATES = frozenset({"delivered", "timed_out", "cancelled", "failed", "empty", "conflict", "off", "unavailable", "refused"})
PORTS_PLACE = re.compile(r"^(?:package:[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+|chrome:https://[a-z0-9-]+(?:\.[a-z0-9-]+)+)$")
PORTS_MAX_ITEMS = 20
PORTS_BLOB_CHUNK = 384 * 1024
PORTS_BLOB_MAX = 4 * 1024 * 1024


def _bad_ports(message: str) -> DesktopRuntimeError:
    return DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, f"Android ports result is malformed: {message}.")


def _validate_ports_blob(value: dict[str, Any]) -> None:
    if set(value) != {"data", "bytes", "done"} or not isinstance(value["done"], bool) or not _is_int(value["bytes"]):
        raise _bad_ports("blob")
    data = value["data"]
    if not isinstance(data, str) or not _B64.match(data) or len(data) > (PORTS_BLOB_CHUNK * 4) // 3 + 4 or value["bytes"] > PORTS_BLOB_MAX:
        raise _bad_ports("blob data")


def _validate_ports_item(item: Any) -> None:
    if not isinstance(item, dict) or item.get("kind") not in ("emit", "await", "cancel"):
        raise _bad_ports("item kind")
    common = {"kind", "id", "runId", "at", "app", "routine", "taskId", "plugin"}
    target = item.get("plugin")
    if target is not None and (not isinstance(target, str) or not re.fullmatch(r"[a-z][a-z0-9-]{1,40}", target)):
        raise _bad_ports("plugin target")
    if not isinstance(item.get("id"), str) or not PORTS_ITEM_ID.match(item["id"]) or not isinstance(item.get("runId"), str) \
            or not PORTS_RUN_ID.match(item["runId"]) or not _is_int(item.get("at")):
        raise _bad_ports("item id")
    if not all(_short_text(item.get(k), 160, nullable=True) for k in ("app", "routine", "taskId")):
        raise _bad_ports("item run")
    kind = item["kind"]
    if kind == "emit":
        port = item.get("port")
        extension = isinstance(port, str) and re.fullmatch(r"x\.[a-z][a-z0-9-]{1,40}\.[a-z][a-z0-9-]{0,40}", port)
        if set(item) - common - {"port", "data", "blob"} or (port not in PORTS_OUT and not extension) or not isinstance(item.get("data"), dict):
            raise _bad_ports("emit")
        if (port == "file.out" or extension) and not target:
            raise _bad_ports("private plugin traffic needs a target")
        if len(json.dumps(item["data"])) > 64 * 1024:
            raise _bad_ports("emit size")
        blob = item.get("blob")
        if blob is not None and (not isinstance(blob, dict) or set(blob) != {"bytes", "mime"} or not _is_int(blob["bytes"], minimum=1)
                                 or blob["bytes"] > PORTS_BLOB_MAX or blob["mime"] not in ("image/png", "image/jpeg", "image/webp")):
            raise _bad_ports("emit blob")
        if (item["port"] in ("screen.shot", "file.out")) != (blob is not None):
            raise _bad_ports("screen.shot blob")
    elif kind == "await":
        if set(item) - common - {"port", "timeoutS", "match", "place"} or item.get("port") not in PORTS_IN or not _is_int(item.get("timeoutS"), minimum=1):
            raise _bad_ports("await")
        match = item.get("match")
        if not isinstance(match, dict) or set(match) - {"ask", "requestId", "output"} or not _short_text(match.get("ask"), 200, nullable=True):
            raise _bad_ports("await match")
        if set(match) - {"ask"} and (not target or item["port"] not in ("value.in", "file.in")):
            raise _bad_ports("structured match needs a targeted value/file wait")
        for key in ("requestId", "output"):
            if key in match and (not isinstance(match[key], str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,80}", match[key])):
                raise _bad_ports("await correlation")
        place = item.get("place")
        if place is not None and (not isinstance(place, str) or not PORTS_PLACE.match(place)):
            raise _bad_ports("await place")
        if item["port"] == "code.in" and place is None:
            raise _bad_ports("a code needs its place")
    else:
        if set(item) - common - {"item", "reason"} or not isinstance(item.get("item"), str) or not PORTS_ITEM_ID.match(item["item"]) \
                or not _short_text(item.get("reason"), 40):
            raise _bad_ports("cancel")


def _validate_ports_response(op: str, value: dict[str, Any]) -> None:
    if op == "ports.poll":
        items = value.get("items")
        if set(value) != {"items"} or not isinstance(items, list) or len(items) > PORTS_MAX_ITEMS:
            raise _bad_ports("poll")
        for item in items:
            _validate_ports_item(item)
        return
    if op == "ports.answer":
        if set(value) != {"handled"} or not isinstance(value["handled"], bool):
            raise _bad_ports("answer")
        return
    if op == "ports.file":
        if set(value) != {"received", "done", "name", "folder"} or not _is_int(value["received"]) or not isinstance(value["done"], bool):
            raise _bad_ports("file")
        return


# Plan 49 (alpha.102): the phone's own numbers for Glass → Numbers. Numbers only: never a text, a sender or a code.
PHONE_NUMBER = re.compile(r"^\+?[0-9]{6,15}$")
NUMBERS_MAX = 8


def _validate_numbers_list(value: dict[str, Any]) -> None:
    def bad(what: str) -> DesktopRuntimeError:
        return DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, f"Android numbers.list result is malformed: {what}.")
    if set(value) != {"enabled", "canRead", "numbers"} or not isinstance(value["enabled"], bool) or not isinstance(value["canRead"], bool):
        raise bad("shape")
    rows = value["numbers"]
    if not isinstance(rows, list) or len(rows) > NUMBERS_MAX:
        raise bad("numbers")
    for row in rows:
        if not isinstance(row, dict) or set(row) != {"number", "source", "slot"}:
            raise bad("row")
        if not isinstance(row["number"], str) or not PHONE_NUMBER.match(row["number"]) or row["source"] not in ("sim", "confirmed"):
            raise bad("number")
        if row["slot"] is not None and not _is_int(row["slot"]):
            raise bad("slot")


CC_OPS = frozenset({"cc.start", "cc.status", "cc.answer", "cc.key", "cc.media"})
CC_MEDIA_MIME = re.compile(r"^(video|image|audio)/[a-z0-9.+-]{1,60}$")
CC_MEDIA_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$")
CC_MEDIA_MAX = 500 * 1024 * 1024
CC_MEDIA_CHUNK = 512 * 1024
CC_LEASE_ID = re.compile(r"^ls_[A-Za-z0-9_-]{12,40}$")
CC_TASK_ID = re.compile(r"^tsk_[A-Za-z0-9_-]{6,40}$")
CC_LEASE_STATES = frozenset({"delivered", "used", "failed", "expired", "unused", "unknown"})
CC_SEALED_SLOTS = frozenset({"password", "otp"})
_B64 = re.compile(r"^[A-Za-z0-9+/]+={0,2}$")
CC_ANSWERS = frozenset({"approve", "decline", "reply", "fill", "stop"})
CC_REQUEST_ID = re.compile(r"^[A-Za-z0-9._:-]{1,120}$")
CC_STATUS_KEYS = frozenset({"missionId", "status", "live", "turns", "workingMs", "costUsd", "summary", "moment", "leases"})
CC_MOMENT_KEYS = frozenset({"kind", "requestId", "text", "gate", "send", "choices", "fields", "approvableHere"})


def _bad_cc(message: str) -> DesktopRuntimeError:
    return DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, f"Android Command Center result is malformed: {message}.")


def _validate_cc_response(op: str, value: dict[str, Any], args: dict[str, Any]) -> None:
    """Plan 33 (C0): a task's start, status and answers. Bounded text only; a moment never carries a typed value."""
    if op == "cc.start":
        if set(value) != {"accepted", "missionId"} or value["accepted"] is not True or not LAB_MISSION_ID.match(str(value["missionId"])):
            raise _bad_cc("start")
        return
    if op == "cc.key":
        if set(value) != {"publicKey", "fingerprint", "strongBox", "suite"} or not isinstance(value["strongBox"], bool):
            raise _bad_cc("key")
        key = value["publicKey"]
        if not isinstance(key, str) or not _B64.match(key) or len(base64.b64decode(key)) != 65 or base64.b64decode(key)[0] != 4:
            raise _bad_cc("key shape")
        if not isinstance(value["fingerprint"], str) or not re.fullmatch(r"(?:[0-9A-F]{4} ){7}[0-9A-F]{4}", value["fingerprint"]):
            raise _bad_cc("fingerprint")
        if value["suite"] != "DHKEM(P-256,HKDF-SHA256)/HKDF-SHA256/AES-256-GCM":
            raise _bad_cc("suite")
        return
    if op == "cc.media":
        if set(value) != {"received", "done", "name", "folder"} or not _is_int(value["received"]) or not isinstance(value["done"], bool):
            raise _bad_cc("media")
        if value["received"] > args.get("size", 0) or not _short_text(value["name"], 100, nullable=True) or not _short_text(value["folder"], 60, nullable=True):
            raise _bad_cc("media facts")
        return
    if op == "cc.answer":
        if set(value) != {"handled", "detail"} or not isinstance(value["handled"], bool) or not _short_text(value["detail"], 200):
            raise _bad_cc("answer")
        return
    if set(value) - {"setup"} != CC_STATUS_KEYS or value["missionId"] != args.get("missionId") or value["status"] not in LAB_STATUSES:
        raise _bad_cc("status")
    if "setup" in value:
        _validate_setup(value["setup"])
    if not isinstance(value["live"], bool) or not _is_int(value["turns"]) or not _is_int(value["workingMs"]):
        raise _bad_cc("status counters")
    if not isinstance(value["costUsd"], (int, float)) or isinstance(value["costUsd"], bool) or not _short_text(value["summary"], 600):
        raise _bad_cc("status facts")
    leases = value["leases"]
    if not isinstance(leases, list) or len(leases) > 4 or not all(
        isinstance(l, dict) and set(l) == {"leaseId", "state"} and CC_LEASE_ID.match(str(l["leaseId"])) and l["state"] in CC_LEASE_STATES
        for l in leases
    ):
        raise _bad_cc("leases")
    moment = value["moment"]
    if moment is None:
        return
    if not isinstance(moment, dict) or set(moment) != CC_MOMENT_KEYS or moment["kind"] not in LAB_MOMENT_KINDS:
        raise _bad_cc("moment")
    if moment["requestId"] is not None and (not isinstance(moment["requestId"], str) or not CC_REQUEST_ID.match(moment["requestId"])):
        raise _bad_cc("moment request")
    if not _short_text(moment["text"], 1000) or not _short_text(moment["gate"], 40, nullable=True):
        raise _bad_cc("moment text")
    if not isinstance(moment["approvableHere"], bool) or not _text_list(moment["choices"], 80, 6):
        raise _bad_cc("moment choices")
    send = moment["send"]
    if send is not None and (
        not isinstance(send, dict) or set(send) != {"text", "recipient", "app"}
        or not _short_text(send["text"], 1000) or not _short_text(send["recipient"], 200) or not _short_text(send["app"], 120)
    ):
        raise _bad_cc("moment send")
    fields = moment["fields"]
    if not isinstance(fields, list) or len(fields) > 8 or not all(
        isinstance(f, dict) and set(f) == {"label", "kind"} and _short_text(f["label"], 60) and _short_text(f["kind"], 20) for f in fields
    ):
        raise _bad_cc("moment fields")


# "code" (plan 49): waiting for a code sent by text to the phone itself, which Cyclone fills.
SETUP_STATES = frozenset({"filling", "verification", "code", "created", "failed"})


def _validate_setup(setup: Any) -> None:
    """Plan 43 T7: where an Account Setup run is: its page of the map, a page that changed, and the handle once made."""
    if not isinstance(setup, dict) or set(setup) != {"state", "page", "pages", "drift", "handle", "note"} or setup["state"] not in SETUP_STATES:
        raise _bad_cc("setup")
    if not _is_int(setup["pages"]) or not 0 <= setup["pages"] <= 15:
        raise _bad_cc("setup pages")
    for key in ("page", "drift"):
        if setup[key] is not None and (not _is_int(setup[key]) or not 1 <= setup[key] <= 15):
            raise _bad_cc("setup page")
    if not _short_text(setup["handle"], 100, nullable=True) or not _short_text(setup["note"], 200):
        raise _bad_cc("setup text")


def _setup_values(values: Any) -> dict[str, str]:
    """A row's values for an Account Setup run: field key -> text, as the owner filled them. Never a password."""
    if not isinstance(values, dict) or len(values) > 40:
        raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "An Account Setup run takes at most 40 values.")
    out: dict[str, str] = {}
    for key, value in values.items():
        if not isinstance(key, str) or not SIGNUP_KEY.match(key) or not isinstance(value, str) or len(value) > 300 \
                or any(ord(c) < 32 for c in value):
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "An Account Setup value is field key -> text of at most 300 characters.")
        out[key] = value
    return out


PROFILE_OPS = frozenset({"profiles.list", "profiles.apps", "profiles.switch", "profiles.app"})
PROFILE_ID = re.compile(r"^(main|Cyclone_[a-f0-9]{16})$")
PROFILE_COLOR = re.compile(r"^#[0-9A-F]{8}$")


def _profile_id(value: Any) -> str:
    if not isinstance(value, str) or not PROFILE_ID.match(value):
        raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "profileId is main or a Cyclone profile id.")
    return value


def _bad_profiles(message: str) -> DesktopRuntimeError:
    return DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, f"Android profiles result is malformed: {message}.")


def _validate_profiles_response(op: str, value: dict[str, Any], args: dict[str, Any]) -> None:
    """Plan 43 T4: profiles (labels, looks, which is in front) and a profile's apps (packages and labels only)."""
    if op == "profiles.list":
        if set(value) != {"profiles", "current"} or not isinstance(value["profiles"], list) or len(value["profiles"]) > 20:
            raise _bad_profiles("list")
        for p in value["profiles"]:
            if not isinstance(p, dict) or set(p) != {"id", "label", "emoji", "color", "ready", "current", "inTrash"}:
                raise _bad_profiles("profile keys")
            if not PROFILE_ID.match(str(p["id"])) or not _short_text(p["label"], 40) or not _short_text(p["emoji"], 8, nullable=True):
                raise _bad_profiles("profile identity")
            if p["color"] is not None and not (isinstance(p["color"], str) and PROFILE_COLOR.match(p["color"])):
                raise _bad_profiles("profile colour")
            if not all(isinstance(p[k], bool) for k in ("ready", "current", "inTrash")):
                raise _bad_profiles("profile facts")
        if value["current"] is not None and not PROFILE_ID.match(str(value["current"])):
            raise _bad_profiles("current")
        return
    if op == "profiles.apps":
        if set(value) != {"apps", "available", "truncated"} or not isinstance(value["truncated"], bool):
            raise _bad_profiles("apps")
        for key in ("apps", "available"):
            items = value[key]
            if not isinstance(items, list) or len(items) > 500 or not all(
                isinstance(a, dict) and set(a) == {"package", "label"} and SIGNUP_PACKAGE.match(str(a["package"])) and _short_text(a["label"], 80)
                for a in items
            ):
                raise _bad_profiles(key)
        return
    if op == "profiles.switch":
        if set(value) != {"switched", "current"} or value["switched"] is not True or value["current"] != args.get("profileId"):
            raise _bad_profiles("switch")
        return
    if set(value) != {"done"} or value["done"] is not True:
        raise _bad_profiles("app")


SIGNUP_OPS = frozenset({"signup.maps", "signup.forget"})
SIGNUP_PACKAGE = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$")
SIGNUP_KINDS = frozenset({"text", "first_name", "last_name", "full_name", "email", "phone", "username", "password", "birthday", "date",
                          "gender", "choice", "checkbox", "number", "photo"})
SIGNUP_CHECKS = frozenset({"email_code", "sms_code", "captcha", "selfie", "id_document", "phone_call", "other"})
SIGNUP_KEY = re.compile(r"^[a-z0-9_]{1,48}$")
#: A map is a template: anything that looks like a typed value (an address, a long number, a secret) is refused.
SIGNUP_VALUE_LIKE = re.compile(r"@[^\s]+\.[a-z]{2,}|\d{5,}|\+\d{6,}", re.I)


def _bad_signup(message: str) -> DesktopRuntimeError:
    return DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, f"Android sign-up result is malformed: {message}.")


def _signup_text(value: Any, limit: int, *, empty: bool = False) -> bool:
    return (isinstance(value, str) and (empty or value.strip() != "") and len(value) <= limit and not SIGNUP_VALUE_LIKE.search(value)
            and not INLINE_SECRET.search(value) and not any(ord(c) < 32 for c in value))


def validate_signup_map(item: Any) -> None:
    """Plan 43 T6: one sign-up map, a schema only: pages, field labels and kinds, format hints, choices and checks."""
    keys = {"package", "app", "appVersion", "mappedAt", "finalLabel", "complete", "pages"}
    if not isinstance(item, dict) or set(item) != keys:
        raise _bad_signup("map keys")
    if not SIGNUP_PACKAGE.match(str(item["package"])) or not _short_text(item["app"], 80) or not _short_text(item["appVersion"], 64):
        raise _bad_signup("map identity")
    if not _is_int(item["mappedAt"]) or not isinstance(item["complete"], bool):
        raise _bad_signup("map facts")
    if item["finalLabel"] is not None and not _signup_text(item["finalLabel"], 60):
        raise _bad_signup("final control")
    pages = item["pages"]
    if not isinstance(pages, list) or not 1 <= len(pages) <= 15:
        raise _bad_signup("pages")
    seen: set[str] = set()
    for index, page in enumerate(pages, start=1):
        if not isinstance(page, dict) or set(page) != {"index", "title", "continue", "check", "fields"} or page["index"] != index:
            raise _bad_signup("page")
        if not _signup_text(page["title"], 80) or not _signup_text(page["continue"], 60):
            raise _bad_signup("page text")
        if page["check"] is not None and page["check"] not in SIGNUP_CHECKS:
            raise _bad_signup("page check")
        fields = page["fields"]
        if not isinstance(fields, list) or len(fields) > 20:
            raise _bad_signup("fields")
        for field in fields:
            if not isinstance(field, dict) or set(field) != {"key", "label", "kind", "required", "hint", "choices"}:
                raise _bad_signup("field keys")
            if not SIGNUP_KEY.match(str(field["key"])) or field["key"] in seen:
                raise _bad_signup("field key")
            seen.add(field["key"])
            if field["kind"] not in SIGNUP_KINDS or not isinstance(field["required"], bool):
                raise _bad_signup("field kind")
            if not _signup_text(field["label"], 60) or not _signup_text(field["hint"], 120, empty=True):
                raise _bad_signup("field text")
            choices = field["choices"]
            if not isinstance(choices, list) or len(choices) > 40 or not all(_signup_text(c, 60) for c in choices):
                raise _bad_signup("field choices")


def _validate_signup_response(op: str, value: dict[str, Any], args: dict[str, Any]) -> None:
    if op == "signup.forget":
        if set(value) != {"forgotten"} or not isinstance(value["forgotten"], bool):
            raise _bad_signup("forget")
        return
    if set(value) != {"maps", "truncated"} or not isinstance(value["maps"], list) or not isinstance(value["truncated"], bool):
        raise _bad_signup("maps")
    if len(value["maps"]) > 200:
        raise _bad_signup("too many maps")
    for item in value["maps"]:
        validate_signup_map(item)


MARKET_OPS = frozenset({"market.catalog", "market.install", "market.remove", "market.run"})
MARKET_LISTING_ID = re.compile(r"^[a-z0-9][a-z0-9.-]{2,63}$")
MARKET_LISTING_KEYS = frozenset({
    "id", "kind", "version", "name", "publisher", "summary", "category", "glyph", "goal", "inputs", "apps", "does",
    "asksFirst", "suggestFor", "featured", "added", "savedInputs", "runs", "lastRunAt",
})
MARKET_CONNECTION_STATES = frozenset({"connected", "needs_setup", "off"})


def _bad_market(message: str) -> DesktopRuntimeError:
    return DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, f"Android marketplace result is malformed: {message}.")


def _text_list(value: Any, limit: int, size: int) -> bool:
    return isinstance(value, list) and len(value) <= size and all(_short_text(item, limit) for item in value)


def _validate_market_response(op: str, value: dict[str, Any], args: dict[str, Any]) -> None:
    """Listings are data the phone shows; nothing in them may carry a secret, a command or unbounded text."""
    if op == "market.catalog":
        if set(value) != {"listings", "suggestions", "installedCount", "connections"}:
            raise _bad_market("catalog")
        listings = value["listings"]
        if not isinstance(listings, list) or len(listings) > 500:
            raise _bad_market("listings")
        for item in listings:
            if not isinstance(item, dict) or set(item) != MARKET_LISTING_KEYS or not MARKET_LISTING_ID.match(str(item.get("id"))):
                raise _bad_market("listing")
            if item["kind"] not in {"recipe", "connection"} or not isinstance(item["added"], bool) or not _is_int(item["runs"]):
                raise _bad_market("listing kind")
            for key, limit in (("name", 60), ("summary", 160), ("category", 40), ("glyph", 8), ("goal", 600), ("version", 20)):
                if not _short_text(item[key], limit):
                    raise _bad_market(key)
            for key in ("apps", "does", "asksFirst", "suggestFor"):
                if not _text_list(item[key], 160, 20):
                    raise _bad_market(key)
            if not isinstance(item["inputs"], list) or len(item["inputs"]) > 12:
                raise _bad_market("inputs")
            saved = item["savedInputs"]
            if saved is not None and (not isinstance(saved, dict) or not all(_short_text(v, 200) for v in saved.values())):
                raise _bad_market("saved inputs")
        if not isinstance(value["suggestions"], list) or not all(
            isinstance(s, dict) and set(s) == {"id", "reason"} and _short_text(s["reason"], 120) for s in value["suggestions"]
        ):
            raise _bad_market("suggestions")
        if not _is_int(value["installedCount"]):
            raise _bad_market("count")
        connections = value["connections"]
        if not isinstance(connections, list) or len(connections) > 20:
            raise _bad_market("connections")
        for item in connections:
            if (not isinstance(item, dict) or set(item) != {"id", "name", "glyph", "state", "detail", "where"}
                    or item["state"] not in MARKET_CONNECTION_STATES or not _short_text(item["detail"], 160)):
                raise _bad_market("connection")
        return
    if value.get("id") != args.get("id"):
        raise _bad_market("id")
    if op == "market.install":
        if set(value) != {"id", "added", "inputs"} or value["added"] is not True or not isinstance(value["inputs"], dict):
            raise _bad_market("install")
    elif op == "market.remove":
        if set(value) != {"id", "removed"} or not isinstance(value["removed"], bool):
            raise _bad_market("remove")
    elif set(value) != {"id", "started"} or value["started"] is not True:
        raise _bad_market("run")


SHARE_PHONE_ID = re.compile(r"^[A-Za-z0-9_-]{16,128}$")
SHARE_PROTOCOL = "cyclone-lan-share-v1"


def _validate_share_status(value: dict[str, Any]) -> None:
    """Wi-Fi share facts: sharing flag, a port and private IPv4 addresses only (never public or loopback)."""
    if set(value) != {"sharing", "port", "addresses", "phoneId", "protocol"} or not isinstance(value["sharing"], bool):
        raise _bad_knowledge("share.status")
    if value["protocol"] != SHARE_PROTOCOL or not isinstance(value["phoneId"], str) or (value["phoneId"] and not SHARE_PHONE_ID.match(value["phoneId"])):
        raise _bad_knowledge("share protocol")
    addresses = value["addresses"]
    if not isinstance(addresses, list) or len(addresses) > 4:
        raise _bad_knowledge("share addresses")
    for address in addresses:
        try:
            ip = ipaddress.IPv4Address(address) if isinstance(address, str) else None
        except ipaddress.AddressValueError:
            ip = None
        if ip is None or not ip.is_private or ip.is_loopback:
            raise _bad_knowledge("share address")
    if value["sharing"]:
        if not _is_int(value["port"], minimum=1) or value["port"] > 65535 or not addresses:
            raise _bad_knowledge("share port")
    elif value["port"] is not None or addresses:
        raise _bad_knowledge("share idle")


class V5ContractService:
    """Constrained PC bridge for Atlas/Vault/mapping metadata operations.

    This service creates no PC-side Atlas, diff journal, mapping state, or Vault truth. Every
    successful result comes from the authenticated Android Gateway.
    """

    PROTOCOL = V5_CONTRACT_PROTOCOL

    def __init__(self, fleet: DeviceFleetManager):
        self.fleet = fleet

    def atlas_places(self, device_id: str) -> dict[str, Any]:
        return self._call(device_id, "atlas.places", {})

    def apps_list(self, device_id: str) -> dict[str, Any]:
        return self._call(device_id, "apps.list", {})

    def runs_list(self, device_id: str, limit: int = 50, run_filter: str = "all") -> dict[str, Any]:
        if not _is_int(limit, minimum=1) or limit > MAX_RUNS or run_filter not in RUN_FILTERS:
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "runs.list takes limit 1..200 and a known filter.")
        return self._call(device_id, "runs.list", {"limit": limit, "filter": run_filter})

    def runs_get(self, device_id: str, run_id: str) -> dict[str, Any]:
        if not isinstance(run_id, str) or not RUN_ID.match(run_id):
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "runId is malformed.")
        return self._call(device_id, "runs.get", {"runId": run_id})

    def runs_mark(self, device_id: str, run_id: str, expected: bool) -> dict[str, Any]:
        if not isinstance(run_id, str) or not RUN_ID.match(run_id) or not isinstance(expected, bool):
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "runs.mark takes a runId and expected true/false.")
        return self._call(device_id, "runs.mark", {"runId": run_id, "expected": expected})

    def atlas_here(self, device_id: str) -> dict[str, Any]:
        return self._call(device_id, "atlas.here", {})

    def share_status(self, device_id: str) -> dict[str, Any]:
        return self._call(device_id, "share.status", {})

    def share_request(self, device_id: str, pc_label: str | None = None) -> dict[str, Any]:
        args = {"pcLabel": pc_label[:60]} if isinstance(pc_label, str) and pc_label.strip() else {}
        return self._call(device_id, "share.request", args)

    def knowledge_summary(self, device_id: str) -> dict[str, Any]:
        return self._call(device_id, "knowledge.get", {})

    def atlas_versions(self, device_id: str, place_id: str) -> dict[str, Any]:
        if not isinstance(place_id, str) or not KNOWLEDGE_PLACE_ID.match(place_id) or len(place_id) > 200:
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "Versions are for apps (package:…).")
        return self._call(device_id, "atlas.versions", {"placeId": place_id})

    def scenarios_list(self, device_id: str, place_id: str, persona: str = "mapping") -> dict[str, Any]:
        if not isinstance(place_id, str) or not KNOWLEDGE_PLACE_ID.match(place_id) or len(place_id) > 200 or persona not in {"live", "mapping"}:
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "Scenarios are for apps (package:…) and a known persona.")
        return self._call(device_id, "scenarios.list", {"placeId": place_id, "persona": persona})

    def atlas_get(self, device_id: str, place_id: str, persona: str) -> dict[str, Any]:
        args = {"placeId": place_id, "persona": persona}
        _validate_place_persona(args)
        return self._call(device_id, "atlas.get", args)

    def atlas_diff(
        self,
        device_id: str,
        place_id: str,
        persona: str,
        since: str | None = None,
    ) -> dict[str, Any]:
        args: dict[str, Any] = {"placeId": place_id, "persona": persona, "since": since}
        _validate_place_persona(args)
        if since is not None and (not isinstance(since, str) or not since or len(since) > 128):
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "since must be a bounded phone-issued cursor.")
        return self._call(device_id, "atlas.diff", args)

    def secret_slots(self, device_id: str, place_id: str, persona: str) -> dict[str, Any]:
        args = {"placeId": place_id, "persona": persona}
        _validate_place_persona(args)
        return self._call(device_id, "secrets.slots", args)

    def secret_request(
        self,
        device_id: str,
        *,
        place_id: str,
        persona: str,
        slot: str,
        reason: str,
        extra: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        args: dict[str, Any] = {
            "placeId": place_id,
            "persona": persona,
            "slot": slot,
            "reason": reason,
        }
        if extra:
            args.update(extra)
        reject_secret_payload(args)
        _validate_place_persona(args)
        if set(args) != {"placeId", "persona", "slot", "reason"}:
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "Unexpected secrets.request field.")
        if not isinstance(slot, str) or SLOT.fullmatch(slot) is None:
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "slot must be metadata only.")
        if not isinstance(reason, str) or REASON.fullmatch(reason) is None or INLINE_SECRET.search(reason):
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "reason must be a bounded-safe-label.")
        return self._call(device_id, "secrets.request", args)

    def lab_start(self, device_id: str, goal: str, run_id: str, variant: dict[str, Any]) -> dict[str, Any]:
        if not isinstance(goal, str) or not goal.strip() or len(goal) > 2000 or INLINE_SECRET.search(goal):
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "A lab goal is 1..2000 characters without secrets.")
        if not isinstance(run_id, str) or not LAB_RUN_ID.match(run_id) or not isinstance(variant, dict):
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "lab.start needs a runId and a variant.")
        return self._call(device_id, "lab.start", {"goal": goal.strip(), "runId": run_id, "variant": variant})

    def lab_status(self, device_id: str, mission_id: str) -> dict[str, Any]:
        return self._call(device_id, "lab.status", {"missionId": _lab_mission(mission_id)})

    def lab_record(self, device_id: str, mission_id: str) -> dict[str, Any]:
        return self._call(device_id, "lab.record", {"missionId": _lab_mission(mission_id)})

    def lab_answer(self, device_id: str, mission_id: str, action: str, *, text: str | None = None,
                   values: dict[str, str] | None = None) -> dict[str, Any]:
        """The lab plays the owner: reply, fill, decline, stop or steer (plan 38: change the task mid-run). It never
        approves and never sends a secret."""
        if action not in LAB_ANSWERS:
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "The lab can reply, fill, decline, stop or steer; it never approves.")
        args: dict[str, Any] = {"missionId": _lab_mission(mission_id), "action": action}
        if action in {"reply", "steer"}:
            if not isinstance(text, str) or not text.strip() or len(text) > 500 or INLINE_SECRET.search(text):
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "A lab reply is 1..500 characters without secrets.")
            args["text"] = text.strip()
        if action == "fill":
            if not isinstance(values, dict) or not 1 <= len(values) <= 8 or not all(
                isinstance(k, str) and isinstance(v, str) and len(k) <= 60 and len(v) <= 300 for k, v in values.items()
            ):
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "A lab fill is 1..8 short text values.")
            args["values"] = values
        return self._call(device_id, "lab.answer", args)

    def learn_run(self, device_id: str, run_id: str) -> dict[str, Any]:
        """Learn everything one run saw and did, on the phone. Returns counts only."""
        if not isinstance(run_id, str) or not RUN_ID.match(run_id):
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "runId is malformed.")
        return self._call(device_id, "learn.run", {"runId": run_id})

    def skills_list(self, device_id: str) -> dict[str, Any]:
        """The owner's saved skills and where each lives on the map (plan 23). Titles, health and counts only."""
        return self._call(device_id, "skills.list", {})

    def dictionary_get(self, device_id: str, place_id: str) -> dict[str, Any]:
        """An app's dictionary (plan 36 §7): its sets, where they live, the organizer's audit and health."""
        if not isinstance(place_id, str) or not KNOWLEDGE_PLACE_ID.match(place_id):
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "placeId must be package:<app>.")
        return self._call(device_id, "dictionary.get", {"placeId": place_id})

    def dictionary_edit(self, device_id: str, body: dict[str, Any]) -> dict[str, Any]:
        """The owner's change to a set, from Glass only. The phone applies the organizer's rules."""
        allowed = {"placeId", "action", "id", "into", "label", "parentId", "kind"}
        if not isinstance(body, dict) or not set(body) <= allowed:
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "dictionary edit has an unexpected field.")
        if not isinstance(body.get("placeId"), str) or not KNOWLEDGE_PLACE_ID.match(body["placeId"]):
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "placeId must be package:<app>.")
        if body.get("action") not in DICT_ACTIONS:
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "Unknown dictionary action.")
        if body["action"] in {"app_word", "mine"}:
            # The owner's "app word or yours?" answer names a review item, never a set.
            if set(body) != {"placeId", "action", "id"} or not isinstance(body.get("id"), str) or not DICT_REVIEW_ID.match(body["id"]):
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "id must be a review id.")
            return self._call(device_id, "dictionary.edit", dict(body))
        for key in ("id", "into"):
            if key in body and (not isinstance(body[key], str) or not DICT_SET_ID.match(body[key])):
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, f"{key} must be a set id.")
        if "parentId" in body and body["parentId"] is not None and (not isinstance(body["parentId"], str) or not DICT_SET_ID.match(body["parentId"])):
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "parentId must be a set id or null.")
        if "label" in body and not _short_text(body["label"], 60):
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "label must be short text.")
        if "kind" in body and (not isinstance(body["kind"], str) or not re.fullmatch(r"[a-z_]{2,20}", body["kind"])):
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "kind is malformed.")
        return self._call(device_id, "dictionary.edit", dict(body))

    def models_list(self, device_id: str) -> dict[str, Any]:
        """The phone's models for the mapping start sheet's picker. The key stays on the phone."""
        return self._call(device_id, "models.list", {})

    def numbers_list(self, device_id: str) -> dict[str, Any]:
        """Plan 49: this phone's numbers (each SIM's and the owner's confirmed ones) and whether codes from texts are on."""
        return self._call(device_id, "numbers.list", {})

    def signup_maps(self, device_id: str) -> dict[str, Any]:
        """Plan 43 T6: the sign-up maps the phone learned. Schemas only; every string is checked for values."""
        return self._call(device_id, "signup.maps", {})

    def signup_forget(self, device_id: str, package: str) -> dict[str, Any]:
        if not isinstance(package, str) or not SIGNUP_PACKAGE.match(package):
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "package is an Android package name.")
        return self._call(device_id, "signup.forget", {"package": package})

    # Plan 43 T4: the phone's profiles.

    def profiles_list(self, device_id: str) -> dict[str, Any]:
        return self._call(device_id, "profiles.list", {})

    def profiles_apps(self, device_id: str, profile_id: str) -> dict[str, Any]:
        return self._call(device_id, "profiles.apps", {"profileId": _profile_id(profile_id)})

    def profiles_switch(self, device_id: str, profile_id: str) -> dict[str, Any]:
        return self._call(device_id, "profiles.switch", {"profileId": _profile_id(profile_id)})

    def profiles_app(self, device_id: str, profile_id: str, package: str, action: str) -> dict[str, Any]:
        if _profile_id(profile_id) == "main":
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "Profile A's apps are managed on the phone.")
        if not isinstance(package, str) or not SIGNUP_PACKAGE.match(package) or package == "com.cyclone.mobile":
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "package is an Android package name, not Cyclone itself.")
        if action not in ("install", "remove"):
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "action is install or remove.")
        return self._call(device_id, "profiles.app", {"profileId": profile_id, "package": package, "action": action})

    def manual_get(self, device_id: str, place_id: str, query: str | None = None) -> dict[str, Any]:
        """An app's manual (plan 36 §8): abilities with their paths, the self-quiz, scores and the manual as Markdown."""
        if not isinstance(place_id, str) or not KNOWLEDGE_PLACE_ID.match(place_id):
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "placeId must be package:<app>.")
        args: dict[str, Any] = {"placeId": place_id}
        if query is not None:
            if not isinstance(query, str) or len(query) > MAX_MANUAL_QUERY:
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "query must be at most 200 characters.")
            if query.strip():
                args["query"] = query.strip()
        return self._call(device_id, "manual.get", args)

    def cc_start(self, device_id: str, goal: str, *, task_id: str | None = None,
                 sealed: list[dict[str, Any]] | None = None, publish: bool = False, signup_map: str | None = None,
                 signup_run: dict[str, Any] | None = None) -> dict[str, Any]:
        """Plan 33 (C0): start an assigned task as an ordinary Mind mission. Goal text only, never a secret.

        C2: [sealed] envelopes (HPKE to the phone's device key, made in the owner's browser) ride along as opaque bytes.
        The gateway cannot open them; it checks their shape only.
        """
        if not isinstance(goal, str) or not goal.strip() or len(goal) > MAX_GOAL or INLINE_SECRET.search(goal):
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "A task goal is 1..2000 characters without secrets.")
        args: dict[str, Any] = {"goal": goal.strip()}
        if signup_map is not None:
            # Plan 43 T6: this task maps the app's sign-up. Nothing else rides along.
            if not isinstance(signup_map, str) or not SIGNUP_PACKAGE.match(signup_map) or sealed or publish:
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "A sign-up mapping task names one app's package, and nothing else.")
            return self._call(device_id, "cc.start", {**args, "signupMap": signup_map})
        if signup_run is not None:
            # Plan 43 T7: create one account with the app's sign-up map as the plan and the row's values; the password
            # rides along sealed (the vault), never as a value.
            if publish or not isinstance(signup_run, dict) or set(signup_run) != {"package", "values"} \
                    or not isinstance(signup_run["package"], str) or not SIGNUP_PACKAGE.match(signup_run["package"]):
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "An Account Setup run names one app's package and its values.")
            args["signupRun"] = {"package": signup_run["package"], "values": _setup_values(signup_run["values"])}
        if publish:
            # C3: this task posts a file; the phone gates its final Share/Post as a send, for this mission.
            if not isinstance(task_id, str) or not CC_TASK_ID.match(task_id):
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "A posting task needs its task id.")
            args.update({"taskId": task_id, "publish": True})
        if sealed:
            if not isinstance(task_id, str) or not CC_TASK_ID.match(task_id) or len(sealed) > 2:
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "Sealed secrets need their task and are at most two.")
            for envelope in sealed:
                if (not isinstance(envelope, dict) or set(envelope) != {"leaseId", "slot", "enc", "ct", "aad"}
                        or not CC_LEASE_ID.match(str(envelope["leaseId"])) or envelope["slot"] not in CC_SEALED_SLOTS
                        or not all(isinstance(envelope[k], str) and _B64.match(envelope[k]) for k in ("enc", "ct"))
                        or not isinstance(envelope["aad"], str) or len(envelope["aad"]) > 1000):
                    raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "A sealed envelope is {leaseId, slot, enc, ct, aad} only.")
            reject_secret_payload(args)
            return self._call(device_id, "cc.start", {**args, "taskId": task_id, "sealed": sealed}, checked=True)
        return self._call(device_id, "cc.start", args)

    def cc_media(self, device_id: str, task_id: str, *, name: str, mime: str, size: int, sha256: str, offset: int,
                 data: bytes) -> dict[str, Any]:
        """Plan 33 (C3): one chunk of a made file (a video or image) for a task. The phone checks the whole file's
        SHA-256 before it adds it to its gallery. Media bytes only; the fields are checked here, the bytes are opaque."""
        if (not isinstance(task_id, str) or not CC_TASK_ID.match(task_id) or not isinstance(name, str) or not CC_MEDIA_NAME.match(name)
                or not isinstance(mime, str) or not CC_MEDIA_MIME.match(mime) or type(size) is not int or not 0 < size <= CC_MEDIA_MAX
                or not isinstance(sha256, str) or not re.fullmatch(r"[0-9a-f]{64}", sha256) or type(offset) is not int
                or not 0 <= offset < size or not isinstance(data, bytes) or not 0 < len(data) <= CC_MEDIA_CHUNK or offset + len(data) > size):
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "A media chunk is {taskId, name, mime, size, sha256, offset, data}.")
        args = {"taskId": task_id, "name": name, "mime": mime, "size": size, "sha256": sha256, "offset": offset,
                "data": base64.b64encode(data).decode()}
        return self._call(device_id, "cc.media", args, checked=True)

    # ---- Cyclone Ports (plan 48 run 4) -----------------------------------------------------------------------------

    def ports_poll(self, device_id: str, ack: list[str], *, drop: bool = False, max_items: int = PORTS_MAX_ITEMS,
                   skills: list[dict[str, Any]] | None = None) -> dict[str, Any]:
        """The phone's outbox: what its runs sent and the waits they opened. [ack] removes items the hub handled."""
        if not isinstance(ack, list) or len(ack) > 100 or not all(isinstance(i, str) and PORTS_ITEM_ID.match(i) for i in ack):
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "ack is a list of port item ids.")
        args: dict[str, Any] = {"ack": ack, "max": max(1, min(int(max_items), PORTS_MAX_ITEMS))}
        if drop:
            args["drop"] = True
        if skills is not None:
            if len(skills) > 8 or len(json.dumps(skills)) > 32000:
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "Ports skill advertisements are too large.")
            args["skills"] = skills
        return self._call(device_id, "ports.poll", args)

    def ports_blob(self, device_id: str, item_id: str, offset: int) -> dict[str, Any]:
        if not isinstance(item_id, str) or not PORTS_ITEM_ID.match(item_id) or type(offset) is not int or offset < 0:
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "A blob read is {id, offset}.")
        return self._call(device_id, "ports.blob", {"id": item_id, "offset": offset})

    def ports_answer(self, device_id: str, item_id: str, state: str, *, reason: str | None = None, plugin: str | None = None,
                     value: Any = None, has_value: bool = False, url: str | None = None, file: dict[str, Any] | None = None,
                     sealed: dict[str, str] | None = None) -> dict[str, Any]:
        """Answers a run's wait. A value or link is screened for secrets like every request (fail closed); a code goes
        only as an envelope sealed to the phone's key, so the PC can relay it but the args carry no readable code."""
        if not isinstance(item_id, str) or not PORTS_ITEM_ID.match(item_id) or state not in PORTS_ANSWER_STATES:
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "An answer names its item and a known state.")
        args: dict[str, Any] = {"id": item_id, "state": state}
        if reason:
            args["reason"] = str(reason)[:200]
        if plugin:
            args["plugin"] = str(plugin)[:80]
        if url is not None:
            if not isinstance(url, str) or not url.startswith("https://") or len(url) > 2000:
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "A link is https.")
            args["url"] = url
        if file is not None:
            args["file"] = file
        if has_value:
            args["value"] = value
        if sealed is None:
            return self._call(device_id, "ports.answer", args)
        if (not isinstance(sealed, dict) or set(sealed) != {"leaseId", "enc", "ct", "aad"} or not CC_LEASE_ID.match(str(sealed["leaseId"]))
                or not all(isinstance(sealed[k], str) and _B64.match(sealed[k]) for k in ("enc", "ct"))
                or not isinstance(sealed["aad"], str) or len(sealed["aad"]) > 600 or has_value or url is not None or file is not None):
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "A sealed code is {leaseId, enc, ct, aad} only.")
        reject_secret_payload(args)
        return self._call(device_id, "ports.answer", {**args, "sealed": sealed}, checked=True)

    def ports_file(self, device_id: str, item_id: str, *, name: str, mime: str, size: int, sha256: str, offset: int,
                   data: bytes) -> dict[str, Any]:
        """One chunk of a file a plugin delivered for a wait (image, video, audio) to the phone's gallery."""
        if (not isinstance(item_id, str) or not PORTS_ITEM_ID.match(item_id) or not isinstance(name, str) or not CC_MEDIA_NAME.match(name)
                or not isinstance(mime, str) or not CC_MEDIA_MIME.match(mime) or type(size) is not int or not 0 < size <= 20 * 1024 * 1024
                or not isinstance(sha256, str) or not re.fullmatch(r"[0-9a-f]{64}", sha256) or type(offset) is not int
                or not 0 <= offset < size or not isinstance(data, bytes) or not 0 < len(data) <= CC_MEDIA_CHUNK or offset + len(data) > size):
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "A file chunk is {id, name, mime, size, sha256, offset, data}.")
        args = {"taskId": item_id, "name": name, "mime": mime, "size": size, "sha256": sha256, "offset": offset,
                "data": base64.b64encode(data).decode()}
        return self._call(device_id, "ports.file", args, checked=True)

    def cc_key(self, device_id: str) -> dict[str, Any]:
        """Plan 33 (C2): the phone's device key (public half and fingerprint) for sealed delivery."""
        return self._call(device_id, "cc.key", {})

    def cc_status(self, device_id: str, mission_id: str) -> dict[str, Any]:
        return self._call(device_id, "cc.status", {"missionId": _lab_mission(mission_id)})

    def cc_answer(self, device_id: str, mission_id: str, action: str, *, request_id: str | None = None,
                  text: str | None = None, values: dict[str, str] | None = None) -> dict[str, Any]:
        """The owner's answer from the Approvals inbox. The phone approves only the request id it showed."""
        if action not in CC_ANSWERS:
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "Unknown answer.")
        args: dict[str, Any] = {"missionId": _lab_mission(mission_id), "action": action}
        if action != "stop":
            if not isinstance(request_id, str) or not CC_REQUEST_ID.match(request_id):
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "requestId is required.")
            args["requestId"] = request_id
        if action == "reply":
            if not isinstance(text, str) or not text.strip() or len(text) > 500 or INLINE_SECRET.search(text):
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "An answer is 1..500 characters without secrets.")
            args["text"] = text.strip()
        if action == "fill":
            if not isinstance(values, dict) or not 1 <= len(values) <= 8 or not all(
                isinstance(k, str) and isinstance(v, str) and len(k) <= 60 and len(v) <= 300 for k, v in values.items()
            ):
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "Details are 1..8 short text values.")
            if any(_secret_name(k) or INLINE_SECRET.search(f"{k}: {v}") for k, v in values.items()):
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "Secrets are typed on the phone, never sent from the PC.")
            args["values"] = values
        return self._call(device_id, "cc.answer", args)

    def market_catalog(self, device_id: str) -> dict[str, Any]:
        return self._call(device_id, "market.catalog", {})

    def market_change(self, device_id: str, op: str, listing_id: str, inputs: dict[str, Any] | None = None) -> dict[str, Any]:
        """Add, remove or run one listing on the phone. The phone validates inputs again and refuses secrets."""
        if op not in {"market.install", "market.remove", "market.run"}:
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "Unknown marketplace change.")
        if not isinstance(listing_id, str) or not MARKET_LISTING_ID.match(listing_id):
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "Listing id is malformed.")
        args: dict[str, Any] = {"id": listing_id}
        if op != "market.remove" and inputs:
            if not isinstance(inputs, dict) or len(inputs) > 12 or not all(
                isinstance(k, str) and len(k) <= 32 and isinstance(v, str) and len(v) <= 200 for k, v in inputs.items()
            ):
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "Inputs are up to 12 short text values.")
            if any(INLINE_SECRET.search(f"{k}: {v}") or _secret_name(k) for k, v in inputs.items()):
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "Secrets never go into a recipe; Cyclone asks on the phone.")
            args["inputs"] = inputs
        return self._call(device_id, op, args)

    def forward(self, device_id: str, op: str, payload: dict[str, Any] | None = None) -> dict[str, Any]:
        """Typed forwarding seam. There is no generic Android-op passthrough or PC mapping truth."""
        if op not in V5_OPS:
            raise DesktopRuntimeError(RuntimeErrorCode.CAPABILITY_UNAVAILABLE, "Unsupported V5 contract operation.")
        args = dict(payload or {})
        reject_secret_payload(args)

        if op == "atlas.places":
            if args:
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "atlas.places takes no arguments.")
            return self.atlas_places(device_id)
        if op == "apps.list":
            if args:
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "apps.list takes no arguments.")
            return self.apps_list(device_id)
        if op == "runs.list":
            if not set(args) <= {"limit", "filter"}:
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "runs.list takes limit and filter only.")
            return self.runs_list(device_id, args.get("limit", 50), args.get("filter", "all"))
        if op == "runs.get":
            if set(args) != {"runId"}:
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "runs.get takes runId only.")
            return self.runs_get(device_id, args["runId"])
        if op == "skills.list":
            if args:
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "skills.list takes no arguments.")
            return self.skills_list(device_id)
        if op == "dictionary.get":
            if set(args) != {"placeId"}:
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "dictionary.get takes placeId only.")
            return self.dictionary_get(device_id, args["placeId"])
        if op == "dictionary.edit":
            return self.dictionary_edit(device_id, args)
        if op == "models.list":
            if args:
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "models.list takes no arguments.")
            return self.models_list(device_id)
        if op == "signup.maps":
            if args:
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "signup.maps takes no arguments.")
            return self.signup_maps(device_id)
        if op == "profiles.list":
            if args:
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "profiles.list takes no arguments.")
            return self.profiles_list(device_id)
        if op in ("profiles.apps", "profiles.switch"):
            if set(args) != {"profileId"}:
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, f"{op} takes profileId only.")
            return self.profiles_apps(device_id, args["profileId"]) if op == "profiles.apps" else self.profiles_switch(device_id, args["profileId"])
        if op == "profiles.app":
            if set(args) != {"profileId", "package", "action"}:
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "profiles.app takes profileId, package and action.")
            return self.profiles_app(device_id, args["profileId"], args["package"], args["action"])
        if op == "signup.forget":
            if set(args) != {"package"}:
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "signup.forget takes package only.")
            return self.signup_forget(device_id, args["package"])
        if op == "manual.get":
            if not {"placeId"} <= set(args) <= {"placeId", "query"}:
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "manual.get takes placeId and query only.")
            return self.manual_get(device_id, args["placeId"], args.get("query"))
        if op == "learn.run":
            if set(args) != {"runId"}:
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "learn.run takes runId only.")
            return self.learn_run(device_id, args["runId"])
        if op == "runs.mark":
            if set(args) != {"runId", "expected"}:
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "runs.mark takes runId and expected only.")
            return self.runs_mark(device_id, args["runId"], args["expected"])
        if op == "atlas.here":
            if args:
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "atlas.here takes no arguments.")
            return self.atlas_here(device_id)
        if op == "share.status":
            if args:
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "share.status takes no arguments.")
            return self.share_status(device_id)
        if op == "share.request":
            if set(args) - {"pcLabel"}:
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "share.request takes pcLabel only.")
            return self.share_request(device_id, args.get("pcLabel"))
        if op == "knowledge.get":
            if args:
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "knowledge.get takes no arguments.")
            return self.knowledge_summary(device_id)
        if op == "atlas.versions":
            if set(args) != {"placeId"}:
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "atlas.versions takes placeId only.")
            return self.atlas_versions(device_id, args["placeId"])
        if op == "scenarios.list":
            if not {"placeId"} <= set(args) <= {"placeId", "persona"}:
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "scenarios.list takes placeId and persona only.")
            return self.scenarios_list(device_id, args["placeId"], args.get("persona", "mapping"))
        if op == "atlas.get":
            if set(args) != {"placeId", "persona"}:
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "atlas.get requires placeId/persona only.")
            return self.atlas_get(device_id, str(args["placeId"]), str(args["persona"]))
        if op == "atlas.diff":
            if set(args) != {"placeId", "persona", "since"}:
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "atlas.diff requires placeId/persona/since.")
            return self.atlas_diff(device_id, str(args["placeId"]), str(args["persona"]), args.get("since"))
        if op == "secrets.slots":
            if set(args) != {"placeId", "persona"}:
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "secrets.slots requires placeId/persona only.")
            return self.secret_slots(device_id, str(args["placeId"]), str(args["persona"]))
        if op == "secrets.request":
            if set(args) != {"placeId", "persona", "slot", "reason"}:
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "secrets.request requires safe metadata only.")
            return self.secret_request(
                device_id,
                place_id=str(args["placeId"]),
                persona=str(args["persona"]),
                slot=str(args["slot"]),
                reason=str(args["reason"]),
            )

        if op == "ask.start":
            if not set(args).issubset({"goal", "sessionId", "displayId"}):
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "ask.start takes goal plus plane identity only.")
            _validate_ask_foreground(args)
            goal = args.get("goal")
            if not isinstance(goal, str) or not goal.strip() or len(goal) > MAX_GOAL:
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "goal must be 1..2000 characters of text.")
            return self._call(device_id, op, args)
        if op in {"ask.status", "ask.cancel"}:
            if not set(args).issubset({"sessionId", "displayId", "requestId"}):
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, f"{op} takes plane identity and a requestId only.")
            _validate_ask_foreground(args)
            if "requestId" in args and not ASK_REQUEST_ID.match(str(args["requestId"])):
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "requestId is malformed.")
            return self._call(device_id, op, args)

        if op == "mapping.start":
            allowed = {
                "placeId", "persona", "sessionId", "displayId", "workspaceId", "workspaceGeneration",
                "executionGeneration", "budget", "resumeJobId", "identity", "describer", "deeper",
            }
            if not set(args).issubset(allowed):
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "mapping.start has an unexpected field.")
            _validate_mapping_plane(args, allow_execution_generation=True)
            resume_job_id = args.get("resumeJobId")
            if resume_job_id is not None:
                if not isinstance(resume_job_id, str) or JOB_ID.fullmatch(resume_job_id) is None:
                    raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "resumeJobId is invalid.")
                if any(key in args for key in ("placeId", "persona", "budget", "identity", "describer", "deeper")):
                    raise DesktopRuntimeError(
                        RuntimeErrorCode.INVALID_REQUEST,
                        "mapping.start resume accepts resumeJobId plus plane identity only.",
                    )
            else:
                _validate_place_persona(args)
                if "budget" in args:
                    _validate_budget(args["budget"])
                if "identity" in args and args["identity"] not in MAPPING_IDENTITIES:
                    raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "identity must be own (look only) or test.")
                if "describer" in args:
                    describer = args["describer"]
                    # Plan 36 §9: which model decides for the pass; "phone" is the phone's current model. Never a key.
                    if not isinstance(describer, dict) or set(describer) != {"model"} or not isinstance(describer["model"], str) \
                            or not DESCRIBER_MODEL.match(describer["model"]):
                        raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "describer must be {\"model\": \"phone\" or a model id}.")
                # Plan 36 §5.5: Map deeper. The phone uses its own self-quiz gaps; no words cross from the PC.
                if "deeper" in args and not isinstance(args["deeper"], bool):
                    raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "deeper must be true or false.")
            return self._call(device_id, op, args)

        allowed_command = {"mappingJobId", "sessionId", "displayId", "workspaceId", "workspaceGeneration"}
        if not set(args).issubset(allowed_command):
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, f"{op} has an unexpected field.")
        _validate_mapping_plane(args, allow_execution_generation=False)
        if op in {"mapping.pause", "mapping.stop"}:
            mapping_job_id = args.get("mappingJobId")
            if not isinstance(mapping_job_id, str) or JOB_ID.fullmatch(mapping_job_id) is None:
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "mappingJobId is required.")
        elif "mappingJobId" in args:
            mapping_job_id = args["mappingJobId"]
            if not isinstance(mapping_job_id, str) or JOB_ID.fullmatch(mapping_job_id) is None:
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "mappingJobId is invalid.")
        return self._call(device_id, op, args)

    def _paired(self, device_id: str) -> DeviceSession:
        session = self.fleet.get(device_id)
        if not session.credential:
            raise DesktopRuntimeError(RuntimeErrorCode.PAIRING_REQUIRED, "Pair this phone before V5 contract access.")
        return session

    def _call(self, device_id: str, op: str, args: dict[str, Any], *, checked: bool = False) -> dict[str, Any]:
        # [checked]: the caller already screened every field that is not opaque ciphertext (sealed envelopes).
        if not checked:
            reject_secret_payload(args)
        session = self._paired(device_id)
        try:
            value = session.bridge().request(op, args, request_id=f"v5-{secrets.token_urlsafe(18)}")
            if not isinstance(value, dict):
                raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android V5 result must be an object.")
            return validate_android_response(op, value, args)
        except BridgeOperationError as exc:
            if exc.code == "PHONE_APP_BUSY":
                raise phone_errors.busy() from exc
            mapping = {
                "AUTH_REJECTED": RuntimeErrorCode.AUTH_REJECTED,
                "PROTOCOL_MISMATCH": RuntimeErrorCode.PROTOCOL_MISMATCH,
                "INVALID_REQUEST": RuntimeErrorCode.INVALID_REQUEST,
                "SECRET_PAYLOAD_REJECTED": RuntimeErrorCode.INVALID_REQUEST,
                "SESSION_REQUIRED": RuntimeErrorCode.SESSION_REQUIRED,
                "SESSION_DISPLAY_MISMATCH": RuntimeErrorCode.SESSION_DISPLAY_MISMATCH,
                "HUMAN_HAS_CONTROL": RuntimeErrorCode.HUMAN_HAS_CONTROL,
                "STALE_CONTROL_REVISION": RuntimeErrorCode.STALE_CONTROL_REVISION,
                "MAPPING_PLANE_BUSY": RuntimeErrorCode.MAPPING_PLANE_BUSY,
                "MAPPING_JOB_NOT_FOUND": RuntimeErrorCode.MAPPING_JOB_NOT_FOUND,
                "RUN_NOT_FOUND": RuntimeErrorCode.RUN_NOT_FOUND,
                "MAPPING_INVALID_STATE": RuntimeErrorCode.MAPPING_INVALID_STATE,
                "ASK_BUSY": RuntimeErrorCode.ASK_BUSY,
                "OVERLAY_UNAVAILABLE": RuntimeErrorCode.OVERLAY_UNAVAILABLE,
                "MOMENT_CHANGED": RuntimeErrorCode.MOMENT_CHANGED,
                "ANSWER_ON_PHONE": RuntimeErrorCode.ANSWER_ON_PHONE,
                "SEALED_REJECTED": RuntimeErrorCode.SEALED_REJECTED,
                "KEY_UNAVAILABLE": RuntimeErrorCode.KEY_UNAVAILABLE,
            }
            raise DesktopRuntimeError(
                mapping.get(exc.code, RuntimeErrorCode.CAPABILITY_UNAVAILABLE),
                f"Android rejected {op}.",
                retryable=phone_errors.retryable(exc.code),
            ) from exc
        except (BridgeDisconnectedError, BridgeProtocolError) as exc:
            raise phone_errors.transport(exc) from exc
