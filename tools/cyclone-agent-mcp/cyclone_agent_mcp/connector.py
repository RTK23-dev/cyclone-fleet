from __future__ import annotations

import asyncio
import json
import os
import shutil
import sys
import tomllib
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from .profiles import (
    SERVER_KEY,
    codex_config_path,
    codex_toml,
    copilot_config_path,
    copilot_profile,
    cursor_mcp_path,
    cursor_profile,
    generic_profile,
    grok_config_path,
    grok_profile,
    opencode_config_path,
    opencode_profile,
)
from .tool_catalog import TOOL_CONTRACTS, TOOL_NAMES

BEGIN = "# BEGIN CYCLONE AGENT MCP"
END = "# END CYCLONE AGENT MCP"


@dataclass(frozen=True)
class ServerCommand:
    command: str
    args: list[str]


def _local_app_data() -> Path:
    raw = os.getenv("LOCALAPPDATA", "").strip()
    if raw:
        return Path(raw)
    return Path.home() / "AppData" / "Local"


def _looks_like_legacy_companion(command: str) -> bool:
    lowered = command.replace("/", "\\").lower()
    return "cyclone pc companion" in lowered or "cyclone-pc-companion" in lowered


def _one_mcp_executable() -> Path | None:
    env_exe = os.getenv("CYCLONE_AGENT_MCP_EXE", "").strip()
    if env_exe:
        path = Path(env_exe).expanduser()
        if not _looks_like_legacy_companion(str(path)):
            return path
    installed = _local_app_data() / "Cyclone One" / "CycloneAgentMCP.exe"
    if installed.is_file():
        return installed
    return None


def _prefer_one_command(command: str, args: list[str]) -> ServerCommand:
    if not _looks_like_legacy_companion(command):
        return ServerCommand(command, args)
    one = _one_mcp_executable()
    if one is not None:
        return ServerCommand(str(one.resolve() if one.exists() else one), ["serve"])
    return ServerCommand(sys.executable, ["-m", "cyclone_agent_mcp", "serve"])


def resolve_server_command(explicit: str | None = None) -> ServerCommand:
    if explicit:
        return _prefer_one_command(str(Path(explicit).expanduser().resolve()), ["serve"])
    env_exe = os.getenv("CYCLONE_AGENT_MCP_EXE")
    if env_exe:
        command = str(Path(env_exe).expanduser().resolve())
        if not _looks_like_legacy_companion(command):
            return ServerCommand(command, ["serve"])
    if getattr(sys, "frozen", False):
        command = str(Path(sys.executable).resolve())
        if not _looks_like_legacy_companion(command):
            return ServerCommand(command, ["serve"])
    one = _one_mcp_executable()
    if one is not None and one.is_file():
        return ServerCommand(str(one.resolve()), ["serve"])
    sibling = Path(sys.executable).with_name("CycloneAgentMCP.exe")
    if os.name == "nt" and sibling.exists() and not _looks_like_legacy_companion(str(sibling)):
        return ServerCommand(str(sibling.resolve()), ["serve"])
    return ServerCommand(sys.executable, ["-m", "cyclone_agent_mcp", "serve"])


def connect(host: str, *, dry_run: bool = False, executable: str | None = None) -> dict[str, Any]:
    from .adapters import get_adapter

    adapter = get_adapter(host)
    adapter.detect()
    result = adapter.connect(dry_run=dry_run, executable=executable)
    return result


def apply_connect(host: str, *, dry_run: bool = False, executable: str | None = None) -> dict[str, Any]:
    server = resolve_server_command(executable)
    if host == "codex":
        path = codex_config_path()
        snippet = codex_toml(server.command, server.args)
        changed = _codex_candidate(path, snippet) != (path.read_text(encoding="utf-8") if path.exists() else "")
        if not dry_run:
            changed = _write_codex_block(path, snippet)
        return {
            "host": host,
            "installed": host_installed(host),
            "path": str(path),
            "dry_run": dry_run,
            "changed": changed,
            "restart_required": changed and not dry_run,
            "configuration": snippet,
        }
    if host == "opencode":
        path = opencode_config_path()
        profile = opencode_profile(server.command, server.args)
        if not dry_run:
            _merge_json(path, profile)
        return {"host": host, "installed": host_installed(host), "path": str(path), "dry_run": dry_run, "configuration": profile}
    if host == "copilot":
        path = copilot_config_path()
        profile = copilot_profile(server.command, server.args)
        if not dry_run:
            _merge_json(path, profile)
        return {"host": host, "installed": host_installed(host), "path": str(path), "dry_run": dry_run, "configuration": profile}
    if host == "cursor":
        path = cursor_mcp_path()
        server = _prefer_one_command(server.command, server.args)
        profile = cursor_profile(server.command, server.args, env=_cursor_gateway_env())
        if not dry_run:
            _merge_cursor_profile(path, profile)
        return {
            "host": host,
            "installed": host_installed(host),
            "path": str(path),
            "dry_run": dry_run,
            "configuration": profile,
        }
    if host == "grok":
        path = grok_config_path()
        server = _prefer_one_command(server.command, server.args)
        profile = grok_profile(server.command, server.args)
        if not dry_run:
            _merge_cursor_profile(path, profile)
        return {
            "host": host,
            "installed": host_installed(host),
            "path": str(path),
            "dry_run": dry_run,
            "configuration": profile,
        }
    if host == "generic":
        return {"host": host, "path": None, "dry_run": True, "configuration": generic_profile(server.command, server.args)}
    raise ValueError(f"Unsupported connector host: {host}")


def disconnect(host: str, *, dry_run: bool = False) -> dict[str, Any]:
    from .adapters import get_adapter

    adapter = get_adapter(host)
    return adapter.disconnect(dry_run=dry_run)


def apply_disconnect(host: str, *, dry_run: bool = False) -> dict[str, Any]:
    if host == "codex":
        path = codex_config_path()
        before = path.read_text(encoding="utf-8") if path.exists() else ""
        after = _remove_codex_block(before)
        if not dry_run and before != after:
            try:
                tomllib.loads(after)
            except tomllib.TOMLDecodeError as exc:
                raise ValueError(f"Codex configuration is not valid TOML; Cyclone left it unchanged: {exc}") from exc
            _write_text_atomic(path, after)
        return {"host": host, "path": str(path), "dry_run": dry_run, "changed": before != after}
    if host == "opencode":
        return _disconnect_json(host, opencode_config_path(), ("mcp", "servers", SERVER_KEY), dry_run)
    if host == "copilot":
        return _disconnect_json(host, copilot_config_path(), ("mcpServers", SERVER_KEY), dry_run)
    if host == "cursor":
        return _disconnect_json(host, cursor_mcp_path(), ("mcpServers", SERVER_KEY), dry_run)
    if host == "grok":
        return _disconnect_json(host, grok_config_path(), ("mcpServers", SERVER_KEY), dry_run)
    if host == "generic":
        return {"host": host, "path": None, "dry_run": True, "changed": False}
    raise ValueError(f"Unsupported connector host: {host}")


def host_installed(host: str) -> bool:
    if host == "cursor":
        if (Path.home() / ".cursor").exists():
            return True
        return shutil.which("cursor") is not None or shutil.which("cursor.exe") is not None
    if host == "grok":
        if (Path.home() / ".grok").exists():
            return True
        if shutil.which("grok") is not None or shutil.which("grok.exe") is not None:
            return True
        local_app_data = os.getenv("LOCALAPPDATA", "").strip()
        if local_app_data and (Path(local_app_data) / "Grok").exists():
            return True
        return False
    executable = {"codex": "codex", "opencode": "opencode", "copilot": "copilot"}.get(host)
    if host == "codex":
        if shutil.which("codex") is not None or codex_config_path().parent.exists():
            return True
        local_app_data = os.getenv("LOCALAPPDATA", "").strip()
        packages = Path(local_app_data) / "Packages" if local_app_data else None
        if packages and packages.is_dir():
            try:
                return any(packages.glob("OpenAI.Codex_*"))
            except OSError:
                return False
    return True if executable is None else shutil.which(executable) is not None


async def _verify_async(command: ServerCommand) -> dict[str, dict[str, Any]]:
    from mcp import Client, StdioServerParameters
    from mcp.client.stdio import stdio_client

    # The MCP SDK intentionally starts child processes with a curated environment and does not
    # inherit PYTHONPATH.  Preserve the source checkout when verification is run before the
    # package has been installed (the normal contributor/CI path), while retaining any caller
    # supplied import roots for packaged or embedded use.
    source_root = str(Path(__file__).resolve().parents[1])
    inherited_pythonpath = os.getenv("PYTHONPATH", "")
    pythonpath = os.pathsep.join(part for part in (source_root, inherited_pythonpath) if part)
    params = StdioServerParameters(
        command=command.command,
        args=command.args,
        env={"PYTHONPATH": pythonpath},
    )
    async with Client(stdio_client(params)) as client:
        result = await client.list_tools()
        return {tool.name: dict(tool.input_schema) for tool in result.tools}


def verify_tools_list(executable: str | None = None) -> dict[str, Any]:
    command = resolve_server_command(executable)
    definitions = asyncio.run(_verify_async(command))
    discovered = sorted(definitions)
    expected = sorted(_expected_tool_names())
    phone_scoped = {contract.name: contract.phone_scoped for contract in TOOL_CONTRACTS}
    schema_errors: list[str] = []
    for name, schema in sorted(definitions.items()):
        properties = schema.get("properties", {}) if isinstance(schema, dict) else {}
        requires_device = phone_scoped.get(name, name != "phone_devices")
        if not requires_device:
            if "device_id" in properties:
                schema_errors.append(f"{name}_must_not_accept_device_id")
            if name == "phone_group_act" and "device_ids" not in properties:
                schema_errors.append("phone_group_act_missing_device_ids")
            if name in {"phone_lab_report", "phone_lab_stop"} and "experiment_id" not in properties:
                schema_errors.append(f"{name}_missing_experiment_id")
        elif name in expected and "device_id" not in properties:
            schema_errors.append(f"{name}_missing_device_id")
    return {
        "ok": discovered == expected and not schema_errors,
        "tools": discovered,
        "expected": expected,
        "schema_errors": schema_errors,
    }


def _expected_tool_names() -> tuple[str, ...]:
    """Use the canonical phone surface when this connector is inside the Companion binary."""
    if getattr(sys, "frozen", False):
        from cyclone_phone_mcp.mcp_server import TOOLS

        return tuple(str(tool["name"]) for tool in TOOLS)
    return TOOL_NAMES


def _codex_candidate(path: Path, snippet: str) -> str:
    current = path.read_text(encoding="utf-8") if path.exists() else ""
    without = _remove_codex_block(current).rstrip()
    return (without + "\n\n" if without else "") + snippet


def _write_codex_block(path: Path, snippet: str) -> bool:
    path.parent.mkdir(parents=True, exist_ok=True)
    current = path.read_text(encoding="utf-8") if path.exists() else ""
    text = _codex_candidate(path, snippet)
    if text == current:
        return False
    try:
        tomllib.loads(text)
    except tomllib.TOMLDecodeError as exc:
        raise ValueError(f"Codex configuration is not valid TOML; Cyclone left it unchanged: {exc}") from exc
    _write_text_atomic(path, text)
    return True


def _remove_codex_block(text: str) -> str:
    start = text.find(BEGIN)
    if start < 0:
        return text
    end = text.find(END, start)
    if end < 0:
        return text
    end = text.find("\n", end)
    if end < 0:
        end = len(text)
    else:
        end += 1
    return (text[:start] + text[end:]).strip() + ("\n" if text[:start] + text[end:] else "")


def _write_text_atomic(path: Path, text: str) -> None:
    temporary = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
    try:
        temporary.write_text(text, encoding="utf-8")
        os.replace(temporary, path)
    finally:
        try:
            temporary.unlink(missing_ok=True)
        except OSError:
            pass


def _cursor_gateway_env() -> dict[str, str]:
    sourced: dict[str, str] = {}
    public: dict[str, str] = {}
    try:
        from cyclone_phone_mcp.tooling import apply_gateway_env

        public = apply_gateway_env(sourced)
    except Exception:
        sourced = {}
        public = {}
    env: dict[str, str] = {}
    for key in ("CYCLONE_DEVICE_GATEWAY_URL", "CYCLONE_DEVICE_GATEWAY_PORT", "CYCLONE_DEVICE_GATEWAY_RUNTIME"):
        value = (os.getenv(key) or sourced.get(key) or public.get(key) or "").strip()
        if value:
            env[key] = value
    env.pop("CYCLONE_DEVICE_GATEWAY_TOKEN", None)
    return env


def _merge_cursor_profile(path: Path, overlay: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    current: dict[str, Any] = {}
    if path.exists():
        loaded = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(loaded, dict):
            raise ValueError(f"Configuration root is not an object: {path}")
        current = loaded
    servers = current.get("mcpServers")
    if not isinstance(servers, dict):
        servers = {}
        current["mcpServers"] = servers
    server = dict((overlay.get("mcpServers") or {}).get(SERVER_KEY) or {})
    env = dict(server.get("env") or {})
    env.pop("CYCLONE_DEVICE_GATEWAY_TOKEN", None)
    server["env"] = env
    servers[SERVER_KEY] = server
    path.write_text(json.dumps(current, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def _merge_json(path: Path, overlay: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    current: dict[str, Any] = {}
    if path.exists():
        loaded = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(loaded, dict):
            raise ValueError(f"Configuration root is not an object: {path}")
        current = loaded
    _deep_merge(current, overlay)
    path.write_text(json.dumps(current, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def _deep_merge(target: dict[str, Any], overlay: dict[str, Any]) -> None:
    for key, value in overlay.items():
        if isinstance(value, dict) and isinstance(target.get(key), dict):
            _deep_merge(target[key], value)
        else:
            target[key] = value


def _disconnect_json(host: str, path: Path, keys: tuple[str, ...], dry_run: bool) -> dict[str, Any]:
    if not path.exists():
        return {"host": host, "path": str(path), "dry_run": dry_run, "changed": False}
    loaded = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(loaded, dict):
        raise ValueError(f"Configuration root is not an object: {path}")
    cursor: Any = loaded
    for key in keys[:-1]:
        if not isinstance(cursor, dict) or key not in cursor:
            return {"host": host, "path": str(path), "dry_run": dry_run, "changed": False}
        cursor = cursor[key]
    changed = isinstance(cursor, dict) and cursor.pop(keys[-1], None) is not None
    if changed and not dry_run:
        path.write_text(json.dumps(loaded, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return {"host": host, "path": str(path), "dry_run": dry_run, "changed": changed}
