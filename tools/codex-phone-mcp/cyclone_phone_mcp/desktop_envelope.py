from __future__ import annotations

from typing import Any

CAPABILITY_PROTOCOL_VERSION = "cyclone.gateway.capability.v1"


def normalize_desktop_action(device_id: str, tool: str, raw: Any) -> dict[str, Any]:
    """Pass through Agent A capability envelopes; adapt leftover Pixel-shaped Desktop blobs.

    Desktop ``/v1/devices/{id}/agent/action`` (PR #45) already emits
    ``cyclone.gateway.capability.v1`` with LayerOutcome ``execution.ok``. Do not rewrite that
    shape. A 3.8.1 Pixel blob with nested ``execution.ok`` / ``androidExecution.ok`` and no
    top-level LayerOutcome ``ok`` is stamped so classify_failure is not PROTOCOL_MISMATCH.
    Missing or non-bool ok stays fail-closed.
    """
    if not isinstance(raw, dict):
        return {
            "protocol_version": CAPABILITY_PROTOCOL_VERSION,
            "capability_id": tool,
            "device_id": device_id,
            "ok": False,
            "transport": {"ok": False, "status": "disconnected"},
            "execution": {"ok": False},
            "verification": {"ok": False, "status": "failed"},
            "error": {"code": "PROTOCOL_MISMATCH", "layer": "PROTOCOL"},
        }
    if (
        raw.get("protocol_version") == CAPABILITY_PROTOCOL_VERSION
        and isinstance(raw.get("execution"), dict)
        and isinstance(raw["execution"].get("ok"), bool)
        and isinstance(raw.get("verification"), dict)
        and isinstance(raw["verification"].get("ok"), bool)
    ):
        # Already a canonical capability envelope (Agent A / V3 action path). Do not re-wrap.
        return raw
    transport = raw.get("transport")
    transport_ok = isinstance(transport, dict) and transport.get("ok") is True
    execution = raw.get("execution")
    execution_ok = _desktop_execution_ok(execution)
    verification = raw.get("verification")
    verification_ok = _desktop_verification_ok(verification)
    canonical_ok = bool(transport_ok and execution_ok and verification_ok)
    verification_out = dict(verification) if isinstance(verification, dict) else {}
    verification_out["ok"] = verification_ok
    verification_out["passed"] = verification_ok
    if not verification_out.get("status"):
        verification_out["status"] = "verified" if verification_ok else "failed"
    after_state = raw.get("afterState") if isinstance(raw.get("afterState"), dict) else raw.get("after")
    return {
        "protocol_version": CAPABILITY_PROTOCOL_VERSION,
        "correlation_id": raw.get("correlation_id"),
        "capability_id": raw.get("capability_id") or tool,
        "device_id": raw.get("device_id") or device_id,
        "ok": canonical_ok,
        "transport": transport if isinstance(transport, dict) else {"ok": transport_ok},
        "execution": _canonical_execution(execution, execution_ok),
        "verification": verification_out,
        "after": raw.get("after") or after_state,
        "afterState": after_state,
        "error": None if canonical_ok else {
            "code": (
                "DEVICE_DISCONNECTED" if not transport_ok
                else "EXECUTION_FAILED" if not execution_ok
                else "VERIFICATION_FAILED"
            ),
            "layer": (
                "TRANSPORT" if not transport_ok
                else "EXECUTION" if not execution_ok
                else "VERIFICATION"
            ),
        },
    }


def _explicit_ok(value: Any) -> bool | None:
    """Return True/False when a layer carries an explicit ok; None if it does not."""
    if isinstance(value, dict) and isinstance(value.get("ok"), bool):
        return value["ok"] is True
    return None


def _desktop_execution_ok(execution: Any) -> bool:
    """Pixel/Desktop nested Android success is canonical ok=true, not PROTOCOL_MISMATCH.

    Desktop fleet actions wrap ``action.execute`` as the execution layer. That payload has
    ``androidExecution.ok`` / nested ``execution.ok`` instead of a top-level ``ok``. Missing
    explicit ok remains fail-closed.
    """
    if not isinstance(execution, dict):
        return False
    for candidate in (
        execution,
        execution.get("androidExecution"),
        execution.get("android_execution"),
        execution.get("execution"),
    ):
        flag = _explicit_ok(candidate)
        if flag is not None:
            return flag
    return False


def _canonical_execution(execution: Any, execution_ok: bool) -> dict[str, Any]:
    """Guarantee classify_failure sees execution.ok on valid Pixel-shaped successes."""
    if isinstance(execution, dict):
        canonical = dict(execution)
        canonical["ok"] = execution_ok
        return canonical
    return {"ok": execution_ok}


def _desktop_verification_ok(verification: Any) -> bool:
    if not isinstance(verification, dict):
        return False
    # Agent A Desktop LayerOutcome: verification.ok + verification.passed are canonical.
    if verification.get("ok") is True and verification.get("passed") is True:
        return True
    passed = verification.get("passed")
    if passed is True:
        return bool(verification.get("after_observation_id"))
    status = str(verification.get("status") or "").upper()
    return (
        verification.get("ok") is True
        and status in {"PASSED", "VERIFIED", "NOT_REQUIRED"}
        and bool(verification.get("after_observation_id"))
    )


def _override_gateway_normalize() -> None:
    """Agent A passthrough must win over leftover 3.8.1 Desktop adapters."""
    try:
        from . import gateway as gw
    except ImportError:
        return
    gw.normalize_desktop_action = normalize_desktop_action


_override_gateway_normalize()
