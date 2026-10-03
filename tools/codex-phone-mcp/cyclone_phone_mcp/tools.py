from __future__ import annotations

import base64
import mimetypes
import os
import re
import time
from pathlib import Path
from typing import Any, Callable

from .compact import compact_element, compact_observation, compact_search, page_changed, page_delta, redact
from .gateway import GatewayClient, GatewayError
from .reports import SessionRecorder
from .protocol import Failure, classify_failure
from .session import (
    SessionScopeError,
    attach_execution_scope,
    require_tool_execution_scope,
    scope_cache_key,
    session_scope_error_result,
    strip_execution_scope,
)

ALLOWED_ACTIONS = {
    "phone.click",
    "phone.long_press",
    "phone.swipe",
    "phone.scroll",
    "phone.type",
    "phone.back",
    "phone.home",
    "phone.open_app",
    "phone.wait_for",
}
ALLOWED_GROUP_ACTIONS = ALLOWED_ACTIONS - {"phone.type"}
FORBIDDEN_OPERATION_KEY = re.compile(
    r"(?i)^(?:cmd|command|shell|adb|powershell|subprocess|executable|script|root|su|docker|host_command)$"
)

FAILURE_CLASSES = {
    "ACCESSIBILITY_PERCEPTION",
    "SEMANTICIZATION_LOSS",
    "AGENT_CONTEXT_TRUNCATION",
    "AGENT_REASONING_OR_MEMORY",
}

PROVIDER_ID = re.compile(r"^[a-z][a-z0-9_.-]{0,79}$")
IMAGE_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.;+:-]{0,239}$")
INSTANCE_ID = re.compile(r"^vdev_[a-f0-9]{16}$")
ROUTINE_ID = re.compile(r"^[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*$")
RUN_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$")
TARGET_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}$")
LAB_EXPERIMENT_ID = re.compile(r"^exp-[0-9]{8}-[0-9]{6}-[a-z0-9]{4}$")
LAB_MISSION_ID = re.compile(r"^[a-z0-9][a-z0-9._-]{2,63}$")
LAB_VARIANT_KEYS = frozenset({"name", "modelId", "effort", "workingMinutes", "marks", "freshMemory", "promptAddendum", "useMap"})
ANDROID_PACKAGE = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$")

MUTATING_ACTIONS = ALLOWED_ACTIONS - {"phone.wait_for"}
WORKSPACE_OPERATIONS = {"list", "register", "switch", "pause", "release", "arm", "next"}
WORKSPACE_PARAM_KEYS = {"id", "label", "appPackage", "androidUserId", "displayId", "goal"}
WORKSPACE_ID_RE = re.compile(r"^[A-Za-z0-9_-]{1,80}$")
_WORKSPACE_NEST_KEYS = ("result", "payload", "workspace", "data", "status", "body")
_HOLDER_MISSING = object()
_LAYER2_NEXT = (
    "Pass workspaceId and workspaceGeneration on mutating phone_act.params. "
    "Layer 2 is not a VD session."
)
ELEMENT_ID_KEYS = {"elementId", "element_id"}
ELEMENT_INDEX_KEYS = {"elementIndex", "element_index"}
REF_KEYS = {"ref"}
ROLE_NAME_KEYS = {"role", "name"}
COORDINATE_KEYS = {
    "x", "y", "x1", "y1", "x2", "y2", "startx", "starty", "endx", "endy",
    "coordinates", "coordinate", "bounds", "point", "points",
}


def _result_failed(result: Any) -> bool:
    return classify_failure(result) is not None


def _device_id(args: dict[str, Any]) -> str:
    return str(args.get("device_id") or "").strip()


def _required_id(args: dict[str, Any], key: str, pattern: re.Pattern[str]) -> str:
    value = str(args.get(key) or "").strip()
    if not pattern.fullmatch(value):
        raise ValueError(f"{key} is invalid")
    return value


def _only_keys(args: dict[str, Any], allowed: set[str]) -> None:
    if set(args) - allowed:
        raise ValueError("Unexpected parameters are not permitted")


def _validate_typed_params(value: Any, path: str = "params") -> None:
    if isinstance(value, dict):
        for key, item in value.items():
            key_text = str(key)
            if FORBIDDEN_OPERATION_KEY.fullmatch(key_text):
                raise ValueError(f"{path}.{key_text} is not a permitted typed phone parameter")
            _validate_typed_params(item, f"{path}.{key_text}")
    elif isinstance(value, list):
        if len(value) > 100:
            raise ValueError(f"{path} exceeds the bounded list size")
        for index, item in enumerate(value):
            _validate_typed_params(item, f"{path}[{index}]")


def _element_id_from_params(params: dict[str, Any]) -> str | None:
    direct = next((params.get(key) for key in ELEMENT_ID_KEYS if params.get(key)), None)
    selector = params.get("selector")
    nested = None
    if isinstance(selector, dict):
        nested = next((selector.get(key) for key in ELEMENT_ID_KEYS if selector.get(key)), None)
        unsupported = set(selector) - ELEMENT_ID_KEYS - ELEMENT_INDEX_KEYS - REF_KEYS - ROLE_NAME_KEYS - {"id"}
        if unsupported:
            raise ValueError(
                "MCP selector input must contain only a current observation-scoped elementId, ref, or role+name; "
                "use phone_locate or phone_ui_search first."
            )
    if direct and nested and str(direct) != str(nested):
        raise ValueError("elementId values disagree")
    element_id = str(direct or nested or "").strip()
    return element_id or None


def _reject_coordinate_params(value: Any, path: str = "params") -> None:
    if isinstance(value, dict):
        for key, nested in value.items():
            key_text = str(key)
            normalized = key_text.replace("_", "").lower()
            if normalized in COORDINATE_KEYS:
                raise ValueError(
                    f"{path}.{key_text} is not permitted: MCP does not execute free-form coordinates. "
                    "Use a current observation-scoped elementId or a safe typed route."
                )
            _reject_coordinate_params(nested, f"{path}.{key_text}")
    elif isinstance(value, list):
        for index, nested in enumerate(value):
            _reject_coordinate_params(nested, f"{path}[{index}]")


def _validate_mcp_action_params(tool: str, params: dict[str, Any]) -> str | None:
    """Keep the PC-agent surface semantic and observation-scoped.

    Gateway support for broader selectors remains unchanged for internal Cyclone routes. MCP is a
    deliberately narrower model-facing surface: it never accepts raw text, fuzzy or coordinate
    selectors that can drift after context truncation. Session identity belongs on top-level MCP
    args; leftover session keys in params are stripped before this validator.
    """
    params = strip_execution_scope(params)
    _validate_typed_params(params)
    workspace_id = params.get("workspaceId")
    workspace_generation = params.get("workspaceGeneration")
    if workspace_id is not None or workspace_generation is not None:
        _require_workspace_identity(workspace_id, workspace_generation)
    params = {key: value for key, value in params.items() if key not in {"workspaceId", "workspaceGeneration"}}
    _reject_coordinate_params(params)
    element_id = _element_id_from_params(params)

    ref = str(params.get("ref") or "").strip()
    role = str(params.get("role") or "").strip()
    name = str(params.get("name") or "").strip()
    if isinstance(params.get("selector"), dict):
        ref = ref or str(params["selector"].get("ref") or "").strip()
        role = role or str(params["selector"].get("role") or "").strip()
        name = name or str(params["selector"].get("name") or "").strip()
    element_index = _element_index_from_params(params)
    if tool in {"phone.click", "phone.long_press", "phone.type"} and not (
        element_id or element_index or ref or (role and name)
    ):
        raise ValueError(
            f"{tool} requires a current observation-scoped elementId, elementIndex, ref, or role+name. Run "
            "phone_locate or phone_observe, then use one returned candidate ID, index, or snapshot ref."
        )
    if tool == "phone.swipe":
        raise ValueError(
            "phone.swipe has no safe typed MCP route in the frozen gateway protocol. "
            "Use phone.scroll with direction=forward/backward instead."
        )
    if tool == "phone.scroll":
        allowed = {"direction", "selector", *ELEMENT_ID_KEYS, *ELEMENT_INDEX_KEYS}
        if set(params) - allowed:
            raise ValueError("phone.scroll accepts only direction and an optional current elementId or elementIndex")
        direction = str(params.get("direction") or "forward").lower()
        if direction not in {"forward", "backward"}:
            raise ValueError("phone.scroll direction must be forward or backward")
    elif tool == "phone.type":
        allowed = {"value", "text", "selector", *ELEMENT_ID_KEYS, *ELEMENT_INDEX_KEYS, *REF_KEYS, *ROLE_NAME_KEYS}
        if set(params) - allowed or not isinstance(params.get("value", params.get("text")), str):
            raise ValueError("phone.type accepts one text/value and a current elementId")
        if "value" in params and "text" in params and params["value"] != params["text"]:
            raise ValueError("phone.type text and value disagree")
    elif tool == "phone.long_press":
        allowed = {"durationMs", "selector", *ELEMENT_ID_KEYS, *ELEMENT_INDEX_KEYS, *REF_KEYS, *ROLE_NAME_KEYS}
        if set(params) - allowed:
            raise ValueError("phone.long_press accepts only durationMs and a current elementId or elementIndex")
    elif tool == "phone.click":
        allowed = {"selector", *ELEMENT_ID_KEYS, *ELEMENT_INDEX_KEYS, *REF_KEYS, *ROLE_NAME_KEYS}
        if set(params) - allowed:
            raise ValueError("phone.click accepts only a current observation-scoped elementId or elementIndex")
    elif tool in {"phone.back", "phone.home"}:
        if params:
            raise ValueError(f"{tool} accepts no parameters")
    elif tool == "phone.open_app":
        _validate_open_app_params(params)
    elif tool == "phone.wait_for":
        allowed = {"condition", "timeoutMs", "pollMs", "selector", *ELEMENT_ID_KEYS}
        if set(params) - allowed:
            raise ValueError("phone.wait_for accepts only a bounded condition, timeout, poll interval, and elementId")
        condition = params.get("condition")
        if condition is not None and not isinstance(condition, dict):
            raise ValueError("phone.wait_for condition must be an object")
    return element_id


_OPEN_APP_PACKAGE_NAME_KEYS = frozenset({"packageName", "package_name"})
_OPEN_APP_DISPLAY_NAME_KEYS = frozenset({"app", "name", "appName", "app_name"})
OPEN_APP_MISSING_PACKAGE = (
    "phone.open_app requires params.package (Android package id). Example: com.android.chrome. "
    "Do not send an app display name or packageName."
)
OPEN_APP_PACKAGE_NAME = (
    "phone.open_app requires params.package, not packageName. Example: com.android.chrome."
)
OPEN_APP_DISPLAY_NAME = (
    "phone.open_app requires params.package (Android package id), not an app display name. "
    "Chrome is com.android.chrome."
)
OPEN_APP_INVALID_PACKAGE = (
    "params.package must be a valid Android package id (e.g. com.android.chrome), not an app name."
)
OPEN_APP_EXTRA_KEYS = "phone.open_app accepts only params.package"


def _validate_open_app_params(params: dict[str, Any]) -> None:
    """Reject app names and packageName aliases; require a real Android package id."""
    keys = set(params)
    if keys & _OPEN_APP_PACKAGE_NAME_KEYS:
        raise ValueError(OPEN_APP_PACKAGE_NAME)
    if keys & _OPEN_APP_DISPLAY_NAME_KEYS:
        raise ValueError(OPEN_APP_DISPLAY_NAME)
    package = params.get("package")
    if "package" not in params or not str(package or "").strip():
        raise ValueError(OPEN_APP_MISSING_PACKAGE)
    if keys - {"package"}:
        raise ValueError(OPEN_APP_EXTRA_KEYS)
    if not ANDROID_PACKAGE.fullmatch(str(package)):
        raise ValueError(OPEN_APP_INVALID_PACKAGE)


def _require_workspace_identity(workspace_id: Any, workspace_generation: Any) -> tuple[str, int]:
    if not isinstance(workspace_id, str) or not WORKSPACE_ID_RE.fullmatch(workspace_id):
        raise ValueError("workspaceId is invalid")
    if type(workspace_generation) is not int or workspace_generation < 0:
        raise ValueError("workspaceGeneration is required with workspaceId")
    return workspace_id, workspace_generation


def _validate_workspace_operation_params(operation: str, params: dict[str, Any]) -> None:
    if "displayId" in params and params.get("displayId") != 0:
        raise ValueError("Layer 2 workspaces require displayId 0")
    workspace_id = params.get("id")
    if workspace_id is not None and not (isinstance(workspace_id, str) and WORKSPACE_ID_RE.fullmatch(workspace_id)):
        raise ValueError("id is invalid")
    if operation == "register":
        for key in ("id", "label", "appPackage"):
            if not str(params.get(key) or "").strip():
                raise ValueError(f"{key} is required to register a workspace")
    if operation in {"switch", "arm"} and not str(params.get("id") or "").strip():
        raise ValueError("id is required")


def _as_workspace_id(value: Any) -> str | None:
    if isinstance(value, str) and WORKSPACE_ID_RE.fullmatch(value):
        return value
    return None


def _as_workspace_generation(value: Any) -> int | None:
    if type(value) is int and value >= 0:
        return value
    return None


def _workspace_identity(value: Any, *, _depth: int = 0) -> tuple[str, int] | None:
    if not isinstance(value, dict) or _depth > 4:
        return None
    workspace_id = _as_workspace_id(value.get("workspaceId") or value.get("workspace_id"))
    generation = None
    for key in ("workspaceGeneration", "workspace_generation", "generation"):
        if key in value:
            generation = _as_workspace_generation(value.get(key))
            break
    holder = value.get("holder")
    if workspace_id is None:
        if isinstance(holder, str):
            workspace_id = _as_workspace_id(holder)
        elif isinstance(holder, dict):
            workspace_id = _as_workspace_id(holder.get("id") or holder.get("workspaceId"))
            if generation is None:
                generation = _as_workspace_generation(
                    holder.get("workspaceGeneration") if "workspaceGeneration" in holder else holder.get("generation")
                )
    if workspace_id is not None and generation is not None:
        return workspace_id, generation
    for key in _WORKSPACE_NEST_KEYS:
        nested = _workspace_identity(value.get(key), _depth=_depth + 1)
        if nested:
            return nested
    return None


def _workspace_holder(value: Any, *, _depth: int = 0) -> Any:
    if not isinstance(value, dict) or _depth > 4:
        return _HOLDER_MISSING
    if "holder" in value:
        return value["holder"]
    for key in _WORKSPACE_NEST_KEYS:
        nested = _workspace_holder(value.get(key), _depth=_depth + 1)
        if nested is not _HOLDER_MISSING:
            return nested
    return _HOLDER_MISSING


def _execution_ok_false(value: Any) -> bool:
    if not isinstance(value, dict):
        return False
    execution = value.get("execution")
    if isinstance(execution, dict):
        if execution.get("ok") is False:
            return True
        nested = execution.get("androidExecution")
        if not isinstance(nested, dict):
            nested = execution.get("android_execution")
        if isinstance(nested, dict) and nested.get("ok") is False:
            return True
    return False


def _compact_layer2_envelope(result: Any) -> dict[str, Any] | None:
    if _execution_ok_false(result):
        return None
    identity = _workspace_identity(result)
    if identity is None:
        return None
    envelope: dict[str, Any] = {
        "workspaceId": identity[0],
        "workspaceGeneration": identity[1],
        "next": _LAYER2_NEXT,
    }
    if isinstance(result, dict):
        for key in ("holder", "gated", "workspaces", "status"):
            if key in result:
                envelope[key] = result[key]
        for nest_key in _WORKSPACE_NEST_KEYS:
            nested = result.get(nest_key)
            if not isinstance(nested, dict):
                continue
            for key in ("holder", "gated", "workspaces", "status"):
                if key not in envelope and key in nested:
                    envelope[key] = nested[key]
    return envelope


def _element_index_from_params(params: dict[str, Any]) -> int | None:
    selector = params.get("selector") if isinstance(params.get("selector"), dict) else {}
    raw = next((params.get(key) for key in ELEMENT_INDEX_KEYS if params.get(key) not in (None, "")), None)
    if raw is None:
        raw = next((selector.get(key) for key in ELEMENT_INDEX_KEYS if selector.get(key) not in (None, "")), None)
    if raw in (None, ""):
        return None
    try:
        index = int(raw)
    except (TypeError, ValueError) as exc:
        raise ValueError("elementIndex must be a positive integer from the current Page Card") from exc
    if index < 1:
        raise ValueError("elementIndex must be a positive integer from the current Page Card")
    return index


def _error_class(value: Any, fallback: str) -> str:
    failure = classify_failure(value)
    return failure.code if failure else fallback


class PhoneTools:
    def __init__(self, gateway: GatewayClient | None = None, recorder: SessionRecorder | None = None):
        self.gateway = gateway or GatewayClient()
        self.recorder = recorder or SessionRecorder()
        self.last_call_failed = False
        self._page_cards: dict[str, tuple[Any, dict[str, Any]]] = {}
        self._current_element_ids: dict[str, set[str]] = {}
        self._layer2_leases: dict[str, dict[str, Any]] = {}

    def call(self, name: str, arguments: dict[str, Any]) -> list[dict[str, Any]]:
        started = time.perf_counter()
        ok = False
        result: Any = None
        try:
            method: Callable[[dict[str, Any]], Any] = getattr(self, name)
            result = method(arguments)
            ok = not _result_failed(result)
            self.last_call_failed = not ok
            return _to_mcp_content(result)
        except SessionScopeError as exc:
            result = session_scope_error_result(exc)
            self.last_call_failed = True
            return _to_mcp_content(result)
        except (AttributeError, GatewayError, ValueError, OSError) as exc:
            gateway_body = exc.body if isinstance(exc, GatewayError) else None
            result = {"error": str(exc), "gateway": gateway_body}
            self.last_call_failed = True
            return [{"type": "text", "text": _json_text(result)}]
        finally:
            self.recorder.record(name, arguments, result, ok, int((time.perf_counter() - started) * 1000))

    def _only_paired_phone(self) -> str:
        """With no device_id, the one paired phone in the PC's fleet (alpha 88): status and capabilities then read the
        same phone phone_devices shows, instead of the legacy single-phone surface that can disagree with it."""
        fleet = getattr(self.gateway, "_fleet_devices", None)
        if not callable(fleet):
            return ""
        try:
            devices, surface = fleet()
        except Exception:
            return ""
        if surface != "fleet" or not isinstance(devices, list):
            return ""
        paired = [str(d.get("deviceId") or d.get("id") or "") for d in devices if isinstance(d, dict) and d.get("paired") is True]
        paired = [d for d in paired if d]
        return paired[0] if len(paired) == 1 else ""

    def phone_status(self, args: dict[str, Any]) -> Any:
        device_id = _device_id(args) or self._only_paired_phone()
        if device_id:
            return _with_sessions_inventory(redact(self.gateway.device_status(device_id)))
        return _with_sessions_inventory(redact(self.gateway.status()))

    def phone_capabilities(self, args: dict[str, Any]) -> Any:
        device_id = _device_id(args) or self._only_paired_phone()
        refresh = bool(args.get("refresh", False))
        if device_id:
            return redact(self.gateway.device_capabilities(device_id, refresh=refresh))
        return redact(self.gateway.capabilities(refresh=refresh))

    def phone_devices(self, args: dict[str, Any]) -> Any:
        return redact(self.gateway.devices(scan=bool(args.get("scan", False))))

    def phone_list(self, args: dict[str, Any]) -> Any:
        return self.phone_devices(args)

    def phone_observe(self, args: dict[str, Any]) -> Any:
        device_id = _device_id(args)
        mode = str(args.get("mode") or "compact")
        include_screenshot = bool(args.get("include_screenshot", False))
        goal = str(args.get("goal") or "").strip()
        scope = require_tool_execution_scope(args)
        identity = _identity_kwargs(scope)
        if device_id:
            raw = self.gateway.device_observe(device_id, include_screenshot=include_screenshot, mode=mode, **identity)
        else:
            raw = self.gateway.observe(include_screenshot=include_screenshot, mode=mode, **identity)
        # Classify the complete typed response before compacting away protocol/error layers.
        if classify_failure(raw):
            return redact(raw)
        if mode == "full":
            return redact(raw)
        return self._remember_page_card(device_id, raw, goal=goal, session_id=_session_id(scope))

    def phone_locate(self, args: dict[str, Any]) -> Any:
        """Fuse bounded readiness, Page Card context, and semantic search for one goal."""
        device_id = _device_id(args)
        goal = str(args.get("goal") or "").strip()
        if not goal:
            raise ValueError("goal is required")
        query = str(args.get("query") or goal).strip()
        if len(query) > 240:
            raise ValueError("query exceeds the bounded length")
        scope = require_tool_execution_scope(args)
        identity = _identity_kwargs(scope)
        session_id = _session_id(scope)
        if device_id:
            status = self.gateway.device_status(device_id)
            raw = self.gateway.device_observe(device_id, include_screenshot=False, mode="compact", **identity)
        else:
            status = self.gateway.status()
            raw = self.gateway.observe(include_screenshot=False, mode="compact", **identity)
        if classify_failure(raw):
            return redact(raw)
        page_card = self._remember_page_card(device_id, raw, goal=goal, session_id=session_id)
        try:
            search_raw = (
                self.gateway.device_ui_search(device_id, query, **identity)
                if device_id else self.gateway.ui_search(query, **identity)
            )
            semantic_search = compact_search(search_raw, query=query, goal=goal)
            self._remember_search_ids(device_id, semantic_search, session_id=session_id)
        except GatewayError as exc:
            semantic_search = {
                "kind": "semantic_search",
                "query": query,
                "available": False,
                "errorClass": _error_class(exc.body, "SEARCH_UNAVAILABLE"),
            }
        return {
            "kind": "phone_locate",
            "goal": goal,
            "status": _compact_status(status),
            "pageCard": page_card,
            "semanticSearch": semantic_search,
            "next": (
                "Use a goal-ranked/current elementId immediately, then call phone_act. "
                "After any mutation, use phone_locate again; IDs are not reusable."
            ),
        }

    def phone_ui_search(self, args: dict[str, Any]) -> Any:
        query = str(args.get("query") or "").strip()
        if not query:
            raise ValueError("query is required")
        goal = str(args.get("goal") or "").strip()
        device_id = _device_id(args)
        scope = require_tool_execution_scope(args)
        identity = _identity_kwargs(scope)
        if device_id:
            search = compact_search(self.gateway.device_ui_search(device_id, query, **identity), query=query, goal=goal)
        else:
            search = compact_search(self.gateway.ui_search(query, **identity), query=query, goal=goal)
        self._remember_search_ids(device_id, search, session_id=_session_id(scope))
        return search

    def phone_inspect_element(self, args: dict[str, Any]) -> Any:
        element_id = str(args.get("element_id") or "").strip()
        if not element_id:
            raise ValueError("element_id is required")
        device_id = _device_id(args)
        identity = _identity_kwargs(require_tool_execution_scope(args))
        if device_id:
            return compact_element(self.gateway.device_ui_element(device_id, element_id, **identity), element_id=element_id)
        return compact_element(self.gateway.ui_element(element_id, **identity), element_id=element_id)

    def phone_screenshot(self, args: dict[str, Any]) -> Any:
        device_id = _device_id(args)
        identity = _identity_kwargs(require_tool_execution_scope(args))
        if device_id:
            observed = self.gateway.device_observe(device_id, include_screenshot=True, mode="compact", **identity)
            compact = compact_observation(observed)
            screenshot = observed.get("screenshot") if isinstance(observed, dict) else None
            available = isinstance(screenshot, dict) and screenshot.get("available") is not False
            result: dict[str, Any] = {
                "observation": compact,
                "screenshot": screenshot,
                "screenshotAvailable": available,
            }
            if not available:
                result["note"] = (
                    "The Desktop agent endpoint returns semantic evidence only. Use the legacy "
                    "single-device surface (omit device_id) for image bytes, or the PC Companion "
                    "live video; a debug bundle remains available for diagnostics."
                )
            return result
        observed = self.gateway.observe(include_screenshot=True, mode="compact", **identity)
        compact = compact_observation(observed)
        screenshot = compact.get("screenshot")
        path = _extract_screenshot_path(screenshot)
        result: dict[str, Any] = {"observation": compact, "screenshot": screenshot}
        if path and Path(path).is_file():
            data = Path(path).read_bytes()
            max_bytes = int(os.getenv("CYCLONE_PHONE_MCP_MAX_IMAGE_BYTES", str(8 * 1024 * 1024)))
            if len(data) <= max_bytes:
                mime = mimetypes.guess_type(path)[0] or "image/png"
                result["_mcp_image"] = {"mimeType": mime, "data": base64.b64encode(data).decode("ascii")}
            else:
                result["imageNote"] = f"Screenshot exists but exceeds MCP image limit ({len(data)} > {max_bytes})"
        return result

    def phone_current_page(self, args: dict[str, Any]) -> Any:
        device_id = _device_id(args)
        if device_id:
            return redact(self.gateway.device_current_page(device_id))
        return redact(self.gateway.current_page())

    def phone_page_history(self, args: dict[str, Any]) -> Any:
        device_id = _device_id(args)
        if device_id:
            return redact(self.gateway.device_page_history(device_id))
        return redact(self.gateway.page_history())

    def phone_workspace(self, args: dict[str, Any]) -> Any:
        """Layer 2 management, with the same trusted gateway/Android authority as phone_act."""
        operation = args.get("operation")
        if operation not in WORKSPACE_OPERATIONS:
            raise ValueError("Unknown workspace operation")
        scope = require_tool_execution_scope(args)
        if scope["sessionId"] != "default-foreground" or scope["displayId"] != 0:
            raise ValueError("Layer 2 workspaces require default-foreground / display 0")
        params = args.get("params", {})
        if params is None:
            params = {}
        if not isinstance(params, dict):
            raise ValueError("params must be an object")
        if set(params) - WORKSPACE_PARAM_KEYS:
            raise ValueError("Unexpected workspace parameter")
        _validate_typed_params(params)
        _validate_workspace_operation_params(str(operation), params)
        identity = _identity_kwargs(scope)
        device_id = _device_id(args)
        goal = str(params.get("goal") or ("Manage Cyclone workspace: " + str(operation)))
        forwarded = attach_execution_scope(params, scope)
        if device_id:
            self.gateway.device_observe(device_id, include_screenshot=False, mode="compact", **identity)
            result = redact(self.gateway.device_action(device_id, "workspace." + str(operation), forwarded, goal, **identity))
        else:
            self.gateway.observe(include_screenshot=False, mode="compact", **identity)
            result = redact(self.gateway.action("workspace." + str(operation), forwarded, goal, **identity))
        self._update_layer2_lease(device_id, str(operation), result)
        if str(operation) in {"switch", "next"}:
            compact = _compact_layer2_envelope(result)
            if compact is not None:
                return compact
        return result

    def phone_act(self, args: dict[str, Any]) -> Any:
        tool = str(args.get("tool") or "")
        if tool not in ALLOWED_ACTIONS:
            raise ValueError(f"Unsupported phone action: {tool}")
        params = args.get("params") or {}
        if not isinstance(params, dict):
            raise ValueError("params must be an object")
        scope = require_tool_execution_scope(args)
        action_params = strip_execution_scope(params)
        _validate_mcp_action_params(tool, action_params)
        goal = str(args.get("goal") or "").strip()
        if not goal:
            raise ValueError("goal is required")
        if tool == "phone.type" and args.get("user_authorized") is not True:
            # This is only an MCP-side intent/UX guard. It is never Android policy authority;
            # the V3 GatewayActionAuthority must still authorize the actual handoff.
            raise ValueError("phone.type requires user_authorized=true as an explicit MCP intent acknowledgement")
        action_params = _forward_type_authorization(tool, args, action_params)
        action_params = _forward_ai_control(args, action_params)
        device_id = _device_id(args)
        self._require_layer2_mutate_lock(device_id, tool, action_params, scope)
        action_params = attach_execution_scope(action_params, scope)
        if tool not in MUTATING_ACTIONS:
            return self._run_non_mutating_action(device_id, tool, action_params, goal, scope=scope)
        return self._run_verified_mutation(device_id, tool, action_params, goal, scope=scope)

    def phone_group_act(self, args: dict[str, Any]) -> Any:
        raw_ids = args.get("device_ids")
        if not isinstance(raw_ids, list) or not raw_ids:
            raise ValueError("device_ids must be a non-empty array of explicit Cyclone device ids")
        device_ids = [str(value).strip() for value in raw_ids]
        if any(not value or len(value) > 160 for value in device_ids):
            raise ValueError("device_ids contains an invalid device id")
        if len(device_ids) > 32 or len(set(device_ids)) != len(device_ids):
            raise ValueError("device_ids must contain 1..32 unique explicit targets")
        tool = str(args.get("tool") or "")
        if tool not in ALLOWED_GROUP_ACTIONS:
            raise ValueError(f"Unsupported group phone action: {tool}")
        params = args.get("params") or {}
        if not isinstance(params, dict):
            raise ValueError("params must be an object")
        scope = require_tool_execution_scope(args)
        identity = _identity_kwargs(scope)
        action_params = strip_execution_scope(params)
        _validate_mcp_action_params(tool, action_params)
        action_params = attach_execution_scope(action_params, scope)
        goal = str(args.get("goal") or "").strip()
        if not goal:
            raise ValueError("goal is required")
        results: list[dict[str, Any]] = []
        for device_id in device_ids:
            try:
                before = self.gateway.device_observe(device_id, include_screenshot=False, mode="compact", **identity)
                outcome = redact(self.gateway.device_action(device_id, tool, action_params, goal, **identity))
                failure = classify_failure(outcome)
                results.append({
                    "device_id": device_id,
                    "ok": failure is None,
                    "before": compact_observation(before),
                    "outcome": outcome,
                    "failure": None if failure is None else {"code": failure.code, "layer": failure.layer},
                })
            except GatewayError as exc:
                results.append({"device_id": device_id, "ok": False, "error": redact(exc.body)})
        return {
            "operation": "typed_group_action",
            "tool": tool,
            "selected_device_ids": device_ids,
            "ok": all(item["ok"] for item in results),
            "results": results,
        }

    def _scope_key(self, device_id: str, session_id: str | None = None) -> str:
        return scope_cache_key(device_id, session_id)

    def _layer2_lease_key(self, device_id: str) -> str:
        return str(device_id or "")

    def _update_layer2_lease(self, device_id: str, operation: str, result: Any) -> None:
        key = self._layer2_lease_key(device_id)
        if operation in {"pause", "release"}:
            self._layer2_leases.pop(key, None)
            return
        if operation == "list":
            holder = _workspace_holder(result)
            if holder is None:
                self._layer2_leases.pop(key, None)
                return
            identity = _workspace_identity(result)
            if identity is not None and not _execution_ok_false(result):
                self._layer2_leases[key] = {
                    "workspaceId": identity[0],
                    "workspaceGeneration": identity[1],
                }
            return
        if operation in {"switch", "next"}:
            identity = _workspace_identity(result)
            if identity is not None and not _execution_ok_false(result):
                self._layer2_leases[key] = {
                    "workspaceId": identity[0],
                    "workspaceGeneration": identity[1],
                }

    def _require_layer2_mutate_lock(
        self,
        device_id: str,
        tool: str,
        action_params: dict[str, Any],
        scope: dict[str, Any],
    ) -> None:
        workspace_id = action_params.get("workspaceId")
        workspace_generation = action_params.get("workspaceGeneration")
        if workspace_id is not None or workspace_generation is not None:
            if scope.get("sessionId") != "default-foreground" or scope.get("displayId") != 0:
                raise ValueError(
                    "workspaceId is only valid on default-foreground / display 0; Layer 2 is not a VD session"
                )
            _require_workspace_identity(workspace_id, workspace_generation)
        if tool not in MUTATING_ACTIONS:
            return
        lease = self._layer2_leases.get(self._layer2_lease_key(device_id))
        if not lease:
            return
        if workspace_id is None or workspace_generation is None:
            raise ValueError(
                "Layer 2 MUTATE_LOCK: mutating phone_act requires workspaceId and workspaceGeneration "
                "matching the current lease; do not invent ids"
            )
        if workspace_id != lease["workspaceId"] or workspace_generation != lease["workspaceGeneration"]:
            raise ValueError(
                "Layer 2 MUTATE_LOCK: stale generation or workspaceId does not match the current lease"
            )

    def _remember_page_card(
        self,
        device_id: str,
        raw: Any,
        *,
        goal: str = "",
        session_id: str | None = None,
    ) -> dict[str, Any]:
        card = compact_observation(raw, goal=goal)
        scope = self._scope_key(device_id, session_id)
        self._page_cards[scope] = (raw, card)
        self._current_element_ids[scope] = _card_element_ids(card)
        return card

    def _remember_search_ids(
        self,
        device_id: str,
        search: dict[str, Any],
        *,
        session_id: str | None = None,
    ) -> None:
        scope = self._scope_key(device_id, session_id)
        if scope not in self._page_cards:
            return
        current = self._current_element_ids.setdefault(scope, set())
        current.update(_search_element_ids(search))

    def _run_non_mutating_action(
        self,
        device_id: str,
        tool: str,
        params: dict[str, Any],
        goal: str,
        *,
        scope: dict[str, Any] | None = None,
    ) -> Any:
        identity = _identity_kwargs(scope)
        try:
            action = (
                self.gateway.device_action(device_id, tool, params, goal, **identity)
                if device_id else self.gateway.action(tool, params, goal, **identity)
            )
        except GatewayError as exc:
            return _failed_action_envelope(
                tool=tool,
                goal=goal,
                before=None,
                after=None,
                action=None,
                error_class=_error_class(exc.body, "GATEWAY_ERROR"),
                failure_layer="gateway",
                delta="The non-mutating action did not return a usable result.",
            )
        failure = classify_failure(action)
        return {
            "kind": "phone_action_result",
            "tool": tool,
            "goal": goal,
            "mutating": False,
            "actionStatus": _action_status(action, failure, after_observed=False),
            "beforePageCard": None,
            "afterPageCard": None,
            "pageChanged": None,
            "delta": "No mutation was requested.",
            "errorClass": failure.code if failure else None,
            "ok": failure is None,
            "error": None if failure is None else {"code": failure.code, "layer": failure.layer},
        }

    def _auto_observe(self, device_id: str, goal: str, scope: dict[str, Any] | None = None):
        identity = _identity_kwargs(scope)
        try:
            raw = (
                self.gateway.device_observe(device_id, include_screenshot=False, mode="compact", **identity)
                if device_id else self.gateway.observe(include_screenshot=False, mode="compact", **identity)
            )
        except GatewayError:
            return None
        if classify_failure(raw) is not None:
            return None
        card = self._remember_page_card(device_id, raw, goal=goal, session_id=_session_id(scope))
        return raw, card

    def _resolve_snapshot_target(self, scope: str, params: dict[str, Any]):
        cached = self._page_cards.get(scope)
        card = cached[1] if cached else {}
        refs = card.get("refs") if isinstance(card.get("refs"), dict) else {}
        selector = params.get("selector") if isinstance(params.get("selector"), dict) else {}
        ref = str(params.get("ref") or selector.get("ref") or "").strip()
        role = str(params.get("role") or selector.get("role") or "").strip()
        name = str(params.get("name") or selector.get("name") or "").strip()
        resolved = dict(params)
        if ref:
            host = refs.get(ref)
            if not isinstance(host, dict) or not host.get("elementId"):
                return "STALE_OBSERVATION"
            resolved["elementId"] = host["elementId"]
            return resolved
        try:
            element_index = _element_index_from_params(params)
        except ValueError:
            return "STALE_OBSERVATION"
        if element_index:
            hosts = list(refs.values())
            candidates = card.get("candidates") if isinstance(card.get("candidates"), dict) else {}
            hosts.extend(list(candidates.get("current") or []) + list(candidates.get("goalRanked") or []))
            hosts.extend(list(card.get("controls") or []))
            for host in hosts:
                if not isinstance(host, dict):
                    continue
                if host.get("elementIndex") == element_index or host.get("element_index") == element_index:
                    if host.get("elementId"):
                        resolved["elementId"] = host["elementId"]
                        return resolved
            return "STALE_OBSERVATION"
        if role and name:
            needle = name.lower()
            for host in refs.values():
                if not isinstance(host, dict):
                    continue
                if str(host.get("role") or "") == role and str(host.get("name") or "").lower() == needle:
                    resolved["elementId"] = host["elementId"]
                    return resolved
            return "STALE_OBSERVATION"
        return resolved

    def _run_verified_mutation(
        self,
        device_id: str,
        tool: str,
        params: dict[str, Any],
        goal: str,
        *,
        scope: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        cache_key = self._scope_key(device_id, _session_id(scope))
        identity = _identity_kwargs(scope)
        cached = self._page_cards.get(cache_key)
        if cached is None:
            cached = self._auto_observe(device_id, goal, scope)
        if cached is None:
            return _failed_action_envelope(
                tool=tool,
                goal=goal,
                before=None,
                after=None,
                action=None,
                error_class="STALE_OBSERVATION",
                failure_layer="protocol",
                delta="No current Page Card is available. Run phone_locate or phone_observe before acting.",
            )
        resolved = self._resolve_snapshot_target(cache_key, params)
        if resolved == "STALE_OBSERVATION":
            return _failed_action_envelope(
                tool=tool,
                goal=goal,
                before=cached[1],
                after=None,
                action=None,
                error_class="STALE_OBSERVATION",
                failure_layer="protocol",
                delta="The ref is not from the current snapshot. Locate again before acting.",
            )
        params = resolved
        if isinstance(params, dict) and params.get("fastPath") is not False:
            params = dict(params)
            params["fastPath"] = True
        params = attach_execution_scope(params, scope)
        element_id = _element_id_from_params(params)
        if element_id and element_id not in self._current_element_ids.get(cache_key, set()):
            return _failed_action_envelope(
                tool=tool,
                goal=goal,
                before=cached[1],
                after=None,
                action=None,
                error_class="STALE_OBSERVATION",
                failure_layer="protocol",
                delta="The elementId is not from the current Page Card/search scope. Locate again before acting.",
            )
        before_raw, _ = cached
        before = compact_observation(before_raw, goal=goal)
        # The gateway clears its observation authority after an action. Clear our model-facing
        # cache at the same boundary so an ID can never be accidentally reused.
        self._page_cards.pop(cache_key, None)
        self._current_element_ids.pop(cache_key, None)
        action: Any = None
        failure = None
        action_error: GatewayError | None = None
        try:
            action = (
                self.gateway.device_action(device_id, tool, params, goal, **identity)
                if device_id else self.gateway.action(tool, params, goal, **identity)
            )
            failure = classify_failure(action)
        except GatewayError as exc:
            action_error = exc
            action = redact(exc.body) if exc.body is not None else {"error": str(exc)}
            failure = classify_failure(action)

        after: dict[str, Any] | None = None
        after_failure = None
        # A 200 transport receipt never proves that Android changed state. When the gateway is
        # still reachable, observe again even after execution/verification failure for evidence.
        action_error_class = failure.code if failure else ("GATEWAY_ERROR" if action_error else None)
        can_observe_after = action_error_class not in {"DEVICE_DISCONNECTED", "AUTH_REJECTED"}
        if can_observe_after:
            try:
                after_raw = (
                    self.gateway.device_observe(device_id, include_screenshot=False, mode="compact", **identity)
                    if device_id else self.gateway.observe(include_screenshot=False, mode="compact", **identity)
                )
                after_failure = classify_failure(after_raw)
                if after_failure is None:
                    after = self._remember_page_card(
                        device_id, after_raw, goal=goal, session_id=_session_id(scope),
                    )
            except GatewayError as exc:
                after_failure = classify_failure(exc.body)
                if after_failure is None:
                    after_failure = Failure("AFTER_OBSERVATION_FAILED", "transport")

        changed = page_changed(before, after)
        delta = page_delta(before, after, changed)
        typed_verification = _gateway_verification_passed(action)
        already_on_page = (
            _android_execution_ok(action)
            and after is not None
            and changed is False
            and _page_has_goal_label(after, goal)
        )
        already_on_home = (
            tool == "phone.home"
            and _android_execution_ok(action)
            and after is not None
            and _page_is_home_launcher(after)
        )
        if already_on_home:
            already_on_page = True
        if failure is not None and failure.code == "VERIFICATION_FAILED" and already_on_page:
            failure = None
        if not typed_verification and already_on_page:
            typed_verification = True
        verified = failure is None and action_error is None and typed_verification and after is not None
        if failure is not None:
            error_class, failure_layer = failure.code, failure.layer
        elif action_error is not None:
            error_class, failure_layer = "GATEWAY_ERROR", "gateway"
        elif after_failure is not None:
            error_class, failure_layer = after_failure.code, after_failure.layer
        elif not typed_verification:
            error_class, failure_layer = "VERIFICATION_REQUIRED", "verification"
        elif after is None:
            error_class, failure_layer = "AFTER_OBSERVATION_FAILED", "verification"
        else:
            error_class, failure_layer = None, None
        return {
            "kind": "phone_action_result",
            "tool": tool,
            "goal": goal,
            "mutating": True,
            "actionStatus": _action_status(
                action, failure, after_observed=after is not None, verification_passed=typed_verification,
            ),
            "beforePageCard": before,
            "afterPageCard": after,
            "pageChanged": changed,
            "delta": delta,
            "errorClass": error_class,
            "failureLayer": failure_layer,
            "ok": verified,
            "error": None if verified else {"code": error_class, "layer": failure_layer},
        }

    def phone_debug_bundle(self, args: dict[str, Any]) -> Any:
        device_id = _device_id(args)
        expected = str(args.get("expected") or "")
        goal = str(args.get("goal") or "")
        if device_id:
            result = redact(self.gateway.device_debug_bundle(device_id, expected, goal))
        else:
            result = redact(self.gateway.debug_bundle(expected, goal))
        stage = _find_stage(result)
        if stage and stage not in FAILURE_CLASSES:
            result = dict(result) if isinstance(result, dict) else {"result": result}
            result["diagnosticWarning"] = f"Unknown failure classification: {stage}"
        return result

    def phone_teach_start(self, args: dict[str, Any]) -> Any:
        device_id = _device_id(args)
        goal = str(args.get("goal") or "")
        if device_id:
            return redact(self.gateway.device_teach_start(device_id, goal))
        return redact(self.gateway.teach_start(goal))

    def phone_teach_status(self, args: dict[str, Any]) -> Any:
        device_id = _device_id(args)
        if device_id:
            return redact(self.gateway.device_teach_status(device_id))
        return redact(self.gateway.teach_status())

    def phone_teach_stop(self, args: dict[str, Any]) -> Any:
        device_id = _device_id(args)
        compile_for_review = bool(args.get("compile_for_review", True))
        if device_id:
            return redact(self.gateway.device_teach_stop(device_id, compile_for_review))
        return redact(self.gateway.teach_stop(compile_for_review))

    def phone_virtual_list(self, _: dict[str, Any]) -> Any:
        _only_keys(_, set())
        return redact(self.gateway.virtual_instances())

    def phone_virtual_create(self, args: dict[str, Any]) -> Any:
        _only_keys(args, {"provider", "image"})
        provider = _required_id(args, "provider", PROVIDER_ID)
        image = _required_id(args, "image", IMAGE_ID)
        return redact(self.gateway.virtual_create(provider, image))

    def phone_virtual_start(self, args: dict[str, Any]) -> Any:
        _only_keys(args, {"instance_id"})
        instance_id = _required_id(args, "instance_id", INSTANCE_ID)
        return redact(self.gateway.virtual_lifecycle(instance_id, "start"))

    def phone_virtual_stop(self, args: dict[str, Any]) -> Any:
        _only_keys(args, {"instance_id"})
        instance_id = _required_id(args, "instance_id", INSTANCE_ID)
        return redact(self.gateway.virtual_lifecycle(instance_id, "stop"))

    def phone_routine_run(self, args: dict[str, Any]) -> Any:
        _only_keys(args, {"device_id", "routine_id"})
        device_id = _required_id(args, "device_id", TARGET_ID)
        routine_id = _required_id(args, "routine_id", ROUTINE_ID)
        return redact(self.gateway.routine_run(device_id, routine_id))

    def phone_routine_status(self, args: dict[str, Any]) -> Any:
        _only_keys(args, {"device_id", "run_id"})
        device_id = _required_id(args, "device_id", TARGET_ID)
        run_id = _required_id(args, "run_id", RUN_ID)
        return redact(self.gateway.routine_status(device_id, run_id))

    def phone_routine_cancel(self, args: dict[str, Any]) -> Any:
        _only_keys(args, {"device_id", "run_id"})
        device_id = _required_id(args, "device_id", TARGET_ID)
        run_id = _required_id(args, "run_id", RUN_ID)
        return redact(self.gateway.routine_cancel(device_id, run_id))

    def phone_lab_missions(self, args: dict[str, Any]) -> Any:
        _only_keys(args, set())
        return redact(self.gateway.lab_missions())

    def phone_lab_start(self, args: dict[str, Any]) -> Any:
        """Start a lab experiment. Variants change only named knobs; boundaries are the phone's and cannot be relaxed."""
        _only_keys(args, {"device_id", "name", "missions", "variants", "repetitions"})
        device_id = _required_id(args, "device_id", TARGET_ID)
        name = args.get("name")
        if not isinstance(name, str) or not 0 < len(name.strip()) <= 80:
            raise ValueError("name is 1..80 characters")
        missions = args.get("missions")
        if not isinstance(missions, list) or not 1 <= len(missions) <= 100 or not all(isinstance(m, str) and LAB_MISSION_ID.match(m) for m in missions):
            raise ValueError("missions is a list of lab mission ids (see phone_lab_missions)")
        variants = args.get("variants") or [{"name": "A"}]
        if not isinstance(variants, list) or not 1 <= len(variants) <= 4 or not all(
            isinstance(v, dict) and set(v) <= LAB_VARIANT_KEYS and isinstance(v.get("name"), str) for v in variants
        ):
            raise ValueError("variants is 1..4 objects with a name and only known knobs")
        repetitions = args.get("repetitions", 1)
        if isinstance(repetitions, bool) or not isinstance(repetitions, int) or not 1 <= repetitions <= 20:
            raise ValueError("repetitions is 1..20")
        return redact(self.gateway.lab_start(device_id, name.strip(), missions, variants, repetitions))

    def phone_lab_report(self, args: dict[str, Any]) -> Any:
        """Without an id: the experiment list. With one: rates, A/B, insights and the failed runs, compacted."""
        _only_keys(args, {"experiment_id"})
        if not args.get("experiment_id"):
            return redact(self.gateway.lab_experiments())
        detail = self.gateway.lab_experiment(_required_id(args, "experiment_id", LAB_EXPERIMENT_ID))
        if not isinstance(detail, dict) or "trials" not in detail:
            return redact(detail)
        failures = []
        for trial in detail["trials"]:
            if not isinstance(trial, dict) or trial.get("verdict") == "pass":
                continue
            phone = trial.get("phone") or {}
            metrics = phone.get("metrics") or {}
            failures.append({
                "mission": trial.get("missionId"), "variant": trial.get("variant"), "verdict": trial.get("verdict"),
                "category": trial.get("category"), "cause": trial.get("cause"), "signals": trial.get("signals"),
                "checks": trial.get("checks"), "summary": phone.get("summary"), "turns": phone.get("turns"),
                "errorTail": metrics.get("errorTail"), "toolCalls": metrics.get("toolCalls"), "traceId": phone.get("traceId"),
            })
        return redact({**{k: v for k, v in detail.items() if k != "trials"}, "failures": failures[:60]})

    def phone_ask(self, args: dict[str, Any]) -> Any:
        """Alpha 91: send one request exactly as the owner would type it, and (optionally) wait for its outcome: the lane
        that took it (instant, answer, ignore, flash, mind), who decided, and the times. Approvals stay on the phone."""
        _only_keys(args, {"device_id", "goal", "wait_s"})
        device_id = _required_id(args, "device_id", TARGET_ID)
        goal = args.get("goal")
        if not isinstance(goal, str) or not 0 < len(goal.strip()) <= 2000:
            raise ValueError("goal is 1..2000 characters")
        wait_s = args.get("wait_s", 20)
        if not isinstance(wait_s, (int, float)) or not 0 <= wait_s <= 300:
            raise ValueError("wait_s is 0..300 seconds")
        started = time.monotonic()
        ack = self.gateway.ask_start(device_id, goal)
        request_id = ack.get("requestId") if isinstance(ack, dict) else None
        status: Any = None
        while wait_s and time.monotonic() - started < wait_s:
            time.sleep(0.4)
            status = self.gateway.ask_status(device_id, request_id)
            request = status.get("request") if isinstance(status, dict) else None
            if not isinstance(request, dict):
                continue
            if request.get("state") in {"done", "failed", "cancelled"}:
                break
            if request.get("lane") in {"flash", "mind"} and status.get("state") in {"done", "failed", "action-needed", "needs-secret"}:
                break
        return redact({"requestId": request_id, "waitedS": round(time.monotonic() - started, 2), "status": status or ack})

    def phone_ask_cancel(self, args: dict[str, Any]) -> Any:
        _only_keys(args, {"device_id", "request_id"})
        device_id = _required_id(args, "device_id", TARGET_ID)
        request_id = args.get("request_id")
        if request_id is not None and (not isinstance(request_id, str) or not re.match(r"^req-[0-9a-f-]{8,40}$", request_id)):
            raise ValueError("request_id is the id phone_ask returned")
        return redact(self.gateway.ask_cancel(device_id, request_id))

    def phone_lab_stop(self, args: dict[str, Any]) -> Any:
        _only_keys(args, {"experiment_id"})
        return redact(self.gateway.lab_stop(_required_id(args, "experiment_id", LAB_EXPERIMENT_ID)))


def _identity_kwargs(scope: dict[str, Any] | None) -> dict[str, Any]:
    if not scope:
        return {}
    return {"session_id": scope["sessionId"], "display_id": scope["displayId"]}


def _session_id(scope: dict[str, Any] | None) -> str | None:
    if not scope:
        return None
    return str(scope.get("sessionId") or "") or None


def _compact_status(status: Any) -> dict[str, Any]:
    """Keep locate useful without returning an unbounded gateway diagnostic object."""
    if not isinstance(status, dict):
        return {"available": False, "summary": "Gateway status was not an object."}
    nested_status = status.get("status") if isinstance(status.get("status"), dict) else {}
    health = status.get("gateway_health") if isinstance(status.get("gateway_health"), dict) else {}
    result: dict[str, Any] = {"available": True}
    for key in (
        "device_id", "deviceId", "serial", "serialSuffix", "model", "state", "ready",
        "cyclone_bridge_reachable", "bridgeReachable", "gatewayEnabled", "accessibilityEnabled",
    ):
        value = status.get(key, nested_status.get(key))
        if isinstance(value, (str, int, float, bool)):
            result[key] = value
    if health:
        result["gatewayHealth"] = {
            key: value for key, value in health.items()
            if key in {"state", "ready", "available"} and isinstance(value, (str, int, float, bool))
        }
    return redact(result)


def _card_element_ids(card: dict[str, Any]) -> set[str]:
    candidates = card.get("candidates") if isinstance(card.get("candidates"), dict) else {}
    rows = list(candidates.get("current") or []) + list(candidates.get("goalRanked") or [])
    ids = {
        str(item["elementId"]) for item in rows
        if isinstance(item, dict) and isinstance(item.get("elementId"), str) and item["elementId"]
    }
    refs = card.get("refs") if isinstance(card.get("refs"), dict) else {}
    for host in refs.values():
        if isinstance(host, dict) and isinstance(host.get("elementId"), str) and host["elementId"]:
            ids.add(host["elementId"])
    return ids


def _search_element_ids(search: dict[str, Any]) -> set[str]:
    return {
        str(item["elementId"]) for item in search.get("results", [])
        if isinstance(item, dict) and isinstance(item.get("elementId"), str) and item["elementId"]
    }


def _forward_type_authorization(tool: str, args: dict[str, Any], params: dict[str, Any]) -> dict[str, Any]:
    """Copy MCP intent flag into Android params. Never echo the typed plaintext here."""
    if tool != "phone.type" or args.get("user_authorized") is not True:
        return params
    forwarded = dict(params)
    forwarded["user_authorized"] = True
    forwarded["userAuthorized"] = True
    return forwarded


def _forward_ai_control(args: dict[str, Any], params: dict[str, Any]) -> dict[str, Any]:
    """Boolean-only Companion yield request. Never steals a locked phone or bypasses Android policy."""
    if args.get("request_ai_control") is not True:
        return params
    forwarded = dict(params)
    forwarded["request_ai_control"] = True
    return forwarded


def _with_sessions_inventory(result: Any) -> Any:
    """Pass through gateway sessions when present; never invent default-foreground."""
    if not isinstance(result, dict) or result.get("sessions") is not None:
        return result
    for key in ("status", "device", "runtime"):
        nested = result.get(key)
        if isinstance(nested, dict) and nested.get("sessions") is not None:
            out = dict(result)
            out["sessions"] = nested["sessions"]
            return out
    return result


def _android_execution_ok(action: Any) -> bool:
    if not isinstance(action, dict):
        return False
    execution = action.get("execution") if isinstance(action.get("execution"), dict) else {}
    if execution.get("ok") is True:
        return True
    nested = execution.get("androidExecution") if isinstance(execution.get("androidExecution"), dict) else {}
    if nested.get("ok") is True:
        return True
    android = action.get("android_execution") if isinstance(action.get("android_execution"), dict) else {}
    return android.get("ok") is True


_HOME_LAUNCHER_PAGE_KEYS = frozenset({"home", "launcher"})


def _page_is_home_launcher(card: dict[str, Any] | None) -> bool:
    if not isinstance(card, dict):
        return False
    location = card.get("location") if isinstance(card.get("location"), dict) else {}
    page_key = str(card.get("pageKey") or location.get("pageKey") or "").strip().lower()
    if page_key in _HOME_LAUNCHER_PAGE_KEYS:
        return True
    title = str(card.get("title") or location.get("title") or "").strip()
    if title == "Home":
        return True
    package = str(card.get("package") or location.get("package") or "")
    return "launcher" in package.lower()


def _page_has_goal_label(card: dict[str, Any] | None, goal: str) -> bool:
    needle = (goal or "").strip()
    if not needle or not isinstance(card, dict):
        return False
    parts = [
        str(card.get("pageText") or ""),
        str(card.get("pageSummary") or ""),
        str(card.get("title") or ""),
        str(card.get("snapshot") or ""),
    ]
    location = card.get("location") if isinstance(card.get("location"), dict) else {}
    parts.append(str(location.get("title") or ""))
    candidates = card.get("candidates") if isinstance(card.get("candidates"), dict) else {}
    for row in list(candidates.get("current") or []) + list(candidates.get("goalRanked") or []):
        if isinstance(row, dict):
            parts.append(str(row.get("label") or ""))
    for row in card.get("controls") or []:
        if isinstance(row, dict):
            parts.append(str(row.get("label") or ""))
    return needle.lower() in " ".join(parts).lower()


def _gateway_verification_passed(action: Any) -> bool:
    if not isinstance(action, dict):
        return False
    verification = action.get("verification")
    return (
        isinstance(verification, dict)
        and verification.get("ok") is True
        and verification.get("status") not in {None, "not_required", "required", "failed"}
    )


def _action_status(
    action: Any, failure: Any, *, after_observed: bool, verification_passed: bool | None = None,
) -> dict[str, Any]:
    action = action if isinstance(action, dict) else {}
    transport = action.get("transport") if isinstance(action.get("transport"), dict) else {}
    execution = action.get("execution") if isinstance(action.get("execution"), dict) else {}
    verification = action.get("verification") if isinstance(action.get("verification"), dict) else {}
    gw_passed = _gateway_verification_passed(action) if verification_passed is None else verification_passed
    return {
        "transport": "ok" if transport.get("ok") is True else ("failed" if transport else "unknown"),
        "execution": "ok" if execution.get("ok") is True else ("failed" if execution else "unknown"),
        "gatewayVerification": "passed" if gw_passed else (
            "failed" if verification else "unavailable"
        ),
        "afterObserved": after_observed,
        "verified": failure is None and gw_passed and after_observed,
    }


def _failed_action_envelope(
    *,
    tool: str,
    goal: str,
    before: dict[str, Any] | None,
    after: dict[str, Any] | None,
    action: Any,
    error_class: str,
    failure_layer: str,
    delta: str,
) -> dict[str, Any]:
    return {
        "kind": "phone_action_result",
        "tool": tool,
        "goal": goal,
        "mutating": True,
        "actionStatus": _action_status(action, None, after_observed=False),
        "beforePageCard": before,
        "afterPageCard": after,
        "pageChanged": page_changed(before, after),
        "delta": delta,
        "errorClass": error_class,
        "failureLayer": failure_layer,
        "ok": False,
        "error": {"code": error_class, "layer": failure_layer},
    }


def _find_stage(value: Any) -> str | None:
    if isinstance(value, dict):
        for key in ("stage", "failureClassification", "failure_classification"):
            if isinstance(value.get(key), str):
                return value[key]
        for nested in value.values():
            stage = _find_stage(nested)
            if stage:
                return stage
    return None


def _extract_screenshot_path(value: Any) -> str | None:
    if isinstance(value, str):
        return value if os.path.isabs(value) else None
    if isinstance(value, dict):
        for key in ("path", "file", "localPath", "local_path"):
            path = value.get(key)
            if isinstance(path, str) and os.path.isabs(path):
                return path
    return None


def _to_mcp_content(result: Any) -> list[dict[str, Any]]:
    if isinstance(result, dict) and "_mcp_image" in result:
        safe = dict(result)
        image = safe.pop("_mcp_image")
        return [
            {"type": "text", "text": _json_text(safe)},
            {"type": "image", "data": image["data"], "mimeType": image["mimeType"]},
        ]
    return [{"type": "text", "text": _json_text(result)}]


def _json_text(value: Any) -> str:
    import json
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))
