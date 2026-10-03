from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
import secrets
import threading
import time
from typing import Any

from ..adb.device import CYCLONE_PACKAGE
from ..desktop_runtime.models import DesktopRuntimeError, RuntimeErrorCode
from ..vmos import PHONE_MUTATION_ENGINE, VMOS_ARCHITECTURE


SECRET_FIELD_NAMES = frozenset({
    "connectKey",
    "connect_key",
    "accessKey",
    "access_key",
    "secretAccessKey",
    "secret_access_key",
    "vmosApiKey",
    "vmos_api_key",
    "controlApiKey",
    "sshPassword",
    "password",
    "privateKey",
    "askpass",
})
HANDOFF_PUBLIC_FIELDS = (
    "DEVICE_ID",
    "SESSION_ID",
    "SESSION_TOKEN",
    "CONTROL_API",
    "MOBILE",
    "ADB",
    "GOAL",
    "NOTES",
)
SESSION_TTL_SECONDS = 7200
SCREENSHOT_TTL_SECONDS = 180


@dataclass
class CloudSession:
    session_id: str
    session_token: str
    device_id: str
    serial: str | None
    expires_at: float
    last_observation_id: str | None = None


@dataclass
class StoredScreenshot:
    data: bytes
    media_type: str
    expires_at: float


@dataclass
class CloudControlService:
    runtime: Any
    gateway_token: str
    public_base: str = ""
    _lock: threading.RLock = field(default_factory=threading.RLock)
    _sessions: dict[str, CloudSession] = field(default_factory=dict)
    _tokens: dict[str, str] = field(default_factory=dict)
    _shots: dict[str, StoredScreenshot] = field(default_factory=dict)

    def public_contract(self) -> dict[str, Any]:
        architecture = VMOS_ARCHITECTURE.public()
        return {
            "ok": True,
            "surface": "chatgpt-actions",
            "mutationEngine": PHONE_MUTATION_ENGINE,
            "handoffFields": list(HANDOFF_PUBLIC_FIELDS),
            "architecture": architecture,
            "auth": "session-token-header-or-bearer",
            "sessionHeader": "X-Cyclone-Session-Token",
            "localBase": self.public_base or "",
        }

    def mint(self, *, device_id: str | None = None, serial: str | None = None, ttl_seconds: int = SESSION_TTL_SECONDS) -> dict[str, Any]:
        session = self._resolve_device(device_id=device_id, serial=serial)
        ttl = max(60, min(int(ttl_seconds or SESSION_TTL_SECONDS), SESSION_TTL_SECONDS))
        record = CloudSession(
            session_id=secrets.token_hex(16),
            session_token=secrets.token_hex(20),
            device_id=session.device_id,
            serial=getattr(session, "serial", None),
            expires_at=time.time() + ttl,
        )
        with self._lock:
            self._sessions[record.session_id] = record
            self._tokens[record.session_token] = record.session_id
        return {
            "sessionId": record.session_id,
            "sessionToken": record.session_token,
            "deviceId": record.device_id,
            "ttlSeconds": ttl,
            "source": "control-api",
        }

    def authenticate(self, authorization: str | None) -> CloudSession | str:
        token = _bearer(authorization)
        if not token:
            raise DesktopRuntimeError(RuntimeErrorCode.AUTH_REJECTED, "A Cyclone session token is required.")
        if secrets.compare_digest(token, self.gateway_token):
            return "operator"
        with self._lock:
            session_id = self._tokens.get(token)
            record = self._sessions.get(session_id) if session_id else None
        if record is None or record.expires_at <= time.time():
            raise DesktopRuntimeError(RuntimeErrorCode.AUTH_REJECTED, "Session token is missing or expired.")
        return record

    def list_devices(self, principal: CloudSession | str) -> dict[str, Any]:
        if isinstance(principal, CloudSession):
            devices = [self._device_card(principal.device_id)]
        else:
            devices = [self._device_card(item["deviceId"]) for item in self.runtime.fleet.list_public()]
        return {"devices": [item for item in devices if item is not None]}

    def status(self, principal: CloudSession | str, device_id: str, session_id: str | None = None) -> dict[str, Any]:
        record = self._require_device(principal, device_id)
        device = self._resolve_device(device_id=record.device_id)
        adb = str(getattr(getattr(device, "adb_device", None), "state", "") or "missing")
        mobile_installed, mobile_running = self._mobile_state(device)
        trust = self._trust(device.device_id)
        paired = getattr(device, "credential", None) is not None
        message = "Ready for ChatGPT Actions." if paired and mobile_running and adb == "device" else self._status_message(
            adb, mobile_installed, mobile_running, paired, trust,
        )
        return strip_secrets({
            "deviceId": device.device_id,
            "sessionId": record.session_id if isinstance(record, CloudSession) else (session_id or ""),
            "adb": _adb_label(adb),
            "mobileInstalled": mobile_installed,
            "mobileRunning": mobile_running,
            "gatewayReady": paired,
            "trustReady": bool(trust.get("trusted") or trust.get("sessionReady")),
            "mutationEngine": PHONE_MUTATION_ENGINE,
            "message": message,
        })

    def observe(self, principal: CloudSession | str, device_id: str, *, session_id: str | None = None, include_ui_summary: bool = True) -> dict[str, Any]:
        record = self._require_device(principal, device_id)
        payload = {"request_ai_control": True, "sessionId": session_id or getattr(record, "session_id", None)}
        observation = self.runtime.agent.observe(
            record.device_id if isinstance(record, CloudSession) else device_id,
            mode="compact",
            include_screenshot=True,
            payload=payload,
        )
        witness = observation.get("witness") or {}
        observation_id = str(witness.get("observation_id") or "")
        if isinstance(record, CloudSession) and observation_id:
            with self._lock:
                record.last_observation_id = observation_id
        after = observation.get("afterState") or observation.get("observation") or {}
        summary = ""
        if include_ui_summary:
            summary = str(after.get("pageSummary") or after.get("pageText") or after.get("title") or "")[:800]
        screenshot_url = self._publish_screenshot(observation.get("screenshot"))
        return strip_secrets({
            "deviceId": device_id,
            "sessionId": session_id or (record.session_id if isinstance(record, CloudSession) else ""),
            "screenshotUrl": screenshot_url,
            "summary": summary,
            "foregroundPackage": after.get("package") or after.get("foregroundPackage"),
            "observationId": observation_id,
            "mutationEngine": PHONE_MUTATION_ENGINE,
        })

    def tap(self, principal: CloudSession | str, device_id: str, body: dict[str, Any]) -> dict[str, Any]:
        x, y = self._normalized_point(principal, device_id, body.get("x"), body.get("y"), body.get("normalized"))
        return self._mutate(principal, device_id, "phone.click", {"x": x, "y": y}, body)

    def swipe(self, principal: CloudSession | str, device_id: str, body: dict[str, Any]) -> dict[str, Any]:
        x1, y1 = self._normalized_point(principal, device_id, body.get("x1"), body.get("y1"), True)
        x2, y2 = self._normalized_point(principal, device_id, body.get("x2"), body.get("y2"), True)
        return self._mutate(
            principal,
            device_id,
            "phone.swipe",
            {"x1": x1, "y1": y1, "x2": x2, "y2": y2, "durationMs": int(body.get("durationMs") or 300)},
            body,
        )

    def type_text(self, principal: CloudSession | str, device_id: str, body: dict[str, Any]) -> dict[str, Any]:
        return self._mutate(principal, device_id, "phone.type", {"text": str(body.get("text") or "")}, body)

    def launch_app(self, principal: CloudSession | str, device_id: str, body: dict[str, Any]) -> dict[str, Any]:
        params = {"package": str(body.get("packageName") or "")}
        if body.get("activity"):
            params["activity"] = str(body["activity"])
        return self._mutate(principal, device_id, "phone.open_app", params, body)

    def press_key(self, principal: CloudSession | str, device_id: str, body: dict[str, Any]) -> dict[str, Any]:
        key = str(body.get("key") or "").lower()
        capability = {
            "back": "phone.back",
            "home": "phone.home",
            "recents": "phone.recents",
            "enter": "phone.key",
        }.get(key)
        if not capability:
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "Unsupported key")
        params = {} if key != "enter" else {"key": "ENTER"}
        return self._mutate(principal, device_id, capability, params, body)

    def screenshot(self, shot_id: str) -> StoredScreenshot:
        self._prune()
        with self._lock:
            shot = self._shots.get(shot_id)
        if shot is None:
            raise DesktopRuntimeError(RuntimeErrorCode.DEVICE_NOT_FOUND, "Screenshot expired or missing.")
        return shot

    def _resolve_device(self, *, device_id: str | None = None, serial: str | None = None):
        if device_id:
            return self.runtime.fleet.get(device_id)
        if serial:
            device = self.runtime.fleet.find_by_serial(serial)
            if device is not None:
                return device
            # The VMOS SSH/ADB tunnel may have become ready after the last fleet scan.
            # Give the desktop runtime one chance to ingest the freshly connected serial.
            scan = getattr(self.runtime.fleet, "scan", None)
            if callable(scan):
                scan()
                device = self.runtime.fleet.find_by_serial(serial)
                if device is not None:
                    return device
        raise DesktopRuntimeError(RuntimeErrorCode.DEVICE_NOT_FOUND, "VMOS ADB device is not in Cyclone fleet yet.")

    def _require_device(self, principal: CloudSession | str, device_id: str) -> CloudSession | Any:
        if isinstance(principal, CloudSession):
            if principal.device_id != device_id:
                raise DesktopRuntimeError(RuntimeErrorCode.AUTH_REJECTED, "Session token does not match this device.")
            return principal
        return self._resolve_device(device_id=device_id)

    def _device_card(self, device_id: str) -> dict[str, Any] | None:
        try:
            device = self.runtime.fleet.get(device_id)
        except DesktopRuntimeError:
            return None
        adb = str(getattr(getattr(device, "adb_device", None), "state", "") or "missing")
        installed, running = self._mobile_state(device)
        return strip_secrets({
            "deviceId": device.device_id,
            "label": (device.public().get("name") or device.device_id),
            "adb": _adb_label(adb),
            "mobileInstalled": installed,
            "mobileRunning": running,
        })

    def _mobile_state(self, device) -> tuple[bool, bool]:
        try:
            installed = bool(device.adb.shell("pm", "path", CYCLONE_PACKAGE, timeout=4).strip())
        except Exception:
            installed = False
        if not installed:
            return False, False
        try:
            running = bool(device.adb.shell("pidof", CYCLONE_PACKAGE, timeout=4).strip())
        except Exception:
            running = False
        return installed, running

    def _trust(self, device_id: str) -> dict[str, Any]:
        try:
            value = self.runtime.trust.status(device_id)
            return value if isinstance(value, dict) else {}
        except Exception:
            return {}

    def _status_message(self, adb: str, installed: bool, running: bool, paired: bool, trust: dict[str, Any]) -> str:
        if adb != "device":
            return f"ADB is {_adb_label(adb)}. Re-run Sync fleet."
        if not installed:
            return "Cyclone Mobile is missing. Install it in the VMOS phone, then Sync fleet."
        if not running:
            return "Cyclone Mobile is installed but not running. Start it, then Sync fleet."
        if not paired:
            return "Cyclone Mobile is not paired with this One gateway. Finish PC Gateway pairing."
        if not bool(trust.get("trusted") or trust.get("sessionReady")):
            return "Cyclone Mobile trust is not ready. Finish pairing/trust, then Sync fleet."
        return "Ready."

    def _normalized_point(self, principal: CloudSession | str, device_id: str, x: Any, y: Any, normalized: Any) -> tuple[int, int]:
        record = self._require_device(principal, device_id)
        device = self._resolve_device(device_id=record.device_id if isinstance(record, CloudSession) else device_id)
        try:
            fx = float(x)
            fy = float(y)
        except (TypeError, ValueError) as exc:
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "Tap/swipe coordinates must be numeric.") from exc
        if normalized or (0 <= fx <= 1 and 0 <= fy <= 1):
            width = int(getattr(device, "display_width", 1080) or 1080)
            height = int(getattr(device, "display_height", 1920) or 1920)
            fx *= width
            fy *= height
        return max(0, int(round(fx))), max(0, int(round(fy)))

    def _mutate(
        self,
        principal: CloudSession | str,
        device_id: str,
        capability_id: str,
        params: dict[str, Any],
        body: dict[str, Any],
    ) -> dict[str, Any]:
        record = self._require_device(principal, device_id)
        target_device = record.device_id if isinstance(record, CloudSession) else device_id
        payload = {
            "capability_id": capability_id,
            "params": params,
            "goal": str(body.get("goal") or "ChatGPT cloud control")[:500],
            "request_ai_control": True,
            "sessionId": body.get("sessionId") or (record.session_id if isinstance(record, CloudSession) else None),
        }
        if isinstance(record, CloudSession) and record.last_observation_id:
            payload["observation_id"] = record.last_observation_id
        result = self.runtime.agent.action(target_device, payload)
        clean = strip_secrets(result if isinstance(result, dict) else {"result": result})
        clean.update({"deviceId": target_device, "sessionId": payload.get("sessionId"), "mutationEngine": PHONE_MUTATION_ENGINE})
        return clean

    def _publish_screenshot(self, raw: Any) -> str:
        if not raw:
            return ""
        data: bytes | None = None
        media_type = "image/png"
        if isinstance(raw, bytes):
            data = raw
        elif isinstance(raw, dict):
            maybe = raw.get("bytes") or raw.get("data")
            if isinstance(maybe, bytes):
                data = maybe
            media_type = str(raw.get("media_type") or raw.get("mime") or media_type)
        if not data:
            return ""
        shot_id = secrets.token_urlsafe(18)
        with self._lock:
            self._shots[shot_id] = StoredScreenshot(data=data, media_type=media_type, expires_at=time.time() + SCREENSHOT_TTL_SECONDS)
        base = self.public_base.rstrip("/") if self.public_base else "/cloud"
        return f"{base}/v1/screenshots/{shot_id}"

    def _prune(self) -> None:
        now = time.time()
        with self._lock:
            expired_sessions = [sid for sid, row in self._sessions.items() if row.expires_at <= now]
            for sid in expired_sessions:
                token = self._sessions[sid].session_token
                self._sessions.pop(sid, None)
                self._tokens.pop(token, None)
            for key in [key for key, row in self._shots.items() if row.expires_at <= now]:
                self._shots.pop(key, None)


def strip_secrets(value: Any) -> Any:
    if isinstance(value, dict):
        return {key: strip_secrets(item) for key, item in value.items() if key not in SECRET_FIELD_NAMES}
    if isinstance(value, list):
        return [strip_secrets(item) for item in value]
    return value


def _bearer(authorization: str | None) -> str:
    if not authorization:
        return ""
    prefix, _, token = authorization.partition(" ")
    if prefix.lower() != "bearer" or not token:
        return ""
    return token.strip()


def _adb_label(value: str) -> str:
    text = value.lower()
    if text == "device":
        return "device"
    if "unauthorized" in text:
        return "unauthorized"
    if "offline" in text:
        return "offline"
    return "missing"
