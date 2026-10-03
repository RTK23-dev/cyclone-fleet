"""VMOS Cloud OpenAPI: list the account's cloud phones and open remote ADB on one.

Signing follows VMOS's OpenAPI guide (HMAC-SHA256 over host, x-date, content-type and the body's SHA-256, credential
scope `<date>/armcloud-paas/request`). The paths are the documented defaults and can be overridden per account
(`endpoints`), so a change on VMOS's side is a settings fix, not a release.

Remote ADB answers with an SSH command (`ssh … user@host -p port -L local:adb-proxy:port -Nf`), a key (the SSH
password) and an expiry. Cyclone asks for the longest lease VMOS allows (7 days) and renews it before it ends.
"""
from __future__ import annotations

import hashlib
import hmac
import json
import shlex
import time
from datetime import datetime, timedelta, timezone
from typing import Any, Callable
from urllib.parse import urlparse

from ..http import Transport, call_json, urllib_transport
from ..models import AdbLink, CloudPhone, ProviderError, normalize_address

BASE_URL = "https://api.vmoscloud.com"
ENDPOINTS = {
    "list": "/vcpcloud/api/padApi/infos",
    "adb": "/vcpcloud/api/padApi/adb",
}
SERVICE = "armcloud-paas"
CONTENT_TYPE = "application/json;charset=UTF-8"
SIGNED_HEADERS = "content-type;host;x-content-sha256;x-date"
MAX_LEASE_MINUTES = 7 * 24 * 60
PAGE_ROWS = 100
MAX_PAGES = 20
# VMOS reports times as Beijing time when it gives no zone; reading them that way is also the earlier (safer) guess.
PROVIDER_TZ = timezone(timedelta(hours=8))
RUNNING = {10: "running", 11: "starting", 12: "starting", 14: "stopped", 15: "starting"}


def sign(access_key: str, secret_key: str, host: str, body: str, x_date: str) -> dict[str, str]:
    """The request headers for one signed call. Pure, so the signature can be checked against VMOS's own example."""
    short_date = x_date[:8]
    content_sha = hashlib.sha256(body.encode("utf-8")).hexdigest()
    canonical = (f"host:{host}\nx-date:{x_date}\ncontent-type:{CONTENT_TYPE}\nsignedHeaders:{SIGNED_HEADERS}\n"
                 f"x-content-sha256:{content_sha}")
    scope = f"{short_date}/{SERVICE}/request"
    to_sign = f"HMAC-SHA256\n{x_date}\n{scope}\n{hashlib.sha256(canonical.encode('utf-8')).hexdigest()}"

    def mac(key: bytes, text: str) -> bytes:
        return hmac.new(key, text.encode("utf-8"), hashlib.sha256).digest()

    signing_key = mac(mac(mac(secret_key.encode("utf-8"), short_date), SERVICE), "request")
    signature = hmac.new(signing_key, to_sign.encode("utf-8"), hashlib.sha256).hexdigest()
    return {
        "content-type": CONTENT_TYPE,
        "x-date": x_date,
        "x-host": host,
        "x-content-sha256": content_sha,
        "authorization": f"HMAC-SHA256 Credential={access_key}, SignedHeaders={SIGNED_HEADERS}, Signature={signature}",
    }


class VmosCloud:
    name = "VMOS Cloud"

    def __init__(self, access_key: str, secret_key: str, *, base_url: str | None = None, endpoints: dict[str, str] | None = None,
                 transport: Transport = urllib_transport, clock: Callable[[], float] = time.time):
        if not access_key or not secret_key:
            raise ProviderError("PROVIDER_AUTH", "Add the VMOS access key and secret key.", retryable=False)
        self._ak, self._sk = access_key, secret_key
        self.base_url = (base_url or BASE_URL).rstrip("/")
        self.endpoints = {**ENDPOINTS, **{k: v for k, v in (endpoints or {}).items() if k in ENDPOINTS and str(v).startswith("/")}}
        self.transport = transport
        self.clock = clock

    def _call(self, endpoint: str, payload: dict[str, Any]) -> Any:
        body = json.dumps(payload, separators=(",", ":"), ensure_ascii=False)
        x_date = datetime.fromtimestamp(self.clock(), timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        host = urlparse(self.base_url).netloc
        headers = sign(self._ak, self._sk, host, body, x_date)
        return call_json(self.transport, "POST", self.base_url + self.endpoints[endpoint], headers, body.encode("utf-8"),
                         provider=self.name)

    def list_phones(self) -> list[CloudPhone]:
        phones: list[CloudPhone] = []
        for page in range(1, MAX_PAGES + 1):
            data = self._call("list", {"page": page, "rows": PAGE_ROWS})
            rows = _rows(data)
            phones.extend(p for p in (_phone(row) for row in rows) if p is not None)
            total = data.get("total") if isinstance(data, dict) else None
            if len(rows) < PAGE_ROWS or (isinstance(total, int) and len(phones) >= total):
                break
        return phones

    def open_adb(self, phone: CloudPhone, minutes: int = MAX_LEASE_MINUTES) -> AdbLink:
        minutes = max(60, min(int(minutes), MAX_LEASE_MINUTES))
        issued = int(self.clock() * 1000)
        data = self._call("adb", {"padCode": phone.remote_id, "enable": True, "expireMinutes": minutes})
        item = data[0] if isinstance(data, list) and data else data
        if not isinstance(item, dict):
            raise ProviderError("PROVIDER_ANSWER", "VMOS didn't return an ADB link for this phone.")
        link = parse_adb_answer(item, issued_ms=issued, requested_minutes=minutes)
        if link is None:
            raise ProviderError("ADB_NOT_OPEN", "VMOS didn't open ADB for this phone. Check that remote ADB is allowed for the account.")
        return link


def _rows(data: Any) -> list[dict[str, Any]]:
    if isinstance(data, list):
        return [r for r in data if isinstance(r, dict)]
    if isinstance(data, dict):
        for key in ("pageData", "list", "records", "rows", "data"):
            value = data.get(key)
            if isinstance(value, list):
                return [r for r in value if isinstance(r, dict)]
    return []


def _phone(row: dict[str, Any]) -> CloudPhone | None:
    code = str(row.get("padCode") or "").strip()
    if not code or len(code) > 64:
        return None
    name = str(row.get("padName") or row.get("name") or row.get("remark") or code)[:80]
    android = row.get("androidVersion") or row.get("imageVersion") or row.get("osVersion")
    status = row.get("padStatus")
    try:
        power = RUNNING.get(int(status), "unknown") if status is not None else "unknown"
    except (TypeError, ValueError):
        power = "unknown"
    return CloudPhone("vmos", code, name, str(android)[:24] if android else None, power)


def parse_adb_answer(item: dict[str, Any], *, issued_ms: int, requested_minutes: int) -> AdbLink | None:
    """An AdbLink from VMOS's `{command, key, adb, expireTime, enable}`. The expiry is the earlier of what VMOS says
    and what Cyclone asked for, so a misread time can only make Cyclone renew early, never late."""
    if item.get("enable") is False:
        return None
    ours = issued_ms + requested_minutes * 60_000
    theirs = parse_time_ms(item.get("expireTime"))
    expires = min(ours, theirs) if theirs and theirs > issued_ms else ours
    command = str(item.get("command") or "").strip()
    if command.startswith("ssh"):
        ssh = parse_ssh_command(command)
        if ssh is None:
            return None
        return AdbLink("ssh", issued_ms, expires, ssh_user=ssh["user"], ssh_host=ssh["host"], ssh_port=ssh["port"],
                       target_host=ssh["target_host"], target_port=ssh["target_port"], secret=str(item.get("key") or "") or None)
    address = normalize_address(str(item.get("adb") or command))
    if address and not address.startswith(("localhost:", "127.")):
        return AdbLink("direct", issued_ms, expires, address=address)
    return None


def parse_ssh_command(command: str) -> dict[str, Any] | None:
    """user, host, port and the `-L` target from VMOS's SSH line. Its own local port and `-f` are not used: Cyclone
    picks a stable local port per phone and keeps the tunnel in the foreground so it can watch it."""
    try:
        parts = shlex.split(command, posix=True)
    except ValueError:
        return None
    user = host = target_host = None
    port, target_port = 22, None
    i = 1
    while i < len(parts):
        token = parts[i]
        if token == "-p" and i + 1 < len(parts):
            port = _int(parts[i + 1], 22)
            i += 2
            continue
        if token.startswith("-L"):
            spec = token[2:] or (parts[i + 1] if i + 1 < len(parts) else "")
            i += 1 if token[2:] else 2
            bits = spec.split(":")
            if len(bits) >= 3:
                target_host, target_port = bits[-2], _int(bits[-1], 0) or None
            continue
        if token in {"-o", "-i", "-l", "-F"}:
            i += 2
            continue
        if not token.startswith("-") and "@" in token:
            user, host = token.rsplit("@", 1)
        i += 1
    if not (user and host and target_host and target_port) or not 1 <= port <= 65535:
        return None
    return {"user": user, "host": host, "port": port, "target_host": target_host, "target_port": target_port}


def parse_time_ms(value: Any) -> int | None:
    if value is None or value == "":
        return None
    if isinstance(value, (int, float)) or (isinstance(value, str) and value.strip().isdigit()):
        number = int(float(value))
        return number if number > 10**12 else number * 1000
    text = str(value).strip()
    for fmt in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%dT%H:%M:%S", "%Y-%m-%dT%H:%M:%SZ", "%Y/%m/%d %H:%M:%S"):
        try:
            parsed = datetime.strptime(text, fmt)
        except ValueError:
            continue
        zone = timezone.utc if text.endswith("Z") else PROVIDER_TZ
        return int(parsed.replace(tzinfo=zone).timestamp() * 1000)
    return None


def _int(value: str, fallback: int) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return fallback
