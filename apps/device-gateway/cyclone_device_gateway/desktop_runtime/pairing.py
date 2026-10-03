from __future__ import annotations

from dataclasses import dataclass
import json
import os
from pathlib import Path
import re
import secrets
import threading
import time
from urllib.parse import urlencode

from ..cyclone_bridge.client import BridgeDisconnectedError, BridgeOperationError, BridgeProtocolError
from .diagnostics import FleetDiagnosticSupervisor
from .fleet import DeviceFleetManager, DeviceSession
from .models import DesktopRuntimeError, DeviceFleetState, RuntimeErrorCode

_CODE_RE = re.compile(r"^[A-Z]{4}$")


@dataclass
class PairingChallenge:
    challenge_id: str
    pc_nonce: str
    usb_session_id: str
    expires_at_ms: int
    attempts: int = 0


class PairingCoordinator:
    MAX_ATTEMPTS = 5
    MAX_LIFETIME_MS = 60_000
    BEGIN_TRANSPORT_ATTEMPTS = 3
    BEGIN_RETRY_DELAY_SECONDS = 0.2
    POST_PAIR_HEALTH_PROBES = 2
    POST_PAIR_HEALTH_DELAY_SECONDS = 0.25
    DIAGNOSTIC_SETTLE_DELAY_SECONDS = 0.4

    def __init__(self, fleet: DeviceFleetManager, diagnostics: FleetDiagnosticSupervisor | None = None):
        self.fleet = fleet
        self.live_diagnostics = diagnostics
        runtime_root = Path(os.getenv("CYCLONE_DEVICE_GATEWAY_RUNTIME", ".runtime/device-gateway")).expanduser().resolve()
        self.diagnostics_dir = runtime_root / "diagnostics"
        # FastAPI runs synchronous handlers in worker threads. Serialize pairing operations per
        # device so repeated code requests cannot install responses out of order, while a slow
        # phone still cannot block another phone.
        self._locks_guard = threading.Lock()
        self._device_locks: dict[str, threading.RLock] = {}

    def begin(self, device_id: str) -> dict:
        with self._device_lock(device_id):
            return self._begin_locked(device_id)

    def _begin_locked(self, device_id: str) -> dict:
        session = self._pairable(device_id)
        # Normal pairing writes only timeline markers. The live process log is already attached before
        # Pair is pressed; heavy dumpsys snapshots are reserved for an actual failure/process death so
        # diagnostics cannot create the ADB load they are trying to observe.
        live = self._mark_live(device_id, "pair.begin.pc_request")
        probe = self._probe_phone_readiness(session)
        self._mark_live(device_id, "pair.begin.preflight")
        pc_nonce = secrets.token_urlsafe(32)
        response = None
        for attempt in range(self.BEGIN_TRANSPORT_ATTEMPTS):
            try:
                response = session.bridge(token="").request_unauthenticated(
                    "pair.begin",
                    {"usbSessionId": session.usb_session_id, "pcNonce": pc_nonce},
                    request_id=secrets.token_urlsafe(18),
                )
                break
            except BridgeOperationError as exc:
                self._mark_live(device_id, "pair.begin.phone_rejected")
                diagnostics = self._capture_pairing_diagnostics(session, "pair.begin.phone_rejected")
                self._map_bridge_error(exc, self._readiness_hint(probe), diagnostics)
            except BridgeProtocolError as exc:
                self._mark_live(device_id, "pair.begin.invalid_response")
                diagnostics = self._capture_pairing_diagnostics(session, "pair.begin.invalid_response")
                raise DesktopRuntimeError(
                    RuntimeErrorCode.DEVICE_DISCONNECTED,
                    self._diagnostic_message(
                        "Cyclone pairing returned an invalid response. "
                        + self._readiness_hint(probe),
                        diagnostics,
                    ),
                    retryable=True,
                ) from exc
            except BridgeDisconnectedError as exc:
                self._mark_live(device_id, f"pair.begin.transport_disconnected.{attempt + 1}")
                if attempt + 1 >= self.BEGIN_TRANSPORT_ATTEMPTS:
                    diagnostics = self._capture_pairing_diagnostics(session, "pair.begin.transport")
                    raise DesktopRuntimeError(
                        RuntimeErrorCode.DEVICE_DISCONNECTED,
                        self._diagnostic_message(
                            "Cyclone pairing transport is unavailable. " + self._readiness_hint(probe),
                            diagnostics,
                        ),
                        retryable=True,
                    ) from exc
                time.sleep(self.BEGIN_RETRY_DELAY_SECONDS)
                try:
                    session.adb.ensure_bridge_forward(session.local_port)
                except Exception:
                    pass

        if response is None:
            diagnostics = self._capture_pairing_diagnostics(session, "pair.begin.no_response")
            raise DesktopRuntimeError(
                RuntimeErrorCode.DEVICE_DISCONNECTED,
                self._diagnostic_message(
                    "Cyclone pairing transport is unavailable. " + self._readiness_hint(probe),
                    diagnostics,
                ),
                retryable=True,
            )
        challenge_id = str(response.get("challengeId") or "")
        phone_expires_at = int(response.get("expiresAtMs") or 0)
        expires_in_ms = int(response.get("expiresInMs") or self.MAX_LIFETIME_MS)
        now = int(time.time() * 1000)
        # Never compare the phone's epoch directly with the PC's epoch. Real phones routinely differ
        # by a few seconds, which previously made a valid 60-second Android challenge look longer
        # than MAX_LIFETIME_MS and caused the PC to reject it before showing the code or QR. The phone
        # enforces phone_expires_at; the PC independently enforces the bounded relative lifetime.
        if (
            not challenge_id
            or phone_expires_at <= 0
            or expires_in_ms <= 0
            or expires_in_ms > self.MAX_LIFETIME_MS
        ):
            self._mark_live(device_id, "pair.begin.challenge_invalid")
            diagnostics = self._capture_pairing_diagnostics(session, "pair.begin.challenge_invalid")
            raise DesktopRuntimeError(
                RuntimeErrorCode.CAPABILITY_UNAVAILABLE,
                self._diagnostic_message("Phone returned an invalid pairing challenge.", diagnostics),
            )
        expires_at = now + expires_in_ms
        pending = PairingChallenge(challenge_id, pc_nonce, session.usb_session_id, expires_at)
        self.fleet.set_pairing(session, pending)
        live = self._mark_live(device_id, "pair.begin.challenge_ready") or live
        return {
            "deviceId": device_id,
            "pairingId": challenge_id,
            "pairing": True,
            "expiresAtMs": expires_at,
            "expiresAtEpochMs": expires_at,
            "attemptsRemaining": self.MAX_ATTEMPTS,
            "qrAvailable": True,
            "qrPayload": "cyclone://pair?" + urlencode({"challenge": challenge_id, "nonce": pc_nonce}),
            "preflight": probe,
            "diagnosticsActive": bool(live and live.get("active")),
            "diagnosticsPath": live.get("sessionPath") if live else None,
            "diagnosticsMode": live.get("mode") if live else None,
        }

    def complete(self, device_id: str, pairing_id: str, code: str) -> dict:
        with self._device_lock(device_id):
            return self._complete_locked(device_id, pairing_id, code)

    def _complete_locked(self, device_id: str, pairing_id: str, code: str) -> dict:
        session = self.fleet.get(device_id)
        pending = session.pending_pairing
        if pending is None:
            raise DesktopRuntimeError(RuntimeErrorCode.PAIRING_REPLAY, "No active pairing challenge exists.")
        if not isinstance(pending, PairingChallenge):
            raise DesktopRuntimeError(RuntimeErrorCode.CAPABILITY_UNAVAILABLE, "Pairing state is invalid.")
        if not pairing_id or pairing_id != pending.challenge_id:
            self._mark_live(device_id, "pair.complete.stale_challenge")
            # A stale PC response is not a mistyped phone code. Keep the latest challenge active
            # and do not consume one of its attempts.
            raise DesktopRuntimeError(
                RuntimeErrorCode.PAIRING_REPLAY,
                "Pairing challenge changed; use the newest code.",
                retryable=True,
            )
        now = int(time.time() * 1000)
        if pending.usb_session_id != session.usb_session_id:
            self.fleet.set_pairing(session, None)
            raise DesktopRuntimeError(RuntimeErrorCode.PAIRING_SESSION_MISMATCH, "USB session changed; begin pairing again.", retryable=True)
        if now > pending.expires_at_ms:
            self.fleet.set_pairing(session, None)
            raise DesktopRuntimeError(RuntimeErrorCode.PAIRING_EXPIRED, "Pairing code expired; begin pairing again.", retryable=True)
        code = code.strip().upper()
        if not _CODE_RE.fullmatch(code):
            self._failed_attempt(session, pending)
            raise DesktopRuntimeError(RuntimeErrorCode.PAIRING_CODE_REJECTED, "Pairing code must be exactly four uppercase letters.")
        self._mark_live(device_id, "pair.complete.pc_submit")
        try:
            response = session.bridge(token="").request_unauthenticated(
                "pair.complete",
                {
                    "challengeId": pending.challenge_id,
                    "usbSessionId": pending.usb_session_id,
                    "pcNonce": pending.pc_nonce,
                    "code": code,
                },
                request_id=secrets.token_urlsafe(18),
            )
        except BridgeOperationError as exc:
            self._mark_live(device_id, f"pair.complete.phone_rejected.{exc.code}")
            if exc.code in {"PAIRING_CODE_REJECTED", "PAIRING_ATTEMPTS_EXCEEDED"}:
                self._failed_attempt(session, pending, force_exhausted=exc.code == "PAIRING_ATTEMPTS_EXCEEDED")
            self._map_bridge_error(exc)
        except (BridgeDisconnectedError, BridgeProtocolError) as exc:
            self._mark_live(device_id, "pair.complete.transport_lost")
            diagnostics = self._capture_pairing_diagnostics(session, "pair.complete.transport")
            raise DesktopRuntimeError(
                RuntimeErrorCode.DEVICE_DISCONNECTED,
                self._diagnostic_message("Cyclone pairing transport disconnected before completion.", diagnostics),
                retryable=True,
            ) from exc

        return self._accept_completion(session, pending, response, "pair.complete")

    def complete_qr(self, device_id: str, pairing_id: str) -> dict:
        with self._device_lock(device_id):
            session = self.fleet.get(device_id)
            pending = self._validated_pending(session, pairing_id, "pair.qr.complete")
            self._mark_live(device_id, "pair.qr.complete.pc_poll")
            try:
                response = session.bridge(token="").request_unauthenticated(
                    "pair.qr.complete",
                    {
                        "challengeId": pending.challenge_id,
                        "usbSessionId": pending.usb_session_id,
                        "pcNonce": pending.pc_nonce,
                    },
                    request_id=secrets.token_urlsafe(18),
                )
            except BridgeOperationError as exc:
                self._mark_live(device_id, f"pair.qr.complete.phone_rejected.{exc.code}")
                self._map_bridge_error(exc)
            except (BridgeDisconnectedError, BridgeProtocolError) as exc:
                self._mark_live(device_id, "pair.qr.complete.transport_lost")
                raise DesktopRuntimeError(
                    RuntimeErrorCode.DEVICE_DISCONNECTED,
                    "QR pairing transport is temporarily unavailable.",
                    retryable=True,
                ) from exc
            if response.get("pending") is True:
                return {"deviceId": device_id, "paired": False, "pending": True}
            return self._accept_completion(session, pending, response, "pair.qr.complete")

    def _validated_pending(self, session: DeviceSession, pairing_id: str, phase: str) -> PairingChallenge:
        pending = session.pending_pairing
        if pending is None:
            raise DesktopRuntimeError(RuntimeErrorCode.PAIRING_REPLAY, "No active pairing challenge exists.")
        if not isinstance(pending, PairingChallenge):
            raise DesktopRuntimeError(RuntimeErrorCode.CAPABILITY_UNAVAILABLE, "Pairing state is invalid.")
        if not pairing_id or pairing_id != pending.challenge_id:
            self._mark_live(session.device_id, f"{phase}.stale_challenge")
            raise DesktopRuntimeError(RuntimeErrorCode.PAIRING_REPLAY, "Pairing challenge changed; request a new one.", retryable=True)
        now = int(time.time() * 1000)
        if pending.usb_session_id != session.usb_session_id:
            self.fleet.set_pairing(session, None)
            raise DesktopRuntimeError(RuntimeErrorCode.PAIRING_SESSION_MISMATCH, "USB session changed; begin pairing again.", retryable=True)
        if now > pending.expires_at_ms:
            self.fleet.set_pairing(session, None)
            raise DesktopRuntimeError(RuntimeErrorCode.PAIRING_EXPIRED, "Pairing challenge expired; begin pairing again.", retryable=True)
        return pending

    def _accept_completion(self, session: DeviceSession, pending: PairingChallenge, response: dict, phase: str) -> dict:
        device_id = session.device_id
        self._mark_live(device_id, f"{phase}.response_received")
        credential = str(response.get("credential") or "")
        if len(credential) < 43:
            self._mark_live(device_id, f"{phase}.invalid_credential")
            diagnostics = self._capture_pairing_diagnostics(session, f"{phase}.invalid_credential")
            raise DesktopRuntimeError(
                RuntimeErrorCode.CAPABILITY_UNAVAILABLE,
                self._diagnostic_message("Phone returned an invalid pairing credential.", diagnostics),
            )

        # A pairing response is not enough proof that the Android process survived the transition.
        # Verify the strong credential twice before changing desktop state or starting any richer UX.
        # This deliberately keeps live video/control out of the pairing transaction.
        try:
            health = self._verify_post_pair_health(session, credential)
        except (BridgeDisconnectedError, BridgeProtocolError, BridgeOperationError, OSError) as exc:
            self._mark_live(device_id, f"{phase}.post_health_failed")
            diagnostics = self._capture_pairing_diagnostics(session, f"{phase}.post_health")
            self.fleet.remember_credential(session, None)
            self.fleet.set_pairing(session, None)
            raise DesktopRuntimeError(
                RuntimeErrorCode.DEVICE_DISCONNECTED,
                self._diagnostic_message(
                    "The phone stopped responding immediately after pairing. Cyclone captured Android crash diagnostics.",
                    diagnostics,
                ),
                retryable=True,
            ) from exc

        self.fleet.remember_credential(session, credential)
        self.fleet.record_bridge_status(session, health)
        self.fleet.set_pairing(session, None)
        session.state = DeviceFleetState.READY if session.screen_awake else DeviceFleetState.SLEEPING
        live = self._mark_live(device_id, f"{phase}.health_verified")
        return {
            "deviceId": device_id,
            "paired": True,
            "state": session.state.value,
            "gatewayHealthy": True,
            "accessibilityConnected": bool(health.get("accessibilityConnected")),
            "diagnosticsActive": bool(live and live.get("active")),
            "diagnosticsPath": live.get("sessionPath") if live else None,
            "device": session.public(),
        }

    def revoke(self, device_id: str) -> dict:
        with self._device_lock(device_id):
            return self._revoke_locked(device_id)

    def _revoke_locked(self, device_id: str) -> dict:
        session = self.fleet.get(device_id)
        self._mark_live(device_id, "pair.revoke.pc_request")
        if session.credential:
            try:
                session.bridge().request("pair.revoke", {}, request_id=secrets.token_urlsafe(18))
            except BridgeOperationError as exc:
                self._map_bridge_error(exc)
            except (BridgeDisconnectedError, BridgeProtocolError) as exc:
                raise DesktopRuntimeError(RuntimeErrorCode.DEVICE_DISCONNECTED, "Could not revoke pairing while the phone is disconnected.", retryable=True) from exc
        self.fleet.remember_credential(session, None)
        self.fleet.set_pairing(session, None)
        session.state = DeviceFleetState.UNPAIRED
        return {"deviceId": device_id, "paired": False, "state": session.state.value}

    def _device_lock(self, device_id: str) -> threading.RLock:
        with self._locks_guard:
            return self._device_locks.setdefault(device_id, threading.RLock())

    def _verify_post_pair_health(self, session: DeviceSession, credential: str) -> dict:
        latest: dict = {}
        for probe in range(self.POST_PAIR_HEALTH_PROBES):
            if probe:
                time.sleep(self.POST_PAIR_HEALTH_DELAY_SECONDS)
            session.adb.ensure_bridge_forward(session.local_port)
            latest = session.bridge(token=credential).request(
                "bridge.status",
                {},
                request_id=secrets.token_urlsafe(18),
            )
            if latest.get("gatewayEnabled") is not True:
                raise BridgeProtocolError("Phone Gateway did not stay enabled after pairing")
            if latest.get("pairingBootstrapListening") is not True and latest.get("socketListening") is not True:
                raise BridgeProtocolError("Phone Gateway listener disappeared after pairing")
        return latest

    def _capture_pairing_diagnostics(self, session: DeviceSession, phase: str) -> str | None:
        # Heavy collection starts only after the operation has already failed. The live recorder has
        # the pre-failure timeline/logcat, so we do not launch a second concurrent dumpsys snapshot.
        self._mark_live(session.device_id, phase)
        collector = getattr(session.adb, "collect_cyclone_crash_diagnostics", None)
        if not callable(collector):
            live = self._live_status(session.device_id)
            return str(live.get("sessionPath")) if live and live.get("sessionPath") else None
        try:
            captured_at = int(time.time() * 1000)
            immediate = collector()
            if self.DIAGNOSTIC_SETTLE_DELAY_SECONDS > 0:
                time.sleep(self.DIAGNOSTIC_SETTLE_DELAY_SECONDS)
            settled = collector()
            self.diagnostics_dir.mkdir(parents=True, exist_ok=True)
            path = self.diagnostics_dir / f"pairing-{session.device_id}-{captured_at}.json"
            path.write_text(
                json.dumps(
                    {
                        "phase": phase,
                        "capturedAtEpochMs": captured_at,
                        "settledCaptureAtEpochMs": int(time.time() * 1000),
                        "deviceId": session.device_id,
                        "liveDiagnostics": self._live_status(session.device_id),
                        "androidImmediate": immediate,
                        "androidSettled": settled,
                    },
                    indent=2,
                    sort_keys=True,
                ),
                encoding="utf-8",
            )
            return str(path)
        except Exception:
            live = self._live_status(session.device_id)
            return str(live.get("sessionPath")) if live and live.get("sessionPath") else None

    def _mark_live(self, device_id: str, stage: str, *, snapshot: bool = False) -> dict | None:
        if self.live_diagnostics is None:
            return None
        try:
            return self.live_diagnostics.mark(device_id, stage, snapshot=snapshot)
        except Exception:
            return None

    def _live_status(self, device_id: str) -> dict | None:
        if self.live_diagnostics is None:
            return None
        try:
            status = self.live_diagnostics.status().get("devices", {})
            value = status.get(device_id)
            return value if isinstance(value, dict) else None
        except Exception:
            return None

    @staticmethod
    def _diagnostic_message(message: str, diagnostics: str | None) -> str:
        if diagnostics:
            return f"{message} Diagnostic file: {diagnostics}"
        return message

    def _failed_attempt(self, session: DeviceSession, pending: PairingChallenge, *, force_exhausted: bool = False) -> None:
        pending.attempts += 1
        if force_exhausted or pending.attempts >= self.MAX_ATTEMPTS:
            self.fleet.set_pairing(session, None)
            raise DesktopRuntimeError(RuntimeErrorCode.PAIRING_ATTEMPTS_EXCEEDED, "Pairing attempt limit reached; begin again.")

    def _pairable(self, device_id: str) -> DeviceSession:
        session = self.fleet.get(device_id)
        if session.adb_device.state == "unauthorized":
            raise DesktopRuntimeError(RuntimeErrorCode.DEVICE_UNAUTHORIZED, "Authorize USB debugging on the phone.")
        if session.adb_device.state != "device":
            raise DesktopRuntimeError(RuntimeErrorCode.DEVICE_NOT_READY, "Phone is not ready for pairing.", retryable=True)
        try:
            session.adb.ensure_bridge_forward(session.local_port)
        except Exception as exc:
            raise DesktopRuntimeError(RuntimeErrorCode.DEVICE_DISCONNECTED, "Could not prepare the isolated USB bridge.", retryable=True) from exc
        return session

    def _probe_phone_readiness(self, session: DeviceSession) -> dict:
        """Bounded, read-only preflight used to explain a failing pairing bootstrap.

        The probe never exposes arbitrary ADB/shell surfaces and never collects screen pixels,
        clipboard content, credentials or pairing codes. Unknown values (None) mean the fixed read
        itself failed, not that the phone is healthy.
        """
        probe = {
            "appRunning": None,
            "accessibilityEnabled": None,
            "accessibilityServiceConfigured": None,
        }
        try:
            pid = session.adb.shell("pidof", "com.cyclone.mobile", timeout=3).strip()
            probe["appRunning"] = bool(pid)
        except Exception:
            probe["appRunning"] = None
        try:
            enabled = session.adb.shell("settings", "get", "secure", "accessibility_enabled", timeout=3).strip()
            probe["accessibilityEnabled"] = enabled == "1"
        except Exception:
            probe["accessibilityEnabled"] = None
        try:
            services = session.adb.shell("settings", "get", "secure", "enabled_accessibility_services", timeout=3).strip()
            probe["accessibilityServiceConfigured"] = "com.cyclone.mobile" in services
        except Exception:
            probe["accessibilityServiceConfigured"] = None
        return probe

    @staticmethod
    def _readiness_hint(probe: dict) -> str:
        hints: list[str] = []
        if probe.get("appRunning") is False:
            hints.append("Cyclone app is not running on the phone; open it before pairing")
        elif probe.get("appRunning") is None:
            hints.append("Cyclone app process could not be checked")
        if probe.get("accessibilityEnabled") is False:
            hints.append("Android accessibility is off")
        if probe.get("accessibilityServiceConfigured") is False:
            hints.append("Cyclone accessibility service is not enabled")
        if hints:
            return "Readiness: " + "; ".join(hints) + "."
        if any(value is None for value in probe.values()):
            return "Readiness: checks were incomplete; the USB bridge transport could not be confirmed."
        return "Readiness: all checks look healthy, but the phone did not complete the pairing bootstrap."

    @staticmethod
    def _map_bridge_error(
        exc: BridgeOperationError,
        hint: str = "",
        diagnostics: str | None = None,
    ) -> None:
        mapping = {
            "AUTH_REJECTED": RuntimeErrorCode.AUTH_REJECTED,
            "PAIRING_EXPIRED": RuntimeErrorCode.PAIRING_EXPIRED,
            "PAIRING_REPLAY": RuntimeErrorCode.PAIRING_REPLAY,
            "PAIRING_CODE_REJECTED": RuntimeErrorCode.PAIRING_CODE_REJECTED,
            "PAIRING_ATTEMPTS_EXCEEDED": RuntimeErrorCode.PAIRING_ATTEMPTS_EXCEEDED,
            "PAIRING_SESSION_MISMATCH": RuntimeErrorCode.PAIRING_SESSION_MISMATCH,
            "CAPABILITY_UNAVAILABLE": RuntimeErrorCode.CAPABILITY_UNAVAILABLE,
        }
        error_code = mapping.get(exc.code, RuntimeErrorCode.CAPABILITY_UNAVAILABLE)
        message = f"Phone rejected pairing with {error_code.value}."
        if hint:
            message = f"{message} {hint}"
        if diagnostics:
            message = f"{message} Diagnostic file: {diagnostics}"
        raise DesktopRuntimeError(error_code, message)
