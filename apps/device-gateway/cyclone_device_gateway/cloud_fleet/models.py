"""The shapes every provider answers in: a cloud phone, and the remote-ADB link to it. Pure."""
from __future__ import annotations

from dataclasses import dataclass, field
import re
from typing import Any, Literal

PROVIDERS = ("vmos", "duoplus", "adb")
PROVIDER_LABELS = {"vmos": "VMOS Cloud", "duoplus": "DuoPlus", "adb": "Remote ADB"}
ADDRESS = re.compile(r"^(?P<host>[A-Za-z0-9](?:[A-Za-z0-9.\-]{0,251}[A-Za-z0-9])?):(?P<port>[0-9]{1,5})$")
ADDRESS_IN_TEXT = re.compile(r"([A-Za-z0-9](?:[A-Za-z0-9.\-]{0,251}[A-Za-z0-9])?:[0-9]{2,5})")


class ProviderError(Exception):
    """A provider call that didn't work, in words the owner can act on. Never carries a key or a raw response."""

    def __init__(self, code: str, message: str, *, retryable: bool = True):
        super().__init__(message)
        self.code = code
        self.message = message
        self.retryable = retryable

    def to_dict(self) -> dict[str, Any]:
        return {"code": self.code, "message": self.message, "retryable": self.retryable}


@dataclass(frozen=True)
class CloudPhone:
    provider: str
    remote_id: str
    name: str
    android: str | None = None
    # The provider's own state in a word: running, stopped, starting, unknown.
    power: str = "unknown"
    # A remote-ADB address the provider listed or the owner pasted (DuoPlus, plain remote ADB).
    address: str | None = None

    def public(self) -> dict[str, Any]:
        return {"provider": self.provider, "remoteId": self.remote_id, "name": self.name, "android": self.android,
                "power": self.power, "address": self.address}


@dataclass(frozen=True)
class AdbLink:
    """How to reach one phone's adb right now. `secret` (an SSH password or key) never leaves this process."""

    kind: Literal["direct", "ssh"]
    issued_at_ms: int
    expires_at_ms: int | None = None
    address: str | None = None
    ssh_user: str | None = None
    ssh_host: str | None = None
    ssh_port: int = 22
    target_host: str | None = None
    target_port: int | None = None
    secret: str | None = field(default=None, repr=False)

    def public(self) -> dict[str, Any]:
        return {"kind": self.kind, "issuedAtMs": self.issued_at_ms, "expiresAtMs": self.expires_at_ms,
                "address": self.address if self.kind == "direct" else None}


def normalize_address(value: str | None) -> str | None:
    """`host:port` from an address or a pasted `adb connect host:port` line; None when there isn't one."""
    text = (value or "").strip()
    if not text:
        return None
    match = ADDRESS.match(text)
    if match is None:
        found = ADDRESS_IN_TEXT.findall(text)
        if not found:
            return None
        match = ADDRESS.match(found[-1])
        if match is None:
            return None
    port = int(match.group("port"))
    if not 1 <= port <= 65535:
        return None
    return f"{match.group('host').lower()}:{port}"


def phone_key(provider: str, account_id: str, remote_id: str) -> str:
    return f"{provider}:{account_id}:{remote_id}"
