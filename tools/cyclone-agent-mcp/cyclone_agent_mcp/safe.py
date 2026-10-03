from __future__ import annotations

import json
import re
from typing import Any

_SECRET_KEY = re.compile(r"(?i)(token|secret|password|passcode|api[_-]?key|authorization|credential|otp)")
_SECRET_VALUE = re.compile(r"(?i)^(bearer\s+\S+|sk-[A-Za-z0-9_-]{12,})$")
_FORBIDDEN_OPERATION_KEY = re.compile(
    r"(?i)^(?:cmd|command|shell|adb|powershell|subprocess|executable|script|root|su|docker|host_command)$"
)
_HUMANIZE_PROFILES = frozenset({"auto", "off", "light", "normal"})
_HUMANIZE_ACTIONS = frozenset({"phone.click", "phone.long_press", "phone.swipe", "phone.scroll"})
_RAW_TRAJECTORY_KEYS = frozenset({"control1", "control2", "controlPoints", "bezier", "path", "points", "samples", "trajectory", "strokes"})


def redact(value: Any) -> Any:
    if isinstance(value, dict):
        result: dict[str, Any] = {}
        for key, item in value.items():
            if _SECRET_KEY.search(str(key)):
                result[str(key)] = "[REDACTED]"
            else:
                result[str(key)] = redact(item)
        return result
    if isinstance(value, list):
        return [redact(item) for item in value]
    if isinstance(value, tuple):
        return tuple(redact(item) for item in value)
    if isinstance(value, str) and _SECRET_VALUE.match(value.strip()):
        return "[REDACTED]"
    return value


def compact_json(value: Any) -> str:
    return json.dumps(redact(value), ensure_ascii=False, separators=(",", ":"))


def validate_typed_params(value: Any, *, path: str = "params") -> None:
    """Reject command-shaped escape hatches while preserving typed phone-action parameters."""
    if isinstance(value, dict):
        for key, item in value.items():
            key_text = str(key)
            if _FORBIDDEN_OPERATION_KEY.fullmatch(key_text):
                raise ValueError(f"{path}.{key_text} is not a permitted typed phone parameter")
            if key_text in _RAW_TRAJECTORY_KEYS and not (key_text == "path" and isinstance(item, str)):
                raise ValueError(f"{path}.{key_text} is not permitted; Android owns gesture trajectory synthesis")
            if key_text == "humanize" and (not isinstance(item, str) or item not in _HUMANIZE_PROFILES):
                raise ValueError("humanize must be one of auto, off, light, normal")
            validate_typed_params(item, path=f"{path}.{key_text}")
    elif isinstance(value, list):
        if len(value) > 100:
            raise ValueError(f"{path} exceeds the bounded list size")
        for index, item in enumerate(value):
            validate_typed_params(item, path=f"{path}[{index}]")


def validate_human_gesture_params(tool: str, params: dict[str, Any]) -> None:
    """Keep PC gesture control semantic: profile preference only, never trajectory synthesis."""
    humanize = params.get("humanize")
    if humanize is not None:
        if tool not in _HUMANIZE_ACTIONS:
            raise ValueError("humanize is only valid for phone.click, phone.long_press, phone.swipe, or phone.scroll")
        if not isinstance(humanize, str) or humanize not in _HUMANIZE_PROFILES:
            raise ValueError("humanize must be one of auto, off, light, normal")
    forbidden = {
        key for key in _RAW_TRAJECTORY_KEYS.intersection(params)
        if not (key == "path" and isinstance(params.get(key), str))
    }
    if forbidden:
        raise ValueError("PC-authored raw gesture trajectories are not permitted")


def strip_typed_plaintext(value: Any, typed: str | None) -> Any:
    """Drop echoed phone.type plaintext from MCP results without logging it."""
    if not typed:
        return value
    if value == typed:
        return "<redacted>"
    if isinstance(value, dict):
        return {str(key): strip_typed_plaintext(item, typed) for key, item in value.items()}
    if isinstance(value, list):
        return [strip_typed_plaintext(item, typed) for item in value]
    if isinstance(value, str) and typed in value:
        return value.replace(typed, "<redacted>")
    return value
