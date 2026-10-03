from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Any

SERVER_KEY = "cyclone-phone"


def codex_toml(command: str, args: list[str]) -> str:
    def q(value: str) -> str:
        return '"' + value.replace('\\', '\\\\').replace('"', '\\"') + '"'
    lines = [
        "# BEGIN CYCLONE AGENT MCP",
        f"[mcp_servers.{SERVER_KEY}]",
        f"command = {q(command)}",
        f"args = [{', '.join(q(item) for item in args)}]",
        "enabled = true",
        "required = false",
        "startup_timeout_sec = 20",
        "tool_timeout_sec = 120",
        'default_tools_approval_mode = "writes"',
        "# Read-only phone inspection is immediate; Codex asks before mutating tools.",
        "# Gateway credentials are inherited at runtime; no token is stored in TOML.",
        "# END CYCLONE AGENT MCP",
    ]
    return "\n".join(lines) + "\n"


def opencode_profile(command: str, args: list[str]) -> dict[str, Any]:
    return {
        "$schema": "https://opencode.ai/config.json",
        "mcp": {
            "servers": {
                SERVER_KEY: {
                    "type": "local",
                    "command": [command, *args],
                    "disabled": False,
                }
            }
        },
    }


def copilot_profile(command: str, args: list[str]) -> dict[str, Any]:
    return {
        "mcpServers": {
            SERVER_KEY: {
                "type": "local",
                "command": command,
                "args": args,
                "env": {},
                "tools": ["*"],
            }
        }
    }


def generic_profile(command: str, args: list[str]) -> dict[str, Any]:
    return copilot_profile(command, args)


def deepseek_opencode_notes() -> dict[str, Any]:
    return {
        "harness": "OpenCode",
        "model_provider": "DeepSeek",
        "credential_setup": "Use OpenCode /connect and select DeepSeek; keep the API key in OpenCode's credential store.",
        "mcp_profile": "opencode",
        "mcp_server": SERVER_KEY,
        "secrets_in_mcp_config": False,
    }


def deepseek_copilot_notes() -> dict[str, Any]:
    return {
        "harness": "GitHub Copilot CLI",
        "model_provider": "DeepSeek via Copilot BYOK when the selected endpoint/model supports streaming and tool calling",
        "provider_environment": [
            "COPILOT_PROVIDER_TYPE",
            "COPILOT_PROVIDER_BASE_URL",
            "COPILOT_PROVIDER_API_KEY",
            "COPILOT_MODEL",
        ],
        "credential_value_embedded": False,
        "mcp_profile": "copilot",
        "mcp_server": SERVER_KEY,
    }


def dumps_json(value: dict[str, Any]) -> str:
    return json.dumps(value, indent=2, ensure_ascii=False) + "\n"


def codex_config_path() -> Path:
    return Path.home() / ".codex" / "config.toml"


def opencode_config_path() -> Path:
    override = os.getenv("OPENCODE_CONFIG")
    return Path(override).expanduser() if override else Path.home() / ".config" / "opencode" / "opencode.json"


def copilot_config_path() -> Path:
    home = Path(os.getenv("COPILOT_HOME", str(Path.home() / ".copilot"))).expanduser()
    return home / "mcp-config.json"


def cursor_mcp_path() -> Path:
    override = os.getenv("CYCLONE_CURSOR_MCP_PATH", "").strip()
    if override:
        return Path(override).expanduser()
    return Path.home() / ".cursor" / "mcp.json"


def grok_config_path() -> Path:
    override = os.getenv("CYCLONE_GROK_MCP_PATH", "").strip()
    if override:
        return Path(override).expanduser()
    return Path.home() / ".grok" / "mcp.json"


def grok_profile(command: str, args: list[str]) -> dict[str, Any]:
    return cursor_profile(command, args)


def cursor_profile(command: str, args: list[str], env: dict[str, str] | None = None) -> dict[str, Any]:
    safe_env: dict[str, str] = {}
    for key, value in dict(env or {}).items():
        if key == "CYCLONE_DEVICE_GATEWAY_TOKEN":
            continue
        if value is None:
            continue
        safe_env[str(key)] = str(value)
    return {
        "mcpServers": {
            SERVER_KEY: {
                "command": command,
                "args": list(args),
                "env": safe_env,
            }
        }
    }
