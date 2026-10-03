from __future__ import annotations

import pytest
from pydantic import ValidationError

from cyclone_device_gateway.capabilities.models import (
    CapabilityActionRequest,
    CapabilityDiscoveryResponse,
    CapabilityHealth,
    CapabilityHealthState,
)
from cyclone_device_gateway.capabilities.registry import CapabilityRegistry
from cyclone_device_gateway.capabilities.service import _safe_android_execution


def request(tool: str, params: dict) -> CapabilityActionRequest:
    return CapabilityActionRequest(
        correlation_id="gesture-test",
        capability_id=tool,
        params=params,
        goal="test",
    )


class Bridge:
    def __init__(self, status):
        self.status = status
        self.calls = []

    def request(self, op, args):
        self.calls.append((op, args))
        if isinstance(self.status, Exception):
            raise self.status
        return self.status


def ready_status(**extra):
    value = {
        "gatewayEnabled": True,
        "socketListening": True,
        "accessibilityConnected": True,
    }
    value.update(extra)
    return value


def test_profiles_are_accepted_for_existing_touch_actions():
    for tool in ("phone.click", "phone.long_press", "phone.swipe", "phone.scroll"):
        for profile in ("auto", "off", "light", "normal"):
            assert request(tool, {"humanize": profile}).params["humanize"] == profile


def test_omitted_humanize_is_backward_compatible():
    assert request("phone.click", {"elementId": "e1"}).params == {"elementId": "e1"}


def test_invalid_profile_fails_closed():
    with pytest.raises(ValidationError):
        request("phone.click", {"humanize": "maximum"})


def test_humanize_is_not_a_generic_action_escape_hatch():
    with pytest.raises(ValidationError):
        request("phone.home", {"humanize": "normal"})


@pytest.mark.parametrize(
    "key",
    ["control1", "control2", "controlPoints", "bezier", "path", "points", "samples", "trajectory", "strokes"],
)
def test_pc_raw_trajectory_fields_are_rejected(key: str):
    with pytest.raises(ValidationError):
        request("phone.swipe", {key: []})


def test_old_mobile_capability_fallback_never_implies_runtime_support():
    bridge = Bridge(ready_status())
    discovery = CapabilityRegistry().discover(bridge).model_dump()
    gesture = discovery["human_gesture"]
    assert bridge.calls == [("bridge.status", {})]
    assert gesture["transport_schema_ready"] is True
    assert gesture["runtime_available"] is False
    assert gesture["runtime_source"] == "legacy_fallback"
    assert gesture["reason_code"] == "MOBILE_SIGNAL_ABSENT"
    assert gesture["profiles"] == ()
    assert gesture["actions"]["phone.drag"] == "unsupported"
    assert all(value == "runtime_unreported" for value in gesture["execution_planes"].values())


def test_matching_phone_runtime_signal_drives_action_and_plane_truth():
    bridge = Bridge(ready_status(capabilities={"humanGesture": {
        "runtimeAvailable": True,
        "controlVersion": "cyclone.human_gesture.control.v1",
        "traceVersion": "cyclone.human_gesture.trace.v1",
        "synthesisVersion": "human-gesture-v03",
        "profiles": ["AUTO", "OFF", "LIGHT", "NORMAL", "future"],
        "actions": {
            "phone.click": "semantic_first_with_fallback",
            "phone.long_press": "semantic_or_touch",
            "phone.swipe": "synthesized_touch",
            "phone.scroll": "semantic_only",
            "phone.drag": "supported",
        },
        "executionPlanes": {
            "foreground": "cubic",
            "sessionKernelVd": "endpoint_duration",
            "layer2Workspace": "downgraded",
        },
    }}))
    gesture = CapabilityRegistry().discover(bridge).model_dump()["human_gesture"]
    assert bridge.calls == [("bridge.status", {})]
    assert gesture["runtime_available"] is True
    assert gesture["runtime_source"] == "bridge_status"
    assert gesture["reason_code"] is None
    assert gesture["profiles"] == ("auto", "off", "light", "normal")
    assert gesture["actions"]["phone.click"] == "semantic_or_touch"
    assert gesture["actions"]["phone.long_press"] == "semantic_or_touch"
    assert gesture["actions"]["phone.swipe"] == "synthesized_touch"
    assert gesture["actions"]["phone.scroll"] == "semantic_native"
    assert gesture["actions"]["phone.drag"] == "unsupported"
    assert gesture["execution_planes"] == {
        "foreground": "full_fidelity",
        "session_kernel_vd": "legacy_touch",
        "layer2_workspace": "downgraded",
    }


def test_exact_mobile_capability_registry_shape_is_normalized_truthfully():
    bridge = Bridge(ready_status(capabilities=[{
        "name": "human_gesture",
        "status": "AVAILABLE",
        "detail": None,
        "runtime": {
            "runtimeAvailable": True,
            "controlVersion": "cyclone.human_gesture.control.v1",
            "traceVersion": "cyclone.human_gesture.trace.v1",
            "synthesisVersion": "human-gesture-v03",
            "supportedProfiles": ["auto", "off", "light", "normal"],
            "actions": {
                "clickFallback": {
                    "humanizeAccepted": True,
                    "autoProfile": "light",
                    "foregroundMode": "semantic_first_then_synthesized_touch",
                },
                "tap": {
                    "humanizeAccepted": True,
                    "autoProfile": "light",
                    "foregroundMode": "synthesized_touch",
                },
                "longPressFallback": {
                    "humanizeAccepted": True,
                    "autoProfile": "light",
                    "foregroundMode": "semantic_first_then_synthesized_touch",
                },
                "swipe": {
                    "humanizeAccepted": True,
                    "autoProfile": "normal",
                    "foregroundMode": "synthesized_touch",
                },
                "scroll": {
                    "humanizeAccepted": True,
                    "autoProfile": "normal",
                    "foregroundMode": "semantic_first_then_safe_grounded_fallback",
                },
            },
            "executionPlanes": {
                "foregroundDisplay0": {
                    "backend": "accessibility_dispatch_gesture",
                    "cubicPath": True,
                    "humanGesture": True,
                },
                "namedVirtualDisplay": {
                    "backend": "accessibility_dispatch_gesture",
                    "cubicPath": True,
                    "humanGesture": True,
                    "displayTarget": "gesture_description_set_display_id",
                },
                "layer2": {
                    "backend": "accessibility_dispatch_gesture",
                    "cubicPath": True,
                    "humanGesture": True,
                    "ownership": "layer2_display0_mutation_lease",
                },
            },
        },
    }]))
    gesture = CapabilityRegistry().discover(bridge).model_dump()["human_gesture"]
    assert gesture["runtime_available"] is True
    assert gesture["profiles"] == ("auto", "off", "light", "normal")
    assert gesture["actions"] == {
        "phone.click": "semantic_or_touch",
        "phone.long_press": "semantic_or_touch",
        "phone.scroll": "semantic_or_touch",
        "phone.swipe": "synthesized_touch",
        "phone.drag": "unsupported",
    }
    assert gesture["execution_planes"] == {
        "foreground": "full_fidelity",
        "session_kernel_vd": "full_fidelity",
        "layer2_workspace": "full_fidelity",
    }


def test_wrong_mobile_control_version_stays_unavailable():
    bridge = Bridge(ready_status(humanGesture={
        "runtimeAvailable": True,
        "controlVersion": "cyclone.human_gesture.control.v999",
        "profiles": ["auto", "off", "light", "normal"],
    }))
    gesture = CapabilityRegistry().discover(bridge).model_dump()["human_gesture"]
    assert gesture["runtime_available"] is False
    assert gesture["runtime_source"] == "bridge_status"
    assert gesture["reason_code"] == "CONTROL_VERSION_MISMATCH"


def test_static_model_default_remains_conservative_for_direct_construction():
    discovery = CapabilityDiscoveryResponse(
        gateway_health=CapabilityHealth(state=CapabilityHealthState.AVAILABLE),
        capabilities=(),
    ).model_dump()
    assert discovery["human_gesture"]["runtime_available"] is False


def test_bounded_android_gesture_projection_keeps_only_authoritative_safe_fields():
    trace_hash = "a" * 64
    raw = {
        "result": {
            "execution": {
                "ok": True,
                "beforeFingerprint": "before",
                "afterFingerprint": "after",
                "pageChanged": True,
                "gesture": {
                    "controlVersion": "cyclone.human_gesture.control.v1",
                    "traceVersion": "cyclone.human_gesture.trace.v1",
                    "synthesisVersion": "human-gesture-v03",
                    "profileRequested": "normal",
                    "profileResolved": "LIGHT",
                    "mode": "synthesized_touch",
                    "backend": "accessibility_dispatch",
                    "traceHash": trace_hash,
                    "synthesisUs": 211,
                    "downgradeReason": "EDGE_CAPACITY",
                    "points": [{"x": 1, "y": 2}],
                    "selector": {"text": "secret"},
                    "typedValue": "secret",
                },
                "unboundedAndroidField": {"secret": "do not project"},
            }
        }
    }
    projected = _safe_android_execution(raw)
    assert projected == {
        "ok": True,
        "beforeFingerprint": "before",
        "afterFingerprint": "after",
        "pageChanged": True,
        "gesture": {
            "controlVersion": "cyclone.human_gesture.control.v1",
            "traceVersion": "cyclone.human_gesture.trace.v1",
            "synthesisVersion": "human-gesture-v03",
            "backend": "accessibility_dispatch",
            "profileRequested": "normal",
            "profileResolved": "LIGHT",
            "mode": "synthesized_touch",
            "traceHash": trace_hash,
            "synthesisUs": 211,
            "downgradeReason": "EDGE_CAPACITY",
        },
    }


def test_exact_mobile_diagnostic_shape_projects_to_canonical_bounded_fields():
    trace_hash = "b" * 64
    projected = _safe_android_execution({
        "result": {"execution": {
            "ok": True,
            "humanGesture": {
                "controlVersion": "cyclone.human_gesture.control.v1",
                "traceVersion": "cyclone.human_gesture.trace.v1",
                "synthesisVersion": "human-gesture-v03",
                "requestedHumanize": "normal",
                "resolvedProfile": "LIGHT",
                "appliedProfile": "LIGHT",
                "interactionMode": "synthesized_touch",
                "dispatchMode": "human_gesture",
                "backend": "accessibility_dispatch_gesture",
                "traceHash": trace_hash,
                "synthesisUs": 317,
                "downgradeReason": "EDGE_CAPACITY",
                "rawPoints": [{"x": 1, "y": 2}],
            },
        }}
    })
    assert projected == {
        "ok": True,
        "gesture": {
            "controlVersion": "cyclone.human_gesture.control.v1",
            "traceVersion": "cyclone.human_gesture.trace.v1",
            "synthesisVersion": "human-gesture-v03",
            "backend": "accessibility_dispatch_gesture",
            "profileRequested": "normal",
            "profileResolved": "LIGHT",
            "mode": "synthesized_touch",
            "traceHash": trace_hash,
            "synthesisUs": 317,
            "downgradeReason": "EDGE_CAPACITY",
        },
    }


def test_malformed_gesture_fields_are_dropped_not_reinterpreted():
    projected = _safe_android_execution({
        "result": {"execution": {
            "ok": True,
            "gesture": {
                "profileRequested": "maximum",
                "profileResolved": "AUTO",
                "mode": "anything",
                "traceHash": "not-a-hash",
                "synthesisUs": -1,
                "downgradeReason": "contains spaces and arbitrary text",
                "points": [1, 2, 3],
            },
        }}
    })
    assert projected == {"ok": True}


def test_incomplete_queued_gesture_projects_completed_false():
    projected = _safe_android_execution({
        "result": {"execution": {
            "ok": False,
            "humanGesture": {
                "controlVersion": "cyclone.human_gesture.control.v1",
                "traceVersion": "cyclone.human_gesture.trace.v1",
                "synthesisVersion": "human-gesture-v03",
                "requestedHumanize": "auto",
                "resolvedProfile": "LIGHT",
                "interactionMode": "synthesized_touch",
                "dispatchMode": "human_gesture",
                "backend": "accessibility_dispatch_gesture",
                "completed": False,
                "reason": "gesture_timeout",
            },
        }}
    })
    assert projected["ok"] is False
    assert projected["gesture"]["completed"] is False
    assert projected["gesture"]["profileRequested"] == "auto"
    assert "reason" not in projected["gesture"]
    assert "points" not in projected.get("gesture", {})
