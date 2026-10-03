from pathlib import Path

import pytest

from cyclone_device_gateway.actions.router import ActionRouter, ActionValidationError
from cyclone_device_gateway.auth import AuditLog
from cyclone_device_gateway.state.store import StateStore


class Bridge:
    def __init__(self):
        self.last_action = None
        self.page = "HOME"
        self.seq = 0

    def semantic(self):
        self.seq += 1
        obs = f"obs-{self.seq}"
        return {
            "observationId": obs,
            "pageKey": self.page,
            "package": "com.test",
            "activity": "Main",
            "semanticControls": [
                {
                    "elementId": f"semantic:{obs}:apps",
                    "label": "Apps",
                    "resourceId": "android:id/apps",
                    "role": "button",
                    "clickable": True,
                    "risk": "SAFE",
                    "selector": {"resourceId": "android:id/apps", "text": "Apps"},
                },
                {
                    "elementId": f"semantic:{obs}:search",
                    "label": "Search",
                    "resourceId": "android:id/search",
                    "role": "textbox",
                    "editable": True,
                    "risk": "SAFE",
                    "selector": {"resourceId": "android:id/search"},
                },
            ],
            "rawAccessibility": {"nodes": []},
        }

    def request(self, op, args=None):
        if op == "action.execute":
            self.last_action = args
            self.page = "APPS"
            return {
                "verification": {"ok": True, "status": "PASSED", "semanticSuccessClaimed": True},
                "execution": {
                    "ok": True,
                    "beforeFingerprint": "a",
                    "afterFingerprint": "b",
                    "error": None,
                }
            }
        return self.semantic()


def make_router(tmp_path: Path, bridge: Bridge, store: StateStore) -> ActionRouter:
    def observe():
        semantic = bridge.semantic()
        oid = store.add_observation(semantic)
        return {**store.get_observation(oid), "device_serial": "PIXEL8"}

    return ActionRouter(
        bridge,
        store,
        AuditLog(tmp_path / "audit.jsonl"),
        observe,
        stabilize=lambda: None,
    )


def test_observation_element_id_is_resolved_before_new_before_observation(tmp_path: Path):
    bridge = Bridge()
    store = StateStore(tmp_path / "db.sqlite")

    initial = bridge.semantic()
    store.add_observation(initial)
    element_id = initial["semanticControls"][0]["elementId"]
    router = make_router(tmp_path, bridge, store)

    result = router.execute(
        tool="phone.click",
        params={"elementId": element_id},
        goal="Open Apps",
    )

    assert result["success"] is True
    forwarded = bridge.last_action["params"]
    assert "elementId" not in forwarded
    assert forwarded["selector"]["resourceId"] == "android:id/apps"
    assert forwarded["selector"]["text"] == "Apps"
    assert forwarded["selector"]["clickable"] is True
    assert forwarded["_gatewayRisk"] == "SAFE"


def test_phone_type_text_alias_is_normalized_to_android_value(tmp_path: Path):
    bridge = Bridge()
    store = StateStore(tmp_path / "db.sqlite")
    initial = bridge.semantic()
    store.add_observation(initial)
    element_id = initial["semanticControls"][1]["elementId"]
    router = make_router(tmp_path, bridge, store)

    result = router.execute(
        tool="phone.type",
        params={"elementId": element_id, "text": "hello world"},
        goal="Enter a search query",
    )

    assert result["success"] is True
    forwarded = bridge.last_action["params"]
    assert forwarded["value"] == "hello world"
    assert "text" not in forwarded
    assert forwarded["selector"]["resourceId"] == "android:id/search"
    assert forwarded["selector"]["editable"] is True
    assert forwarded["_gatewayRisk"] == "SAFE"


def test_phone_type_accepts_direct_selector_aliases(tmp_path: Path):
    bridge = Bridge()
    store = StateStore(tmp_path / "db.sqlite")
    store.add_observation(bridge.semantic())
    router = make_router(tmp_path, bridge, store)

    router.execute(
        tool="phone.type",
        params={"resource_id": "android:id/search", "value": "query"},
        goal="Enter a search query",
    )

    forwarded = bridge.last_action["params"]
    assert forwarded["value"] == "query"
    assert forwarded["selector"] == {"resourceId": "android:id/search"}


def test_phone_type_rejects_focused_field_without_explicit_target(tmp_path: Path):
    bridge = Bridge()
    store = StateStore(tmp_path / "db.sqlite")
    store.add_observation(bridge.semantic())
    router = make_router(tmp_path, bridge, store)

    with pytest.raises(ActionValidationError, match="explicit selector or elementId"):
        router.execute(
            tool="phone.type",
            params={"text": "must not go to arbitrary focused field"},
            goal="Type safely",
        )


def test_phone_type_rejects_conflicting_text_and_value(tmp_path: Path):
    bridge = Bridge()
    store = StateStore(tmp_path / "db.sqlite")
    store.add_observation(bridge.semantic())
    router = make_router(tmp_path, bridge, store)

    with pytest.raises(ActionValidationError, match="disagree"):
        router.execute(
            tool="phone.type",
            params={
                "selector": {"resourceId": "android:id/search"},
                "text": "one",
                "value": "two",
            },
            goal="Type safely",
        )
