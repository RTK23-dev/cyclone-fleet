from __future__ import annotations

import secrets
from typing import Any, Callable

from .base import DeviceBackendCapabilities, DeviceBackendStatus
from ..desktop_runtime.agent import ALLOWED_PHONE_TOOLS, DesktopAgentService
from ..desktop_runtime.fleet import DeviceFleetManager
from ..desktop_runtime.models import DesktopRuntimeError, RuntimeErrorCode


class DesktopAndroidBackend:
    """Adapts a physical or virtual Android session without bypassing PhoneToolExecutor."""

    def __init__(
        self,
        fleet: DeviceFleetManager,
        agent: DesktopAgentService,
        device_id: str,
        *,
        snapshot: Callable[[str, str], dict[str, Any]] | None = None,
    ):
        self.fleet = fleet
        self.agent = agent
        self.device_id = device_id
        self._snapshot = snapshot

    def identify(self) -> dict[str, Any]:
        return self.fleet.get(self.device_id).public()

    def status(self) -> DeviceBackendStatus:
        session = self.fleet.get(self.device_id)
        return DeviceBackendStatus(
            device_id=session.device_id,
            state=session.state.value,
            source=session.source,
            provider=session.provider,
            last_seen_ms=session.last_seen_ms,
            diagnostic_reason=session.last_safe_error,
        )

    def capabilities(self) -> DeviceBackendCapabilities:
        session = self.fleet.get(self.device_id)
        paired = session.credential is not None
        return DeviceBackendCapabilities(
            observe=paired,
            search=paired,
            semantic_actions=tuple(sorted(ALLOWED_PHONE_TOOLS)) if paired else (),
            screenshot=session.video is not None,
            stream_profiles=("thumbnail", "focus") if session.video is not None else (),
            recover=True,
        )

    def observe(self, *, mode: str = "compact") -> dict[str, Any]:
        return self.agent.observe(self.device_id, mode=mode)

    def search(self, query: str) -> dict[str, Any]:
        return self.agent.ui_search(self.device_id, query)

    def act(self, capability_id: str, params: dict[str, Any], *, goal: str = "") -> dict[str, Any]:
        # A current observation witness is mandatory for every mutation.
        observed = self.agent.observe(self.device_id, mode="compact")
        witness = observed.get("witness") or {}
        observation_id = str(witness.get("observation_id") or "")
        payload_params = dict(params)
        request_ai = bool(payload_params.pop("request_ai_control", False))
        return self.agent.action(self.device_id, {
            "capability_id": capability_id,
            "params": payload_params,
            "goal": goal,
            "expected_observation_id": observation_id,
            "request_ai_control": request_ai,
        })

    def screenshot(self, *, profile: str = "thumbnail") -> dict[str, Any]:
        # Share the same device-scoped bounded artifact contract as the public agent endpoint.
        return self.agent.screenshot(self.device_id, profile=profile)

    def stream(self, *, profile: str = "thumbnail") -> Any:
        if profile not in {"thumbnail", "focus"}:
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "Unknown stream profile.")
        session = self.fleet.get(self.device_id)
        if session.video is None:
            raise DesktopRuntimeError(RuntimeErrorCode.CAPABILITY_UNAVAILABLE, "Video is unavailable.")
        return session.video.subscribe(profile)

    def app_state(self) -> dict[str, Any]:
        page = self.agent.current_page(self.device_id)
        return {"deviceId": self.device_id, "page": page}

    def diagnostics(self) -> dict[str, Any]:
        session = self.fleet.get(self.device_id)
        video = session.video.diagnostics() if session.video is not None and hasattr(session.video, "diagnostics") else {}
        return {"device": session.public(), "video": video, "fleet": self.fleet.diagnostics()}

    def recover(self) -> dict[str, Any]:
        self.fleet.refresh_once(source="backend-recover")
        session = self.fleet.get(self.device_id)
        try:
            session.adb.ensure_bridge_forward(session.local_port)
        except Exception as exc:
            raise DesktopRuntimeError(RuntimeErrorCode.DEVICE_DISCONNECTED, "Device recovery failed.", retryable=True) from exc
        return {"deviceId": self.device_id, "state": session.state.value, "requested": True, "requestId": secrets.token_urlsafe(12)}

    def close(self) -> None:
        # Fleet owns the shared ADB and video lifecycle; a backend view has no independent process.
        return None
