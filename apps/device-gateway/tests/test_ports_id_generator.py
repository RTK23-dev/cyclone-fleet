"""Native starter: owner pairing, scope/consent enforcement and targeted private traffic."""
import json

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from cyclone_device_gateway.ports.api import create_ports_router
from cyclone_device_gateway.ports.hub import PortHub, PortsError
from cyclone_device_gateway.ports.id_generator import PORTS, validate_config


MANIFEST = {"contract": "cyclone.ports/1", "name": "id-generator", "version": "0.1.0", "title": "ID Generator",
            "endpoint": "http://127.0.0.1:8787", "serves": [{"port": p, "way": w} for p, w in PORTS.items()],
            "needs": {"personal": True}, "features": ["idempotent-delivery"],
            "ui": {"panelUrl": "http://127.0.0.1:5173/plugins/id-generator?embed=1",
                   "settingsUrl": "http://127.0.0.1:5173/settings/id-generator"}}


@pytest.fixture
def native(tmp_path):
    paired = []
    def fetch(method, url, body=None, headers=None, timeout=2):
        if url.endswith("/cyclone-plugin.json"):
            return 200, MANIFEST, 0
        if url.endswith("/health") or url.endswith("/api/health"):
            return 200, {"ok": True, "studio": {"product": "mrz-studio-local", "version": "7.2.1"}}, 0
        if url.endswith("/api/id-generator/pair"):
            paired.append(json.loads(body)["secret"])
            return 200, {"configured": True}, 0
        return 200, {"accepted": True}, 0
    h = PortHub(tmp_path, fetch=fetch, checker=lambda *_: [])
    h.id_generator.fetch = fetch
    yield h, paired
    h.traffic.flush()
    h.stop()
    h.store.close()


def activate(h):
    h.id_generator.connect(list(PORTS))
    # Conformance outcomes are covered by the real HTTP test suite; this fixture supplies the passed outcome.
    h.store.update("id-generator", checks={"passed": True, "total": 1, "failed": 0, "items": []}, health="ok")


def test_discovery_is_read_only_and_pairing_never_returns_key(native):
    h, paired = native
    assert h.id_generator.status()["state"] == "found"
    assert not h.store.plugins() and not paired
    result = h.id_generator.connect(list(PORTS))
    assert len(paired) == 1 and paired[0].startswith("k1.")
    assert paired[0] not in json.dumps(result)
    assert paired[0] not in json.dumps(h.store.activity())
    h.id_generator.connect(list(PORTS))
    assert paired[0] == paired[1]  # every restart/new Glass uses the durable PC key


def test_policy_persists_and_enforces_both_contexts(native):
    h, _ = native
    activate(h)
    c = h.id_generator.config()
    h.id_generator.configure(dict(c, apps=["com.example.company"], routines=["rtn_company1"]))
    assert h.id_generator.config()["apps"] == ["com.example.company"]
    assert not h.traffic._route({"app": "com.example.other", "routine": "rtn_company1", "plugin": "id-generator"}, "value.in")["effective"]
    assert h.traffic._route({"app": "com.example.company", "routine": "rtn_company1", "plugin": "id-generator"}, "value.in")["effective"] == ["id-generator"]
    h.id_generator.configure(dict(c, agentEnabled=False))
    assert h.id_generator.skills() == []
    assert not h.traffic._route({"plugin": "id-generator"}, "value.in")["effective"]


def test_target_does_not_override_owner_port_map_or_consent(native):
    h, _ = native
    activate(h)
    h.set_binding("default", "value.in", [])
    assert not h.traffic._route({"plugin": "id-generator"}, "value.in")["effective"]
    h.set_binding("default", "value.in", None)
    assert h.traffic._route({"plugin": "id-generator"}, "value.in")["effective"] == ["id-generator"]
    h.set_port("id-generator", "value.in", False)
    assert not h.id_generator.skills()
    assert not h.traffic._route({"plugin": "id-generator"}, "value.in")["effective"]


def test_only_literal_loopback_addresses_and_bounded_usage_text(native):
    h, _ = native
    for url in ("https://example.com", "http://192.168.1.8:8787", "http://127.0.0.1:8787/?x=1", "http://u:p@localhost"):
        with pytest.raises(PortsError):
            validate_config(dict(h.id_generator.config(), apiBase=url))
    for text in ("x" * 4001, "api_key=sk-supersecretabcdefghijk"):
        with pytest.raises(PortsError):
            validate_config(dict(h.id_generator.config(), instructions=text))
    assert h.id_generator._ui_url("https://evil.example/plugins/id-generator", "/plugins/id-generator") is None
    assert h.id_generator._ui_url("http://127.0.0.1:5173/plugins/id-generator?next=evil", "/plugins/id-generator") is None


def test_revocation_blocks_pending_and_already_held_generator_answers(native):
    h, _ = native
    activate(h)
    config = h.id_generator.config()
    for delivered_first in (False, True):
        h.id_generator.configure(dict(config, agentEnabled=True))
        opened = h.traffic.wait("run_revoked", "value.in", {"requestId": "employee1"}, 30, {"plugin": "id-generator"})
        h.traffic.flush()
        token = h.store.wait_token(opened["awaitId"])
        body = {"v": 1, "deliveryId": "dl_native_test", "value": {"status": "complete"}}
        if delivered_first:
            assert h.traffic.deliver("run_revoked", "value.in", "Port " + token, body)[0] == 200
        h.id_generator.configure(dict(config, agentEnabled=False))
        if not delivered_first:
            assert h.traffic.deliver("run_revoked", "value.in", "Port " + token, body)[0] == 410
        answer = h.traffic.result(opened["awaitId"])
        assert answer["state"] == "cancelled" and "value" not in answer


def test_native_api_requires_bearer_and_does_not_provision_during_get(native):
    h, paired = native
    app = FastAPI()
    app.include_router(create_ports_router(type("Runtime", (), {"ports": h})(), "t" * 32))
    with TestClient(app) as client:
        assert client.get("/v1/ports/starters/id-generator").status_code == 401
        headers = {"Authorization": "Bearer " + "t" * 32}
        assert client.get("/v1/ports/starters/id-generator", headers=headers).json()["state"] == "found"
        assert not paired
        assert client.get("/v1/ports/skills", headers=headers).json() == {"skills": []}
        assert client.post("/v1/ports/starters/id-generator/connect", headers=headers, json={"allowed": list(PORTS)}).status_code == 200
