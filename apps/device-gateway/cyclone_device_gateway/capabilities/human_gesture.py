from __future__ import annotations

import re
from typing import Any

CONTROL_VERSION = "cyclone.human_gesture.control.v1"
TRACE_VERSION = "cyclone.human_gesture.trace.v1"
PROFILE_VALUES = ("auto", "off", "light", "normal")
HUMANIZE_ACTIONS = frozenset({"phone.click", "phone.long_press", "phone.swipe", "phone.scroll"})
SAFE_ACTION_STATES = frozenset({
    "supported",
    "semantic_native",
    "semantic_or_touch",
    "synthesized_touch",
    "legacy_touch",
    "downgraded",
    "unsupported",
    "not_reported",
})
SAFE_PLANE_STATES = frozenset({
    "full_fidelity",
    "supported",
    "legacy_touch",
    "downgraded",
    "backend_dependent",
    "unsupported",
    "not_reported",
})
_SAFE_TOKEN = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.:-]{0,95}")
_SHA256 = re.compile(r"[0-9a-fA-F]{64}")

_FALLBACK_ACTIONS = {
    "phone.click": "transport_ready_runtime_unreported",
    "phone.long_press": "transport_ready_runtime_unreported",
    "phone.scroll": "transport_ready_runtime_unreported",
    "phone.swipe": "transport_ready_runtime_unreported",
    "phone.drag": "unsupported",
}
_FALLBACK_PLANES = {
    "foreground": "runtime_unreported",
    "session_kernel_vd": "runtime_unreported",
    "layer2_workspace": "runtime_unreported",
}
_ACTION_ALIASES = {
    "phone.click": ("phone.click", "clickFallback", "click_fallback", "tap"),
    "phone.long_press": ("phone.long_press", "longPressFallback", "long_press_fallback"),
    "phone.swipe": ("phone.swipe", "swipe"),
    "phone.scroll": ("phone.scroll", "scroll"),
}
_PLANE_ALIASES = {
    "foreground": ("foreground", "foregroundDisplay0", "foreground_display0"),
    "session_kernel_vd": (
        "session_kernel_vd",
        "sessionKernelVd",
        "namedVirtualDisplay",
        "named_virtual_display",
    ),
    "layer2_workspace": ("layer2_workspace", "layer2Workspace", "layer2"),
}


def _read(mapping: dict[str, Any], *keys: str) -> Any:
    for key in keys:
        if key in mapping:
            return mapping[key]
    return None


def _runtime_from_capability_entry(entry: Any) -> dict[str, Any] | None:
    if not isinstance(entry, dict):
        return None
    name = _read(entry, "name", "key", "id")
    if isinstance(name, str) and name.replace("-", "_").lower() not in {"human_gesture", "humangesture"}:
        return None
    runtime = entry.get("runtime")
    if isinstance(runtime, dict):
        return runtime
    config = entry.get("config")
    if isinstance(config, dict):
        details = config.get("details")
        if isinstance(details, dict):
            runtime = details.get("runtime")
            if isinstance(runtime, dict):
                return runtime
            if _read(details, "controlVersion", "control_version") is not None:
                return details
    if _read(entry, "controlVersion", "control_version") is not None:
        return entry
    return None


def _runtime_block(status: Any) -> dict[str, Any] | None:
    if not isinstance(status, dict):
        return None
    for key in ("humanGesture", "human_gesture"):
        value = status.get(key)
        if isinstance(value, dict):
            return value
    capabilities = status.get("capabilities")
    if isinstance(capabilities, dict):
        for key in ("humanGesture", "human_gesture"):
            value = capabilities.get(key)
            if isinstance(value, dict):
                nested = _runtime_from_capability_entry(value)
                return nested or value
        nested_capabilities = capabilities.get("capabilities")
        if isinstance(nested_capabilities, list):
            for entry in nested_capabilities:
                runtime = _runtime_from_capability_entry(entry)
                if runtime is not None:
                    return runtime
    elif isinstance(capabilities, list):
        for entry in capabilities:
            runtime = _runtime_from_capability_entry(entry)
            if runtime is not None:
                return runtime
    for key in ("phoneCapabilities", "phone_capabilities"):
        values = status.get(key)
        if isinstance(values, list):
            for entry in values:
                runtime = _runtime_from_capability_entry(entry)
                if runtime is not None:
                    return runtime
    return None


def _profiles(value: Any) -> tuple[str, ...]:
    if not isinstance(value, (list, tuple)):
        return ()
    found: list[str] = []
    for item in value:
        if not isinstance(item, str):
            continue
        normalized = item.strip().lower()
        if normalized in PROFILE_VALUES and normalized not in found:
            found.append(normalized)
    return tuple(found)


def _normalize_state(value: Any, allowed: frozenset[str]) -> str:
    if isinstance(value, bool):
        return "supported" if value else "unsupported"
    if isinstance(value, dict):
        if value.get("supported") is False or value.get("humanizeAccepted") is False:
            return "unsupported"
        explicit = value.get("mode") or value.get("state") or value.get("support")
        if explicit is None and "foregroundMode" in value:
            explicit = value.get("foregroundMode")
        if explicit is None and value.get("supported") is True:
            explicit = "supported"
        if explicit is None and value.get("cubicPath") is True:
            explicit = "full_fidelity"
        if explicit is None and value.get("humanGesture") is True:
            explicit = "full_fidelity"
        if explicit is None and value.get("compatibility") is not None:
            explicit = value.get("compatibility")
        if explicit is None and value.get("humanizeAccepted") is True:
            explicit = "supported"
        value = explicit
    if not isinstance(value, str):
        return "not_reported"
    token = value.strip().lower().replace("-", "_").replace(" ", "_")
    aliases = {
        "full": "full_fidelity",
        "cubic": "full_fidelity",
        "cubic_supported": "full_fidelity",
        "endpoint_duration": "legacy_touch",
        "endpoint_duration_only": "legacy_touch",
        "semantic_only": "semantic_native",
        "semantic_first": "semantic_native",
        "semantic_first_with_fallback": "semantic_or_touch",
        "semantic_first_then_synthesized_touch": "semantic_or_touch",
        "semantic_first_then_safe_grounded_fallback": "semantic_or_touch",
        "human_gesture": "synthesized_touch",
        "touch": "synthesized_touch",
    }
    token = aliases.get(token, token)
    return token if token in allowed else "not_reported"


def _action_state(raw_actions: dict[str, Any], action: str) -> str:
    for key in _ACTION_ALIASES[action]:
        if key in raw_actions:
            return _normalize_state(raw_actions.get(key), SAFE_ACTION_STATES)
    return "not_reported"


def _plane_state(raw_planes: dict[str, Any], plane: str) -> str:
    for key in _PLANE_ALIASES[plane]:
        if key in raw_planes:
            return _normalize_state(raw_planes.get(key), SAFE_PLANE_STATES)
    return "not_reported"


def discovery_from_bridge_status(status: Any) -> dict[str, Any]:
    """Project only phone-originated Human Gesture truth; old/unknown phones stay conservative."""
    block = _runtime_block(status)
    fallback = {
        "control_version": CONTROL_VERSION,
        "trace_version": TRACE_VERSION,
        "transport_schema_ready": True,
        "runtime_available": False,
        "runtime_source": "legacy_fallback",
        "reason_code": "MOBILE_SIGNAL_ABSENT",
        "profiles": (),
        "actions": dict(_FALLBACK_ACTIONS),
        "execution_planes": dict(_FALLBACK_PLANES),
    }
    if block is None:
        return fallback

    control = _read(block, "controlVersion", "control_version")
    runtime = _read(block, "runtimeAvailable", "runtime_available")
    trace = _read(block, "traceVersion", "trace_version")
    synthesis = _read(block, "synthesisVersion", "synthesis_version")
    if control != CONTROL_VERSION:
        fallback.update(runtime_source="bridge_status", reason_code="CONTROL_VERSION_MISMATCH")
        return fallback
    if runtime is not True:
        fallback.update(runtime_source="bridge_status", reason_code="RUNTIME_UNAVAILABLE")
        return fallback

    raw_actions = _read(block, "actions", "actionSupport", "action_support")
    raw_actions = raw_actions if isinstance(raw_actions, dict) else {}
    actions = {action: _action_state(raw_actions, action) for action in sorted(HUMANIZE_ACTIONS)}
    actions["phone.drag"] = "unsupported"

    raw_planes = _read(block, "executionPlanes", "execution_planes", "planes")
    raw_planes = raw_planes if isinstance(raw_planes, dict) else {}
    planes = {plane: _plane_state(raw_planes, plane) for plane in _PLANE_ALIASES}

    out = {
        "control_version": CONTROL_VERSION,
        "trace_version": trace if isinstance(trace, str) and _SAFE_TOKEN.fullmatch(trace) else TRACE_VERSION,
        "transport_schema_ready": True,
        "runtime_available": True,
        "runtime_source": "bridge_status",
        "reason_code": None,
        "profiles": _profiles(_read(block, "profiles", "supportedProfiles", "supported_profiles")),
        "actions": actions,
        "execution_planes": planes,
    }
    if isinstance(synthesis, str) and _SAFE_TOKEN.fullmatch(synthesis):
        out["synthesis_version"] = synthesis
    return out


def safe_gesture_diagnostics(execution: Any) -> dict[str, Any] | None:
    """Return a bounded diagnostics object and never forward raw Android gesture structures."""
    if not isinstance(execution, dict):
        return None
    raw = execution.get("gesture")
    if not isinstance(raw, dict):
        raw = execution.get("humanGesture")
    if not isinstance(raw, dict):
        return None

    out: dict[str, Any] = {}
    string_fields = {
        "controlVersion": 96,
        "traceVersion": 96,
        "synthesisVersion": 96,
        "backend": 96,
    }
    for key, limit in string_fields.items():
        value = raw.get(key)
        if isinstance(value, str) and len(value) <= limit and _SAFE_TOKEN.fullmatch(value):
            out[key] = value

    requested = _read(raw, "profileRequested", "requestedHumanize")
    if isinstance(requested, str) and requested.strip().lower() in PROFILE_VALUES:
        out["profileRequested"] = requested.strip().lower()
    resolved = _read(raw, "profileResolved", "resolvedProfile", "appliedProfile")
    if isinstance(resolved, str) and resolved.strip().lower() in PROFILE_VALUES[1:]:
        out["profileResolved"] = resolved.strip().upper()

    mode = _read(raw, "mode", "interactionMode", "dispatchMode")
    if isinstance(mode, str):
        normalized_mode = mode.strip().lower().replace("-", "_").replace(" ", "_")
        mode_aliases = {
            "human_gesture": "synthesized_touch",
            "touch": "synthesized_touch",
            "semantic": "semantic_native",
            "semantic_only": "semantic_native",
        }
        normalized_mode = mode_aliases.get(normalized_mode, normalized_mode)
        if normalized_mode in {
            "semantic_native", "synthesized_touch", "legacy_touch", "downgraded", "unsupported"
        }:
            out["mode"] = normalized_mode

    trace_hash = raw.get("traceHash")
    if isinstance(trace_hash, str) and _SHA256.fullmatch(trace_hash):
        out["traceHash"] = trace_hash.lower()

    synthesis_us = raw.get("synthesisUs")
    if type(synthesis_us) is int and 0 <= synthesis_us <= 10_000_000:
        out["synthesisUs"] = synthesis_us

    completed = raw.get("completed")
    if isinstance(completed, bool):
        out["completed"] = completed

    reason = raw.get("downgradeReason")
    if isinstance(reason, str) and len(reason) <= 96 and _SAFE_TOKEN.fullmatch(reason):
        out["downgradeReason"] = reason

    return out or None
