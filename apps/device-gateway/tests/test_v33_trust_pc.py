from __future__ import annotations

import base64
import hashlib
import json
from types import SimpleNamespace

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.serialization import load_der_public_key

from cyclone_device_gateway.cyclone_bridge.client import BridgeOperationError
from cyclone_device_gateway.desktop_runtime.trust_v33 import (
    PCTrustCoordinator,
    PCTrustStore,
    TRUST_PROTOCOL_ID,
    TRUST_PROTOCOL_VERSION,
    session_receipt_transcript,
    session_transcript,
    trust_receipt_transcript,
    trust_transcript,
)


def b64(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).decode("ascii").rstrip("=")


def unb64(value: str) -> bytes:
    return base64.urlsafe_b64decode((value + "=" * ((4 - len(value) % 4) % 4)).encode("ascii"))


def digest(value: bytes) -> str:
    return b64(hashlib.sha256(value).digest())


class FakePhone:
    def __init__(self):
        self.key = ec.generate_private_key(ec.SECP256R1())
        self.public_der = self.key.public_key().public_bytes(
            serialization.Encoding.DER,
            serialization.PublicFormat.SubjectPublicKeyInfo,
        )
        self.public = b64(self.public_der)
        self.phone_id = digest(self.public_der)
        self.pc_public = ""
        self.pc_id = ""
        self.pc_nonce = ""
        self.challenge_id = "trust-challenge"
        self.phone_nonce = "phone-nonce-value-1234567890"
        self.trust_expires = 4_000_000_000_000
        self.allowed = False
        self.trust_id = "trust-id-value"
        self.generation = 1
        self.session_challenge_id = "session-challenge"
        self.session_phone_nonce = "session-phone-nonce-12345678"
        self.session_expires = 4_000_000_030_000
        self.session_id = "session-id-value"
        self.session_token = "session-token-that-is-never-persisted-123456789"
        self.session_token_expires = 4_000_000_300_000
        self.active_token = ""

    def sign(self, value: str) -> str:
        return b64(self.key.sign(value.encode("utf-8"), ec.ECDSA(hashes.SHA256())))

    def verify_pc(self, value: str, signature: str) -> None:
        key = load_der_public_key(unb64(self.pc_public))
        assert isinstance(key, ec.EllipticCurvePublicKey)
        key.verify(unb64(signature), value.encode("utf-8"), ec.ECDSA(hashes.SHA256()))

    def call(self, op: str, args: dict, auth: str):
        assert args.get("protocolVersion") == TRUST_PROTOCOL_VERSION or op == "bridge.status"
        if op == "trust.negotiate":
            return {
                "protocolId": TRUST_PROTOCOL_ID,
                "protocolVersion": TRUST_PROTOCOL_VERSION,
                "signatureAlgorithm": "ECDSA_P256_SHA256",
                "phoneId": self.phone_id,
                "phonePublicKey": self.public,
                "capabilities": ["trust.device-bound", "trust.phone-confirmation", "trust.session-short-lived"],
            }
        if op == "trust.begin":
            self.pc_public = args["pcPublicKey"]
            self.pc_id = args["pcId"]
            self.pc_nonce = args["pcNonce"]
            expected_pc_id = digest(unb64(self.pc_public))
            assert self.pc_id == expected_pc_id
            transcript = (
                f"{TRUST_PROTOCOL_ID}\n"
                f"purpose=trust-complete\n"
                f"protocol={TRUST_PROTOCOL_VERSION}\n"
                f"challengeId={self.challenge_id}\n"
                f"phoneId={self.phone_id}\n"
                f"pcId={self.pc_id}\n"
                f"pcNonce={self.pc_nonce}\n"
                f"phoneNonce={self.phone_nonce}\n"
                f"expiresAtMs={self.trust_expires}\n"
            )
            return {
                "protocolVersion": TRUST_PROTOCOL_VERSION,
                "challengeId": self.challenge_id,
                "phoneId": self.phone_id,
                "phonePublicKey": self.public,
                "pcId": self.pc_id,
                "phoneNonce": self.phone_nonce,
                "expiresAtMs": self.trust_expires,
                "confirmationRequired": True,
                "confirmationState": "PENDING",
                "transcript": transcript,
            }
        if op == "trust.complete":
            if getattr(self, "rejected", False):
                raise BridgeOperationError("TRUST_REJECTED")
            if not self.allowed:
                raise BridgeOperationError("PHONE_CONFIRMATION_REQUIRED")
            transcript = trust_transcript(
                challenge_id=self.challenge_id,
                phone_id=self.phone_id,
                pc_id=self.pc_id,
                pc_nonce=self.pc_nonce,
                phone_nonce=self.phone_nonce,
                expires_at_ms=self.trust_expires,
            )
            self.verify_pc(transcript, args["pcSignature"])
            receipt = trust_receipt_transcript(
                challenge_id=self.challenge_id,
                trust_id=self.trust_id,
                phone_id=self.phone_id,
                pc_id=self.pc_id,
                generation=self.generation,
            )
            return {
                "trusted": True,
                "trustId": self.trust_id,
                "phoneId": self.phone_id,
                "pcId": self.pc_id,
                "generation": self.generation,
                "phoneSignature": self.sign(receipt),
                "signatureAlgorithm": "ECDSA_P256_SHA256",
                "sessionRequired": True,
            }
        if op == "trust.session.begin":
            assert args["trustId"] == self.trust_id
            assert args["generation"] == self.generation
            pc_nonce = args["pcNonce"]
            transcript = session_transcript(
                challenge_id=self.session_challenge_id,
                trust_id=self.trust_id,
                phone_id=self.phone_id,
                pc_id=self.pc_id,
                generation=self.generation,
                pc_nonce=pc_nonce,
                phone_nonce=self.session_phone_nonce,
                expires_at_ms=self.session_expires,
            )
            self.session_pc_nonce = pc_nonce
            self.session_transcript = transcript
            return {
                "protocolVersion": TRUST_PROTOCOL_VERSION,
                "challengeId": self.session_challenge_id,
                "trustId": self.trust_id,
                "phoneId": self.phone_id,
                "pcId": self.pc_id,
                "generation": self.generation,
                "phoneNonce": self.session_phone_nonce,
                "expiresAtMs": self.session_expires,
                "transcript": transcript,
            }
        if op == "trust.session.complete":
            self.verify_pc(self.session_transcript, args["pcSignature"])
            self.active_token = self.session_token
            receipt = session_receipt_transcript(
                session_id=self.session_id,
                trust_id=self.trust_id,
                phone_id=self.phone_id,
                pc_id=self.pc_id,
                generation=self.generation,
                expires_at_ms=self.session_token_expires,
                token_digest=digest(self.session_token.encode("utf-8")),
            )
            return {
                "authenticated": True,
                "sessionId": self.session_id,
                "sessionToken": self.session_token,
                "expiresAtMs": self.session_token_expires,
                "trustId": self.trust_id,
                "generation": self.generation,
                "phoneSignature": self.sign(receipt),
                "signatureAlgorithm": "ECDSA_P256_SHA256",
            }
        if op == "bridge.status":
            assert auth == self.active_token
            return {"gatewayEnabled": True, "socketListening": True, "accessibilityConnected": True}
        if op == "trust.revoke":
            assert auth == self.active_token
            return {"revoked": True, "trustId": self.trust_id}
        raise AssertionError(op)


class FakeBridge:
    def __init__(self, phone: FakePhone, token: str):
        self.phone = phone
        self.token = token

    def request_unauthenticated(self, op, args=None, request_id=None):
        return self.phone.call(op, args or {}, "")

    def request(self, op, args=None, request_id=None):
        return self.phone.call(op, args or {}, self.token)


class FakeSession:
    def __init__(self, phone: FakePhone):
        self.device_id = "dev_test"
        self.adb_device = SimpleNamespace(state="device")
        self.credential = None
        self.bridge_ok = None
        self.bridge_last_error = None
        self.bridge_error_class = None
        self.last_heartbeat_ms = None
        self.phone = phone

    def bridge(self, token=None, auto_forward=False):
        return FakeBridge(self.phone, token or self.credential or "")


class FakeFleet:
    def __init__(self, session: FakeSession):
        self.session = session

    def get(self, device_id):
        assert device_id == self.session.device_id
        return self.session

    def list_public(self):
        return [{"deviceId": self.session.device_id}]

    def remember_credential(self, session, credential):
        assert session is self.session
        session.credential = credential


def test_v33_transcript_fixture_is_exact():
    value = trust_transcript(
        challenge_id="c",
        phone_id="phone",
        pc_id="pc",
        pc_nonce="nonce-pc",
        phone_nonce="nonce-phone",
        expires_at_ms=42,
    )
    assert value == (
        "cyclone.android.trust.v3\n"
        "purpose=trust-complete\n"
        "protocol=3.3\n"
        "challengeId=c\n"
        "phoneId=phone\n"
        "pcId=pc\n"
        "pcNonce=nonce-pc\n"
        "phoneNonce=nonce-phone\n"
        "expiresAtMs=42\n"
    )


def test_pc_trust_requires_phone_confirmation_and_never_persists_session_secret(tmp_path):
    phone = FakePhone()
    session = FakeSession(phone)
    fleet = FakeFleet(session)
    store = PCTrustStore(tmp_path)
    coordinator = PCTrustCoordinator(fleet, store, pc_label="Cyclone Test PC")

    begun = coordinator.begin("dev_test")
    assert begun["state"] == "CONFIRMATION_REQUIRED"
    assert begun["confirmationRequired"] is True
    assert begun["phoneConfirmation"] == "Allow this PC"
    assert len(begun["matchCode"]) == 6 and begun["matchCode"].isdigit()
    assert coordinator.status("dev_test")["matchCode"] == begun["matchCode"]
    assert coordinator.status("dev_test")["pcLabel"] == coordinator.pc_label
    assert session.credential is None

    waiting = coordinator.complete("dev_test")
    assert waiting["completed"] is False
    assert waiting["confirmationRequired"] is True
    assert session.credential is None

    phone.allowed = True
    completed = coordinator.complete("dev_test")
    assert completed["completed"] is True
    assert completed["trusted"] is True
    assert completed["sessionReady"] is True
    assert session.credential == phone.session_token
    assert store.record("dev_test")["phoneId"] == phone.phone_id

    persisted = json.dumps(store.load(), sort_keys=True)
    assert phone.session_token not in persisted
    assert "identityPrivateKeyPem" in persisted
    assert coordinator.status("dev_test")["sessionSecretPersisted"] is False


def test_revoke_removes_persisted_trust_record(tmp_path):
    phone = FakePhone()
    phone.allowed = True
    session = FakeSession(phone)
    fleet = FakeFleet(session)
    store = PCTrustStore(tmp_path)
    coordinator = PCTrustCoordinator(fleet, store)

    coordinator.begin("dev_test")
    coordinator.complete("dev_test")
    assert store.record("dev_test") is not None

    result = coordinator.revoke("dev_test")
    assert result["revoked"] is True
    assert result["localTrustCleared"] is True
    assert result["phoneRevocationConfirmed"] is True
    assert result["state"] == "REVOKED"
    assert store.record("dev_test") is None
    assert session.credential is None


def test_revoke_forgets_local_record_when_connected_phone_identity_changed(tmp_path):
    original_phone = FakePhone()
    original_phone.allowed = True
    session = FakeSession(original_phone)
    fleet = FakeFleet(session)
    store = PCTrustStore(tmp_path)
    coordinator = PCTrustCoordinator(fleet, store)

    coordinator.begin("dev_test")
    coordinator.complete("dev_test")
    assert store.record("dev_test")["phoneId"] == original_phone.phone_id

    coordinator.stop()
    replacement_phone = FakePhone()
    session.phone = replacement_phone

    result = coordinator.revoke("dev_test")

    assert result["revoked"] is True
    assert result["state"] == "REVOKED"
    assert result["localTrustCleared"] is True
    assert result["phoneRevocationConfirmed"] is False
    assert result["phoneRevocationError"] == "TRUST_AUTH_FAILED"
    assert store.record("dev_test") is None
    assert session.credential is None


def test_revoke_forgets_local_record_while_phone_is_offline(tmp_path):
    phone = FakePhone()
    phone.allowed = True
    session = FakeSession(phone)
    fleet = FakeFleet(session)
    store = PCTrustStore(tmp_path)
    coordinator = PCTrustCoordinator(fleet, store)

    coordinator.begin("dev_test")
    coordinator.complete("dev_test")
    coordinator.stop()
    session.adb_device.state = "offline"

    result = coordinator.revoke("dev_test")

    assert result["localTrustCleared"] is True
    assert result["phoneRevocationConfirmed"] is False
    assert result["phoneRevocationError"] == "DEVICE_DISCONNECTED"
    assert store.record("dev_test") is None


def test_trust_match_code_is_shared_with_the_phone():
    # Same transcript and code as GatewayTrustMatchCodeTest on the phone.
    from cyclone_device_gateway.desktop_runtime.trust_v33 import trust_match_code, trust_transcript

    transcript = trust_transcript(
        challenge_id="challenge-1", phone_id="phone-1", pc_id="pc-1",
        pc_nonce="pcnonce", phone_nonce="phonenonce", expires_at_ms=1700000000000,
    )
    assert trust_match_code(transcript) == "173715"


def test_not_now_on_the_phone_is_a_plain_rejection_and_clears_the_request(tmp_path):
    phone = FakePhone()
    session = FakeSession(phone)
    coordinator = PCTrustCoordinator(FakeFleet(session), PCTrustStore(tmp_path), pc_label="Cyclone Test PC")
    coordinator.begin("dev_test")
    phone.rejected = True
    import pytest

    from cyclone_device_gateway.desktop_runtime.models import DesktopRuntimeError

    with pytest.raises(DesktopRuntimeError) as err:
        coordinator.complete("dev_test")
    assert err.value.code == "TRUST_REJECTED"
    assert "Not now" in err.value.safe_message
    status = coordinator.status("dev_test")
    assert status["state"] == "UNPAIRED" and status["matchCode"] is None
