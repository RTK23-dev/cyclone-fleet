from __future__ import annotations

from ..actions.router import ActionValidationError
from ..execution_scope import attach_execution_identity, parse_execution_identity
from .human_gesture import safe_gesture_diagnostics
from .models import (
    CAPABILITY_PROTOCOL_VERSION,
    CapabilityActionRequest,
    CapabilityActionResponse,
    CapabilityHealth,
    CapabilityHealthState,
    CapabilityObservationResponse,
    CapabilityObserveRequest,
    FailureLayer,
    GatewayError,
    GatewayErrorCode,
    LayerOutcome,
    SafetyMetadata,
    Witness,
)
from .registry import CapabilityRegistry


ERROR_HTTP_STATUS = {
    GatewayErrorCode.CAPABILITY_UNAVAILABLE: 503,
    GatewayErrorCode.STALE_OBSERVATION: 409,
    GatewayErrorCode.POLICY_DENIED: 403,
    GatewayErrorCode.EXECUTION_FAILED: 502,
    GatewayErrorCode.VERIFICATION_FAILED: 409,
    GatewayErrorCode.DEVICE_DISCONNECTED: 503,
    GatewayErrorCode.PROTOCOL_MISMATCH: 409,
    GatewayErrorCode.AUTH_REJECTED: 401,
}


class CapabilityService:
    def __init__(self, action_router, store, registry: CapabilityRegistry | None = None):
        self.action_router = action_router
        self.store = store
        self.registry = registry or CapabilityRegistry()

    def observe(
        self,
        request: CapabilityObserveRequest,
        observe,
        retrieval,
        knowledge_context,
    ) -> CapabilityObservationResponse:
        if request.protocol_version != CAPABILITY_PROTOCOL_VERSION:
            error = _error(
                GatewayErrorCode.PROTOCOL_MISMATCH,
                FailureLayer.PROTOCOL,
                "Capability protocol version is not supported.",
            )
            return CapabilityObservationResponse(
                correlation_id=request.correlation_id,
                ok=False,
                transport=LayerOutcome(ok=True, status="not_attempted"),
                error=error,
            )
        try:
            try:
                identity = parse_execution_identity(request.model_dump(exclude_none=True))
            except ValueError as exc:
                error = _error(
                    GatewayErrorCode.PROTOCOL_MISMATCH,
                    FailureLayer.PROTOCOL,
                    str(exc)[:240],
                )
                return CapabilityObservationResponse(
                    correlation_id=request.correlation_id,
                    ok=False,
                    transport=LayerOutcome(ok=True, status="not_attempted"),
                    error=error,
                )
            observe(
                screenshot=request.include_screenshot,
                uiautomator=True,
                diagnostics=request.mode == "full",
                execution_scope=identity,
            )
            observation = retrieval.get_page_context(request.mode, request.goal)
            if observation is None:
                raise RuntimeError("observation unavailable")
            observation.update(knowledge_context(request.goal))
            witness = _parse_observation_witness(self.store.current_observation())
            return CapabilityObservationResponse(
                correlation_id=request.correlation_id,
                ok=True,
                transport=LayerOutcome(ok=True, status="connected"),
                witness=witness,
                observation=observation,
            )
        except Exception as exc:
            code = getattr(exc, "code", None)
            if code == GatewayErrorCode.AUTH_REJECTED or code == "AUTH_REJECTED":
                error = _error(
                    GatewayErrorCode.AUTH_REJECTED,
                    FailureLayer.PROTOCOL,
                    "Android bridge authentication was rejected.",
                )
            elif code == GatewayErrorCode.PROTOCOL_MISMATCH or code == "PROTOCOL_MISMATCH":
                error = _error(
                    GatewayErrorCode.PROTOCOL_MISMATCH,
                    FailureLayer.PROTOCOL,
                    "Android bridge protocol is incompatible.",
                )
            elif code == GatewayErrorCode.CAPABILITY_UNAVAILABLE or code == "CAPABILITY_UNAVAILABLE":
                error = _error(
                    GatewayErrorCode.CAPABILITY_UNAVAILABLE,
                    FailureLayer.CAPABILITY,
                    "Required Android observation capability is unavailable.",
                    retryable=True,
                )
            else:
                error = _error(
                    GatewayErrorCode.DEVICE_DISCONNECTED,
                    FailureLayer.TRANSPORT,
                    "Android device observation transport is unavailable.",
                    retryable=True,
                )
            return CapabilityObservationResponse(
                correlation_id=request.correlation_id,
                ok=False,
                transport=LayerOutcome(
                    ok=error.code not in {GatewayErrorCode.DEVICE_DISCONNECTED},
                    status="disconnected" if error.code == GatewayErrorCode.DEVICE_DISCONNECTED else "connected",
                    error=error if error.layer == FailureLayer.TRANSPORT else None,
                ),
                error=error,
            )

    def execute(self, request: CapabilityActionRequest) -> CapabilityActionResponse:
        available = CapabilityHealth(state=CapabilityHealthState.AVAILABLE)
        descriptor = self.registry.descriptor(request.capability_id, available)
        safety = descriptor.safety if descriptor else SafetyMetadata(
            mutates_phone=False,
            requires_fresh_observation=False,
            requires_android_policy=False,
        )

        if request.protocol_version != CAPABILITY_PROTOCOL_VERSION:
            return self._rejected(
                request, safety, GatewayErrorCode.PROTOCOL_MISMATCH,
                FailureLayer.PROTOCOL, "Capability protocol version is not supported.",
            )
        if descriptor is None:
            return self._rejected(
                request, safety, GatewayErrorCode.CAPABILITY_UNAVAILABLE,
                FailureLayer.CAPABILITY, "Capability is not declared by the Android allowlist.",
            )
        if safety.requires_fresh_observation and request.expected_observation_id is None:
            return self._rejected(
                request, safety, GatewayErrorCode.STALE_OBSERVATION,
                FailureLayer.PROTOCOL, "A fresh observation witness is required before this action.",
                retryable=True,
            )
        if request.expected_observation_id is not None:
            current = self.store.current_observation()
            current_id = _observation_id(current)
            if current_id != request.expected_observation_id:
                return self._rejected(
                    request, safety, GatewayErrorCode.STALE_OBSERVATION,
                    FailureLayer.PROTOCOL, "Expected observation is no longer current.",
                    retryable=True,
                )

        try:
            identity = parse_execution_identity(request.model_dump(exclude_none=True))
        except ValueError as exc:
            return self._rejected(
                request, safety, GatewayErrorCode.PROTOCOL_MISMATCH,
                FailureLayer.PROTOCOL, str(exc)[:240],
            )
        try:
            params = attach_execution_identity(request.params, identity)
            raw = self.action_router.execute(
                tool=request.capability_id,
                params=params,
                goal=request.goal,
                source=request.source,
                request_id=request.correlation_id,
            )
        except ActionValidationError as exc:
            if str(getattr(exc, "code", "") or "").upper() == GatewayErrorCode.STALE_OBSERVATION:
                return self._rejected(
                    request, safety, GatewayErrorCode.STALE_OBSERVATION,
                    FailureLayer.PROTOCOL, "Expected observation is no longer current.",
                    retryable=True,
                )
            return self._rejected(
                request, safety, GatewayErrorCode.PROTOCOL_MISMATCH,
                FailureLayer.PROTOCOL, "Action request failed schema or safety validation.",
            )
        except Exception as exc:
            code = getattr(exc, "code", None)
            if code == "AUTH_REJECTED":
                return self._rejected(
                    request, safety, GatewayErrorCode.AUTH_REJECTED,
                    FailureLayer.PROTOCOL, "Android bridge authentication was rejected.",
                )
            if code == "CAPABILITY_UNAVAILABLE":
                return self._rejected(
                    request, safety, GatewayErrorCode.CAPABILITY_UNAVAILABLE,
                    FailureLayer.CAPABILITY, "Android action capability is unavailable.",
                    retryable=True,
                )
            return self._rejected(
                request, safety, GatewayErrorCode.DEVICE_DISCONNECTED,
                FailureLayer.TRANSPORT, "Android device transport is unavailable.",
                retryable=True,
            )

        transport_ok = raw.get("transport_ok") is True
        execution_ok = raw.get("execution_ok") is True
        verification_required = safety.mutates_phone
        verification_ok = raw.get("verification_ok") is True or not verification_required

        error = self._map_error(raw, transport_ok, execution_ok, verification_ok)
        transport_error = error if error and error.layer == FailureLayer.TRANSPORT else None
        execution_error = error if error and error.layer in {
            FailureLayer.CAPABILITY,
            FailureLayer.POLICY,
            FailureLayer.EXECUTION,
            FailureLayer.PROTOCOL,
        } else None
        verification_error = error if error and error.layer == FailureLayer.VERIFICATION else None
        return CapabilityActionResponse(
            correlation_id=request.correlation_id,
            capability_id=request.capability_id,
            ok=transport_ok and execution_ok and verification_ok,
            transport=LayerOutcome(
                ok=transport_ok,
                status="connected" if transport_ok else "disconnected",
                error=transport_error,
            ),
            execution=LayerOutcome(
                ok=execution_ok,
                authoritative=True,
                status="android_succeeded" if execution_ok else "android_failed",
                error=execution_error,
            ),
            verification=LayerOutcome(
                ok=verification_ok,
                authoritative=True,
                status=(
                    "not_required"
                    if not verification_required
                    else str(raw.get("verification") or "missing")
                ),
                error=verification_error,
            ),
            before=_parse_witness(raw.get("before_witness")),
            after=_parse_witness(raw.get("after_witness")),
            android_execution=_safe_android_execution(raw),
            safety=safety,
            latency_ms=max(0, int(raw.get("latency_ms") or 0)),
            transition_id=raw.get("transition_id"),
            error=error,
        )

    def _map_error(
        self,
        raw: dict,
        transport_ok: bool,
        execution_ok: bool,
        verification_ok: bool,
    ) -> GatewayError | None:
        if not transport_ok:
            return _error(
                GatewayErrorCode.DEVICE_DISCONNECTED,
                FailureLayer.TRANSPORT,
                "Android device transport is unavailable.",
                retryable=True,
            )
        if not execution_ok:
            error_class = str(raw.get("error_class") or "").upper()
            if error_class == GatewayErrorCode.AUTH_REJECTED:
                return _error(
                    GatewayErrorCode.AUTH_REJECTED,
                    FailureLayer.PROTOCOL,
                    "Android bridge authentication was rejected.",
                )
            if error_class == GatewayErrorCode.CAPABILITY_UNAVAILABLE:
                return _error(
                    GatewayErrorCode.CAPABILITY_UNAVAILABLE,
                    FailureLayer.CAPABILITY,
                    "Android action capability is unavailable.",
                    retryable=True,
                )
            if error_class == GatewayErrorCode.POLICY_DENIED:
                return _error(
                    GatewayErrorCode.POLICY_DENIED,
                    FailureLayer.POLICY,
                    "Android policy denied the action.",
                )
            if error_class == GatewayErrorCode.STALE_OBSERVATION:
                return _error(
                    GatewayErrorCode.STALE_OBSERVATION,
                    FailureLayer.PROTOCOL,
                    "Android rejected stale observation evidence.",
                    retryable=True,
                )
            if error_class == GatewayErrorCode.PROTOCOL_MISMATCH:
                return _error(
                    GatewayErrorCode.PROTOCOL_MISMATCH,
                    FailureLayer.PROTOCOL,
                    "Android execution result did not match the capability protocol.",
                )
            return _error(
                GatewayErrorCode.EXECUTION_FAILED,
                FailureLayer.EXECUTION,
                "Android PhoneToolExecutor reported execution failure.",
            )
        if not verification_ok:
            return _error(
                GatewayErrorCode.VERIFICATION_FAILED,
                FailureLayer.VERIFICATION,
                "The authoritative after-state did not verify the action.",
                retryable=True,
            )
        return None

    @staticmethod
    def _rejected(
        request: CapabilityActionRequest,
        safety: SafetyMetadata,
        code: GatewayErrorCode,
        layer: FailureLayer,
        message: str,
        retryable: bool = False,
    ) -> CapabilityActionResponse:
        error = _error(code, layer, message, retryable)
        return CapabilityActionResponse(
            correlation_id=request.correlation_id,
            capability_id=request.capability_id,
            ok=False,
            transport=LayerOutcome(
                ok=layer != FailureLayer.TRANSPORT,
                status="not_attempted" if layer != FailureLayer.TRANSPORT else "disconnected",
                error=error if layer == FailureLayer.TRANSPORT else None,
            ),
            execution=LayerOutcome(
                ok=False,
                authoritative=True,
                status="not_attempted",
                error=error if layer in {
                    FailureLayer.CAPABILITY,
                    FailureLayer.POLICY,
                    FailureLayer.EXECUTION,
                    FailureLayer.PROTOCOL,
                } else None,
            ),
            verification=LayerOutcome(
                ok=False,
                authoritative=True,
                status="not_attempted",
                error=error if layer == FailureLayer.VERIFICATION else None,
            ),
            safety=safety,
            latency_ms=0,
            error=error,
        )


def _observation_id(observation: dict | None) -> str | None:
    if not observation:
        return None
    semantic = observation.get("semantic")
    if isinstance(semantic, dict):
        value = semantic.get("observationId") or semantic.get("observation_id")
        if value:
            return str(value)
    value = observation.get("id")
    return str(value) if value else None


def _parse_witness(value) -> Witness | None:
    if not isinstance(value, dict):
        return None
    try:
        return Witness.model_validate(value)
    except Exception:
        return None


def _parse_observation_witness(observation: dict | None) -> Witness | None:
    if not observation:
        return None
    semantic = observation.get("semantic")
    semantic = semantic if isinstance(semantic, dict) else {}
    value = {
        "observation_id": str(
            semantic.get("observationId")
            or semantic.get("observation_id")
            or observation.get("id")
        ),
        "gateway_record_id": str(observation.get("id")),
        "page_key": semantic.get("pageKey") or observation.get("page_key"),
        "package": semantic.get("package") or observation.get("package"),
        "accessibility_fingerprint": (
            semantic.get("accessibilityFingerprint") or semantic.get("fingerprint")
        ),
    }
    return _parse_witness(value)


def _safe_android_execution(raw: dict) -> dict | None:
    """Project Android authority plus a strictly bounded Human Gesture diagnostic block."""
    result = raw.get("result")
    if not isinstance(result, dict):
        return None
    execution = result.get("execution")
    if not isinstance(execution, dict):
        execution = result.get("androidExecution")
    if not isinstance(execution, dict):
        execution = result.get("android_execution")
    if not isinstance(execution, dict):
        return None
    allowed = ("ok", "beforeFingerprint", "afterFingerprint", "error", "verification", "pageChanged")
    projected = {key: execution[key] for key in allowed if key in execution}
    gesture = safe_gesture_diagnostics(execution)
    if gesture is not None:
        projected["gesture"] = gesture
    return projected or None


def _error(
    code: GatewayErrorCode,
    layer: FailureLayer,
    message: str,
    retryable: bool = False,
) -> GatewayError:
    return GatewayError(code=code, layer=layer, message=message, retryable=retryable)
