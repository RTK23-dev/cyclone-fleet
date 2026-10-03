from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Protocol, runtime_checkable


@dataclass(frozen=True)
class DeviceBackendCapabilities:
    observe: bool
    search: bool
    semantic_actions: tuple[str, ...]
    screenshot: bool
    stream_profiles: tuple[str, ...]
    recover: bool

    def public(self) -> dict[str, Any]:
        return {
            "observe": self.observe,
            "search": self.search,
            "semanticActions": list(self.semantic_actions),
            "screenshot": self.screenshot,
            "streamProfiles": list(self.stream_profiles),
            "recover": self.recover,
        }


@dataclass(frozen=True)
class DeviceBackendStatus:
    device_id: str
    state: str
    source: str
    provider: str | None
    last_seen_ms: int
    diagnostic_reason: str | None = None


@runtime_checkable
class DeviceBackend(Protocol):
    """Provider-neutral phone seam. Mutation remains in Cyclone's canonical phone path."""

    def identify(self) -> dict[str, Any]: ...
    def status(self) -> DeviceBackendStatus: ...
    def capabilities(self) -> DeviceBackendCapabilities: ...
    def observe(self, *, mode: str = "compact") -> dict[str, Any]: ...
    def search(self, query: str) -> dict[str, Any]: ...
    def act(self, capability_id: str, params: dict[str, Any], *, goal: str = "") -> dict[str, Any]: ...
    def screenshot(self, *, profile: str = "thumbnail") -> dict[str, Any]: ...
    def stream(self, *, profile: str = "thumbnail") -> Any: ...
    def app_state(self) -> dict[str, Any]: ...
    def diagnostics(self) -> dict[str, Any]: ...
    def recover(self) -> dict[str, Any]: ...
    def close(self) -> None: ...
