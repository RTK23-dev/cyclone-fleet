"""The provider adapters: one small interface for every cloud: list the phones, open a remote-ADB link to one."""
from __future__ import annotations

from typing import Any, Protocol

from ..http import Transport, urllib_transport
from ..models import AdbLink, CloudPhone, ProviderError


class Provider(Protocol):
    name: str

    def list_phones(self) -> list[CloudPhone]: ...

    def open_adb(self, phone: CloudPhone, minutes: int) -> AdbLink: ...


def make_provider(account: dict[str, Any], *, transport: Transport = urllib_transport, clock=None) -> Provider:
    """The adapter for one saved account. `account` holds its secrets; they stay inside the adapter."""
    import time

    kind = account.get("provider")
    secrets = account.get("secrets") or {}
    options = {"base_url": account.get("baseUrl") or None, "endpoints": account.get("endpoints") or None,
               "transport": transport, "clock": clock or time.time}
    if kind == "vmos":
        from .vmos import VmosCloud

        return VmosCloud(str(secrets.get("accessKey") or ""), str(secrets.get("secretKey") or ""), **options)
    if kind == "duoplus":
        from .duoplus import DuoPlus

        return DuoPlus(str(secrets.get("apiKey") or ""), addresses=account.get("addresses") or {}, **options)
    if kind == "adb":
        from .remote_adb import RemoteAdb

        return RemoteAdb(account.get("addresses") or {}, clock=options["clock"])
    raise ProviderError("PROVIDER_UNKNOWN", "Cyclone doesn't know that cloud provider.", retryable=False)


__all__ = ["Provider", "make_provider"]
