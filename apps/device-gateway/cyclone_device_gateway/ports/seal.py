"""Sealing a verification code to one phone (plan 48, run 4).

A code a plugin delivers on ``code.in`` goes to the phone as an HPKE envelope (RFC 9180, base mode), the same suite the
phone already opens for sealed vault delivery: DHKEM(P-256, HKDF-SHA256) + HKDF-SHA256 + AES-256-GCM. It is sealed to
the phone's device key (Android Keystore, ``cc.key``) and bound to that key, the run, a lease id and an expiry in the
associated data. The phone opens it, fills it once with ``vault_fill`` and forgets it. The PC never stores the code, and
neither the PC log nor any model ever sees it.
"""
from __future__ import annotations

import base64
import hashlib
import json
import secrets
import time

from cryptography.hazmat.primitives import hashes, hmac
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

INFO = b"cyclone-port-code/v1"
KEM_ID, KDF_ID, AEAD_ID = 0x0010, 0x0001, 0x0002
CODE_TTL_S = 300


def _i2osp(value: int, length: int) -> bytes:
    return value.to_bytes(length, "big")


def _extract(salt: bytes, ikm: bytes) -> bytes:
    h = hmac.HMAC(salt or b"\x00" * 32, hashes.SHA256())
    h.update(ikm)
    return h.finalize()


def _expand(prk: bytes, info: bytes, length: int) -> bytes:
    out, block, counter = b"", b"", 1
    while len(out) < length:
        h = hmac.HMAC(prk, hashes.SHA256())
        h.update(block + info + bytes([counter]))
        block = h.finalize()
        out += block
        counter += 1
    return out[:length]


def _labeled_extract(suite: bytes, salt: bytes, label: bytes, ikm: bytes) -> bytes:
    return _extract(salt, b"HPKE-v1" + suite + label + ikm)


def _labeled_expand(suite: bytes, prk: bytes, label: bytes, info: bytes, length: int) -> bytes:
    return _expand(prk, _i2osp(length, 2) + b"HPKE-v1" + suite + label + info, length)


def seal(recipient_public: bytes, plaintext: bytes, info: bytes, aad: bytes,
         ephemeral: ec.EllipticCurvePrivateKey | None = None) -> tuple[bytes, bytes]:
    """HPKE base-mode single-shot seal. Returns (enc, ciphertext). ``ephemeral`` is for test vectors only."""
    if len(recipient_public) != 65 or recipient_public[0] != 4:
        raise ValueError("the phone's key must be an uncompressed P-256 point")
    pk_r = ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), recipient_public)
    sk_e = ephemeral or ec.generate_private_key(ec.SECP256R1())
    enc = sk_e.public_key().public_bytes(Encoding.X962, PublicFormat.UncompressedPoint)
    dh = sk_e.exchange(ec.ECDH(), pk_r)
    kem_suite = b"KEM" + _i2osp(KEM_ID, 2)
    eae_prk = _labeled_extract(kem_suite, b"", b"eae_prk", dh)
    shared = _labeled_expand(kem_suite, eae_prk, b"shared_secret", enc + recipient_public, 32)
    suite = b"HPKE" + _i2osp(KEM_ID, 2) + _i2osp(KDF_ID, 2) + _i2osp(AEAD_ID, 2)
    psk_id_hash = _labeled_extract(suite, b"", b"psk_id_hash", b"")
    info_hash = _labeled_extract(suite, b"", b"info_hash", info)
    context = b"\x00" + psk_id_hash + info_hash
    secret = _labeled_extract(suite, shared, b"secret", b"")
    key = _labeled_expand(suite, secret, b"key", context, 32)
    nonce = _labeled_expand(suite, secret, b"base_nonce", context, 12)
    return enc, AESGCM(key).encrypt(nonce, plaintext, aad)


def fingerprint(public_key: bytes) -> str:
    """The phone's key fingerprint as ``cc.key`` shows it: SHA-256, first 16 bytes, 8 groups of 4 hex digits."""
    digest = hashlib.sha256(public_key).hexdigest().upper()[:32]
    return " ".join(digest[i:i + 4] for i in range(0, 32, 4))


def code_envelope(public_key_b64: str, device_fingerprint: str, run_id: str, place: str, code: str, *,
                  now_ms: int | None = None, lease: str | None = None,
                  ephemeral: ec.EllipticCurvePrivateKey | None = None) -> dict[str, str]:
    """The envelope ``ports.answer`` carries: {leaseId, enc, ct, aad}. The aad binds the phone's key, the run, the app
    or site the code is for, a lease id the phone accepts once, and an expiry."""
    public = base64.b64decode(public_key_b64)
    now_ms = int(time.time() * 1000) if now_ms is None else now_ms
    lease = lease or "ls_" + secrets.token_urlsafe(18)
    bound = {"deviceKey": device_fingerprint, "expiresAt": now_ms + CODE_TTL_S * 1000, "leaseId": lease,
             "place": place, "runId": run_id, "slot": "code"}
    aad = json.dumps(bound, separators=(",", ":"), sort_keys=True)
    enc, ct = seal(public, code.encode(), INFO, aad.encode(), ephemeral)
    return {"leaseId": lease, "enc": base64.b64encode(enc).decode(), "ct": base64.b64encode(ct).decode(), "aad": aad}
