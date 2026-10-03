from __future__ import annotations

from collections import defaultdict, deque
import secrets
import threading
import time
from typing import Any

from ..actions.envelope import (
    android_execution_error_class,
    canonical_error,
    extract_android_execution,
    safe_android_execution,
)
from ..cyclone_bridge.client import BridgeDisconnectedError, BridgeOperationError, BridgeProtocolError
from ..execution_scope import (
    DEFAULT_FOREGROUND_SESSION_ID,
    attach_execution_identity,
    history_key,
    parse_execution_identity,
)
from .fleet import DeviceFleetManager, DeviceSession
from .layer2 import (
    LAYER2_OPS,
    WORKSPACE_ID,
    layer2_error_from_execution,
    parse_layer2_error,
)
from . import phone_errors
from .models import DesktopRuntimeError, RuntimeErrorCode, now_ms
from .page_text import _compact_observation
from .readiness import enrich_device_public

CAPABILITY_PROTOCOL_VERSION = "cyclone.gateway.capability.v1"
DEVICE_OPERATION_CONTRACT_VERSION = "cyclone.desktop.device-operation.v1"
ALLOWED_PHONE_TOOLS = frozenset({
    "workspace.list", "workspace.register", "workspace.switch", "phone.workspace_switch",
    "workspace.pause", "workspace.release", "workspace.arm", "workspace.next",
    "phone.observe", "phone.find", "phone.click", "phone.long_press", "phone.swipe",
    "phone.scroll", "phone.type", "phone.back", "phone.home", "phone.open_app", "phone.wait_for",
})
WORKSPACE_PHONE_TOOLS = frozenset({
    "workspace.list", "workspace.register", "workspace.switch", "phone.workspace_switch",
    "workspace.pause", "workspace.release", "workspace.arm", "workspace.next",
})
WORKSPACE_AI_TOOLS = frozenset({
    "workspace.register", "workspace.switch", "phone.workspace_switch", "workspace.arm", "workspace.next",
})
LAYER2_MUTATING_PHONE_TOOLS = frozenset({
    "phone.click", "phone.long_press", "phone.swipe", "phone.scroll", "phone.type",
    "phone.back", "phone.home", "phone.open_app", "phone.launch_intent", "phone.set_clipboard",
    "phone.tap",
})
PAGE_TRANSITION_TOOLS = frozenset({
    "phone.click", "phone.long_press", "phone.back", "phone.home", "phone.open_app",
})
HUMAN_HAS_CONTROL_HINT = (
    "Companion currently owns input. Yield control (Give control to AI) "
    "or retry with request_ai_control=true. A locked phone is not stolen."
)
_SHARE_READONLY_STATES = frozenset({"SHARE", "REFERENCE", "READ_ONLY"})


def _goal_label_present(after_raw: dict[str, Any], after: dict[str, Any], goal: str) -> bool:
    needle = (goal or "").strip()
    if not needle:
        return False
    parts = [
        str((after_raw or {}).get("pageText") or ""),
        str((after_raw or {}).get("pageSummary") or ""),
        str((after_raw or {}).get("pageTitle") or ""),
        str((after or {}).get("pageText") or ""),
        str((after or {}).get("pageSummary") or ""),
        str((after or {}).get("title") or ""),
    ]
    return needle.lower() in " ".join(parts).lower()


_HOME_PAGE_KEYS = frozenset({"home", "launcher"})


def _home_launcher_surface(after_raw: dict[str, Any], after: dict[str, Any] | None = None) -> bool:
    """True when the after-state is a home/launcher surface (case-insensitive)."""
    raw = after_raw or {}
    compact = after or {}
    page_key = str(raw.get("pageKey") or compact.get("pageKey") or "").strip().lower()
    if page_key in _HOME_PAGE_KEYS:
        return True
    title = str(
        raw.get("pageTitle")
        or raw.get("title")
        or compact.get("title")
        or compact.get("pageTitle")
        or ""
    ).strip()
    if title.lower() == "home":
        return True
    package = str(raw.get("package") or compact.get("package") or "").lower()
    return "launcher" in package


class DesktopAgentService:
    """Device-scoped adapter to the Android Gateway.

    This layer deliberately exposes only the frozen typed Cyclone operations. It never accepts an
    arbitrary Android bridge operation, ADB command, shell command, or executable payload.
    """

    def __init__(
        self,
        fleet: DeviceFleetManager,
        history_limit: int = 40,
        *,
        snapshot=None,
        after_action_timeout_seconds: float = 1.0,
        after_action_poll_seconds: float = 0.1,
        layer2=None,
    ):
        self.fleet = fleet
        self.history_limit = max(5, min(int(history_limit), 100))
        self._snapshot = snapshot
        self._after_action_timeout_seconds = max(0.0, min(float(after_action_timeout_seconds), 2.0))
        self._after_action_poll_seconds = max(0.01, min(float(after_action_poll_seconds), 0.25))
        self._layer2 = layer2
        self._history: dict[str, deque[dict[str, Any]]] = defaultdict(lambda: deque(maxlen=self.history_limit))
        self._lock = threading.RLock()

    def status(self, device_id: str) -> dict[str, Any]:
        session = self._paired(device_id)
        result = self._request(session, "bridge.status", {})
        self.fleet.record_bridge_status(session, result)
        controller_owner = result.get("controllerOwner") if isinstance(result, dict) else None
        owner = getattr(session, "input_owner", "HUMAN")
        payload = {
            **self._operation_context(session, device_id, "status"),
            "status": result,
            "connection_health": self._connection_health(session),
            "inputOwner": owner,
            "controllerOwner": controller_owner,
            "sessions": self._session_summaries(session, result),
            "handoff": {
                "companionOwner": owner,
                "yieldHint": HUMAN_HAS_CONTROL_HINT if owner == "HUMAN" else None,
            },
        }
        if self._layer2 is not None:
            payload["layer2"] = self._layer2.summary(device_id)
        return payload

    @staticmethod
    def _connection_health(session: DeviceSession) -> dict[str, Any]:
        return {
            "bridgeReachable": session.bridge_ok,
            "lastHeartbeatEpochMs": session.last_heartbeat_ms,
            "reconnectAttempts": session.reconnect_attempts,
            "nextRetryEpochMs": session.next_reconnect_at_ms or None,
            "lastError": session.bridge_last_error,
            "errorClass": session.bridge_error_class,
        }

    def capabilities(self, device_id: str) -> dict[str, Any]:
        session = self._paired(device_id)
        status = self._request(session, "bridge.status", {})
        self.fleet.record_bridge_status(session, status)
        raw = status.get("capabilities") if isinstance(status, dict) else None
        phone_tools = raw.get("phoneTools", []) if isinstance(raw, dict) else []
        allowed = sorted({str(item) for item in phone_tools if str(item) in ALLOWED_PHONE_TOOLS})
        return {
            **self._operation_context(session, device_id, "capabilities"),
            "protocol_version": CAPABILITY_PROTOCOL_VERSION,
            "capabilities": [
                {"capability_id": capability_id, "source": "ANDROID_CANONICAL"}
                for capability_id in allowed
            ],
            "gateway_health": {"state": "READY" if allowed else "UNAVAILABLE"},
        }

    @staticmethod
    def _check_live_phone(session, payload, identity):
        if (payload or {}).get("livePhone") is not True:
            return
        if getattr(session, "source", None) not in {"USB", "LAN", "CLOUD"} or identity != {"sessionId": "default-foreground", "displayId": 0}:
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "Live Phone requires a physical foreground phone.")

    def observe(
        self,
        device_id: str,
        *,
        mode: str = "compact",
        include_screenshot: bool = False,
        payload: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        session = self._paired(device_id)  # USB/trust pairing, not execution sessionId.
        identity = self._execution_identity(payload)
        self._check_live_phone(session, payload, identity)
        observe_args = dict(identity or {})
        if (payload or {}).get("livePhone") is True:
            observe_args["livePhone"] = True
        raw_observation = self._request(session, "observe.semantic", observe_args)
        if identity:
            raw_observation.setdefault("sessionId", identity["sessionId"])
            raw_observation.setdefault("displayId", identity["displayId"])
        selected_mode = mode if mode in {"compact", "full"} else "compact"
        observation = raw_observation if selected_mode == "full" else _compact_observation(raw_observation)
        observation_id = str(raw_observation.get("observationId") or "")
        record = {
            "kind": "observation",
            "at": now_ms(),
            "observationId": observation_id,
            "pageKey": raw_observation.get("pageKey"),
            "package": raw_observation.get("package"),
        }
        self._append(device_id, record, identity)
        response = {
            **self._operation_context(session, device_id, "observe"),
            "mode": selected_mode,
            "observation": observation,
            "witness": {
                "observation_id": observation_id,
                "page_key": raw_observation.get("pageKey"),
                "captured_at": raw_observation.get("timestamp"),
            },
            "afterState": self._after_state(raw_observation),
            # Screenshot transport belongs to Desktop video. This response never contains image
            # bytes, only the same bounded local artifact contract as `agent/screenshot`.
            "screenshot": None,
        }
        if include_screenshot:
            response["screenshot"] = self.screenshot(device_id, profile="live-phone" if (payload or {}).get("livePhone") is True else "thumbnail")["screenshot"]
        return response

    def ui_search(self, device_id: str, query: str, payload: dict[str, Any] | None = None) -> dict[str, Any]:
        session = self._paired(device_id)
        args = attach_execution_identity({"query": query[:300], "limit": 50}, self._execution_identity(payload))
        result = self._request(session, "ui.search", args)
        return {**result, **self._operation_context(session, device_id, "search"), "query": query[:300]}

    def ui_element(self, device_id: str, element_id: str, payload: dict[str, Any] | None = None) -> dict[str, Any]:
        session = self._paired(device_id)
        args = attach_execution_identity({"elementId": element_id[:500]}, self._execution_identity(payload))
        result = self._request(session, "ui.element", args)
        return {
            **result,
            **self._operation_context(session, device_id, "inspect"),
            "elementId": str(result.get("elementId") or element_id[:500]),
        }

    def current_page(self, device_id: str, payload: dict[str, Any] | None = None) -> dict[str, Any]:
        identity = self._execution_identity(payload)
        key = history_key(device_id, identity)
        with self._lock:
            latest = next((item for item in reversed(self._history[key]) if item.get("kind") == "observation"), None)
        if latest is None:
            return self.observe(device_id, payload=payload)
        session = self._paired(device_id)
        return {**self._operation_context(session, device_id, "current_page"), "page": latest}

    def page_history(self, device_id: str, payload: dict[str, Any] | None = None) -> dict[str, Any]:
        session = self._paired(device_id)
        identity = self._execution_identity(payload)
        key = history_key(device_id, identity)
        with self._lock:
            items = list(self._history[key])
        return {**self._operation_context(session, device_id, "page_history"), "history": items}

    def action(self, device_id: str, payload: dict[str, Any]) -> dict[str, Any]:
        session = self._paired(device_id)
        tool = str(payload.get("capability_id") or payload.get("tool") or "")
        if tool not in ALLOWED_PHONE_TOOLS:
            raise DesktopRuntimeError(RuntimeErrorCode.CAPABILITY_UNAVAILABLE, "Requested phone capability is unavailable.")
        params = payload.get("params") or {}
        if not isinstance(params, dict):
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "params must be an object.")
        params = dict(params)
        identity = self._execution_identity(payload)
        self._check_live_phone(session, payload, identity)
        self._reject_mixed_planes(params, identity)
        self._enforce_layer2_mutate_lock(device_id, tool, params, identity)
        if identity:
            params = attach_execution_identity(params, identity)
        goal = str(payload.get("goal") or tool.replace("phone.", "").replace("_", " "))[:1000]
        expected = str(payload.get("expected_observation_id") or payload.get("currentObservationId") or "")
        workspace_tool = tool in WORKSPACE_PHONE_TOOLS or tool.startswith("workspace.")
        mutating = tool not in {"phone.observe", "phone.find", "phone.wait_for", "workspace.list"}
        request_ai = bool(payload.get("request_ai_control") or params.pop("request_ai_control", False))
        if tool in WORKSPACE_AI_TOOLS or (mutating and not workspace_tool):
            self._require_ai_ownership(session, request_ai)
        if mutating and not expected and not workspace_tool:
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "A fresh observation is required before mutation.")
        if workspace_tool:
            return self._workspace_action(session, device_id, tool, params, goal)

        before = self._latest_observation(device_id, identity)
        args: dict[str, Any] = {
            "tool": tool,
            "params": params,
            "goal": goal,
            # Android owns this source constant and the authority decision.
            "source": "PC_CODEX",
        }
        if identity:
            args["sessionId"] = identity["sessionId"]
            args["displayId"] = identity["displayId"]
        if payload.get("livePhone") is True:
            args["livePhone"] = True
        if expected:
            args["currentObservationId"] = expected
        execution = self._request(session, "action.execute", args)
        after_raw = self._observe_after_action(session, before, tool, execution, identity)
        if identity:
            after_raw.setdefault("sessionId", identity["sessionId"])
            after_raw.setdefault("displayId", identity["displayId"])
        after = _compact_observation(after_raw)
        after_id = str(after_raw.get("observationId") or "")
        self._append(device_id, {
            "kind": "action",
            "at": now_ms(),
            "tool": tool,
            "beforeObservationId": expected or (before or {}).get("observationId"),
            "afterObservationId": after_id,
        }, identity)
        self._append(device_id, {
            "kind": "observation",
            "at": now_ms(),
            "observationId": after_id,
            "pageKey": after_raw.get("pageKey"),
            "package": after_raw.get("package"),
        }, identity)
        parsed_execution = extract_android_execution(execution)
        if parsed_execution is None:
            execution_ok = False
            execution_error_class = "PROTOCOL_MISMATCH"
        else:
            execution_ok = bool(parsed_execution.get("ok"))
            execution_error_class = (
                None if execution_ok else android_execution_error_class(parsed_execution)
            )
            if not execution_ok:
                layer2_error = layer2_error_from_execution(parsed_execution)
                if layer2_error is not None:
                    raise layer2_error
        android_execution = safe_android_execution(execution)
        android_verification = execution.get("verification") if isinstance(execution, dict) else None
        if not isinstance(android_verification, dict) and isinstance(parsed_execution, dict):
            nested = parsed_execution.get("verification")
            android_verification = nested if isinstance(nested, dict) else None
        verification_status = (
            str(android_verification.get("status") or "UNKNOWN").upper()
            if isinstance(android_verification, dict)
            else "MISSING"
        )
        semantic_success_claimed = (
            android_verification.get("semanticSuccessClaimed") is not False
            if isinstance(android_verification, dict)
            else False
        )
        page_changed_status = verification_status in {"PAGE_CHANGED", "PAGECHANGED"}
        # Seeing a goal label that was already present cannot prove the click did anything.
        already_on_page = False
        already_on_home = (
            execution_ok
            and bool(after_id)
            and tool == "phone.home"
            and _home_launcher_surface(after_raw, after)
        )
        verification_passed = (
            execution_ok
            and bool(after_id)
            and (
                already_on_page
                or already_on_home
                or (
                    isinstance(android_verification, dict)
                    and android_verification.get("ok") is True
                    and (
                        verification_status in {"PASSED", "NOT_REQUIRED", "ALREADY_ON_PAGE"}
                        or page_changed_status
                        or android_verification.get("pageChanged") is True
                    )
                    and semantic_success_claimed
                )
            )
        )
        error = None
        if not execution_ok:
            if execution_error_class == "PROTOCOL_MISMATCH":
                error = canonical_error(
                    "PROTOCOL_MISMATCH",
                    "PROTOCOL",
                    "Android execution result did not match the capability protocol.",
                )
                execution_status = "protocol_mismatch"
            elif execution_error_class == "POLICY_DENIED":
                error = canonical_error(
                    "POLICY_DENIED",
                    "POLICY",
                    "Android policy denied the action.",
                )
                execution_status = "android_failed"
            elif execution_error_class == "STALE_OBSERVATION":
                error = canonical_error(
                    "STALE_OBSERVATION",
                    "PROTOCOL",
                    "Android rejected stale observation evidence.",
                    retryable=True,
                )
                execution_status = "android_failed"
            else:
                error = canonical_error(
                    execution_error_class or "EXECUTION_FAILED",
                    "EXECUTION",
                    "Android PhoneToolExecutor reported execution failure.",
                )
                execution_status = "android_failed"
        elif not verification_passed:
            error = canonical_error(
                "VERIFICATION_FAILED",
                "VERIFICATION",
                "The authoritative after-state did not verify the action.",
                retryable=True,
            )
            execution_status = "android_succeeded"
        else:
            execution_status = "android_succeeded"
        overall_ok = execution_ok and verification_passed
        execution_layer = {
            "ok": execution_ok,
            "authoritative": True,
            "status": execution_status,
            "androidExecution": android_execution,
        }
        if error is not None and not execution_ok:
            execution_layer["error"] = error
        verification_layer = {
            # Android is the sole verification authority. A successful transport, executor
            # result, or fresh observation is evidence, but none of those alone proves that
            # the requested semantic after-state was reached.
            "ok": verification_passed,
            "passed": verification_passed,
            "authoritative": True,
            "status": verification_status,
            "code": android_verification.get("code") if isinstance(android_verification, dict) else None,
            "semantic_success_claimed": semantic_success_claimed,
            "authority": "ANDROID_CANONICAL",
            "before_observation_id": expected or (before or {}).get("observationId"),
            "after_observation_id": after_id,
            "after_page_key": after_raw.get("pageKey"),
        }
        if already_on_home:
            verification_layer["basis"] = str(
                (android_verification or {}).get("basis") or "ALREADY_ON_HOME"
            )
        elif already_on_page:
            verification_layer["basis"] = str(
                (android_verification or {}).get("basis") or "ALREADY_ON_PAGE"
            )
        if error is not None and execution_ok and not verification_passed:
            verification_layer["error"] = error
        return {
            **self._operation_context(session, device_id, "act"),
            "protocol_version": CAPABILITY_PROTOCOL_VERSION,
            "capability_id": tool,
            "ok": overall_ok,
            "transport": {"ok": True, "status": "connected"},
            "execution": execution_layer,
            "verification": verification_layer,
            "android_execution": android_execution,
            "after": after,
            "afterState": self._after_state(after_raw),
            "error": error,
        }

    def _workspace_action(
        self,
        session: DeviceSession,
        device_id: str,
        tool: str,
        params: dict[str, Any],
        goal: str,
    ) -> dict[str, Any]:
        op = "switch" if tool == "phone.workspace_switch" else tool.split(".", 1)[-1]
        if op not in LAYER2_OPS:
            raise DesktopRuntimeError(RuntimeErrorCode.CAPABILITY_UNAVAILABLE, "Requested phone capability is unavailable.")
        if self._layer2 is None:
            raise DesktopRuntimeError(RuntimeErrorCode.CAPABILITY_UNAVAILABLE, "Layer 2 workspace service is unavailable.")
        public = self._layer2.command(device_id, op, params, tool=tool, goal=goal)
        response = {
            **self._operation_context(session, device_id, "act"),
            "protocol_version": CAPABILITY_PROTOCOL_VERSION,
            "capability_id": tool,
            "ok": True,
            "transport": {"ok": True, "status": "connected"},
            "execution": {
                "ok": True,
                "authoritative": True,
                "status": "android_succeeded",
                "androidExecution": {"ok": True},
            },
            "verification": {
                "ok": True,
                "passed": True,
                "authoritative": True,
                "status": "PASSED",
                "authority": "ANDROID_CANONICAL",
                "basis": "WORKSPACE_ENGINE_POSTCONDITION",
            },
            "android_execution": {"ok": True},
            "after": {},
            "afterState": {},
            "error": None,
            "layer2": public,
        }
        for key in (
            "workspaceId",
            "workspaceGeneration",
            "holder",
            "workspaces",
            "armed",
            "gated",
            "verified",
            "next",
            "lockOwner",
        ):
            if key in public:
                response[key] = public[key]
        return response

    def _reject_mixed_planes(self, params: dict[str, Any], identity: dict[str, Any] | None) -> None:
        workspace_id = params.get("workspaceId")
        if workspace_id in (None, ""):
            return
        named = identity is not None and (
            identity.get("sessionId") != DEFAULT_FOREGROUND_SESSION_ID
            or identity.get("displayId") not in (None, 0)
        )
        if named:
            raise DesktopRuntimeError(
                RuntimeErrorCode.INVALID_REQUEST,
                "Do not mix Layer 2 workspaceId with a named session or non-zero displayId.",
            )

    def _enforce_layer2_mutate_lock(
        self,
        device_id: str,
        tool: str,
        params: dict[str, Any],
        identity: dict[str, Any] | None,
    ) -> None:
        workspace_id = params.get("workspaceId")
        generation = params.get("workspaceGeneration")
        has_id = workspace_id not in (None, "")
        has_generation = generation is not None
        if has_id or has_generation:
            if not isinstance(workspace_id, str) or not WORKSPACE_ID.fullmatch(workspace_id):
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "workspaceId is invalid.")
            if type(generation) is not int or generation < 0:
                raise DesktopRuntimeError(
                    RuntimeErrorCode.INVALID_REQUEST,
                    "workspaceGeneration is required with workspaceId.",
                )
        if tool not in LAYER2_MUTATING_PHONE_TOOLS:
            return
        lease = self._layer2.lease(device_id) if self._layer2 is not None else None
        if not lease:
            return
        named = identity is not None and (
            identity.get("sessionId") != DEFAULT_FOREGROUND_SESSION_ID
            or identity.get("displayId") not in (None, 0)
        )
        if named:
            raise DesktopRuntimeError(
                RuntimeErrorCode.INVALID_REQUEST,
                "Do not mix Layer 2 workspaceId with a named session or non-zero displayId.",
            )
        if not has_id or not has_generation:
            raise DesktopRuntimeError(
                RuntimeErrorCode.MUTATE_LOCK,
                "MUTATE_LOCK: switch and use the current workspaceId/workspaceGeneration",
            )
        if workspace_id != lease.get("workspaceId"):
            raise DesktopRuntimeError(
                RuntimeErrorCode.MUTATE_LOCK,
                "MUTATE_LOCK: switch and use the current workspaceId/workspaceGeneration",
            )
        if generation != lease.get("generation"):
            raise DesktopRuntimeError(
                RuntimeErrorCode.STALE_WORKSPACE,
                "STALE_WORKSPACE: observe again after switch and pass the current workspaceGeneration",
            )

    def _observe_after_action(
        self,
        session: DeviceSession,
        before: dict[str, Any] | None,
        tool: str,
        execution: dict[str, Any],
        identity: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        observe_args = dict(identity or {})
        after = self._request(session, "observe.semantic", observe_args)
        if tool not in PAGE_TRANSITION_TOOLS or before is None:
            return after
        if tool == "phone.home" and _home_launcher_surface(after):
            return after
        verification = execution.get("verification")
        if (
            isinstance(verification, dict)
            and verification.get("ok") is True
            and str(verification.get("status") or "").upper() in {"PASSED", "NOT_REQUIRED"}
            and verification.get("semanticSuccessClaimed") is not False
        ):
            return after
        deadline = time.monotonic() + self._after_action_timeout_seconds
        while self._same_page(before, after) and time.monotonic() < deadline:
            time.sleep(self._after_action_poll_seconds)
            after = self._request(session, "observe.semantic", observe_args)
        return after

    @staticmethod
    def _same_page(before: dict[str, Any], after: dict[str, Any]) -> bool:
        return (
            before.get("package") == after.get("package")
            and before.get("pageKey") == after.get("pageKey")
        )

    def screenshot(self, device_id: str, *, profile: str = "thumbnail") -> dict[str, Any]:
        """Return a bounded per-device artifact reference, never frame bytes or a hidden default phone."""
        session = self.fleet.get(device_id)
        if profile not in {"thumbnail", "focus", "live-phone"}:
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "Unknown screenshot profile.")
        if str(getattr(getattr(session, "adb_device", None), "state", "") or "") != "device":
            return self._screenshot_unavailable(session, device_id, "SCREENSHOT_USB_UNAVAILABLE", "USB authorization is required for screenshots.")
        if self._snapshot is None:
            return self._screenshot_unavailable(session, device_id, "SCREENSHOT_CAPABILITY_UNAVAILABLE", "Screenshot capture is unavailable.")
        try:
            capture = self._snapshot(device_id, profile)
        except DesktopRuntimeError as exc:
            return self._screenshot_unavailable(session, device_id, "SCREENSHOT_CAPTURE_FAILED", exc.safe_message)
        except Exception:
            return self._screenshot_unavailable(session, device_id, "SCREENSHOT_CAPTURE_FAILED", "Screenshot capture failed safely.")
        path = str(capture.get("filePath") or "")
        if not path:
            return self._screenshot_unavailable(session, device_id, "SCREENSHOT_ARTIFACT_MISSING", "Screenshot capture returned no artifact.")
        artifact = {
            "kind": "LOCAL_FILE",
            "reference": path,
            "mediaType": str(capture.get("codec") or "image/jpeg"),
            "width": capture.get("width"),
            "height": capture.get("height"),
            "timestampMs": capture.get("timestampMs"),
        }
        return {
            **self._operation_context(session, device_id, "screenshot"),
            # Retained for existing batch consumers; new callers should read the bounded artifact.
            "filePath": path,
            "codec": artifact["mediaType"],
            "width": artifact["width"],
            "height": artifact["height"],
            "timestampMs": artifact["timestampMs"],
            "artifact": artifact,
            "screenshot": {"available": True, "reasonCode": "SCREENSHOT_AVAILABLE", "artifact": artifact},
        }

    def debug_bundle(self, device_id: str, *, expected: str = "", goal: str = "") -> dict[str, Any]:
        session = self._paired(device_id)
        result = self._request(session, "debug.snapshot", {})
        return {**self._operation_context(session, device_id, "debug"), "expected": expected[:500], "goal": goal[:500], "snapshot": result}

    def teach_start(self, device_id: str, *, goal: str = "") -> dict[str, Any]:
        session = self._paired(device_id)
        result = self._request(session, "teach.start", {"goal": goal[:1000]})
        return {**self._operation_context(session, device_id, "teach_start"), "teaching": result}

    def teach_status(self, device_id: str) -> dict[str, Any]:
        session = self._paired(device_id)
        return {**self._operation_context(session, device_id, "teach_status"), "teaching": self._request(session, "teach.status", {})}

    def teach_stop(self, device_id: str, *, compile_for_review: bool = True) -> dict[str, Any]:
        session = self._paired(device_id)
        result = self._request(session, "teach.stop", {"compileForReview": bool(compile_for_review)})
        return {**self._operation_context(session, device_id, "teach_stop"), "teaching": result}

    def _screenshot_unavailable(self, session: DeviceSession, device_id: str, reason_code: str, message: str) -> dict[str, Any]:
        return {
            **self._operation_context(session, device_id, "screenshot", available=False, reason_code=reason_code),
            "artifact": None,
            "screenshot": {"available": False, "reasonCode": reason_code, "message": message[:240], "artifact": None},
        }

    def _operation_context(
        self,
        session: DeviceSession,
        device_id: str,
        operation: str,
        *,
        available: bool = True,
        reason_code: str = "CAPABILITY_AVAILABLE",
    ) -> dict[str, Any]:
        public = self._safe_public(session)
        return {
            "device_id": device_id,
            "deviceId": device_id,
            "operation": operation,
            "deviceContract": {"version": DEVICE_OPERATION_CONTRACT_VERSION, "targetDeviceId": device_id},
            "capability": {"available": available, "reasonCode": reason_code},
            "deviceHealth": (public.get("health") if public else None),
        }

    @staticmethod
    def _safe_public(session: DeviceSession) -> dict[str, Any]:
        try:
            return enrich_device_public(session)
        except Exception:
            return {}

    @staticmethod
    def _after_state(observation: dict[str, Any]) -> dict[str, Any]:
        return {
            "observationId": observation.get("observationId"),
            "pageKey": observation.get("pageKey"),
            "package": observation.get("package"),
            "activity": observation.get("activity"),
            "accessibilityFingerprint": observation.get("accessibilityFingerprint"),
        }

    def _paired(self, device_id: str) -> DeviceSession:
        session = self.fleet.get(device_id)
        if not session.credential:
            raise DesktopRuntimeError(RuntimeErrorCode.PAIRING_REQUIRED, "Pair this phone before agent access.")
        return session

    def _request(self, session: DeviceSession, op: str, args: dict[str, Any]) -> dict[str, Any]:
        try:
            value = session.bridge().request(op, args, request_id=secrets.token_urlsafe(18))
            return value if isinstance(value, dict) else {"value": value}
        except BridgeOperationError as exc:
            if exc.code == "PHONE_APP_BUSY":
                raise phone_errors.busy() from exc
            mapping = {
                "AUTH_REJECTED": RuntimeErrorCode.AUTH_REJECTED,
                "CAPABILITY_UNAVAILABLE": RuntimeErrorCode.CAPABILITY_UNAVAILABLE,
                "STALE_OBSERVATION": RuntimeErrorCode.STALE_OBSERVATION,
                "POLICY_DENIED": RuntimeErrorCode.POLICY_DENIED,
                "PROTOCOL_MISMATCH": RuntimeErrorCode.PROTOCOL_MISMATCH,
                "AGENT_CONTEXT_TRUNCATION": RuntimeErrorCode.AGENT_CONTEXT_TRUNCATION,
                "HUMAN_HAS_CONTROL": RuntimeErrorCode.HUMAN_HAS_CONTROL,
                "PHONE_LOCKED": RuntimeErrorCode.PHONE_LOCKED,
                "BACKGROUND_MODE_UNAVAILABLE": RuntimeErrorCode.BACKGROUND_MODE_UNAVAILABLE,
                "STALE_SESSION": RuntimeErrorCode.STALE_SESSION,
                "FOREGROUND_REQUIRED": RuntimeErrorCode.FOREGROUND_REQUIRED,
                "GATE": RuntimeErrorCode.GATE,
                "MUTATE_LOCK": RuntimeErrorCode.MUTATE_LOCK,
                "TARGET_MISMATCH": RuntimeErrorCode.TARGET_MISMATCH,
                "STALE_WORKSPACE": RuntimeErrorCode.STALE_WORKSPACE,
                "QUEUE_EMPTY": RuntimeErrorCode.QUEUE_EMPTY,
                "USER_UNVERIFIED": RuntimeErrorCode.USER_UNVERIFIED,
            }
            mapped = parse_layer2_error(exc.code, str(exc))
            raise DesktopRuntimeError(
                mapping.get(mapped or exc.code, RuntimeErrorCode.CAPABILITY_UNAVAILABLE),
                f"Android Gateway rejected {op}.",
                retryable=phone_errors.retryable(mapped or exc.code),
            ) from exc
        except (BridgeDisconnectedError, BridgeProtocolError) as exc:
            raise phone_errors.transport(exc) from exc

    def _require_ai_ownership(self, session: DeviceSession, request_ai_control: bool) -> None:
        owner = getattr(session, "input_owner", None)
        if owner != "HUMAN":
            return
        if request_ai_control:
            if getattr(session, "screen_awake", True) is False:
                raise DesktopRuntimeError(
                    RuntimeErrorCode.PHONE_LOCKED,
                    "Cannot take AI ownership while the phone is locked or asleep.",
                )
            session.input_owner = "AI"
            return
        raise DesktopRuntimeError(RuntimeErrorCode.HUMAN_HAS_CONTROL, HUMAN_HAS_CONTROL_HINT, retryable=True)

    def _session_summaries(self, session: DeviceSession, bridge_status: dict[str, Any]) -> list[dict[str, Any]]:
        raw_items = bridge_status.get("executionSessions") if isinstance(bridge_status, dict) else None
        summaries: list[dict[str, Any]] = []
        if isinstance(raw_items, list):
            for item in raw_items:
                if not isinstance(item, dict):
                    continue
                session_id = str(item.get("sessionId") or "").strip()
                if not session_id:
                    continue
                executable = item.get("executable") is not False
                foreground = session_id == DEFAULT_FOREGROUND_SESSION_ID
                state = str(item.get("state") or "").upper()
                summaries.append({
                    "sessionId": session_id,
                    "displayId": item.get("displayId"),
                    "state": item.get("state"),
                    "inputOwner": item.get("inputOwner"),
                    "executable": executable,
                    "kind": "FOREGROUND" if foreground else "BACKGROUND",
                    "readOnly": (not executable) or state in _SHARE_READONLY_STATES,
                    "targetPackage": item.get("targetPackage") or item.get("package"),
                })
        if not any(item.get("sessionId") == DEFAULT_FOREGROUND_SESSION_ID for item in summaries):
            summaries.insert(0, {
                "sessionId": DEFAULT_FOREGROUND_SESSION_ID,
                "displayId": 0,
                "state": "FOREGROUND",
                "inputOwner": getattr(session, "input_owner", "HUMAN"),
                "executable": True,
                "kind": "FOREGROUND",
                "readOnly": False,
                "targetPackage": None,
            })
        return summaries

    def _execution_identity(self, payload: dict[str, Any] | None) -> dict[str, Any] | None:
        try:
            return parse_execution_identity(payload)
        except ValueError as exc:
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, str(exc)) from exc

    def _latest_observation(
        self,
        device_id: str,
        identity: dict[str, Any] | None = None,
    ) -> dict[str, Any] | None:
        key = history_key(device_id, identity)
        with self._lock:
            for item in reversed(self._history[key]):
                if item.get("kind") == "observation":
                    return item
        return None

    def _append(
        self,
        device_id: str,
        value: dict[str, Any],
        identity: dict[str, Any] | None = None,
    ) -> None:
        key = history_key(device_id, identity)
        with self._lock:
            self._history[key].append(value)
