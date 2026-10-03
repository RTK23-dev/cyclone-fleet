from __future__ import annotations

from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

from cyclone_device_gateway.actions.router import ActionValidationError
from cyclone_device_gateway.capabilities.models import (
    CAPABILITY_PROTOCOL_VERSION,
    CapabilityActionRequest,
    CapabilityHealthState,
    CapabilityObserveRequest,
    FailureLayer,
    GatewayErrorCode,
)
from cyclone_device_gateway.capabilities.registry import CapabilityRegistry
from cyclone_device_gateway.capabilities.service import CapabilityService
from cyclone_device_gateway.config import Settings
from cyclone_device_gateway.server import create_app


class Store:
    def __init__(self, observation_id: str = "obs-current"):
        self.observation_id = observation_id

    def current_observation(self):
        return {
            "id": "gateway-record",
            "page_key": "HOME",
            "package": "com.test",
            "semantic": {
                "observationId": self.observation_id,
                "pageKey": "HOME",
                "package": "com.test",
                "accessibilityFingerprint": "fingerprint-home",
            },
        }


class Bridge:
    def __init__(self, reachable: bool = True):
        self.reachable = reachable

    def request(self, op, args=None):
        if not self.reachable:
            raise OSError("private disconnect details")
        assert op == "bridge.status"
        return {
            "gatewayEnabled": True,
            "socketListening": True,
            "accessibilityConnected": True,
        }


class Router:
    def __init__(self, result=None, error: Exception | None = None):
        self.result = result or successful_result()
        self.error = error
        self.calls = []

    def execute(self, **kwargs):
        self.calls.append(kwargs)
        if self.error:
            raise self.error
        return self.result


def successful_result(**overrides):
    value = {
        "request_id": "correlation-test",
        "success": True,
        "transport_ok": True,
        "execution_ok": True,
        "verification_ok": True,
        "verification": "page_changed",
        "latency_ms": 12,
        "transition_id": "transition-1",
        "error_class": None,
        "before_witness": {
            "observation_id": "obs-before",
            "gateway_record_id": "record-before",
            "page_key": "HOME",
            "package": "com.test",
            "accessibility_fingerprint": "before-fingerprint",
        },
        "after_witness": {
            "observation_id": "obs-after",
            "gateway_record_id": "record-after",
            "page_key": "APPS",
            "package": "com.test",
            "accessibility_fingerprint": "after-fingerprint",
        },
    }
    value.update(overrides)
    return value


def request(**overrides):
    value = {
        "correlation_id": "correlation-test",
        "capability_id": "phone.click",
        "params": {"selector": {"resourceId": "id/apps"}},
        "expected_observation_id": "obs-current",
    }
    value.update(overrides)
    return CapabilityActionRequest(**value)


def test_discovery_is_typed_stable_and_android_authoritative():
    registry = CapabilityRegistry()
    first = registry.discover(Bridge())
    second = registry.discover(Bridge())

    assert first == second
    assert first.protocol_version == CAPABILITY_PROTOCOL_VERSION
    assert first.gateway_health.state == CapabilityHealthState.AVAILABLE
    ids = [item.capability_id for item in first.capabilities]
    assert ids == sorted(ids)
    assert "phone.click" in ids and "phone.observe" in ids
    click = next(item for item in first.capabilities if item.capability_id == "phone.click")
    assert click.safety.requires_android_policy is True
    assert click.safety.authoritative_executor == "CYCLONE_ANDROID_PHONE_TOOL_EXECUTOR"
    assert click.safety.generic_shell_allowed is False


def test_discovery_health_reports_device_disconnect_without_raw_exception():
    discovery = CapabilityRegistry().discover(Bridge(reachable=False))
    assert discovery.gateway_health.state == CapabilityHealthState.UNAVAILABLE
    assert discovery.gateway_health.reason_code == "DEVICE_DISCONNECTED"
    assert "private disconnect details" not in str(discovery)


def test_stale_observation_is_explicit_and_action_is_not_called():
    router = Router()
    result = CapabilityService(router, Store("obs-new")).execute(request())

    assert result.ok is False
    assert result.error.code == GatewayErrorCode.STALE_OBSERVATION
    assert result.error.retryable is True
    assert router.calls == []


def test_missing_required_observation_is_rejected_before_android_call():
    router = Router()
    result = CapabilityService(router, Store()).execute(
        request(expected_observation_id=None),
    )

    assert result.ok is False
    assert result.error.code == GatewayErrorCode.STALE_OBSERVATION
    assert result.error.layer == FailureLayer.PROTOCOL
    assert result.error.retryable is True
    assert router.calls == []


def test_schema_or_safety_validation_is_protocol_failure():
    router = Router(error=ActionValidationError("nested shell is forbidden"))
    result = CapabilityService(router, Store()).execute(request())

    assert result.error.code == GatewayErrorCode.PROTOCOL_MISMATCH
    assert result.error.layer == FailureLayer.PROTOCOL
    assert "nested shell" not in str(result)


@pytest.mark.parametrize(
    ("raw", "expected_code", "expected_layer"),
    [
        (
            successful_result(
                success=False,
                execution_ok=False,
                verification_ok=False,
                error_class="POLICY_DENIED",
            ),
            GatewayErrorCode.POLICY_DENIED,
            FailureLayer.POLICY,
        ),
        (
            successful_result(
                success=False,
                execution_ok=False,
                verification_ok=False,
                error_class="ELEMENT_NOT_FOUND",
            ),
            GatewayErrorCode.EXECUTION_FAILED,
            FailureLayer.EXECUTION,
        ),
        (
            successful_result(
                success=False,
                transport_ok=False,
                execution_ok=False,
                verification_ok=False,
                error_class="DEVICE_DISCONNECTED",
            ),
            GatewayErrorCode.DEVICE_DISCONNECTED,
            FailureLayer.TRANSPORT,
        ),
        (
            successful_result(
                verification_ok=False,
                verification="android_verification_failed",
                verification_error_class="TARGET_NOT_REACHED",
            ),
            GatewayErrorCode.VERIFICATION_FAILED,
            FailureLayer.VERIFICATION,
        ),
    ],
)
def test_failure_layers_are_distinct(raw, expected_code, expected_layer):
    result = CapabilityService(Router(raw), Store()).execute(request())
    assert result.ok is False
    assert result.error.code == expected_code
    assert result.error.layer == expected_layer


def test_success_carries_same_correlation_and_before_after_witnesses():
    router = Router()
    result = CapabilityService(router, Store()).execute(request())

    assert result.ok is True
    assert result.correlation_id == "correlation-test"
    assert router.calls[0]["request_id"] == "correlation-test"
    assert result.before.observation_id == "obs-before"
    assert result.after.observation_id == "obs-after"
    assert result.execution.authoritative is True
    assert result.verification.authoritative is True


def test_protocol_version_mismatch_fails_before_android_call():
    router = Router()
    result = CapabilityService(router, Store()).execute(
        request(protocol_version="future.protocol.v9"),
    )
    assert result.error.code == GatewayErrorCode.PROTOCOL_MISMATCH
    assert router.calls == []


def test_unknown_capability_cannot_expand_android_allowlist():
    router = Router()
    result = CapabilityService(router, Store()).execute(
        request(capability_id="desktop.shell"),
    )
    assert result.error.code == GatewayErrorCode.CAPABILITY_UNAVAILABLE
    assert router.calls == []


def test_typed_observation_returns_correlation_and_witness():
    store = Store()
    service = CapabilityService(Router(), store)

    class Retrieval:
        @staticmethod
        def get_page_context(mode, goal):
            return {"pageKey": "HOME", "mode": mode, "goal": goal}

    result = service.observe(
        CapabilityObserveRequest(correlation_id="observe-correlation", goal="Inspect"),
        lambda **kwargs: None,
        Retrieval(),
        lambda goal: {"knowledgeProvenance": "ANDROID_CANONICAL"},
    )
    assert result.ok is True
    assert result.correlation_id == "observe-correlation"
    assert result.witness.observation_id == "obs-current"
    assert result.observation["knowledgeProvenance"] == "ANDROID_CANONICAL"


def test_http_auth_loopback_and_non_200_execution_failure(tmp_path):
    configured = Settings(
        "http-secret",
        None,
        "adb",
        tmp_path,
        bridge_token="android-secret",
    )
    router = Router(
        successful_result(
            success=False,
            execution_ok=False,
            verification_ok=False,
            error_class="ELEMENT_NOT_FOUND",
        )
    )
    registry = CapabilityRegistry()
    gateway = SimpleNamespace(
        actions=router,
        bridge=Bridge(),
        capability_registry=registry,
        capabilities=CapabilityService(router, Store(), registry),
    )
    client = TestClient(create_app(configured, gateway))

    assert client.get("/v1/capabilities").status_code == 401
    headers = {"Authorization": "Bearer http-secret"}
    discovery = client.get("/v1/capabilities", headers=headers)
    assert discovery.status_code == 200
    typed = client.post(
        "/v1/capabilities/action",
        headers=headers,
        json=request().model_dump(mode="json"),
    )
    assert typed.status_code == 502
    assert typed.json()["error"]["code"] == "EXECUTION_FAILED"

    legacy = client.post(
        "/v1/action",
        headers=headers,
        json={
            "tool": "phone.click",
            "params": {"selector": {"resourceId": "id/apps"}},
            "request_id": "legacy-correlation",
        },
    )
    assert legacy.status_code == 502
    assert legacy.json()["execution_ok"] is False

    with pytest.raises(ValueError, match="loopback"):
        Settings("token", None, "adb", tmp_path, host="0.0.0.0", bridge_token="android")


def test_public_capability_surface_contains_no_shell_root_or_generic_command():
    discovery = CapabilityRegistry().discover(Bridge())
    ids = [descriptor.capability_id.lower() for descriptor in discovery.capabilities]
    assert all("shell" not in capability_id for capability_id in ids)
    assert all("root" not in capability_id for capability_id in ids)
    assert "desktop.command" not in ids
    assert all(
        descriptor.safety.generic_shell_allowed is False
        for descriptor in discovery.capabilities
    )
