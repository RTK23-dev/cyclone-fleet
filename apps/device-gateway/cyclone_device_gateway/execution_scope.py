from __future__ import annotations

from typing import Any, Mapping

DEFAULT_FOREGROUND_SESSION_ID = "default-foreground"
IDENTITY_KEYS = frozenset({"sessionId", "session_id", "displayId", "display_id", "executionContext"})
FOREGROUND_PLANE_KIND = "foreground"
SESSION_KERNEL_VD_PLANE_KIND = "session_kernel_vd"
FOREGROUND_PLANE_LABEL = "Foreground"
SESSION_KERNEL_VD_PLANE_LABEL = "Session Kernel VD"


def classify_session_plane(session_id: str | None, display_id: int | None = None) -> dict[str, Any]:
    """Label Foreground vs Session Kernel VD. Does not classify Layer 2 workspaces."""
    session = str(session_id or "").strip()
    if not session:
        raise ValueError("sessionId is required")
    if session == DEFAULT_FOREGROUND_SESSION_ID:
        if display_id is None:
            display_id = 0
        if display_id != 0:
            raise ValueError("default-foreground session must use display 0")
        return {
            "kind": FOREGROUND_PLANE_KIND,
            "sessionId": session,
            "displayId": 0,
            "workspaceId": None,
            "workspaceGeneration": None,
            "label": FOREGROUND_PLANE_LABEL,
        }
    if display_id is None:
        raise ValueError("An explicit background session requires its displayId")
    if display_id <= 0:
        raise ValueError("Named Session Kernel VD displayId must be an int > 0")
    return {
        "kind": SESSION_KERNEL_VD_PLANE_KIND,
        "sessionId": session,
        "displayId": display_id,
        "workspaceId": None,
        "workspaceGeneration": None,
        "label": SESSION_KERNEL_VD_PLANE_LABEL,
    }


def parse_execution_identity(args: Mapping[str, Any] | None) -> dict[str, Any] | None:
    """Canonical `{sessionId, displayId}` or None to omit (legacy default-foreground).

    Execution identity is not usb_session_id, media sessionId, teach sessionId, or MCP report
    sessionId. Named Session Kernel VD sessions require displayId > 0; never silently send display 0.
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
        session = _agree("sessionId", session, _read_session(nested))
        display = _agree("displayId", display, _read_display(nested))
    params = args.get("params") if isinstance(args.get("params"), Mapping) else None
    if params is not None:
        session = _agree("sessionId", session, _read_session(params))
        display = _agree("displayId", display, _read_display(params))
        nested_params = params.get("executionContext") if "executionContext" in params else None
        if nested_params is not None:
            if not isinstance(nested_params, Mapping):
                raise ValueError("executionContext must be an object")
            session = _agree("sessionId", session, _read_session(nested_params))
            display = _agree("displayId", display, _read_display(nested_params))
    if session is None and display is None:
        return None
    if session is None:
        if display == 0:
            return None
        raise ValueError("displayId requires sessionId")
    plane = classify_session_plane(session, display)
    return {"sessionId": plane["sessionId"], "displayId": plane["displayId"]}


def attach_execution_identity(payload: Mapping[str, Any] | None, identity: Mapping[str, Any] | None) -> dict[str, Any]:
    out = dict(payload or {})
    if not identity:
        return out
    out["sessionId"] = identity["sessionId"]
    out["displayId"] = identity["displayId"]
    return out


def history_key(device_id: str, identity: Mapping[str, Any] | None) -> str:
    session_id = (identity or {}).get("sessionId") or DEFAULT_FOREGROUND_SESSION_ID
    return f"{device_id}::{session_id}"


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
