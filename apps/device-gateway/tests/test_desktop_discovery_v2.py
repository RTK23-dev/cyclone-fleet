import os
from pathlib import Path
import subprocess

import pytest

from cyclone_device_gateway.adb.client import ADBDevice, _hidden_process_kwargs
from cyclone_device_gateway.config import resolve_adb_path, resolve_gateway_port
from cyclone_device_gateway.desktop_runtime.fleet import DeviceFleetManager


class Inventory:
    def __init__(self, devices):
        self.current = list(devices)

    def devices(self):
        return list(self.current)


class FakeDeviceADB:
    def __init__(self, serial):
        self.serial = serial

    def ensure_bridge_forward(self, _port):
        return True

    def remove_forward(self, _port):
        return None

    def shell(self, *args, timeout=15):
        if args[:2] == ("pm", "path"):
            return "package:/data/app/com.cyclone.mobile/base.apk\n"
        if args[:2] == ("dumpsys", "power"):
            return "mWakefulness=Awake\nDisplay Power: state=ON\n"
        if args[:2] == ("wm", "size"):
            return "Physical size: 1080x2400\n"
        return ""


def test_resolve_adb_path_finds_cyclone_platform_tools_location(monkeypatch, tmp_path):
    adb = tmp_path / "Android" / "platform-tools" / "adb.exe"
    adb.parent.mkdir(parents=True)
    adb.write_bytes(b"stub")
    monkeypatch.setenv("LOCALAPPDATA", str(tmp_path))
    monkeypatch.delenv("ADB_PATH", raising=False)
    monkeypatch.delenv("ANDROID_HOME", raising=False)
    monkeypatch.delenv("ANDROID_SDK_ROOT", raising=False)
    monkeypatch.setattr("cyclone_device_gateway.config.shutil.which", lambda _name: None)
    assert Path(resolve_adb_path()) == adb


def test_dynamic_gateway_port_can_come_from_explicit_port_or_loopback_url(monkeypatch):
    monkeypatch.setenv("CYCLONE_DEVICE_GATEWAY_PORT", "43123")
    monkeypatch.setenv("CYCLONE_DEVICE_GATEWAY_URL", "http://127.0.0.1:43124")
    assert resolve_gateway_port() == 43123

    monkeypatch.delenv("CYCLONE_DEVICE_GATEWAY_PORT")
    assert resolve_gateway_port() == 43124


def test_manual_fleet_scan_reports_what_adb_and_cyclone_each_see():
    inventory = Inventory([ADBDevice("PIXEL8", "device", model="Pixel_8")])
    fleet = DeviceFleetManager(
        inventory_adb=inventory,
        adb_factory=lambda serial: FakeDeviceADB(serial),
        poll_seconds=20,
    )
    devices = fleet.refresh_once(source="manual")
    assert len(devices) == 1
    diagnostic = fleet.diagnostics()
    assert diagnostic["adbAvailable"] is True
    assert diagnostic["rawAdbDeviceCount"] == 1
    assert diagnostic["authorizedAdbDeviceCount"] == 1
    assert diagnostic["fleetDeviceCount"] == 1
    assert diagnostic["lastScanSource"] == "manual"
    assert diagnostic["lastScanError"] is None


def test_tracker_unavailable_does_not_make_successful_adb_scan_unhealthy():
    inventory = Inventory([])  # deliberately no start_track_devices method
    fleet = DeviceFleetManager(inventory_adb=inventory, poll_seconds=20)
    fleet.refresh_once(source="manual")
    diagnostic = fleet.diagnostics()
    assert diagnostic["adbAvailable"] is True
    assert diagnostic["rawAdbDeviceCount"] == 0


@pytest.mark.skipif(os.name != "nt", reason="Windows console suppression contract")
def test_windows_adb_helpers_are_created_without_console_windows():
    kwargs = _hidden_process_kwargs()
    assert int(kwargs["creationflags"]) & subprocess.CREATE_NO_WINDOW
    startupinfo = kwargs["startupinfo"]
    assert startupinfo.dwFlags & subprocess.STARTF_USESHOWWINDOW
    assert startupinfo.wShowWindow == subprocess.SW_HIDE
