"""End-to-end tests of the kit: the Dev Hub, the three example plugins and the conformance checker."""
import hashlib
import hmac
import json
import os
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

import pytest

from cyclone_ports import CATALOG, PluginServer, deliver, sign, validate_envelope, validate_manifest, verify
from cyclone_ports.sdk import error_code, secrets_from_env
from cyclone_ports.conformance import check_plugin
from cyclone_ports.devhub import TINY_PNG, DevHub

ROOT = Path(__file__).resolve().parents[1]
SECRET = "test-secret-1"
CODE = "482913"
RUN = {"runId": "run_t1", "taskId": "task_t1", "rowId": "row_t1", "app": "com.example.app"}


def post(url, body, headers=None):
    request = urllib.request.Request(url, data=json.dumps(body).encode(), method="POST",
                                     headers={"Content-Type": "application/json", **(headers or {})})
    try:
        with urllib.request.urlopen(request, timeout=5) as response:
            return response.status
    except urllib.error.HTTPError as error:
        return error.code


def wait_for(predicate, timeout=5.0):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if predicate():
            return True
        time.sleep(0.02)
    return False


@pytest.fixture
def hub(tmp_path):
    h = DevHub(SECRET, sources=["second-phone"], run_dir=tmp_path / "runs", log_path=tmp_path / "audit.jsonl").start()
    yield h
    h.stop()


# ---- contract pieces -------------------------------------------------------------------------------------------------

def test_signature_round_trip_and_refusals():
    body = b'{"v":1}'
    header = sign(SECRET, "POST", "/ports/log.line", body)
    assert verify(SECRET, header, "POST", "/ports/log.line", body)
    assert not verify(SECRET, header, "POST", "/ports/log.line", body + b" ")       # body changed
    assert not verify(SECRET, header, "POST", "/ports/run.event", body)             # another path
    assert not verify("other", header, "POST", "/ports/log.line", body)             # wrong secret
    stale = sign(SECRET, "POST", "/ports/log.line", body, int(time.time()) - 3600)
    assert not verify(SECRET, stale, "POST", "/ports/log.line", body)
    assert not verify(SECRET, None, "POST", "/ports/log.line", body)


def test_signature_vectors_match_an_independent_implementation():
    """The vectors other-language plugins test against, recomputed here straight from SPEC.md §4."""
    vectors = json.loads((ROOT / "schemas" / "signature-vectors.json").read_text())["vectors"]
    assert len(vectors) >= 3
    for v in vectors:
        kid, dot, rest = v["secret"].partition(".")
        key = rest if dot and kid.startswith("k") and kid[1:].isdigit() else v["secret"]
        kid = kid if key == rest else "k1"
        text = f'{v["t"]}\n{v["requestId"]}\n{v["method"]}\n{v["path"]}\n{hashlib.sha256(v["body"].encode()).hexdigest()}'
        mac = hmac.new(key.encode(), text.encode(), hashlib.sha256).hexdigest()
        assert v["signingString"] == text, v["name"]
        assert v["header"] == f't={v["t"]},kid={kid},id={v["requestId"]},v1={mac}', v["name"]
        assert sign(v["secret"], v["method"], v["path"], v["body"].encode(), v["t"], v["requestId"]) == v["header"]


def test_key_rotation_accepts_both_keys_during_the_switch():
    keys = secrets_from_env({"CYCLONE_PLUGIN_SECRET": "k1.old-key", "CYCLONE_PLUGIN_SECRET_NEXT": "k2.new-key"})
    assert keys == {"k1": "old-key", "k2": "new-key"}
    body = b"{}"
    for secret in ("k1.old-key", "k2.new-key"):
        assert verify(keys, sign(secret, "POST", "/ports/log.line", body), "POST", "/ports/log.line", body)
    assert not verify(keys, sign("k3.other", "POST", "/ports/log.line", body), "POST", "/ports/log.line", body)
    assert not verify({"k1": "old-key"}, sign("k2.new-key", "POST", "/p", body), "POST", "/p", body)


def test_example_manifests_are_valid():
    for name in ("logger", "pc_images", "sms_plugin"):
        manifest = json.loads((ROOT / "examples" / name / "cyclone-plugin.json").read_text())
        assert validate_manifest(manifest) == [], name


def test_manifest_rules():
    base = {"contract": "cyclone.ports/1", "name": "x-plugin", "version": "1.0.0",
            "endpoint": "http://127.0.0.1:9000", "serves": [{"port": "log.line", "way": "out"}]}
    assert validate_manifest(base) == []
    assert validate_manifest({**base, "endpoint": "http://192.168.1.4:9000"})       # plain http off loopback
    assert validate_manifest({**base, "serves": [{"port": "secret.out", "way": "out"}]})  # hub/vault only
    assert validate_manifest({**base, "serves": [{"port": "code.in", "way": "out"}]})     # wrong way
    assert validate_manifest({**base, "serves": [{"port": "screen.shot", "way": "out"}]})  # personal, not declared
    assert validate_manifest({**base, "serves": [{"port": "screen.shot", "way": "out"}],
                              "needs": {"personal": True}}) == []


def test_account_fields_never_carry_secrets():
    hub = DevHub(SECRET)
    try:
        env = hub.envelope(RUN, "account.fields", {"fields": {"name": "Sam", "password": "x"}})
        assert any("password" in p for p in validate_envelope(env))
        env = hub.envelope(RUN, "account.fields", {"fields": {"name": "Sam", "smsPin": "1"}})
        assert validate_envelope(env)
    finally:
        hub.stop()


def test_catalog_keeps_secret_ports_off_plugins():
    assert not CATALOG["secret.out"].plugin_served and not CATALOG["secret.in"].plugin_served
    assert CATALOG["code.in"].sensitivity == "secret"


# ---- end to end ------------------------------------------------------------------------------------------------------

def test_signup_scenario_with_sms_code_never_keeps_the_code(hub, tmp_path, examples, capfd):
    logger = examples["logger"].build(SECRET, tmp_path / "logger").start()
    sms = examples["sms_plugin"].build(SECRET, "second-phone", "fwd-token").start()
    filled = []
    hub.phone_fill = lambda run_id, code: filled.append((run_id, code))
    try:
        hub.bind_plugin(logger.url)
        hub.bind_plugin(sms.url)
        scenario = json.loads((ROOT / "scenarios" / "signup.json").read_text())
        results = []
        runner = threading.Thread(target=lambda: results.extend(hub.run_scenario(scenario)))
        runner.start()
        assert wait_for(lambda: any(e["event"] == "await" for e in hub.audit))
        assert post(f"{sms.url}/sms", {"from": "Example", "text": f"Your code is {CODE}"}) == 401  # no token
        assert post(f"{sms.url}/sms", {"from": "Bank", "text": "Code 111111"}, {"X-Forwarder-Token": "fwd-token"}) == 202
        assert post(f"{sms.url}/sms", {"from": "Example", "text": f"Your Example code is {CODE}. Don't share it."},
                    {"X-Forwarder-Token": "fwd-token"}) == 202
        runner.join(10)
        assert not runner.is_alive()
    finally:
        logger.stop()
        sms.stop()

    code_step = next(r for r in results if r.get("await") == "code.in")
    assert code_step["state"] == "delivered" and code_step["filled"] and code_step["codeLength"] == 6
    assert CODE not in json.dumps(results)
    assert filled == [(scenario["run"]["runId"], CODE)]
    assert [r["status"] for r in results if "emit" in r] == [202] * 7

    lines = [json.loads(l) for l in (tmp_path / "logger" / "runs.jsonl").read_text().splitlines()]
    assert [l["port"] for l in lines] == ["run.event", "account.fields", "run.event", "screen.shot", "log.line",
                                          "run.event", "run.event"]
    shot = next(l for l in lines if l["port"] == "screen.shot")
    assert "artifactUrl" not in shot
    assert (tmp_path / "logger" / "artifacts" / shot["savedAs"]).read_bytes() == TINY_PNG

    # the code is nowhere: not in the audit log, the logger's files, the run folder or anything printed
    out, err = capfd.readouterr()
    assert CODE not in out + err
    for path in tmp_path.rglob("*"):
        if path.is_file():
            assert CODE.encode() not in path.read_bytes(), path
    assert "would be sealed to the phone" in (tmp_path / "audit.jsonl").read_text()


def test_code_from_an_unregistered_source_is_refused(hub, examples):
    sms = examples["sms_plugin"].build(SECRET, "someone-elses-phone", "fwd-token").start()
    try:
        hub.bind_plugin(sms.url)
        post(f"{sms.url}/sms", {"from": "Example", "text": f"code {CODE}"}, {"X-Forwarder-Token": "fwd-token"})
        outcome = hub.await_(RUN, "code.in", {"from": "Example", "pattern": r"\b\d{6}\b"}, timeout_s=1.5)
    finally:
        sms.stop()
    assert outcome == {"state": "timed_out"}
    assert any(e["event"] == "delivery_refused" and e["reason"] == "unregistered_source" for e in hub.audit)


def test_image_scenario_saves_the_file_to_the_run_folder(hub, tmp_path, examples):
    folder = tmp_path / "pictures"
    folder.mkdir()
    (folder / "old.png").write_bytes(b"old")
    os.utime(folder / "old.png", (time.time() - 60, time.time() - 60))
    (folder / "avatar.png").write_bytes(TINY_PNG)
    images = examples["pc_images"].build(SECRET, folder).start()
    logger = examples["logger"].build(SECRET, tmp_path / "logger").start()
    try:
        hub.bind_plugin(images.url)
        hub.bind_plugin(logger.url)
        results = hub.run_scenario(json.loads((ROOT / "scenarios" / "image.json").read_text()))
    finally:
        images.stop()
        logger.stop()
    step = next(r for r in results if r.get("await") == "file.in")
    assert step["state"] == "delivered" and step["name"] == "avatar.png"
    assert Path(step["saved"]).read_bytes() == TINY_PNG


def test_deliver_status_codes(hub):
    quiet = PluginServer({"contract": "cyclone.ports/1", "name": "quiet", "version": "0.0.1",
                          "endpoint": "http://127.0.0.1:1", "serves": [{"port": "value.in", "way": "in"}],
                          "needs": {"personal": True}}, SECRET).start()  # accepts awaits, never delivers
    try:
        hub.bind_plugin(quiet.url)
        url = f"{hub.url}/v1/ports/{RUN['runId']}/value.in/deliver"
        assert deliver(url, "nope", {"v": 1, "value": "x"})[0] == 404           # nothing waiting

        holder = {}
        t = threading.Thread(target=lambda: holder.update(hub.await_(RUN, "value.in", {}, timeout_s=3)))
        t.start()
        assert wait_for(lambda: hub._awaits)
        record = next(iter(hub._awaits.values()))
        assert deliver(url, "wrong", {"v": 1, "value": "x"})[0] == 401          # bad token
        assert deliver(url, record.token, {"v": 1})[0] == 422                   # invalid
        assert deliver(url, record.token, {"v": 1, "value": "hello"})[0] == 200
        assert deliver(url, record.token, {"v": 1, "value": "again"})[0] == 409  # already delivered
        t.join(5)
        assert holder == {"state": "delivered", "value": "hello"}

        record = None
        t = threading.Thread(target=lambda: hub.await_(RUN, "value.in", {}, timeout_s=1))
        t.start()
        assert wait_for(lambda: any(a.state == "waiting" for a in hub._awaits.values()))
        record = next(a for a in hub._awaits.values() if a.state == "waiting")
        t.join(5)
        assert deliver(url, record.token, {"v": 1, "value": "late"})[0] == 410  # expired
    finally:
        quiet.stop()


def test_artifact_links_work_once(hub):
    link = hub.artifact(b"png-bytes", "image/png")
    with urllib.request.urlopen(link, timeout=5) as response:
        assert response.read() == b"png-bytes"
    with pytest.raises(urllib.error.HTTPError) as error:
        urllib.request.urlopen(link, timeout=5)
    assert error.value.code == 410
    with pytest.raises(urllib.error.HTTPError):
        urllib.request.urlopen(link.split("?")[0] + "?t=guess", timeout=5)


# ---- conformance -----------------------------------------------------------------------------------------------------

def test_examples_pass_conformance(tmp_path, examples):
    servers = [examples["logger"].build(SECRET, tmp_path / "logger"),
               examples["pc_images"].build(SECRET, tmp_path / "empty"),
               examples["sms_plugin"].build(SECRET, "second-phone", "fwd-token")]
    for server in servers:
        server.start()
    try:
        for server in servers:
            failed = [c for c in check_plugin(server.url, SECRET) if not c.ok]
            assert failed == [], (server.manifest["name"], failed)
    finally:
        for server in servers:
            server.stop()


def test_conformance_catches_a_plugin_that_skips_signatures(tmp_path, examples):
    server = examples["logger"].build(SECRET, tmp_path / "logger").start()
    try:
        failed = {c.name for c in check_plugin(server.url, "a-different-secret") if not c.ok}
    finally:
        server.stop()
    # with the wrong secret the hub's own envelopes are refused, so the out-port checks fail
    assert "run.event: accepts a sample envelope (2xx)" in failed


# ---- evolution: what keeps old plugins working as the contract grows -------------------------------------------------

def test_plugins_read_envelopes_tolerantly_but_the_hub_sends_strictly():
    hub = DevHub(SECRET)
    try:
        env = hub.envelope(RUN, "run.event", {"stage": "a-stage-added-later", "newField": 1}, futureTopLevel=True)
        assert validate_envelope(env, strict=False) == []          # an old plugin accepts what a newer hub sends
        assert validate_envelope(env, strict=True)                 # this hub only sends stages it knows
        del env["id"]
        assert validate_envelope(env, strict=False)                # structure still matters
    finally:
        hub.stop()


def test_a_repeated_delivery_is_idempotent(hub):
    quiet = PluginServer({"contract": "cyclone.ports/1", "name": "quiet", "version": "0.0.1",
                          "endpoint": "http://127.0.0.1:1", "serves": [{"port": "value.in", "way": "in"}],
                          "needs": {"personal": True}}, SECRET).start()
    try:
        hub.bind_plugin(quiet.url)
        holder = {}
        t = threading.Thread(target=lambda: holder.update(hub.await_(RUN, "value.in", {}, timeout_s=3)))
        t.start()
        assert wait_for(lambda: hub._awaits)
        record = next(iter(hub._awaits.values()))
        url = f"{hub.url}/v1/ports/{RUN['runId']}/value.in/deliver"
        body = {"v": 1, "value": "hello", "deliveryId": "dl_retry_0001"}
        assert deliver(url, record.token, body)[0] == 200
        status, answer = deliver(url, record.token, body)          # a retry after a lost answer
        assert status == 200 and answer.get("duplicate") is True
        status, answer = deliver(url, record.token, dict(body, deliveryId="dl_other_0002"))
        assert status == 409 and error_code(answer) == "already_delivered"
        t.join(5)
        assert holder == {"state": "delivered", "value": "hello"}
    finally:
        quiet.stop()


def test_extension_ports_round_trip(hub):
    """A plugin adds its own data point (x.<plugin>.<name>) without a contract change."""
    got = []
    manifest = {"contract": "cyclone.ports/1", "name": "crm", "version": "1.0.0", "endpoint": "http://127.0.0.1:1",
                "serves": [{"port": "x.crm.lead", "way": "out"}, {"port": "x.crm.next-handle", "way": "in"}],
                "needs": {"personal": True}, "features": ["idempotent-delivery", "a-feature-from-the-future"]}
    assert validate_manifest(manifest) == []
    assert validate_manifest(dict(manifest, serves=[{"port": "x.other.lead", "way": "out"}]))  # another's namespace
    assert validate_manifest(dict(manifest, needs={"personal": False}))                         # extensions are personal
    crm = PluginServer(manifest, SECRET)
    crm.on_out = lambda port, env: got.append((port, env["data"]))
    crm.on_await = lambda port, req: threading.Thread(
        target=deliver, args=(req["deliverUrl"], req["token"], {"v": 1, "value": "sam.example.42"})).start()
    crm.start()
    try:
        hub.bind_plugin(crm.url)
        assert hub.emit(RUN, "x.crm.lead", {"stage": "qualified"}) == 202
        with pytest.raises(ValueError):
            hub.emit(RUN, "x.crm.lead", {"password": "nope"})      # no secrets on extension ports either
        assert hub.await_(RUN, "x.crm.next-handle", {}, timeout_s=3) == {"state": "delivered",
                                                                        "value": "sam.example.42"}
    finally:
        crm.stop()
    assert got == [("x.crm.lead", {"stage": "qualified"})]


def test_errors_have_one_shape(hub):
    status, answer = deliver(f"{hub.url}/v1/ports/run_x/value.in/deliver", "nope", {"v": 1, "value": 1}, attempts=1)
    assert status == 404 and set(answer["error"]) >= {"code", "message", "retryable"}


def test_conformance_catches_a_plugin_that_breaks_on_new_values():
    """The forward-compatibility check: a plugin that only accepts today's stages fails it."""
    manifest = {"contract": "cyclone.ports/1", "name": "rigid", "version": "0.0.1", "endpoint": "http://127.0.0.1:1",
                "serves": [{"port": "run.event", "way": "out"}]}
    rigid = PluginServer(manifest, SECRET)

    def on_out(port, env):
        if env["data"]["stage"] not in ("started", "done"):
            raise ValueError("unknown stage")
    rigid.on_out = on_out
    rigid.start()
    try:
        failed = {c.name for c in check_plugin(rigid.url, SECRET) if not c.ok and c.required}
    finally:
        rigid.stop()
    assert failed == {"run.event: tolerates unknown fields and values (2xx)"}


def test_schemas_agree_with_the_sdk():
    jsonschema = pytest.importorskip("jsonschema")
    schema = lambda name: json.loads((ROOT / "schemas" / f"{name}.schema.json").read_text())
    for name in ("logger", "pc_images", "sms_plugin"):
        jsonschema.validate(json.loads((ROOT / "examples" / name / "cyclone-plugin.json").read_text()),
                            schema("manifest"))
    hub = DevHub(SECRET)
    try:
        for port, data in (("run.event", {"stage": "started"}), ("x.crm.lead", {"stage": "new"})):
            hub.bindings[port] = ("http://127.0.0.1:1", "out")
            jsonschema.validate(hub.envelope(RUN, port, data), schema("envelope"))
    finally:
        hub.stop()
    for body in ({"v": 1, "code": "482913", "source": "s", "from": "f", "deliveryId": "dl_12345678"},
                 {"v": 1, "url": "https://example.com/confirm", "source": "s"},
                 {"v": 1, "value": {"any": "json"}},
                 {"v": 1, "name": "a.png", "mime": "image/png", "base64": "AA==", "sha256": "0" * 64}):
        jsonschema.validate(body, schema("delivery"))


def test_node_verifier_matches_the_vectors():
    import shutil
    import subprocess
    node = shutil.which("node")
    if node is None:
        pytest.skip("node is not installed")
    out = subprocess.run([node, str(ROOT / "js" / "vectors-test.mjs"), str(ROOT / "schemas" / "signature-vectors.json")],
                         capture_output=True, text=True, timeout=30, cwd=ROOT / "js")
    assert out.returncode == 0, out.stderr
    lines = out.stdout.strip().splitlines()
    assert len(lines) >= 3 and all(l.endswith("true replay: false tampered: false") for l in lines), out.stdout
