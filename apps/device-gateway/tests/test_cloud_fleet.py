"""Plan 44 run 1 (alpha 90): cloud phones joined to this PC's fleet and kept connected on Cyclone's terms."""
from __future__ import annotations

import json
from pathlib import Path
from types import SimpleNamespace

import pytest

from cyclone_device_gateway.adb.client import ADBError
from cyclone_device_gateway.cloud_fleet import lease as L
from cyclone_device_gateway.cloud_fleet.models import AdbLink, CloudPhone, ProviderError, normalize_address
from cyclone_device_gateway.cloud_fleet.providers import make_provider
from cyclone_device_gateway.cloud_fleet.providers.duoplus import DuoPlus
from cyclone_device_gateway.cloud_fleet.providers.vmos import VmosCloud, parse_adb_answer, parse_ssh_command, parse_time_ms, sign
from cyclone_device_gateway.cloud_fleet.service import CloudFleetService
from cyclone_device_gateway.cloud_fleet.tunnel import ASKPASS_MODE, ASKPASS_SECRET, SshTunnel, askpass_main, explain, ssh_argv
from cyclone_device_gateway.cloud_fleet.vault import CloudVault
from cyclone_device_gateway.desktop_runtime.connection_doctor import diagnose
from cyclone_device_gateway.desktop_runtime.models import AITrustState, BridgeState, DesktopRuntimeError, DiscoveryState

NOW = 1_790_000_000.0
AK, SK = "AKID-test-1234", "SECRET-never-shown-5678"
SSH_PASSWORD = "vmos-ssh-password-9999"
VMOS_SSH = "ssh -oHostKeyAlgorithms=+ssh-rsa 10.255.1.2_1733_ab@103.20.30.40 -p 1824 -L 8572:adb-proxy:46328 -Nf"


class Clock:
    def __init__(self, t: float = NOW):
        self.t = t

    def __call__(self) -> float:
        return self.t


class FakeHttp:
    """A provider API: answers by path, records what it was sent."""

    def __init__(self, routes: dict[str, object]):
        self.routes = routes
        self.calls: list[tuple[str, dict[str, str], dict]] = []

    def __call__(self, method, url, headers, body, timeout):
        path = "/" + url.split("/", 3)[3]
        self.calls.append((path, headers, json.loads(body.decode("utf-8")) if body else {}))
        answer = self.routes.get(path)
        if callable(answer):
            answer = answer(json.loads(body.decode("utf-8")))
        if isinstance(answer, tuple):
            return answer
        if answer is None:
            return 404, b"{}"
        return 200, json.dumps(answer).encode("utf-8")


def vmos_routes(**over):
    routes = {
        "/vcpcloud/api/padApi/infos": {"code": 200, "msg": "success", "data": {"pageData": [
            {"padCode": "AC32010230001", "padName": "Shop 1", "androidVersion": "15", "padStatus": 10},
            {"padCode": "AC32010230002", "padStatus": 14},
        ], "total": 2}},
        "/vcpcloud/api/padApi/adb": {"code": 200, "data": {"padCode": "AC32010230001", "command": VMOS_SSH, "key": SSH_PASSWORD,
                                                          "adb": "adb connect localhost:8572", "expireTime": "2026-10-07 12:00:00",
                                                          "enable": True}},
    }
    routes.update(over)
    return routes


# Providers ----------------------------------------------------------------------------------------------------

def test_vmos_signs_every_call_and_never_sends_the_secret_key():
    http = FakeHttp(vmos_routes())
    vmos = VmosCloud(AK, SK, transport=http, clock=Clock())
    phones = vmos.list_phones()
    assert [(p.remote_id, p.name, p.android, p.power) for p in phones] == [
        ("AC32010230001", "Shop 1", "15", "running"), ("AC32010230002", "AC32010230002", None, "stopped")]
    path, headers, body = http.calls[0]
    assert body == {"page": 1, "rows": 100}
    assert headers["x-host"] == "api.vmoscloud.com"
    assert headers["x-date"] == "20260921T141320Z"
    assert headers["authorization"].startswith(f"HMAC-SHA256 Credential={AK}, SignedHeaders=content-type;host;x-content-sha256;x-date, Signature=")
    assert SK not in json.dumps(headers)
    # Pure and deterministic, so it can be checked against VMOS's own example once run 0 records one.
    assert sign(AK, SK, "api.vmoscloud.com", "{}", "20260921T141320Z") == sign(AK, SK, "api.vmoscloud.com", "{}", "20260921T141320Z")
    assert sign(AK, SK, "api.vmoscloud.com", "{}", "20260921T141320Z")["authorization"] != \
        sign(AK, SK + "x", "api.vmoscloud.com", "{}", "20260921T141320Z")["authorization"]


def test_vmos_remote_adb_asks_for_seven_days_and_reads_the_ssh_link():
    http = FakeHttp(vmos_routes())
    vmos = VmosCloud(AK, SK, transport=http, clock=Clock())
    link = vmos.open_adb(CloudPhone("vmos", "AC32010230001", "Shop 1"), 10_000_000)
    assert http.calls[0][2] == {"padCode": "AC32010230001", "enable": True, "expireMinutes": 10080}
    assert (link.kind, link.ssh_user, link.ssh_host, link.ssh_port, link.target_host, link.target_port) == (
        "ssh", "10.255.1.2_1733_ab", "103.20.30.40", 1824, "adb-proxy", 46328)
    assert link.secret == SSH_PASSWORD
    assert SSH_PASSWORD not in json.dumps(link.public()) and "secret" not in repr(link)
    # The earlier of VMOS's time and the 7 days asked for.
    assert link.expires_at_ms == int(NOW * 1000) + 10080 * 60_000
    # VMOS's time without a zone is read as Beijing time: the earlier guess, so Cyclone renews early, never late.
    assert parse_time_ms("2026-10-07 12:00:00") == parse_time_ms("2026-10-07T04:00:00Z")


def test_the_expiry_is_the_earlier_of_theirs_and_ours():
    issued = int(NOW * 1000)
    later = parse_adb_answer({"command": VMOS_SSH, "key": "k", "expireTime": issued + 30 * 24 * 3600_000}, issued_ms=issued, requested_minutes=60)
    assert later.expires_at_ms == issued + 60 * 60_000
    garbage = parse_adb_answer({"command": VMOS_SSH, "key": "k", "expireTime": "soon"}, issued_ms=issued, requested_minutes=60)
    assert garbage.expires_at_ms == issued + 60 * 60_000
    assert parse_adb_answer({"command": VMOS_SSH, "enable": False}, issued_ms=issued, requested_minutes=60) is None
    direct = parse_adb_answer({"adb": "adb connect 203.0.113.9:40001"}, issued_ms=issued, requested_minutes=60)
    assert (direct.kind, direct.address) == ("direct", "203.0.113.9:40001")


@pytest.mark.parametrize("command,expected", [
    (VMOS_SSH, ("10.255.1.2_1733_ab", "103.20.30.40", 1824, "adb-proxy", 46328)),
    ("ssh -p 22 -L9000:adb-proxy:5555 -N user@host.example", ("user", "host.example", 22, "adb-proxy", 5555)),
    ("ssh -o StrictHostKeyChecking=no u@h -L 127.0.0.1:1:10.0.0.1:5555", ("u", "h", 22, "10.0.0.1", 5555)),
])
def test_ssh_lines_are_read_whatever_their_order(command, expected):
    got = parse_ssh_command(command)
    assert (got["user"], got["host"], got["port"], got["target_host"], got["target_port"]) == expected


def test_a_broken_ssh_line_is_no_link():
    assert parse_ssh_command("ssh -L broken") is None
    assert parse_ssh_command("ssh 'unterminated") is None
    assert parse_adb_answer({"command": "ssh nothing", "key": "k"}, issued_ms=0, requested_minutes=60) is None


def test_provider_failures_are_named_and_keys_are_never_echoed():
    denied = VmosCloud(AK, SK, transport=FakeHttp({"/vcpcloud/api/padApi/infos": (401, b"{}")}), clock=Clock())
    with pytest.raises(ProviderError) as exc:
        denied.list_phones()
    assert exc.value.code == "PROVIDER_AUTH" and not exc.value.retryable
    refused = VmosCloud(AK, SK, transport=FakeHttp({"/vcpcloud/api/padApi/infos": {"code": 500, "msg": "Signature mismatch"}}), clock=Clock())
    with pytest.raises(ProviderError) as exc:
        refused.list_phones()
    assert exc.value.code == "PROVIDER_AUTH"
    down = VmosCloud(AK, SK, transport=FakeHttp({"/vcpcloud/api/padApi/infos": (503, b"")}), clock=Clock())
    with pytest.raises(ProviderError) as exc:
        down.list_phones()
    assert exc.value.retryable and SK not in exc.value.message

    def offline(*_args):
        raise OSError("no route")
    with pytest.raises(ProviderError) as exc:
        VmosCloud(AK, SK, transport=offline, clock=Clock()).list_phones()
    assert exc.value.code == "PROVIDER_UNREACHABLE"


def test_endpoints_can_be_corrected_per_account_without_a_release():
    http = FakeHttp({"/v2/pads": {"code": 200, "data": []}})
    make_provider({"provider": "vmos", "secrets": {"accessKey": AK, "secretKey": SK}, "endpoints": {"list": "/v2/pads", "evil": "/x"}},
                  transport=http).list_phones()
    assert http.calls[0][0] == "/v2/pads"


def test_duoplus_lists_phones_and_uses_the_listed_or_pasted_adb_address():
    http = FakeHttp({"/api/v1/cloudPhone/list": {"code": 200, "data": {"list": [
        {"id": "img1", "name": "DP one", "os": "Android 13", "status": 1, "adb": "adb connect 198.51.100.4:30001"},
        {"id": "img2", "name": "DP two", "status": 0},
    ]}}})
    duo = DuoPlus("dp-key-1", addresses={"img2": "198.51.100.5:30002"}, transport=http, clock=Clock())
    phones = {p.remote_id: p for p in duo.list_phones()}
    assert http.calls[0][1]["DuoPlus-API-Key"] == "dp-key-1"
    assert phones["img1"].address == "198.51.100.4:30001" and phones["img1"].power == "running"
    assert duo.open_adb(phones["img2"]).address == "198.51.100.5:30002"
    with pytest.raises(ProviderError) as exc:
        DuoPlus("k", transport=http, clock=Clock()).open_adb(CloudPhone("duoplus", "img9", "x"))
    assert exc.value.code == "ADB_ADDRESS_NEEDED" and not exc.value.retryable


@pytest.mark.parametrize("text,expected", [
    ("203.0.113.7:5555", "203.0.113.7:5555"),
    ("adb connect Phone.Example.COM:40001", "phone.example.com:40001"),
    ("nothing here", None), ("1.2.3.4:99999", None), ("", None),
])
def test_addresses_are_read_from_whatever_the_owner_pasted(text, expected):
    assert normalize_address(text) == expected


# Tunnel -------------------------------------------------------------------------------------------------------

class FakeProcess:
    def __init__(self, argv, **kwargs):
        self.argv, self.env = argv, kwargs.get("env") or {}
        self.returncode = None
        self.stderr = None
        self.terminated = False

    def poll(self):
        return self.returncode

    def terminate(self):
        self.terminated, self.returncode = True, -15

    def wait(self, timeout=None):
        return self.returncode

    def kill(self):
        self.returncode = -9


def test_the_tunnel_binds_loopback_on_our_port_and_the_key_only_travels_through_askpass(tmp_path):
    link = parse_adb_answer({"command": VMOS_SSH, "key": SSH_PASSWORD}, issued_ms=0, requested_minutes=60)
    spawned = []
    tunnel = SshTunnel(link, 19123, ssh="ssh", scratch=tmp_path, spawn=lambda argv, **kw: spawned.append(FakeProcess(argv, **kw)) or spawned[-1])
    tunnel.start()
    process = spawned[0]
    assert "127.0.0.1:19123:adb-proxy:46328" in process.argv and "-f" not in process.argv and "-Nf" not in process.argv
    assert "10.255.1.2_1733_ab@103.20.30.40" == process.argv[-1]
    assert SSH_PASSWORD not in " ".join(process.argv)
    assert process.env[ASKPASS_SECRET] == SSH_PASSWORD and process.env[ASKPASS_MODE] == "1" and process.env["SSH_ASKPASS_REQUIRE"] == "force"
    assert not any(SSH_PASSWORD in p.read_text(encoding="utf-8", errors="ignore") for p in tmp_path.rglob("*") if p.is_file())
    assert tunnel.alive()
    tunnel.stop()
    assert process.terminated and not tunnel.alive()


def test_a_private_key_goes_to_a_user_only_file_that_is_deleted_with_the_tunnel(tmp_path):
    pem = "-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----"
    link = parse_adb_answer({"command": VMOS_SSH, "key": pem}, issued_ms=0, requested_minutes=60)
    spawned = []
    tunnel = SshTunnel(link, 19124, ssh="ssh", scratch=tmp_path, spawn=lambda argv, **kw: spawned.append(FakeProcess(argv, **kw)) or spawned[-1])
    tunnel.start()
    key_file = Path(spawned[0].argv[spawned[0].argv.index("-i") + 1])
    assert key_file.read_text().startswith("-----BEGIN") and ASKPASS_SECRET not in spawned[0].env
    tunnel.stop()
    assert not key_file.exists()


def test_askpass_mode_prints_the_key_and_ssh_errors_read_as_plain_words(monkeypatch, capsys):
    monkeypatch.delenv(ASKPASS_MODE, raising=False)
    assert askpass_main() is False
    monkeypatch.setenv(ASKPASS_MODE, "1")
    monkeypatch.setenv(ASKPASS_SECRET, "pw")
    assert askpass_main() is True
    assert capsys.readouterr().out == "pw\n"
    assert explain("user@host: Permission denied (password).")[0] == "TUNNEL_KEY_REFUSED"
    assert explain("bind [127.0.0.1]:19000: Address already in use")[0] == "TUNNEL_PORT_BUSY"
    assert explain("")[0] == "TUNNEL_CLOSED"
    argv = ssh_argv("ssh", AdbLink("ssh", 0, None, ssh_user="u", ssh_host="h", ssh_port=2, target_host="t", target_port=3), 19000, Path("kh"))
    assert "ExitOnForwardFailure=yes" in argv and "ServerAliveInterval=15" in argv


# Lease --------------------------------------------------------------------------------------------------------

def test_a_seven_day_key_renews_with_a_fifth_left_and_short_ones_fifteen_minutes_early():
    week = AdbLink("ssh", 0, 7 * 24 * 3600_000)
    assert L.renew_at_ms(week) == int(7 * 24 * 3600_000 * 0.8)
    hour = AdbLink("ssh", 0, 3600_000)
    assert L.renew_at_ms(hour) == 3600_000 - 15 * 60_000
    assert L.renew_at_ms(AdbLink("direct", 0, None, address="a:1")) is None
    assert [L.backoff_s(n) for n in (1, 2, 3, 4, 9)] == [5, 15, 60, 300, 300]
    assert L.stable_port("k", [], lambda _p: True) == L.stable_port("k", [], lambda _p: True)
    first = L.stable_port("k", [], lambda _p: True)
    assert L.stable_port("k", [first], lambda _p: True) != first


# The keeper ---------------------------------------------------------------------------------------------------

class FakeAdb:
    def __init__(self):
        self.states: dict[str, str] = {}
        self.connects: list[str] = []
        self.disconnects: list[str] = []
        self.reachable = True

    def devices(self):
        return [SimpleNamespace(serial=s, state=st) for s, st in self.states.items()]

    def connect(self, serial, timeout=15):
        self.connects.append(serial)
        if not self.reachable:
            return f"failed to connect to '{serial}': Connection refused"
        self.states[serial] = "device"
        return f"connected to {serial}"

    def run(self, args, timeout=15, use_serial=True):
        if args[0] == "disconnect":
            self.disconnects.append(args[1])
            self.states.pop(args[1], None)
        return ""


class FakeFleet:
    def __init__(self):
        self.sessions: dict[str, SimpleNamespace] = {}

    def find_by_serial(self, serial):
        return self.sessions.get(serial)

    def refresh_once(self, source="manual"):
        return []


class FakeCare:
    def __init__(self):
        self.updates: list[str] = []

    def start_update(self, device_id):
        self.updates.append(device_id)


def make_service(tmp_path, routes=None, **kw):
    clock = Clock()
    http = FakeHttp(routes or vmos_routes())
    adb = FakeAdb()
    spawned: list[FakeProcess] = []
    fleet = FakeFleet()
    care = FakeCare()
    service = CloudFleetService(tmp_path, fleet, care=care, vault=CloudVault(tmp_path / "v", persistent=False), transport=http,
                                clock=clock, adb=adb, ssh="ssh",
                                spawn=lambda argv, **k: spawned.append(FakeProcess(argv, **k)) or spawned[-1],
                                port_free=lambda _p: True, **kw)
    return SimpleNamespace(service=service, clock=clock, http=http, adb=adb, spawned=spawned, fleet=fleet, care=care)


def add_vmos(env):
    return env.service.add_account({"provider": "vmos", "label": "Shop", "secrets": {"accessKey": AK, "secretKey": SK}})


def phone_of(account, remote):
    return next(p for p in account["phones"] if p["remoteId"] == remote)


def run(env, seconds=10):
    env.clock.t += seconds
    env.service.tick()


def test_a_kept_vmos_phone_opens_adb_starts_its_tunnel_connects_and_gets_cyclone(tmp_path):
    env = make_service(tmp_path)
    account = add_vmos(env)
    assert [p["name"] for p in account["phones"]] == ["AC32010230002", "Shop 1"]
    env.service.set_phone(account["id"], "AC32010230001", keep=True)
    env.service.tick()
    state = phone_of(env.service.account_public(account["id"]), "AC32010230001")
    assert state["state"] == "tunnel" and len(env.spawned) == 1
    serial = state["serial"]
    assert serial.startswith("127.0.0.1:19")
    assert env.service.metadata_for_serial(serial) == {"source": "CLOUD", "provider": "vmos", "instanceId": "AC32010230001"}
    env.fleet.sessions[serial] = SimpleNamespace(device_id="dev_cloud1", adb=SimpleNamespace(shell=lambda *a, **k: "Unable to find package"))
    run(env)
    state = phone_of(env.service.account_public(account["id"]), "AC32010230001")
    assert state["state"] == "connected" and state["deviceId"] == "dev_cloud1"
    assert "key renews in 5 days" in state["message"]
    assert env.adb.connects == [serial]
    assert env.care.updates == ["dev_cloud1"]  # Cyclone wasn't there, so phone care installs the verified build
    run(env)
    assert env.care.updates == ["dev_cloud1"] and len(env.spawned) == 1  # once, and the tunnel is left alone


def test_the_key_is_renewed_before_it_expires_on_the_same_local_port(tmp_path):
    env = make_service(tmp_path)
    account = add_vmos(env)
    env.service.set_phone(account["id"], "AC32010230001", keep=True)
    env.service.tick()
    run(env)
    serial = phone_of(env.service.account_public(account["id"]), "AC32010230001")["serial"]
    adb_calls = sum(1 for c in env.http.calls if c[0].endswith("/adb"))
    run(env, 6 * 24 * 3600)  # past the renewal point
    assert sum(1 for c in env.http.calls if c[0].endswith("/adb")) == adb_calls + 1
    assert env.spawned[0].terminated and len(env.spawned) == 2
    assert phone_of(env.service.account_public(account["id"]), "AC32010230001")["serial"] == serial


def test_a_dead_tunnel_is_restarted_and_a_refused_key_gets_a_new_one(tmp_path):
    env = make_service(tmp_path)
    account = add_vmos(env)
    env.service.set_phone(account["id"], "AC32010230001", keep=True)
    env.service.tick()
    run(env)
    env.spawned[-1].returncode = 255
    env.service._links[next(iter(env.service._links))].tunnel._stderr = "Permission denied (password)."
    run(env)
    link = next(iter(env.service._links.values()))
    assert link.state == "waiting" and link.error_code == "TUNNEL_KEY_REFUSED" and link.lease is None
    adb_calls = sum(1 for c in env.http.calls if c[0].endswith("/adb"))
    run(env, 30)
    assert sum(1 for c in env.http.calls if c[0].endswith("/adb")) == adb_calls + 1
    assert env.spawned[-1].returncode is None


def test_a_wrong_key_waits_for_the_owner_and_a_stopped_phone_says_so(tmp_path):
    env = make_service(tmp_path, routes=vmos_routes(**{"/vcpcloud/api/padApi/adb": (403, b"{}")}))
    account = add_vmos(env)
    env.service.set_phone(account["id"], "AC32010230001", keep=True)
    env.service.set_phone(account["id"], "AC32010230002", keep=True)
    env.service.tick()
    public = env.service.account_public(account["id"])
    assert phone_of(public, "AC32010230001")["state"] == "needs_you"
    assert "API key" in phone_of(public, "AC32010230001")["message"]
    assert phone_of(public, "AC32010230002")["state"] == "off"
    calls = len(env.http.calls)
    run(env, 60)
    assert not any(c[0].endswith("/adb") for c in env.http.calls[calls:])  # no hammering a refused key


def test_letting_go_of_a_phone_stops_its_tunnel_and_disconnects_adb(tmp_path):
    env = make_service(tmp_path)
    account = add_vmos(env)
    env.service.set_phone(account["id"], "AC32010230001", keep=True)
    env.service.tick()
    run(env)
    serial = phone_of(env.service.account_public(account["id"]), "AC32010230001")["serial"]
    env.service.set_phone(account["id"], "AC32010230001", keep=False)
    assert env.spawned[0].terminated and serial in env.adb.disconnects
    assert phone_of(env.service.account_public(account["id"]), "AC32010230001")["state"] == "off"
    env.service.remove_account(account["id"])
    assert env.service.status()["accounts"] == []


def test_remote_adb_phones_are_added_by_address_and_reconnected_when_they_drop(tmp_path):
    env = make_service(tmp_path)
    account = env.service.add_account({"provider": "adb", "label": "Lab box"})
    account = env.service.add_address(account["id"], "adb connect 203.0.113.7:5555", "Box 1")
    env.service.tick()
    assert env.adb.connects == ["203.0.113.7:5555"]
    env.adb.states.clear()
    env.adb.reachable = False
    run(env)
    link = next(iter(env.service._links.values()))
    assert link.state == "waiting" and "Trying again" in link.message
    env.adb.reachable = True
    run(env, 10)
    assert link.state == "connected" and env.service.metadata_for_serial("203.0.113.7:5555")["source"] == "CLOUD"
    with pytest.raises(DesktopRuntimeError):
        env.service.add_address(account["id"], "not an address")


def test_account_input_is_checked_and_keys_never_come_back_out(tmp_path):
    env = make_service(tmp_path)
    with pytest.raises(DesktopRuntimeError):
        env.service.add_account({"provider": "gcp"})
    with pytest.raises(DesktopRuntimeError):
        env.service.add_account({"provider": "vmos", "secrets": {"accessKey": AK}})
    with pytest.raises(DesktopRuntimeError):
        env.service.add_account({"provider": "vmos", "secrets": {"accessKey": AK, "secretKey": SK}, "baseUrl": "http://evil"})
    account = add_vmos(env)
    env.service.set_phone(account["id"], "AC32010230001", keep=True)
    env.service.tick()
    run(env)
    text = json.dumps(env.service.status())
    for secret in (AK, SK, SSH_PASSWORD):
        assert secret not in text


def test_the_routes_take_no_commands_and_return_no_keys(tmp_path):
    from fastapi import FastAPI
    from fastapi.testclient import TestClient
    from cyclone_device_gateway.cloud_fleet.api import create_cloud_fleet_router

    env = make_service(tmp_path)
    app = FastAPI()
    app.include_router(create_cloud_fleet_router(env.service, "tok"))
    client = TestClient(app)
    auth = {"Authorization": "Bearer tok"}
    assert client.get("/v1/cloud").status_code == 401
    created = client.post("/v1/cloud/accounts", headers=auth, json={"provider": "vmos", "secrets": {"accessKey": AK, "secretKey": SK}})
    assert created.status_code == 200 and SK not in created.text and AK not in created.text
    account_id = created.json()["id"]
    assert client.post(f"/v1/cloud/accounts/{account_id}/phones/AC32010230001", headers=auth, json={"keep": True, "command": "rm -rf /"}).status_code == 422
    assert client.post(f"/v1/cloud/accounts/{account_id}/phones/AC32010230001", headers=auth, json={"keep": True}).json()["phones"]
    assert client.post(f"/v1/cloud/accounts/{account_id}/phones/nope", headers=auth, json={"keep": True}).status_code == 404
    assert client.post("/v1/cloud/accounts", headers=auth, json={"provider": "adb", "shell": "id"}).status_code == 422
    env.service.tick()
    assert SSH_PASSWORD not in client.get("/v1/cloud", headers=auth).text


def test_the_vault_encrypts_on_windows_and_keeps_nothing_on_disk_elsewhere(tmp_path):
    flip = lambda data: bytes(b ^ 0x5A for b in data)
    vault = CloudVault(tmp_path / "a.dpapi", protect=flip, unprotect=flip, persistent=True)
    vault.put({"id": "acc_1", "provider": "vmos", "secrets": {"secretKey": SK}, "phones": {}, "addresses": {}})
    assert SK.encode() not in (tmp_path / "a.dpapi").read_bytes()
    assert CloudVault(tmp_path / "a.dpapi", protect=flip, unprotect=flip, persistent=True).account("acc_1")["secrets"]["secretKey"] == SK
    memory = CloudVault(tmp_path / "b.dpapi", persistent=False)
    memory.put({"id": "acc_2", "provider": "adb", "secrets": {}, "phones": {}, "addresses": {}})
    assert not (tmp_path / "b.dpapi").exists() and memory.security_mode == "MEMORY_ONLY"
    (tmp_path / "c.dpapi").write_bytes(b"garbage")
    broken = CloudVault(tmp_path / "c.dpapi", protect=flip, unprotect=lambda _d: (_ for _ in ()).throw(OSError("other user")), persistent=True)
    assert broken.accounts() == {} and broken.load_error == "OSError"


def test_a_cloud_phone_that_dropped_is_being_reopened_not_a_cable_problem():
    verdict = diagnose(discovery=DiscoveryState.ABSENT, bridge=BridgeState.DEGRADED, trust=AITrustState.UNPAIRED,
                       session_ready=False, source="CLOUD")
    assert verdict.code == "CLOUD_LINK_DOWN" and verdict.working and "cable" not in verdict.message
