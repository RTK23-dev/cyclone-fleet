from __future__ import annotations

import json
import sys
from typing import Any

from .surface import PhoneTools
from .protocol import classify_failure

SERVER_NAME = "cyclone-phone"
SERVER_VERSION = "3.1-beta"

DEFAULT_SURFACE = (
    "phone_status",
    "phone_locate",
    "phone_act",
    "phone_skill_save",
    "phone_skill_run",
)

INSTRUCTIONS = (
    "Control the phone semantic-first through Cyclone Fast Path. Default loop: phone_status → phone_locate(goal) → phone_act → phone_skill_save | phone_skill_run. "
    "Planner tools land: phone.open_app / phone.launch_intent / phone.wait_for / model status=done. "
    "UI sub-agent tools operate the current tree: phone_observe/phone_locate (get_tree) then index click/type via phone_act. "
    "Prefer phone.open_app or an allowlisted intent/deep-link before hunting a launcher icon. 3.9.12 Ask→workspace already routes a uniquely named installed app. "
    "phone.open_app uses params.package only (Android package id, e.g. com.android.chrome). Do not send an app display name or packageName. "
    "Browse/open Chrome is phone_act tool=phone.open_app params.package=com.android.chrome. It does not use OpenRouter. "
    "phone_locate returns readiness, a bounded Page Card (pageText + pageSummary, elementIndex), and goal-ranked candidates. "
    "If a verified skill matches goal + pageKey, call phone_skill_run and skip the model. "
    "Prefer the Page Card snapshot (YAML hosts with ref=eN) as the page context. Refs and elementIndex die on the next snapshot. "
    "MCP rejects free-form text/fuzzy/coordinate selectors; pass params.elementId, params.elementIndex, params.ref, or role+name from the current snapshot. "
    "One screen-changing phone_act per decision turn. Form field batching is allowed only when non-nav. "
    "phone_act returns ok, pageChanged, before, after.pageCard, delta, errorClass, generation; transport success is never verification. "
    "Ordinary taps are verified locally by fingerprint settle (300ms, then +500/+1000). UNCHANGED is verified=false — do not click the same control again. "
    "Screenshot/vision only when perceptionMode=vision_escalate or the a11y tree is empty/custom canvas. "
    "phone_skill_save writes status=draft into existing AutomationStore via SkillCompiler.compile only when 2+ steps are verified; secret slots are stripped. "
    "phone_skill_run runs verified skills (or dryRun on a draft) through PhoneToolExecutor via the gateway and returns per-step act envelopes. "
    "When multiple phones are connected, pass device_id from phone_devices. "
    "Use phone_workspace to list/register/switch/pause/release/arm/next Layer 2 workspaces on default-foreground display 0, then pass workspaceId+workspaceGeneration on mutating phone_act.params. Layer 2 is not a VD session. "
    "session_id is required on observe/act/locate/search/inspect/screenshot/skill_run/group_act. "
    "Read session_id from phone_status (sessions inventory when present). "
    "Pass session_id=default-foreground for the live human display (display 0). Named workspace sessions require display_id > 0 and must never be rewritten onto display 0. "
    "sessionId / executionContext.sessionId aliases are accepted; do not invent default-foreground when session_id is missing. "
    "user_authorized is only an MCP intent acknowledgement and never bypasses Android policy. "
    "request_ai_control=true asks Companion to yield input; it never steals a locked phone and never bypasses Android policy. "
    "PHONE_APP_BUSY means the phone is connected but Cyclone on it is still busy: wait about a second and retry the same call; it is not a disconnect. "
    "Each phone_devices row carries connection {code, title, message, action}: the first broken link (cable, USB Allow, app stopped, Allow this PC, locked phone, PC Gateway off, Accessibility). When a call fails, read it and tell the owner that one step instead of guessing. "
    "Do not expose secrets or use arbitrary shell/root/ADB commands."
)


PLANNER_MCP = {"phone_status", "phone_skill_run", "phone_skill_save", "phone_capabilities"}
UI_MCP = {"phone_observe", "phone_locate", "phone_ui_search", "phone_inspect_element", "phone_act"}


def _surface_role(name: str) -> str:
    if name in PLANNER_MCP:
        return "planner"
    if name in UI_MCP:
        return "ui"
    return "shared"


def _tool(name: str, description: str, schema: dict[str, Any], *, read_only: bool, destructive: bool = False) -> dict[str, Any]:
    default = name in DEFAULT_SURFACE
    return {
        "name": name,
        "description": description,
        "inputSchema": schema,
        "annotations": {
            "readOnlyHint": read_only,
            "destructiveHint": destructive,
            "idempotentHint": read_only,
            "openWorldHint": True,
            "cycloneDefaultSurface": default,
            "cycloneSurface": _surface_role(name),
            "cycloneFastPath": True,
        },
    }


def _with_device(schema: dict[str, Any]) -> dict[str, Any]:
    properties = dict(schema.get("properties") or {})
    properties["device_id"] = {
        "type": "string",
        "description": "Optional device id from phone_devices. Omit to use the gateway's selected single phone.",
    }
    return {
        "type": "object",
        "properties": properties,
        "required": schema.get("required", []),
        "additionalProperties": False,
    }


def _with_session(schema: dict[str, Any], *, required: bool = True) -> dict[str, Any]:
    properties = dict(schema.get("properties") or {})
    properties["session_id"] = {
        "type": "string",
        "description": (
            "REQUIRED execution session id (not USB, media, teach, or MCP-report sessionId). "
            "Pass default-foreground for the live human display. Named workspace sessions "
            "require display_id > 0. sessionId / executionContext.sessionId aliases also satisfy this."
        ),
    }
    properties["display_id"] = {
        "type": "integer",
        "description": (
            "Virtual display id for the execution session. Required and must be > 0 when "
            "session_id is not default-foreground. Never send display 0 for a named workspace."
        ),
    }
    properties["sessionId"] = {
        "type": "string",
        "description": "Alias of session_id. Either alias satisfies the session_id requirement.",
    }
    properties["displayId"] = {
        "type": "integer",
        "description": "Alias of display_id.",
    }
    properties["executionContext"] = {
        "type": "object",
        "description": "Optional nested {sessionId, displayId}. Must agree with top-level aliases.",
        "properties": {
            "sessionId": {"type": "string"},
            "session_id": {"type": "string"},
            "displayId": {"type": "integer"},
            "display_id": {"type": "integer"},
        },
        "additionalProperties": False,
    }
    required_fields = list(schema.get("required") or [])
    if required and "session_id" not in required_fields:
        required_fields.append("session_id")
    return {
        "type": "object",
        "properties": properties,
        "required": required_fields,
        "additionalProperties": False,
    }


TOOLS = [
    _tool(
        "phone_workspace",
        "Layer 2 workspaces: register profiles, switch with verified package/user binding, pause/release the global mutate lock, arm jobs and claim next round-robin slice. GATE stays authoritative. Switch/next returns workspaceId + workspaceGeneration; pass both inside phone_act.params after a fresh observe. Layer 2 is default-foreground / display 0 only, not a named VD session. No parallel input.",
        _with_session(_with_device({
            "type": "object",
            "properties": {
                "operation": {
                    "type": "string",
                    "enum": ["list", "register", "switch", "pause", "release", "arm", "next"],
                    "description": "Layer 2 workspace operation. GATE stays authoritative.",
                },
                "params": {
                    "type": "object",
                    "description": "Typed Layer 2 parameters. displayId must be 0 when sent. Layer 2 is not a VD session.",
                    "properties": {
                        "id": {"type": "string", "pattern": "^[A-Za-z0-9_-]{1,80}$"},
                        "label": {"type": "string"},
                        "appPackage": {"type": "string"},
                        "androidUserId": {"type": "integer"},
                        "displayId": {"type": "integer", "const": 0, "description": "Layer 2 is display 0 only."},
                        "goal": {"type": "string"},
                    },
                    "additionalProperties": False,
                },
            },
            "required": ["operation"],
        })),
        read_only=False,
    ),
    _tool("phone_status", "Read Cyclone gateway, ADB, bridge and Accessibility readiness for one phone.", _with_device({"type": "object", "properties": {}}), read_only=True),
    _tool("phone_locate", "Primary locate-first tool: fuse device status, a bounded Page Card (pageText + pageSummary), and goal-aware semantic search. If a verified skill matches goal + pageKey, skip the model and call phone_skill_run.", _with_session(_with_device({"type": "object", "properties": {"goal": {"type": "string"}, "query": {"type": "string"}}, "required": ["goal"]})), read_only=True),
    _tool("phone_act", "Execute one typed Cyclone phone action through the V3 Android authority seam and canonical PhoneToolExecutor. Planner: phone.open_app / phone.wait_for. phone.open_app requires params.package (Android package id, e.g. com.android.chrome), not an app display name or packageName. UI: click/type/scroll by current elementId or elementIndex. Locate first. click/long_press/type require a current observation-scoped elementId, elementIndex, snapshot ref, or role+name; free-form selectors and coordinates are rejected. One screen-changing act per turn. Fast Path settles 300ms then fingerprints; UNCHANGED is verified=false — do not double-click. phone.scroll accepts direction=forward/backward; phone.swipe has no safe MCP route. Every mutation returns action status plus before/after Page Cards, pageChanged, delta, errorClass, and generation. phone.type requires user_authorized=true but Android policy remains authoritative. request_ai_control=true asks Companion to yield input; it never steals a locked phone. After a Layer 2 phone_workspace switch/next, mutating params MUST include workspaceId + workspaceGeneration from that result.", _with_session(_with_device({"type": "object", "properties": {"tool": {"type": "string", "enum": ["phone.click", "phone.long_press", "phone.swipe", "phone.scroll", "phone.type", "phone.back", "phone.home", "phone.open_app", "phone.wait_for"]}, "params": {"type": "object", "description": "Typed safe parameters only. phone.open_app requires params.package (Android package id, e.g. com.android.chrome); do not send an app display name or packageName. Use current elementId or elementIndex for element actions; raw selector text/fuzzy/bounds/coordinates are rejected. After a Layer 2 switch/next, mutating params MUST include workspaceId (string [A-Za-z0-9_-]{1,80}) and workspaceGeneration (integer >= 0) from that result. Layer 2 is not a named VD session.", "properties": {"package": {"type": "string", "description": "required Android package id for phone.open_app (e.g. com.android.chrome). Not an app display name. Do not send packageName."}, "workspaceId": {"type": "string", "pattern": "^[A-Za-z0-9_-]{1,80}$", "description": "Required on mutating phone_act after a Layer 2 switch/next. Must match the current lease."}, "workspaceGeneration": {"type": "integer", "minimum": 0, "description": "Required on mutating phone_act after a Layer 2 switch/next. Stale generation fails closed."}}}, "goal": {"type": "string"}, "user_authorized": {"type": "boolean", "default": False}, "request_ai_control": {"type": "boolean", "default": False, "description": "Ask Companion to yield input so MCP can act. Boolean only. Never steals a locked phone and never bypasses Android policy."}}, "required": ["tool", "params", "goal"]})), read_only=False, destructive=True),
    _tool("phone_skill_save", "Compile verified 2+ phone_act steps into a disabled draft skill in the existing AutomationStore (SkillCompiler.compile). Unverified steps do not write. Secret slots are stripped. Workers cannot mark verified.", _with_session(_with_device({"type": "object", "properties": {"goal": {"type": "string"}, "pageKey": {"type": "string"}, "app": {"type": "string"}, "steps": {"type": "array", "items": {"type": "object"}, "minItems": 2}, "params": {"type": "object", "description": "Slot values only. Secret slots are stripped and never persisted."}}, "required": ["goal", "steps"]}), required=False), read_only=False),
    _tool("phone_skill_run", "Run one skill from AutomationStore through PhoneToolExecutor via the gateway. Only status=verified runs live; drafts require dryRun=true. Returns per-step act envelopes.", _with_session(_with_device({"type": "object", "properties": {"skill_id": {"type": "string"}, "dryRun": {"type": "boolean", "default": False}, "params": {"type": "object"}}, "required": ["skill_id"]})), read_only=False, destructive=True),
    _tool("phone_capabilities", "Discover the typed V3 phone capability inventory and health. Discovery is metadata, not action authority.", _with_device({"type": "object", "properties": {"refresh": {"type": "boolean", "default": False}}}), read_only=True),
    _tool("phone_devices", "Auto-detect connected phones through the PC gateway fleet. Returns device ids, states, pairing and display info; scan=true forces a fresh ADB scan.", {"type": "object", "properties": {"scan": {"type": "boolean", "default": False}}, "additionalProperties": False}, read_only=True),
    _tool("phone_list", "Alias of phone_devices. Auto-detect connected phones through the PC gateway fleet.", {"type": "object", "properties": {"scan": {"type": "boolean", "default": False}}, "additionalProperties": False}, read_only=True),
    _tool("phone_observe", "UI get_tree: read a bounded a11y-first Page Card with stable elementIndex. Compact mode is the normal path; provide goal to rank current candidates. Screenshot only when perceptionMode=vision_escalate. Element IDs/indices expire after mutation.", _with_session(_with_device({"type": "object", "properties": {"mode": {"type": "string", "enum": ["compact", "full"], "default": "compact"}, "include_screenshot": {"type": "boolean", "default": False}, "goal": {"type": "string"}}})), read_only=True),
    _tool("phone_ui_search", "Run bounded semantic search when Page Card context is insufficient. Results retain only safe candidates and current observation-scoped IDs; no raw UI tree is returned.", _with_session(_with_device({"type": "object", "properties": {"query": {"type": "string"}, "goal": {"type": "string"}}, "required": ["query"]})), read_only=True),
    _tool("phone_inspect_element", "Inspect one current candidate. Its elementId is observation-scoped: act now or re-observe after any mutation.", _with_session(_with_device({"type": "object", "properties": {"element_id": {"type": "string"}}, "required": ["element_id"]})), read_only=True),
    _tool("phone_screenshot", "Vision escalate: capture/return the current screenshot plus its PageKey-correlated compact observation. Use only when perceptionMode=vision_escalate or the a11y tree is empty/custom canvas.", _with_session(_with_device({"type": "object", "properties": {}})), read_only=True),
    _tool("phone_current_page", "Read the gateway's current page record for one phone.", _with_device({"type": "object", "properties": {}}), read_only=True),
    _tool("phone_page_history", "Read recent page/action transition history for verification and recovery.", _with_device({"type": "object", "properties": {}}), read_only=True),
    _tool("phone_group_act", "Run one typed non-secret phone action on 1..32 explicitly selected devices. Cyclone observes each target first and returns independent per-device outcomes.", _with_session({"type": "object", "properties": {"device_ids": {"type": "array", "items": {"type": "string"}, "minItems": 1, "maxItems": 32, "uniqueItems": True}, "tool": {"type": "string", "enum": ["phone.click", "phone.long_press", "phone.swipe", "phone.scroll", "phone.back", "phone.home", "phone.open_app", "phone.wait_for"]}, "params": {"type": "object"}, "goal": {"type": "string"}}, "required": ["device_ids", "tool", "params", "goal"]}), read_only=False, destructive=True),
    _tool("phone_debug_bundle", "Capture the bridge diagnostic bundle when perception, context, execution or verification disagree.", _with_device({"type": "object", "properties": {"goal": {"type": "string"}, "expected": {"type": "string"}}}), read_only=True),
    _tool("phone_teach_start", "Start Cyclone's canonical Follow Me/Teach session; this does not create a second teaching store.", _with_device({"type": "object", "properties": {"goal": {"type": "string"}}}), read_only=False),
    _tool("phone_teach_status", "Read the active Cyclone teaching session state.", _with_device({"type": "object", "properties": {}}), read_only=True),
    _tool("phone_teach_stop", "Stop Cyclone teaching and optionally compile evidence into a disabled-for-review routine.", _with_device({"type": "object", "properties": {"compile_for_review": {"type": "boolean", "default": True}}}), read_only=False),
    _tool("phone_virtual_list", "List Cyclone-managed virtual phone instances through the authenticated local Gateway.", {"type": "object", "properties": {}, "additionalProperties": False}, read_only=True),
    _tool("phone_virtual_create", "Create one virtual phone from an installed provider image. Provider policy and availability remain authoritative.", {"type": "object", "properties": {"provider": {"type": "string", "pattern": "^[a-z][a-z0-9_.-]{0,79}$"}, "image": {"type": "string", "pattern": "^[A-Za-z0-9][A-Za-z0-9_.;+:-]{0,239}$"}}, "required": ["provider", "image"], "additionalProperties": False}, read_only=False),
    _tool("phone_virtual_start", "Start one explicitly identified Cyclone virtual phone.", {"type": "object", "properties": {"instance_id": {"type": "string", "pattern": "^vdev_[a-f0-9]{16}$"}}, "required": ["instance_id"], "additionalProperties": False}, read_only=False),
    _tool("phone_virtual_stop", "Stop one explicitly identified Cyclone virtual phone.", {"type": "object", "properties": {"instance_id": {"type": "string", "pattern": "^vdev_[a-f0-9]{16}$"}}, "required": ["instance_id"], "additionalProperties": False}, read_only=False),
    _tool("phone_routine_run", "Run one known Cyclone routine on one explicitly selected device. No arbitrary routine payload is accepted.", {"type": "object", "properties": {"device_id": {"type": "string", "pattern": "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}$"}, "routine_id": {"type": "string", "pattern": "^[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*$"}}, "required": ["device_id", "routine_id"], "additionalProperties": False}, read_only=False),
    _tool("phone_routine_status", "Read one explicitly targeted Cyclone routine run.", {"type": "object", "properties": {"device_id": {"type": "string", "pattern": "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}$"}, "run_id": {"type": "string", "pattern": "^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$"}}, "required": ["device_id", "run_id"], "additionalProperties": False}, read_only=True),
    _tool("phone_routine_cancel", "Cancel one explicitly targeted Cyclone routine run.", {"type": "object", "properties": {"device_id": {"type": "string", "pattern": "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}$"}, "run_id": {"type": "string", "pattern": "^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$"}}, "required": ["device_id", "run_id"], "additionalProperties": False}, read_only=False),
    _tool("phone_ask", "Send one request to Cyclone exactly as the owner would type it in the Ask bar, and wait up to wait_s seconds for what became of it: the lane that took it (instant, answer, ignore, flash, mind), who decided (grammar, phone, decisions, rules...), how long deciding took, and the task's state for Flash/Mind. Approvals stay on the phone.", {"type": "object", "properties": {"device_id": {"type": "string", "pattern": "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}$"}, "goal": {"type": "string", "minLength": 1, "maxLength": 2000}, "wait_s": {"type": "number", "minimum": 0, "maximum": 300, "default": 20}}, "required": ["device_id", "goal"], "additionalProperties": False}, read_only=False),
    _tool("phone_ask_cancel", "Stop what a phone_ask request started (its Instant run, or its Flash/Mind task). Without request_id, the latest request.", {"type": "object", "properties": {"device_id": {"type": "string", "pattern": "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}$"}, "request_id": {"type": "string", "pattern": "^req-[0-9a-f-]{8,40}$"}}, "required": ["device_id"], "additionalProperties": False}, read_only=False),
    _tool("phone_lab_missions", "List Cyclone Lab missions (goal, suite, how success is read from the phone) for measuring Cyclone.", {"type": "object", "properties": {}, "additionalProperties": False}, read_only=True),
    _tool("phone_lab_start", "Run a Cyclone Lab experiment: missions x variants x repetitions on one paired phone, scored from the phone. A variant is {name, modelId?, effort?, workingMinutes?, marks?, freshMemory?, promptAddendum?, useMap?}. The lab never approves consequential actions and never supplies secrets.", {"type": "object", "properties": {"device_id": {"type": "string", "pattern": "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}$"}, "name": {"type": "string", "minLength": 1, "maxLength": 80}, "missions": {"type": "array", "items": {"type": "string", "pattern": "^[a-z0-9][a-z0-9._-]{2,63}$"}, "minItems": 1, "maxItems": 100}, "variants": {"type": "array", "items": {"type": "object"}, "minItems": 1, "maxItems": 4}, "repetitions": {"type": "integer", "minimum": 1, "maximum": 20, "default": 1}}, "required": ["device_id", "name", "missions"], "additionalProperties": False}, read_only=False),
    _tool("phone_lab_report", "Cyclone Lab results: without an id the experiment list; with one, success rates with confidence intervals, A/B comparisons, insights and every failed run with its cause, checks and tool errors.", {"type": "object", "properties": {"experiment_id": {"type": "string", "pattern": "^exp-[0-9]{8}-[0-9]{6}-[a-z0-9]{4}$"}}, "additionalProperties": False}, read_only=True),
    _tool("phone_lab_stop", "Stop a running Cyclone Lab experiment after the current mission is stopped.", {"type": "object", "properties": {"experiment_id": {"type": "string", "pattern": "^exp-[0-9]{8}-[0-9]{6}-[a-z0-9]{4}$"}}, "required": ["experiment_id"], "additionalProperties": False}, read_only=False),
]


class McpServer:
    def __init__(self, phone_tools: PhoneTools | None = None):
        self.phone_tools = phone_tools or PhoneTools()

    def handle(self, request: dict[str, Any]) -> dict[str, Any] | None:
        method = request.get("method")
        request_id = request.get("id")
        if method == "notifications/initialized":
            return None
        if method == "initialize":
            params = request.get("params") or {}
            protocol = params.get("protocolVersion") or "2025-06-18"
            return _result(request_id, {
                "protocolVersion": protocol,
                "capabilities": {"tools": {"listChanged": False}},
                "serverInfo": {"name": SERVER_NAME, "version": SERVER_VERSION},
                "instructions": INSTRUCTIONS,
            })
        if method == "ping":
            return _result(request_id, {})
        if method == "tools/list":
            return _result(request_id, {"tools": listed_tools(self.phone_tools), "defaultSurface": list(DEFAULT_SURFACE)})
        if method == "tools/call":
            params = request.get("params") or {}
            name = params.get("name")
            arguments = params.get("arguments") or {}
            listed = {tool["name"] for tool in listed_tools(self.phone_tools)}
            if name not in listed:
                return _error(request_id, -32602, f"Unknown tool: {name}")
            content = self.phone_tools.call(str(name), arguments if isinstance(arguments, dict) else {})
            is_error = bool(getattr(self.phone_tools, "last_call_failed", _content_failed(content)))
            return _result(request_id, {"content": content, "isError": is_error})
        return _error(request_id, -32601, f"Method not found: {method}")

    def serve_stdio(self) -> None:
        for raw in sys.stdin:
            raw = raw.strip()
            if not raw:
                continue
            try:
                request = json.loads(raw)
                response = self.handle(request)
            except Exception as exc:
                request_id = None
                try:
                    request_id = request.get("id") if isinstance(request, dict) else None
                except Exception:
                    pass
                response = _error(request_id, -32603, f"Internal server error: {exc}")
            if response is not None:
                sys.stdout.write(json.dumps(response, separators=(",", ":")) + "\n")
                sys.stdout.flush()


def listed_tools(phone_tools: Any) -> list[dict[str, Any]]:
    """Permission-aware listing: omit tools the current phone cannot run when capabilities are known."""
    omitted = getattr(phone_tools, "omitted_tools", None)
    if not omitted:
        return list(TOOLS)
    blocked = set(omitted)
    return [tool for tool in TOOLS if tool["name"] not in blocked]


def _result(request_id: Any, result: Any) -> dict[str, Any]:
    return {"jsonrpc": "2.0", "id": request_id, "result": result}


def _error(request_id: Any, code: int, message: str) -> dict[str, Any]:
    return {"jsonrpc": "2.0", "id": request_id, "error": {"code": code, "message": message}}


def _content_failed(content: list[dict[str, Any]]) -> bool:
    if not content or content[0].get("type") != "text":
        return False
    try:
        return classify_failure(json.loads(content[0].get("text", ""))) is not None
    except (TypeError, json.JSONDecodeError):
        return True
