from __future__ import annotations

from typing import Any, Mapping

DEFAULT_FOREGROUND_SESSION_ID = "default-foreground"
IDENTITY_KEYS = frozenset({"sessionId", "session_id", "displayId", "display_id", "executionContext"})
SESSION_REQUIRED = "SESSION_REQUIRED"
SESSION_DISPLAY_MISMATCH = "SESSION_DISPLAY_MISMATCH"
FOREGROUND_PLANE_KIND = "foreground"
SESSION_KERNEL_VD_PLANE_KIND = "session_kernel_vd"
FOREGROUND_PLANE_LABEL = "Foreground"
SESSION_KERNEL_VD_PLANE_LABEL = "Session Kernel VD"
SESSION_REQUIRED_MESSAGE = (
    "session_id is required. Pass default-foreground for the live human display, "
    "or a named Session Kernel VD with display_id > 0."
)


class SessionScopeError(ValueError):
    """MCP-surface execution identity failure. Never invent default-foreground."""

    def __init__(self, message: str, error_class: str = SESSION_REQUIRED):
        super().__init__(message)
        self.error_class = error_class


def session_scope_error_result(exc: SessionScopeError) -> dict[str, Any]:
    return {
        "ok": False,
        "errorClass": exc.error_class,
        "error": {
            "code": exc.error_class,
            "layer": "protocol",
            "message": str(exc),
        },
    }


def classify_session_plane(session_id: str | None, display_id: int | None = None) -> dict[str, Any]:
    """Label Foreground vs Session Kernel VD for glass/MCP. Never invents default-foreground."""
    try:
        args: dict[str, Any] = {}
        if session_id is not None:
            args["sessionId"] = session_id
        if display_id is not None:
            args["displayId"] = display_id
        identity = parse_execution_scope(args)
        if not identity or not str(identity.get("sessionId") or "").strip():
            raise SessionScopeError(SESSION_REQUIRED_MESSAGE, SESSION_REQUIRED)
        return _plane_for_identity(identity["sessionId"], identity["displayId"])
    except SessionScopeError:
        raise
    except ValueError as exc:
        raise _scope_error(exc) from exc


def parse_execution_scope(args: Mapping[str, Any] | None) -> dict[str, Any] | None:
    """Return canonical ``{sessionId, displayId}`` or None to omit (legacy default-foreground).

    Execution identity is not usb_session_id, media sessionId, teach sessionId, or MCP report
    sessionId. Omitted identity must not invent a Session Kernel VD. A named Session Kernel VD
    requires displayId > 0 and must never silently fall back to display 0.
    """
    if args is None:
        return None
    if not isinstance(args, Mapping):
        raise ValueError("execution scope args must be an object")
    session = _read_session(args)
    display = _read_display(args)
    nested = args.get("executionContext") if "executionContext" in args else None
    if nested is not None:
        if not isinstance(nested, Mapping):
            raise ValueError("executionContext must be an object")
        nested_session = _read_session(nested)
        nested_display = _read_display(nested)
        session = _agree("sessionId", session, nested_session)
        display = _agree("displayId", display, nested_display)
    if session is None and display is None:
        return None
    if session is None:
        if display == 0:
            return None
        raise ValueError("displayId requires sessionId")
    plane = _plane_for_identity(session, display)
    return {"sessionId": plane["sessionId"], "displayId": plane["displayId"]}


def parse_tool_execution_scope(args: Mapping[str, Any] | None) -> dict[str, Any] | None:
    """Parse top-level MCP args and nested action params; reject disagreements."""
    if not isinstance(args, Mapping):
        return parse_execution_scope(args)
    top = parse_execution_scope(args)
    params = args.get("params")
    nested = parse_execution_scope(params) if isinstance(params, dict) else None
    return _agree_scope(top, nested)


def require_tool_execution_scope(args: Mapping[str, Any] | None) -> dict[str, Any]:
    """Fail closed unless an explicit sessionId is present. Never invent default-foreground."""
    try:
        scope = parse_tool_execution_scope(args)
    except ValueError as exc:
        raise _scope_error(exc) from exc
    if not scope or not str(scope.get("sessionId") or "").strip():
        raise SessionScopeError(SESSION_REQUIRED_MESSAGE, SESSION_REQUIRED)
    return scope


def _scope_error(exc: ValueError) -> SessionScopeError:
    message = str(exc)
    lowered = message.lower()
    if "display" in lowered:
        return SessionScopeError(message, SESSION_DISPLAY_MISMATCH)
    if "session" in lowered or "conflicting" in lowered:
        return SessionScopeError(message, SESSION_REQUIRED)
    return SessionScopeError(message, SESSION_REQUIRED)


def attach_execution_scope(params: Mapping[str, Any] | None, scope: Mapping[str, Any] | None) -> dict[str, Any]:
    """Copy canonical sessionId/displayId onto params. Top-level identity is enough for callers."""
    out = dict(params or {})
    existing = parse_execution_scope(out)
    agreed = _agree_scope(dict(scope) if scope else None, existing)
    if agreed is None:
        return out
    out["sessionId"] = agreed["sessionId"]
    out["displayId"] = agreed["displayId"]
    return out


def strip_execution_scope(params: Mapping[str, Any] | None) -> dict[str, Any]:
    return {key: value for key, value in dict(params or {}).items() if key not in IDENTITY_KEYS}


def scope_cache_key(device_id: str | None, session_id: str | None) -> str:
    return f"{device_id or '__gateway_selected__'}::{session_id or DEFAULT_FOREGROUND_SESSION_ID}"


def _plane_for_identity(session: str, display: int | None) -> dict[str, Any]:
    if session == DEFAULT_FOREGROUND_SESSION_ID:
        if display is None:
            display = 0
        if display != 0:
            raise ValueError("default-foreground session must use display 0")
        return {
            "kind": FOREGROUND_PLANE_KIND,
            "sessionId": session,
            "displayId": 0,
            "label": FOREGROUND_PLANE_LABEL,
        }
    if display is None:
        raise ValueError("An explicit background session requires its displayId")
    if display <= 0:
        raise ValueError("Named Session Kernel VD displayId must be an int > 0")
    return {
        "kind": SESSION_KERNEL_VD_PLANE_KIND,
        "sessionId": session,
        "displayId": display,
        "label": SESSION_KERNEL_VD_PLANE_LABEL,
    }


def _agree_scope(left: dict[str, Any] | None, right: dict[str, Any] | None) -> dict[str, Any] | None:
    if left and right:
        if left["sessionId"] != right["sessionId"] or left["displayId"] != right["displayId"]:
            raise ValueError("Conflicting execution session identities")
        return left
    return left or right


def _read_session(args: Mapping[str, Any]) -> str | None:
    return _agree("sessionId", _session_value(args, "session_id"), _session_value(args, "sessionId"))


def _read_display(args: Mapping[str, Any]) -> int | None:
    return _agree("displayId", _display_value(args, "display_id"), _display_value(args, "displayId"))


def _session_value(args: Mapping[str, Any], key: str) -> str | None:
    if key not in args or args[key] is None:
        return None
    value = args[key]
    if not isinstance(value, str) or not value or value != value.strip():
        raise ValueError("Invalid sessionId")
    return value


def _display_value(args: Mapping[str, Any], key: str) -> int | None:
    if key not in args or args[key] is None:
        return None
    value = args[key]
    if isinstance(value, bool) or isinstance(value, str) or not isinstance(value, (int, float)):
        raise ValueError("Invalid displayId")
    if float(value) != int(value) or int(value) < 0:
        raise ValueError("Invalid displayId")
    return int(value)


def _agree(name: str, left: Any, right: Any) -> Any:
    if left is not None and right is not None and left != right:
        raise ValueError(f"Conflicting {name} aliases")
    return left if left is not None else right
