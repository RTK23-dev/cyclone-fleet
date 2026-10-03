from __future__ import annotations

from types import SimpleNamespace

import pytest

from cyclone_device_gateway.desktop_runtime.agent import DesktopAgentService


class AndroidBridge:
    def __init__(self, verification: dict):
        self.verification = verification
        self.observation = 0

    def request(self, op, args=None, request_id=None):
        if op == "action.execute":
            return {
                "execution": {"ok": True},
                "androidExecution": {"ok": True},
                "verification": self.verification,
            }
        if op == "observe.semantic":
            self.observation += 1
            return {
                "observationId": f"obs-{self.observation}",
                "pageKey": "HOME",
                "package": "com.android.launcher3",
                "pageTitle": "Home",
                "pageText": "Home",
                "pageSummary": "Home launcher",
            }
        raise AssertionError(op)


class OneDeviceFleet:
    def __init__(self, bridge: AndroidBridge):
        self.session = SimpleNamespace(
            credential="credential",
            bridge=lambda: bridge,
        )

    def get(self, device_id):
        assert device_id == "dev_test"
        return self.session


@pytest.mark.parametrize(
    ("android_verification", "expected_status"),
    [
        ({"ok": False, "status": "FAILED", "code": "VERIFICATION_FAILED"}, "FAILED"),
        ({"ok": True, "status": "OBSERVED", "semanticSuccessClaimed": False}, "OBSERVED"),
    ],
)
def test_desktop_never_promotes_android_failure_or_observation_to_verified(
    android_verification, expected_status
):
    service = DesktopAgentService(OneDeviceFleet(AndroidBridge(android_verification)))
    result = service.action(
        "dev_test",
        {
            "capability_id": "phone.click",
            "expected_observation_id": "obs-before",
            "params": {"elementId": "apps"},
        },
    )

    assert result["execution"]["androidExecution"]["ok"] is True
    assert result["verification"]["passed"] is False
    assert result["verification"]["status"] == expected_status
    assert result["verification"]["authority"] == "ANDROID_CANONICAL"


def test_desktop_accepts_only_android_passed_semantic_verification():
    service = DesktopAgentService(
        OneDeviceFleet(AndroidBridge({"ok": True, "status": "PASSED"}))
    )
    result = service.action(
        "dev_test",
        {
            "capability_id": "phone.home",
            "expected_observation_id": "obs-before",
        },
    )

    assert result["verification"]["passed"] is True
    assert result["verification"]["status"] == "PASSED"


class GoalAwareBridge(AndroidBridge):
    def request(self, op, args=None, request_id=None):
        if op == "observe.semantic":
            self.observation += 1
            return {
                "observationId": f"obs-{self.observation}",
                "pageKey": "HOME",
                "package": "com.android.launcher3",
                "pageTitle": "Home",
                "pageText": "See all 98 apps. Ask Cyclone.",
                "pageSummary": "Home launcher with See all 98 apps",
            }
        return super().request(op, args, request_id)


def test_existing_goal_label_does_not_verify_unchanged_click():
    service = DesktopAgentService(
        OneDeviceFleet(
            GoalAwareBridge({"ok": True, "status": "OBSERVED", "semanticSuccessClaimed": False})
        )
    )
    result = service.action(
        "dev_test",
        {
            "capability_id": "phone.click",
            "expected_observation_id": "obs-before",
            "goal": "See all 98 apps",
            "params": {"elementId": "see-all"},
        },
    )
    assert result["execution"]["androidExecution"]["ok"] is True
    assert result["verification"]["passed"] is False
    assert result["ok"] is False
    assert result["verification"].get("basis") != "ALREADY_ON_PAGE"
    assert result["verification"]["authority"] == "ANDROID_CANONICAL"
    assert result["afterState"]["pageKey"] == "HOME" or result["verification"]["after_page_key"] == "HOME"


def test_explicit_negative_verification_cannot_be_overridden_by_existing_label():
    service = DesktopAgentService(
        OneDeviceFleet(
            GoalAwareBridge({"ok": False, "status": "OBSERVED", "code": "VERIFICATION_FAILED", "semanticSuccessClaimed": False})
        )
    )
    result = service.action(
        "dev_test",
        {
            "capability_id": "phone.click",
            "expected_observation_id": "obs-before",
            "goal": "See all 98 apps",
            "params": {"elementId": "see-all"},
        },
    )
    assert result["execution"]["androidExecution"]["ok"] is True
    assert result["verification"]["passed"] is False
    assert result["ok"] is False
    assert result["verification"].get("basis") != "ALREADY_ON_PAGE"


class FailingHomeExecutionBridge(AndroidBridge):
    def request(self, op, args=None, request_id=None):
        if op == "action.execute":
            return {
                "execution": {
                    "ok": False,
                    "error": {"code": "EXECUTION_FAILED", "message": "home failed"},
                },
                "androidExecution": {
                    "ok": False,
                    "error": {"code": "EXECUTION_FAILED", "message": "home failed"},
                },
                "verification": {"ok": False, "status": "FAILED"},
            }
        return super().request(op, args, request_id)


def test_phone_home_already_on_home_is_not_verification_failed():
    service = DesktopAgentService(
        OneDeviceFleet(
            AndroidBridge(
                {
                    "ok": False,
                    "status": "OBSERVED",
                    "code": "VERIFICATION_FAILED",
                    "semanticSuccessClaimed": False,
                }
            )
        )
    )
    result = service.action(
        "dev_test",
        {
            "capability_id": "phone.home",
            "expected_observation_id": "obs-before",
            "goal": "Go home",
        },
    )
    assert result["execution"]["androidExecution"]["ok"] is True
    assert result["verification"]["passed"] is True
    assert result["ok"] is True
    assert result["verification"]["basis"] == "ALREADY_ON_HOME"
    assert result["error"] is None
    assert result["verification"].get("error") is None
    assert result["verification"]["status"] != "VERIFICATION_FAILED"
    assert (result.get("error") or {}).get("code") != "VERIFICATION_FAILED"


def test_phone_home_execution_failure_stays_failed():
    service = DesktopAgentService(
        OneDeviceFleet(FailingHomeExecutionBridge({"ok": False, "status": "FAILED"}))
    )
    result = service.action(
        "dev_test",
        {
            "capability_id": "phone.home",
            "expected_observation_id": "obs-before",
            "goal": "Go home",
        },
    )
    assert result["execution"]["ok"] is False
    assert result["execution"]["androidExecution"]["ok"] is False
    assert result["ok"] is False
    assert result["verification"]["passed"] is False
    assert result["error"]["code"] == "EXECUTION_FAILED"
    assert result["verification"].get("basis") != "ALREADY_ON_HOME"
