from __future__ import annotations

import pytest

from cyclone_agent_mcp.safe import validate_human_gesture_params, validate_typed_params
from cyclone_agent_mcp.tools import PhoneTools


class CaptureGateway:
    def __init__(self) -> None:
        self.calls: list[dict] = []

    def action(
        self,
        tool: str,
        params: dict,
        goal: str,
        device_id: str | None = None,
        **identity,
    ) -> dict:
        self.calls.append(
            {
                "tool": tool,
                "params": dict(params),
                "goal": goal,
                "device_id": device_id,
                "identity": dict(identity),
            }
        )
        return {"ok": True}


def action_args(
    tool: str,
    params: dict,
    *,
    session_id: str = "default-foreground",
    display_id: int = 0,
) -> dict:
    return {
        "tool": tool,
        "params": params,
        "goal": "test human gesture transport",
        "session_id": session_id,
        "display_id": display_id,
    }


def test_humanize_profiles_are_semantic_preferences_including_long_press():
    for tool in ("phone.click", "phone.long_press", "phone.swipe", "phone.scroll"):
        for profile in ("auto", "off", "light", "normal"):
            params = {"humanize": profile}
            validate_typed_params(params)
            validate_human_gesture_params(tool, params)


def test_foreground_phone_act_forwards_humanize_and_identity_unchanged():
    gateway = CaptureGateway()
    tools = PhoneTools(gateway=gateway)

    tools.phone_act(action_args("phone.click", {
        "elementId": "current-observation-element",
        "humanize": "light",
    }))

    assert len(gateway.calls) == 1
    call = gateway.calls[0]
    assert call["tool"] == "phone.click"
    assert call["params"] == {"elementId": "current-observation-element", "humanize": "light"}
    assert call["identity"] == {"session_id": "default-foreground", "display_id": 0}


def test_named_vd_phone_act_preserves_exact_nonzero_display_identity():
    gateway = CaptureGateway()
    tools = PhoneTools(gateway=gateway)

    tools.phone_act(action_args(
        "phone.swipe",
        {"x1": 10, "y1": 700, "x2": 10, "y2": 200, "humanize": "normal"},
        session_id="orders-session",
        display_id=7,
    ))

    assert gateway.calls[0]["identity"] == {"session_id": "orders-session", "display_id": 7}
    assert gateway.calls[0]["params"]["humanize"] == "normal"


def test_layer2_phone_act_preserves_workspace_generation_and_display_zero():
    gateway = CaptureGateway()
    tools = PhoneTools(gateway=gateway)
    params = {
        "elementId": "current-observation-element",
        "humanize": "auto",
        "workspaceId": "workspace_abc",
        "workspaceGeneration": 12,
    }

    tools.phone_act(action_args("phone.long_press", params))

    assert gateway.calls[0]["identity"] == {"session_id": "default-foreground", "display_id": 0}
    assert gateway.calls[0]["params"] == params


def test_omitted_field_keeps_old_clients_and_payload_shape_valid():
    gateway = CaptureGateway()
    tools = PhoneTools(gateway=gateway)
    params = {"elementId": "current-observation-element"}

    tools.phone_act(action_args("phone.click", params))

    assert gateway.calls[0]["params"] == params
    assert "humanize" not in gateway.calls[0]["params"]


def test_invalid_profile_rejected_before_transport():
    gateway = CaptureGateway()
    tools = PhoneTools(gateway=gateway)

    with pytest.raises(ValueError):
        tools.phone_act(action_args("phone.click", {"humanize": "random"}))

    assert gateway.calls == []


def test_humanize_rejected_on_non_touch_action_before_transport():
    gateway = CaptureGateway()
    tools = PhoneTools(gateway=gateway)

    with pytest.raises(ValueError):
        tools.phone_act(action_args("phone.home", {"humanize": "normal"}))

    assert gateway.calls == []


def test_raw_path_choreography_rejected_before_transport():
    gateway = CaptureGateway()
    tools = PhoneTools(gateway=gateway)

    for params in (
        {"path": [[0, 0], [1, 1]]},
        {"control1": {"x": 1, "y": 2}},
        {"trajectory": [{"x": 1, "y": 2}]},
    ):
        with pytest.raises(ValueError):
            tools.phone_act(action_args("phone.swipe", params))

    assert gateway.calls == []


def test_missing_execution_identity_fails_before_transport():
    gateway = CaptureGateway()
    tools = PhoneTools(gateway=gateway)

    with pytest.raises(ValueError):
        tools.phone_act({
            "tool": "phone.click",
            "params": {"elementId": "current-observation-element", "humanize": "light"},
            "goal": "must stay scoped",
        })

    assert gateway.calls == []


def test_named_vd_cannot_be_normalized_into_layer2():
    gateway = CaptureGateway()
    tools = PhoneTools(gateway=gateway)

    with pytest.raises(ValueError):
        tools.phone_act(action_args(
            "phone.click",
            {
                "elementId": "current-observation-element",
                "humanize": "light",
                "workspaceId": "workspace_abc",
                "workspaceGeneration": 12,
            },
            session_id="named-session",
            display_id=8,
        ))

    assert gateway.calls == []
