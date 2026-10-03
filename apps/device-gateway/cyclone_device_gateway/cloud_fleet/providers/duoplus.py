"""DuoPlus OpenAPI: list the account's cloud phones. Its ADB is an address open only to whitelisted IPs, so Cyclone
uses the address DuoPlus lists for a phone, or the one the owner pasted from the DuoPlus dashboard, and keeps adb
connected to it. The key goes in the `DuoPlus-API-Key` header.

The paths are the documented defaults and can be overridden per account (`endpoints`).
"""
from __future__ import annotations

import json
import time
from typing import Any, Callable

from ..http import Transport, call_json, urllib_transport
from ..models import AdbLink, CloudPhone, ProviderError, normalize_address

BASE_URL = "https://openapi.duoplus.net"
ENDPOINTS = {"list": "/api/v1/cloudPhone/list"}
PAGE_SIZE = 100
MAX_PAGES = 20
POWER = {0: "stopped", 1: "running", 2: "starting", 3: "stopped", 10: "running"}


class DuoPlus:
    name = "DuoPlus"

    def __init__(self, api_key: str, *, addresses: dict[str, str] | None = None, base_url: str | None = None,
                 endpoints: dict[str, str] | None = None, transport: Transport = urllib_transport,
                 clock: Callable[[], float] = time.time):
        if not api_key:
            raise ProviderError("PROVIDER_AUTH", "Add the DuoPlus API key.", retryable=False)
        self._key = api_key
        self.addresses = {str(k): v for k, v in (addresses or {}).items()}
        self.base_url = (base_url or BASE_URL).rstrip("/")
        self.endpoints = {**ENDPOINTS, **{k: v for k, v in (endpoints or {}).items() if k in ENDPOINTS and str(v).startswith("/")}}
        self.transport = transport
        self.clock = clock

    def _call(self, endpoint: str, payload: dict[str, Any]) -> Any:
        headers = {"Content-Type": "application/json", "DuoPlus-API-Key": self._key, "Lang": "en"}
        return call_json(self.transport, "POST", self.base_url + self.endpoints[endpoint], headers,
                         json.dumps(payload, separators=(",", ":")).encode("utf-8"), provider=self.name)

    def list_phones(self) -> list[CloudPhone]:
        phones: list[CloudPhone] = []
        for page in range(1, MAX_PAGES + 1):
            data = self._call("list", {"page": page, "pagesize": PAGE_SIZE})
            rows = _rows(data)
            phones.extend(p for p in (self._phone(row) for row in rows) if p is not None)
            if len(rows) < PAGE_SIZE:
                break
        return phones

    def open_adb(self, phone: CloudPhone, minutes: int = 0) -> AdbLink:
        address = normalize_address(self.addresses.get(phone.remote_id)) or phone.address
        if not address:
            raise ProviderError("ADB_ADDRESS_NEEDED", "Turn on ADB for this phone in DuoPlus, add this PC's IP to the ADB "
                                "whitelist, then paste the phone's ADB address here.", retryable=False)
        return AdbLink("direct", int(self.clock() * 1000), None, address=address)

    def _phone(self, row: dict[str, Any]) -> CloudPhone | None:
        remote = str(row.get("id") or row.get("image_id") or row.get("imageId") or "").strip()
        if not remote or len(remote) > 64:
            return None
        name = str(row.get("name") or row.get("remark") or remote)[:80]
        android = row.get("os") or row.get("android_version") or row.get("system_version") or row.get("os_version")
        status = row.get("status")
        try:
            power = POWER.get(int(status), "unknown") if status is not None else "unknown"
        except (TypeError, ValueError):
            power = str(status).lower()[:16] if isinstance(status, str) else "unknown"
        listed = None
        for key in ("adb", "adb_address", "adbAddress", "adb_info"):
            if row.get(key):
                listed = normalize_address(str(row[key]))
                if listed:
                    break
        address = normalize_address(self.addresses.get(remote)) or listed
        return CloudPhone("duoplus", remote, name, str(android)[:24] if android else None, power, address)


def _rows(data: Any) -> list[dict[str, Any]]:
    if isinstance(data, list):
        return [r for r in data if isinstance(r, dict)]
    if isinstance(data, dict):
        for key in ("list", "data", "records", "rows"):
            value = data.get(key)
            if isinstance(value, list):
                return [r for r in value if isinstance(r, dict)]
    return []
