"""Plan 48 run 4: phone runs on the Port Hub, end to end. The PhoneBridge collects a fake phone's port outbox through the
real V5 contract (so every check on what the phone sends back runs), plays it into the real hub with the kit's example
plugins, and answers the phone's waits: a code only sealed to the phone's trusted key, values and links as data, files
in gallery chunks."""
from __future__ import annotations

import base64
import json
import time

import pytest
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

from cyclone_device_gateway.desktop_runtime.models import DesktopRuntimeError
from cyclone_device_gateway.desktop_runtime.v5_contract import V5ContractService
from cyclone_device_gateway.ports import seal
from cyclone_device_gateway.ports.phone import FAST_S, IDLE_S, PhoneBridge
from cyclone_ports import deliver

from test_ports_traffic import CODE, Gateway, load_example, post_sms

PNG = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==")
PLACE = "package:com.example.app"


def hpke_open(private: ec.EllipticCurvePrivateKey, enc: bytes, ct: bytes, info: bytes, aad: bytes) -> bytes:
    """The phone's side of seal.seal (RFC 9180 base mode), independent of the sealing code path."""
    public = private.public_key().public_bytes(Encoding.X962, PublicFormat.UncompressedPoint)
    dh = private.exchange(ec.ECDH(), ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), enc))
    kem = b"KEM" + seal._i2osp(seal.KEM_ID, 2)
    shared = seal._labeled_expand(kem, seal._labeled_extract(kem, b"", b"eae_prk", dh), b"shared_secret", enc + public, 32)
    suite = b"HPKE" + seal._i2osp(seal.KEM_ID, 2) + seal._i2osp(seal.KDF_ID, 2) + seal._i2osp(seal.AEAD_ID, 2)
    context = b"\x00" + seal._labeled_extract(suite, b"", b"psk_id_hash", b"") + seal._labeled_extract(suite, b"", b"info_hash", info)
    secret = seal._labeled_extract(suite, shared, b"secret", b"")
    key = seal._labeled_expand(suite, secret, b"key", context, 32)
    nonce = seal._labeled_expand(suite, secret, b"base_nonce", context, 12)
    return AESGCM(key).decrypt(nonce, ct, aad)


class FakePhone:
    """The phone's PortOutbox and gateway adapter, as the bridge sees them over the bridge protocol."""

    def __init__(self) -> None:
        self.key = ec.generate_private_key(ec.SECP256R1())
        self.public = self.key.public_key().public_bytes(Encoding.X962, PublicFormat.UncompressedPoint)
        self.queue: list[dict] = []
        self.blobs: dict[str, bytes] = {}
        self.answers: dict[str, dict] = {}
        self.codes: dict[str, str] = {}
        self.files: dict[str, bytearray] = {}
        self.polls: list[dict] = []
        self.seq = 0

    @property
    def trusted(self) -> dict[str, str]:
        return {"publicKey": base64.b64encode(self.public).decode(), "fingerprint": seal.fingerprint(self.public)}

    def _id(self) -> str:
        self.seq += 1
        return f"pt_item{self.seq:08d}"

    def emit(self, run: str, port: str, data: dict, blob: bytes | None = None) -> str:
        item = {"kind": "emit", "id": self._id(), "runId": run, "at": 1, "app": "com.example.app", "port": port, "data": data}
        if blob is not None:
            item["blob"] = {"bytes": len(blob), "mime": "image/png"}
            self.blobs[item["id"]] = blob
        self.queue.append(item)
        return item["id"]

    def wait(self, run: str, port: str, ask: str = "", place: str | None = None, timeout: int = 60) -> str:
        item = {"kind": "await", "id": self._id(), "runId": run, "at": 1, "app": "com.example.app", "port": port,
                "timeoutS": timeout, "match": {"ask": ask}}
        if place:
            item["place"] = place
        self.queue.append(item)
        return item["id"]

    def cancel(self, run: str, await_item: str) -> None:
        self.queue.append({"kind": "cancel", "id": self._id(), "runId": run, "at": 1, "item": await_item, "reason": "cancelled"})

    # ---- the bridge protocol -----------------------------------------------------------------------------------------

    def request(self, op, args, request_id=None):
        if op == "ports.poll":
            self.polls.append(dict(args))
            self.queue = [i for i in self.queue if i["id"] not in set(args.get("ack", []))]
            if args.get("drop") and self.queue:
                self.queue.pop(0)
            return {"items": [dict(i) for i in self.queue[: args.get("max", 20)]]}
        if op == "ports.blob":
            blob = self.blobs[args["id"]]
            end = min(len(blob), args["offset"] + 384 * 1024)
            return {"data": base64.b64encode(blob[args["offset"]:end]).decode(), "bytes": len(blob), "done": end == len(blob)}
        if op == "ports.answer":
            self.answers[args["id"]] = dict(args)
            sealed = args.get("sealed")
            if sealed is not None:
                bound = json.loads(sealed["aad"])
                self.codes[args["id"]] = hpke_open(self.key, base64.b64decode(sealed["enc"]), base64.b64decode(sealed["ct"]),
                                                   seal.INFO, sealed["aad"].encode()).decode()
                self.answers[args["id"]]["bound"] = bound
            return {"handled": True}
        if op == "ports.file":
            buffer = self.files.setdefault(args["taskId"], bytearray())
            buffer += base64.b64decode(args["data"])
            return {"received": len(buffer), "done": len(buffer) == args["size"], "name": args["name"], "folder": "Pictures/Cyclone"}
        raise AssertionError(op)


class Session:
    credential = "paired"

    def __init__(self, phone):
        self._phone = phone

    def bridge(self):
        return self._phone


class Fleet:
    def __init__(self, phone):
        self.session = Session(phone)

    def get(self, device_id):
        return self.session


@pytest.fixture
def gateway(tmp_path):
    g = Gateway(tmp_path / "ports")
    yield g
    g.stop()


def bridge_for(gateway, phone, trusted=True, clock=time.monotonic):
    contract = V5ContractService(Fleet(phone))
    devices = lambda: [{"deviceId": "phone-1", "paired": True, "state": "ready"}]  # noqa: E731
    return PhoneBridge(gateway.hub, contract, devices, lambda device: phone.trusted if trusted else None, clock=clock)


def answered(phone, item_id, timeout=15.0):
    deadline = time.time() + timeout
    while item_id not in phone.answers and time.time() < deadline:
        time.sleep(0.05)
    return phone.answers.get(item_id)


def test_a_phone_run_sends_events_and_screenshots_to_plugins(gateway, tmp_path):
    logger = load_example("logger").build("k9.x", tmp_path / "logger").start()
    try:
        gateway.add(logger)
        phone = FakePhone()
        bridge = bridge_for(gateway, phone)
        phone.emit("m1phone01", "run.event", {"stage": "started"})
        phone.emit("m1phone01", "screen.shot", {"pageKey": "signup:birthday"}, PNG)
        bridge.poll("phone-1")
        gateway.hub.traffic.flush()
        bridge.poll("phone-1")
        assert phone.queue == [], "handled items are acknowledged on the next poll"
        lines = [json.loads(l) for l in (tmp_path / "logger" / "runs.jsonl").read_text().splitlines()]
        lines = [l for l in lines if l["runId"] == "m1phone01"]
        assert [l["port"] for l in lines] == ["run.event", "screen.shot"]
        assert lines[1]["pageKey"] == "signup:birthday" and lines[0]["app"] == "com.example.app"
    finally:
        logger.stop()


def test_a_code_reaches_the_phone_only_sealed_to_its_trusted_key(gateway, tmp_path):
    sms = load_example("sms_plugin").build("k9.x", "my-second-phone", "fwd").start()
    try:
        gateway.add(sms)
        phone = FakePhone()
        bridge = bridge_for(gateway, phone)
        item = phone.wait("m1phone02", "code.in", "the Example sign-up code", PLACE)
        bridge.poll("phone-1")
        gateway.hub.traffic.flush()
        post_sms(sms.url, f"Your Example code is {CODE}")
        answer = answered(phone, item)
        assert answer and answer["state"] == "delivered" and answer["plugin"] == "sms-codes"
        assert phone.codes[item] == CODE, "the phone opens it with its own key"
        bound = answer["bound"]
        assert bound["runId"] == "m1phone02" and bound["place"] == PLACE and bound["slot"] == "code"
        assert bound["deviceKey"] == phone.trusted["fingerprint"] and bound["leaseId"] == answer["sealed"]["leaseId"]
        assert CODE not in json.dumps({k: v for k, v in answer.items() if k != "bound"}), "the code never travels readable"
        assert all(CODE.encode() not in p.read_bytes() for p in tmp_path.rglob("*") if p.is_file())
    finally:
        sms.stop()


def test_an_untrusted_phone_gets_no_code(gateway):
    sms = load_example("sms_plugin").build("k9.x", "my-second-phone", "fwd").start()
    try:
        gateway.add(sms)
        phone = FakePhone()
        bridge = bridge_for(gateway, phone, trusted=False)
        item = phone.wait("m1phone03", "code.in", "", PLACE)
        bridge.poll("phone-1")
        gateway.hub.traffic.flush()
        post_sms(sms.url, f"Your Example code is {CODE}")
        answer = answered(phone, item)
        assert answer["state"] == "failed" and "trusted" in answer["reason"] and "sealed" not in answer
        assert phone.codes == {}
        await_id = gateway.hub.traffic.store.waits(run_id="m1phone03")[0]["awaitId"]
        assert gateway.hub.traffic.take_code(await_id) is None, "the code is gone from memory either way"
    finally:
        sms.stop()


def test_values_links_and_files_come_back_as_data(gateway, tmp_path):
    from cyclone_ports import PluginServer
    asked = []
    server = PluginServer({"contract": "cyclone.ports/1", "name": "values", "version": "1.0.0", "endpoint": "http://127.0.0.1:1",
                           "serves": [{"port": "value.in", "way": "in"}, {"port": "link.in", "way": "in"}],
                           "needs": {"personal": True}}, "k9.x")
    server.on_await = lambda port, request: asked.append(request)
    server.start()
    pictures = tmp_path / "pictures"
    pictures.mkdir()
    (pictures / "avatar.png").write_bytes(PNG)
    images = load_example("pc_images").build("k9.x", pictures).start()
    try:
        gateway.add(server)
        gateway.add(images)
        phone = FakePhone()
        bridge = bridge_for(gateway, phone)
        value = phone.wait("m1phone04", "value.in", "a caption")
        link = phone.wait("m1phone04", "link.in", "the confirmation link")
        secret = phone.wait("m1phone04", "value.in", "anything")
        file = phone.wait("m1phone04", "file.in", "a profile picture")
        bridge.poll("phone-1")
        gateway.hub.traffic.flush()
        by_port = {}
        for request in asked:
            if request["runId"] == "m1phone04":  # adding a plugin runs its checks, which ask sample waits too
                by_port.setdefault(request["port"], []).append(request)
        url = lambda port: f"http://127.0.0.1:{gateway.port}/v1/ports/m1phone04/{port}/deliver"  # noqa: E731
        first, second = by_port["value.in"]
        assert deliver(url("value.in"), first["token"], {"v": 1, "value": "Sunset run"}, attempts=1)[0] == 200
        assert deliver(url("value.in"), second["token"], {"v": 1, "value": {"password": "hunter2"}}, attempts=1)[0] == 200
        assert deliver(url("link.in"), by_port["link.in"][0]["token"],
                       {"v": 1, "url": "https://example.com/confirm?t=abc", "source": "inbox"}, attempts=1)[0] == 200
        assert answered(phone, value)["value"] == "Sunset run"
        assert answered(phone, link)["url"] == "https://example.com/confirm?t=abc"
        refused = answered(phone, secret)
        assert refused["state"] == "failed" and "secret" in refused["reason"] and "value" not in refused
        got = answered(phone, file)
        assert got["state"] == "delivered" and got["file"]["name"] == "avatar.png" and got["file"]["folder"] == "Pictures/Cyclone"
        assert bytes(phone.files[file]) == PNG
    finally:
        server.stop()
        images.stop()


def test_a_stopped_run_cancels_its_wait_and_no_plugin_means_an_honest_answer(gateway):
    from cyclone_ports import PluginServer
    cancels = []
    server = PluginServer({"contract": "cyclone.ports/1", "name": "values", "version": "1.0.0", "endpoint": "http://127.0.0.1:1",
                           "serves": [{"port": "value.in", "way": "in"}], "needs": {"personal": True}}, "k9.x")
    server.on_cancel = lambda port, request: cancels.append(request["awaitId"])
    server.start()
    try:
        gateway.add(server)
        phone = FakePhone()
        bridge = bridge_for(gateway, phone)
        item = phone.wait("m1phone05", "value.in", "a caption")
        nobody = phone.wait("m1phone05", "code.in", "", PLACE)
        bridge.poll("phone-1")
        assert answered(phone, nobody)["state"] == "empty", "no plugin serves code.in: answered at once"
        phone.cancel("m1phone05", item)
        bridge.poll("phone-1")
        assert answered(phone, item)["state"] == "cancelled"
        gateway.hub.traffic.flush()
        deadline = time.time() + 5
        while not cancels and time.time() < deadline:
            time.sleep(0.05)
        assert cancels, "the plugin is told the run stopped"
    finally:
        server.stop()


def test_a_message_the_pc_refuses_is_dropped_and_the_rest_flow(gateway, tmp_path):
    logger = load_example("logger").build("k9.x", tmp_path / "logger").start()
    try:
        gateway.add(logger)
        phone = FakePhone()
        bridge = bridge_for(gateway, phone)
        bad = phone.emit("m1phone06", "log.line", {"text": "x", "password": "hunter2"})  # the phone's scrub would never send this
        phone.emit("m1phone06", "run.event", {"stage": "done"})
        bridge.poll("phone-1")
        assert any(p.get("drop") for p in phone.polls), "the refused message is dropped on the phone"
        assert all(i["id"] != bad for i in phone.queue)
        bridge.poll("phone-1")
        bridge.poll("phone-1")
        gateway.hub.traffic.flush()
        lines = [json.loads(l) for l in (tmp_path / "logger" / "runs.jsonl").read_text().splitlines()]
        assert any(l["runId"] == "m1phone06" and l["port"] == "run.event" for l in lines)
        assert "hunter2" not in (tmp_path / "logger" / "runs.jsonl").read_text()
    finally:
        logger.stop()


def test_polling_is_fast_only_while_a_phone_is_busy(gateway):
    now = [100.0]
    phone = FakePhone()
    bridge = bridge_for(gateway, phone, clock=lambda: now[0])
    bridge.tick()
    assert len(phone.polls) == 1
    now[0] += FAST_S
    bridge.tick()
    assert len(phone.polls) == 1, "idle: no second poll after the fast interval"
    now[0] += IDLE_S
    bridge.tick()
    assert len(phone.polls) == 2
    phone.emit("m1phone07", "log.line", {"text": "hello"})
    now[0] += IDLE_S
    bridge.tick()
    now[0] += FAST_S
    bridge.tick()
    assert len(phone.polls) == 4, "busy: polled again after the fast interval"
    assert IDLE_S < 20, "an idle phone still counts as connected (the phone allows 20 s between polls)"


def test_the_contract_checks_what_the_phone_sends(gateway):
    phone = FakePhone()
    contract = V5ContractService(Fleet(phone))
    phone.queue.append({"kind": "await", "id": "pt_item99999999", "runId": "m1phone08", "at": 1, "port": "code.in",
                        "timeoutS": 60, "match": {"ask": ""}})
    with pytest.raises(DesktopRuntimeError):
        contract.ports_poll("phone-1", [])  # a code wait without its place is refused
    with pytest.raises(DesktopRuntimeError):
        contract.ports_answer("phone-1", "pt_item99999999", "delivered", value={"otp": "1"}, has_value=True)
    with pytest.raises(DesktopRuntimeError):
        contract.ports_answer("phone-1", "pt_item99999999", "delivered", sealed={"leaseId": "ls_x", "enc": "a", "ct": "b", "aad": "{}"})
