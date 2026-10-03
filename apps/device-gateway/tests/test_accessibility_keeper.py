"""Alpha 91: Cyclone's Accessibility is put back after Android removed it (force-stop), within fixed limits."""
from __future__ import annotations

from types import SimpleNamespace

from cyclone_device_gateway.desktop_runtime.accessibility_keeper import SERVICE, AccessibilityKeeper, has_cyclone, restored
from cyclone_device_gateway.desktop_runtime.connection_medic import ConnectionMedic
from cyclone_device_gateway.desktop_runtime.models import DeviceFleetState

OTHERS = "ai.closepaw/.app.AgentService:com.artemis.helper/.ArtemisAccessibilityService"


class Adb:
    def __init__(self, value: str):
        self.value = value
        self.calls: list[tuple[str, ...]] = []

    def shell(self, *args, timeout=15):
        self.calls.append(args)
        if args[:3] == ("settings", "get", "secure"):
            return self.value + "\n"
        if args[:4] == ("settings", "put", "secure", "enabled_accessibility_services"):
            self.value = args[4].strip("'")
        if args[0] == "pidof":
            return "1234"
        return ""


class Clock:
    def __init__(self):
        self.t = 1000.0

    def __call__(self):
        return self.t


def test_the_list_keeps_every_other_service_and_adds_only_cyclone():
    assert restored(OTHERS) == OTHERS + ":" + SERVICE
    assert restored("null") == SERVICE
    assert restored("") == SERVICE
    assert restored("bad;rm -rf /:" + OTHERS) == OTHERS + ":" + SERVICE
    assert has_cyclone(OTHERS + ":com.cyclone.mobile/com.cyclone.mobile.CycloneAccessibilityService")
    assert not has_cyclone(OTHERS)


def test_it_restores_only_where_the_owner_had_it_on_and_at_most_three_times_an_hour(tmp_path, monkeypatch):
    monkeypatch.delenv("CYCLONE_AUTO_REPAIR_ACCESSIBILITY", raising=False)
    clock = Clock()
    keeper = AccessibilityKeeper(tmp_path / "k.json", clock=clock)
    adb = Adb(OTHERS)
    session = SimpleNamespace(adb=adb)
    assert keeper.check("dev1", session) is None, "never seen on: never turned on"
    assert adb.calls == []
    keeper.seen_on("dev1")
    clock.t += 11  # it looks at most every 10 s
    assert keeper.check("dev1", session) == "restored"
    assert adb.value == OTHERS + ":" + SERVICE
    assert ("settings", "put", "secure", "accessibility_enabled", "1") in adb.calls
    for _ in range(2):
        adb.value = OTHERS
        clock.t += 60
        assert keeper.check("dev1", session) == "restored"
    adb.value = OTHERS
    clock.t += 60
    assert keeper.check("dev1", session) is None, "a phone that keeps losing it is reported, not hammered"
    clock.t += 3600
    assert keeper.check("dev1", session) == "restored"
    monkeypatch.setenv("CYCLONE_AUTO_REPAIR_ACCESSIBILITY", "0")
    adb.value = OTHERS
    clock.t += 60
    assert keeper.check("dev1", session) is None
    # Remembered across a PC restart.
    assert AccessibilityKeeper(tmp_path / "k.json")._seen == {"dev1": True}


def test_the_medic_remembers_it_on_and_puts_it_back_when_it_goes(tmp_path, monkeypatch):
    monkeypatch.delenv("CYCLONE_AUTO_REPAIR_ACCESSIBILITY", raising=False)
    adb = Adb(OTHERS + ":" + SERVICE)
    session = SimpleNamespace(adb=adb, adb_device=SimpleNamespace(state="device"), state=DeviceFleetState.READY,
                              bridge_last_error=None, bridge_ok=True, credential="tok", accessibility_connected=True,
                              usb_session_id="u1", app_running=True)
    fleet = SimpleNamespace(get=lambda _id: session, list_public=lambda: [{"deviceId": "dev1"}])
    keeper = AccessibilityKeeper(tmp_path / "k.json", clock=Clock())
    medic = ConnectionMedic(fleet, keeper=keeper)
    medic.check("dev1")
    assert keeper._seen == {"dev1": True}
    adb.value = OTHERS  # Android took it away after a force-stop
    session.accessibility_connected = False
    medic.check("dev1")
    assert adb.value == OTHERS + ":" + SERVICE
    assert session.accessibility_connected is None


def test_a_phone_without_accessibility_is_never_reported_ready():
    from cyclone_device_gateway.desktop_runtime.fleet import DeviceSession

    from cyclone_device_gateway.adb.client import ADBDevice

    device = ADBDevice(serial="ABC123", state="device", model="Pixel_8", device="shiba", product="shiba", transport_id="1")
    live = DeviceSession(device_id="dev1", serial="ABC123", adb_device=device, adb=None, local_port=18001, usb_session_id="u",
                         state=DeviceFleetState.READY, credential="tok", accessibility_connected=False)
    assert live.public()["state"] == "ATTENTION"
    live.accessibility_connected = True
    assert live.public()["state"] == "READY"


def test_repair_puts_it_back_when_the_owner_presses_it_and_opens_the_list_when_it_cannot(tmp_path, monkeypatch):
    # Alpha 93: the owner's Repair press is the owner's choice, so no "seen on before" is needed; the list is the fallback.
    from cyclone_device_gateway.desktop_runtime.connection_medic import OPEN_ACCESSIBILITY
    from cyclone_device_gateway.desktop_runtime.accessibility_keeper import MAX_OWNER_REPAIRS_PER_HOUR
    clock = Clock()
    adb = Adb(OTHERS)
    session = SimpleNamespace(adb=adb, adb_device=SimpleNamespace(state="device"), state=DeviceFleetState.READY,
                              bridge_last_error=None, bridge_ok=True, credential="tok", accessibility_connected=False,
                              usb_session_id="u1", app_running=True)
    fleet = SimpleNamespace(get=lambda _id: session, list_public=lambda: [{"deviceId": "dev1"}])
    keeper = AccessibilityKeeper(tmp_path / "k.json", clock=clock)
    medic = ConnectionMedic(fleet, keeper=keeper, sleep=lambda _s: None)
    result = medic.fix("dev1", "open_accessibility")
    assert result["repaired"] is True
    assert adb.value == OTHERS + ":" + SERVICE
    assert OPEN_ACCESSIBILITY not in adb.calls
    assert keeper._seen == {"dev1": True}, "from now on the keeper restores it by itself"
    assert medic.fix("dev1", "open_accessibility")["repaired"] is True  # already on: nothing to open
    for _ in range(MAX_OWNER_REPAIRS_PER_HOUR - 1):
        adb.value = OTHERS
        assert medic.fix("dev1", "open_accessibility").get("repaired") is True
    adb.value = OTHERS
    assert "repaired" not in medic.fix("dev1", "open_accessibility"), "past the hourly limit the list opens instead"
    assert OPEN_ACCESSIBILITY in adb.calls


def test_repair_opens_the_list_when_the_setting_does_not_stick(tmp_path):
    from cyclone_device_gateway.desktop_runtime.connection_medic import OPEN_ACCESSIBILITY

    class Stuck(Adb):
        def shell(self, *args, timeout=15):
            if args[:4] == ("settings", "put", "secure", "enabled_accessibility_services"):
                self.calls.append(args)
                return ""
            return super().shell(*args, timeout=timeout)

    adb = Stuck(OTHERS)
    session = SimpleNamespace(adb=adb, adb_device=SimpleNamespace(state="device"), state=DeviceFleetState.READY,
                              bridge_last_error=None, bridge_ok=True, credential="tok", accessibility_connected=False,
                              usb_session_id="u1", app_running=True)
    fleet = SimpleNamespace(get=lambda _id: session, list_public=lambda: [{"deviceId": "dev1"}])
    medic = ConnectionMedic(fleet, keeper=AccessibilityKeeper(tmp_path / "k.json", clock=Clock()), sleep=lambda _s: None)
    assert "repaired" not in medic.fix("dev1", "open_accessibility")
    assert OPEN_ACCESSIBILITY in adb.calls
