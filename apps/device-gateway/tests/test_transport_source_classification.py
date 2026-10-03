from cyclone_device_gateway.adb.client import ADBDevice
from cyclone_device_gateway.desktop_runtime.fleet import DeviceFleetManager


class EmptyInventory:
    def devices(self):
        return []


class FakeDeviceADB:
    def __init__(self, serial):
        self.serial = serial


def test_network_adb_serial_is_lan_not_usb():
    fleet = DeviceFleetManager(
        inventory_adb=EmptyInventory(),
        adb_factory=lambda serial: FakeDeviceADB(serial),
    )
    session = fleet._upsert(ADBDevice("10.0.0.2:5555", "offline", model="VMOS"))
    assert session.source == "LAN"
    assert session.public()["transport"] == {"kind": "LAN", "endpoint": "lan"}


def test_physical_usb_serial_stays_usb():
    fleet = DeviceFleetManager(
        inventory_adb=EmptyInventory(),
        adb_factory=lambda serial: FakeDeviceADB(serial),
    )
    session = fleet._upsert(ADBDevice("3B171FDJH0061G", "offline", model="Pixel_8"))
    assert session.source == "USB"
    assert session.public()["transport"] == {"kind": "USB", "endpoint": "usb"}


def test_android_emulator_serial_is_virtual_not_usb_or_lan():
    fleet = DeviceFleetManager(
        inventory_adb=EmptyInventory(),
        adb_factory=lambda serial: FakeDeviceADB(serial),
    )
    session = fleet._upsert(ADBDevice("emulator-5554", "offline", model="Android_Emulator"))
    assert session.source == "VIRTUAL"
    assert session.provider == "external-emulator"
