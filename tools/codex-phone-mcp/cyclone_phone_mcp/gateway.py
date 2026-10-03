from __future__ import annotations

import json
import os
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import dataclass
from typing import Any

from .session import attach_execution_scope, parse_execution_scope

DEFAULT_BASE_URL = "http://127.0.0.1:8765"
CAPABILITY_PROTOCOL_VERSION = "cyclone.gateway.capability.v1"
NON_MUTATING_CAPABILITIES = {"workspace.list", "phone.observe", "phone.find", "phone.wait_for"}


class GatewayError(RuntimeError):
    def __init__(self, message: str, *, status: int | None = None, body: Any = None):
        super().__init__(message)
        self.status = status
        self.body = body


@dataclass(frozen=True)
class GatewayResponse:
    status: int
    body: Any


class GatewayClient:
    """Authenticated V3 capability client for Cyclone's loopback-only PC gateway."""

    def __init__(self, base_url: str | None = None, token: str | None = None, timeout: float = 30.0):
        provided_token = token is not None
        provided_url = base_url is not None
        resolved_token = token if provided_token else os.getenv("CYCLONE_DEVICE_GATEWAY_TOKEN", "")
        resolved_url = base_url if provided_url else os.getenv("CYCLONE_DEVICE_GATEWAY_URL")
        if (not provided_token and not str(resolved_token or "").strip()) or (
            not provided_url and not str(resolved_url or "").strip()
        ):
            try:
                from .tooling import apply_gateway_env

                apply_gateway_env()
            except Exception:
                pass
            if not provided_token and not str(resolved_token or "").strip():
                resolved_token = os.getenv("CYCLONE_DEVICE_GATEWAY_TOKEN", "")
            if not provided_url and not str(resolved_url or "").strip():
                resolved_url = os.getenv("CYCLONE_DEVICE_GATEWAY_URL")
        self.base_url = (resolved_url or DEFAULT_BASE_URL).rstrip("/")
        self.token = str(resolved_token or "")
        self.timeout = timeout
        parsed = urllib.parse.urlparse(self.base_url)
        if parsed.scheme != "http" or parsed.hostname not in {"127.0.0.1", "localhost", "::1"}:
            raise GatewayError("Gateway URL must use loopback HTTP")
        self._observation_ids: dict[str, str] = {}
        self._capability_ids: frozenset[str] | None = None
        self._capability_discovery: dict[str, Any] | None = None
        self._device_capabilities: dict[str, dict[str, Any]] = {}
        self._device_capability_ids: dict[str, frozenset[str]] = {}

    def _request(self, method: str, path: str, payload: Any | None = None) -> Any:
        if not self.token:
            raise GatewayError(
                "Gateway bearer is not persisted. Start Cyclone One so the local MCP can attach.",
                body={"error": {"code": "AUTH_REJECTED", "layer": "PROTOCOL"}},
            )
        url = f"{self.base_url}{path}"
        data = None
        headers = {"Authorization": f"Bearer {self.token}", "Accept": "application/json"}
        if payload is not None:
            data = json.dumps(payload).encode("utf-8")
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(url, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                raw = response.read()
                if not raw:
                    return {}
                return json.loads(raw.decode("utf-8"))
        except urllib.error.HTTPError as exc:
            raw_body = exc.read().decode("utf-8", errors="replace")
            try:
                body: Any = json.loads(raw_body) if raw_body else {}
            except json.JSONDecodeError:
                body = {"error": {"code": "INVALID_GATEWAY_ERROR", "message": raw_body[:500]}}
            if isinstance(body, dict) and isinstance(body.get("detail"), dict):
                # FastAPI error envelope used by the Desktop fleet router (e.g. PAIRING_REQUIRED).
                detail = body["detail"]
                body = {
                    "error": {
                        "code": str(detail.get("code") or "GATEWAY_ERROR"),
                        "layer": "GATEWAY",
                        "message": str(detail.get("message") or raw_body[:500]),
                        "retryable": bool(detail.get("retryable", False)),
                    }
                }
            if exc.code in {401, 403} and not (isinstance(body, dict) and isinstance(body.get("error"), dict)):
                body = {
                    "error": {
                        "code": "AUTH_REJECTED",
                        "layer": "PROTOCOL",
                        "message": "PC Gateway authentication rejected.",
                        "retryable": False,
                    }
                }
            raise GatewayError(f"Gateway HTTP {exc.code} for {path}", status=exc.code, body=body) from exc
        except (urllib.error.URLError, TimeoutError) as exc:
            raise GatewayError(
                f"Gateway unavailable at {self.base_url}",
                body={"error": {"code": "DEVICE_DISCONNECTED", "layer": "TRANSPORT", "retryable": True}},
            ) from exc
        except json.JSONDecodeError as exc:
            raise GatewayError(
                f"Gateway returned invalid JSON for {path}",
                body={"error": {"code": "PROTOCOL_MISMATCH", "layer": "PROTOCOL"}},
            ) from exc

    def status(self) -> Any:
        return self._request("GET", "/v1/device/status")

    def devices(self, *, scan: bool = False) -> dict[str, Any]:
        """Auto-detect connected phones through the gateway fleet surface.

        Prefers the Desktop fleet endpoints (``/v1/devices``, ``/v1/fleet``). If the running
        gateway only exposes the legacy single-device surface, the tool degrades to one row built
        from ``/v1/device/status`` so Codex still has a stable device to work with.
        """
        if scan:
            self._request("POST", "/v1/fleet/scan")
        devices, surface = self._fleet_devices()
        legacy: dict[str, Any] = {}
        try:
            legacy = self.status()
        except GatewayError:
            pass
        selected = _legacy_serial_suffix(legacy)
        if devices is not None:
            return {
                "protocol_version": CAPABILITY_PROTOCOL_VERSION,
                "surface": surface,
                "devices": devices,
                "selectedSerialSuffix": selected,
                "legacy": {
                    "available": bool(legacy),
                    "bridgeReachable": bool(legacy.get("cyclone_bridge_reachable")),
                    "selectedSerialSuffix": selected,
                },
            }
        row = _legacy_device_row(legacy)
        return {
            "protocol_version": CAPABILITY_PROTOCOL_VERSION,
            "surface": "legacy",
            "devices": [row] if row else [],
            "selectedSerialSuffix": selected,
            "legacy": {
                "available": bool(legacy),
                "bridgeReachable": bool(legacy.get("cyclone_bridge_reachable")),
                "selectedSerialSuffix": selected,
            },
        }

    def _fleet_devices(self) -> tuple[list[dict[str, Any]] | None, str]:
        for path in ("/v1/devices", "/v1/fleet"):
            try:
                response = self._request("GET", path)
                devices = response.get("devices") if isinstance(response, dict) else None
                if isinstance(devices, list):
                    return devices, "fleet"
            except GatewayError as exc:
                if exc.status not in {404, 405}:
                    raise
        return None, "legacy"

    def capabilities(self, *, refresh: bool = False) -> dict[str, Any]:
        if self._capability_discovery is not None and not refresh:
            return self._capability_discovery
        response = self._request("GET", "/v1/capabilities")
        if not isinstance(response, dict) or response.get("protocol_version") != CAPABILITY_PROTOCOL_VERSION:
            raise GatewayError(
                "PC Gateway capability protocol mismatch",
                body={"error": {"code": "PROTOCOL_MISMATCH", "layer": "PROTOCOL"}},
            )
        descriptors = response.get("capabilities")
        if not isinstance(descriptors, list):
            raise GatewayError(
                "PC Gateway capability discovery is malformed",
                body={"error": {"code": "PROTOCOL_MISMATCH", "layer": "PROTOCOL"}},
            )
        ids = []
        for descriptor in descriptors:
            if not isinstance(descriptor, dict) or not isinstance(descriptor.get("capability_id"), str):
                raise GatewayError(
                    "PC Gateway capability descriptor is malformed",
                    body={"error": {"code": "PROTOCOL_MISMATCH", "layer": "PROTOCOL"}},
                )
            ids.append(descriptor["capability_id"])
        self._capability_ids = frozenset(ids)
        self._capability_discovery = response
        return response

    def observe(
        self,
        *,
        include_screenshot: bool = False,
        mode: str = "compact",
        session_id: str | None = None,
        sessionId: str | None = None,
        display_id: int | None = None,
        displayId: int | None = None,
    ) -> Any:
        identity = _execution_identity(session_id=session_id, sessionId=sessionId, display_id=display_id, displayId=displayId)
        payload = {
            "protocol_version": CAPABILITY_PROTOCOL_VERSION,
            "correlation_id": str(uuid.uuid4()),
            "include_screenshot": include_screenshot,
            "mode": mode,
            **identity,
        }
        response = self._request("POST", "/v1/capabilities/observe", payload)
        witness = response.get("witness") if isinstance(response, dict) else None
        if isinstance(witness, dict) and isinstance(witness.get("observation_id"), str):
            self._observation_ids[_observation_key(None, identity)] = witness["observation_id"]
        return response

    def ui_search(
        self,
        query: str,
        *,
        session_id: str | None = None,
        sessionId: str | None = None,
        display_id: int | None = None,
        displayId: int | None = None,
    ) -> Any:
        params: dict[str, Any] = {"q": query}
        params.update(_execution_identity(session_id=session_id, sessionId=sessionId, display_id=display_id, displayId=displayId))
        encoded = urllib.parse.urlencode(params)
        return self._request("GET", f"/v1/ui/search?{encoded}")

    def ui_element(
        self,
        element_id: str,
        *,
        session_id: str | None = None,
        sessionId: str | None = None,
        display_id: int | None = None,
        displayId: int | None = None,
    ) -> Any:
        identity = _execution_identity(session_id=session_id, sessionId=sessionId, display_id=display_id, displayId=displayId)
        path = f"/v1/ui/element/{urllib.parse.quote(element_id, safe='')}"
        if identity:
            path = f"{path}?{urllib.parse.urlencode(identity)}"
        return self._request("GET", path)

    def current_page(self) -> Any:
        return self._request("GET", "/v1/page/current")

    def page_history(self) -> Any:
        return self._request("GET", "/v1/page/history")

    def action(
        self,
        tool: str,
        params: dict[str, Any],
        goal: str,
        *,
        session_id: str | None = None,
        sessionId: str | None = None,
        display_id: int | None = None,
        displayId: int | None = None,
    ) -> Any:
        discovery = self.capabilities()
        if self._capability_ids is None or tool not in self._capability_ids:
            raise GatewayError(
                f"Capability {tool} is not advertised by the connected Cyclone gateway",
                body={
                    "protocol_version": CAPABILITY_PROTOCOL_VERSION,
                    "capability_id": tool,
                    "ok": False,
                    "error": {"code": "CAPABILITY_UNAVAILABLE", "layer": "CAPABILITY", "retryable": False},
                },
            )
        gateway_health = discovery.get("gateway_health") if isinstance(discovery, dict) else None
        if isinstance(gateway_health, dict) and gateway_health.get("state") == "UNAVAILABLE":
            raise GatewayError(
                "Connected Cyclone gateway reports unavailable capabilities",
                body={"error": {"code": "CAPABILITY_UNAVAILABLE", "layer": "CAPABILITY", "retryable": True}},
            )
        identity = _execution_identity(session_id=session_id, sessionId=sessionId, display_id=display_id, displayId=displayId)
        observation_id = self._observation_ids.get(_observation_key(None, identity))
        if tool not in NON_MUTATING_CAPABILITIES and observation_id is None:
            raise GatewayError(
                "A fresh phone observation is required before a mutating action",
                body={
                    "protocol_version": CAPABILITY_PROTOCOL_VERSION,
                    "capability_id": tool,
                    "ok": False,
                    "error": {
                        "code": "STALE_OBSERVATION",
                        "layer": "PROTOCOL",
                        "retryable": True,
                    },
                },
            )
        forwarded, request_ai_control = _split_ai_control(params)
        payload = {
            "protocol_version": CAPABILITY_PROTOCOL_VERSION,
            "correlation_id": str(uuid.uuid4()),
            "capability_id": tool,
            "params": attach_execution_scope(forwarded, identity or None),
            "goal": goal,
            "source": "PC_CODEX",
            **identity,
        }
        if request_ai_control:
            payload["request_ai_control"] = True
        if observation_id is not None:
            payload["expected_observation_id"] = observation_id
        response = self._request("POST", "/v1/capabilities/action", payload)
        if tool not in NON_MUTATING_CAPABILITIES:
            # Element IDs and action evidence are observation-scoped. Force a re-observe after a
            # mutation instead of letting Codex accidentally reuse stale authority.
            self._observation_ids.pop(_observation_key(None, identity), None)
        return response

    def debug_bundle(self, expected: str | None = None, goal: str | None = None) -> Any:
        return self._request("POST", "/v1/debug/bundle", {"expected": expected or "", "goal": goal or ""})

    def teach_start(self, goal: str = "") -> Any:
        return self._request("POST", "/v1/teach/start", {"goal": goal, "source": "PC_CODEX"})

    def teach_status(self) -> Any:
        return self._request("GET", "/v1/teach/status")

    def teach_stop(self, compile_for_review: bool = True) -> Any:
        return self._request("POST", "/v1/teach/stop", {"compileForReview": compile_for_review, "source": "PC_CODEX"})

    def virtual_instances(self) -> Any:
        return self._bounded_route("GET", "/v1/virtual/instances")

    def virtual_create(self, provider: str, image: str) -> Any:
        return self._bounded_route("POST", "/v1/virtual/instances", {
            "provider": provider,
            "image": image,
            "network_mode": "loopback",
        })

    def virtual_lifecycle(self, instance_id: str, operation: str) -> Any:
        return self._bounded_route(
            "POST", f"/v1/virtual/instances/{_quote(instance_id)}/{operation}"
        )

    def routine_run(self, device_id: str, routine_id: str) -> Any:
        return self._bounded_route(
            "POST",
            f"/v1/devices/{_quote(device_id)}/agent/routines/{_quote(routine_id)}/runs",
            {},
        )

    def routine_status(self, device_id: str, run_id: str) -> Any:
        return self._bounded_route(
            "GET", f"/v1/devices/{_quote(device_id)}/agent/routine-runs/{_quote(run_id)}"
        )

    def routine_cancel(self, device_id: str, run_id: str) -> Any:
        return self._bounded_route(
            "POST",
            f"/v1/devices/{_quote(device_id)}/agent/routine-runs/{_quote(run_id)}/cancel",
            {},
        )

    def lab_missions(self) -> Any:
        return self._bounded_route("GET", "/v1/lab/missions")

    def lab_start(self, device_id: str, name: str, missions: list[str], variants: list[dict[str, Any]], repetitions: int) -> Any:
        return self._bounded_route("POST", "/v1/lab/experiments", {
            "deviceId": device_id, "name": name, "missions": missions, "variants": variants, "repetitions": repetitions,
        })

    def ask_start(self, device_id: str, goal: str) -> Any:
        return self._bounded_route("POST", f"/v1/devices/{_quote(device_id)}/ask/start", {"goal": goal})

    def ask_status(self, device_id: str, request_id: str | None = None) -> Any:
        return self._bounded_route("POST", f"/v1/devices/{_quote(device_id)}/ask/status", {"requestId": request_id} if request_id else {})

    def ask_cancel(self, device_id: str, request_id: str | None = None) -> Any:
        return self._bounded_route("POST", f"/v1/devices/{_quote(device_id)}/ask/cancel", {"requestId": request_id} if request_id else {})

    def lab_experiments(self) -> Any:
        return self._bounded_route("GET", "/v1/lab/experiments")

    def lab_experiment(self, experiment_id: str) -> Any:
        return self._bounded_route("GET", f"/v1/lab/experiments/{_quote(experiment_id)}")

    def lab_stop(self, experiment_id: str) -> Any:
        return self._bounded_route("POST", f"/v1/lab/experiments/{_quote(experiment_id)}/stop", {})

    def _bounded_route(self, method: str, path: str, payload: Any | None = None) -> Any:
        """Call one frozen Gateway route and never improvise when it is unavailable."""
        try:
            return self._request(method, path, payload)
        except GatewayError as exc:
            if exc.status not in {404, 405}:
                raise
            raise GatewayError(
                "Cyclone Device Gateway does not expose this governed capability",
                status=exc.status,
                body={
                    "error": {
                        "code": "CAPABILITY_UNAVAILABLE",
                        "layer": "CAPABILITY",
                        "retryable": False,
                    }
                },
            ) from exc

    def device_status(self, device_id: str) -> Any:
        return self._request("GET", f"/v1/devices/{_quote(device_id)}/agent/status")

    def device_capabilities(self, device_id: str, *, refresh: bool = False) -> dict[str, Any]:
        cached = self._device_capabilities.get(device_id)
        if cached is not None and not refresh:
            return cached
        response = self._request("GET", f"/v1/devices/{_quote(device_id)}/agent/capabilities")
        if not isinstance(response, dict) or response.get("protocol_version") != CAPABILITY_PROTOCOL_VERSION:
            raise GatewayError(
                "Per-device capability protocol mismatch",
                body={"error": {"code": "PROTOCOL_MISMATCH", "layer": "PROTOCOL"}},
            )
        descriptors = response.get("capabilities")
        if not isinstance(descriptors, list):
            raise GatewayError(
                "Per-device capability discovery is malformed",
                body={"error": {"code": "PROTOCOL_MISMATCH", "layer": "PROTOCOL"}},
            )
        ids = []
        for descriptor in descriptors:
            if not isinstance(descriptor, dict) or not isinstance(descriptor.get("capability_id"), str):
                raise GatewayError(
                    "Per-device capability descriptor is malformed",
                    body={"error": {"code": "PROTOCOL_MISMATCH", "layer": "PROTOCOL"}},
                )
            ids.append(descriptor["capability_id"])
        self._device_capability_ids[device_id] = frozenset(ids)
        self._device_capabilities[device_id] = response
        return response

    def device_observe(
        self,
        device_id: str,
        *,
        include_screenshot: bool = False,
        mode: str = "compact",
        session_id: str | None = None,
        sessionId: str | None = None,
        display_id: int | None = None,
        displayId: int | None = None,
    ) -> Any:
        identity = _execution_identity(session_id=session_id, sessionId=sessionId, display_id=display_id, displayId=displayId)
        response = self._request("POST", f"/v1/devices/{_quote(device_id)}/agent/observe", {
            "mode": mode,
            "include_screenshot": include_screenshot,
            **identity,
        })
        witness = response.get("witness") if isinstance(response, dict) else None
        if isinstance(witness, dict) and isinstance(witness.get("observation_id"), str):
            self._observation_ids[_observation_key(device_id, identity)] = witness["observation_id"]
        return response

    def device_ui_search(
        self,
        device_id: str,
        query: str,
        *,
        session_id: str | None = None,
        sessionId: str | None = None,
        display_id: int | None = None,
        displayId: int | None = None,
    ) -> Any:
        params: dict[str, Any] = {"q": query}
        params.update(_execution_identity(session_id=session_id, sessionId=sessionId, display_id=display_id, displayId=displayId))
        encoded = urllib.parse.urlencode(params)
        return self._request("GET", f"/v1/devices/{_quote(device_id)}/agent/ui/search?{encoded}")

    def device_ui_element(
        self,
        device_id: str,
        element_id: str,
        *,
        session_id: str | None = None,
        sessionId: str | None = None,
        display_id: int | None = None,
        displayId: int | None = None,
    ) -> Any:
        identity = _execution_identity(session_id=session_id, sessionId=sessionId, display_id=display_id, displayId=displayId)
        path = f"/v1/devices/{_quote(device_id)}/agent/ui/element/{urllib.parse.quote(element_id, safe='')}"
        if identity:
            path = f"{path}?{urllib.parse.urlencode(identity)}"
        return self._request("GET", path)

    def device_current_page(self, device_id: str) -> Any:
        return self._request("GET", f"/v1/devices/{_quote(device_id)}/agent/page/current")

    def device_page_history(self, device_id: str) -> Any:
        return self._request("GET", f"/v1/devices/{_quote(device_id)}/agent/page/history")

    def device_action(
        self,
        device_id: str,
        tool: str,
        params: dict[str, Any],
        goal: str,
        *,
        session_id: str | None = None,
        sessionId: str | None = None,
        display_id: int | None = None,
        displayId: int | None = None,
    ) -> Any:
        discovery = self.device_capabilities(device_id)
        advertised = self._device_capability_ids.get(device_id) or frozenset()
        if tool not in advertised:
            raise GatewayError(
                f"Capability {tool} is not advertised by the connected Cyclone phone",
                body={
                    "protocol_version": CAPABILITY_PROTOCOL_VERSION,
                    "capability_id": tool,
                    "ok": False,
                    "error": {"code": "CAPABILITY_UNAVAILABLE", "layer": "CAPABILITY", "retryable": False},
                },
            )
        gateway_health = discovery.get("gateway_health") if isinstance(discovery, dict) else None
        if isinstance(gateway_health, dict) and gateway_health.get("state") == "UNAVAILABLE":
            raise GatewayError(
                "Connected phone reports unavailable capabilities",
                body={"error": {"code": "CAPABILITY_UNAVAILABLE", "layer": "CAPABILITY", "retryable": True}},
            )
        identity = _execution_identity(session_id=session_id, sessionId=sessionId, display_id=display_id, displayId=displayId)
        observation_id = self._observation_ids.get(_observation_key(device_id, identity))
        if tool not in NON_MUTATING_CAPABILITIES and not observation_id:
            raise GatewayError(
                "A fresh phone observation is required before a mutating action",
                body={
                    "protocol_version": CAPABILITY_PROTOCOL_VERSION,
                    "capability_id": tool,
                    "ok": False,
                    "error": {
                        "code": "STALE_OBSERVATION",
                        "layer": "PROTOCOL",
                        "retryable": True,
                    },
                },
            )
        forwarded, request_ai_control = _split_ai_control(params)
        payload = {
            "capability_id": tool,
            "params": attach_execution_scope(forwarded, identity or None),
            "goal": goal,
            "expected_observation_id": observation_id,
            **identity,
        }
        if request_ai_control:
            payload["request_ai_control"] = True
        raw = self._request("POST", f"/v1/devices/{_quote(device_id)}/agent/action", payload)
        if tool not in NON_MUTATING_CAPABILITIES:
            self._observation_ids.pop(_observation_key(device_id, identity), None)
        return normalize_desktop_action(device_id, tool, raw)

    def device_debug_bundle(self, device_id: str, expected: str = "", goal: str = "") -> Any:
        return self._request(
            "POST",
            f"/v1/devices/{_quote(device_id)}/agent/debug",
            {"expected": expected, "goal": goal},
        )

    def device_teach_start(self, device_id: str, goal: str = "") -> Any:
        return self._request(
            "POST",
            f"/v1/devices/{_quote(device_id)}/agent/teach/start",
            {"goal": goal},
        )

    def device_teach_status(self, device_id: str) -> Any:
        return self._request("GET", f"/v1/devices/{_quote(device_id)}/agent/teach/status")

    def device_teach_stop(self, device_id: str, compile_for_review: bool = True) -> Any:
        return self._request(
            "POST",
            f"/v1/devices/{_quote(device_id)}/agent/teach/stop",
            {"compile_for_review": compile_for_review},
        )


def normalize_desktop_action(device_id: str, tool: str, raw: Any) -> dict[str, Any]:
    """Adapt the Desktop fleet agent action envelope to the canonical capability envelope.

    The Desktop agent returns ``{transport, execution, verification, after}`` without the V3
    capability protocol wrapper. This adapter validates that shape and re-wraps it so the shared
    fail-closed classifier (transport -> execution -> verification) applies unchanged.
    """
    if not isinstance(raw, dict):
        return {
            "protocol_version": CAPABILITY_PROTOCOL_VERSION,
            "capability_id": tool,
            "device_id": device_id,
            "ok": False,
            "transport": {"ok": False, "status": "disconnected"},
            "execution": {"ok": False},
            "verification": {"ok": False, "status": "failed"},
            "error": {"code": "PROTOCOL_MISMATCH", "layer": "PROTOCOL"},
        }
    transport = raw.get("transport")
    transport_ok = isinstance(transport, dict) and transport.get("ok") is True
    execution = raw.get("execution")
    execution_ok = _desktop_execution_ok(execution)
    verification = raw.get("verification")
    verification_ok = _desktop_verification_ok(verification)
    return {
        "protocol_version": CAPABILITY_PROTOCOL_VERSION,
        "correlation_id": raw.get("correlation_id"),
        "capability_id": raw.get("capability_id") or tool,
        "device_id": raw.get("device_id") or device_id,
        "ok": bool(transport_ok and execution_ok and verification_ok),
        "transport": transport if isinstance(transport, dict) else {"ok": transport_ok},
        "execution": execution if isinstance(execution, dict) else {"ok": execution_ok},
        "verification": {
            "ok": verification_ok,
            "status": "verified" if verification_ok else "failed",
            "before_observation_id": (verification or {}).get("before_observation_id") if isinstance(verification, dict) else None,
            "after_observation_id": (verification or {}).get("after_observation_id") if isinstance(verification, dict) else None,
            "after_page_key": (verification or {}).get("after_page_key") if isinstance(verification, dict) else None,
        },
        "after": raw.get("after"),
        "error": None,
    }


def _desktop_execution_ok(execution: Any) -> bool:
    if isinstance(execution, dict):
        value = execution.get("ok")
        if isinstance(value, bool):
            return value
        return True
    return bool(execution)


def _desktop_verification_ok(verification: Any) -> bool:
    if not isinstance(verification, dict):
        return False
    passed = verification.get("passed")
    return passed is True and bool(verification.get("after_observation_id"))


def _legacy_serial_suffix(legacy: dict[str, Any]) -> str | None:
    serial = legacy.get("serial")
    if not isinstance(serial, str) or not serial:
        return None
    return serial[-4:] if len(serial) >= 4 else serial


def _legacy_device_row(legacy: dict[str, Any]) -> dict[str, Any] | None:
    if not isinstance(legacy, dict) or not legacy:
        return None
    serial = legacy.get("serial")
    suffix = None
    if isinstance(serial, str) and serial:
        suffix = serial[-4:] if len(serial) >= 4 else serial
    paired = bool(legacy.get("cyclone_bridge_reachable"))
    return {
        "deviceId": None,
        "id": None,
        "state": "READY" if paired else "ATTENTION",
        "name": str(legacy.get("model") or "Android phone"),
        "model": legacy.get("model"),
        "serialSuffix": suffix,
        "paired": paired,
        "pairing": False,
        "screen": "UNKNOWN",
        "display": {
            "width": None,
            "height": None,
            "resolution": legacy.get("screen_resolution"),
        },
        "surface": "legacy",
    }


def _quote(value: str) -> str:
    return urllib.parse.quote(value, safe="")


def _split_ai_control(params: dict[str, Any] | None) -> tuple[dict[str, Any], bool]:
    forwarded = dict(params or {})
    requested = forwarded.pop("request_ai_control", False) is True
    return forwarded, requested


def _execution_identity(
    *,
    session_id: str | None = None,
    sessionId: str | None = None,
    display_id: int | None = None,
    displayId: int | None = None,
) -> dict[str, Any]:
    scope = parse_execution_scope({
        "session_id": session_id,
        "sessionId": sessionId,
        "display_id": display_id,
        "displayId": displayId,
    })
    if scope is None:
        return {}
    return {"sessionId": scope["sessionId"], "displayId": scope["displayId"]}


def _observation_key(device_id: str | None, identity: dict[str, Any] | None) -> str:
    session_id = None
    if isinstance(identity, dict):
        session_id = identity.get("sessionId")
    return f"{device_id or '__legacy__'}::{session_id or 'default-foreground'}"
