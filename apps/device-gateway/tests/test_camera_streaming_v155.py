from __future__ import annotations

from dataclasses import dataclass
import queue

import pytest

from cyclone_device_gateway.api import camera_stream_api as camera


class FakeAdb:
    def __init__(self, *, sdk: int = 35, version_code: int = 102):
        self.sdk = sdk
        self.version_code = version_code
        self.calls: list[tuple[str, ...]] = []

    def shell(self, *args: str, timeout: float = 15) -> str:
        if args[:2] == ("getprop", "ro.build.version.sdk"):
            return str(self.sdk)
        if args[:3] == ("dumpsys", "package", "com.cyclone.mobile"):
            return f"Packages:\n versionCode={self.version_code} minSdk=33 targetSdk=35\n"
        return ""

    def run(self, args, timeout=15):
        self.calls.append(tuple(args))
        return "Starting: Intent"


@dataclass
class FakeAdbDevice:
    state: str = "device"


class FakeDevice:
    def __init__(self, device_id: str, *, sdk: int = 35, version_code: int = 102, state: str = "device"):
        self.device_id = device_id
        self.adb_device = FakeAdbDevice(state)
        self.adb = FakeAdb(sdk=sdk, version_code=version_code)


class FakeFleet:
    def __init__(self, *devices: FakeDevice):
        self.devices = {item.device_id: item for item in devices}

    def get(self, device_id: str):
        if device_id not in self.devices:
            raise LookupError(device_id)
        return self.devices[device_id]


class FakeDiagnostics:
    def mark(self, *args, **kwargs):
        return None


class FakeRuntime:
    def __init__(self, *devices: FakeDevice):
        self.fleet = FakeFleet(*devices)
        self.live_diagnostics = FakeDiagnostics()


class FakeSession:
    next_id = 0

    def __init__(self, device, spec, diagnostic=None):
        type(self).next_id += 1
        self.device = device
        self.camera_spec = spec
        self.session_id = f"session-{type(self).next_id}"
        self.stopped = False
        self.started = False

    def start_capture(self):
        self.started = True

    def stop(self):
        self.stopped = True

    def status(self):
        return {
            "sessionId": self.session_id,
            "state": "WAITING_KEYFRAME" if self.started and not self.stopped else "STOPPED",
            "lastError": None,
            "preserveSourceAspect": True,
            "resolutionPolicy": self.camera_spec.resolution_policy,
        }

    def subscribe(self):
        return queue.Queue()

    def unsubscribe(self, subscriber):
        return None


class PartiallyFailingManager(camera.CameraStreamManager):
    def __init__(self, runtime, failed: set[str]):
        super().__init__(runtime)
        self.failed = failed

    def _launch_target(self, target, session_id, viewer_token, gateway_port):
        if target.device_id in self.failed:
            raise RuntimeError("viewer launch failed")


def body(**overrides) -> camera.CameraStartBody:
    data = {
        "source_device_id": "source",
        "target_device_ids": ["viewer-a"],
        "facing": "back",
        "quality": "high",
        "fps": 30,
    }
    data.update(overrides)
    return camera.CameraStartBody(**data)


def test_quality_modes_preserve_sensor_aspect_without_overclaiming_resolution():
    high = camera.CameraSpec.from_body(body())
    native = camera.CameraSpec.from_body(body(quality="native", fps=60))

    assert high.max_long_edge == 1920
    assert high.resolution_policy == "sensor-aspect-up-to-1920"
    assert high.bitrate_bps == 18_000_000

    assert native.max_long_edge is None
    assert native.resolution_policy == "sensor-greatest"
    assert native.bitrate_bps == 48_000_000


def test_source_phone_cannot_be_a_viewer():
    manager = camera.CameraStreamManager(FakeRuntime())
    with pytest.raises(ValueError, match="source phone cannot also be a viewer"):
        manager.start(body(target_device_ids=["source"]), 8765)


def test_source_requires_android_12_or_newer(monkeypatch):
    source = FakeDevice("source", sdk=30)
    viewer = FakeDevice("viewer-a")
    manager = camera.CameraStreamManager(FakeRuntime(source, viewer))
    monkeypatch.setattr(camera, "CameraScrcpySession", FakeSession)

    with pytest.raises(ValueError, match="Android 12 or newer"):
        manager.start(body(), 8765)


def test_receivers_require_mobile_442_or_newer_when_none_are_compatible(monkeypatch):
    source = FakeDevice("source")
    viewer = FakeDevice("viewer-a", version_code=101)
    manager = camera.CameraStreamManager(FakeRuntime(source, viewer))
    monkeypatch.setattr(camera, "CameraScrcpySession", FakeSession)

    with pytest.raises(ValueError, match="4.4.2 or newer"):
        manager.start(body(), 8765)


def test_incompatible_receiver_degrades_but_does_not_block_compatible_phone(monkeypatch):
    source = FakeDevice("source")
    good = FakeDevice("viewer-a", version_code=102)
    old = FakeDevice("viewer-b", version_code=101)
    manager = PartiallyFailingManager(FakeRuntime(source, good, old), set())
    monkeypatch.setattr(camera, "CameraScrcpySession", FakeSession)

    result = manager.start(body(target_device_ids=["viewer-a", "viewer-b"]), 8765)

    assert result["active"] is True
    assert result["degraded"] is True
    assert result["requestedViewerCount"] == 2
    assert result["targetDeviceIds"] == ["viewer-a"]
    assert result["launchFailures"][0]["deviceId"] == "viewer-b"
    assert "4.4.2 or newer" in result["launchFailures"][0]["error"]


def test_partial_viewer_launch_is_degraded_not_falsely_successful(monkeypatch):
    source = FakeDevice("source")
    viewer_a = FakeDevice("viewer-a")
    viewer_b = FakeDevice("viewer-b")
    runtime = FakeRuntime(source, viewer_a, viewer_b)
    manager = PartiallyFailingManager(runtime, {"viewer-b"})
    monkeypatch.setattr(camera, "CameraScrcpySession", FakeSession)

    result = manager.start(body(target_device_ids=["viewer-a", "viewer-b"]), 8765)

    assert result["active"] is True
    assert result["degraded"] is True
    assert result["requestedViewerCount"] == 2
    assert result["targetDeviceIds"] == ["viewer-a"]
    assert result["launchFailures"] == [{"deviceId": "viewer-b", "error": "viewer launch failed"}]


def test_zero_successful_viewers_fails_atomically(monkeypatch):
    source = FakeDevice("source")
    viewer = FakeDevice("viewer-a")
    manager = PartiallyFailingManager(FakeRuntime(source, viewer), {"viewer-a"})
    monkeypatch.setattr(camera, "CameraScrcpySession", FakeSession)

    with pytest.raises(ValueError, match="No receiving phone could start"):
        manager.start(body(), 8765)
    assert manager.status()["active"] is False


def test_viewer_tokens_are_target_scoped_and_unpredictable(monkeypatch):
    source = FakeDevice("source")
    viewer_a = FakeDevice("viewer-a")
    viewer_b = FakeDevice("viewer-b")
    manager = PartiallyFailingManager(FakeRuntime(source, viewer_a, viewer_b), set())
    monkeypatch.setattr(camera, "CameraScrcpySession", FakeSession)
    manager.start(body(target_device_ids=["viewer-a", "viewer-b"]), 8765)

    tokens = dict(manager._target_tokens)
    assert len(tokens["viewer-a"]) >= 24
    assert tokens["viewer-a"] != tokens["viewer-b"]

    with pytest.raises(PermissionError):
        manager.subscribe(manager._session.session_id, "viewer-a", tokens["viewer-b"])


def test_viewer_credentials_never_enter_websocket_url_or_logs():
    target = FakeDevice("viewer-a")
    manager = camera.CameraStreamManager(FakeRuntime(target))
    token = "secure_viewer_token_1234567890"

    manager._launch_target(target, "session-abc", token, 8765)

    launch = target.adb.calls[-1]
    url_index = launch.index("cyclone_stream_url") + 1
    url = launch[url_index]
    assert url == "ws://127.0.0.1:17881/v1/camera-stream/ws/session-abc"
    assert "?" not in url
    assert token not in url
    assert "cyclone_stream_token" in launch
    assert token in launch
    assert "cyclone_stream_target" in launch
    assert "viewer-a" in launch
