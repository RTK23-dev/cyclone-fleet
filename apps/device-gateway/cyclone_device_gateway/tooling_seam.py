"""Cyclone One 1.1 tooling seam: persist the PC gateway bearer for local tools.

Local MCP, doctor and Cursor attach through a DPAPI (Windows) or 0600 runtime
file plus a token-free locator. Tools must not scrape CyclonePCRuntime process
environment. Tokens are never written into Cursor mcp.json, doctor output or
the public locator.
"""

from __future__ import annotations

import ctypes
import json
import os
from pathlib import Path
import stat
import sys
from typing import Any, Mapping
from urllib.parse import urlparse

CRYPTPROTECT_UI_FORBIDDEN = 0x1
DEFAULT_GATEWAY_URL = "http://127.0.0.1:8765"
DEFAULT_GATEWAY_PORT = 8765
LOCATOR_SCHEMA = "cyclone.one.gateway.locator.v1"
PRODUCT_ONE = "Cyclone One"
PRODUCT_LEGACY_COMPANION = "Cyclone PC Companion"
CURSOR_SERVER_KEY = "cyclone-phone"
LEGACY_COMPANION_DIR_NAMES = (
    "Cyclone PC Companion",
    "cyclone-pc-companion",
    "CyclonePCCompanion",
)

if os.name == "nt":
    from ctypes import wintypes

    class DATA_BLOB(ctypes.Structure):
        _fields_ = [("cbData", wintypes.DWORD), ("pbData", ctypes.POINTER(ctypes.c_byte))]
else:
    DATA_BLOB = None  # type: ignore[misc, assignment]


def _isolated_tooling_root() -> bool:
    return bool(os.getenv("CYCLONE_TOOLING_TEST_ROOT", "").strip())


def _uses_dpapi_token() -> bool:
    """Windows DPAPI bearer file unless an isolated tooling test root is set.

    Tests must not patch ``os.name`` to force the JSON path: Python 3.13 pathlib
    then tries to construct PosixPath on Windows and raises.
    """
    return os.name == "nt" and not _isolated_tooling_root()


def token_filename() -> str:
    return "gateway-token.dpapi" if _uses_dpapi_token() else "gateway-token.json"


def local_app_data() -> Path:
    override = os.getenv("CYCLONE_TOOLING_TEST_ROOT", "").strip()
    if override:
        root = Path(override).expanduser()
        root.mkdir(parents=True, exist_ok=True)
        return root
    raw = os.getenv("LOCALAPPDATA", "").strip()
    if raw:
        return Path(raw)
    return Path.home() / "AppData" / "Local"


def one_install_dir() -> Path:
    return local_app_data() / PRODUCT_ONE


def one_runtime_dir() -> Path:
    configured = os.getenv("CYCLONE_DEVICE_GATEWAY_RUNTIME", "").strip()
    if configured:
        path = Path(configured).expanduser()
        path.mkdir(parents=True, exist_ok=True)
        return path
    path = one_install_dir() / "runtime"
    path.mkdir(parents=True, exist_ok=True)
    return path


def token_path() -> Path:
    return one_runtime_dir() / token_filename()


def legacy_token_path() -> Path:
    return local_app_data() / "Cyclone" / "pc-companion" / token_filename()


def locator_path() -> Path:
    return one_runtime_dir() / "gateway-locator.json"


def cursor_mcp_path() -> Path:
    override = os.getenv("CYCLONE_CURSOR_MCP_PATH", "").strip()
    if override:
        return Path(override).expanduser()
    return Path.home() / ".cursor" / "mcp.json"


def legacy_companion_dirs() -> list[Path]:
    roots = [local_app_data()]
    program_files = os.getenv("ProgramFiles", "").strip()
    program_files_x86 = os.getenv("ProgramFiles(x86)", "").strip()
    for raw in (program_files, program_files_x86):
        if raw:
            roots.append(Path(raw))
    found: list[Path] = []
    seen: set[str] = set()
    for root in roots:
        for name in LEGACY_COMPANION_DIR_NAMES:
            candidate = root / name
            key = str(candidate).lower()
            if key in seen:
                continue
            seen.add(key)
            if candidate.is_dir():
                found.append(candidate)
    return found


def detect_legacy_companion() -> dict[str, Any]:
    paths = legacy_companion_dirs()
    one = one_install_dir()
    one_present = one.is_dir()
    version_hint = None
    for path in paths:
        version_hint = _legacy_version_hint(path) or version_hint
    present = bool(paths)
    beside_one = present and one_present
    return {
        "present": present,
        "besideOne": beside_one,
        "product": PRODUCT_LEGACY_COMPANION,
        "preferredProduct": PRODUCT_ONE,
        "preferredPath": str(one),
        "paths": [str(path) for path in paths],
        "versionHint": version_hint,
        "warn": beside_one,
        "detail": _legacy_detail(present, beside_one, version_hint),
    }


def session_secret_persisted() -> bool:
    connection = load_connection(include_env=False)
    return bool(connection and connection.get("token"))


def save_connection(
    token: str,
    url: str,
    *,
    port: int | None = None,
    runtime: str | Path | None = None,
    mcp_executable: str | Path | None = None,
) -> dict[str, Any]:
    value = token.strip()
    if not value:
        return load_locator() or {}
    safe_url = _safe_loopback_url(url) or DEFAULT_GATEWAY_URL
    resolved_port = int(port or _port_from_url(safe_url) or DEFAULT_GATEWAY_PORT)
    runtime_dir = Path(runtime).expanduser() if runtime else one_runtime_dir()
    runtime_dir.mkdir(parents=True, exist_ok=True)
    payload = json.dumps(
        {"version": 1, "token": value, "url": safe_url, "port": resolved_port},
        separators=(",", ":"),
    ).encode("utf-8")
    path = runtime_dir / token_filename()
    path.parent.mkdir(parents=True, exist_ok=True)
    _write_secret_bytes(path, payload)
    locator = {
        "schema": LOCATOR_SCHEMA,
        "product": PRODUCT_ONE,
        "url": safe_url,
        "port": resolved_port,
        "runtime": str(runtime_dir),
        "mcpExecutable": str(mcp_executable) if mcp_executable else _resolve_mcp_executable(),
        "sessionSecretPersisted": True,
        "tokenPath": str(path),
        "tokenStorage": "dpapi" if _uses_dpapi_token() else "runtime-file",
    }
    _write_json(runtime_dir / "gateway-locator.json", locator)
    return locator


def load_connection(*, include_env: bool = True) -> dict[str, str] | None:
    if include_env:
        env_token = os.getenv("CYCLONE_DEVICE_GATEWAY_TOKEN", "").strip()
        env_url = _safe_loopback_url(os.getenv("CYCLONE_DEVICE_GATEWAY_URL", ""))
        if env_token:
            locator = load_locator() or {}
            url = env_url or str(locator.get("url") or DEFAULT_GATEWAY_URL)
            return {
                "token": env_token,
                "url": url,
                "port": str(locator.get("port") or _port_from_url(url) or DEFAULT_GATEWAY_PORT),
                "runtime": str(locator.get("runtime") or one_runtime_dir()),
            }
    for path in (token_path(), legacy_token_path()):
        loaded = _load_secret_file(path)
        if loaded:
            locator = load_locator() or {}
            url = loaded.get("url") or locator.get("url") or DEFAULT_GATEWAY_URL
            return {
                "token": loaded["token"],
                "url": str(url),
                "port": str(loaded.get("port") or locator.get("port") or _port_from_url(str(url)) or DEFAULT_GATEWAY_PORT),
                "runtime": str(locator.get("runtime") or one_runtime_dir()),
            }
    return None


def load_locator() -> dict[str, Any] | None:
    path = locator_path()
    if not path.is_file():
        return None
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError, UnicodeDecodeError):
        return None
    if not isinstance(payload, dict):
        return None
    url = _safe_loopback_url(str(payload.get("url") or ""))
    if not url:
        return None
    public = dict(payload)
    public.pop("token", None)
    public["url"] = url
    public["port"] = int(payload.get("port") or _port_from_url(url) or DEFAULT_GATEWAY_PORT)
    public["sessionSecretPersisted"] = bool(payload.get("sessionSecretPersisted", True))
    return public


def load_token() -> str | None:
    connection = load_connection()
    return connection.get("token") if connection else None


def save_token(token: str) -> None:
    save_connection(token, os.getenv("CYCLONE_DEVICE_GATEWAY_URL", DEFAULT_GATEWAY_URL))


def apply_gateway_env(env: dict[str, str] | None = None) -> dict[str, str]:
    """Inject CYCLONE_DEVICE_GATEWAY_{TOKEN,URL,PORT,RUNTIME} when missing.

    Existing process values win. The bearer is loaded from DPAPI / runtime file;
    URL/port/runtime come from the token-free locator.
    """
    target = os.environ if env is None else env
    connection = load_connection(include_env=False)
    locator = load_locator() or {}
    token = (target.get("CYCLONE_DEVICE_GATEWAY_TOKEN") or "").strip()
    if not token and connection:
        token = connection["token"]
        target["CYCLONE_DEVICE_GATEWAY_TOKEN"] = token
    url = (target.get("CYCLONE_DEVICE_GATEWAY_URL") or "").strip()
    if not url:
        url = str((connection or {}).get("url") or locator.get("url") or DEFAULT_GATEWAY_URL)
        target["CYCLONE_DEVICE_GATEWAY_URL"] = url
    port = (target.get("CYCLONE_DEVICE_GATEWAY_PORT") or "").strip()
    if not port:
        target["CYCLONE_DEVICE_GATEWAY_PORT"] = str(
            locator.get("port") or (connection or {}).get("port") or _port_from_url(url) or DEFAULT_GATEWAY_PORT
        )
    runtime = (target.get("CYCLONE_DEVICE_GATEWAY_RUNTIME") or "").strip()
    if not runtime:
        target["CYCLONE_DEVICE_GATEWAY_RUNTIME"] = str(locator.get("runtime") or one_runtime_dir())
    return {
        "CYCLONE_DEVICE_GATEWAY_URL": target.get("CYCLONE_DEVICE_GATEWAY_URL", ""),
        "CYCLONE_DEVICE_GATEWAY_PORT": target.get("CYCLONE_DEVICE_GATEWAY_PORT", ""),
        "CYCLONE_DEVICE_GATEWAY_RUNTIME": target.get("CYCLONE_DEVICE_GATEWAY_RUNTIME", ""),
        "sessionSecretPersisted": "true" if (token or session_secret_persisted()) else "false",
    }


def persist_runtime_bearer(
    token: str,
    url: str,
    *,
    port: int | None = None,
    runtime: str | Path | None = None,
    mcp_executable: str | Path | None = None,
    write_cursor: bool = True,
) -> dict[str, Any]:
    locator = save_connection(
        token,
        url,
        port=port,
        runtime=runtime,
        mcp_executable=mcp_executable,
    )
    apply_gateway_env()
    cursor = None
    if write_cursor:
        cursor = write_cursor_mcp_json(
            command=locator.get("mcpExecutable") or resolve_one_mcp_executable(),
            url=locator.get("url") or url,
            port=int(locator.get("port") or port or DEFAULT_GATEWAY_PORT),
            runtime=locator.get("runtime") or str(one_runtime_dir()),
        )
    return {"locator": locator, "cursor": cursor, "sessionSecretPersisted": True}


def resolve_one_mcp_executable() -> str | None:
    env_exe = os.getenv("CYCLONE_AGENT_MCP_EXE", "").strip()
    if env_exe:
        return str(Path(env_exe).expanduser())
    if getattr(sys, "frozen", False):
        current = Path(sys.executable).resolve()
        if current.name.lower() == "cycloneagentmcp.exe":
            return str(current)
        sibling = current.with_name("CycloneAgentMCP.exe")
        if sibling.is_file():
            return str(sibling)
    installed = one_install_dir() / "CycloneAgentMCP.exe"
    if installed.is_file():
        return str(installed)
    return None


def write_cursor_mcp_json(
    *,
    command: str | None,
    args: list[str] | None = None,
    url: str | None = None,
    port: int | None = None,
    runtime: str | None = None,
    path: Path | None = None,
) -> dict[str, Any]:
    """Write/merge ~/.cursor/mcp.json pointing at Cyclone One, never at legacy Companion.

    Gateway token is intentionally omitted; CycloneAgentMCP loads it from DPAPI.
    """
    target = path or cursor_mcp_path()
    executable = command or resolve_one_mcp_executable() or "CycloneAgentMCP.exe"
    if _looks_like_legacy_companion(executable):
        one_exe = resolve_one_mcp_executable()
        if one_exe:
            executable = one_exe
    locator = load_locator() or {}
    safe_url = _safe_loopback_url(url or str(locator.get("url") or "")) or DEFAULT_GATEWAY_URL
    resolved_port = int(port or locator.get("port") or _port_from_url(safe_url) or DEFAULT_GATEWAY_PORT)
    resolved_runtime = str(runtime or locator.get("runtime") or one_runtime_dir())
    server = {
        "command": executable,
        "args": list(args or ["serve"]),
        "env": {
            "CYCLONE_DEVICE_GATEWAY_URL": safe_url,
            "CYCLONE_DEVICE_GATEWAY_PORT": str(resolved_port),
            "CYCLONE_DEVICE_GATEWAY_RUNTIME": resolved_runtime,
        },
    }
    current: dict[str, Any] = {}
    if target.is_file():
        try:
            loaded = json.loads(target.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError, UnicodeDecodeError):
            loaded = {}
        if isinstance(loaded, dict):
            current = loaded
    servers = current.get("mcpServers")
    if not isinstance(servers, dict):
        servers = {}
        current["mcpServers"] = servers
    previous = servers.get(CURSOR_SERVER_KEY)
    servers[CURSOR_SERVER_KEY] = server
    changed = previous != server
    if changed:
        target.parent.mkdir(parents=True, exist_ok=True)
        _write_json(target, current)
    env_blob = json.dumps(server.get("env") or {})
    if "CYCLONE_DEVICE_GATEWAY_TOKEN" in env_blob:
        raise RuntimeError("Cursor mcp.json must not embed CYCLONE_DEVICE_GATEWAY_TOKEN")
    return {
        "path": str(target),
        "changed": changed,
        "server": CURSOR_SERVER_KEY,
        "command": executable,
        "legacyCompanion": _looks_like_legacy_companion(executable),
    }


def public_locator_summary() -> dict[str, Any]:
    locator = load_locator() or {}
    legacy = detect_legacy_companion()
    summary = {
        "schema": LOCATOR_SCHEMA,
        "product": PRODUCT_ONE,
        "sessionSecretPersisted": session_secret_persisted(),
        "url": locator.get("url"),
        "port": locator.get("port"),
        "runtime": locator.get("runtime"),
        "mcpExecutable": locator.get("mcpExecutable") or resolve_one_mcp_executable(),
        "legacyCompanion": {
            "present": legacy["present"],
            "besideOne": legacy["besideOne"],
            "warn": legacy["warn"],
            "versionHint": legacy["versionHint"],
        },
    }
    blob = json.dumps(summary)
    if "CYCLONE_DEVICE_GATEWAY_TOKEN" in blob:
        raise RuntimeError("locator summary leaked a token env name with a value")
    return summary


def _resolve_mcp_executable() -> str | None:
    return resolve_one_mcp_executable()


def _legacy_version_hint(path: Path) -> str | None:
    for candidate in (
        path / "version.txt",
        path / "resources" / "version.txt",
        path / "RELEASE.txt",
    ):
        if candidate.is_file():
            try:
                text = candidate.read_text(encoding="utf-8", errors="replace").strip().splitlines()
            except OSError:
                continue
            if text:
                return text[0][:32]
    name = path.name.lower()
    if "3.8" in name:
        return "3.8.x"
    return "3.8.x" if path.is_dir() else None


def _legacy_detail(present: bool, beside_one: bool, version_hint: str | None) -> str:
    if beside_one:
        version = version_hint or "3.8.x"
        return (
            f"{PRODUCT_LEGACY_COMPANION} {version} is installed beside {PRODUCT_ONE}. "
            f"Prefer {PRODUCT_ONE}; uninstall the legacy companion to avoid MCP/path confusion."
        )
    if present:
        version = version_hint or "3.8.x"
        return f"{PRODUCT_LEGACY_COMPANION} {version} is installed. Prefer {PRODUCT_ONE}."
    return f"No legacy {PRODUCT_LEGACY_COMPANION} install detected"


def _looks_like_legacy_companion(command: str) -> bool:
    lowered = command.replace("/", "\\").lower()
    return "cyclone pc companion" in lowered or "cyclone-pc-companion" in lowered


def _safe_loopback_url(value: str) -> str | None:
    text = (value or "").strip()
    if not text:
        return None
    try:
        parsed = urlparse(text)
        if parsed.scheme != "http" or parsed.hostname not in {"127.0.0.1", "localhost", "::1"} or parsed.port is None:
            return None
    except ValueError:
        return None
    return f"http://127.0.0.1:{parsed.port}"


def _port_from_url(url: str) -> int | None:
    try:
        parsed = urlparse(url)
    except ValueError:
        return None
    return parsed.port


def _write_json(path: Path, payload: Mapping[str, Any]) -> None:
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(dict(payload), indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    temporary.replace(path)


def _write_secret_bytes(path: Path, payload: bytes) -> None:
    encrypted = _protect(payload) if _uses_dpapi_token() else payload
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_bytes(encrypted)
    if not _uses_dpapi_token():
        os.chmod(temporary, stat.S_IRUSR | stat.S_IWUSR)
    temporary.replace(path)


def _load_secret_file(path: Path) -> dict[str, Any] | None:
    if not path.is_file():
        return None
    try:
        raw = path.read_bytes()
        text = (_unprotect(raw) if _uses_dpapi_token() and path.suffix == ".dpapi" else raw).decode("utf-8").strip()
    except Exception:
        return None
    if not text:
        return None
    try:
        payload = json.loads(text)
    except json.JSONDecodeError:
        return {"token": text, "url": DEFAULT_GATEWAY_URL}
    if not isinstance(payload, dict):
        return None
    token = str(payload.get("token") or "").strip()
    url = _safe_loopback_url(str(payload.get("url") or ""))
    if not token:
        return None
    result: dict[str, Any] = {"token": token, "url": url or DEFAULT_GATEWAY_URL}
    if payload.get("port") is not None:
        result["port"] = payload["port"]
    return result


def _blob(data: bytes):
    buffer = ctypes.create_string_buffer(data)
    blob = DATA_BLOB(len(data), ctypes.cast(buffer, ctypes.POINTER(ctypes.c_byte)))
    return buffer, blob


def _protect(data: bytes) -> bytes:
    if os.name != "nt":
        return data
    _, source = _blob(data)
    output = DATA_BLOB()
    crypt32 = ctypes.windll.crypt32
    kernel32 = ctypes.windll.kernel32
    if not crypt32.CryptProtectData(
        ctypes.byref(source), None, None, None, None, CRYPTPROTECT_UI_FORBIDDEN, ctypes.byref(output)
    ):
        raise ctypes.WinError()
    try:
        return ctypes.string_at(output.pbData, output.cbData)
    finally:
        kernel32.LocalFree(output.pbData)


def _unprotect(data: bytes) -> bytes:
    if os.name != "nt":
        return data
    _, source = _blob(data)
    output = DATA_BLOB()
    crypt32 = ctypes.windll.crypt32
    kernel32 = ctypes.windll.kernel32
    if not crypt32.CryptUnprotectData(
        ctypes.byref(source), None, None, None, None, CRYPTPROTECT_UI_FORBIDDEN, ctypes.byref(output)
    ):
        raise ctypes.WinError()
    try:
        return ctypes.string_at(output.pbData, output.cbData)
    finally:
        kernel32.LocalFree(output.pbData)
