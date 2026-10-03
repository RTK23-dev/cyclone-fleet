from __future__ import annotations

import re
import secrets
import threading
from typing import Any

from ..actions.envelope import extract_android_execution
from ..cyclone_bridge.client import BridgeDisconnectedError, BridgeOperationError, BridgeProtocolError
from ..execution_scope import DEFAULT_FOREGROUND_SESSION_ID, IDENTITY_KEYS, parse_execution_identity
from .fleet import DeviceFleetManager, DeviceSession
from .models import DesktopRuntimeError, RuntimeErrorCode


LAYER2_PROTOCOL_VERSION = "cyclone.one.layer2.v1"
LAYER2_OPS = frozenset({"list", "register", "switch", "pause", "release", "arm", "next"})
LAYER2_ERROR_CODES = (
    "GATE",
    "MUTATE_LOCK",
    "TARGET_MISMATCH",
    "STALE_WORKSPACE",
    "QUEUE_EMPTY",
    "USER_UNVERIFIED",
)
FORBIDDEN_PARAM_KEYS = frozenset({"command", "shell", "powershell", "su", "script"})
ALLOWED_PARAM_KEYS = frozenset({"id", "label", "appPackage", "androidUserId", "displayId", "goal"})
WORKSPACE_ID = re.compile(r"^[A-Za-z0-9_-]{1,80}$")
PACKAGE_NAME = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+$")


def parse_layer2_error(code: str | None, message: str | None) -> str | None:
    """Map Android Layer 2 prefixes; never synthesize GATE."""
    blob = f"{code or ''} {message or ''}".upper()
    if not blob.strip():
        return None
    for token in LAYER2_ERROR_CODES:
        if (
            blob.startswith(token)
            or f"{token}:" in blob
            or f" {token} " in f" {blob} "
            or blob.endswith(token)
        ):
            return token
    return None


def layer2_error_from_execution(execution: dict[str, Any] | None) -> DesktopRuntimeError | None:
    if not isinstance(execution, dict):
        return None
    error = execution.get("error")
    code = ""
    message = ""
    if isinstance(error, dict):
        code = str(error.get("code") or "")
        message = str(error.get("message") or "")
    elif isinstance(error, str):
        message = error
    token = parse_layer2_error(code, message)
    if token is None:
        return None
    return DesktopRuntimeError(
        RuntimeErrorCode(token),
        (message or token)[:300],
        retryable=token in {"GATE", "STALE_WORKSPACE", "QUEUE_EMPTY"},
    )


def _forbidden_paths(value: Any, prefix: str = "") -> list[str]:
    found: list[str] = []
    if isinstance(value, dict):
        for key, nested in value.items():
            key_text = str(key)
            path = f"{prefix}.{key_text}" if prefix else key_text
            if key_text.lower() in FORBIDDEN_PARAM_KEYS:
                found.append(path)
            found.extend(_forbidden_paths(nested, path))
    elif isinstance(value, list):
        for index, nested in enumerate(value):
            found.extend(_forbidden_paths(nested, f"{prefix}[{index}]"))
    return found


class Layer2WorkspaceService:
    """Display-0 time-sliced lock adapter. Not fleet groups and not named VD sessions."""

    PROTOCOL = LAYER2_PROTOCOL_VERSION
    OPS = LAYER2_OPS

    def __init__(self, fleet: DeviceFleetManager):
        self.fleet = fleet
        self._lock = threading.RLock()
        self._leases: dict[str, dict[str, Any]] = {}

    def list(self, device_id: str) -> dict[str, Any]:
        return self.command(device_id, "list", {})

    def command(
        self,
        device_id: str,
        operation: str,
        params: dict[str, Any] | None = None,
        *,
        tool: str | None = None,
        goal: str = "",
    ) -> dict[str, Any]:
        op, android_tool = self._operation(operation, tool)
        raw_params = dict(params or {})
        bad = _forbidden_paths(raw_params)
        if bad:
            raise DesktopRuntimeError(
                RuntimeErrorCode.INVALID_REQUEST,
                f"Forbidden workspace parameter(s): {', '.join(sorted(bad))}",
            )
        identity = self._require_foreground(raw_params)
        clean = self._workspace_params(raw_params)
        self._validate_op(op, clean)
        if op == "arm" and "goal" in clean:
            clean["goal"] = str(clean["goal"])[:500]
        forwarded = dict(clean)
        forwarded["sessionId"] = DEFAULT_FOREGROUND_SESSION_ID
        forwarded["displayId"] = 0
        goal_text = str(clean.get("goal") or goal or f"workspace {op}")[:1000]
        session = self._paired(device_id)
        result = self._request(
            session,
            "action.execute",
            {
                "tool": android_tool,
                "params": forwarded,
                "goal": goal_text,
                "source": "PC_CODEX",
                "sessionId": DEFAULT_FOREGROUND_SESSION_ID,
                "displayId": 0,
            },
        )
        execution = extract_android_execution(result)
        if execution is None:
            raise DesktopRuntimeError(
                RuntimeErrorCode.PROTOCOL_MISMATCH,
                "Android workspace result did not match the capability protocol.",
            )
        if execution.get("ok") is not True:
            raised = layer2_error_from_execution(execution)
            if raised is not None:
                raise raised
            error = execution.get("error") if isinstance(execution.get("error"), dict) else {}
            message = str((error or {}).get("message") or "Android workspace operation failed.")
            raise DesktopRuntimeError(RuntimeErrorCode.CAPABILITY_UNAVAILABLE, message[:300])
        payload = self._payload(execution, result)
        public = self._normalize(device_id, payload, op=op, identity=identity)
        if op in {"pause", "release"}:
            self.clear(device_id)
        else:
            self.remember_from_status(device_id, public)
        return public

    def lease(self, device_id: str) -> dict[str, Any] | None:
        with self._lock:
            value = self._leases.get(device_id)
            return dict(value) if value else None

    def summary(self, device_id: str) -> dict[str, Any]:
        current = self.lease(device_id)
        return {
            "protocol": self.PROTOCOL,
            "plane": "layer2",
            "holder": None if current is None else current.get("workspaceId"),
            "workspaceGeneration": None if current is None else current.get("generation"),
        }

    def clear(self, device_id: str) -> None:
        with self._lock:
            self._leases.pop(device_id, None)

    def remember_from_status(self, device_id: str, public: dict[str, Any]) -> None:
        holder = public.get("holder") or public.get("workspaceId") or public.get("lockOwner")
        generation = public.get("workspaceGeneration")
        if holder in (None, "") or generation is None:
            self.clear(device_id)
            return
        try:
            generation_i = int(generation)
        except (TypeError, ValueError):
            self.clear(device_id)
            return
        if generation_i < 0:
            self.clear(device_id)
            return
        package = None
        for item in public.get("workspaces") or []:
            if isinstance(item, dict) and str(item.get("id") or "") == str(holder):
                package = item.get("appPackage")
                break
        with self._lock:
            self._leases[device_id] = {
                "workspaceId": str(holder),
                "generation": generation_i,
                "package": package,
            }

    def remember_from_android(
        self,
        device_id: str,
        execution: dict[str, Any] | None,
        result: dict[str, Any] | None = None,
    ) -> None:
        payload = self._payload(execution, result)
        self.remember_from_status(device_id, self._normalize(device_id, payload))

    def _paired(self, device_id: str) -> DeviceSession:
        session = self.fleet.get(device_id)
        if not session.credential:
            raise DesktopRuntimeError(RuntimeErrorCode.PAIRING_REQUIRED, "Pair this phone before workspace access.")
        return session

    def _request(self, session: DeviceSession, op: str, args: dict[str, Any]) -> dict[str, Any]:
        try:
            value = session.bridge().request(op, args, request_id=f"layer2-{secrets.token_urlsafe(18)}")
            return value if isinstance(value, dict) else {"value": value}
        except BridgeOperationError as exc:
            mapped = parse_layer2_error(exc.code, str(exc))
            mapping = {
                "AUTH_REJECTED": RuntimeErrorCode.AUTH_REJECTED,
                "CAPABILITY_UNAVAILABLE": RuntimeErrorCode.CAPABILITY_UNAVAILABLE,
                "STALE_OBSERVATION": RuntimeErrorCode.STALE_OBSERVATION,
                "POLICY_DENIED": RuntimeErrorCode.POLICY_DENIED,
                "PROTOCOL_MISMATCH": RuntimeErrorCode.PROTOCOL_MISMATCH,
                "HUMAN_HAS_CONTROL": RuntimeErrorCode.HUMAN_HAS_CONTROL,
                "PHONE_LOCKED": RuntimeErrorCode.PHONE_LOCKED,
                "GATE": RuntimeErrorCode.GATE,
                "MUTATE_LOCK": RuntimeErrorCode.MUTATE_LOCK,
                "TARGET_MISMATCH": RuntimeErrorCode.TARGET_MISMATCH,
                "STALE_WORKSPACE": RuntimeErrorCode.STALE_WORKSPACE,
                "QUEUE_EMPTY": RuntimeErrorCode.QUEUE_EMPTY,
                "USER_UNVERIFIED": RuntimeErrorCode.USER_UNVERIFIED,
            }
            code = mapping.get(mapped or exc.code, RuntimeErrorCode.CAPABILITY_UNAVAILABLE)
            raise DesktopRuntimeError(code, f"Android rejected {op}.") from exc
        except (BridgeDisconnectedError, BridgeProtocolError) as exc:
            raise DesktopRuntimeError(
                RuntimeErrorCode.DEVICE_DISCONNECTED,
                "Phone disconnected from Cyclone Gateway.",
                retryable=True,
            ) from exc

    @staticmethod
    def _operation(operation: str, tool: str | None) -> tuple[str, str]:
        raw = str(tool or operation or "").strip()
        if raw == "phone.workspace_switch":
            return "switch", "phone.workspace_switch"
        name = raw[10:] if raw.startswith("workspace.") else raw
        if name not in LAYER2_OPS:
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "Unknown workspace operation.")
        return name, f"workspace.{name}"

    @staticmethod
    def _require_foreground(params: dict[str, Any]) -> dict[str, Any]:
        try:
            identity = parse_execution_identity(params)
        except ValueError as exc:
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, str(exc)) from exc
        if identity is None:
            return {"sessionId": DEFAULT_FOREGROUND_SESSION_ID, "displayId": 0}
        if identity.get("sessionId") != DEFAULT_FOREGROUND_SESSION_ID or identity.get("displayId") != 0:
            raise DesktopRuntimeError(
                RuntimeErrorCode.INVALID_REQUEST,
                "Layer 2 workspaces require default-foreground / display 0.",
            )
        return identity

    @staticmethod
    def _workspace_params(params: dict[str, Any]) -> dict[str, Any]:
        clean: dict[str, Any] = {}
        for key, value in params.items():
            if key in IDENTITY_KEYS:
                continue
            if key not in ALLOWED_PARAM_KEYS:
                raise DesktopRuntimeError(
                    RuntimeErrorCode.INVALID_REQUEST,
                    f"Unexpected workspace parameter: {key}",
                )
            clean[key] = value
        if "displayId" in clean and clean["displayId"] != 0:
            raise DesktopRuntimeError(
                RuntimeErrorCode.INVALID_REQUEST,
                "Layer 2 workspaces require displayId 0.",
            )
        return clean

    @staticmethod
    def _validate_op(op: str, params: dict[str, Any]) -> None:
        if op == "register":
            workspace_id = params.get("id")
            label = params.get("label")
            package = params.get("appPackage")
            if not isinstance(workspace_id, str) or not WORKSPACE_ID.fullmatch(workspace_id):
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "register requires a valid id.")
            if not isinstance(label, str) or not label.strip() or len(label) > 80:
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "register requires label (1..80).")
            if not isinstance(package, str) or not PACKAGE_NAME.fullmatch(package):
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "register requires a valid appPackage.")
            if "androidUserId" in params:
                user = params["androidUserId"]
                if type(user) is not int or user < 0:
                    raise DesktopRuntimeError(
                        RuntimeErrorCode.INVALID_REQUEST,
                        "androidUserId must be an int >= 0.",
                    )
            if "displayId" in params and params["displayId"] != 0:
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "Layer 2 supports display 0 only.")
            return
        if op in {"switch", "arm"}:
            workspace_id = params.get("id")
            if not isinstance(workspace_id, str) or not WORKSPACE_ID.fullmatch(workspace_id):
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, f"{op} requires a valid id.")

    @staticmethod
    def _payload(execution: dict[str, Any] | None, result: dict[str, Any] | None) -> dict[str, Any]:
        for source in (execution, result):
            if not isinstance(source, dict):
                continue
            nested = source.get("payload")
            if isinstance(nested, dict):
                return nested
        merged: dict[str, Any] = {}
        for source in (result, execution):
            if isinstance(source, dict):
                merged.update(source)
        return merged

    def _normalize(
        self,
        device_id: str,
        payload: dict[str, Any],
        *,
        op: str | None = None,
        identity: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        workspaces: list[dict[str, Any]] = []
        raw_items = payload.get("workspaces")
        if isinstance(raw_items, list):
            for item in raw_items:
                if not isinstance(item, dict):
                    continue
                workspaces.append({
                    "id": str(item.get("id") or ""),
                    "label": str(item.get("label") or ""),
                    "appPackage": str(item.get("appPackage") or ""),
                    "androidUserId": item.get("androidUserId") if isinstance(item.get("androidUserId"), int) else 0,
                    "displayId": 0,
                    "state": str(item.get("state") or ""),
                })
        workspace_id = payload.get("workspaceId")
        holder = payload.get("holder")
        if holder in ("", None):
            holder = workspace_id
        if holder not in ("", None):
            holder = str(holder)
        else:
            holder = None
        generation = payload.get("workspaceGeneration")
        if isinstance(generation, bool) or generation in ("", None):
            generation = None
        elif isinstance(generation, (int, float)) and float(generation) == int(generation) and int(generation) >= 0:
            generation = int(generation)
        else:
            generation = None
        armed = payload.get("armed")
        armed_ids = [str(item) for item in armed] if isinstance(armed, list) else []
        public: dict[str, Any] = {
            "protocol": self.PROTOCOL,
            "deviceId": device_id,
            "sessionId": DEFAULT_FOREGROUND_SESSION_ID,
            "displayId": 0,
            "plane": "layer2",
            "workspaces": workspaces,
            "holder": holder,
            "workspaceGeneration": generation,
            "armed": armed_ids,
            "gated": bool(payload.get("gated")) if payload.get("gated") is not None else False,
            "root": payload.get("root"),
            "lockOwner": holder,
        }
        goals = payload.get("goals")
        if isinstance(goals, dict):
            public["goals"] = goals
        elif isinstance(payload.get("goal"), str) and payload.get("goal"):
            public["goals"] = {str(workspace_id or holder or ""): str(payload.get("goal"))[:500]}
        if identity:
            public["sessionId"] = identity.get("sessionId") or DEFAULT_FOREGROUND_SESSION_ID
        if op in {"switch", "next"} or workspace_id not in ("", None):
            public["workspaceId"] = str(workspace_id or holder or "")
            public["workspaceGeneration"] = generation
            if "verified" in payload:
                public["verified"] = bool(payload.get("verified"))
            elif op in {"switch", "next"}:
                public["verified"] = True
            if payload.get("next") is not None:
                public["next"] = payload.get("next")
        return public
