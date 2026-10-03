from __future__ import annotations

from typing import Iterable

from .codex import CodexAdapter
from .copilot import CopilotAdapter
from .cursor import CursorAdapter
from .generic import GenericMcpAdapter
from .grok import GrokAdapter
from .local_ai_adapter import (
    AI_STATES,
    PHONE_STATES,
    ConnectionStatus,
    DetectionResult,
    LocalAIAdapter,
    RepairResult,
    overall_ai_state,
    repair_actions,
)
from .opencode import OpenCodeAdapter

ADAPTERS: dict[str, type[LocalAIAdapter]] = {
    "codex": CodexAdapter,
    "grok": GrokAdapter,
    "cursor": CursorAdapter,
    "opencode": OpenCodeAdapter,
    "copilot": CopilotAdapter,
    "generic": GenericMcpAdapter,
}

HOST_ALIASES = {
    "deepseek": "opencode",
    "deepseek-mcp": "opencode",
    "generic-mcp": "generic",
}


def normalize_host(host: str) -> str:
    key = (host or "").strip().lower()
    return HOST_ALIASES.get(key, key)


def get_adapter(host: str) -> LocalAIAdapter:
    key = normalize_host(host)
    adapter_cls = ADAPTERS.get(key)
    if adapter_cls is None:
        raise ValueError(f"Unsupported connector host: {host}")
    return adapter_cls()


def iter_adapters() -> Iterable[LocalAIAdapter]:
    for adapter_cls in ADAPTERS.values():
        yield adapter_cls()


def discover_adapters() -> list[dict]:
    return [adapter.detect().as_dict() for adapter in iter_adapters()]


__all__ = [
    "ADAPTERS",
    "AI_STATES",
    "PHONE_STATES",
    "ConnectionStatus",
    "DetectionResult",
    "LocalAIAdapter",
    "RepairResult",
    "discover_adapters",
    "get_adapter",
    "iter_adapters",
    "normalize_host",
    "overall_ai_state",
    "repair_actions",
]
