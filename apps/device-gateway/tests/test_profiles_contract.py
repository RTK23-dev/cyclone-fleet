"""Plan 43 (T4): the phone's profiles over the V5 contract. Labels, looks and which profile is in front; a profile's
apps as packages and labels; switching, and adding or removing an app in one Cyclone profile."""
from __future__ import annotations

from typing import Any

import pytest

from cyclone_device_gateway.desktop_runtime.models import DesktopRuntimeError
from cyclone_device_gateway.desktop_runtime.v5_contract import V5ContractService, validate_android_response

B = "Cyclone_0123456789abcdef"
LIST = {"profiles": [
    {"id": "main", "label": "Profile A", "emoji": None, "color": None, "ready": True, "current": False, "inTrash": False},
    {"id": B, "label": "Brand B", "emoji": "🛍", "color": "#FF7C4DFF", "ready": True, "current": True, "inTrash": False}],
    "current": B}


def test_profile_results_are_checked():
    assert validate_android_response("profiles.list", LIST, {}) == LIST
    apps = {"apps": [{"package": "com.instagram.android", "label": "Instagram"}], "available": [], "truncated": False}
    assert validate_android_response("profiles.apps", apps, {"profileId": B}) == apps
    assert validate_android_response("profiles.switch", {"switched": True, "current": B}, {"profileId": B})
    assert validate_android_response("profiles.app", {"done": True}, {"profileId": B, "package": "com.x.app", "action": "install"})
    bad = [
        ("profiles.list", {**LIST, "extra": 1}, {}),
        ("profiles.list", {"profiles": [{**LIST["profiles"][0], "id": "../etc"}], "current": None}, {}),
        ("profiles.list", {"profiles": [{**LIST["profiles"][1], "color": "red"}], "current": None}, {}),
        ("profiles.apps", {"apps": [{"package": "rm -rf", "label": "x"}], "available": [], "truncated": False}, {"profileId": B}),
        ("profiles.switch", {"switched": True, "current": "main"}, {"profileId": B}),
        ("profiles.app", {"done": False}, {}),
    ]
    for op, value, args in bad:
        with pytest.raises(DesktopRuntimeError):
            validate_android_response(op, value, args)


class Recorder(V5ContractService):
    def __init__(self) -> None:
        self.calls: list[tuple[str, str, dict[str, Any]]] = []

    def _call(self, device_id: str, op: str, args: dict[str, Any], *, checked: bool = False) -> dict[str, Any]:
        self.calls.append((device_id, op, args))
        return {}


def test_profile_requests_are_checked_before_they_reach_the_phone():
    service = Recorder()
    service.profiles_list("pixel8")
    service.profiles_apps("pixel8", B)
    service.profiles_switch("pixel8", "main")
    service.profiles_app("pixel8", B, "com.instagram.android", "remove")
    assert [c[1] for c in service.calls] == ["profiles.list", "profiles.apps", "profiles.switch", "profiles.app"]
    assert service.calls[-1][2] == {"profileId": B, "package": "com.instagram.android", "action": "remove"}
    for call in (lambda: service.profiles_switch("pixel8", "Cyclone_nothex"),
                 lambda: service.profiles_app("pixel8", "main", "com.x.app", "install"),
                 lambda: service.profiles_app("pixel8", B, "com.cyclone.mobile", "remove"),
                 lambda: service.profiles_app("pixel8", B, "com.x.app", "wipe")):
        with pytest.raises(DesktopRuntimeError):
            call()
    assert len(service.calls) == 4
