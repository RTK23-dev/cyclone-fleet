from __future__ import annotations

import json

from pathlib import Path
import time
import zipfile

import pytest
from fastapi.testclient import TestClient

from cyclone_device_gateway.adb.client import ADBDevice, ADBError
from cyclone_device_gateway.config import Settings
from cyclone_device_gateway.cyclone_bridge.client import BridgeDisconnectedError
from cyclone_device_gateway.desktop_runtime.api import DesktopRuntime, create_desktop_app
from cyclone_device_gateway.desktop_runtime.agent import DesktopAgentService
from cyclone_device_gateway.desktop_runtime.controls import MANUAL_KINDS, ClipboardService, ManualControlService, clipboard_looks_sensitive
from cyclone_device_gateway.desktop_runtime.fleet import DeviceFleetManager
from cyclone_device_gateway.desktop_runtime.models import DesktopRuntimeError, DeviceFleetState, VIDEO_PROFILES, deterministic_device_id
from cyclone_device_gateway.desktop_runtime.pairing import PairingCoordinator
from cyclone_device_gateway.desktop_runtime.video import VideoFleetLimiter, VideoStreamController
from cyclone_device_gateway.cyclone_bridge.protocol import ALLOWED_OPS
from cyclone_device_gateway.media.backend import ScrcpyMediaBackend


def unavailable_media_backend() -> ScrcpyMediaBackend:
    return ScrcpyMediaBackend(artifact_path=Path(__file__).with_name("missing-scrcpy-server"))


class Inventory:
    def __init__(self, devices):
        self.current = list(devices)

    def devices(self):
        return list(self.current)


class FakeDeviceADB:
    def __init__(self, serial, *, fail=False):
        self.serial = serial
        self.fail = fail
        self.forwards = []
        self.removed = []
        self.shell_calls = []
        self.process_calls = []

    def shell(self, *args, timeout=15):
        self.shell_calls.append(args)
        if self.fail:
            raise ADBError("offline")
        if args[:2] == ("pm", "path"):
            return "package:/data/app/com.cyclone.mobile/base.apk\n"
        if args[:2] == ("dumpsys", "power"):
            return "mWakefulness=Awake\nDisplay Power: state=ON\n"
        if args[:2] == ("wm", "size"):
            return "Physical size: 1080x2400\n"
        return ""

    def ensure_bridge_forward(self, port):
        self.forwards.append(port)
        return True

    def remove_forward(self, port):
        self.removed.append(port)

    def exec_out(self, *args, timeout=15):
        return b"png-not-used-in-sleep-test"

    def start_process(self, args, stdout=None):
        self.process_calls.append(args)
        raise RuntimeError("h264 unavailable in unit test")

    def collect_cyclone_crash_diagnostics(self):
        return {
            "pid": "",
            "exit_info": "ApplicationExitInfo reason=REASON_CRASH lastStage=gateway.pair.complete.returning",
            "crash_logcat": "FATAL EXCEPTION: cyclone-test",
            "enabled_accessibility_services": "com.cyclone.mobile/.CycloneAccessibilityService",
            "accessibility_enabled": "1",
        }


class FakeBridge:
    def __init__(self, credentials=None, app_version=None):
        self.credentials = list(credentials or ["A" * 43, "B" * 43, "C" * 43])
        self.app_version = app_version
        self.challenge = 0
        self.calls = []
        self.qr_approved = False
        self.manual_result = {"ok": True, "status": "DONE"}

    def request_unauthenticated(self, op, args=None, request_id=None):
        self.calls.append((op, args or {}))
        if op == "pair.begin":
            self.challenge += 1
            return {"challengeId": f"ch-{self.challenge}", "expiresAtMs": int(time.time() * 1000) + 60_000}
        if op == "pair.complete":
            return {"credential": self.credentials.pop(0), "paired": True}
        if op == "pair.qr.complete":
            if not self.qr_approved:
                return {"paired": False, "pending": True}
            return {"credential": self.credentials.pop(0), "paired": True}
        raise AssertionError(op)

    def request(self, op, args=None, request_id=None):
        self.calls.append((op, args or {}))
        if op == "bridge.status":
            status = {"gatewayEnabled": True, "socketListening": True, "accessibilityConnected": True}
            if self.app_version is not None:
                status["appVersion"] = self.app_version
            return status
        if op == "pair.revoke":
            return {"revoked": True}
        if op == "manual.execute":
            return self.manual_result
        if op == "clipboard.get":
            return {"mode": "PC_TO_PHONE", "reverseSync": "UNAVAILABLE"}
        if op == "clipboard.set":
            return {"updated": True}
        raise AssertionError(op)


class FlakyBeginBridge(FakeBridge):
    def __init__(self):
        super().__init__()
        self.fail_begin_once = True

    def request_unauthenticated(self, op, args=None, request_id=None):
        if op == "pair.begin" and self.fail_begin_once:
            self.fail_begin_once = False
            self.calls.append((op, args or {}))
            raise BridgeDisconnectedError("simulated transient USB race")
        return super().request_unauthenticated(op, args, request_id)


class ClockSkewBeginBridge(FakeBridge):
    def __init__(self, skew_ms, include_relative_lifetime=True, relative_lifetime_ms=60_000):
        super().__init__()
        self.skew_ms = skew_ms
        self.include_relative_lifetime = include_relative_lifetime
        self.relative_lifetime_ms = relative_lifetime_ms

    def request_unauthenticated(self, op, args=None, request_id=None):
        if op != "pair.begin":
            return super().request_unauthenticated(op, args, request_id)
        self.calls.append((op, args or {}))
        self.challenge += 1
        result = {
            "challengeId": f"skew-{self.challenge}",
            "expiresAtMs": int(time.time() * 1000) + 60_000 + self.skew_ms,
        }
        if self.include_relative_lifetime:
            result["expiresInMs"] = self.relative_lifetime_ms
        return result


class DiesAfterPairBridge(FakeBridge):
    def request(self, op, args=None, request_id=None):
        self.calls.append((op, args or {}))
        if op == "bridge.status":
            raise BridgeDisconnectedError("simulated phone process death after pair.complete")
        return super().request(op, args, request_id)


def make_fleet(devices, failures=()):
    inventory = Inventory(devices)
    adbs = {}

    def factory(serial):
        adbs.setdefault(serial, FakeDeviceADB(serial, fail=serial in failures))
        return adbs[serial]

    fleet = DeviceFleetManager(
        inventory_adb=inventory,
        adb_factory=factory,
        poll_seconds=.2,
        max_devices=16,
        max_workers=8,
    )
    return fleet, inventory, adbs


def install_fake_bridges(fleet, bridge_by_id=None):
    bridge_by_id = bridge_by_id or {}
    for item in fleet.list_public():
        session = fleet.get(item["deviceId"])
        bridge = bridge_by_id.setdefault(item["deviceId"], FakeBridge())
        session.bridge = lambda token=None, auto_forward=False, b=bridge: b
    return bridge_by_id


def paired_session_for_services():
    fleet, _, _ = make_fleet([ADBDevice("A", "device")])
    fleet.refresh_once()
    session = fleet.get(deterministic_device_id("A"))
    session.credential = "Z" * 43
    bridge = FakeBridge()
    session.bridge = lambda token=None, auto_forward=False: bridge
    return fleet, session, bridge


def test_multi_device_discovery_authorized_unauthorized_and_isolated_forwards():
    devices = [
        ADBDevice("SERIAL-A-1234", "device", "Pixel_8"),
        ADBDevice("SERIAL-B-5678", "unauthorized", "Pixel_7"),
        ADBDevice("SERIAL-C-9012", "device", "Pixel_6"),
    ]
    fleet, _, adbs = make_fleet(devices)
    fleet.refresh_once()
    public = fleet.list_public()
    assert len(public) == 3
    assert {p["state"] for p in public} == {"UNPAIRED", "UNAUTHORIZED"}
    a = fleet.get(deterministic_device_id("SERIAL-A-1234"))
    c = fleet.get(deterministic_device_id("SERIAL-C-9012"))
    assert a.local_port != c.local_port
    assert adbs["SERIAL-A-1234"].forwards == [a.local_port]
    assert adbs["SERIAL-C-9012"].forwards == [c.local_port]
    assert "SERIAL-A-1234" not in str(a.public())


def test_reconnect_reuses_device_id_and_credential_and_cleans_only_removed_device():
    fleet, inventory, adbs = make_fleet([ADBDevice("A", "device"), ADBDevice("B", "device")])
    fleet.refresh_once()
    install_fake_bridges(fleet)
    a = fleet.get(deterministic_device_id("A"))
    b = fleet.get(deterministic_device_id("B"))
    fleet.remember_credential(a, "X" * 43)
    a_port, b_port = a.local_port, b.local_port
    inventory.current = [ADBDevice("B", "device")]
    fleet.refresh_once()
    assert adbs["A"].removed == [a_port]
    assert b_port not in adbs["A"].removed
    offline = {item["deviceId"]: item for item in fleet.list_public()}[a.device_id]
    assert offline["state"] == "DISCONNECTED"
    assert offline["paired"] is True
    assert offline["connectionHealth"]["errorClass"] == "ADB_DISCONNECTED"
    inventory.current = [ADBDevice("A", "device"), ADBDevice("B", "device")]
    fleet.refresh_once()
    install_fake_bridges(fleet)
    a2 = fleet.get(deterministic_device_id("A"))
    assert a2.device_id == a.device_id
    assert a2.local_port == a_port
    assert a2.credential == "X" * 43


def test_one_device_failure_isolation():
    fleet, _, _ = make_fleet(
        [ADBDevice("GOOD", "device"), ADBDevice("BAD", "device")],
        failures={"BAD"},
    )
    fleet.refresh_once()
    assert fleet.get(deterministic_device_id("BAD")).state == DeviceFleetState.ATTENTION
    assert fleet.get(deterministic_device_id("GOOD")).state == DeviceFleetState.UNPAIRED


def test_paired_health_refresh_sends_authenticated_phone_heartbeat():
    fleet, _, _ = make_fleet([ADBDevice("HEARTBEAT", "device")])
    fleet.refresh_once()
    bridges = install_fake_bridges(fleet)
    session = fleet.get(deterministic_device_id("HEARTBEAT"))
    fleet.remember_credential(session, "H" * 43)

    fleet.refresh_once()

    assert session.state == DeviceFleetState.READY
    assert [op for op, _ in bridges[session.device_id].calls].count("bridge.status") == 1


def test_real_fleet_exposes_only_authenticated_mobile_version_and_rechecks_it(tmp_path):
    fleet, _, _ = make_fleet([ADBDevice("VERSION-PIXEL", "device", "Pixel_8")])
    fleet.refresh_once()
    session = fleet.get(deterministic_device_id("VERSION-PIXEL"))
    bridge = FakeBridge(app_version="5.0.0-alpha.2.dev2")
    session.bridge = lambda token=None, auto_forward=False: bridge
    settings = Settings("pc-secret", None, "adb", tmp_path)
    runtime = DesktopRuntime(settings, fleet=fleet)
    with TestClient(create_desktop_app(settings, runtime)) as client:
        headers = {"Authorization": "Bearer pc-secret"}

        def public_device():
            response = client.get("/v1/fleet", headers=headers)
            assert response.status_code == 200
            return response.json()["devices"][0]

        # A connected phone without authenticated bridge status cannot be assumed V5.
        assert "mobileVersion" not in public_device()
        fleet.remember_credential(session, "H" * 43)
        fleet.refresh_once()
        assert public_device()["mobileVersion"] == "5.0.0-alpha.2.dev2"

        bridge.app_version = "4.8.0"
        fleet.refresh_once()
        assert public_device()["mobileVersion"] == "4.8.0"

        # Unexpected or missing phone status must not retain a stale V5 claim.
        bridge.app_version = "5-not-a-version"
        fleet.refresh_once()
        assert "mobileVersion" not in public_device()
        bridge.app_version = None
        fleet.refresh_once()
        assert "mobileVersion" not in public_device()

        bridge.app_version = "5.0.0-alpha.2.dev2"
        fleet.refresh_once()
        assert public_device()["mobileVersion"] == "5.0.0-alpha.2.dev2"
        original_request = bridge.request

        def offline(*args, **kwargs):
            raise BridgeDisconnectedError("simulated offline phone")

        bridge.request = offline
        fleet.refresh_once()
        assert "mobileVersion" not in public_device()
        bridge.request = original_request
        fleet.refresh_once()
        assert public_device()["mobileVersion"] == "5.0.0-alpha.2.dev2"
        # Trust restore re-confirms the same session token; that must not hide the version (Glass "Waiting for Cyclone").
        fleet.remember_credential(session, "H" * 43)
        assert public_device()["mobileVersion"] == "5.0.0-alpha.2.dev2"
        fleet.remember_credential(session, "N" * 43)
        assert "mobileVersion" not in public_device()
        fleet.refresh_once()
        assert public_device()["mobileVersion"] == "5.0.0-alpha.2.dev2"
        fleet.remember_credential(session, None)
        assert "mobileVersion" not in public_device()


def test_pairing_timeout_replay_attempt_limit_and_token_rotation():
    fleet, session, bridge = paired_session_for_services()
    bridge.app_version = "5.0.0-alpha.2.dev2"
    session.credential = None
    pairing = PairingCoordinator(fleet)
    pairing.POST_PAIR_HEALTH_DELAY_SECONDS = 0
    begin = pairing.begin(session.device_id)
    assert begin["pairing"] is True and "credential" not in begin
    session.pending_pairing.expires_at_ms = int(time.time() * 1000) - 1
    with pytest.raises(DesktopRuntimeError) as err:
        pairing.complete(session.device_id, begin["pairingId"], "NOVA")
    assert err.value.code == "PAIRING_EXPIRED"

    begin = pairing.begin(session.device_id)
    for _ in range(4):
        with pytest.raises(DesktopRuntimeError) as err:
            pairing.complete(session.device_id, begin["pairingId"], "bad")
        assert err.value.code == "PAIRING_CODE_REJECTED"
    with pytest.raises(DesktopRuntimeError) as err:
        pairing.complete(session.device_id, begin["pairingId"], "bad")
    assert err.value.code == "PAIRING_ATTEMPTS_EXCEEDED"

    begin = pairing.begin(session.device_id)
    first = pairing.complete(session.device_id, begin["pairingId"], "NOVA")
    first_token = session.credential
    assert first["paired"] is True and first["gatewayHealthy"] is True and "credential" not in first
    assert first["device"]["mobileVersion"] == "5.0.0-alpha.2.dev2"
    assert [op for op, _ in bridge.calls].count("bridge.status") >= 2
    with pytest.raises(DesktopRuntimeError) as err:
        pairing.complete(session.device_id, begin["pairingId"], "NOVA")
    assert err.value.code == "PAIRING_REPLAY"
    begin = pairing.begin(session.device_id)
    pairing.complete(session.device_id, begin["pairingId"], "NOVA")
    assert session.credential != first_token


def test_pair_begin_repairs_forward_and_recovers_one_transient_transport_drop():
    fleet, session, _ = paired_session_for_services()
    session.credential = None
    bridge = FlakyBeginBridge()
    session.bridge = lambda token=None, auto_forward=False: bridge
    pairing = PairingCoordinator(fleet)
    pairing.BEGIN_RETRY_DELAY_SECONDS = 0

    result = pairing.begin(session.device_id)

    assert result["pairing"] is True
    assert [op for op, _ in bridge.calls].count("pair.begin") == 2
    assert session.adb.forwards.count(session.local_port) >= 2


@pytest.mark.parametrize("skew_ms", [-120_000, -2_000, 2_000, 120_000])
def test_pair_begin_uses_bounded_relative_lifetime_instead_of_cross_device_epoch(skew_ms):
    fleet, session, _ = paired_session_for_services()
    session.credential = None
    bridge = ClockSkewBeginBridge(skew_ms)
    session.bridge = lambda token=None, auto_forward=False: bridge
    pairing = PairingCoordinator(fleet)

    before = int(time.time() * 1000)
    result = pairing.begin(session.device_id)
    after = int(time.time() * 1000)

    assert result["pairing"] is True
    assert before + 60_000 <= result["expiresAtEpochMs"] <= after + 60_000
    assert session.pending_pairing.expires_at_ms == result["expiresAtEpochMs"]


def test_pair_begin_keeps_legacy_mobile_compatible_when_phone_clock_is_ahead():
    fleet, session, _ = paired_session_for_services()
    session.credential = None
    bridge = ClockSkewBeginBridge(2_000, include_relative_lifetime=False)
    session.bridge = lambda token=None, auto_forward=False: bridge

    result = PairingCoordinator(fleet).begin(session.device_id)

    assert result["pairing"] is True
    assert result["qrAvailable"] is True


def test_pair_begin_rejects_unbounded_relative_lifetime():
    fleet, session, _ = paired_session_for_services()
    session.credential = None
    bridge = ClockSkewBeginBridge(0, relative_lifetime_ms=60_001)
    session.bridge = lambda token=None, auto_forward=False: bridge
    pairing = PairingCoordinator(fleet)

    with pytest.raises(DesktopRuntimeError) as error:
        pairing.begin(session.device_id)

    assert error.value.code == "CAPABILITY_UNAVAILABLE"


def test_pair_complete_never_marks_ready_until_phone_survives_health_probe_and_writes_diagnostics(tmp_path):
    fleet, session, _ = paired_session_for_services()
    session.credential = None
    bridge = DiesAfterPairBridge()
    session.bridge = lambda token=None, auto_forward=False: bridge
    pairing = PairingCoordinator(fleet)
    pairing.POST_PAIR_HEALTH_DELAY_SECONDS = 0
    pairing.diagnostics_dir = tmp_path
    begin = pairing.begin(session.device_id)

    with pytest.raises(DesktopRuntimeError) as err:
        pairing.complete(session.device_id, begin["pairingId"], "NOVA")

    assert err.value.code == "DEVICE_DISCONNECTED"
    assert session.credential is None
    assert session.state == DeviceFleetState.UNPAIRED
    logs = list(tmp_path.glob("pairing-*.json"))
    assert len(logs) == 1
    text = logs[0].read_text(encoding="utf-8")
    assert "pair.complete.post_health" in text
    assert "FATAL EXCEPTION: cyclone-test" in text
    assert "Diagnostic file:" in err.value.safe_message


def test_pairing_confirmation_is_bound_to_latest_challenge_without_consuming_it():
    fleet, session, _ = paired_session_for_services()
    session.credential = None
    pairing = PairingCoordinator(fleet)
    pairing.POST_PAIR_HEALTH_DELAY_SECONDS = 0
    first = pairing.begin(session.device_id)
    latest = pairing.begin(session.device_id)

    with pytest.raises(DesktopRuntimeError) as err:
        pairing.complete(session.device_id, first["pairingId"], "NOVA")
    assert err.value.code == "PAIRING_REPLAY"
    assert session.pending_pairing.challenge_id == latest["pairingId"]
    assert session.pending_pairing.attempts == 0

    assert pairing.complete(session.device_id, latest["pairingId"], "NOVA")["paired"] is True


def test_qr_pairing_is_one_time_pending_until_phone_scan_approval():
    fleet, session, bridge = paired_session_for_services()
    session.credential = None
    pairing = PairingCoordinator(fleet)
    pairing.POST_PAIR_HEALTH_DELAY_SECONDS = 0
    begin = pairing.begin(session.device_id)

    assert begin["qrAvailable"] is True
    assert begin["qrPayload"].startswith("cyclone://pair?challenge=")
    assert "credential" not in begin["qrPayload"]
    assert pairing.complete_qr(session.device_id, begin["pairingId"]) == {
        "deviceId": session.device_id,
        "paired": False,
        "pending": True,
    }
    assert session.credential is None

    bridge.qr_approved = True
    completed = pairing.complete_qr(session.device_id, begin["pairingId"])
    assert completed["paired"] is True
    assert session.credential is not None
    with pytest.raises(DesktopRuntimeError) as err:
        pairing.complete_qr(session.device_id, begin["pairingId"])
    assert err.value.code == "PAIRING_REPLAY"


def test_manual_control_routes_explicit_device_and_never_echoes_keyboard_text():
    fleet, session, bridge = paired_session_for_services()
    service = ManualControlService(fleet)
    assert service.execute(session.device_id, {"kind": "tap", "x": .25, "y": .75})["kind"] == "tap"
    assert service.execute(session.device_id, {"kind": "swipe", "x1": .5, "y1": .8, "x2": .5, "y2": .2, "duration_ms": 280})["kind"] == "swipe"
    assert bridge.calls[-1][1] == {"kind": "swipe", "source": "HUMAN_DESKTOP", "x1": .5, "y1": .8, "x2": .5, "y2": .2, "durationMs": 280}
    result = service.execute(session.device_id, {"kind": "text", "text": "ordinary words"})
    assert "ordinary words" not in str(result)
    assert bridge.calls[-1][0] == "manual.execute"
    assert bridge.calls[-1][1]["text"] == "ordinary words"
    with pytest.raises(DesktopRuntimeError):
        service.execute(session.device_id, {"kind": "shell"})


def test_manual_control_reports_android_execution_instead_of_transport_success():
    fleet, session, bridge = paired_session_for_services()
    service = ManualControlService(fleet)
    bridge.manual_result = {
        "transport": {"ok": True},
        "androidExecution": {"ok": False, "errorCode": "HUMAN_HAS_CONTROL"},
    }
    refused = service.execute(session.device_id, {"kind": "tap", "x": .5, "y": .5})
    assert refused["ok"] is False
    assert refused["status"] == "HUMAN_HAS_CONTROL"

    bridge.manual_result = {"androidExecution": {"ok": True}, "verification": {"status": "OBSERVED"}}
    accepted = service.execute(session.device_id, {"kind": "tap", "x": .5, "y": .5})
    assert accepted["ok"] is True


def test_opening_paired_phone_uses_only_fixed_wake_event_and_marks_ready():
    fleet, session, bridge = paired_session_for_services()
    session.screen_awake = False
    session.state = DeviceFleetState.SLEEPING

    result = ManualControlService(fleet).execute(session.device_id, {"kind": "wake"})

    assert result["ok"] is True
    assert result["status"] == "DISPLAY_WAKE_REQUESTED"
    assert session.screen_awake is True
    assert session.state == DeviceFleetState.READY
    assert ("input", "keyevent", "224") in session.adb.shell_calls
    assert all(op != "manual.execute" for op, _ in bridge.calls)
    assert [op for op, _ in bridge.calls].count("bridge.status") == 1


def test_clipboard_is_pc_to_phone_and_sensitive_values_are_rejected_without_echo():
    fleet, session, _ = paired_session_for_services()
    service = ClipboardService(fleet)
    assert service.capability(session.device_id)["reverseSync"] == "UNAVAILABLE"
    assert service.set(session.device_id, "hello desktop")["updated"] is True
    assert clipboard_looks_sensitive("OTP: 123456") is True
    assert clipboard_looks_sensitive("eyJabcde123.eyJdefgh456.signature789") is True
    with pytest.raises(DesktopRuntimeError) as err:
        service.set(session.device_id, "OTP: 123456")
    assert "123456" not in str(err.value)


def test_video_profiles_are_bounded_thumbnail_cheaper_and_sleeping_stream_pauses():
    assert VIDEO_PROFILES["thumbnail"].max_long_edge <= 540
    assert VIDEO_PROFILES["thumbnail"].target_fps == 8
    assert VIDEO_PROFILES["thumbnail"].cpu_weight < VIDEO_PROFILES["focus"].cpu_weight
    assert VIDEO_PROFILES["focus"].max_long_edge == 1920
    assert VIDEO_PROFILES["focus"].target_fps == 30
    fleet, session, _ = paired_session_for_services()
    session.screen_awake = False
    limiter = VideoFleetLimiter(max_sources=12, max_focus=2)
    controller = VideoStreamController(session, limiter, media_backend=unavailable_media_backend())
    assert controller.subscriber_count() == 0
    assert limiter.snapshot()["sources"] == 0
    q = controller.subscribe("thumbnail")
    init = q.get(timeout=2)
    sleeping = q.get(timeout=2)
    assert "stream.init" in init.data
    assert "SLEEPING" in sleeping.data
    controller.unsubscribe("thumbnail", q)
    time.sleep(.1)
    controller.stop_all()


def test_focus_stream_uses_bounded_image_fallback_when_scrcpy_is_unavailable():
    fleet, session, _ = paired_session_for_services()
    session.screen_awake = True
    session.adb.exec_out = lambda *args, **kwargs: (
        b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01"
        b"\x08\x06\x00\x00\x00\x1f\x15\xc4\x89\x00\x00\x00\rIDAT\x08\xd7c\xf8\xcf\xc0\xf0\x1f\x00\x05\x00\x01\xff\x89\x99"
        b"=\x1d\x00\x00\x00\x00IEND\xaeB`\x82"
    )
    controller = VideoStreamController(session, VideoFleetLimiter(), media_backend=unavailable_media_backend())

    q = controller.subscribe("focus")
    init = q.get(timeout=2)
    frame = q.get(timeout=2)

    assert init.kind == "text"
    assert '"codec":"image/' in init.data
    assert '"backend":"adb-screenshot"' in init.data
    assert frame.kind == "binary"
    assert session.adb.process_calls == []
    controller.unsubscribe("focus", q)
    controller.stop_all()


def test_video_reports_bounded_capture_failure_instead_of_silent_infinite_wait():
    fleet, session, _ = paired_session_for_services()
    session.screen_awake = True

    def fail_capture(*args, **kwargs):
        raise ADBError("capture unavailable")

    session.adb.exec_out = fail_capture
    events = []
    controller = VideoStreamController(
        session,
        VideoFleetLimiter(),
        diagnostic=lambda stage, details: events.append((stage, details)),
        media_backend=unavailable_media_backend(),
    )
    q = controller.subscribe("thumbnail")
    assert "stream.init" in q.get(timeout=2).data
    failure = q.get(timeout=3)
    assert failure.kind == "text"
    assert "FRAME_CAPTURE_FAILED" in failure.data
    assert any(stage == "server.frame.capture_exhausted" for stage, _ in events)
    assert controller.diagnostics()["failuresByProfile"]["thumbnail"] >= 3
    controller.unsubscribe("thumbnail", q)
    controller.stop_all()


def test_companion_input_ownership_blocks_mcp_until_yield_and_never_steals_locked_phone():
    fleet, session, _bridge = paired_session_for_services()
    controls = ManualControlService(fleet)
    agent = DesktopAgentService(fleet)
    assert session.input_owner == "HUMAN"
    with pytest.raises(DesktopRuntimeError) as blocked:
        agent._require_ai_ownership(session, False)
    assert blocked.value.code == "HUMAN_HAS_CONTROL"
    session.screen_awake = False
    with pytest.raises(DesktopRuntimeError) as locked:
        agent._require_ai_ownership(session, True)
    assert locked.value.code == "PHONE_LOCKED"
    session.screen_awake = True
    agent._require_ai_ownership(session, True)
    assert session.input_owner == "AI"
    yielded = controls.execute(session.device_id, {"kind": "yield_ai"})
    assert yielded["ok"] is True
    assert yielded["inputOwner"] == "AI"
    taken = controls.execute(session.device_id, {"kind": "take_human"})
    assert taken["inputOwner"] == "HUMAN"
    controls.execute(session.device_id, {"kind": "tap", "x": .2, "y": .3})
    assert session.input_owner == "HUMAN"
    status = agent.status(session.device_id)
    assert status["inputOwner"] == "HUMAN"
    assert status["sessions"][0]["sessionId"] == "default-foreground"
    assert status["sessions"][0]["kind"] == "FOREGROUND"
    assert status["sessions"][0]["executable"] is True


def test_http_agent_action_blocked_while_human_owns_input_and_request_ai_control_yields(tmp_path):
    fleet, session, bridge = paired_session_for_services()
    settings = Settings("pc-secret", None, "adb", tmp_path)
    runtime = DesktopRuntime(settings, fleet=fleet)
    app = create_desktop_app(settings, runtime)
    headers = {"Authorization": "Bearer pc-secret"}
    with TestClient(app) as client:
        blocked = client.post(
            f"/v1/devices/{session.device_id}/agent/action",
            headers=headers,
            json={"capability_id": "phone.home", "expected_observation_id": "obs-1"},
        )
        assert blocked.status_code == 409
        assert blocked.json()["detail"]["code"] == "HUMAN_HAS_CONTROL"
        session.screen_awake = False
        locked = client.post(
            f"/v1/devices/{session.device_id}/agent/action",
            headers=headers,
            json={
                "capability_id": "phone.home",
                "expected_observation_id": "obs-1",
                "request_ai_control": True,
            },
        )
        assert locked.status_code == 423
        assert locked.json()["detail"]["code"] == "PHONE_LOCKED"
        session.screen_awake = True

        def action_execute(op, args=None, request_id=None):
            bridge.calls.append((op, args or {}))
            if op == "bridge.status":
                return {"gatewayEnabled": True, "socketListening": True, "accessibilityConnected": True}
            if op == "observe.semantic":
                return {
                    "observationId": "obs-after",
                    "pageKey": "HOME",
                    "package": "com.android.launcher3",
                    "activity": "Home",
                    "accessibilityFingerprint": "fp",
                    "pageText": {"protocol": "cyclone-page-text-v1", "lines": [{"text": "Home"}]},
                    "pageSummary": {"protocol": "cyclone-page-summary-v1", "title": "Home"},
                }
            if op == "action.execute":
                return {
                    "execution": {"ok": True},
                    "androidExecution": {"ok": True},
                    "verification": {"ok": True, "status": "PASSED", "semanticSuccessClaimed": True},
                }
            raise AssertionError(op)

        bridge.request = action_execute
        yielded = client.post(
            f"/v1/devices/{session.device_id}/agent/action",
            headers=headers,
            json={
                "capability_id": "phone.home",
                "expected_observation_id": "obs-1",
                "request_ai_control": True,
            },
        )
        assert yielded.status_code == 200
        assert session.input_owner == "AI"


def test_no_generic_shell_or_arbitrary_adb_surface():
    forbidden = ("shell", "powershell", "root", "su", "command", "script", "adb")
    assert MANUAL_KINDS == {
        "tap", "swipe", "back", "home", "scroll_up", "scroll_down", "text", "wake",
        "yield_ai", "take_human",
    }
    assert all(not any(word in op.lower() for word in forbidden) for op in ALLOWED_OPS)


@pytest.mark.parametrize("count", [1, 2, 4, 8, 12])
def test_fleet_performance_shape_is_bounded(count):
    devices = [ADBDevice(f"SERIAL-{i}", "device") for i in range(count)]
    fleet, _, adbs = make_fleet(devices)
    started = time.perf_counter()
    fleet.refresh_once()
    elapsed = time.perf_counter() - started
    sessions = [fleet.get(deterministic_device_id(d.serial)) for d in devices]
    assert len({s.local_port for s in sessions}) == count
    assert fleet.max_workers <= 8
    assert all(len(adbs[d.serial].forwards) == 1 for d in devices)
    assert elapsed < 2.0


def test_frozen_http_and_websocket_routes_are_authenticated(tmp_path):
    fleet, _, _ = make_fleet([ADBDevice("SERIAL-API-1234", "device")])
    fleet.refresh_once()
    install_fake_bridges(fleet)
    settings = Settings("pc-secret", None, "adb", tmp_path)
    runtime = DesktopRuntime(settings, fleet=fleet)
    for public in fleet.list_public():
        session = fleet.get(public["deviceId"])
        session.video = VideoStreamController(
            session,
            runtime.video_limiter,
            media_backend=unavailable_media_backend(),
        )
    app = create_desktop_app(settings, runtime)
    with TestClient(app) as client:
        assert client.get("/v1/fleet").status_code == 401
        headers = {"Authorization": "Bearer pc-secret"}
        response = client.get("/v1/fleet", headers=headers)
        assert response.status_code == 200
        assert len(response.json()["devices"]) == 1
        device_id = response.json()["devices"][0]["deviceId"]
        begin = client.post(f"/v1/devices/{device_id}/pair/begin", headers=headers)
        assert begin.status_code == 200
        qr_pending = client.post(
            f"/v1/devices/{device_id}/pair/qr/complete",
            headers=headers,
            json={"pairing_id": begin.json()["pairingId"]},
        )
        assert qr_pending.status_code == 200
        assert qr_pending.json()["pending"] is True
        assert client.post(
            f"/v1/devices/{device_id}/pair/complete",
            headers=headers,
            json={"code": "NOVA"},
        ).status_code == 422
        complete = client.post(
            f"/v1/devices/{device_id}/pair/complete",
            headers=headers,
            json={"pairing_id": begin.json()["pairingId"], "code": "nova"},
        )
        assert complete.status_code == 200
        assert complete.json()["paired"] is True
        with client.websocket_connect(f"/v1/devices/{device_id}/video?profile=thumbnail", headers=headers) as video:
            assert "stream.init" in video.receive_text()
        assert client.post(
            f"/v1/devices/{device_id}/control",
            headers=headers,
            json={"kind": "shell"},
        ).status_code == 422
        with client.websocket_connect("/v1/fleet/events", headers=headers) as websocket:
            assert websocket.receive_json()["event"] == "FLEET_SNAPSHOT"


def test_live_view_heals_itself_when_usb_is_not_ready_yet(tmp_path):
    fleet, _, _ = make_fleet([ADBDevice("SERIAL-USB-9876", "device")])
    fleet.refresh_once()
    install_fake_bridges(fleet)
    settings = Settings("pc-secret", None, "adb", tmp_path)
    runtime = DesktopRuntime(settings, fleet=fleet)
    device_id = fleet.list_public()[0]["deviceId"]
    session = fleet.get(device_id)
    session.video = VideoStreamController(session, runtime.video_limiter, media_backend=unavailable_media_backend())
    app = create_desktop_app(settings, runtime)
    headers = {"Authorization": "Bearer pc-secret"}
    with TestClient(app) as client:
        fleet.stop() if hasattr(fleet, "stop") else None
        session.adb_device = ADBDevice("SERIAL-USB-9876", "unauthorized")
        with client.websocket_connect(f"/v1/devices/{device_id}/video?profile=thumbnail", headers=headers) as video:
            first = json.loads(video.receive_text())
            assert first == {"type": "stream.error", "code": "USB_UNAUTHORIZED", "retryable": True}
            assert "stream.init" in video.receive_text(), "the producer keeps trying instead of closing the door"


def test_closed_browser_releases_subscription_while_video_producer_is_silent(tmp_path):
    from cyclone_device_gateway.desktop_runtime.video import StreamMessage
    fleet, _, _ = make_fleet([ADBDevice("SERIAL-IDLE-1234", "device")])
    fleet.refresh_once()
    install_fake_bridges(fleet)
    settings = Settings("pc-secret", None, "adb", tmp_path)
    runtime = DesktopRuntime(settings, fleet=fleet)
    device_id = fleet.list_public()[0]["deviceId"]
    session = fleet.get(device_id)
    controller = VideoStreamController(session, runtime.video_limiter, jpeg_first=True)
    def silent_producer(profile, stop):
        controller._broadcast(profile, StreamMessage("text", '{"type":"stream.init"}'))
        stop.wait(5)
    controller._produce_jpeg = silent_producer
    session.video = controller
    with TestClient(create_desktop_app(settings, runtime)) as client:
        session.input_owner = "AI"
        with client.websocket_connect(f"/v1/devices/{device_id}/video?profile=focus",
                headers={"Authorization": "Bearer pc-secret"}) as video:
            assert video.receive_json()["type"] == "stream.init"
            assert controller.subscriber_count() == 1
            assert session.input_owner == "AI", "watching a focus stream must not take input"
            video.close()
            deadline = time.monotonic() + 2.5
            while controller.subscriber_count() and time.monotonic() < deadline:
                time.sleep(0.01)
            assert controller.subscriber_count() == 0


def test_connection_debug_flow_records_client_server_timeline_and_creates_sendable_zip(tmp_path):
    fleet, session, _ = paired_session_for_services()
    settings = Settings("pc-secret", None, "adb", tmp_path)
    runtime = DesktopRuntime(settings, fleet=fleet)
    runtime.live_diagnostics.runtime_root = tmp_path
    app = create_desktop_app(settings, runtime)
    headers = {"Authorization": "Bearer pc-secret"}

    with TestClient(app) as client:
        path = f"/v1/devices/{session.device_id}/diagnostics/stream-event"
        assert client.post(path, json={"stage": "client.ws.close", "code": "ABNORMAL_CLOSE"}).status_code == 401
        recorded = client.post(
            path,
            headers=headers,
            json={"stage": "client.ws.close", "code": "ABNORMAL_CLOSE", "attempt": 3, "close_code": 1006, "retryable": True},
        )
        assert recorded.status_code == 200

        bundle_response = client.post(f"/v1/devices/{session.device_id}/diagnostics/bundle", headers=headers)
        assert bundle_response.status_code == 200
        bundle_path = Path(bundle_response.json()["path"])
        assert bundle_path.is_file()
        with zipfile.ZipFile(bundle_path) as archive:
            names = set(archive.namelist())
            assert "connection-manifest.json" in names
            assert "timeline.jsonl" in names
            manifest = archive.read("connection-manifest.json").decode("utf-8")
            timeline = archive.read("timeline.jsonl").decode("utf-8")
        assert "authenticatedBridgeProbe" in manifest
        assert "rawCaptureProbe" in manifest
        assert "client.ws.close" in timeline
        assert "ABNORMAL_CLOSE" in timeline
        assert "pc-secret" not in manifest + timeline
        assert session.credential not in manifest + timeline
