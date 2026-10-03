from __future__ import annotations

from dataclasses import dataclass
from types import SimpleNamespace

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from cyclone_device_gateway.adb.client import ADBDevice, ADBError
from cyclone_device_gateway.adb.onboarding import (
    ADBTransportOnboarding,
    TransportOnboardingError,
    normalize_adb_endpoint,
)
from cyclone_device_gateway.desktop_runtime import transport_api
from cyclone_device_gateway.desktop_runtime.transport_api import create_transport_router


class FakeADB:
    def __init__(self, inventories=None, *, fail_pair=False, fail_connect=False):
        self.calls: list[list[str]] = []
        self.inventories = list(inventories or [[]])
        self.fail_pair = fail_pair
        self.fail_connect = fail_connect

    def run(self, args, *, timeout=15, use_serial=True, binary=False):
        call = list(args)
        self.calls.append(call)
        if call and call[0] == "pair" and self.fail_pair:
            raise ADBError(f"pair failed and reflected secret {call[-1]}")
        if call and call[0] == "connect" and self.fail_connect:
            raise ADBError("connect failed")
        return "ok"

    def devices(self):
        if len(self.inventories) > 1:
            return self.inventories.pop(0)
        return list(self.inventories[0])


@pytest.mark.parametrize("raw", [
    "",
    "-s:5555",
    "http://10.0.0.2:5555",
    "10.0.0.2:0",
    "10.0.0.2:65536",
    "host name:5555",
    "host:5555/extra",
    "host;rm:5555",
])
def test_endpoint_validation_rejects_non_host_port_and_argument_injection(raw):
    with pytest.raises(TransportOnboardingError) as exc:
        normalize_adb_endpoint(raw)
    assert exc.value.code == "INVALID_ENDPOINT"


def test_usb_status_distinguishes_ready_unauthorized_and_offline_without_mutation():
    adb = FakeADB([[
        ADBDevice("USB_READY", "device", model="Pixel_8"),
        ADBDevice("USB_LOCKED", "unauthorized", model="Pixel_7"),
        ADBDevice("USB_STALE", "offline", model="Pixel_6"),
    ]])
    result = ADBTransportOnboarding(adb).usb_status()
    assert result["readyDeviceCount"] == 1
    assert result["deviceCount"] == 3
    states = {item["serial"]: item for item in result["devices"]}
    assert states["USB_READY"]["ready"] is True
    assert "approve" in states["USB_LOCKED"]["action"].lower()
    assert "offline" in states["USB_STALE"]["action"].lower()
    assert adb.calls == []


def test_wireless_pair_uses_separate_pair_and_connect_endpoints_and_never_returns_secret():
    adb = FakeADB([
        [],
        [ADBDevice("192.168.1.25:42891", "device", model="Pixel_8")],
    ])
    result = ADBTransportOnboarding(adb, settle_seconds=0.01, poll_seconds=0.001).pair_wireless(
        "192.168.1.25:37123",
        "123456",
        "192.168.1.25:42891",
    )
    assert result["ok"] is True
    assert adb.calls[0] == ["pair", "192.168.1.25:37123", "123456"]
    assert adb.calls[1] == ["connect", "192.168.1.25:42891"]
    assert "123456" not in repr(result)


def test_wireless_pair_failure_redacts_pairing_code_even_when_adb_error_reflects_it():
    adb = FakeADB(fail_pair=True)
    onboarding = ADBTransportOnboarding(adb)
    with pytest.raises(TransportOnboardingError) as exc:
        onboarding.pair_wireless("10.0.0.2:37001", "654321", "10.0.0.2:42001")
    assert exc.value.code == "WIRELESS_PAIR_FAILED"
    assert "654321" not in str(exc.value)
    assert "654321" not in exc.value.safe_message


def test_wireless_rejects_same_pair_and_connect_port_before_adb_is_called():
    adb = FakeADB()
    with pytest.raises(TransportOnboardingError) as exc:
        ADBTransportOnboarding(adb).pair_wireless("10.0.0.2:37001", "123456", "10.0.0.2:37001")
    assert exc.value.code == "PAIR_CONNECT_ENDPOINTS_MATCH"
    assert adb.calls == []


def test_remote_connect_reports_offline_target_as_action_required_not_ready():
    endpoint = "vmos.example:5555"
    adb = FakeADB([[ADBDevice(endpoint, "offline", model="VMOS")]])
    result = ADBTransportOnboarding(adb, settle_seconds=0).connect(endpoint, mode="vmos")
    assert result["ok"] is False
    assert result["device"]["state"] == "offline"
    assert "offline" in result["next"].lower()


def test_remote_connect_rejects_stale_inventory_when_requested_target_never_appears():
    adb = FakeADB([[ADBDevice("old-vmos.example:5555", "device", model="Old VMOS")]])
    with pytest.raises(TransportOnboardingError) as exc:
        ADBTransportOnboarding(adb, settle_seconds=0).connect("new-vmos.example:5555", mode="vmos")
    assert exc.value.code == "ADB_CONNECT_NOT_VISIBLE"


def test_reconnect_can_recover_from_offline_to_ready_on_second_attempt():
    endpoint = "vmos.example:5555"
    adb = FakeADB([
        [ADBDevice(endpoint, "offline", model="VMOS")],
        [ADBDevice(endpoint, "device", model="VMOS")],
    ])
    onboarding = ADBTransportOnboarding(adb, settle_seconds=0)
    first = onboarding.connect(endpoint, mode="vmos")
    second = onboarding.connect(endpoint, mode="vmos")
    assert first["ok"] is False
    assert second["ok"] is True
    assert [call[0] for call in adb.calls] == ["connect", "connect"]


@dataclass
class FakeFleet:
    refreshes: list[str]

    def refresh_once(self, *, source="manual"):
        self.refreshes.append(source)
        return []


class FakeTransportService:
    def __init__(self):
        self.calls = []

    def usb_status(self):
        self.calls.append(("usb",))
        return {"mode": "usb", "ok": True, "next": "ready", "devices": []}

    def pair_wireless(self, pair_endpoint, pairing_code, connect_endpoint):
        self.calls.append(("wifi", pair_endpoint, pairing_code, connect_endpoint))
        return {"mode": "wifi", "ok": True, "next": "ready", "endpoint": connect_endpoint}

    def connect(self, endpoint, *, mode="vmos"):
        self.calls.append((mode, endpoint))
        return {"mode": mode, "ok": True, "next": "ready", "endpoint": endpoint}

    def disconnect(self, endpoint):
        self.calls.append(("disconnect", endpoint))
        return {"ok": True, "endpoint": endpoint}


def _transport_test_client(monkeypatch):
    service = FakeTransportService()
    monkeypatch.setattr(transport_api, "ADBTransportOnboarding", lambda _adb: service)
    runtime = SimpleNamespace(
        settings=SimpleNamespace(adb_path="bundled-adb"),
        fleet=FakeFleet([]),
    )
    app = FastAPI()
    app.include_router(create_transport_router(runtime, "secret-token"))
    return TestClient(app), service, runtime


def test_transport_api_requires_bearer_and_forbids_arbitrary_command_fields(monkeypatch):
    client, _service, _runtime = _transport_test_client(monkeypatch)
    assert client.get("/v1/transport/usb").status_code in {401, 403}
    response = client.post(
        "/v1/transport/vmos/connect",
        headers={"Authorization": "Bearer secret-token"},
        json={"endpoint": "vmos.example:5555", "command": "shell id"},
    )
    assert response.status_code == 422


def test_wifi_api_pair_connect_forces_transport_rescan(monkeypatch):
    client, service, runtime = _transport_test_client(monkeypatch)
    response = client.post(
        "/v1/transport/wifi/pair",
        headers={"Authorization": "Bearer secret-token"},
        json={
            "pair_endpoint": "10.0.0.2:37001",
            "connect_endpoint": "10.0.0.2:42001",
            "pairing_code": "123456",
        },
    )
    assert response.status_code == 200
    assert service.calls == [("wifi", "10.0.0.2:37001", "123456", "10.0.0.2:42001")]
    assert runtime.fleet.refreshes == ["transport-wifi"]


def test_vmos_api_connect_forces_transport_rescan(monkeypatch):
    client, service, runtime = _transport_test_client(monkeypatch)
    response = client.post(
        "/v1/transport/vmos/connect",
        headers={"Authorization": "Bearer secret-token"},
        json={"endpoint": "vmos.example:5555"},
    )
    assert response.status_code == 200
    assert service.calls == [("vmos", "vmos.example:5555")]
    assert runtime.fleet.refreshes == ["transport-vmos"]
