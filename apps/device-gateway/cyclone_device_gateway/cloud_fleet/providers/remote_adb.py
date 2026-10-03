"""Any phone reachable at a remote-ADB address (another cloud, a phone on a VPN): no API, just the address the owner
added, kept connected."""
from __future__ import annotations

import time
from typing import Callable

from ..models import AdbLink, CloudPhone, ProviderError, normalize_address


class RemoteAdb:
    name = "Remote ADB"

    def __init__(self, addresses: dict[str, str], *, clock: Callable[[], float] = time.time):
        self.addresses = {str(k): v for k, v in addresses.items()}
        self.clock = clock

    def list_phones(self) -> list[CloudPhone]:
        return [CloudPhone("adb", remote, address, None, "unknown", normalize_address(address))
                for remote, address in sorted(self.addresses.items()) if normalize_address(address)]

    def open_adb(self, phone: CloudPhone, minutes: int = 0) -> AdbLink:
        address = normalize_address(self.addresses.get(phone.remote_id))
        if not address:
            raise ProviderError("ADB_ADDRESS_NEEDED", "Add the phone's ADB address.", retryable=False)
        return AdbLink("direct", int(self.clock() * 1000), None, address=address)
