from __future__ import annotations

import math
import re
import secrets
from typing import Any

from ..cyclone_bridge.client import BridgeDisconnectedError, BridgeOperationError, BridgeProtocolError
from .fleet import DeviceFleetManager, DeviceSession
from .models import DesktopRuntimeError, RuntimeErrorCode

MANUAL_KINDS = frozenset({
    "tap", "swipe", "back", "home", "scroll_up", "scroll_down", "text", "wake",
    "yield_ai", "take_human",
})
HUMAN_INPUT_KINDS = frozenset({"tap", "swipe", "back", "home", "scroll_up", "scroll_down", "text"})
YIELD_HINT = (
    "Companion currently owns input. Click Give control to AI, "
    "or retry the MCP action with request_ai_control=true. A locked phone is not stolen."
)
_SENSITIVE_HINT = re.compile(r"(?i)(password|passcode|otp|one.?time|verification.?code|api.?key|bearer|token|secret|cvv|pin)\s*[:=]?")
_JWT = re.compile(r"^[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}$")
_LONG_SECRET = re.compile(r"^[A-Za-z0-9_+\-/=]{32,}$")
_OTPISH = re.compile(r"^\s*\d{4,8}\s*$")


def clipboard_looks_sensitive(text: str) -> bool:
    value = text.strip()
    if not value:
        return False
    return bool(_SENSITIVE_HINT.search(value) or _JWT.fullmatch(value) or _LONG_SECRET.fullmatch(value) or _OTPISH.fullmatch(value))


class ManualControlService:
    def __init__(self, fleet: DeviceFleetManager):
        self.fleet = fleet

    def execute(self, device_id: str, payload: dict[str, Any]) -> dict[str, Any]:
        session = self._paired(device_id)
        kind = str(payload.get("kind") or "")
        if kind not in MANUAL_KINDS:
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "Unsupported manual control kind.")
        if kind == "yield_ai":
            return self.set_owner(device_id, "AI")
        if kind == "take_human":
            return self.set_owner(device_id, "HUMAN")
        if kind in HUMAN_INPUT_KINDS:
            session.input_owner = "HUMAN"
        args: dict[str, Any] = {"kind": kind, "source": "HUMAN_DESKTOP"}
        if kind == "tap":
            x, y = payload.get("x"), payload.get("y")
            if not isinstance(x, (int, float)) or not isinstance(y, (int, float)):
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "tap requires normalized x and y.")
            if not (math.isfinite(float(x)) and math.isfinite(float(y)) and 0.0 <= float(x) <= 1.0 and 0.0 <= float(y) <= 1.0):
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "tap coordinates must be in the 0.0..1.0 range.")
            args.update({"x": float(x), "y": float(y)})
        elif kind == "swipe":
            points = [payload.get(name) for name in ("x1", "y1", "x2", "y2")]
            if not all(isinstance(value, (int, float)) for value in points):
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "swipe requires normalized x1, y1, x2, and y2.")
            normalized = [float(value) for value in points]
            if not all(math.isfinite(value) and 0.0 <= value <= 1.0 for value in normalized):
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "swipe coordinates must be in the 0.0..1.0 range.")
            duration_ms = payload.get("duration_ms", 350)
            if not isinstance(duration_ms, int) or not 100 <= duration_ms <= 3000:
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "swipe duration must be between 100 and 3000 milliseconds.")
            args.update(dict(zip(("x1", "y1", "x2", "y2"), normalized)), durationMs=duration_ms)
        elif kind == "text":
            text = payload.get("text")
            if not isinstance(text, str) or not text:
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "text requires a non-empty string.")
            if len(text) > 4096:
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "text batch exceeds 4096 characters.")
            args["text"] = text
        elif kind == "wake":
            # This is a fixed Android key event, available only after Cyclone pairing. It wakes the
            # display but cannot unlock the phone, inject arbitrary shell, or grant authority.
            try:
                session.adb.shell("input", "keyevent", "224", timeout=4)
            except Exception as exc:
                raise DesktopRuntimeError(
                    RuntimeErrorCode.CAPABILITY_UNAVAILABLE,
                    "Cyclone could not wake the paired phone display.",
                    retryable=True,
                ) from exc
            self.fleet.mark_screen_awake(session)
            # Make the phone-side status immediately observe this authenticated PC session instead
            # of waiting for the next bounded fleet heartbeat.
            try:
                health = session.bridge().request(
                    "bridge.status",
                    {},
                    request_id=f"desktop-wake-health-{secrets.token_urlsafe(12)}",
                )
                self.fleet.record_bridge_status(session, health)
            except BridgeOperationError as exc:
                code = RuntimeErrorCode.AUTH_REJECTED if exc.code == "AUTH_REJECTED" else RuntimeErrorCode.CAPABILITY_UNAVAILABLE
                raise DesktopRuntimeError(code, f"Phone rejected wake health verification with {code.value}.") from exc
            except (BridgeDisconnectedError, BridgeProtocolError) as exc:
                raise DesktopRuntimeError(
                    RuntimeErrorCode.DEVICE_DISCONNECTED,
                    "Phone woke, but the authenticated Cyclone session is unavailable.",
                    retryable=True,
                ) from exc
            return {"deviceId": device_id, "kind": kind, "ok": True, "status": "DISPLAY_WAKE_REQUESTED"}
        return self._call(session, "manual.execute", args, result_shape={"deviceId": device_id, "kind": kind})

    def set_owner(self, device_id: str, owner: str) -> dict[str, Any]:
        session = self._paired(device_id)
        if owner == "AI" and not session.screen_awake:
            raise DesktopRuntimeError(
                RuntimeErrorCode.PHONE_LOCKED,
                "Cannot give AI control while the phone is locked or asleep.",
            )
        session.input_owner = "AI" if owner == "AI" else "HUMAN"
        status = "AI_HAS_CONTROL" if session.input_owner == "AI" else "HUMAN_HAS_CONTROL"
        kind = "yield_ai" if session.input_owner == "AI" else "take_human"
        return {
            "deviceId": device_id,
            "kind": kind,
            "ok": True,
            "inputOwner": session.input_owner,
            "status": status,
        }

    def _paired(self, device_id: str) -> DeviceSession:
        session = self.fleet.get(device_id)
        if not session.credential:
            raise DesktopRuntimeError(RuntimeErrorCode.PAIRING_REQUIRED, "Pair this phone before manual control.")
        return session

    @staticmethod
    def _call(session: DeviceSession, op: str, args: dict[str, Any], result_shape: dict[str, Any]) -> dict[str, Any]:
        try:
            result = session.bridge().request(op, args, request_id=secrets.token_urlsafe(18))
        except BridgeOperationError as exc:
            code = RuntimeErrorCode.AUTH_REJECTED if exc.code == "AUTH_REJECTED" else RuntimeErrorCode.CAPABILITY_UNAVAILABLE
            raise DesktopRuntimeError(code, f"Phone rejected manual control with {code.value}.") from exc
        except (BridgeDisconnectedError, BridgeProtocolError) as exc:
            raise DesktopRuntimeError(RuntimeErrorCode.DEVICE_DISCONNECTED, "Phone disconnected during manual control.", retryable=True) from exc
        safe = dict(result_shape)
        android_execution = result.get("androidExecution")
        if isinstance(android_execution, dict):
            safe["ok"] = android_execution.get("ok") is True
            if not safe["ok"]:
                safe["status"] = str(android_execution.get("errorCode") or "ACTION_NOT_PERFORMED")
        else:
            safe["ok"] = result.get("ok") is True
        if "status" in result and safe["ok"]:
            safe["status"] = result["status"]
        return safe


class ClipboardService:
    def __init__(self, fleet: DeviceFleetManager):
        self.fleet = fleet

    def capability(self, device_id: str) -> dict[str, Any]:
        session = self.fleet.get(device_id)
        if not session.credential:
            raise DesktopRuntimeError(RuntimeErrorCode.PAIRING_REQUIRED, "Pair this phone before clipboard use.")
        try:
            result = session.bridge().request("clipboard.get", {}, request_id=secrets.token_urlsafe(18))
        except BridgeOperationError as exc:
            if exc.code == "CAPABILITY_UNAVAILABLE":
                return {"deviceId": device_id, "mode": "PC_TO_PHONE", "reverseSync": "UNAVAILABLE", "content": None}
            raise DesktopRuntimeError(RuntimeErrorCode.CAPABILITY_UNAVAILABLE, "Clipboard capability is unavailable.") from exc
        except (BridgeDisconnectedError, BridgeProtocolError) as exc:
            raise DesktopRuntimeError(RuntimeErrorCode.DEVICE_DISCONNECTED, "Phone disconnected during clipboard check.", retryable=True) from exc
        return {
            "deviceId": device_id,
            "mode": str(result.get("mode") or "PC_TO_PHONE"),
            "reverseSync": str(result.get("reverseSync") or "UNAVAILABLE"),
            "content": None,
        }

    def set(self, device_id: str, text: str) -> dict[str, Any]:
        session = self.fleet.get(device_id)
        if not session.credential:
            raise DesktopRuntimeError(RuntimeErrorCode.PAIRING_REQUIRED, "Pair this phone before clipboard use.")
        if not isinstance(text, str) or not text:
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "clipboard text must be non-empty.")
        if len(text) > 16_384:
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "clipboard text exceeds 16384 characters.")
        if clipboard_looks_sensitive(text):
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "Sensitive-looking clipboard content is not synchronized.")
        try:
            result = session.bridge().request(
                "clipboard.set",
                {"text": text, "source": "HUMAN_DESKTOP"},
                request_id=secrets.token_urlsafe(18),
            )
        except BridgeOperationError as exc:
            code = RuntimeErrorCode.AUTH_REJECTED if exc.code == "AUTH_REJECTED" else RuntimeErrorCode.CAPABILITY_UNAVAILABLE
            raise DesktopRuntimeError(code, f"Phone rejected clipboard update with {code.value}.") from exc
        except (BridgeDisconnectedError, BridgeProtocolError) as exc:
            raise DesktopRuntimeError(RuntimeErrorCode.DEVICE_DISCONNECTED, "Phone disconnected during clipboard update.", retryable=True) from exc
        return {"deviceId": device_id, "updated": result.get("updated") is True, "mode": "PC_TO_PHONE"}
