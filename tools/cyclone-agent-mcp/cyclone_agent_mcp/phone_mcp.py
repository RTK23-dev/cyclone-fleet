"""Import the canonical Codex phone-MCP helpers when the sibling package is available.

CycloneAgentMCP.exe already ships cyclone_phone_mcp. Source tests add the sibling path.
"""
from __future__ import annotations

import sys
from pathlib import Path
from typing import Any


def _ensure_phone_mcp() -> None:
    try:
        import cyclone_phone_mcp  # noqa: F401
        return
    except ImportError:
        sibling = Path(__file__).resolve().parents[2] / "codex-phone-mcp"
        if sibling.is_dir() and str(sibling) not in sys.path:
            sys.path.insert(0, str(sibling))


def parse_execution_scope(args: dict[str, Any] | None):
    _ensure_phone_mcp()
    from cyclone_phone_mcp.session import parse_execution_scope as _parse
    return _parse(args)


def require_tool_execution_scope(args: dict[str, Any] | None):
    _ensure_phone_mcp()
    from cyclone_phone_mcp.session import require_tool_execution_scope as _require
    return _require(args)


def session_scope_error_result(exc: Exception) -> dict[str, Any]:
    _ensure_phone_mcp()
    from cyclone_phone_mcp.session import session_scope_error_result as _result
    return _result(exc)  # type: ignore[arg-type]


def is_session_scope_error(exc: Exception) -> bool:
    _ensure_phone_mcp()
    from cyclone_phone_mcp.session import SessionScopeError
    return isinstance(exc, SessionScopeError)


def compact_observation(payload: Any, *, goal: str = "") -> dict[str, Any]:
    _ensure_phone_mcp()
    from cyclone_phone_mcp.compact import compact_observation as _compact
    return _compact(payload, goal=goal)


def normalize_desktop_action(device_id: str, tool: str, raw: Any) -> dict[str, Any]:
    _ensure_phone_mcp()
    from cyclone_phone_mcp.gateway import normalize_desktop_action as _normalize
    return _normalize(device_id, tool, raw)


def skill_save_payload(args: dict[str, Any]) -> dict[str, Any]:
    _ensure_phone_mcp()
    from cyclone_phone_mcp.skills import build_save_payload
    return build_save_payload(args)


def skill_save_success(result: Any, payload: dict[str, Any]) -> dict[str, Any]:
    _ensure_phone_mcp()
    from cyclone_phone_mcp.skills import save_success
    return save_success(result, payload)


def skill_run_normalize(result: Any, *, skill_id: str, dry_run: bool) -> dict[str, Any]:
    _ensure_phone_mcp()
    from cyclone_phone_mcp.skills import normalize_run
    return normalize_run(result, skill_id=skill_id, dry_run=dry_run)


def draft_run_denied(skill_id: str, status: str = "draft") -> dict[str, Any]:
    _ensure_phone_mcp()
    from cyclone_phone_mcp.skills import draft_run_denied as _denied
    return _denied(skill_id, status)


def matched_verified_skill(match_raw: Any, goal: str, page_key: str) -> dict[str, Any] | None:
    _ensure_phone_mcp()
    from cyclone_phone_mcp.skills import matched_verified_skill as _match
    return _match(match_raw, goal, page_key)


def validate_open_app_params(params: dict[str, Any]) -> None:
    _ensure_phone_mcp()
    from cyclone_phone_mcp.tools import _validate_open_app_params as _validate
    _validate(params)
