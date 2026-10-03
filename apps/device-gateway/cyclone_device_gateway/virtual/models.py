from __future__ import annotations

from dataclasses import asdict, dataclass, field
from enum import StrEnum
from typing import Any


class VirtualInstanceState(StrEnum):
    CREATED = "CREATED"
    STARTING = "STARTING"
    RUNNING = "RUNNING"
    STOPPED = "STOPPED"
    ERROR = "ERROR"


@dataclass(frozen=True)
class VirtualDeviceConfig:
    image: str
    width: int = 1080
    height: int = 1920
    dpi: int = 420
    fps: int = 30
    locale: str = "en-US"
    timezone: str = "UTC"
    network_mode: str = "loopback"
    storage_mb: int = 8192

    def validate(self) -> None:
        if not self.image or len(self.image) > 240:
            raise ValueError("image must be a bounded provider image ID")
        if not 320 <= self.width <= 3840 or not 480 <= self.height <= 3840:
            raise ValueError("display dimensions are outside the supported range")
        if not 120 <= self.dpi <= 640 or not 1 <= self.fps <= 60:
            raise ValueError("DPI or FPS is outside the supported range")
        if self.network_mode != "loopback":
            raise ValueError("virtual Android networking defaults to loopback only")
        if not 2048 <= self.storage_mb <= 131072:
            raise ValueError("storage_mb is outside the supported range")


@dataclass
class VirtualInstance:
    instance_id: str
    provider: str
    cyclone_device_id: str
    name: str
    config: VirtualDeviceConfig
    state: VirtualInstanceState = VirtualInstanceState.CREATED
    data_path: str = ""
    adb_endpoint: str | None = None
    console_port: int | None = None
    created_at_ms: int = 0
    last_started_at_ms: int | None = None
    last_error: str | None = None
    pid: int | None = None

    def public(self) -> dict[str, Any]:
        value = asdict(self)
        value["state"] = self.state.value
        value["config"] = asdict(self.config)
        value["instanceId"] = value.pop("instance_id")
        value["cycloneDeviceId"] = value.pop("cyclone_device_id")
        value["dataPath"] = value.pop("data_path")
        value["adbEndpoint"] = value.pop("adb_endpoint")
        value["consolePort"] = value.pop("console_port")
        value["createdAtEpochMs"] = value.pop("created_at_ms")
        value["lastStartedAtEpochMs"] = value.pop("last_started_at_ms")
        value["lastError"] = value.pop("last_error")
        return value


@dataclass(frozen=True)
class VirtualProviderHealth:
    provider: str
    available: bool
    state: str
    reason: str | None
    capabilities: tuple[str, ...] = field(default_factory=tuple)

    def public(self) -> dict[str, Any]:
        return {
            "provider": self.provider,
            "available": self.available,
            "state": self.state,
            "reason": self.reason,
            "capabilities": list(self.capabilities),
        }
