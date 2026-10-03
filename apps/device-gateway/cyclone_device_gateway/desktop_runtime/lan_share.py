"""PC side of Wi-Fi screen share (``cyclone-lan-share-v1``), the AnyDesk-style path that needs no USB/ADB.

The phone listens on the local network while its owner shares the whole screen. This client proves it is the PC the
phone trusted (PC identity key from ``trust_v33``), checks the phone's signature with the phone key stored at
"Connect this PC?", agrees a fresh key per connection (ephemeral P-256 ECDH + HKDF-SHA256) and reads AES-256-GCM
records. The channel is view-only: frames come in, nothing controls the phone. Mirrors
``apps/mobile/.../share/LanShareProtocol.kt``; the HKDF vector in the tests is shared with the phone's tests.
"""
from __future__ import annotations

import base64
import hashlib
import json
import secrets
import socket
import struct
from dataclasses import dataclass
from typing import Callable, Iterator

from cryptography.exceptions import InvalidSignature, InvalidTag
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives.serialization import load_der_public_key

VERSION = "cyclone-lan-share-v1"
MAX_LINE_BYTES = 4096
MAX_RECORD_BYTES = 4 * 1024 * 1024
TYPE_JPEG = 1
TYPE_STATUS = 2


class LanShareError(Exception):
    """Handshake or stream failure; ``code`` is safe to show."""

    def __init__(self, code: str, message: str = ""):
        super().__init__(message or code)
        self.code = code


def b64(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def unb64(value: str) -> bytes:
    return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))


def transcript(phone_id: str, trust_id: str, phone_nonce: str, pc_nonce: str, phone_eph: str, pc_eph: str) -> str:
    return "\n".join([VERSION, phone_id, trust_id, phone_nonce, pc_nonce, phone_eph, pc_eph])


def hkdf(ikm: bytes, salt: bytes, info: str, length: int = 32) -> bytes:
    return HKDF(algorithm=hashes.SHA256(), length=length, salt=salt, info=info.encode("utf-8")).derive(ikm)


def derive_keys(shared_secret: bytes, text: str) -> tuple[bytes, bytes]:
    salt = hashlib.sha256(text.encode("utf-8")).digest()
    return hkdf(shared_secret, salt, f"{VERSION} phone->pc"), hkdf(shared_secret, salt, f"{VERSION} pc->phone")


class Opener:
    """AES-256-GCM with 96-bit counter nonces that must arrive in order (no replay, no reordering)."""

    def __init__(self, key: bytes):
        self._aead = AESGCM(key)
        self._expected = 0

    def open(self, sealed: bytes) -> bytes:
        if len(sealed) <= 28:
            raise LanShareError("RECORD_INVALID")
        iv = sealed[:12]
        counter = struct.unpack(">Q", iv[4:12])[0]
        if iv[:4] != b"\x00\x00\x00\x00" or counter != self._expected:
            raise LanShareError("RECORD_OUT_OF_ORDER")
        self._expected += 1
        try:
            return self._aead.decrypt(iv, sealed[12:], VERSION.encode("utf-8"))
        except InvalidTag as exc:
            raise LanShareError("RECORD_TAMPERED") from exc


@dataclass(frozen=True)
class PhoneTrust:
    """What the PC stored when the phone allowed it (``trust_v33`` record)."""

    trust_id: str
    phone_id: str
    phone_public_key: str  # base64url DER SubjectPublicKeyInfo


class LanShareClient:
    def __init__(
        self,
        host: str,
        port: int,
        trust: PhoneTrust,
        sign: Callable[[str], str],
        *,
        timeout_s: float = 5.0,
        connect: Callable[[tuple[str, int], float], socket.socket] | None = None,
    ):
        self.host = host
        self.port = port
        self.trust = trust
        self._sign = sign
        self._timeout = timeout_s
        self._connect = connect or (lambda address, timeout: socket.create_connection(address, timeout=timeout))
        self._sock: socket.socket | None = None
        self._reader = None
        self._opener: Opener | None = None

    def __enter__(self) -> "LanShareClient":
        self.open()
        return self

    def __exit__(self, *exc) -> None:
        self.close()

    def open(self) -> None:
        sock = self._connect((self.host, self.port), self._timeout)
        sock.settimeout(self._timeout)
        self._sock = sock
        self._reader = sock.makefile("rb")
        hello = self._read_line()
        if hello.get("v") != VERSION:
            raise LanShareError("PROTOCOL_MISMATCH")
        phone_id = str(hello.get("phoneId") or "")
        phone_nonce = str(hello.get("phoneNonce") or "")
        phone_eph = str(hello.get("phoneEph") or "")
        if phone_id != self.trust.phone_id:
            raise LanShareError("WRONG_PHONE", "A different phone answered at this address.")
        ephemeral = ec.generate_private_key(ec.SECP256R1())
        pc_eph = b64(ephemeral.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo))
        pc_nonce = b64(secrets.token_bytes(16))
        text = transcript(phone_id, self.trust.trust_id, phone_nonce, pc_nonce, phone_eph, pc_eph)
        self._write_line({"trustId": self.trust.trust_id, "pcNonce": pc_nonce, "pcEph": pc_eph, "pcSig": self._sign(text)})
        reply = self._read_line()
        if reply.get("ok") is not True:
            raise LanShareError(str(reply.get("code") or "REFUSED"), "The phone refused this PC.")
        try:
            phone_key = load_der_public_key(unb64(self.trust.phone_public_key))
            phone_key.verify(unb64(str(reply.get("phoneSig") or "")), text.encode("utf-8"), ec.ECDSA(hashes.SHA256()))  # type: ignore[union-attr]
        except (InvalidSignature, ValueError, TypeError) as exc:
            raise LanShareError("PHONE_NOT_VERIFIED", "The phone at this address could not prove who it is.") from exc
        peer = load_der_public_key(unb64(phone_eph))
        shared = ephemeral.exchange(ec.ECDH(), peer)  # type: ignore[arg-type]
        phone_to_pc, _ = derive_keys(shared, text)
        self._opener = Opener(phone_to_pc)

    def records(self) -> Iterator[tuple[int, bytes]]:
        """Yield (type, payload) until the phone stops sharing or the network drops."""
        if self._opener is None or self._reader is None:
            raise LanShareError("NOT_OPEN")
        while True:
            header = self._reader.read(4)
            if len(header) < 4:
                return
            size = struct.unpack(">I", header)[0]
            if not 28 < size <= MAX_RECORD_BYTES:
                raise LanShareError("RECORD_INVALID")
            sealed = self._reader.read(size)
            if len(sealed) < size:
                return
            plain = self._opener.open(sealed)
            yield plain[0], plain[1:]

    def close(self) -> None:
        for closer in (self._reader, self._sock):
            try:
                if closer is not None:
                    closer.close()
            except OSError:
                pass
        self._reader = None
        self._sock = None

    def _read_line(self) -> dict:
        assert self._reader is not None
        line = self._reader.readline(MAX_LINE_BYTES + 1)
        if not line or len(line) > MAX_LINE_BYTES or not line.endswith(b"\n"):
            raise LanShareError("HANDSHAKE_FAILED")
        try:
            value = json.loads(line.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise LanShareError("HANDSHAKE_FAILED") from exc
        if not isinstance(value, dict):
            raise LanShareError("HANDSHAKE_FAILED")
        return value

    def _write_line(self, value: dict) -> None:
        assert self._sock is not None
        self._sock.sendall((json.dumps(value, separators=(",", ":")) + "\n").encode("utf-8"))


class LanShareDirectory:
    """Where each trusted phone shares its screen on the local network, learned from ``share.status``.

    The last good address is kept, so the live view survives the cable being unplugged while the phone keeps sharing.
    """

    STATUS_TTL_S = 3.0

    def __init__(self, status: Callable[[str], dict], trust_record: Callable[[str], dict | None], sign: Callable[[str], str],
                 *, clock: Callable[[], float] | None = None, client_factory: Callable[..., LanShareClient] | None = None):
        import time as _time

        self._status = status
        self._trust_record = trust_record
        self._sign = sign
        self._clock = clock or _time.monotonic
        self._client_factory = client_factory or LanShareClient
        self._cache: dict[str, tuple[float, dict]] = {}
        self._last_good: dict[str, dict] = {}

    def share_status(self, device_id: str) -> dict | None:
        now = self._clock()
        cached = self._cache.get(device_id)
        if cached and now - cached[0] < self.STATUS_TTL_S:
            return cached[1]
        try:
            value = self._status(device_id)
        except Exception:
            # Bridge unreachable (cable out, ADB restarting): the phone may still be sharing over Wi-Fi.
            value = self._last_good.get(device_id)
        if value and value.get("sharing"):
            self._last_good[device_id] = value
        elif value is not None:
            self._last_good.pop(device_id, None)
        self._cache[device_id] = (now, value or {})
        return value

    def available(self, device_id: str) -> bool:
        value = self.share_status(device_id)
        return bool(value and value.get("sharing") and value.get("addresses") and value.get("port"))

    def open(self, device_id: str) -> LanShareClient | None:
        value = self.share_status(device_id)
        record = self._trust_record(device_id) or {}
        trust_id, phone_id, phone_key = (str(record.get(k) or "") for k in ("trustId", "phoneId", "phonePublicKey"))
        if not value or not value.get("sharing") or not (trust_id and phone_id and phone_key):
            return None
        if value.get("phoneId") and value.get("phoneId") != phone_id:
            return None
        trust = PhoneTrust(trust_id=trust_id, phone_id=phone_id, phone_public_key=phone_key)
        for address in value.get("addresses") or []:
            client = self._client_factory(str(address), int(value["port"]), trust, self._sign, timeout_s=5.0)
            try:
                client.open()
                return client
            except (OSError, LanShareError):
                client.close()
        self._cache.pop(device_id, None)
        return None

    def for_device(self, device_id: str) -> "DeviceLanShare":
        return DeviceLanShare(self, device_id)


@dataclass(frozen=True)
class DeviceLanShare:
    directory: LanShareDirectory
    device_id: str

    def available(self) -> bool:
        return self.directory.available(self.device_id)

    def open(self) -> LanShareClient | None:
        return self.directory.open(self.device_id)
