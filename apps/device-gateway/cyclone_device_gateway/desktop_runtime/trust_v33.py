from __future__ import annotations

from dataclasses import dataclass
import base64
import ctypes
from ctypes import wintypes
import hashlib
import json
import os
from pathlib import Path
import platform
import secrets
import threading
import time
from typing import Any

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.serialization import load_der_public_key, load_pem_private_key

from ..cyclone_bridge.client import BridgeDisconnectedError, BridgeOperationError, BridgeProtocolError
from .models import DesktopRuntimeError, RuntimeErrorCode

TRUST_PROTOCOL_ID = "cyclone.android.trust.v3"
TRUST_PROTOCOL_VERSION = "3.3"
SESSION_REFRESH_MARGIN_MS = 30_000
RECONNECT_BACKOFF_SECONDS = (2, 5, 10, 30)
# A locked phone only needs unlocking: look again soon instead of backing off (alpha 88).
LOCKED_RETRY_SECONDS = 2
# One automatic "Connect this PC?" per plug-in, and none for a day after the owner says Not now (alpha 88).
AUTO_ASK_DECLINE_COOLDOWN_MS = 24 * 3600 * 1000
AUTO_COMPLETE_POLL_MS = 2_000


def _now_ms() -> int:
    return int(time.time() * 1000)


def _b64url(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).decode("ascii").rstrip("=")


def _unb64url(value: str) -> bytes:
    padding = "=" * ((4 - len(value) % 4) % 4)
    return base64.urlsafe_b64decode((value + padding).encode("ascii"))


def _sha256_b64url(value: bytes) -> str:
    return _b64url(hashlib.sha256(value).digest())


def _canonical(fields: list[tuple[str, str]]) -> str:
    return TRUST_PROTOCOL_ID + "\n" + "".join(f"{key}={value}\n" for key, value in fields)


def trust_transcript(*, challenge_id: str, phone_id: str, pc_id: str, pc_nonce: str, phone_nonce: str, expires_at_ms: int) -> str:
    return _canonical([
        ("purpose", "trust-complete"),
        ("protocol", TRUST_PROTOCOL_VERSION),
        ("challengeId", challenge_id),
        ("phoneId", phone_id),
        ("pcId", pc_id),
        ("pcNonce", pc_nonce),
        ("phoneNonce", phone_nonce),
        ("expiresAtMs", str(expires_at_ms)),
    ])


def trust_match_code(transcript: str) -> str:
    """Six digits both screens show while a phone is being connected (not a secret, only a visual match).

    Phone twin: `GatewayTrustProtocolV33.matchCode`. Both derive it from the same signed transcript, so the
    user can see that the phone asking is the phone on this PC's screen.
    """
    digest = hashlib.sha256(transcript.encode("utf-8")).digest()
    return f"{int.from_bytes(digest[:4], 'big') % 1_000_000:06d}"


def trust_receipt_transcript(*, challenge_id: str, trust_id: str, phone_id: str, pc_id: str, generation: int) -> str:
    return _canonical([
        ("purpose", "trust-receipt"),
        ("protocol", TRUST_PROTOCOL_VERSION),
        ("challengeId", challenge_id),
        ("trustId", trust_id),
        ("phoneId", phone_id),
        ("pcId", pc_id),
        ("generation", str(generation)),
    ])


def session_transcript(*, challenge_id: str, trust_id: str, phone_id: str, pc_id: str, generation: int, pc_nonce: str, phone_nonce: str, expires_at_ms: int) -> str:
    return _canonical([
        ("purpose", "session-open"),
        ("protocol", TRUST_PROTOCOL_VERSION),
        ("challengeId", challenge_id),
        ("trustId", trust_id),
        ("phoneId", phone_id),
        ("pcId", pc_id),
        ("generation", str(generation)),
        ("pcNonce", pc_nonce),
        ("phoneNonce", phone_nonce),
        ("expiresAtMs", str(expires_at_ms)),
    ])


def session_receipt_transcript(*, session_id: str, trust_id: str, phone_id: str, pc_id: str, generation: int, expires_at_ms: int, token_digest: str) -> str:
    return _canonical([
        ("purpose", "session-receipt"),
        ("protocol", TRUST_PROTOCOL_VERSION),
        ("sessionId", session_id),
        ("trustId", trust_id),
        ("phoneId", phone_id),
        ("pcId", pc_id),
        ("generation", str(generation)),
        ("expiresAtMs", str(expires_at_ms)),
        ("tokenSha256", token_digest),
    ])


class _DataBlob(ctypes.Structure):
    _fields_ = [("cbData", wintypes.DWORD), ("pbData", ctypes.POINTER(ctypes.c_byte))]


def _dpapi_transform(data: bytes, *, protect: bool) -> bytes:
    if os.name != "nt":
        raise OSError("Windows DPAPI is available only on Windows")
    source = ctypes.create_string_buffer(data)
    source_blob = _DataBlob(len(data), ctypes.cast(source, ctypes.POINTER(ctypes.c_byte)))
    target_blob = _DataBlob()
    crypt32 = ctypes.windll.crypt32
    kernel32 = ctypes.windll.kernel32
    flags = 0x1  # CRYPTPROTECT_UI_FORBIDDEN
    if protect:
        ok = crypt32.CryptProtectData(
            ctypes.byref(source_blob),
            "Cyclone 3.3 PC trust",
            None,
            None,
            None,
            flags,
            ctypes.byref(target_blob),
        )
    else:
        description = ctypes.c_wchar_p()
        ok = crypt32.CryptUnprotectData(
            ctypes.byref(source_blob),
            ctypes.byref(description),
            None,
            None,
            None,
            flags,
            ctypes.byref(target_blob),
        )
    if not ok:
        raise OSError(ctypes.get_last_error(), "Windows DPAPI operation failed")
    try:
        return ctypes.string_at(target_blob.pbData, target_blob.cbData)
    finally:
        kernel32.LocalFree(target_blob.pbData)


class PCTrustStore:
    """Persistent V3.3 PC identity/trust state.

    Production Windows persistence is encrypted with current-user DPAPI. Non-Windows environments
    intentionally keep the same state only in memory so Linux CI/dev never creates a plaintext
    private-key file while still exercising protocol code.
    """

    def __init__(self, root: Path | None = None):
        runtime = Path(os.getenv("CYCLONE_DEVICE_GATEWAY_RUNTIME", ".runtime/device-gateway")).expanduser().resolve()
        self.root = (root or runtime.parent / "trust").resolve()
        self.path = self.root / "cyclone-v33-pc-trust.dpapi"
        self._lock = threading.RLock()
        self._memory: dict[str, Any] = {}
        self._load_error: str | None = None

    @property
    def security_mode(self) -> str:
        return "WINDOWS_DPAPI_CURRENT_USER" if os.name == "nt" else "EPHEMERAL_NON_WINDOWS"

    @property
    def load_error(self) -> str | None:
        return self._load_error

    def load(self) -> dict[str, Any]:
        with self._lock:
            if os.name != "nt":
                return json.loads(json.dumps(self._memory))
            if not self.path.exists():
                return {}
            try:
                plaintext = _dpapi_transform(self.path.read_bytes(), protect=False)
                value = json.loads(plaintext.decode("utf-8"))
                if not isinstance(value, dict):
                    raise ValueError("trust state is not an object")
                self._load_error = None
                return value
            except Exception as exc:
                self._load_error = exc.__class__.__name__
                # Fail closed: an unreadable protected identity is not silently replaced during
                # this process because doing so would make a previously trusted PC look different.
                raise DesktopRuntimeError(
                    RuntimeErrorCode.TRUST_AUTH_FAILED,
                    "Cyclone could not unlock this PC's protected trust identity. Re-trust this PC after repairing local app data.",
                ) from exc

    def save(self, value: dict[str, Any]) -> None:
        payload = json.dumps(value, sort_keys=True, separators=(",", ":")).encode("utf-8")
        with self._lock:
            if os.name != "nt":
                self._memory = json.loads(payload.decode("utf-8"))
                return
            self.root.mkdir(parents=True, exist_ok=True)
            protected = _dpapi_transform(payload, protect=True)
            temporary = self.path.with_suffix(".tmp")
            temporary.write_bytes(protected)
            try:
                os.chmod(temporary, 0o600)
            except OSError:
                pass
            temporary.replace(self.path)

    def record(self, device_id: str) -> dict[str, Any] | None:
        state = self.load()
        records = state.get("records") if isinstance(state.get("records"), dict) else {}
        value = records.get(device_id)
        return dict(value) if isinstance(value, dict) else None

    def put_record(self, device_id: str, record: dict[str, Any]) -> None:
        state = self.load()
        records = state.setdefault("records", {})
        if not isinstance(records, dict):
            records = {}
            state["records"] = records
        records[device_id] = dict(record)
        self.save(state)

    def remove_record(self, device_id: str) -> None:
        state = self.load()
        records = state.get("records")
        if isinstance(records, dict):
            records.pop(device_id, None)
        self.save(state)

    def identity_pem(self) -> bytes | None:
        state = self.load()
        value = state.get("identityPrivateKeyPem")
        return value.encode("utf-8") if isinstance(value, str) and value.startswith("-----BEGIN PRIVATE KEY-----") else None

    def save_identity_pem(self, pem: bytes) -> None:
        state = self.load()
        state["identityPrivateKeyPem"] = pem.decode("utf-8")
        state.setdefault("records", {})
        self.save(state)


class PCIdentity:
    def __init__(self, store: PCTrustStore):
        self.store = store
        pem = store.identity_pem()
        if pem is None:
            key = ec.generate_private_key(ec.SECP256R1())
            pem = key.private_bytes(
                serialization.Encoding.PEM,
                serialization.PrivateFormat.PKCS8,
                serialization.NoEncryption(),
            )
            store.save_identity_pem(pem)
            self._key = key
        else:
            loaded = load_pem_private_key(pem, password=None)
            if not isinstance(loaded, ec.EllipticCurvePrivateKey) or not isinstance(loaded.curve, ec.SECP256R1):
                raise DesktopRuntimeError(RuntimeErrorCode.TRUST_AUTH_FAILED, "Cyclone PC identity is not a P-256 key.")
            self._key = loaded
        public_der = self._key.public_key().public_bytes(
            serialization.Encoding.DER,
            serialization.PublicFormat.SubjectPublicKeyInfo,
        )
        self.public_key_base64 = _b64url(public_der)
        self.pc_id = _sha256_b64url(public_der)

    def sign(self, transcript: str) -> str:
        return _b64url(self._key.sign(transcript.encode("utf-8"), ec.ECDSA(hashes.SHA256())))


@dataclass
class PendingTrust:
    device_id: str
    challenge_id: str
    phone_id: str
    phone_public_key: str
    pc_nonce: str
    phone_nonce: str
    expires_at_ms: int
    transcript: str


@dataclass
class ActiveSession:
    token: str
    expires_at_ms: int
    session_id: str
    # The USB session this trusted session was opened on. A replug or a new phone process means a new one (alpha 88).
    usb_session_id: str = ""


class PCTrustCoordinator:
    def __init__(self, fleet: Any, store: PCTrustStore | None = None, *, pc_label: str | None = None):
        self.fleet = fleet
        self.store = store or PCTrustStore()
        self.identity = PCIdentity(self.store)
        self.pc_label = _safe_pc_label(pc_label)
        self._lock = threading.RLock()
        self._pending: dict[str, PendingTrust] = {}
        self._active: dict[str, ActiveSession] = {}
        self._revoked: set[str] = set()
        self._next_retry_ms: dict[str, int] = {}
        self._retry_attempts: dict[str, int] = {}
        self._last_error: dict[str, str] = {}
        # Alpha 88: one automatic Allow prompt per USB session, and the owner's "Not now" is respected for a day.
        self.auto_ask = os.getenv("CYCLONE_AUTO_CONNECT", "1").strip() != "0"
        self._asked_usb: dict[str, str] = {}
        self._declined_at: dict[str, int] = {}
        self._auto_pending: set[str] = set()
        self._last_complete_poll: dict[str, int] = {}
        self._seen_usb: dict[str, str] = {}
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None

    def start(self) -> None:
        with self._lock:
            if self._thread and self._thread.is_alive():
                return
            self._stop.clear()
            self._thread = threading.Thread(target=self._restore_loop, name="cyclone-v33-trust-reconnect", daemon=True)
            self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        thread = self._thread
        if thread and thread.is_alive():
            thread.join(timeout=2.0)
        with self._lock:
            active_ids = list(self._active)
            self._active.clear()
        for device_id in active_ids:
            try:
                self.fleet.remember_credential(self.fleet.get(device_id), None)
            except Exception:
                pass

    @staticmethod
    def _is_current(active: ActiveSession | None, session: Any, *, margin_ms: int = 0) -> bool:
        """The trusted session is usable only on the USB session it was opened on, while the fleet still carries its
        token (a phone that rejected it cleared it) and before it expires. Anything else is restored, never assumed."""
        return (
            active is not None
            and active.expires_at_ms > _now_ms() + margin_ms
            and active.usb_session_id == str(getattr(session, "usb_session_id", "") or "")
            and getattr(session, "credential", None) == active.token
        )

    def status(self, device_id: str) -> dict[str, Any]:
        session = self.fleet.get(device_id)
        record = self.store.record(device_id)
        with self._lock:
            pending = self._pending.get(device_id)
            active = self._active.get(device_id)
            last_error = self._last_error.get(device_id)
            revoked = device_id in self._revoked
            auto_asked = device_id in self._auto_pending
        now = _now_ms()
        active_ready = self._is_current(active, session)
        if pending is not None and pending.expires_at_ms > now:
            state = "CONFIRMATION_REQUIRED"
        elif revoked:
            state = "REVOKED"
        elif record is not None:
            state = "TRUSTED"
        else:
            state = "UNPAIRED"
        return {
            "deviceId": device_id,
            "protocolVersion": TRUST_PROTOCOL_VERSION,
            "state": state,
            "matchCode": trust_match_code(pending.transcript) if state == "CONFIRMATION_REQUIRED" and pending else None,
            "confirmationRequired": state == "CONFIRMATION_REQUIRED",
            "trusted": record is not None and not revoked,
            "sessionReady": active_ready,
            "sessionExpiresAtEpochMs": active.expires_at_ms if active_ready and active else None,
            "pcId": self.identity.pc_id,
            "pcLabel": self.pc_label,
            "pcIdentityStorage": self.store.security_mode,
            "sessionSecretPersisted": False,
            "lastSafeError": last_error,
            "adbReady": str(getattr(session.adb_device, "state", "")) == "device",
            # The PC asked on its own when the phone was plugged in (alpha 88).
            "autoAsked": auto_asked and state == "CONFIRMATION_REQUIRED",
        }

    def begin(self, device_id: str, *, auto: bool = False) -> dict[str, Any]:
        with self._lock:
            record = self.store.record(device_id)
            if record is not None and device_id not in self._revoked:
                result = self.open_session(device_id)
                return {**self.status(device_id), "restored": True, "sessionReady": result["sessionReady"]}
            pending = self._pending.get(device_id)
            if pending is not None and pending.expires_at_ms > _now_ms() + 5_000:
                # One question on the phone at a time: a second Connect joins the one already showing.
                if not auto:
                    self._auto_pending.discard(device_id)
                return {**self.status(device_id), "challengeId": pending.challenge_id, "expiresAtEpochMs": pending.expires_at_ms,
                        "phoneConfirmation": "Allow this PC", "manualFallback": False}
            session = self._adb_ready(device_id)
            bridge = session.bridge(token="")
            negotiated = bridge.request_unauthenticated(
                "trust.negotiate",
                {"protocolVersion": TRUST_PROTOCOL_VERSION},
                request_id=f"trust-negotiate-{secrets.token_urlsafe(12)}",
            )
            phone_id, phone_key = self._validated_phone_identity(negotiated)
            pc_nonce = secrets.token_urlsafe(32)
            response = bridge.request_unauthenticated(
                "trust.begin",
                {
                    "protocolVersion": TRUST_PROTOCOL_VERSION,
                    "pcId": self.identity.pc_id,
                    "pcLabel": self.pc_label,
                    "pcPublicKey": self.identity.public_key_base64,
                    "pcNonce": pc_nonce,
                },
                request_id=f"trust-begin-{secrets.token_urlsafe(12)}",
            )
            if str(response.get("protocolVersion") or "") != TRUST_PROTOCOL_VERSION:
                raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Phone returned a different Cyclone trust protocol version.")
            challenge_id = _required(response, "challengeId")
            begin_phone_id = _required(response, "phoneId")
            begin_phone_key = _required(response, "phonePublicKey")
            phone_nonce = _required(response, "phoneNonce")
            pc_id = _required(response, "pcId")
            expires_at_ms = int(response.get("expiresAtMs") or 0)
            if begin_phone_id != phone_id or begin_phone_key != phone_key or pc_id != self.identity.pc_id or expires_at_ms <= _now_ms():
                raise DesktopRuntimeError(RuntimeErrorCode.TRUST_AUTH_FAILED, "Phone trust challenge identity did not match negotiation.")
            expected = trust_transcript(
                challenge_id=challenge_id,
                phone_id=phone_id,
                pc_id=self.identity.pc_id,
                pc_nonce=pc_nonce,
                phone_nonce=phone_nonce,
                expires_at_ms=expires_at_ms,
            )
            if str(response.get("transcript") or "") != expected:
                raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Phone trust transcript did not match the V3.3 fixture.")
            self._pending[device_id] = PendingTrust(
                device_id=device_id,
                challenge_id=challenge_id,
                phone_id=phone_id,
                phone_public_key=phone_key,
                pc_nonce=pc_nonce,
                phone_nonce=phone_nonce,
                expires_at_ms=expires_at_ms,
                transcript=expected,
            )
            self._revoked.discard(device_id)
            self._clear_retry(device_id)
            if auto:
                self._auto_pending.add(device_id)
            else:
                self._auto_pending.discard(device_id)
            return {
                **self.status(device_id),
                "challengeId": challenge_id,
                "expiresAtEpochMs": expires_at_ms,
                "phoneConfirmation": "Allow this PC",
                "manualFallback": False,
            }

    def complete(self, device_id: str) -> dict[str, Any]:
        with self._lock:
            pending = self._pending.get(device_id)
            if pending is None:
                record = self.store.record(device_id)
                if record is not None:
                    self.open_session(device_id)
                    return {**self.status(device_id), "completed": True}
                raise DesktopRuntimeError(RuntimeErrorCode.TRUST_CONFIRMATION_REQUIRED, "Start Allow this PC trust first.", retryable=True)
            if _now_ms() > pending.expires_at_ms:
                self._pending.pop(device_id, None)
                raise DesktopRuntimeError(RuntimeErrorCode.TRUST_EXPIRED, "Allow this PC confirmation expired; try again.", retryable=True)
            session = self._adb_ready(device_id)
            try:
                response = session.bridge(token="").request_unauthenticated(
                    "trust.complete",
                    {
                        "protocolVersion": TRUST_PROTOCOL_VERSION,
                        "challengeId": pending.challenge_id,
                        "pcSignature": self.identity.sign(pending.transcript),
                    },
                    request_id=f"trust-complete-{secrets.token_urlsafe(12)}",
                )
            except BridgeOperationError as exc:
                if exc.code == "PHONE_CONFIRMATION_REQUIRED":
                    return {**self.status(device_id), "completed": False, "confirmationRequired": True}
                if exc.code in {"TRUST_REJECTED", "TRUST_EXPIRED"}:
                    self._pending.pop(device_id, None)
                    self._auto_pending.discard(device_id)
                    if exc.code == "TRUST_REJECTED":
                        self._declined_at[device_id] = _now_ms()
                self._raise_bridge(exc)
            trust_id = _required(response, "trustId")
            phone_id = _required(response, "phoneId")
            pc_id = _required(response, "pcId")
            generation = int(response.get("generation") or 0)
            phone_signature = _required(response, "phoneSignature")
            if phone_id != pending.phone_id or pc_id != self.identity.pc_id or generation < 1:
                raise DesktopRuntimeError(RuntimeErrorCode.TRUST_AUTH_FAILED, "Phone trust receipt identity did not match the pending challenge.")
            receipt = trust_receipt_transcript(
                challenge_id=pending.challenge_id,
                trust_id=trust_id,
                phone_id=phone_id,
                pc_id=pc_id,
                generation=generation,
            )
            _verify_phone_signature(pending.phone_public_key, receipt, phone_signature)
            self.store.put_record(device_id, {
                "trustId": trust_id,
                "phoneId": phone_id,
                "phonePublicKey": pending.phone_public_key,
                "pcId": pc_id,
                "generation": generation,
            })
            self._pending.pop(device_id, None)
            self._auto_pending.discard(device_id)
            self._revoked.discard(device_id)
            self.open_session(device_id)
            return {**self.status(device_id), "completed": True}

    def open_session(self, device_id: str) -> dict[str, Any]:
        with self._lock:
            session = self._adb_ready(device_id)
            record = self.store.record(device_id)
            if record is None:
                raise DesktopRuntimeError(RuntimeErrorCode.TRUST_CONFIRMATION_REQUIRED, "Allow this PC on the phone first.", retryable=True)
            active = self._active.get(device_id)
            now = _now_ms()
            if self._is_current(active, session, margin_ms=SESSION_REFRESH_MARGIN_MS):
                return {"deviceId": device_id, "sessionReady": True, "sessionExpiresAtEpochMs": active.expires_at_ms}
            # Opened on another USB session, rejected by the phone, or expiring: open a fresh one on this session.
            self._active.pop(device_id, None)
            negotiated = session.bridge(token="").request_unauthenticated(
                "trust.negotiate",
                {"protocolVersion": TRUST_PROTOCOL_VERSION},
                request_id=f"session-negotiate-{secrets.token_urlsafe(12)}",
            )
            phone_id, phone_key = self._validated_phone_identity(negotiated)
            if phone_id != str(record.get("phoneId") or "") or phone_key != str(record.get("phonePublicKey") or ""):
                raise DesktopRuntimeError(RuntimeErrorCode.TRUST_AUTH_FAILED, "Connected phone identity no longer matches the stored trust record.")
            pc_nonce = secrets.token_urlsafe(32)
            response = session.bridge(token="").request_unauthenticated(
                "trust.session.begin",
                {
                    "protocolVersion": TRUST_PROTOCOL_VERSION,
                    "trustId": str(record.get("trustId") or ""),
                    "generation": int(record.get("generation") or 0),
                    "pcNonce": pc_nonce,
                },
                request_id=f"session-begin-{secrets.token_urlsafe(12)}",
            )
            challenge_id = _required(response, "challengeId")
            trust_id = _required(response, "trustId")
            session_phone_id = _required(response, "phoneId")
            pc_id = _required(response, "pcId")
            generation = int(response.get("generation") or 0)
            phone_nonce = _required(response, "phoneNonce")
            expires_at_ms = int(response.get("expiresAtMs") or 0)
            expected = session_transcript(
                challenge_id=challenge_id,
                trust_id=trust_id,
                phone_id=session_phone_id,
                pc_id=pc_id,
                generation=generation,
                pc_nonce=pc_nonce,
                phone_nonce=phone_nonce,
                expires_at_ms=expires_at_ms,
            )
            if (
                str(response.get("transcript") or "") != expected
                or trust_id != str(record.get("trustId") or "")
                or session_phone_id != phone_id
                or pc_id != self.identity.pc_id
                or generation != int(record.get("generation") or 0)
                or expires_at_ms <= now
            ):
                raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Phone session challenge did not match the V3.3 trust fixture.")
            completed = session.bridge(token="").request_unauthenticated(
                "trust.session.complete",
                {
                    "protocolVersion": TRUST_PROTOCOL_VERSION,
                    "challengeId": challenge_id,
                    "pcSignature": self.identity.sign(expected),
                },
                request_id=f"session-complete-{secrets.token_urlsafe(12)}",
            )
            token = _required(completed, "sessionToken")
            session_id = _required(completed, "sessionId")
            completed_trust_id = _required(completed, "trustId")
            completed_generation = int(completed.get("generation") or 0)
            session_expires = int(completed.get("expiresAtMs") or 0)
            phone_signature = _required(completed, "phoneSignature")
            if completed_trust_id != trust_id or completed_generation != generation or session_expires <= now or len(token) < 32:
                raise DesktopRuntimeError(RuntimeErrorCode.TRUST_AUTH_FAILED, "Phone returned an invalid trusted session receipt.")
            receipt = session_receipt_transcript(
                session_id=session_id,
                trust_id=trust_id,
                phone_id=phone_id,
                pc_id=self.identity.pc_id,
                generation=generation,
                expires_at_ms=session_expires,
                token_digest=_sha256_b64url(token.encode("utf-8")),
            )
            _verify_phone_signature(phone_key, receipt, phone_signature)
            self._active[device_id] = ActiveSession(token=token, expires_at_ms=session_expires, session_id=session_id,
                                                    usb_session_id=str(getattr(session, "usb_session_id", "") or ""))
            self.fleet.remember_credential(session, token)
            try:
                health = session.bridge(token=token).request(
                    "bridge.status",
                    {},
                    request_id=f"session-health-{secrets.token_urlsafe(12)}",
                )
                self.fleet.record_bridge_status(session, health)
                session.bridge_ok = bool(health.get("gatewayEnabled") is True)
                session.last_heartbeat_ms = _now_ms()
                session.bridge_last_error = None
                session.bridge_error_class = None
            except Exception:
                # The signed session is still valid. The independent bridge plane owns recovery.
                session.bridge_ok = False
            self._clear_retry(device_id)
            return {"deviceId": device_id, "sessionReady": True, "sessionExpiresAtEpochMs": session_expires}

    def rotate(self, device_id: str) -> dict[str, Any]:
        with self._lock:
            token = self._active_token(device_id)
            session = self._adb_ready(device_id)
            record = self.store.record(device_id)
            if record is None:
                raise DesktopRuntimeError(RuntimeErrorCode.TRUST_CONFIRMATION_REQUIRED, "Allow this PC on the phone first.")
            response = session.bridge(token=token).request(
                "trust.rotate",
                {"protocolVersion": TRUST_PROTOCOL_VERSION},
                request_id=f"trust-rotate-{secrets.token_urlsafe(12)}",
            )
            generation = int(response.get("generation") or 0)
            if generation <= int(record.get("generation") or 0):
                raise DesktopRuntimeError(RuntimeErrorCode.TRUST_AUTH_FAILED, "Phone did not advance the trust generation during rotation.")
            record["generation"] = generation
            self.store.put_record(device_id, record)
            self._active.pop(device_id, None)
            self.fleet.remember_credential(session, None)
            self.open_session(device_id)
            return {**self.status(device_id), "rotated": True}

    def revoke(self, device_id: str) -> dict[str, Any]:
        with self._lock:
            # Local forgetting must remain available when USB is offline or the phone identity
            # changed. Remote revocation is attempted below only when a valid session can open.
            session = self.fleet.get(device_id)
            record = self.store.record(device_id)
            if record is None:
                self._pending.pop(device_id, None)
                self._active.pop(device_id, None)
                self._revoked.add(device_id)
                self.fleet.remember_credential(session, None)
                return {
                    **self.status(device_id),
                    "revoked": True,
                    "localTrustCleared": True,
                    "phoneRevocationConfirmed": False,
                }
            phone_revocation_confirmed = False
            phone_revocation_error: str | None = None
            try:
                token = self._active_token(device_id)
                session.bridge(token=token).request(
                    "trust.revoke",
                    {"protocolVersion": TRUST_PROTOCOL_VERSION, "trustId": str(record.get("trustId") or "")},
                    request_id=f"trust-revoke-{secrets.token_urlsafe(12)}",
                )
                phone_revocation_confirmed = True
            except DesktopRuntimeError as exc:
                # Forgetting a stale/offline phone must not depend on reopening the very trust
                # session that is being removed. Clear only this PC's bounded record and report
                # that phone-side revocation could not be confirmed; a new trust handshake still
                # requires the phone's explicit Allow this PC confirmation.
                phone_revocation_error = exc.code
            except BridgeOperationError as exc:
                phone_revocation_error = str(exc.code or "BRIDGE_OPERATION_FAILED")
            except (BridgeDisconnectedError, BridgeProtocolError) as exc:
                phone_revocation_error = exc.__class__.__name__
            self.store.remove_record(device_id)
            self._pending.pop(device_id, None)
            self._active.pop(device_id, None)
            self._revoked.add(device_id)
            self.fleet.remember_credential(session, None)
            return {
                **self.status(device_id),
                "revoked": True,
                "localTrustCleared": True,
                "phoneRevocationConfirmed": phone_revocation_confirmed,
                "phoneRevocationError": phone_revocation_error,
            }

    def _active_token(self, device_id: str) -> str:
        active = self._active.get(device_id)
        if not self._is_current(active, self.fleet.get(device_id), margin_ms=SESSION_REFRESH_MARGIN_MS):
            self.open_session(device_id)
            active = self._active.get(device_id)
        if active is None:
            raise DesktopRuntimeError(RuntimeErrorCode.TRUST_AUTH_FAILED, "Cyclone could not open a trusted phone session.")
        return active.token

    def _validated_phone_identity(self, value: dict[str, Any]) -> tuple[str, str]:
        if str(value.get("protocolVersion") or "") != TRUST_PROTOCOL_VERSION or str(value.get("protocolId") or "") != TRUST_PROTOCOL_ID:
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Phone does not support the Cyclone 3.3 trust protocol.")
        if str(value.get("signatureAlgorithm") or "") != "ECDSA_P256_SHA256":
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Phone trust signature algorithm is unsupported.")
        phone_id = _required(value, "phoneId")
        phone_key = _required(value, "phonePublicKey")
        public = _load_phone_public_key(phone_key)
        public_der = public.public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
        if _sha256_b64url(public_der) != phone_id:
            raise DesktopRuntimeError(RuntimeErrorCode.TRUST_AUTH_FAILED, "Phone identity fingerprint did not match its public key.")
        return phone_id, phone_key

    def _adb_ready(self, device_id: str):
        session = self.fleet.get(device_id)
        state = str(getattr(session.adb_device, "state", "") or "")
        if state == "unauthorized":
            raise DesktopRuntimeError(RuntimeErrorCode.DEVICE_UNAUTHORIZED, "Approve USB debugging on the phone.", retryable=True)
        if state != "device":
            raise DesktopRuntimeError(RuntimeErrorCode.DEVICE_DISCONNECTED, "Phone is not ADB-ready.", retryable=True)
        return session

    def _restore_loop(self) -> None:
        while not self._stop.wait(1.0):
            try:
                items = self.fleet.list_public()
            except Exception:
                continue
            for item in items:
                if self._stop.is_set():
                    return
                device_id = str(item.get("deviceId") or item.get("id") or "")
                if not device_id:
                    continue
                try:
                    self._tick(device_id)
                except Exception:
                    pass

    def _tick(self, device_id: str) -> None:
        """One restore step for one phone: resume a trusted phone silently on every new USB session, finish a pending
        Allow without Glass open, and ask a new phone once when it's plugged in (alpha 88)."""
        session = self.fleet.get(device_id)
        usb = str(getattr(session, "usb_session_id", "") or "")
        with self._lock:
            if self._seen_usb.get(device_id) != usb:
                # A replug or a returning phone: try at once instead of waiting out an old backoff.
                self._seen_usb[device_id] = usb
                self._clear_retry(device_id)
            pending = self._pending.get(device_id)
            active = self._active.get(device_id)
            next_retry = self._next_retry_ms.get(device_id, 0)
        now = _now_ms()
        if pending is not None and pending.expires_at_ms > now:
            if now - self._last_complete_poll.get(device_id, 0) >= AUTO_COMPLETE_POLL_MS:
                self._last_complete_poll[device_id] = now
                try:
                    self.complete(device_id)
                except DesktopRuntimeError:
                    pass
            return
        if self.store.record(device_id) is None:
            self._maybe_auto_ask(device_id, session, usb)
            return
        if self._is_current(active, session, margin_ms=SESSION_REFRESH_MARGIN_MS) or next_retry > now:
            return
        if str(getattr(session.adb_device, "state", "") or "") != "device":
            return
        try:
            self.open_session(device_id)
        except Exception as exc:
            self._note_retry(device_id, exc)

    def _maybe_auto_ask(self, device_id: str, session: Any, usb: str) -> None:
        """A phone with Cyclone plugged into this PC for the first time gets one "Connect this PC?" on its own."""
        if not self.auto_ask or device_id in self._revoked:
            return
        if str(getattr(session.adb_device, "state", "") or "") != "device" or str(getattr(session, "state", "")) != "UNPAIRED":
            return
        # USB, and the cloud phones the owner added with their provider key (plan 44). Never a stray LAN address.
        if getattr(session, "source", "USB") not in {"USB", "CLOUD"}:
            return
        with self._lock:
            if self._asked_usb.get(device_id) == usb:
                return
            if _now_ms() - self._declined_at.get(device_id, -AUTO_ASK_DECLINE_COOLDOWN_MS) < AUTO_ASK_DECLINE_COOLDOWN_MS:
                return
            self._asked_usb[device_id] = usb
        try:
            self.begin(device_id, auto=True)
        except Exception as exc:
            self._note_retry(device_id, exc)

    def _note_retry(self, device_id: str, exc: Exception) -> None:
        with self._lock:
            attempt = self._retry_attempts.get(device_id, 0) + 1
            self._retry_attempts[device_id] = attempt
            delay = RECONNECT_BACKOFF_SECONDS[min(attempt - 1, len(RECONNECT_BACKOFF_SECONDS) - 1)]
            if getattr(exc, "code", None) in {RuntimeErrorCode.PHONE_LOCKED.value, "PHONE_LOCKED_OR_UNAVAILABLE"}:
                delay = LOCKED_RETRY_SECONDS
            self._next_retry_ms[device_id] = _now_ms() + delay * 1000
            self._last_error[device_id] = _safe_error(exc)
        try:
            session = self.fleet.get(device_id)
            session.bridge_ok = False
            session.bridge_error_class = exc.__class__.__name__
            session.bridge_last_error = _safe_error(exc)
        except Exception:
            pass

    def _clear_retry(self, device_id: str) -> None:
        self._retry_attempts.pop(device_id, None)
        self._next_retry_ms.pop(device_id, None)
        self._last_error.pop(device_id, None)

    @staticmethod
    def _raise_bridge(exc: BridgeOperationError) -> None:
        mapping = {
            "PROTOCOL_MISMATCH": RuntimeErrorCode.PROTOCOL_MISMATCH,
            "TRUST_EXPIRED": RuntimeErrorCode.TRUST_EXPIRED,
            "TRUST_REVOKED": RuntimeErrorCode.TRUST_REVOKED,
            "AUTH_REJECTED": RuntimeErrorCode.TRUST_AUTH_FAILED,
            "AUTH_SIGNATURE_INVALID": RuntimeErrorCode.TRUST_AUTH_FAILED,
            "PHONE_LOCKED_OR_UNAVAILABLE": RuntimeErrorCode.PHONE_LOCKED,
            "TRUST_REJECTED": RuntimeErrorCode.TRUST_REJECTED,
        }
        code = mapping.get(exc.code, RuntimeErrorCode.TRUST_AUTH_FAILED)
        raise DesktopRuntimeError(code, _plain_error(exc.code), retryable=exc.code in {"TRUST_EXPIRED", "PHONE_LOCKED_OR_UNAVAILABLE"}) from exc


def _load_phone_public_key(value: str) -> ec.EllipticCurvePublicKey:
    try:
        key = load_der_public_key(_unb64url(value))
    except Exception as exc:
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Phone identity public key is invalid.") from exc
    if not isinstance(key, ec.EllipticCurvePublicKey) or not isinstance(key.curve, ec.SECP256R1):
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Phone identity must use P-256.")
    return key


def _verify_phone_signature(public_key_base64: str, transcript: str, signature_base64: str) -> None:
    key = _load_phone_public_key(public_key_base64)
    try:
        key.verify(_unb64url(signature_base64), transcript.encode("utf-8"), ec.ECDSA(hashes.SHA256()))
    except (InvalidSignature, ValueError) as exc:
        raise DesktopRuntimeError(RuntimeErrorCode.TRUST_AUTH_FAILED, "Phone signature did not authenticate the Cyclone trust receipt.") from exc


def _required(value: dict[str, Any], key: str) -> str:
    result = str(value.get(key) or "").strip()
    if not result:
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, f"Phone trust response is missing {key}.")
    return result


def _safe_pc_label(value: str | None) -> str:
    raw = value or os.getenv("COMPUTERNAME") or platform.node() or "Cyclone PC"
    cleaned = " ".join("" if ord(char) < 32 else char for char in raw).strip()
    return (cleaned[:80] or "Cyclone PC")


def _plain_error(code: str) -> str:
    return {
        "PROTOCOL_MISMATCH": "Cyclone Mobile and PC Companion trust versions do not match.",
        "TRUST_EXPIRED": "Allow this PC confirmation expired; try again.",
        "TRUST_REVOKED": "This PC was revoked on the phone; allow it again.",
        "AUTH_REJECTED": "The trusted phone session was rejected; allow this PC again if needed.",
        "AUTH_SIGNATURE_INVALID": "Cyclone could not authenticate the trust exchange.",
        "PHONE_LOCKED_OR_UNAVAILABLE": "Unlock the phone to restore AI/Codex access.",
        "TRUST_REJECTED": "The phone answered Not now; this PC was not connected.",
    }.get(code, "Cyclone AI trust could not be restored.")


def _safe_error(exc: Exception) -> str:
    if isinstance(exc, DesktopRuntimeError):
        return exc.safe_message[:240]
    if isinstance(exc, BridgeOperationError):
        return _plain_error(exc.code)
    if isinstance(exc, (BridgeDisconnectedError, BridgeProtocolError)):
        return "Cyclone Android bridge is reconnecting."
    return (str(exc).replace("\r", " ").replace("\n", " ")[:240] or exc.__class__.__name__)
