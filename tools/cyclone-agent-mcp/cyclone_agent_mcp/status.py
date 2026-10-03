from __future__ import annotations

import json
import shutil
from pathlib import Path
from typing import Any

from .adapters import discover_adapters, iter_adapters, overall_ai_state, repair_actions
from .adapters.local_ai_adapter import phone_state
from .connector import _expected_tool_names, host_installed, resolve_server_command
from .gateway import GatewayClient, GatewayError
from .profiles import (
    SERVER_KEY,
    codex_config_path,
    copilot_config_path,
    cursor_mcp_path,
    grok_config_path,
    opencode_config_path,
)
from .tool_catalog import TOOL_NAMES

HOST_STATES = {"CONNECTED", "READY", "NOT_INSTALLED", "ATTENTION"}
GENERIC_STATES = {"READY", "ATTENTION"}
AI_STATES = {"UNKNOWN", "DETECTED", "CONFIGURED", "CONNECTED", "FAILED"}
PHONE_STATES = {"UNKNOWN", "CONNECTED", "READY", "DISCONNECTED"}


def get_ai_status() -> dict[str, Any]:
    server = resolve_server_command()
    server_ready = _command_exists(server.command)
    adapters = {adapter.id: adapter.status().as_dict() for adapter in iter_adapters()}
    return {
        "state": overall_ai_state([item["state"] for item in adapters.values()]),
        "server_ready": server_ready,
        "adapters": adapters,
        "discovery": discover_adapters(),
    }


def get_phone_status() -> dict[str, Any]:
    gateway = _gateway_status()
    state = phone_state(
        reachable=bool(gateway.get("reachable")),
        device_count=int(gateway.get("device_count") or 0),
        ready_device_count=int(gateway.get("ready_device_count") or 0),
    )
    return {**gateway, "state": state}


def connection_status(*, probe_gateway: bool = False) -> dict[str, Any]:
    ai = get_ai_status()
    adapters = ai["adapters"]
    codex = _legacy_host_state(adapters.get("codex", {}))
    opencode = _legacy_host_state(adapters.get("opencode", {}))
    copilot = _legacy_host_state(adapters.get("copilot", {}))
    deepseek = "READY" if ai["server_ready"] and (opencode in {"CONNECTED", "READY"} or copilot in {"CONNECTED", "READY"}) else (
        "NOT_INSTALLED" if opencode == "NOT_INSTALLED" and copilot == "NOT_INSTALLED" else "ATTENTION"
    )
    generic = "READY" if ai["server_ready"] else "ATTENTION"
    result: dict[str, Any] = {
        "codex": codex,
        "deepseek_harness": deepseek,
        "generic_mcp": generic,
        "ai": ai,
    }
    if probe_gateway:
        phone = get_phone_status()
        result["phone"] = phone
        result["repair"] = [item.as_dict() for item in repair_actions(
            ai_state_value=str(ai.get("state") or "UNKNOWN"),
            ai_configured=any(bool(item.get("configured")) for item in adapters.values()),
            phone_state_value=str(phone.get("state") or "UNKNOWN"),
            engine_ready=bool(ai.get("server_ready")),
        )]
        gateway = {key: value for key, value in phone.items() if key != "state"}
        gateway["state"] = "READY" if phone.get("ready_device_count") else (
            "NO_READY_PHONE" if phone.get("reachable") else "OFFLINE"
        )
        if "devices" in phone:
            gateway["devices"] = phone["devices"]
        result["details"] = {
            "codex": {
                "state": adapters.get("codex", {}).get("state"),
                "legacy_state": codex,
                "detected": adapters.get("codex", {}).get("detected"),
                "configured": adapters.get("codex", {}).get("configured"),
                "config_path": adapters.get("codex", {}).get("config_path") or str(codex_config_path()),
                "server_ready": ai["server_ready"],
                "approval_mode": "writes",
            },
            "gateway": gateway,
            "mcp": {
                "server": SERVER_KEY,
                "tool_count": len(_expected_tool_names()),
                "transport": "stdio",
            },
            "adapters": adapters,
        }
    return result


def _legacy_host_state(adapter: dict[str, Any]) -> str:
    state = str(adapter.get("state") or "UNKNOWN")
    return {
        "CONNECTED": "READY",
        "CONFIGURED": "CONNECTED",
        "DETECTED": "ATTENTION",
        "FAILED": "ATTENTION",
        "UNKNOWN": "NOT_INSTALLED",
    }.get(state, "ATTENTION")


def _host_state(host: str, path: Path, configured, server_ready: bool) -> str:
    installed = host_installed(host)
    if not installed:
        return "NOT_INSTALLED"
    try:
        has_config = configured(path)
    except Exception:
        return "ATTENTION"
    if has_config and server_ready:
        return "READY"
    if has_config:
        return "CONNECTED"
    return "ATTENTION"


def _safe_configured(path: Path, configured) -> bool:
    try:
        return bool(configured(path))
    except Exception:
        return False


def _gateway_status() -> dict[str, Any]:
    try:
        devices = GatewayClient(timeout=2.0).list_devices()
    except GatewayError as exc:
        code = "GATEWAY_UNAVAILABLE"
        if isinstance(exc.body, dict):
            error = exc.body.get("error")
            if isinstance(error, dict) and isinstance(error.get("code"), str):
                code = error["code"][:80]
        return {
            "state": "OFFLINE",
            "reachable": False,
            "ready_device_count": 0,
            "device_count": 0,
            "error_code": code,
        }
    except Exception:
        # Connector diagnostics must never make the desktop shell unusable.
        # Keep implementation details out of the model-facing status payload.
        return {
            "state": "OFFLINE",
            "reachable": False,
            "ready_device_count": 0,
            "device_count": 0,
            "error_code": "GATEWAY_UNAVAILABLE",
        }
    safe_devices = [device.safe_dict() for device in devices]
    ready = [device for device in devices if device.ready]
    return {
        "state": "READY" if ready else "NO_READY_PHONE",
        "reachable": True,
        "ready_device_count": len(ready),
        "device_count": len(devices),
        "devices": safe_devices,
    }


def _command_exists(command: str) -> bool:
    path = Path(command)
    return path.exists() if path.is_absolute() else shutil.which(command) is not None


def _codex_configured(path: Path) -> bool:
    return path.exists() and f"[mcp_servers.{SERVER_KEY}]" in path.read_text(encoding="utf-8")


def _json_opencode_configured(path: Path) -> bool:
    data = _json(path)
    return SERVER_KEY in data.get("mcp", {}).get("servers", {})


def _json_copilot_configured(path: Path) -> bool:
    data = _json(path)
    return SERVER_KEY in data.get("mcpServers", {})


def _json_mcp_servers_configured(path: Path) -> bool:
    data = _json(path)
    return SERVER_KEY in data.get("mcpServers", {})


def _json(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {}
    value = json.loads(path.read_text(encoding="utf-8"))
    return value if isinstance(value, dict) else {}
