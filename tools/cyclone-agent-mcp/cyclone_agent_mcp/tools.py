from __future__ import annotations

import re
import time
from typing import Any, Callable

from .audit import SafeAuditLog
from .gateway import GatewayClient, GatewayError
from .safe import (
    redact,
    strip_typed_plaintext,
    validate_human_gesture_params,
    validate_typed_params,
)
from .tool_catalog import ALLOWED_ACTIONS, ALLOWED_GROUP_ACTIONS, TOOL_NAMES
from .phone_mcp import (
    compact_observation,
    draft_run_denied,
    is_session_scope_error,
    matched_verified_skill,
    parse_execution_scope,
    require_tool_execution_scope,
    session_scope_error_result,
    skill_run_normalize,
    skill_save_payload,
    skill_save_success,
    validate_open_app_params,
)

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


PROVIDER_ID = re.compile(r"^[a-z][a-z0-9_.-]{0,79}$")
IMAGE_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.;+:-]{0,239}$")
INSTANCE_ID = re.compile(r"^vdev_[a-f0-9]{16}$")
ROUTINE_ID = re.compile(r"^[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*$")
RUN_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$")
LAB_EXPERIMENT_ID = re.compile(r"^exp-[0-9]{8}-[0-9]{6}-[a-z0-9]{4}$")
LAB_MISSION_ID = re.compile(r"^[a-z0-9][a-z0-9._-]{2,63}$")
APP_PACKAGE = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$")
LAB_VARIANT_KEYS = frozenset({"name", "modelId", "effort", "workingMinutes", "marks", "freshMemory", "promptAddendum", "useMap"})
TARGET_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}$")


def _identity_kwargs(args: dict[str, Any]) -> dict[str, Any]:
    scope = parse_execution_scope(args)
    if not scope:
        return {}
    return {"session_id": scope["sessionId"], "display_id": scope["displayId"]}


def _required_identity_kwargs(args: dict[str, Any]) -> dict[str, Any]:
    scope = require_tool_execution_scope(args)
    return {"session_id": scope["sessionId"], "display_id": scope["displayId"]}


def _forward_type_authorization(tool: str, args: dict[str, Any], params: dict[str, Any]) -> dict[str, Any]:
    """Copy MCP intent flag into Android params. Never echo the typed plaintext here."""
    if tool != "phone.type" or args.get("user_authorized") is not True:
        return params
    forwarded = dict(params)
    forwarded["user_authorized"] = True
    forwarded["userAuthorized"] = True
    return forwarded


def _required_id(args: dict[str, Any], key: str, pattern: re.Pattern[str]) -> str:
    value = str(args.get(key) or "").strip()
    if not pattern.fullmatch(value):
        raise ValueError(f"{key} is invalid")
    return value


def _only_keys(args: dict[str, Any], allowed: set[str]) -> None:
    if set(args) - allowed:
        raise ValueError("Unexpected parameters are not permitted")


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


class PhoneTools:
    def __init__(self, gateway: GatewayClient | None = None, audit: SafeAuditLog | None = None):
        self.gateway = gateway or GatewayClient()
        self.audit = audit or SafeAuditLog()
        self._layer2_leases: dict[str, dict[str, Any]] = {}

    def call(self, name: str, arguments: dict[str, Any]) -> Any:
        if name not in TOOL_NAMES:
            return {"error": {"code": "UNKNOWN_TOOL", "message": "Unknown Cyclone MCP tool"}}
        started = time.perf_counter()
        ok = False
        error_code: str | None = None
        try:
            method: Callable[[dict[str, Any]], Any] = getattr(self, name)
            result = redact(method(arguments))
            error_code = _error_code(result)
            ok = error_code is None
            return result
        except GatewayError as exc:
            body = redact(exc.body) if exc.body is not None else {"error": {"code": "GATEWAY_ERROR"}}
            error_code = _error_code(body) or "GATEWAY_ERROR"
            return body
        except (ValueError, OSError) as exc:
            if is_session_scope_error(exc):
                body = session_scope_error_result(exc)
                error_code = str(body.get("errorClass") or "SESSION_REQUIRED")
                return redact(body)
            error_code = "INVALID_REQUEST"
            return {"error": {"code": error_code, "message": str(exc)[:300]}}
        finally:
            self.audit.record(name, ok=ok, elapsed_ms=int((time.perf_counter() - started) * 1000), error_code=error_code)

    @staticmethod
    def _device(args: dict[str, Any]) -> str | None:
        value = args.get("device_id")
        if value is None:
            return None
        value = str(value).strip()
        return value or None

    def _update_layer2_lease(self, device_id: str, operation: str, result: Any) -> None:
        key = str(device_id or "")
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
        lease = self._layer2_leases.get(str(device_id or ""))
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

    def phone_list(self, _: dict[str, Any]) -> Any:
        return {"devices": [device.safe_dict() for device in self.gateway.list_devices()]}

    def phone_status(self, args: dict[str, Any]) -> Any:
        return _with_sessions_inventory(self.gateway.status(self._device(args)))

    def phone_capabilities(self, args: dict[str, Any]) -> Any:
        return self.gateway.capabilities(self._device(args), refresh=bool(args.get("refresh", False)))

    def phone_observe(self, args: dict[str, Any]) -> Any:
        mode = str(args.get("mode") or "compact")
        if mode not in {"compact", "full"}:
            raise ValueError("mode must be compact or full")
        return self.gateway.observe(
            self._device(args),
            include_screenshot=bool(args.get("include_screenshot", False)),
            mode=mode,
            **_required_identity_kwargs(args),
        )

    def phone_locate(self, args: dict[str, Any]) -> Any:
        goal = str(args.get("goal") or "").strip()
        if not goal:
            raise ValueError("goal is required")
        query = str(args.get("query") or goal).strip()
        device_id = self._device(args)
        identity = _required_identity_kwargs(args)
        status = self.gateway.status(device_id)
        raw = self.gateway.observe(device_id, include_screenshot=False, mode="compact", **identity)
        page_card = compact_observation(raw, goal=goal)
        try:
            search_raw = self.gateway.ui_search(query, device_id, **identity)
        except GatewayError as exc:
            search_raw = {"available": False, "error": redact(exc.body)}
        matched = None
        skip_model = False
        try:
            match_raw = self.gateway.skill_match(goal, str(page_card.get("pageKey") or ""), device_id)
            matched = matched_verified_skill(match_raw, goal, str(page_card.get("pageKey") or ""))
            skip_model = bool(matched)
        except (GatewayError, AttributeError, TypeError, ValueError, ImportError):
            matched = None
            skip_model = False
        return {
            "kind": "phone_locate",
            "goal": goal,
            "status": status if isinstance(status, dict) else {"available": False},
            "pageCard": page_card,
            "semanticSearch": search_raw,
            "matchedSkill": matched,
            "skipModel": skip_model,
            "next": (
                matched["next"] if matched else
                "Use a goal-ranked/current elementId immediately, then call phone_act. "
                "After any mutation, use phone_locate again; IDs are not reusable."
            ),
        }

    def phone_ui_search(self, args: dict[str, Any]) -> Any:
        query = str(args.get("query") or "").strip()
        if not query:
            raise ValueError("query is required")
        return self.gateway.ui_search(query, self._device(args), **_required_identity_kwargs(args))

    def phone_inspect_element(self, args: dict[str, Any]) -> Any:
        element_id = str(args.get("element_id") or "").strip()
        if not element_id:
            raise ValueError("element_id is required")
        return self.gateway.ui_element(element_id, self._device(args), **_required_identity_kwargs(args))

    def phone_screenshot(self, args: dict[str, Any]) -> Any:
        return self.gateway.observe(self._device(args), include_screenshot=True, mode="compact", **_required_identity_kwargs(args))

    def phone_current_page(self, args: dict[str, Any]) -> Any:
        return self.gateway.current_page(self._device(args))

    def phone_page_history(self, args: dict[str, Any]) -> Any:
        return self.gateway.page_history(self._device(args))

    def phone_workspace(self, args: dict[str, Any]) -> Any:
        """Layer 2 management on default-foreground / display 0. Not a VD session."""
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
        validate_typed_params(params)
        _validate_workspace_operation_params(str(operation), params)
        identity = _required_identity_kwargs(args)
        device_id = self._device(args)
        goal = str(params.get("goal") or ("Manage Cyclone workspace: " + str(operation)))
        forwarded = dict(params)
        self.gateway.observe(device_id, include_screenshot=False, mode="compact", **identity)
        result = self.gateway.action("workspace." + str(operation), forwarded, goal, device_id, **identity)
        self._update_layer2_lease(device_id or "", str(operation), result)
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
        validate_typed_params(params)
        validate_human_gesture_params(tool, params)
        goal = str(args.get("goal") or "").strip()
        if not goal:
            raise ValueError("goal is required")
        if tool == "phone.type" and args.get("user_authorized") is not True:
            raise ValueError("phone.type requires user_authorized=true; Android policy remains authoritative")
        params = dict(params)
        if "workspaceId" in params or "workspaceGeneration" in params:
            _require_workspace_identity(params.get("workspaceId"), params.get("workspaceGeneration"))
        if tool == "phone.open_app":
            validate_open_app_params({
                key: value for key, value in params.items()
                if key not in {"workspaceId", "workspaceGeneration"}
            })
        params = _forward_type_authorization(tool, args, params)
        if args.get("request_ai_control") is True:
            params = dict(params)
            params["request_ai_control"] = True
        scope = require_tool_execution_scope(args)
        self._require_layer2_mutate_lock(self._device(args) or "", tool, params, scope)
        result = self.gateway.action(tool, params, goal, self._device(args), **_required_identity_kwargs(args))
        if tool == "phone.type":
            typed = params.get("value") if isinstance(params.get("value"), str) else params.get("text")
            result = strip_typed_plaintext(result, typed if isinstance(typed, str) else None)
        return result

    def phone_skill_save(self, args: dict[str, Any]) -> Any:
        built = skill_save_payload(args)
        if built.get("written") is False:
            return built
        payload = built.get("_compile")
        if not isinstance(payload, dict):
            raise ValueError("skill compile payload is invalid")
        identity = _identity_kwargs(args)
        if identity:
            payload = dict(payload)
            payload["sessionId"] = identity["session_id"]
            payload["displayId"] = identity["display_id"]
        result = self.gateway.skill_save(payload, self._device(args))
        return skill_save_success(result, payload)

    def phone_skill_run(self, args: dict[str, Any]) -> Any:
        skill_id = str(args.get("skill_id") or args.get("skillId") or "").strip()
        if not skill_id:
            raise ValueError("skill_id is required")
        dry_run = bool(args.get("dryRun") or args.get("dry_run"))
        device_id = self._device(args)
        if not dry_run:
            try:
                meta = self.gateway.skill_get(skill_id, device_id)
                skill = meta.get("skill") if isinstance(meta, dict) and isinstance(meta.get("skill"), dict) else meta
                if isinstance(skill, dict) and str(skill.get("status") or "").lower() == "draft":
                    return draft_run_denied(skill_id, "draft")
            except (GatewayError, AttributeError, TypeError, ValueError, KeyError):
                pass
        params = args.get("params") if isinstance(args.get("params"), dict) else {}
        identity = _required_identity_kwargs(args)
        params = dict(params)
        params["sessionId"] = identity["session_id"]
        params["displayId"] = identity["display_id"]
        result = self.gateway.skill_run(
            skill_id, dry_run=dry_run,
            params=params,
            device_id=device_id,
        )
        return skill_run_normalize(result, skill_id=skill_id, dry_run=dry_run)

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
        validate_typed_params(params)
        validate_human_gesture_params(tool, params)
        goal = str(args.get("goal") or "").strip()
        if not goal:
            raise ValueError("goal is required")
        if tool == "phone.open_app":
            validate_open_app_params({
                key: value for key, value in params.items()
                if key not in {"workspaceId", "workspaceGeneration"}
            })
        identity = _required_identity_kwargs(args)
        results: list[dict[str, Any]] = []
        for device_id in device_ids:
            try:
                before = self.gateway.observe(device_id, include_screenshot=False, mode="compact", **identity)
                outcome = self.gateway.action(tool, params, goal, device_id, **identity)
                results.append({"device_id": device_id, "ok": _error_code(outcome) is None, "before": before, "outcome": outcome})
            except GatewayError as exc:
                results.append({"device_id": device_id, "ok": False, "error": exc.body or {"code": "GATEWAY_ERROR"}})
        return {
            "operation": "typed_group_action",
            "tool": tool,
            "selected_device_ids": device_ids,
            "ok": all(item["ok"] for item in results),
            "results": results,
        }

    def phone_debug_bundle(self, args: dict[str, Any]) -> Any:
        return self.gateway.debug_bundle(
            self._device(args), expected=str(args.get("expected") or ""), goal=str(args.get("goal") or "")
        )

    def phone_teach_start(self, args: dict[str, Any]) -> Any:
        return self.gateway.teach_start(self._device(args), goal=str(args.get("goal") or ""))

    def phone_teach_status(self, args: dict[str, Any]) -> Any:
        return self.gateway.teach_status(self._device(args))

    def phone_teach_stop(self, args: dict[str, Any]) -> Any:
        return self.gateway.teach_stop(self._device(args), compile_for_review=bool(args.get("compile_for_review", True)))

    def phone_virtual_list(self, _: dict[str, Any]) -> Any:
        _only_keys(_, set())
        return self.gateway.virtual_instances()

    def phone_virtual_create(self, args: dict[str, Any]) -> Any:
        _only_keys(args, {"provider", "image"})
        provider = _required_id(args, "provider", PROVIDER_ID)
        image = _required_id(args, "image", IMAGE_ID)
        return self.gateway.virtual_create(provider, image)

    def phone_virtual_start(self, args: dict[str, Any]) -> Any:
        _only_keys(args, {"instance_id"})
        instance_id = _required_id(args, "instance_id", INSTANCE_ID)
        return self.gateway.virtual_lifecycle(instance_id, "start")

    def phone_virtual_stop(self, args: dict[str, Any]) -> Any:
        _only_keys(args, {"instance_id"})
        instance_id = _required_id(args, "instance_id", INSTANCE_ID)
        return self.gateway.virtual_lifecycle(instance_id, "stop")

    def phone_routine_run(self, args: dict[str, Any]) -> Any:
        _only_keys(args, {"device_id", "routine_id"})
        device_id = _required_id(args, "device_id", TARGET_ID)
        routine_id = _required_id(args, "routine_id", ROUTINE_ID)
        return self.gateway.routine_run(device_id, routine_id)

    def phone_routine_status(self, args: dict[str, Any]) -> Any:
        _only_keys(args, {"device_id", "run_id"})
        device_id = _required_id(args, "device_id", TARGET_ID)
        run_id = _required_id(args, "run_id", RUN_ID)
        return self.gateway.routine_status(device_id, run_id)

    def phone_routine_cancel(self, args: dict[str, Any]) -> Any:
        _only_keys(args, {"device_id", "run_id"})
        device_id = _required_id(args, "device_id", TARGET_ID)
        run_id = _required_id(args, "run_id", RUN_ID)
        return self.gateway.routine_cancel(device_id, run_id)


    def phone_app_manual(self, args: dict[str, Any]) -> Any:
        """An app's manual (plan 36 §8): what you can do in it and the path to each, in the app's own words. Read only."""
        _only_keys(args, {"device_id", "app", "query"})
        device_id = _required_id(args, "device_id", TARGET_ID)
        app = args.get("app")
        if not isinstance(app, str) or not APP_PACKAGE.match(app):
            raise ValueError("app is the app's package name, like com.instagram.android")
        query = args.get("query")
        if query is not None and (not isinstance(query, str) or len(query) > 200):
            raise ValueError("query is at most 200 characters")
        manual = self.gateway.app_manual(device_id, app, query.strip() if query else None)
        if not isinstance(manual, dict) or "abilities" not in manual:
            return manual
        # Hits first when there is a query; the full list stays available in the Markdown.
        hits = {h.get("id"): h.get("score") for h in manual.get("hits") or [] if isinstance(h, dict)}
        abilities = manual.get("abilities") or []
        best = [dict(a, fit=hits[a["id"]]) for a in abilities if isinstance(a, dict) and a.get("id") in hits]
        best.sort(key=lambda a: -(a.get("fit") or 0))
        return {
            "app": manual.get("appLabel"), "version": manual.get("currentVersion"), "query": manual.get("query"),
            "clearMatch": manual.get("clear"), "matches": best, "abilities": abilities if not hits else abilities[:40],
            "quiz": manual.get("quiz"), "scores": manual.get("scores"), "markdown": manual.get("markdown"),
        }

    def phone_lab_missions(self, args: dict[str, Any]) -> Any:
        _only_keys(args, set())
        return self.gateway.lab_missions()

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
        if not isinstance(repetitions, int) or not 1 <= repetitions <= 20:
            raise ValueError("repetitions is 1..20")
        return self.gateway.lab_start(device_id, name.strip(), missions, variants, repetitions)

    def phone_lab_report(self, args: dict[str, Any]) -> Any:
        """Without an id: the experiment list. With one: rates, A/B, insights and the failed runs, compacted."""
        _only_keys(args, {"experiment_id"})
        if not args.get("experiment_id"):
            return self.gateway.lab_experiments()
        detail = self.gateway.lab_experiment(_required_id(args, "experiment_id", LAB_EXPERIMENT_ID))
        if not isinstance(detail, dict) or "trials" not in detail:
            return detail
        failures = []
        for trial in detail["trials"]:
            if trial.get("verdict") == "pass":
                continue
            phone = trial.get("phone") or {}
            metrics = phone.get("metrics") or {}
            failures.append({
                "mission": trial.get("missionId"), "variant": trial.get("variant"), "verdict": trial.get("verdict"),
                "category": trial.get("category"), "cause": trial.get("cause"), "signals": trial.get("signals"),
                "checks": trial.get("checks"), "summary": phone.get("summary"), "turns": phone.get("turns"),
                "errorTail": metrics.get("errorTail"), "toolCalls": metrics.get("toolCalls"), "traceId": phone.get("traceId"),
            })
        return {**{k: v for k, v in detail.items() if k != "trials"}, "failures": failures[:60]}

    def phone_lab_stop(self, args: dict[str, Any]) -> Any:
        _only_keys(args, {"experiment_id"})
        return self.gateway.lab_stop(_required_id(args, "experiment_id", LAB_EXPERIMENT_ID))


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


def _error_code(value: Any) -> str | None:
    if not isinstance(value, dict):
        return None
    error = value.get("error")
    if isinstance(error, dict) and isinstance(error.get("code"), str):
        return error["code"]
    return None
