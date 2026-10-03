"""Provider-neutral Local AI adapter contract.

Cyclone owns phone tools, MCP, discovery, permissions and verification.
The AI provider owns the model, inference, conversation and reasoning.
"""

from __future__ import annotations

from dataclasses import asdict, dataclass
from typing import Any, Protocol

AI_STATES = ("UNKNOWN", "DETECTED", "CONFIGURED", "CONNECTED", "FAILED")
PHONE_STATES = ("UNKNOWN", "CONNECTED", "READY", "DISCONNECTED")
REPAIR_LAYERS = ("local_ai", "phone", "engine")


@dataclass(frozen=True)
class DetectionResult:
    id: str
    detected: bool
    installed: bool
    config_path: str | None
    detail: str = ""

    def as_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass(frozen=True)
class ConnectionStatus:
    id: str
    state: str
    detected: bool
    configured: bool
    server_ready: bool
    detail: str = ""
    config_path: str | None = None

    def as_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass(frozen=True)
class RepairResult:
    id: str
    layer: str
    action: str
    label: str
    detail: str
    ok: bool = True

    def as_dict(self) -> dict[str, Any]:
        return asdict(self)


class LocalAIAdapter(Protocol):
    id: str

    def detect(self) -> DetectionResult: ...

    def status(self) -> ConnectionStatus: ...

    def connect(self, *, dry_run: bool = False, executable: str | None = None) -> dict[str, Any]: ...

    def disconnect(self, *, dry_run: bool = False) -> dict[str, Any]: ...

    def repair(self, *, dry_run: bool = False, executable: str | None = None) -> RepairResult: ...

    def verify(self, *, executable: str | None = None) -> dict[str, Any]: ...


def ai_state(*, installed: bool, configured: bool, server_ready: bool, failed: bool = False) -> str:
    if failed:
        return "FAILED"
    if not installed:
        return "UNKNOWN"
    if not configured:
        return "DETECTED"
    if server_ready:
        return "CONNECTED"
    return "CONFIGURED"


def overall_ai_state(states: list[str]) -> str:
    if any(state == "CONNECTED" for state in states):
        return "CONNECTED"
    if any(state == "CONFIGURED" for state in states):
        return "CONFIGURED"
    if any(state == "FAILED" for state in states):
        return "FAILED"
    if any(state == "DETECTED" for state in states):
        return "DETECTED"
    return "UNKNOWN"


def phone_state(*, reachable: bool, device_count: int, ready_device_count: int) -> str:
    if not reachable:
        return "DISCONNECTED"
    if ready_device_count > 0:
        return "READY"
    if device_count > 0:
        return "CONNECTED"
    return "DISCONNECTED"


def repair_actions(
    *,
    ai_state_value: str,
    ai_configured: bool,
    phone_state_value: str,
    engine_ready: bool,
) -> list[RepairResult]:
    """Return independent repairs. Phone unreadiness never becomes an AI failure."""
    actions: list[RepairResult] = []
    if not engine_ready:
        actions.append(
            RepairResult(
                id="engine",
                layer="engine",
                action="restart",
                label="Restart",
                detail="Cyclone engine stopped",
            )
        )
    if ai_state_value in {"UNKNOWN", "DETECTED"} or (ai_state_value == "FAILED" and not ai_configured):
        actions.append(
            RepairResult(
                id="local_ai",
                layer="local_ai",
                action="configure",
                label="Configure",
                detail="Configuration missing",
            )
        )
    elif ai_state_value == "FAILED":
        actions.append(
            RepairResult(
                id="local_ai",
                layer="local_ai",
                action="repair",
                label="Repair",
                detail="Local AI configuration needs repair",
            )
        )
    if phone_state_value in {"DISCONNECTED", "UNKNOWN"}:
        actions.append(
            RepairResult(
                id="phone",
                layer="phone",
                action="connect",
                label="Connect phone",
                detail="Not connected",
            )
        )
    return actions
