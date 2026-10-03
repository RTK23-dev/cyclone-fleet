from __future__ import annotations

import json
import socket
import struct
import threading

import pytest
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.serialization import load_der_public_key

from cyclone_device_gateway.desktop_runtime import lan_share
from cyclone_device_gateway.desktop_runtime.lan_share import (
    LanShareClient,
    LanShareError,
    Opener,
    PhoneTrust,
    b64,
    derive_keys,
    hkdf,
    transcript,
    unb64,
)


def _pub(key) -> str:
    return b64(key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo))


def _sign(key, text: str) -> str:
    return b64(key.sign(text.encode("utf-8"), ec.ECDSA(hashes.SHA256())))


class FakePhone:
    """Python mirror of LanShareServer.kt: hello, verify PC, sign, then sealed records."""

    def __init__(self, phone_key, pc_public_key: str, *, trusted: bool = True, records: list[bytes] | None = None):
        self.phone_key = phone_key
        self.pc_public_key = pc_public_key
        self.trusted = trusted
        self.records = records if records is not None else [bytes([lan_share.TYPE_JPEG]) + b"\xff\xd8jpeg\xff\xd9"]
        self.listener = socket.socket()
        self.listener.bind(("127.0.0.1", 0))
        self.listener.listen(1)
        self.port = self.listener.getsockname()[1]
        self.thread = threading.Thread(target=self._serve, daemon=True)
        self.thread.start()

    def _serve(self) -> None:
        conn, _ = self.listener.accept()
        with conn:
            reader = conn.makefile("rb")
            eph = ec.generate_private_key(ec.SECP256R1())
            phone_nonce = b64(b"n" * 16)
            hello = {"v": lan_share.VERSION, "phoneId": "phone-1", "phoneNonce": phone_nonce, "phoneEph": _pub(eph)}
            conn.sendall((json.dumps(hello) + "\n").encode())
            pc = json.loads(reader.readline())
            text = transcript("phone-1", pc["trustId"], phone_nonce, pc["pcNonce"], hello["phoneEph"], pc["pcEph"])
            try:
                if not self.trusted:
                    raise ValueError("NOT_TRUSTED")
                load_der_public_key(unb64(self.pc_public_key)).verify(unb64(pc["pcSig"]), text.encode(), ec.ECDSA(hashes.SHA256()))
            except Exception:
                conn.sendall(b'{"ok":false,"code":"NOT_TRUSTED"}\n')
                return
            conn.sendall((json.dumps({"ok": True, "phoneSig": _sign(self.phone_key, text)}) + "\n").encode())
            shared = eph.exchange(ec.ECDH(), load_der_public_key(unb64(pc["pcEph"])))
            key, _ = derive_keys(shared, text)
            aead = AESGCM(key)
            for counter, payload in enumerate(self.records):
                iv = b"\x00\x00\x00\x00" + struct.pack(">Q", counter)
                sealed = iv + aead.encrypt(iv, payload, lan_share.VERSION.encode())
                conn.sendall(struct.pack(">I", len(sealed)) + sealed)


def _client(port: int, phone_key, pc_key, phone_id: str = "phone-1") -> LanShareClient:
    trust = PhoneTrust(trust_id="trust-1", phone_id=phone_id, phone_public_key=_pub(phone_key))
    return LanShareClient("127.0.0.1", port, trust, lambda text: _sign(pc_key, text))


def test_hkdf_matches_the_phone_vector():
    # Same vector as LanShareServerTest.hkdfMatchesTheSharedVector on the phone.
    assert b64(hkdf(b"cyclone", b"salt", f"{lan_share.VERSION} phone->pc")) == "m8as1vvqDP8l5-qa4kxJXIfbBQWBtkEUzw4OXxMYlbE"


def test_trusted_pc_reads_encrypted_frames_from_the_phone():
    phone_key, pc_key = ec.generate_private_key(ec.SECP256R1()), ec.generate_private_key(ec.SECP256R1())
    phone = FakePhone(phone_key, _pub(pc_key))
    with _client(phone.port, phone_key, pc_key) as client:
        kind, payload = next(client.records())
    assert kind == lan_share.TYPE_JPEG and payload == b"\xff\xd8jpeg\xff\xd9"


def test_refusal_and_impostors_are_named():
    phone_key, pc_key = ec.generate_private_key(ec.SECP256R1()), ec.generate_private_key(ec.SECP256R1())
    refused = FakePhone(phone_key, _pub(pc_key), trusted=False)
    with pytest.raises(LanShareError) as error:
        _client(refused.port, phone_key, pc_key).open()
    assert error.value.code == "NOT_TRUSTED"
    # A different phone key at the address: the PC never reads its frames.
    impostor = FakePhone(ec.generate_private_key(ec.SECP256R1()), _pub(pc_key))
    with pytest.raises(LanShareError) as error:
        _client(impostor.port, phone_key, pc_key).open()
    assert error.value.code == "PHONE_NOT_VERIFIED"
    other = FakePhone(phone_key, _pub(pc_key))
    with pytest.raises(LanShareError) as error:
        _client(other.port, phone_key, pc_key, phone_id="phone-2").open()
    assert error.value.code == "WRONG_PHONE"


def test_records_cannot_be_replayed_or_tampered():
    key = bytes(range(32))
    aead = AESGCM(key)
    iv0 = b"\x00" * 4 + struct.pack(">Q", 0)
    first = iv0 + aead.encrypt(iv0, b"a", lan_share.VERSION.encode())
    opener = Opener(key)
    assert opener.open(first) == b"a"
    with pytest.raises(LanShareError) as error:
        opener.open(first)
    assert error.value.code == "RECORD_OUT_OF_ORDER"
    tampered = first[:-1] + bytes([first[-1] ^ 1])
    with pytest.raises(LanShareError) as error:
        Opener(key).open(tampered)
    assert error.value.code == "RECORD_TAMPERED"


# --- directory, contract and live-view source ---------------------------------------------------------------------

from cyclone_device_gateway.desktop_runtime.lan_share import LanShareDirectory  # noqa: E402
from cyclone_device_gateway.desktop_runtime.v5_contract import validate_android_response  # noqa: E402
from cyclone_device_gateway.desktop_runtime.models import DesktopRuntimeError  # noqa: E402

PHONE_ID = "A" * 43
SHARING = {"sharing": True, "port": 47823, "addresses": ["192.168.1.20"], "phoneId": PHONE_ID, "protocol": lan_share.VERSION}
IDLE = {"sharing": False, "port": None, "addresses": [], "phoneId": PHONE_ID, "protocol": lan_share.VERSION}
RECORD = {"trustId": "trust-1", "phoneId": PHONE_ID, "phonePublicKey": "k"}


class FakeClient:
    opened: list[tuple[str, int]] = []

    def __init__(self, host, port, trust, sign, timeout_s=5.0):
        self.host, self.port, self.trust = host, port, trust

    def open(self):
        FakeClient.opened.append((self.host, self.port))

    def close(self):
        pass


def test_directory_remembers_the_share_when_the_cable_is_pulled():
    now = [0.0]
    replies = [SHARING]
    def status(device_id):
        reply = replies[0]
        if isinstance(reply, Exception):
            raise reply
        return reply
    directory = LanShareDirectory(status, lambda d: RECORD, lambda t: "sig", clock=lambda: now[0], client_factory=FakeClient)
    assert directory.available("d1")
    client = directory.open("d1")
    assert client is not None and client.host == "192.168.1.20" and client.trust.trust_id == "trust-1"
    replies[0] = OSError("bridge gone")  # cable out: the phone keeps sharing over Wi-Fi
    now[0] += 10
    assert directory.available("d1")
    replies[0] = IDLE  # the phone stopped sharing
    now[0] += 10
    assert not directory.available("d1")
    assert directory.open("d1") is None


def test_directory_never_opens_for_an_untrusted_or_different_phone():
    directory = LanShareDirectory(lambda d: SHARING, lambda d: None, lambda t: "sig", client_factory=FakeClient)
    assert directory.open("d1") is None
    other = LanShareDirectory(lambda d: {**SHARING, "phoneId": "B" * 43}, lambda d: RECORD, lambda t: "sig", client_factory=FakeClient)
    assert other.open("d1") is None


@pytest.mark.parametrize("value", [
    {**SHARING, "addresses": ["8.8.8.8"]},
    {**SHARING, "addresses": ["127.0.0.1"]},
    {**SHARING, "port": 0},
    {**IDLE, "port": 5},
    {**SHARING, "protocol": "other"},
    {**SHARING, "token": "x"},
])
def test_share_status_from_the_phone_is_validated(value):
    with pytest.raises(DesktopRuntimeError):
        validate_android_response("share.status", value, {})


def test_share_status_and_request_shapes():
    assert validate_android_response("share.status", SHARING, {}) == SHARING
    assert validate_android_response("share.status", IDLE, {}) == IDLE
    assert validate_android_response("share.request", {"prompted": True, "sharing": False}, {"pcLabel": "DESK"})
    with pytest.raises(DesktopRuntimeError):
        validate_android_response("share.request", {"prompted": "yes", "sharing": False}, {})


def test_live_view_prefers_the_phone_wifi_share_and_falls_back_to_adb():
    from test_desktop_fleet_runtime import paired_session_for_services, unavailable_media_backend
    from cyclone_device_gateway.desktop_runtime.video import VideoFleetLimiter, VideoStreamController

    class Share:
        def __init__(self):
            self.sharing = True

        def available(self):
            return self.sharing

        def open(self):
            if not self.sharing:
                return None
            share = self

            class Client:
                def records(self):
                    yield lan_share.TYPE_JPEG, b"\xff\xd8wifi\xff\xd9"
                    share.sharing = False  # the phone stops sharing after one frame

                def close(self):
                    pass
            return Client()

    fleet, session, _ = paired_session_for_services()
    session.screen_awake = True
    session.adb.exec_out = lambda *args, **kwargs: (_ for _ in ()).throw(OSError("no adb"))
    controller = VideoStreamController(session, VideoFleetLimiter(), media_backend=unavailable_media_backend(), lan_share=Share())
    q = controller.subscribe("focus")
    init = q.get(timeout=2)
    frame = q.get(timeout=2)
    assert '"backend":"lan-share"' in init.data and '"codec":"image/jpeg"' in init.data
    assert frame.kind == "binary" and frame.data.endswith(b"\xff\xd8wifi\xff\xd9")
    fallback = q.get(timeout=3)
    assert '"backend":"adb-screenshot"' in fallback.data, "when the phone stops sharing, ADB screenshots take over"
    controller.unsubscribe("focus", q)
    controller.stop_all()
