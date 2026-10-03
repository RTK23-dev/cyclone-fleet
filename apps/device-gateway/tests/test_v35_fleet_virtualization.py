from __future__ import annotations

import json
import os
from pathlib import Path
import socket
import time

import pytest
from fastapi.testclient import TestClient

from cyclone_device_gateway.backends.base import DeviceBackend, DeviceBackendCapabilities, DeviceBackendStatus
from cyclone_device_gateway.desktop_runtime.batch import FleetBatchService
from cyclone_device_gateway.desktop_runtime.api import DesktopRuntime, create_desktop_app
from cyclone_device_gateway.desktop_runtime.workspace import FleetWorkspaceStore
from cyclone_device_gateway.config import Settings
from cyclone_device_gateway.virtual.avd import AndroidEmulatorProvider, CommandResult
from cyclone_device_gateway.virtual.models import VirtualDeviceConfig, VirtualInstance, VirtualInstanceState
from cyclone_device_gateway.virtual.ports import LoopbackPortAllocator
from cyclone_device_gateway.virtual.registry import VirtualDeviceRegistry
from cyclone_device_gateway.virtual.service import VirtualDeviceService


class FakeBackend:
    def __init__(self, device_id: str, *, fail: bool = False):
        self.device_id = device_id
        self.fail = fail

    def identify(self): return {"deviceId": self.device_id}
    def status(self): return DeviceBackendStatus(self.device_id, "READY", "USB", None, 1)
    def capabilities(self): return DeviceBackendCapabilities(True, True, ("phone.home",), True, ("thumbnail",), True)
    def observe(self, *, mode="compact"): return {"observation": {"id": "obs"}}
    def search(self, query): return {"items": []}
    def act(self, capability_id, params, *, goal=""):
        if self.fail:
            raise RuntimeError("isolated failure")
        return {"success": True, "transport_ok": True, "execution_ok": True, "verification_ok": True, "verification": "android_verified"}
    def screenshot(self, *, profile="thumbnail"): return {"filePath": f"{self.device_id}.jpg"}
    def stream(self, *, profile="thumbnail"): return object()
    def app_state(self): return {"package": "com.example"}
    def diagnostics(self): return {"ok": True}
    def recover(self): return {"requested": True}
    def close(self): return None


def test_device_backend_contract_is_runtime_conformant_and_capabilities_are_explicit():
    backend = FakeBackend("dev_aaaaaaaa")
    assert isinstance(backend, DeviceBackend)
    assert backend.status().source == "USB"
    assert backend.capabilities().public()["semanticActions"] == ["phone.home"]


def test_workspace_persists_groups_selection_and_filters_unified_inventory(tmp_path):
    path = tmp_path / "fleet-workspace.json"
    store = FleetWorkspaceStore(path)
    group = store.put_group("qa_lab", "QA lab", ["dev_physical", "dev_virtual", "dev_physical"])
    assert group["deviceIds"] == ["dev_physical", "dev_virtual"]
    assert store.set_selection(["dev_virtual", "dev_physical"]) == ["dev_virtual", "dev_physical"]

    restored = FleetWorkspaceStore(path)
    devices = [
        {"deviceId": "dev_physical", "name": "Pixel 8", "source": "USB", "state": "READY"},
        {"deviceId": "dev_virtual", "name": "Android lab", "source": "VIRTUAL", "provider": "android-emulator", "state": "READY"},
    ]
    assert [item["deviceId"] for item in restored.search(devices, "android", group=restored.group("qa_lab"))] == ["dev_virtual"]
    assert restored.public()["selectedDeviceIds"] == ["dev_virtual", "dev_physical"]


def test_workspace_rejects_implicit_or_malformed_targets(tmp_path):
    store = FleetWorkspaceStore(tmp_path / "workspace.json")
    with pytest.raises(ValueError):
        store.set_selection(["*"])
    with pytest.raises(ValueError):
        store.put_group("not valid", "name", [])


def test_loopback_port_allocator_avoids_reserved_and_bound_ports():
    blocker = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    blocker.bind(("127.0.0.1", 5554))
    try:
        console, adb = LoopbackPortAllocator(5554, 5560).allocate_emulator_pair({5556})
    finally:
        blocker.close()
    assert (console, adb) == (5558, 5559)


def test_loopback_port_allocator_leases_and_releases_pairs():
    allocator = LoopbackPortAllocator(5590, 5594)
    first = allocator.allocate_emulator_pair()
    second = allocator.allocate_emulator_pair()
    assert first == (5590, 5591)
    assert second == (5592, 5593)
    allocator.release_emulator_pair(*first)
    assert allocator.allocate_emulator_pair() == first


def test_registry_persists_provider_state_and_never_replays_running_claim(tmp_path):
    path = tmp_path / "instances.json"
    registry = VirtualDeviceRegistry(path)
    item = VirtualInstance(
        "vdev_0123456789abcdef", "android-emulator", "dev_0123456789abcdef", "cyclone_test",
        VirtualDeviceConfig("system-images;android-35;google_apis;x86_64"),
        state=VirtualInstanceState.RUNNING, data_path=str(tmp_path / "avd"), adb_endpoint="emulator-5554",
        console_port=5554, created_at_ms=1,
    )
    registry.save(item)
    restored = VirtualDeviceRegistry(path).get(item.instance_id)
    assert restored.state == VirtualInstanceState.STOPPED
    assert restored.adb_endpoint == "emulator-5554"
    assert VirtualDeviceRegistry(path).metadata_for_serial("emulator-5554")["provider"] == "android-emulator"


class FakeProcess:
    pid = 4242


class FakeRunner:
    def __init__(self):
        self.calls = []

    def run(self, executable, args, *, timeout=30, input_text=None, env=None):
        self.calls.append((Path(executable).name, tuple(args), input_text, env.get("ANDROID_AVD_HOME") if env else None))
        if "get-state" in args:
            return CommandResult(0, "device\n", "")
        if "sys.boot_completed" in args:
            return CommandResult(0, "1\n", "")
        return CommandResult(0, "", "")

    def start(self, executable, args, *, env=None):
        self.calls.append((Path(executable).name, tuple(args), None, env.get("ANDROID_AVD_HOME") if env else None))
        return FakeProcess()


def fake_sdk(root: Path) -> Path:
    emulator_name = "emulator.exe" if os.name == "nt" else "emulator"
    adb_name = "adb.exe" if os.name == "nt" else "adb"
    avdmanager_name = "avdmanager.bat" if os.name == "nt" else "avdmanager"
    for relative in (
        f"emulator/{emulator_name}", f"platform-tools/{adb_name}", f"cmdline-tools/latest/bin/{avdmanager_name}",
        "system-images/android-35/google_apis/x86_64/package.xml",
    ):
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("test", encoding="utf-8")
    return root


def test_android_emulator_provider_uses_only_fixed_argument_vectors_and_loopback_endpoint(tmp_path):
    runner = FakeRunner()
    provider = AndroidEmulatorProvider(tmp_path / "runtime", sdk_root=fake_sdk(tmp_path / "sdk"), runner=runner, boot_timeout=5)
    image = "system-images;android-35;google_apis;x86_64"
    item = VirtualInstance(
        "vdev_0123456789abcdef", provider.provider_id, "dev_0123456789abcdef", "cyclone_0123456789abcdef",
        VirtualDeviceConfig(image, width=720, height=1280, dpi=320), console_port=5554,
    )
    assert provider.health().available is True
    provider.create(item)
    provider.start(item)
    assert item.state == VirtualInstanceState.RUNNING
    assert item.adb_endpoint == "emulator-5554"
    config = (Path(item.data_path) / "config.ini").read_text(encoding="utf-8")
    assert "hw.lcd.width=720" in config and "hw.lcd.density=320" in config
    emulator_name = "emulator.exe" if os.name == "nt" else "emulator"
    launch = next(call for call in runner.calls if call[0] == emulator_name)
    assert "-port" in launch[1] and "5554" in launch[1] and "-no-window" in launch[1]
    assert all(isinstance(call[1], tuple) for call in runner.calls)
    provider.stop(item)
    provider.delete(item)
    assert any(call[1][:4] == ("-s", "emulator-5554", "emu", "kill") for call in runner.calls)


def test_virtual_service_fails_closed_when_provider_is_unavailable(tmp_path):
    provider = AndroidEmulatorProvider(tmp_path / "runtime", sdk_root=tmp_path / "missing")
    service = VirtualDeviceService(VirtualDeviceRegistry(tmp_path / "registry.json"), [provider])
    assert service.health()[0]["available"] is False
    with pytest.raises(RuntimeError, match="Android SDK is missing"):
        service.create(provider.provider_id, VirtualDeviceConfig("system-images;android-35;google_apis;x86_64"))


def test_virtual_service_exposes_stable_status_capabilities_and_endpoint(tmp_path):
    runner = FakeRunner()
    provider = AndroidEmulatorProvider(tmp_path / "runtime", sdk_root=fake_sdk(tmp_path / "sdk"), runner=runner, boot_timeout=5)
    registry = VirtualDeviceRegistry(tmp_path / "registry.json")
    service = VirtualDeviceService(registry, [provider], ports=LoopbackPortAllocator(5580, 5584))
    image = "system-images;android-35;google_apis;x86_64"
    created = service.create(provider.provider_id, VirtualDeviceConfig(image))
    assert "clone" not in service.health()[0]["capabilities"]
    assert "snapshot.create" not in service.health()[0]["capabilities"]
    running = service.lifecycle(created["instanceId"], "start")
    endpoint = service.endpoint(created["instanceId"])
    assert running["state"] == "RUNNING"
    assert endpoint["ready"] is True and endpoint["bindAddress"] == "127.0.0.1"
    with pytest.raises(RuntimeError, match="Stop"):
        service.configure(created["instanceId"], VirtualDeviceConfig(image, width=800, height=1280, dpi=300))
    service.lifecycle(created["instanceId"], "stop")
    configured = service.configure(created["instanceId"], VirtualDeviceConfig(image, width=800, height=1280, dpi=300))
    assert configured["config"]["width"] == 800
    assert service.get(created["instanceId"])["state"] == "STOPPED"


def wait_batch(service: FleetBatchService, batch_id: str) -> dict:
    deadline = time.monotonic() + 3
    while time.monotonic() < deadline:
        result = service.get(batch_id)
        if result["status"] != "RUNNING":
            return result
        time.sleep(0.01)
    raise AssertionError("batch did not finish")


def test_batch_requires_explicit_unique_targets_and_reports_per_device_verification():
    service = FleetBatchService(lambda device_id: FakeBackend(device_id, fail=device_id.endswith("bad")))
    with pytest.raises(ValueError, match="non-empty unique"):
        service.submit([], "home")
    with pytest.raises(ValueError, match="non-empty unique"):
        service.submit(["dev_a", "dev_a"], "home")
    submitted = service.submit(["dev_good", "dev_bad"], "home")
    result = wait_batch(service, submitted["batchId"])
    assert result["summary"] == {"requested": 2, "completed": 2, "succeeded": 1, "failed": 1}
    by_id = {item["deviceId"]: item for item in result["results"]}
    assert by_id["dev_good"]["verificationOk"] is True
    assert by_id["dev_bad"]["ok"] is False


def test_batch_rejects_arbitrary_commands_and_unvalidated_packages():
    service = FleetBatchService(lambda device_id: FakeBackend(device_id))
    with pytest.raises(ValueError, match="Unsupported"):
        service.submit(["dev_a"], "shell", {"command": "id"})
    with pytest.raises(ValueError, match="package"):
        service.submit(["dev_a"], "open_app", {"package": "com.example; rm"})


class EmptyFleet:
    def __init__(self):
        self.source_resolver = None
        self.video_factory = None

    def set_source_resolver(self, resolver): self.source_resolver = resolver
    def set_video_factory(self, factory): self.video_factory = factory
    def list_public(self): return []
    def diagnostics(self): return {"adbAvailable": False, "rawAdbDeviceCount": 0, "lastScanError": "test"}
    def start(self): return None
    def stop(self): return None


def test_fleet_and_virtual_http_contracts_are_authenticated_and_fail_closed(tmp_path, monkeypatch):
    empty_sdk = tmp_path / "empty-sdk"
    empty_sdk.mkdir()
    monkeypatch.setenv("ANDROID_SDK_ROOT", str(empty_sdk))
    monkeypatch.setenv("ANDROID_HOME", str(empty_sdk))
    monkeypatch.delenv("LOCALAPPDATA", raising=False)
    settings = Settings("pc-secret", None, "adb", tmp_path)
    runtime = DesktopRuntime(settings, fleet=EmptyFleet())
    client = TestClient(create_desktop_app(settings, runtime))
    headers = {"Authorization": "Bearer pc-secret"}

    assert client.get("/v1/virtual/providers").status_code == 401
    providers = client.get("/v1/virtual/providers", headers=headers)
    assert providers.status_code == 200
    assert providers.json()["providers"][0]["available"] is False
    create = client.post("/v1/virtual/instances", headers=headers, json={
        "provider": "android-emulator", "image": "system-images;android-35;google_apis;x86_64",
    })
    assert create.status_code == 503
    assert create.json()["detail"]["code"] == "PROVIDER_UNAVAILABLE"

    saved = client.post("/v1/fleet/groups/lab", headers=headers, json={"name": "Lab", "device_ids": ["dev_aaaaaaaa"]})
    assert saved.status_code == 200
    selected = client.post("/v1/fleet/selection", headers=headers, json={"device_ids": ["dev_aaaaaaaa"]})
    assert selected.json()["selectedDeviceIds"] == ["dev_aaaaaaaa"]
    workspace = client.get("/v1/fleet/workspace", headers=headers).json()
    assert workspace["groups"][0]["groupId"] == "lab"
