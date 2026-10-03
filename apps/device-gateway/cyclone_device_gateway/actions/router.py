from __future__ import annotations

from copy import deepcopy
import time
import uuid
import re
from typing import Any, Callable

from ..auth import AuditLog, redact_params
from ..cyclone_bridge.client import BridgeBusyError, BridgeOperationError, BridgeProtocolError
from ..retrieval.service import RetrievalService
from ..state.store import StateStore
from ..execution_scope import attach_execution_identity, parse_execution_identity
from .envelope import android_execution_error_class, extract_android_execution


ALLOWED_TOOLS = {
    "workspace.list",
    "workspace.register",
    "workspace.switch",
    "phone.workspace_switch",
    "workspace.pause",
    "workspace.release",
    "workspace.arm",
    "workspace.next",

    "phone.observe",
    "phone.find",
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
NON_MUTATING_TOOLS = {"workspace.list", "phone.observe", "phone.find", "phone.wait_for"}
FORBIDDEN_KEYS = {"command", "shell", "powershell", "su", "script"}
SELECTOR_KEY_ALIASES = {
    "resource_id": "resourceId",
    "content_desc": "contentDescription",
    "content_description": "contentDescription",
    "content_description_contains": "contentDescriptionContains",
    "text_contains": "textContains",
    "class_name": "class",
    "fuzzy_text": "fuzzyText",
    "min_fuzzy_score": "minFuzzyScore",
    "ancestor_text": "ancestorText",
    "descendant_text": "descendantText",
    "relative_to_text": "relativeToText",
    "relative_direction": "relativeDirection",
}
TYPE_DIRECT_SELECTOR_KEYS = {
    "resourceId",
    "contentDescription",
    "contentDescriptionContains",
    "textContains",
    "class",
    "role",
    "ancestorText",
    "descendantText",
    "x",
    "y",
    "relativeToText",
    "relativeDirection",
    "fuzzyText",
    "minFuzzyScore",
    "clickable",
    "editable",
    "scrollable",
}
EMBEDDED_SELECTOR_KEYS = TYPE_DIRECT_SELECTOR_KEYS | {"text"}
ERROR_CODE_PATTERN = re.compile(r"[A-Z][A-Z0-9_]{0,63}")


class ActionValidationError(ValueError):
    def __init__(self, message: str, *, code: str = "PROTOCOL_MISMATCH"):
        super().__init__(message)
        self.code = code


def _safe_error_code(value: Any, fallback: str) -> str:
    normalized = str(value or "").upper()
    return normalized if ERROR_CODE_PATTERN.fullmatch(normalized) else fallback


def _forbidden_paths(value: Any, prefix: str = "") -> list[str]:
    found: list[str] = []
    if isinstance(value, dict):
        for key, nested in value.items():
            key_text = str(key)
            path = f"{prefix}.{key_text}" if prefix else key_text
            if key_text.lower() in FORBIDDEN_KEYS:
                found.append(path)
            found.extend(_forbidden_paths(nested, path))
    elif isinstance(value, list):
        for index, nested in enumerate(value):
            found.extend(_forbidden_paths(nested, f"{prefix}[{index}]"))
    return found


def _normalize_aliases(value: dict[str, Any]) -> dict[str, Any]:
    normalized: dict[str, Any] = {}
    for key, nested in value.items():
        target = SELECTOR_KEY_ALIASES.get(str(key), str(key))
        if target in normalized and normalized[target] != nested:
            raise ActionValidationError(f"Conflicting selector aliases for {target}")
        normalized[target] = nested
    return normalized


def validate_action(tool: str, params: dict[str, Any]) -> None:
    if tool not in ALLOWED_TOOLS:
        raise ActionValidationError(f"Unsupported phone tool: {tool}")
    if not isinstance(params, dict):
        raise ActionValidationError("params must be an object")
    bad = _forbidden_paths(params)
    if bad:
        raise ActionValidationError(f"Forbidden action parameter(s): {', '.join(sorted(bad))}")
    if tool == "phone.type":
        has_text = "text" in params
        has_value = "value" in params
        if not has_text and not has_value:
            raise ActionValidationError("phone.type requires string text/value")
        if has_text and not isinstance(params.get("text"), str):
            raise ActionValidationError("phone.type text must be a string")
        if has_value and not isinstance(params.get("value"), str):
            raise ActionValidationError("phone.type value must be a string")
        if has_text and has_value and params.get("text") != params.get("value"):
            raise ActionValidationError("phone.type text and value disagree; provide one value")


def _android_execution(result: Any) -> dict[str, Any] | None:
    return extract_android_execution(result)


def _witness(observation: dict[str, Any]) -> dict[str, Any]:
    semantic = observation.get("semantic")
    semantic = semantic if isinstance(semantic, dict) else {}
    return {
        "observation_id": str(
            semantic.get("observationId")
            or semantic.get("observation_id")
            or observation.get("id")
        ),
        "gateway_record_id": str(observation.get("id")),
        "page_key": semantic.get("pageKey") or observation.get("page_key"),
        "package": semantic.get("package") or observation.get("package"),
        "accessibility_fingerprint": (
            semantic.get("accessibilityFingerprint") or semantic.get("fingerprint")
        ),
    }


def _selector_from_element(element: dict[str, Any]) -> dict[str, Any]:
    selector: dict[str, Any] = {}

    embedded = element.get("selector")
    if isinstance(embedded, dict):
        for key, value in _normalize_aliases(embedded).items():
            if key in EMBEDDED_SELECTOR_KEYS and value not in (None, ""):
                selector[key] = value

    resource_id = element.get("resourceId") or element.get("resource_id")
    actual_text = element.get("text")
    content_description = element.get("contentDescription") or element.get("content_desc")
    class_name = element.get("class")
    role = element.get("role")

    if resource_id and "resourceId" not in selector:
        selector["resourceId"] = resource_id
    if actual_text and actual_text != "<redacted>" and not any(
        key in selector for key in ("text", "textContains")
    ):
        selector["text"] = actual_text
    if content_description and content_description != "<redacted>" and not any(
        key in selector for key in ("contentDescription", "contentDescriptionContains")
    ):
        selector["contentDescription"] = content_description

    if not selector:
        label = element.get("label")
        if label and label != "<redacted>":
            selector["text"] = label

    if not selector:
        bounds = element.get("bounds")
        if isinstance(bounds, dict):
            left = bounds.get("left")
            top = bounds.get("top")
            right = bounds.get("right")
            bottom = bounds.get("bottom")
            if all(isinstance(value, (int, float)) for value in (left, top, right, bottom)):
                selector["x"] = int((left + right) / 2)
                selector["y"] = int((top + bottom) / 2)

    if class_name and "resourceId" not in selector and "class" not in selector:
        selector["class"] = class_name
    if role and "resourceId" not in selector and "role" not in selector:
        selector["role"] = role
    if element.get("clickable") is True:
        selector["clickable"] = True
    if element.get("editable") is True:
        selector["editable"] = True
    if element.get("scrollable") is True:
        selector["scrollable"] = True

    if not selector:
        raise ActionValidationError("Element has no stable selector evidence")
    return selector


def _normalize_action_params(tool: str, params: dict[str, Any]) -> dict[str, Any]:
    normalized = deepcopy(params)

    selector = normalized.get("selector")
    if selector is not None:
        if not isinstance(selector, dict):
            raise ActionValidationError("selector must be an object")
        normalized["selector"] = _normalize_aliases(selector)

    for alias, canonical in SELECTOR_KEY_ALIASES.items():
        if alias in normalized:
            value = normalized.pop(alias)
            if canonical in normalized and normalized[canonical] != value:
                raise ActionValidationError(f"Conflicting selector aliases for {canonical}")
            normalized[canonical] = value

    if tool != "phone.type":
        return normalized

    if "text" in normalized:
        typed = normalized.pop("text")
        if "value" in normalized and normalized["value"] != typed:
            raise ActionValidationError("phone.type text and value disagree; provide one value")
        normalized["value"] = typed

    if not isinstance(normalized.get("value"), str):
        raise ActionValidationError("phone.type requires string text/value")

    if "selector" not in normalized:
        direct_selector: dict[str, Any] = {}
        for key in list(normalized):
            if key in TYPE_DIRECT_SELECTOR_KEYS:
                direct_selector[key] = normalized.pop(key)
        if direct_selector:
            normalized["selector"] = direct_selector

    if not isinstance(normalized.get("selector"), dict) or not normalized["selector"]:
        raise ActionValidationError(
            "phone.type requires an explicit selector or elementId; focused-field typing is not exposed"
        )
    return normalized


class ActionRouter:
    def __init__(
        self,
        bridge,
        store: StateStore,
        audit: AuditLog,
        observe: Callable[[], dict],
        stabilize: Callable[[], dict | None] | None = None,
        resolve_element: Callable[[str], dict | None] | None = None,
    ):
        self.bridge = bridge
        self.store = store
        self.audit = audit
        self.observe = observe
        self.stabilize = stabilize
        self.resolve_element = resolve_element or RetrievalService(store).get_element

    def _resolve_element_ids(self, params: dict[str, Any]) -> dict[str, Any]:
        resolved = deepcopy(params)
        selector = resolved.get("selector")
        if selector is not None and not isinstance(selector, dict):
            raise ActionValidationError("selector must be an object")

        element_id = resolved.pop("elementId", None) or resolved.pop("element_id", None)
        if isinstance(selector, dict):
            element_id = (
                selector.pop("elementId", None)
                or selector.pop("element_id", None)
                or element_id
            )

        if not element_id:
            return resolved

        element = self.resolve_element(str(element_id))
        if element is None:
            raise ActionValidationError(
                "Unknown or stale elementId; observe/search the current page again",
                code="STALE_OBSERVATION",
            )

        stable_selector = _selector_from_element(element)
        if isinstance(selector, dict):
            stable_selector.update(_normalize_aliases(selector))
        resolved["selector"] = stable_selector

        risk = element.get("risk")
        if isinstance(risk, str) and risk:
            resolved["_gatewayRisk"] = risk.upper()
        return resolved

    def execute(
        self,
        *,
        tool: str,
        params: dict,
        goal: str = "",
        source: str = "PC_CODEX",
        request_id: str | None = None,
    ) -> dict:
        validate_action(tool, params)
        if source != "PC_CODEX":
            raise ActionValidationError("Action source must be PC_CODEX")

        resolved_params = self._resolve_element_ids(params)
        resolved_params = _normalize_action_params(tool, resolved_params)
        validate_action(tool, resolved_params)

        request_id = request_id or str(uuid.uuid4())
        try:
            identity = parse_execution_identity(params) or parse_execution_identity(resolved_params)
        except ValueError as exc:
            raise ActionValidationError(str(exc), code="INVALID_REQUEST") from exc
        if identity:
            resolved_params = attach_execution_identity(resolved_params, identity)
        if identity and identity["sessionId"] != "default-foreground":
            # This router's legacy observer is foreground-only. Never let its evidence verify a VD action.
            raise ActionValidationError("Use the exact-session desktop agent route for a named display", code="INVALID_REQUEST")
        before = self.observe()
        started = time.perf_counter()
        error_class: str | None = None
        transport_ok = True

        try:
            execute_args = {
                "tool": tool,
                "params": resolved_params,
                "goal": goal,
                "source": source,
                "requestId": request_id,
                "correlationId": request_id,
                "currentObservationId": _witness(before)["observation_id"],
            }
            if identity:
                execute_args["sessionId"] = identity["sessionId"]
                execute_args["displayId"] = identity["displayId"]
            result = self.bridge.request(
                "action.execute",
                execute_args,
            )
            execution = _android_execution(result)
            if execution is not None:
                success = bool(execution.get("ok"))
                if not success:
                    error_class = android_execution_error_class(execution)
            else:
                success = False
                error_class = "PROTOCOL_MISMATCH"
        except BridgeProtocolError:
            result = {"error": {"code": "PROTOCOL_MISMATCH"}}
            success = False
            error_class = "PROTOCOL_MISMATCH"
        except BridgeOperationError as exc:
            error_code = _safe_error_code(exc.code, "ANDROID_ACTION_FAILED")
            result = {"error": {"code": error_code}}
            success = False
            error_class = error_code
        except BridgeBusyError:
            # The phone is there but its app didn't answer in time: busy, not a lost phone.
            result = {"error": {"code": "PHONE_APP_BUSY"}}
            success = False
            error_class = "PHONE_APP_BUSY"
        except Exception:
            result = {"error": {"code": "DEVICE_DISCONNECTED"}}
            success = False
            transport_ok = False
            error_class = "DEVICE_DISCONNECTED"

        if self.stabilize is not None:
            try:
                self.stabilize()
            except Exception:
                if success:
                    error_class = error_class or "StabilizationWarning"

        try:
            after = self.observe()
        except Exception:
            after = before
            transport_ok = False
            success = False
            error_class = "DEVICE_DISCONNECTED"
        duration_ms = int((time.perf_counter() - started) * 1000)

        execution = _android_execution(result)
        before_fingerprint = execution.get("beforeFingerprint") if execution else None
        after_fingerprint = execution.get("afterFingerprint") if execution else None

        explicit_verification = None
        if isinstance(result, dict):
            explicit_verification = result.get("verification")
        if not isinstance(explicit_verification, dict) and execution:
            explicit_verification = execution.get("verification")

        verification_error_class: str | None = None
        if not success:
            verification = "android_action_failed"
            verification_ok = False
        elif isinstance(explicit_verification, dict) and explicit_verification.get("ok") is False:
            verification = "android_verification_failed"
            verification_ok = False
            raw_error = explicit_verification.get("error")
            if isinstance(raw_error, dict):
                verification_error_class = _safe_error_code(
                    raw_error.get("code"),
                    "VERIFICATION_FAILED",
                )
            else:
                verification_error_class = "VERIFICATION_FAILED"
        elif isinstance(explicit_verification, dict):
            # OBSERVED/semanticSuccessClaimed=false is not navigation success, even if ok=true
            # from an older Android bridge. Explicit negative evidence always beats hash churn.
            verification_ok = (
                explicit_verification.get("ok") is True
                and explicit_verification.get("semanticSuccessClaimed") is not False
                and explicit_verification.get("status") not in {"OBSERVED", "FAILED", "DEGRADED", "NOT_RUN"}
            )
            verification = "android_verified" if verification_ok else "android_verification_failed"
            if not verification_ok:
                verification_error_class = "NO_SEMANTIC_PROGRESS"
        else:
            # Legacy execution acceptance/fingerprint change is not an Android postcondition.
            verification = "verification_missing"
            verification_ok = False
            verification_error_class = "VERIFICATION_REQUIRED"

        if tool in NON_MUTATING_TOOLS and success:
            verification = "not_required"
            verification_ok = True

        overall_success = transport_ok and success and verification_ok

        stored_result = result if tool != "phone.type" else {
            "success": overall_success,
            "typed_value_redacted": True,
            "error_class": error_class,
        }
        action_id = self.store.add_action(
            request_id,
            tool,
            redact_params(tool, resolved_params),
            stored_result,
            duration_ms,
        )
        transition_id = self.store.add_transition(
            before_id=before["id"],
            action_id=action_id,
            after_id=after["id"],
            before_page=before.get("page_key"),
            after_page=after.get("page_key"),
            success=overall_success,
            latency_ms=duration_ms,
            verification=verification,
            backend="CYCLONE_ANDROID_BRIDGE",
            error_class=error_class,
        )
        self.audit.write(
            {
                "device": after.get("device_serial"),
                "request_id": request_id,
                "operation": "action.execute",
                "tool": tool,
                "params": redact_params(tool, resolved_params),
                "result": {
                    "success": overall_success,
                    "execution_ok": success,
                    "verification_ok": verification_ok,
                    "error_class": error_class,
                },
                "duration_ms": duration_ms,
                "source_client": source,
            }
        )
        return {
            "request_id": request_id,
            "success": overall_success,
            "transport_ok": transport_ok,
            "execution_ok": success,
            "verification_ok": verification_ok,
            "result": result,
            "transition_id": transition_id,
            "before_page": before.get("page_key"),
            "after_page": after.get("page_key"),
            "latency_ms": duration_ms,
            "verification": verification,
            "verification_error_class": verification_error_class,
            "error_class": error_class,
            "before_witness": _witness(before),
            "after_witness": _witness(after),
        }
