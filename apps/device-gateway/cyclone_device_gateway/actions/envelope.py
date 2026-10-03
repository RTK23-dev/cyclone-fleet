from __future__ import annotations

import re
from typing import Any

ERROR_CODE_PATTERN = re.compile(r"[A-Z][A-Z0-9_]{0,63}")
_EXECUTION_KEYS = ("execution", "androidExecution", "android_execution")
_SAFE_EXECUTION_FIELDS = (
    "ok",
    "beforeFingerprint",
    "afterFingerprint",
    "error",
    "verification",
    "pageChanged",
)


def extract_android_execution(result: Any) -> dict[str, Any] | None:
    """Return the Android execution object when it carries a boolean ``ok``.

    Pixel 3.8.1 ``action.execute`` results place authoritative ``ok`` on nested
    ``execution``, nested ``androidExecution``, or (less often) the result root.
    A missing or non-bool ``ok`` is malformed and must stay fail-closed.
    """
    if not isinstance(result, dict):
        return None
    for key in _EXECUTION_KEYS:
        payload = result.get(key)
        if isinstance(payload, dict) and isinstance(payload.get("ok"), bool):
            return payload
    if isinstance(result.get("ok"), bool):
        extracted = {field: result[field] for field in _SAFE_EXECUTION_FIELDS if field in result}
        extracted["ok"] = result["ok"]
        return extracted
    return None


def android_execution_error_class(execution: dict[str, Any] | None) -> str:
    if not isinstance(execution, dict):
        return "PROTOCOL_MISMATCH"
    error = execution.get("error")
    if isinstance(error, dict):
        code = str(error.get("code") or "").upper()
        if ERROR_CODE_PATTERN.fullmatch(code):
            return code
    return "EXECUTION_FAILED"


def safe_android_execution(result: Any) -> dict[str, Any] | None:
    execution = extract_android_execution(result)
    if not isinstance(execution, dict):
        return None
    return {key: execution[key] for key in _SAFE_EXECUTION_FIELDS if key in execution}


def canonical_error(
    code: str,
    layer: str,
    message: str,
    *,
    retryable: bool = False,
) -> dict[str, Any]:
    return {"code": code, "layer": layer, "message": message, "retryable": retryable}
