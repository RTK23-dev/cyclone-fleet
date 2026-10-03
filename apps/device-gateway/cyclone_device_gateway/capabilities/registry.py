from __future__ import annotations

from collections.abc import Iterable

from ..actions.router import ALLOWED_TOOLS
from ..cyclone_bridge.client import BridgeBusyError, BridgeOperationError
from .human_gesture import discovery_from_bridge_status
from .models import (
    CapabilityDescriptor,
    CapabilityDiscoveryResponse,
    CapabilityHealth,
    CapabilityHealthState,
    CapabilityKind,
    HumanGestureDiscovery,
    SafetyMetadata,
)


OBSERVATION_CAPABILITIES = {"workspace.list", "phone.observe", "phone.find", "phone.wait_for"}
SENSITIVE_PARAMETERS = {"phone.type": ("text", "value")}


class CapabilityRegistry:
    """Read-only descriptors for operations already owned by Android.

    This registry advertises the existing allowlist. It cannot add operations to the Android
    bridge and is not an authority or executor.
    """

    def __init__(self, capability_ids: Iterable[str] = ALLOWED_TOOLS):
        ids = tuple(sorted(set(capability_ids)))
        unknown = set(ids) - ALLOWED_TOOLS
        if unknown:
            raise ValueError(f"Registry cannot invent Android capabilities: {sorted(unknown)}")
        self._capability_ids = ids

    def descriptor(
        self,
        capability_id: str,
        health: CapabilityHealth,
    ) -> CapabilityDescriptor | None:
        if capability_id not in self._capability_ids:
            return None
        mutates = capability_id not in OBSERVATION_CAPABILITIES
        return CapabilityDescriptor(
            capability_id=capability_id,
            version="1.0.0",
            kind=CapabilityKind.ACTION if mutates else CapabilityKind.OBSERVATION,
            request_schema="cyclone.gateway.action.request.v1",
            response_schema="cyclone.gateway.action.response.v1",
            safety=SafetyMetadata(
                mutates_phone=mutates,
                requires_fresh_observation=mutates,
                requires_android_policy=mutates,
                sensitive_parameter_names=SENSITIVE_PARAMETERS.get(capability_id, ()),
            ),
            health=health,
        )

    def discover(self, bridge) -> CapabilityDiscoveryResponse:
        # One phone status read drives both transport health and Human Gesture truth. Do not infer
        # runtime support from PC schema availability or perform a second capability authority read.
        status = self._bridge_status(bridge)
        health = self._bridge_health_from_status(status)
        answered = status if isinstance(status, dict) and _FAILURE not in status else None
        gesture = HumanGestureDiscovery.model_validate(discovery_from_bridge_status(answered))
        return CapabilityDiscoveryResponse(
            gateway_health=health,
            capabilities=tuple(
                self.descriptor(capability_id, health)
                for capability_id in self._capability_ids
            ),
            human_gesture=gesture,
        )

    @staticmethod
    def _bridge_status(bridge):
        # Say why the phone didn't answer (alpha 88): a busy app or a rejected session is not a lost phone.
        try:
            status = bridge.request("bridge.status", {})
        except BridgeBusyError:
            return {_FAILURE: "PHONE_APP_BUSY"}
        except BridgeOperationError as exc:
            return {_FAILURE: "TOKEN_SESSION_MISMATCH" if exc.code in _AUTH_CODES else exc.code}
        except Exception:
            return None
        return status if isinstance(status, dict) else None

    @staticmethod
    def _bridge_health(bridge) -> CapabilityHealth:
        """Compatibility helper retained for existing callers/tests."""
        return CapabilityRegistry._bridge_health_from_status(CapabilityRegistry._bridge_status(bridge))

    @staticmethod
    def _bridge_health_from_status(status) -> CapabilityHealth:
        if status is None:
            return CapabilityHealth(
                state=CapabilityHealthState.UNAVAILABLE,
                reason_code="DEVICE_DISCONNECTED",
            )
        if not isinstance(status, dict):
            return CapabilityHealth(
                state=CapabilityHealthState.UNAVAILABLE,
                reason_code="PROTOCOL_MISMATCH",
            )
        if _FAILURE in status:
            reason = str(status[_FAILURE])
            return CapabilityHealth(
                state=CapabilityHealthState.DEGRADED if reason == "PHONE_APP_BUSY" else CapabilityHealthState.UNAVAILABLE,
                reason_code=reason,
            )
        readiness_fields = ("gatewayEnabled", "socketListening", "accessibilityConnected")
        if any(not isinstance(status.get(field), bool) for field in readiness_fields):
            return CapabilityHealth(
                state=CapabilityHealthState.UNAVAILABLE,
                reason_code="PROTOCOL_MISMATCH",
            )
        ready = bool(
            status["gatewayEnabled"]
            and status["socketListening"]
            and status["accessibilityConnected"]
        )
        return CapabilityHealth(
            state=(
                CapabilityHealthState.AVAILABLE
                if ready
                else CapabilityHealthState.DEGRADED
            ),
            reason_code=None if ready else "ANDROID_NOT_READY",
        )


_FAILURE = "__bridgeFailure"
_AUTH_CODES = {"AUTH_REJECTED", "TRUST_EXPIRED", "TRUST_REVOKED", "AUTH_SIGNATURE_INVALID"}
