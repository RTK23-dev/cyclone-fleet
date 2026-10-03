"""Alpha 88: one truth for connection health, the failing link named with one action, and connecting that stays
connected (trust bound to the USB session, silent resume, one automatic Allow per plug-in, a stopped app woken)."""
from __future__ import annotations

from types import SimpleNamespace

import pytest

from cyclone_device_gateway.capabilities.registry import CapabilityRegistry
from cyclone_device_gateway.cyclone_bridge.client import BridgeBusyError, BridgeOperationError
from cyclone_device_gateway.desktop_runtime import trust_v33
from cyclone_device_gateway.desktop_runtime.connection_doctor import diagnose
from cyclone_device_gateway.desktop_runtime.connection_medic import OPEN_ACCESSIBILITY, OPEN_APP, WAKE, ConnectionMedic
from cyclone_device_gateway.desktop_runtime.models import (
    AITrustState,
    BridgeState,
    DesktopRuntimeError,
    DeviceFleetState,
    DiscoveryState,
    RuntimeErrorCode,
)
from cyclone_device_gateway.desktop_runtime.readiness import control_summary, discovery_state, enrich_device_public
from cyclone_device_gateway.desktop_runtime.trust_v33 import PCTrustCoordinator, PCTrustStore
from test_v33_trust_pc import FakeFleet, FakePhone, FakeSession


def trusted(tmp_path):
    phone = FakePhone()
    session = FakeSession(phone)
    session.usb_session_id = "usb-1"
    session.state = "UNPAIRED"
    session.source = "USB"
    coordinator = PCTrustCoordinator(FakeFleet(session), PCTrustStore(tmp_path), pc_label="Test PC")
    coordinator.auto_ask = False
    coordinator.begin("dev_test")
    phone.allowed = True
    coordinator.complete("dev_test")
    return phone, session, coordinator


# Fix 3: trust belongs to the USB session it was opened on ----------------------------------------------------

def test_a_replug_never_reuses_the_old_session_and_it_resumes_without_a_tap(tmp_path):
    phone, session, coordinator = trusted(tmp_path)
    assert coordinator.status("dev_test")["sessionReady"] is True
    session.usb_session_id = "usb-2"
    assert coordinator.status("dev_test")["sessionReady"] is False, "a new USB session is not trusted until its own handshake"
    begins = []
    original = phone.call
    phone.call = lambda op, args, auth: (begins.append(op), original(op, args, auth))[1]
    coordinator._tick("dev_test")
    assert "trust.session.begin" in begins and "trust.begin" not in begins, "a silent session restore, not a new Allow"
    assert coordinator.status("dev_test")["sessionReady"] is True
    assert coordinator._active["dev_test"].usb_session_id == "usb-2"


def test_a_token_the_phone_rejected_is_restored_instead_of_trusted_forever(tmp_path):
    _phone, session, coordinator = trusted(tmp_path)
    session.credential = None  # the fleet cleared it after the phone said AUTH_REJECTED
    assert coordinator.status("dev_test")["sessionReady"] is False
    coordinator._tick("dev_test")
    assert coordinator.status("dev_test")["sessionReady"] is True


def test_a_locked_phone_is_retried_in_seconds_not_backed_off(tmp_path, monkeypatch):
    _phone, _session, coordinator = trusted(tmp_path)
    monkeypatch.setattr(trust_v33, "_now_ms", lambda: 1_000_000)
    for _ in range(4):
        coordinator._note_retry("dev_test", DesktopRuntimeError(RuntimeErrorCode.PHONE_LOCKED, "Unlock the phone."))
    assert coordinator._next_retry_ms["dev_test"] == 1_000_000 + trust_v33.LOCKED_RETRY_SECONDS * 1000


# Fix 5: one automatic Allow per plug-in, completed without Glass open ------------------------------------------

def plugged(tmp_path):
    phone = FakePhone()
    session = FakeSession(phone)
    session.usb_session_id = "usb-1"
    session.state = DeviceFleetState.UNPAIRED
    session.source = "USB"
    calls: list[str] = []
    original = phone.call
    phone.call = lambda op, args, auth: (calls.append(op), original(op, args, auth))[1]
    return phone, session, PCTrustCoordinator(FakeFleet(session), PCTrustStore(tmp_path), pc_label="Test PC"), calls


def test_a_new_phone_is_asked_once_and_connects_when_the_owner_taps_allow(tmp_path, monkeypatch):
    clock = {"now": 1_000_000}
    monkeypatch.setattr(trust_v33, "_now_ms", lambda: clock["now"])
    phone, session, coordinator, calls = plugged(tmp_path)
    phone.trust_expires = clock["now"] + 120_000
    coordinator._tick("dev_test")
    assert calls.count("trust.begin") == 1
    status = coordinator.status("dev_test")
    assert status["state"] == "CONFIRMATION_REQUIRED" and status["autoAsked"] is True
    clock["now"] += 3_000
    coordinator._tick("dev_test")
    assert calls.count("trust.begin") == 1, "one question on the phone, not one per loop"
    # Glass's Connect joins the question already showing instead of asking again.
    coordinator.begin("dev_test")
    assert calls.count("trust.begin") == 1
    phone.allowed = True
    clock["now"] += 3_000
    coordinator._tick("dev_test")
    assert coordinator.status("dev_test")["trusted"] is True
    assert coordinator.status("dev_test")["sessionReady"] is True


def test_not_now_is_respected_for_a_day_even_across_replugs(tmp_path, monkeypatch):
    clock = {"now": 1_000_000}
    monkeypatch.setattr(trust_v33, "_now_ms", lambda: clock["now"])
    phone, session, coordinator, calls = plugged(tmp_path)
    phone.trust_expires = clock["now"] + 120_000
    coordinator._tick("dev_test")
    phone.rejected = True
    clock["now"] += 3_000
    coordinator._tick("dev_test")
    assert coordinator.status("dev_test")["state"] == "UNPAIRED"
    session.usb_session_id = "usb-2"
    clock["now"] += 60_000
    coordinator._tick("dev_test")
    assert calls.count("trust.begin") == 1
    clock["now"] += trust_v33.AUTO_ASK_DECLINE_COOLDOWN_MS
    phone.rejected = False
    phone.trust_expires = clock["now"] + 120_000
    session.usb_session_id = "usb-3"
    coordinator._tick("dev_test")
    assert calls.count("trust.begin") == 2


def test_auto_ask_can_be_turned_off_and_never_targets_wifi_or_virtual_phones(tmp_path):
    _phone, session, coordinator, calls = plugged(tmp_path)
    session.source = "LAN"
    coordinator._tick("dev_test")
    session.source = "USB"
    coordinator.auto_ask = False
    coordinator._tick("dev_test")
    assert "trust.begin" not in calls


# Fix 4: the failing link, named once ----------------------------------------------------------------------------

READY = dict(discovery=DiscoveryState.ADB_READY, bridge=BridgeState.CONNECTED, trust=AITrustState.TRUSTED, session_ready=True)


@pytest.mark.parametrize("change,code,action", [
    ({"discovery": DiscoveryState.ABSENT}, "USB_NOT_DETECTED", None),
    ({"discovery": DiscoveryState.UNAUTHORIZED}, "USB_ALLOW", None),
    ({"discovery": DiscoveryState.OFFLINE}, "USB_OFFLINE", None),
    ({"bridge": BridgeState.APP_MISSING}, "APP_MISSING", "install"),
    ({"app_running": False}, "APP_STOPPED", "start_app"),
    ({"bridge_error_class": "BridgeBusyError"}, "APP_BUSY", None),
    ({"trust": AITrustState.CONFIRMATION_REQUIRED, "match_code": "123456"}, "TRUST_CONFIRM", None),
    ({"trust": AITrustState.UNPAIRED}, "TRUST_NEEDED", "connect"),
    ({"trust": AITrustState.REVOKED}, "TRUST_AGAIN", "connect"),
    ({"session_ready": False, "last_error": "Unlock the phone to restore AI/Codex access."}, "PHONE_LOCKED", None),
    ({"session_ready": False}, "RESUMING", None),
    ({"gateway_enabled": False}, "GATEWAY_OFF", "open_cyclone"),
    ({"bridge": BridgeState.DEGRADED}, "RECONNECTING", None),
    ({"accessibility": False}, "ACCESSIBILITY_OFF", "open_accessibility"),
    ({}, "READY", None),
])
def test_the_doctor_names_the_first_broken_link_with_at_most_one_action(change, code, action):
    verdict = diagnose(**{**READY, **change}).to_dict()
    assert verdict["code"] == code
    assert (verdict["action"] or {}).get("kind") == action
    assert verdict["ok"] is (code == "READY")
    assert verdict["title"]
    if code == "TRUST_CONFIRM":
        assert "123 456" in verdict["message"]


def fleet_session(**over):
    base = dict(
        device_id="dev_a", serial="SER1234", state=DeviceFleetState.READY, adb_device=SimpleNamespace(state="device", model="Pixel 8", product="shiba", device="shiba"),
        credential="tok", bridge_ok=True, bridge_last_error=None, bridge_error_class=None, bridge_gateway_enabled=True,
        bridge_socket_listening=True, accessibility_connected=True, pending_pairing=None, screen_awake=True, video=None,
        source="USB", app_running=True, phone_controller="AGENT", input_owner="HUMAN",
    )
    base.update(over)
    session = SimpleNamespace(**base)
    session.public = lambda: {"deviceId": session.device_id, "state": str(session.state)}
    return session


def test_every_surface_reads_one_verdict_and_a_gone_phone_is_never_usb_authorized():
    ready = enrich_device_public(fleet_session(), {"state": "TRUSTED", "sessionReady": True})
    assert ready["connection"]["code"] == "READY"
    assert ready["health"]["planes"]["tokenSession"]["reasonCode"] == "TOKEN_SESSION_MATCHED"
    carried = enrich_device_public(fleet_session(), {"state": "TRUSTED", "sessionReady": False})
    assert carried["health"]["planes"]["tokenSession"]["reasonCode"] == "TOKEN_SESSION_PENDING"
    assert carried["connection"]["code"] == "RESUMING"
    gone = fleet_session(state=DeviceFleetState.DISCONNECTED)
    assert discovery_state(gone) == DiscoveryState.ABSENT
    public = enrich_device_public(gone, {"state": "TRUSTED", "sessionReady": False})
    assert public["health"]["planes"]["usbAuthorization"]["reasonCode"] == "USB_ABSENT"
    assert public["connection"]["code"] == "USB_NOT_DETECTED"


def test_the_phone_owns_control_and_the_pc_mirrors_it():
    from cyclone_device_gateway.desktop_runtime.fleet import DeviceFleetManager

    session = fleet_session(input_owner="AI", mobile_version=None)
    DeviceFleetManager.record_bridge_status(SimpleNamespace(_lock=__import__("threading").RLock()), session,
                                            {"controllerOwner": "HUMAN", "gatewayEnabled": True})
    assert session.phone_controller == "HUMAN" and session.input_owner == "HUMAN"
    assert control_summary(session)["effective"] == "OWNER_ON_PHONE"
    assert control_summary(fleet_session(input_owner="AI"))["effective"] == "AI"
    assert control_summary(fleet_session())["effective"] == "PC_USER"


def test_capability_health_says_busy_or_trust_instead_of_disconnected():
    class Bridge:
        def __init__(self, error):
            self.error = error

        def request(self, op, args):
            raise self.error

    registry = CapabilityRegistry()
    busy = registry.discover(Bridge(BridgeBusyError("slow")))
    assert (busy.gateway_health.state.value, busy.gateway_health.reason_code) == ("DEGRADED", "PHONE_APP_BUSY")
    auth = registry.discover(Bridge(BridgeOperationError("AUTH_REJECTED")))
    assert auth.gateway_health.reason_code == "TOKEN_SESSION_MISMATCH"
    gone = registry.discover(Bridge(OSError("refused")))
    assert gone.gateway_health.reason_code == "DEVICE_DISCONNECTED"


# The medic ------------------------------------------------------------------------------------------------------

class ShellAdb:
    def __init__(self, running=False):
        self.running = running
        self.calls: list[tuple] = []

    def shell(self, *args, timeout=15):
        self.calls.append(args)
        if args[0] == "pidof":
            return "4242\n" if self.running else ""
        if args == WAKE:
            self.running = self.wake_works
        return ""

    wake_works = True


class MedicFleet:
    def __init__(self, session):
        self.session = session

    def get(self, device_id):
        if device_id != self.session.device_id:
            raise DesktopRuntimeError(RuntimeErrorCode.DEVICE_NOT_FOUND, "no")
        return self.session

    def list_public(self):
        return [{"deviceId": self.session.device_id}]


def test_a_stopped_app_is_woken_quietly_and_not_hammered():
    adb = ShellAdb(running=False)
    adb.wake_works = False
    session = fleet_session(adb=adb, bridge_ok=False, app_running=None, usb_session_id="usb-1")
    now = {"t": 0.0}
    medic = ConnectionMedic(MedicFleet(session), clock=lambda: now["t"], sleep=lambda _s: None)
    medic.check("dev_a")
    assert session.app_running is False and adb.calls.count(WAKE) == 1
    medic.check("dev_a")
    assert adb.calls.count(WAKE) == 1, "at most one wake every 30 s"
    for _ in range(10):
        now["t"] += 31
        medic.check("dev_a")
    assert adb.calls.count(WAKE) == 5, "five wakes per USB session, then it is reported, not retried"
    assert OPEN_APP not in adb.calls, "never pops Cyclone's screen on its own"
    session.usb_session_id = "usb-2"
    now["t"] += 31
    medic.check("dev_a")
    assert adb.calls.count(WAKE) == 6


def test_one_click_fixes_run_fixed_commands_only():
    adb = ShellAdb(running=False)
    adb.wake_works = False
    session = fleet_session(adb=adb)
    medic = ConnectionMedic(MedicFleet(session), sleep=lambda _s: None)
    medic.fix("dev_a", "start_app")
    assert WAKE in adb.calls and OPEN_APP in adb.calls, "falls back to Cyclone's launcher on older phones"
    medic.fix("dev_a", "open_accessibility")
    assert OPEN_ACCESSIBILITY in adb.calls
    with pytest.raises(DesktopRuntimeError):
        medic.fix("dev_a", "rm -rf")
    with pytest.raises(DesktopRuntimeError):
        ConnectionMedic(MedicFleet(fleet_session(state=DeviceFleetState.DISCONNECTED, adb=adb))).fix("dev_a", "start_app")
