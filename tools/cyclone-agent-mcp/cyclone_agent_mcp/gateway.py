from __future__ import annotations

import json
import os
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from typing import Any

DEFAULT_BASE_URL = "http://127.0.0.1:8765"
CAPABILITY_PROTOCOL_VERSION = "cyclone.gateway.capability.v1"
NON_MUTATING_CAPABILITIES = {"workspace.list", "phone.observe", "phone.find", "phone.wait_for"}


class GatewayError(RuntimeError):
    def __init__(self, message: str, *, status: int | None = None, body: Any = None):
        super().__init__(message)
        self.status = status
        self.body = body


@dataclass(frozen=True)
class DeviceSummary:
    device_id: str
    state: str
    label: str | None = None
    transport: str | None = None
    legacy_unscoped: bool = False

    @property
    def ready(self) -> bool:
        return self.state.upper() in {"READY", "CONNECTED"}

    def safe_dict(self) -> dict[str, Any]:
        value: dict[str, Any] = {"device_id": self.device_id, "state": self.state}
        if self.label:
            value["label"] = self.label[:80]
        if self.transport:
            value["transport"] = self.transport[:32]
        return value


class GatewayClient:
    """Authenticated client for Cyclone's loopback PC Device Gateway only."""

    def __init__(self, base_url: str | None = None, token: str | None = None, timeout: float = 30.0):
        self.base_url = (base_url or os.getenv("CYCLONE_DEVICE_GATEWAY_URL") or DEFAULT_BASE_URL).rstrip("/")
        self.token = token if token is not None else os.getenv("CYCLONE_DEVICE_GATEWAY_TOKEN", "")
        self.timeout = timeout
        self._validate_loopback(self.base_url)
        self._last_observation_id: dict[str, str] = {}
        self._capability_ids: dict[str, frozenset[str]] = {}
        self._capability_discovery: dict[str, dict[str, Any]] = {}
        self._legacy_device_id: str | None = None

    @staticmethod
    def _validate_loopback(base_url: str) -> None:
        parsed = urllib.parse.urlparse(base_url)
        if parsed.scheme != "http" or parsed.hostname not in {"127.0.0.1", "localhost", "::1"}:
            raise GatewayError("Gateway URL must use loopback HTTP")

    def _reload_secure_connection(self) -> bool:
        try:
            from secure_gateway_token import load_connection
        except ImportError:
            return False
        try:
            connection = load_connection()
        except Exception:
            return False
        if not isinstance(connection, dict):
            return False
        token = str(connection.get("token") or "").strip()
        base_url = str(connection.get("url") or "").strip().rstrip("/")
        if not token or not base_url:
            return False
        self._validate_loopback(base_url)
        changed = token != self.token or base_url != self.base_url
        self.token = token
        self.base_url = base_url
        return changed

    def _request(self, method: str, path: str, payload: Any | None = None) -> Any:
        try:
            return self._request_once(method, path, payload)
        except GatewayError as exc:
            recoverable = exc.status in {401, 403} or (
                isinstance(exc.body, dict)
                and isinstance(exc.body.get("error"), dict)
                and exc.body["error"].get("code") in {"AUTH_REJECTED", "DEVICE_DISCONNECTED"}
            )
            if recoverable and self._reload_secure_connection():
                self._capability_ids.clear()
                self._capability_discovery.clear()
                self._last_observation_id.clear()
                return self._request_once(method, path, payload)
            raise

    def _request_once(self, method: str, path: str, payload: Any | None = None) -> Any:
        if not self.token:
            self._reload_secure_connection()
            if not self.token:
                raise GatewayError(
                    "Cyclone Device Gateway credential is unavailable",
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
                return json.loads(raw.decode("utf-8")) if raw else {}
        except urllib.error.HTTPError as exc:
            raw = exc.read().decode("utf-8", errors="replace")
            try:
                body: Any = json.loads(raw) if raw else {}
            except json.JSONDecodeError:
                body = {"error": {"code": "PROTOCOL_MISMATCH", "layer": "PROTOCOL"}}
            # FastAPI wraps typed Desktop errors as {detail:{code,...}}. Normalize for MCP callers.
            if isinstance(body, dict) and isinstance(body.get("detail"), dict):
                detail = body["detail"]
                body = {"error": {"code": detail.get("code", "GATEWAY_ERROR"), "message": detail.get("message", "Gateway request failed"), "retryable": detail.get("retryable", False)}}
            raise GatewayError(f"Gateway HTTP {exc.code}", status=exc.code, body=body) from exc
        except (urllib.error.URLError, TimeoutError) as exc:
            raise GatewayError(
                "Cyclone Device Gateway is unavailable",
                body={"error": {"code": "DEVICE_DISCONNECTED", "layer": "TRANSPORT", "retryable": True}},
            ) from exc
        except json.JSONDecodeError as exc:
            raise GatewayError(
                "Cyclone Device Gateway returned invalid JSON",
                body={"error": {"code": "PROTOCOL_MISMATCH", "layer": "PROTOCOL"}},
            ) from exc

    def list_devices(self) -> list[DeviceSummary]:
        try:
            raw = self._request("GET", "/v1/fleet")
        except GatewayError as exc:
            if exc.status != 404:
                raise
            return self._legacy_single_device()
        items = raw.get("devices") if isinstance(raw, dict) else None
        if not isinstance(items, list):
            raise GatewayError("Device inventory is malformed", body={"error": {"code": "PROTOCOL_MISMATCH", "layer": "PROTOCOL"}})
        devices: list[DeviceSummary] = []
        for item in items:
            if not isinstance(item, dict):
                continue
            device_id = item.get("deviceId") or item.get("device_id") or item.get("id")
            if not isinstance(device_id, str) or not device_id.strip():
                continue
            state = item.get("state") or ("READY" if item.get("ready") is True else "ATTENTION")
            devices.append(DeviceSummary(
                device_id=device_id.strip(),
                state=str(state).upper(),
                label=str(item.get("name") or item.get("model") or "").strip() or None,
                transport="USB",
            ))
        return sorted(devices, key=lambda item: item.device_id)

    def _legacy_single_device(self) -> list[DeviceSummary]:
        status = self._request("GET", "/v1/device/status")
        if not isinstance(status, dict):
            return []
        device_id = status.get("device_id") or status.get("serial") or "default"
        state = status.get("state") or status.get("readiness") or status.get("status") or "READY"
        self._legacy_device_id = str(device_id)
        return [DeviceSummary(str(device_id), str(state).upper(), legacy_unscoped=True)]

    def select_device(self, device_id: str | None = None) -> DeviceSummary:
        devices = self.list_devices()
        ready = [item for item in devices if item.ready]
        if device_id:
            matches = [item for item in devices if item.device_id == device_id]
            if not matches:
                raise GatewayError("Requested device is not available", body={"error": {"code": "DEVICE_NOT_FOUND", "layer": "DEVICE"}})
            selected = matches[0]
            if not selected.ready:
                raise GatewayError("Requested device is not ready", body={"error": {"code": "DEVICE_NOT_READY", "layer": "DEVICE"}, "device": selected.safe_dict()})
            return selected
        if len(ready) == 1:
            return ready[0]
        if len(ready) > 1:
            raise GatewayError(
                "Multiple READY devices require explicit selection",
                body={"error": {"code": "DEVICE_SELECTION_REQUIRED", "layer": "DEVICE", "retryable": True}, "available_devices": [item.safe_dict() for item in ready]},
            )
        raise GatewayError(
            "No READY Cyclone device is available",
            body={"error": {"code": "DEVICE_NOT_READY", "layer": "DEVICE", "retryable": True}, "available_devices": [item.safe_dict() for item in devices]},
        )

    def _agent_path(self, selected: DeviceSummary, suffix: str) -> str:
        if selected.legacy_unscoped:
            raise GatewayError("Desktop device-scoped API is unavailable on this older Gateway", body={"error": {"code": "CAPABILITY_UNAVAILABLE", "layer": "DEVICE"}})
        return f"/v1/devices/{urllib.parse.quote(selected.device_id, safe='')}/agent{suffix}"

    def status(self, device_id: str | None = None) -> Any:
        selected = self.select_device(device_id)
        if selected.legacy_unscoped:
            return self._request("GET", "/v1/device/status")
        return self._request("GET", self._agent_path(selected, "/status"))

    def capabilities(self, device_id: str | None = None, *, refresh: bool = False) -> dict[str, Any]:
        selected = self.select_device(device_id)
        key = selected.device_id
        if key in self._capability_discovery and not refresh:
            return self._capability_discovery[key]
        if selected.legacy_unscoped:
            response = self._request("GET", "/v1/capabilities")
        else:
            response = self._request("GET", self._agent_path(selected, "/capabilities"))
        if not isinstance(response, dict) or response.get("protocol_version") != CAPABILITY_PROTOCOL_VERSION:
            raise GatewayError("Capability protocol mismatch", body={"error": {"code": "PROTOCOL_MISMATCH", "layer": "PROTOCOL"}})
        descriptors = response.get("capabilities")
        if not isinstance(descriptors, list):
            raise GatewayError("Capability inventory is malformed", body={"error": {"code": "PROTOCOL_MISMATCH", "layer": "PROTOCOL"}})
        ids = [item.get("capability_id") for item in descriptors if isinstance(item, dict) and isinstance(item.get("capability_id"), str)]
        self._capability_ids[key] = frozenset(ids)
        self._capability_discovery[key] = response
        return response

    def observe(
        self,
        device_id: str | None = None,
        *,
        include_screenshot: bool = False,
        mode: str = "compact",
        session_id: str | None = None,
        sessionId: str | None = None,
        display_id: int | None = None,
        displayId: int | None = None,
    ) -> Any:
        selected = self.select_device(device_id)
        identity = _execution_identity(session_id=session_id, sessionId=sessionId, display_id=display_id, displayId=displayId)
        if selected.legacy_unscoped:
            response = self._request("POST", "/v1/capabilities/observe", {
                "protocol_version": CAPABILITY_PROTOCOL_VERSION,
                "correlation_id": os.urandom(16).hex(),
                "include_screenshot": include_screenshot,
                "mode": mode,
                **identity,
            })
        else:
            response = self._request("POST", self._agent_path(selected, "/observe"), {
                "include_screenshot": include_screenshot,
                "mode": mode,
                **identity,
            })
        witness = response.get("witness") if isinstance(response, dict) else None
        if isinstance(witness, dict) and isinstance(witness.get("observation_id"), str):
            self._last_observation_id[_observation_key(selected.device_id, identity)] = witness["observation_id"]
        return response

    def ui_search(
        self,
        query: str,
        device_id: str | None = None,
        *,
        session_id: str | None = None,
        sessionId: str | None = None,
        display_id: int | None = None,
        displayId: int | None = None,
    ) -> Any:
        selected = self.select_device(device_id)
        identity = _execution_identity(session_id=session_id, sessionId=sessionId, display_id=display_id, displayId=displayId)
        query_string = urllib.parse.urlencode({"q": query, **identity})
        if selected.legacy_unscoped:
            return self._request("GET", f"/v1/ui/search?{query_string}")
        return self._request("GET", self._agent_path(selected, f"/ui/search?{query_string}"))

    def ui_element(
        self,
        element_id: str,
        device_id: str | None = None,
        *,
        session_id: str | None = None,
        sessionId: str | None = None,
        display_id: int | None = None,
        displayId: int | None = None,
    ) -> Any:
        selected = self.select_device(device_id)
        quoted = urllib.parse.quote(element_id, safe="")
        identity = _execution_identity(session_id=session_id, sessionId=sessionId, display_id=display_id, displayId=displayId)
        suffix = f"?{urllib.parse.urlencode(identity)}" if identity else ""
        if selected.legacy_unscoped:
            return self._request("GET", f"/v1/ui/element/{quoted}{suffix}")
        return self._request("GET", self._agent_path(selected, f"/ui/element/{quoted}{suffix}"))

    def current_page(self, device_id: str | None = None) -> Any:
        selected = self.select_device(device_id)
        return self._request("GET", "/v1/page/current" if selected.legacy_unscoped else self._agent_path(selected, "/page/current"))

    def page_history(self, device_id: str | None = None) -> Any:
        selected = self.select_device(device_id)
        return self._request("GET", "/v1/page/history" if selected.legacy_unscoped else self._agent_path(selected, "/page/history"))

    def action(
        self,
        tool: str,
        params: dict[str, Any],
        goal: str,
        device_id: str | None = None,
        *,
        session_id: str | None = None,
        sessionId: str | None = None,
        display_id: int | None = None,
        displayId: int | None = None,
    ) -> Any:
        selected = self.select_device(device_id)
        discovery = self.capabilities(selected.device_id)
        capability_ids = self._capability_ids.get(selected.device_id, frozenset())
        if tool not in capability_ids:
            raise GatewayError(f"Capability {tool} is unavailable", body={"error": {"code": "CAPABILITY_UNAVAILABLE", "layer": "CAPABILITY"}, "capability_id": tool})
        identity = _execution_identity(session_id=session_id, sessionId=sessionId, display_id=display_id, displayId=displayId)
        observation_id = self._last_observation_id.get(_observation_key(selected.device_id, identity))
        if tool not in NON_MUTATING_CAPABILITIES and observation_id is None:
            raise GatewayError("A fresh phone observation is required before mutation", body={"error": {"code": "STALE_OBSERVATION", "layer": "PROTOCOL", "retryable": True}})
        forwarded_params = dict(params)
        request_ai_control = forwarded_params.pop("request_ai_control", False) is True
        forwarded_params.update(identity)
        if selected.legacy_unscoped:
            payload = {
                "protocol_version": CAPABILITY_PROTOCOL_VERSION,
                "correlation_id": os.urandom(16).hex(),
                "capability_id": tool,
                "params": forwarded_params,
                "goal": goal,
                "source": "PC_AGENT_MCP",
                **identity,
            }
            if request_ai_control:
                payload["request_ai_control"] = True
            if observation_id:
                payload["expected_observation_id"] = observation_id
            response = self._request("POST", "/v1/capabilities/action", payload)
        else:
            payload = {"capability_id": tool, "params": forwarded_params, "goal": goal, **identity}
            if request_ai_control:
                payload["request_ai_control"] = True
            if observation_id:
                payload["expected_observation_id"] = observation_id
            response = self._request("POST", self._agent_path(selected, "/action"), payload)
        if tool not in NON_MUTATING_CAPABILITIES:
            self._last_observation_id.pop(_observation_key(selected.device_id, identity), None)
        return response

    def debug_bundle(self, device_id: str | None = None, *, expected: str = "", goal: str = "") -> Any:
        selected = self.select_device(device_id)
        if selected.legacy_unscoped:
            return self._request("POST", "/v1/debug/bundle", {"expected": expected, "goal": goal})
        return self._request("POST", self._agent_path(selected, "/debug"), {"expected": expected, "goal": goal})

    def teach_start(self, device_id: str | None = None, *, goal: str = "") -> Any:
        selected = self.select_device(device_id)
        if selected.legacy_unscoped:
            return self._request("POST", "/v1/teach/start", {"goal": goal, "source": "PC_AGENT_MCP"})
        return self._request("POST", self._agent_path(selected, "/teach/start"), {"goal": goal})

    def teach_status(self, device_id: str | None = None) -> Any:
        selected = self.select_device(device_id)
        return self._request("GET", "/v1/teach/status" if selected.legacy_unscoped else self._agent_path(selected, "/teach/status"))

    def teach_stop(self, device_id: str | None = None, *, compile_for_review: bool = True) -> Any:
        selected = self.select_device(device_id)
        if selected.legacy_unscoped:
            return self._request("POST", "/v1/teach/stop", {"compileForReview": compile_for_review, "source": "PC_AGENT_MCP"})
        return self._request("POST", self._agent_path(selected, "/teach/stop"), {"compile_for_review": compile_for_review})

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
            "POST", f"/v1/virtual/instances/{urllib.parse.quote(instance_id, safe='')}/{operation}"
        )

    def routine_run(self, device_id: str, routine_id: str) -> Any:
        return self._bounded_route(
            "POST",
            f"/v1/devices/{urllib.parse.quote(device_id, safe='')}/agent/routines/"
            f"{urllib.parse.quote(routine_id, safe='')}/runs",
            {},
        )

    def routine_status(self, device_id: str, run_id: str) -> Any:
        return self._bounded_route(
            "GET",
            f"/v1/devices/{urllib.parse.quote(device_id, safe='')}/agent/routine-runs/"
            f"{urllib.parse.quote(run_id, safe='')}",
        )

    def routine_cancel(self, device_id: str, run_id: str) -> Any:
        return self._bounded_route(
            "POST",
            f"/v1/devices/{urllib.parse.quote(device_id, safe='')}/agent/routine-runs/"
            f"{urllib.parse.quote(run_id, safe='')}/cancel",
            {},
        )

    def app_manual(self, device_id: str, package_name: str, query: str | None = None) -> Any:
        params = {"placeId": f"package:{package_name}"}
        if query:
            params["q"] = query
        return self._bounded_route(
            "GET",
            f"/v1/devices/{urllib.parse.quote(device_id, safe='')}/manual?{urllib.parse.urlencode(params)}",
        )

    def lab_missions(self) -> Any:
        return self._bounded_route("GET", "/v1/lab/missions")

    def lab_start(self, device_id: str, name: str, missions: list[str], variants: list[dict[str, Any]], repetitions: int) -> Any:
        return self._bounded_route("POST", "/v1/lab/experiments", {
            "deviceId": device_id, "name": name, "missions": missions, "variants": variants, "repetitions": repetitions,
        })

    def lab_experiments(self) -> Any:
        return self._bounded_route("GET", "/v1/lab/experiments")

    def lab_experiment(self, experiment_id: str) -> Any:
        return self._bounded_route("GET", f"/v1/lab/experiments/{urllib.parse.quote(experiment_id, safe='')}")

    def lab_stop(self, experiment_id: str) -> Any:
        return self._bounded_route("POST", f"/v1/lab/experiments/{urllib.parse.quote(experiment_id, safe='')}/stop", {})

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


def _execution_identity(
    *,
    session_id: str | None = None,
    sessionId: str | None = None,
    display_id: int | None = None,
    displayId: int | None = None,
) -> dict[str, Any]:
    session = session_id if session_id not in (None, "") else sessionId
    display = display_id if display_id is not None else displayId
    if session in (None, "") and display is None:
        return {}
    payload: dict[str, Any] = {}
    if session not in (None, ""):
        payload["sessionId"] = session
    if display is not None:
        payload["displayId"] = display
    return payload


def _observation_key(device_id: str | None, identity: dict[str, Any] | None) -> str:
    session_id = identity.get("sessionId") if isinstance(identity, dict) else None
    return f"{device_id or '__legacy__'}::{session_id or 'default-foreground'}"

