"""The cloud phones this PC keeps connected: the accounts, the phones picked from them, and one keeper loop that opens,
renews and repairs each phone's remote-ADB link. A connected cloud phone is an ordinary fleet phone from there on."""
from __future__ import annotations

import re
import secrets
import socket
import subprocess
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable

from ..adb.client import ADBClient, ADBError
from ..desktop_runtime.models import DesktopRuntimeError, RuntimeErrorCode
from . import lease as L
from .http import Transport, urllib_transport
from .models import PROVIDER_LABELS, PROVIDERS, AdbLink, CloudPhone, ProviderError, normalize_address, phone_key
from .providers import make_provider
from .tunnel import SshTunnel, find_ssh
from .vault import CloudVault

TICK_S = 10.0
LIST_EVERY_S = 300.0
MAX_ACCOUNTS = 20
MAX_KEPT = 32  # the fleet's own limit
LEASE_MINUTES = 7 * 24 * 60
SECRET_LIMIT = 400
PRINTABLE = re.compile(r"^[\x21-\x7e]+$")
ENDPOINT_KEYS = {"vmos": {"list", "adb"}, "duoplus": {"list"}, "adb": set()}


@dataclass
class Link:
    """One kept phone's live state. `lease` holds the provider's secret and never leaves this module."""

    key: str
    state: str = "waiting"
    message: str = "Getting ready…"
    lease: AdbLink | None = None
    tunnel: SshTunnel | None = None
    serial: str | None = None
    attempts: int = 0
    connect_failures: int = 0
    tunnel_deaths: int = 0
    next_try_ms: int = 0
    connected_since_ms: int | None = None
    error_code: str | None = None
    installed_checked: bool = False


def _port_free(port: int) -> bool:
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        sock.bind(("127.0.0.1", port))
        return True
    except OSError:
        return False
    finally:
        sock.close()


class CloudFleetService:
    def __init__(self, root: Path, fleet: Any, *, care: Any = None, vault: CloudVault | None = None,
                 transport: Transport = urllib_transport, clock: Callable[[], float] = time.time,
                 adb: ADBClient | None = None, ssh: str | None = None, spawn: Callable[..., Any] = subprocess.Popen,
                 port_free: Callable[[int], bool] = _port_free, provider_factory: Callable[..., Any] = make_provider):
        self.root = root
        self.fleet = fleet
        self.care = care
        self.vault = vault or CloudVault(root / "cloud-accounts.dpapi")
        self.transport = transport
        self.clock = clock
        self.adb = adb or getattr(fleet, "inventory_adb", None) or ADBClient("adb", None)
        self.ssh = ssh if ssh is not None else find_ssh()
        self.spawn = spawn
        self.port_free = port_free
        self.provider_factory = provider_factory
        self._lock = threading.RLock()
        self._links: dict[str, Link] = {}
        self._phones: dict[str, dict[str, CloudPhone]] = {}
        self._listed_at: dict[str, float] = {}
        self._account_errors: dict[str, dict[str, Any]] = {}
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None

    # Lifecycle ------------------------------------------------------------------------------------------------

    def start(self) -> None:
        if self._thread and self._thread.is_alive():
            return
        self._stop.clear()
        self._thread = threading.Thread(target=self._loop, name="cyclone-cloud-fleet", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        with self._lock:
            links = list(self._links.values())
        for link in links:
            if link.tunnel is not None:
                link.tunnel.stop()

    def _loop(self) -> None:
        while not self._stop.is_set():
            try:
                self.tick()
            except Exception:
                pass
            self._stop.wait(TICK_S)

    def _now_ms(self) -> int:
        return int(self.clock() * 1000)

    # Accounts -------------------------------------------------------------------------------------------------

    def add_account(self, body: dict[str, Any]) -> dict[str, Any]:
        provider = str(body.get("provider") or "")
        if provider not in PROVIDERS:
            raise _bad("Pick VMOS Cloud, DuoPlus or Remote ADB.")
        if len(self.vault.accounts()) >= MAX_ACCOUNTS:
            raise _bad(f"Cyclone keeps at most {MAX_ACCOUNTS} cloud accounts.")
        label = str(body.get("label") or "").strip()[:40] or PROVIDER_LABELS[provider]
        raw = body.get("secrets") if isinstance(body.get("secrets"), dict) else {}
        needed = {"vmos": ("accessKey", "secretKey"), "duoplus": ("apiKey",), "adb": ()}[provider]
        kept: dict[str, str] = {}
        for name in needed:
            value = str(raw.get(name) or "").strip()
            if not value or len(value) > SECRET_LIMIT or not PRINTABLE.match(value):
                raise _bad("Paste the key exactly as the provider shows it.")
            kept[name] = value
        account: dict[str, Any] = {
            "id": "acc_" + secrets.token_hex(6), "provider": provider, "label": label, "createdAtMs": self._now_ms(),
            "installCyclone": body.get("installCyclone") is not False, "secrets": kept, "phones": {}, "addresses": {},
        }
        base_url = str(body.get("baseUrl") or "").strip()
        if base_url:
            if not re.match(r"^https://[A-Za-z0-9.\-]+(:[0-9]{1,5})?$", base_url):
                raise _bad("The API address must be https://host with nothing after it.")
            account["baseUrl"] = base_url
        endpoints = body.get("endpoints") if isinstance(body.get("endpoints"), dict) else {}
        clean = {k: str(v) for k, v in endpoints.items() if k in ENDPOINT_KEYS[provider] and re.match(r"^/[A-Za-z0-9/_\-.]{1,120}$", str(v))}
        if clean:
            account["endpoints"] = clean
        self.vault.put(account)
        if provider != "adb":
            try:
                self.refresh_phones(account["id"])
            except ProviderError:
                pass  # shown on the account; the key is kept so the owner can retry after fixing it on the provider
        return self.account_public(account["id"])

    def remove_account(self, account_id: str) -> dict[str, Any]:
        account = self._require(account_id)
        for remote_id in list((account.get("phones") or {}).keys()):
            self._release(phone_key(account["provider"], account_id, remote_id))
        self.vault.remove(account_id)
        with self._lock:
            self._phones.pop(account_id, None)
            self._account_errors.pop(account_id, None)
        return {"ok": True, "removed": account_id}

    def refresh_phones(self, account_id: str) -> list[dict[str, Any]]:
        account = self._require(account_id)
        try:
            phones = self._provider(account).list_phones()
        except ProviderError as exc:
            with self._lock:
                self._account_errors[account_id] = exc.to_dict()
            self._listed_at[account_id] = self.clock()
            raise
        with self._lock:
            self._phones[account_id] = {p.remote_id: p for p in phones}
            self._account_errors.pop(account_id, None)
        self._listed_at[account_id] = self.clock()
        names = {p.remote_id: p.name for p in phones}
        self.vault.update(account_id, lambda a: [a["phones"][r].update({"name": names[r]}) for r in a.get("phones", {}) if r in names])
        return [p.public() for p in phones]

    def add_address(self, account_id: str, address: str, name: str | None = None) -> dict[str, Any]:
        """Remote ADB: add a phone by address (it's kept connected). DuoPlus: the address pasted for a listed phone."""
        account = self._require(account_id)
        clean = normalize_address(address)
        if clean is None:
            raise _bad("That isn't an ADB address. It looks like 203.0.113.7:5555.")
        if account["provider"] != "adb":
            raise _bad("Paste the address on the phone it belongs to.")
        remote = clean

        def change(a: dict[str, Any]) -> None:
            a["addresses"][remote] = clean
            a["phones"][remote] = {"keep": True, "name": (name or clean)[:80]}

        self.vault.update(account_id, change)
        return self.account_public(account_id)

    def set_phone(self, account_id: str, remote_id: str, *, keep: bool | None = None, address: str | None = None) -> dict[str, Any]:
        account = self._require(account_id)
        known = self._phones.get(account_id, {}).get(remote_id)
        if known is None and remote_id not in (account.get("phones") or {}) and remote_id not in (account.get("addresses") or {}):
            raise DesktopRuntimeError(RuntimeErrorCode.DEVICE_NOT_FOUND, "That cloud phone isn't in this account.")
        clean = None
        if address is not None:
            clean = normalize_address(address) if address.strip() else ""
            if clean is None:
                raise _bad("That isn't an ADB address. It looks like 203.0.113.7:5555.")
        if keep and sum(1 for a in self.vault.accounts().values() for p in (a.get("phones") or {}).values() if p.get("keep")) >= MAX_KEPT:
            raise _bad(f"Cyclone keeps at most {MAX_KEPT} phones connected.")

        def change(a: dict[str, Any]) -> None:
            entry = a["phones"].setdefault(remote_id, {"keep": False, "name": known.name if known else remote_id})
            if keep is not None:
                entry["keep"] = keep
            if clean is not None:
                if clean:
                    a["addresses"][remote_id] = clean
                else:
                    a["addresses"].pop(remote_id, None)

        self.vault.update(account_id, change)
        key = phone_key(account["provider"], account_id, remote_id)
        if keep is False or clean is not None:
            self._release(key)
        with self._lock:
            link = self._links.get(key)
            if link is not None:
                link.next_try_ms = 0
        return self.account_public(account_id)

    # Status ---------------------------------------------------------------------------------------------------

    def status(self) -> dict[str, Any]:
        return {"ssh": bool(self.ssh), "security": self.vault.security_mode,
                "accounts": [self.account_public(account_id) for account_id in self.vault.accounts()]}

    def account_public(self, account_id: str) -> dict[str, Any]:
        account = self._require(account_id)
        listed = self._phones.get(account_id, {})
        saved = account.get("phones") or {}
        phones = []
        for remote_id in sorted(set(listed) | set(saved), key=lambda r: (listed.get(r).name if r in listed else saved[r].get("name", r)).lower()):
            phone = listed.get(remote_id)
            entry = saved.get(remote_id) or {}
            item = phone.public() if phone else {"provider": account["provider"], "remoteId": remote_id,
                                                 "name": entry.get("name") or remote_id, "android": None, "power": "unknown",
                                                 "address": None}
            item["address"] = (account.get("addresses") or {}).get(remote_id) or item.get("address")
            item["keep"] = bool(entry.get("keep"))
            item.update(self._link_public(phone_key(account["provider"], account_id, remote_id), bool(entry.get("keep"))))
            phones.append(item)
        return {"id": account_id, "provider": account["provider"], "providerLabel": PROVIDER_LABELS[account["provider"]],
                "label": account.get("label"), "installCyclone": bool(account.get("installCyclone", True)),
                "hasKey": bool(account.get("secrets")), "error": self._account_errors.get(account_id), "phones": phones}

    def _link_public(self, key: str, keep: bool) -> dict[str, Any]:
        with self._lock:
            link = self._links.get(key)
        if not keep:
            return {"state": "off", "message": "Not kept connected.", "serial": None, "deviceId": None, "expiresAtMs": None}
        if link is None:
            return {"state": "waiting", "message": "Connecting…", "serial": None, "deviceId": None, "expiresAtMs": None}
        device_id = None
        if link.serial:
            session = self.fleet.find_by_serial(link.serial) if hasattr(self.fleet, "find_by_serial") else None
            device_id = getattr(session, "device_id", None)
        return {"state": link.state, "message": link.message, "serial": link.serial, "deviceId": device_id,
                "expiresAtMs": link.lease.expires_at_ms if link.lease else None, "errorCode": link.error_code}

    def metadata_for_serial(self, serial: str) -> dict[str, str] | None:
        """For the fleet: a serial that is one of Cyclone's cloud links is a CLOUD phone of that provider."""
        with self._lock:
            for key, link in self._links.items():
                if link.serial and link.serial == serial:
                    provider, _account, remote = key.split(":", 2)
                    return {"source": "CLOUD", "provider": provider, "instanceId": remote}
        return None

    # The keeper ----------------------------------------------------------------------------------------------

    def tick(self) -> None:
        accounts = self.vault.accounts()
        wanted: set[str] = set()
        adb_states: dict[str, str] | None = None
        for account_id, account in accounts.items():
            kept = [r for r, p in (account.get("phones") or {}).items() if p.get("keep")]
            if account["provider"] != "adb" and (kept or account_id not in self._phones) \
                    and self.clock() - self._listed_at.get(account_id, 0) >= LIST_EVERY_S:
                try:
                    self.refresh_phones(account_id)
                except ProviderError:
                    pass
            for remote_id in kept:
                key = phone_key(account["provider"], account_id, remote_id)
                wanted.add(key)
                if adb_states is None:
                    adb_states = self._adb_states()
                self._keep(account, remote_id, key, adb_states)
        with self._lock:
            stale = [key for key in self._links if key not in wanted]
        for key in stale:
            self._release(key)

    def _keep(self, account: dict[str, Any], remote_id: str, key: str, adb_states: dict[str, str]) -> None:
        now = self._now_ms()
        with self._lock:
            link = self._links.setdefault(key, Link(key))
        if link.next_try_ms > now:
            return
        phone = self._phones.get(account["id"], {}).get(remote_id) or CloudPhone(
            account["provider"], remote_id, (account.get("phones", {}).get(remote_id) or {}).get("name") or remote_id)
        if phone.power == "stopped":
            return self._set(link, "off", "This cloud phone is off. Turn it on at the provider.", wait_s=60)
        try:
            renew = link.lease is not None and L.renew_at_ms(link.lease) is not None and now >= L.renew_at_ms(link.lease)
            if link.lease is None or renew:
                self._set(link, "renewing" if renew else "opening",
                          "Renewing the ADB key…" if renew else f"Opening ADB on {PROVIDER_LABELS[account['provider']]}…")
                lease = self._provider(account).open_adb(phone, LEASE_MINUTES)
                self._use(link, account, remote_id, lease)
            if link.lease is not None and link.lease.kind == "ssh":
                if link.tunnel is None or not link.tunnel.alive():
                    if link.tunnel is not None:
                        code, words = link.tunnel.failure()
                        link.tunnel.stop()
                        link.tunnel_deaths += 1
                        if code == "TUNNEL_KEY_REFUSED" or link.tunnel_deaths >= 3:
                            link.lease, link.tunnel_deaths = None, 0
                        raise ProviderError(code, words)
                    self._start_tunnel(link, account, remote_id)
                    return self._set(link, "tunnel", "Starting the secure tunnel…", wait_s=3)
            serial = link.serial
            if serial is None:
                raise ProviderError("ADB_NO_ADDRESS", "The provider gave no ADB address.")
            if adb_states.get(serial) != "device":
                self._set(link, "connecting", "Connecting adb…")
                if not self._adb_connect(serial):
                    link.connect_failures += 1
                    if link.connect_failures >= 3 and link.lease is not None and link.lease.expires_at_ms is not None:
                        link.lease, link.connect_failures = None, 0  # a fresh key and tunnel, not the same dead address
                    raise ProviderError("ADB_CONNECT_FAILED", "adb couldn't reach the phone yet.")
                adb_states[serial] = "device"
            self._connected(link, account, now)
        except ProviderError as exc:
            link.attempts += 1
            wait = L.backoff_s(link.attempts) if exc.retryable else L.NEEDS_YOU_RETRY_S
            state = "waiting" if exc.retryable else "needs_you"
            suffix = f" Trying again in {L.in_words(wait * 1000)}." if exc.retryable else ""
            link.error_code = exc.code
            self._set(link, state, exc.message + suffix, wait_s=wait)

    def _use(self, link: Link, account: dict[str, Any], remote_id: str, lease: AdbLink) -> None:
        old_serial = link.serial
        if link.tunnel is not None:
            link.tunnel.stop()
            link.tunnel = None
        link.lease, link.tunnel_deaths = lease, 0
        if lease.kind == "direct":
            link.serial = lease.address
        else:
            port = self._local_port(account, remote_id, link.key)
            link.serial = f"127.0.0.1:{port}"
        if old_serial and old_serial != link.serial:
            self._disconnect(old_serial)

    def _start_tunnel(self, link: Link, account: dict[str, Any], remote_id: str) -> None:
        if not self.ssh:
            raise ProviderError("SSH_MISSING", "This PC has no OpenSSH client. Turn on Windows' optional feature "
                                "\"OpenSSH Client\", then try again.", retryable=False)
        port = int(str(link.serial).rsplit(":", 1)[1])
        tunnel = SshTunnel(link.lease, port, ssh=self.ssh, scratch=self.root / "tunnels", spawn=self.spawn)
        tunnel.start()
        link.tunnel = tunnel

    def _connected(self, link: Link, account: dict[str, Any], now: int) -> None:
        if link.state != "connected":
            link.connected_since_ms = now
        link.attempts, link.connect_failures, link.error_code = 0, 0, None
        left = ""
        if link.lease is not None and link.lease.expires_at_ms is not None:
            renew = L.renew_at_ms(link.lease) or link.lease.expires_at_ms
            left = f" · key renews in {L.in_words(renew - now)}"
        self._set(link, "connected", "Connected" + left, wait_s=TICK_S)
        if account.get("installCyclone", True) and not link.installed_checked:
            self._install_if_missing(link)

    def _install_if_missing(self, link: Link) -> None:
        """A cloud phone without Cyclone gets this PC's verified build through phone care, once."""
        if self.care is None or not hasattr(self.fleet, "find_by_serial"):
            link.installed_checked = True
            return
        session = self.fleet.find_by_serial(link.serial or "")
        if session is None:
            try:
                self.fleet.refresh_once(source="cloud")
            except Exception:
                return
            session = self.fleet.find_by_serial(link.serial or "")
            if session is None:
                return
        link.installed_checked = True
        try:
            from ..phone_care.updater import read_installed

            if read_installed(session.adb) is None:
                self.care.start_update(session.device_id)
                link.message = link.message.replace("Connected", "Connected · installing Cyclone", 1)
        except Exception:
            link.installed_checked = False

    def _release(self, key: str) -> None:
        with self._lock:
            link = self._links.pop(key, None)
        if link is None:
            return
        if link.tunnel is not None:
            link.tunnel.stop()
        if link.serial:
            self._disconnect(link.serial)

    def _adb_connect(self, serial: str) -> bool:
        """`adb connect` exits 0 even when it fails; only its words say whether it connected."""
        try:
            output = str(self.adb.connect(serial, timeout=10)).lower()
        except ADBError:
            return False
        return ("connected to" in output or "already connected" in output) and "cannot" not in output and "failed" not in output

    def _disconnect(self, serial: str) -> None:
        try:
            self.adb.run(["disconnect", serial], timeout=10, use_serial=False)
        except Exception:
            pass

    def _adb_states(self) -> dict[str, str]:
        try:
            return {d.serial: d.state for d in self.adb.devices()}
        except Exception:
            return {}

    def _local_port(self, account: dict[str, Any], remote_id: str, key: str) -> int:
        saved = ((account.get("phones") or {}).get(remote_id) or {}).get("localPort")
        with self._lock:
            used = {int(str(l.serial).rsplit(":", 1)[1]) for k, l in self._links.items()
                    if k != key and l.serial and l.serial.startswith("127.0.0.1:")}
        if isinstance(saved, int) and saved not in used:
            return saved
        used |= {p.get("localPort") for a in self.vault.accounts().values() for r, p in (a.get("phones") or {}).items()
                 if isinstance(p.get("localPort"), int) and not (a["id"] == account["id"] and r == remote_id)}
        port = L.stable_port(phone_key(account["provider"], account["id"], remote_id), used, self.port_free)
        if port is None:
            raise ProviderError("NO_LOCAL_PORT", "This PC has no free local port for the tunnel.")
        self.vault.update(account["id"], lambda a: a["phones"].setdefault(remote_id, {"keep": True}).update({"localPort": port}))
        return port

    def _set(self, link: Link, state: str, message: str, *, wait_s: float = 0) -> None:
        link.state, link.message = state, message
        link.next_try_ms = self._now_ms() + int(wait_s * 1000)

    def _provider(self, account: dict[str, Any]) -> Any:
        return self.provider_factory(account, transport=self.transport, clock=self.clock)

    def _require(self, account_id: str) -> dict[str, Any]:
        account = self.vault.account(account_id)
        if account is None:
            raise DesktopRuntimeError(RuntimeErrorCode.DEVICE_NOT_FOUND, "That cloud account isn't on this PC.")
        return account


def _bad(message: str) -> DesktopRuntimeError:
    return DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, message)
