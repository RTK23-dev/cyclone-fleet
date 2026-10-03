from __future__ import annotations

from pathlib import Path

from fastapi.testclient import TestClient

from cyclone_device_gateway.adb.client import ADBDevice
from cyclone_device_gateway.config import Settings, resolve_adb_path
from cyclone_device_gateway.desktop_runtime.api import DesktopRuntime, create_desktop_app
from cyclone_device_gateway.desktop_runtime.fleet import DeviceFleetManager


class Inventory:
    def __init__(self):
        self.current = []

    def devices(self):
        return list(self.current)


class FakeADB:
    def __init__(self, serial: str):
        self.serial = serial

    def ensure_bridge_forward(self, _port: int):
        return True

    def remove_forward(self, _port: int):
        return None

    def shell(self, *args: str, timeout: int = 15):
        if args[:2] == ("pm", "path"):
            return "package:/data/app/com.cyclone.mobile/base.apk\n"
        if args[:2] == ("dumpsys", "power"):
            return "mWakefulness=Awake\n"
        if args[:2] == ("wm", "size"):
            return "Physical size: 1080x2400\n"
        return ""


def test_resolve_adb_path_finds_cyclone_platform_tools_location(tmp_path, monkeypatch):
    platform_tools = tmp_path / "Android" / "platform-tools"
    platform_tools.mkdir(parents=True)
    adb = platform_tools / "adb.exe"
    adb.write_bytes(b"placeholder")
    monkeypatch.delenv("ADB_PATH", raising=False)
    monkeypatch.delenv("ANDROID_SDK_ROOT", raising=False)
    monkeypatch.delenv("ANDROID_HOME", raising=False)
    monkeypatch.setenv("LOCALAPPDATA", str(tmp_path))
    monkeypatch.setattr("cyclone_device_gateway.config.shutil.which", lambda _name: None)
    assert Path(resolve_adb_path()) == adb


def test_manual_scan_refreshes_fleet_immediately(tmp_path):
    inventory = Inventory()
    fleet = DeviceFleetManager(
        inventory_adb=inventory,
        adb_factory=lambda serial: FakeADB(serial),
        poll_seconds=20.0,
    )
    settings = Settings("pc-secret", None, "adb", tmp_path)
    runtime = DesktopRuntime(settings, fleet=fleet)
    app = create_desktop_app(settings, runtime)
    headers = {"Authorization": "Bearer pc-secret"}

    with TestClient(app) as client:
        assert client.get("/v1/fleet", headers=headers).json()["devices"] == []
        inventory.current = [ADBDevice("PHONE-1234", "device", "Pixel_8")]
        response = client.post("/v1/fleet/scan", headers=headers)
        assert response.status_code == 200
        devices = response.json()["devices"]
        assert len(devices) == 1
        assert devices[0]["model"] == "Pixel_8"
        assert devices[0]["state"] == "UNPAIRED"
        assert response.json()["discovery"]["rawAdbDeviceCount"] == 1
        assert response.json()["discovery"]["lastScanSource"] == "manual"


def test_default_desktop_runtime_uses_event_driven_discovery_with_low_rate_fallback(tmp_path):
    settings = Settings("pc-secret", None, "adb", tmp_path)
    runtime = DesktopRuntime(settings)
    assert runtime.fleet.poll_seconds == 20.0
